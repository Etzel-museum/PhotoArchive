package com.davidlevi.photoarchive

import android.os.Environment
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Imports Google Takeout zips of Google Photos into the archive, one part at a time:
 * extract → check → add (dates from Takeout's JSON files, duplicates dropped) → delete the zip.
 */
object Takeout {
    class Part(val file: File, val exportId: String, val number: Int)

    /** One Takeout export (all parts share its id). [total] is the number of parts the email listed; 0 = unknown. */
    class Export {
        val parts = sortedSetOf<Int>()
        var total = 0
        var items = 0
        var dups = 0
        var bytes = 0L

        val missing: List<Int> get() = (1..maxOf(total, parts.maxOrNull() ?: 0)).filter { it !in parts }
        val complete: Boolean get() = total > 0 && missing.isEmpty()

        fun toJson(): JSONObject = JSONObject().put("parts", JSONArray(parts.toList())).put("total", total)
            .put("items", items).put("dups", dups).put("bytes", bytes)

        companion object {
            fun from(o: JSONObject) = Export().apply {
                o.optJSONArray("parts")?.let { a -> for (i in 0 until a.length()) parts += a.getInt(i) }
                total = o.optInt("total")
                items = o.optInt("items")
                dups = o.optInt("dups")
                bytes = o.optLong("bytes")
            }
        }
    }

    /** What was imported so far; kept in the archive (.takeout.json) so it survives reinstalling the app. */
    class State(private val file: File) {
        /** "<folder>/<file name>" and "<file name>" -> photoTakenTime from Takeout's JSON files. */
        val meta = HashMap<String, Long>()
        /** Archive path -> Takeout entry name, for files imported before their JSON (in another part) was seen. */
        val undated = HashMap<String, String>()
        val exports = HashMap<String, Export>()

        init {
            runCatching {
                val o = JSONObject(file.readText())
                o.optJSONObject("meta")?.let { m -> for (k in m.keys()) meta[k] = m.getLong(k) }
                o.optJSONObject("undated")?.let { m -> for (k in m.keys()) undated[k] = m.getString(k) }
                o.optJSONObject("exports")?.let { m -> for (k in m.keys()) exports[k] = Export.from(m.getJSONObject(k)) }
            }
        }

        fun save() {
            val o = JSONObject()
            o.put("meta", JSONObject().apply { for ((k, v) in meta) put(k, v) })
            o.put("undated", JSONObject().apply { for ((k, v) in undated) put(k, v) })
            o.put("exports", JSONObject().apply { for ((k, v) in exports) put(k, v.toJson()) })
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }

        fun export(id: String): Export = exports.getOrPut(id) { Export() }

        /** Taken time for a media entry: same folder first, then any folder; also tries "(1)" and "-edited" variants. */
        fun lookup(entryName: String): Long? {
            val f = File(entryName)
            val dir = f.parentFile?.name ?: ""
            val plain = f.name.replace(copyNo, "")
            val names = listOf(f.name, plain, plain.replace(edited, ""))
            for (n in names) meta["$dir/$n"]?.let { return it }
            for (n in names) meta[n]?.let { return it }
            return null
        }
    }

    // takeout-20261116T101010Z-001.zip, takeout-20261116T101010Z-3-001.zip, "… (1).zip" from a repeated download
    private val partName = Regex("^takeout-(.+?)-(\\d{3})(?:\\s*\\(\\d+\\))?\\.zip$", RegexOption.IGNORE_CASE)
    private val copyNo = Regex("\\(\\d+\\)(?=\\.[^.]+$)")
    private val edited = Regex("-edited(?=\\.[^.]+$)", RegexOption.IGNORE_CASE)
    private val jsonCopyNo = Regex("\\((\\d+)\\)$")

    fun stateOf(archive: Archive) = State(File(archive.root, ".takeout.json"))

    fun parse(f: File): Part? =
        partName.find(f.name)?.let { Part(f, it.groupValues[1], it.groupValues[2].toInt()) }

    /** Takeout zips in Downloads (and folders under it). */
    fun findParts(): List<Part> {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return dl.walkTopDown().maxDepth(3).filter { it.isFile }.mapNotNull { parse(it) }.toList()
            .sortedWith(compareBy<Part>({ it.exportId }, { it.number }))
    }

    /** Date shown for an export id like "20261116T101010Z". */
    fun exportDate(id: String): String = Media.dateFromName(id)?.let { fmtDate(it) } ?: id

    class Result {
        var items = 0
        var bytes = 0L
        var dups = 0
        var failed = 0
        var skipped = 0
        var dated = 0
        val done = ArrayList<Part>()
        val problems = ArrayList<String>()
    }

    fun import(
        archive: Archive,
        parts: List<Part>,
        progress: (what: String, done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean
    ): Result {
        val r = Result()
        archive.ensure()
        val st = stateOf(archive)

        // Pass 1: dates from every selected part, since a photo's JSON can sit in a different part than the photo.
        for (p in parts) {
            progress("קורא תאריכים – חלק ${p.number}", 0, 0)
            runCatching {
                ZipFile(p.file).use { z ->
                    for (e in z.entries()) {
                        if (!e.isDirectory && e.name.endsWith(".json", ignoreCase = true)) readMeta(z, e, st)
                    }
                }
            }
        }
        r.dated += fixUndated(archive, st)

        val sizes = archive.sizeIndex()
        val tmp = File(archive.root, ".import")
        tmp.deleteRecursively()
        tmp.mkdirs()

        for (p in parts) {
            if (cancelled()) break
            val z = try {
                ZipFile(p.file)
            } catch (e: Exception) {
                r.problems += "חלק ${p.number}: הקובץ פגום או לא הורד עד הסוף – צריך להוריד אותו שוב."
                continue
            }
            val exp = st.export(p.exportId)
            var partFailed = 0
            var finished = false
            z.use {
                val entries = z.entries().toList().filter { !it.isDirectory }
                val media = entries.filter { Media.isMedia(File(it.name)) }
                r.skipped += entries.count { !it.name.endsWith(".json", ignoreCase = true) && !Media.isMedia(File(it.name)) }
                val need = media.sumOf { maxOf(it.size, 0L) }
                val free = StatFs(archive.root.path).availableBytes
                if (free < need + (200L shl 20)) {
                    r.problems += "חלק ${p.number}: אין מספיק מקום פנוי (צריך ${fmtSize(need)}, פנוי ${fmtSize(free)})."
                    return@use
                }
                for ((i, e) in media.withIndex()) {
                    if (cancelled()) return@use
                    if (i % 10 == 0) progress("חלק ${p.number}", i, media.size)
                    val name = File(e.name).name
                    val t = freeName(tmp, name)
                    if (!extract(z, e, t)) { r.failed++; partFailed++; continue }
                    val exif = if (Media.isVideo(t)) null else Media.exifTime(t)
                    val meta = st.lookup(e.name)
                    val time = exif ?: meta ?: Media.knownTime(t)
                    if (exif == null && meta != null) Media.writeExifTime(t, meta)
                    val len = t.length()
                    val fallback = if (e.time > 0) e.time else System.currentTimeMillis()
                    val added = archive.add(t, time ?: fallback, Media.sourceByName(name), sizes) { dest ->
                        if (time == null) st.undated[archive.rel(dest)] = e.name
                    }
                    when (added) {
                        Archive.Added.MOVED -> { r.items++; r.bytes += len; exp.items++; exp.bytes += len }
                        Archive.Added.DUPLICATE -> { r.dups++; exp.dups++ }
                        Archive.Added.FAILED -> { r.failed++; partFailed++; t.delete() }
                    }
                    if (i % 200 == 199) { archive.saveIndex(); st.save() }
                }
                progress("חלק ${p.number}", media.size, media.size)
                finished = true
            }
            if (finished && partFailed == 0) {
                exp.parts += p.number
                r.done += p
                p.file.delete()
            } else if (finished) {
                r.problems += "חלק ${p.number}: ${fmtCount(partFailed)} קבצים לא יובאו. הקובץ לא נמחק – אפשר לייבא אותו שוב."
            }
            archive.saveIndex()
            st.save()
        }

        progress("מסדר תאריכים", 0, 0)
        r.dated += fixUndated(archive, st)
        archive.saveIndex()
        st.save()
        tmp.deleteRecursively()
        return r
    }

    /** Remembers photoTakenTime from one of Takeout's JSON files, under every name the photo may have. */
    private fun readMeta(z: ZipFile, e: ZipEntry, st: State) {
        if (e.size > 1_000_000) return
        val o = runCatching {
            JSONObject(z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) })
        }.getOrNull() ?: return
        val ts = o.optJSONObject("photoTakenTime")?.optString("timestamp")?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val t = ts * 1000
        val f = File(e.name)
        val dir = f.parentFile?.name ?: ""
        // The JSON's own name: "IMG_1.jpg.supplemental-metadata.json", "IMG_1.jpg(1).json", often cut short.
        var stem = f.name.removeSuffix(".json")
        val n = jsonCopyNo.find(stem)?.groupValues?.get(1)
        if (n != null) stem = stem.substringBeforeLast("(")
        val sup = stem.indexOf(".supp", ignoreCase = true)
        if (sup > 0) stem = stem.substring(0, sup)
        val keys = if (n != null && '.' in stem) {
            // A "(1)" copy: its title is the original's name, so only the numbered name is safe.
            listOf("${stem.substringBeforeLast('.')}($n).${stem.substringAfterLast('.')}")
        } else {
            listOfNotNull(o.optString("title").ifEmpty { null }, stem)
        }
        for (k in keys) {
            st.meta["$dir/$k"] = t
            st.meta[k] = t
        }
    }

    /** Extracts one entry and checks its size and CRC. */
    private fun extract(z: ZipFile, e: ZipEntry, dest: File): Boolean = try {
        val crc = CRC32()
        z.getInputStream(e).use { i ->
            dest.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                    o.write(buf, 0, n)
                }
            }
        }
        val ok = (e.crc == -1L || crc.value == e.crc) && (e.size == -1L || dest.length() == e.size)
        if (!ok) dest.delete()
        ok
    } catch (ex: Exception) {
        dest.delete()
        false
    }

    /** Files imported without a date get it once their JSON shows up (e.g. in a later part). */
    private fun fixUndated(archive: Archive, st: State): Int {
        var n = 0
        for ((rel, entry) in st.undated.entries.toList()) {
            val t = st.lookup(entry) ?: continue
            st.undated.remove(rel)
            val f = archive.setTime(rel, t) ?: continue
            Media.writeExifTime(f, t)
            n++
        }
        return n
    }
}
