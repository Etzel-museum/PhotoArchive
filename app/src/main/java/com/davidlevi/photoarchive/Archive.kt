package com.davidlevi.photoarchive

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The archive folder: YYYY/MM/<file>, hidden from the gallery and Google Photos by a .nomedia file.
 * .index.json remembers each file's taken date and source; .trash holds deleted files for 30 days.
 */
class Archive(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var root: File
        get() = File(prefs.getString("root", null) ?: defaultRoot().path)
        set(v) { prefs.edit().putString("root", v.path).apply() }

    /** Photos taken in the last [keepDays] days stay in the normal gallery when scanning. */
    var keepDays: Int
        get() = prefs.getInt("keepDays", 30)
        set(v) { prefs.edit().putInt("keepDays", v).apply() }

    fun defaultRoot() = File(Environment.getExternalStorageDirectory(), FOLDER)

    val trashDir get() = File(root, ".trash")
    val waBackupDir get() = File(root, "WhatsApp-backup")
    private val indexFile get() = File(root, ".index.json")
    private val trashIndexFile get() = File(trashDir, "index.json")

    /** Archive-relative path -> (taken time, source). */
    private val index = HashMap<String, Pair<Long, Src>>()
    private var indexFor: String? = null

    @Synchronized
    fun ensure() {
        root.mkdirs()
        val nm = File(root, ".nomedia")
        if (!nm.exists()) runCatching { nm.createNewFile() }
        if (indexFor != root.path) loadIndex()
    }

    private fun loadIndex() {
        index.clear()
        indexFor = root.path
        if (!indexFile.exists()) return
        runCatching {
            val o = JSONObject(indexFile.readText())
            for (k in o.keys()) {
                val e = o.getJSONArray(k)
                index[k] = e.getLong(0) to Src.of(e.getString(1))
            }
        }
    }

    @Synchronized
    fun saveIndex() {
        val o = JSONObject()
        for ((k, v) in index) o.put(k, JSONArray().put(v.first).put(v.second.key))
        val tmp = File(root, ".index.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(indexFile)
    }

    private fun rel(f: File) = f.path.removePrefix(root.path + "/")

    /** Every photo/video in the archive, newest first. */
    fun items(): List<MediaItem> {
        ensure()
        val out = ArrayList<MediaItem>()
        root.listFiles()?.forEach { y ->
            if (!y.isDirectory || !yearDir.matches(y.name)) return@forEach
            y.walkTopDown().filter { it.isFile && Media.isMedia(it) }.forEach { f ->
                val e = synchronized(this) { index[rel(f)] }
                out += MediaItem(
                    f,
                    e?.first ?: Media.dateFromName(f.name) ?: f.lastModified(),
                    e?.second ?: Media.sourceOf(f),
                    Media.isVideo(f),
                    f.length()
                )
            }
        }
        out.sortByDescending { it.time }
        return out
    }

    /** File size -> archive files of that size; used to spot files that are already archived. */
    fun sizeIndex(): MutableMap<Long, MutableList<File>> =
        items().groupByTo(HashMap<Long, MutableList<File>>(), { it.size }, { it.file })

    enum class Added { MOVED, DUPLICATE, FAILED }

    /**
     * Moves [f] into YYYY/MM of [time]. If an identical file is already archived,
     * [f] is just deleted (DUPLICATE). The caller saves the index when done.
     */
    fun add(f: File, time: Long, src: Src, sizes: MutableMap<Long, MutableList<File>>): Added {
        val len = f.length()
        sizes[len]?.forEach { old ->
            if (sameContent(old, f)) return if (f.delete()) Added.DUPLICATE else Added.FAILED
        }
        val z = zoned(time)
        val dir = File(root, String.format(Locale.US, "%04d/%02d", z.year, z.monthValue))
        dir.mkdirs()
        val dest = freeName(dir, f.name)
        if (!moveFile(f, dest)) return Added.FAILED
        synchronized(this) { index[rel(dest)] = time to src }
        sizes.getOrPut(len) { mutableListOf() } += dest
        return Added.MOVED
    }

    // ---- trash ----

    private fun readTrash(): JSONObject =
        runCatching { JSONObject(trashIndexFile.readText()) }.getOrElse { JSONObject() }

    private fun writeTrash(o: JSONObject) {
        trashDir.mkdirs()
        trashIndexFile.writeText(o.toString())
    }

    /** Moves the files to the trash (archive files and WhatsApp files alike). Returns how many moved. */
    @Synchronized
    fun trash(items: List<MediaItem>): Int {
        ensure()
        trashDir.mkdirs()
        val t = readTrash()
        val now = System.currentTimeMillis()
        var n = 0
        for (m in items) {
            val dest = freeName(trashDir, "${now}_${m.file.name}")
            if (!moveFile(m.file, dest)) continue
            t.put(dest.name, JSONObject().put("p", m.file.path).put("t", m.time).put("s", m.src.key))
            index.remove(rel(m.file))
            n++
        }
        writeTrash(t)
        saveIndex()
        return n
    }

    class TrashInfo(val count: Int, val bytes: Long)

    @Synchronized
    fun trashInfo(): TrashInfo {
        val files = trashDir.listFiles()?.filter { it.isFile && it.name != trashIndexFile.name } ?: emptyList()
        return TrashInfo(files.size, files.sumOf { it.length() })
    }

    /** Puts every trashed file back where it was. Returns the restored paths. */
    @Synchronized
    fun restoreAll(): List<String> {
        ensure()
        val t = readTrash()
        val out = ArrayList<String>()
        for (name in t.keys().asSequence().toList()) {
            val e = t.getJSONObject(name)
            val f = File(trashDir, name)
            if (!f.exists()) { t.remove(name); continue }
            val orig = File(e.getString("p"))
            val dir = orig.parentFile ?: continue
            dir.mkdirs()
            val dest = freeName(dir, orig.name)
            if (!moveFile(f, dest)) continue
            t.remove(name)
            out += dest.path
            if (dest.path.startsWith(root.path + "/")) index[rel(dest)] = e.getLong("t") to Src.of(e.optString("s"))
        }
        writeTrash(t)
        saveIndex()
        return out
    }

    /** Permanently deletes trashed files that were trashed before [before] (default: all). */
    @Synchronized
    fun emptyTrash(before: Long = Long.MAX_VALUE) {
        val files = trashDir.listFiles() ?: return
        val t = readTrash()
        for (f in files) {
            if (f.name == trashIndexFile.name) continue
            val stamp = f.name.substringBefore('_').toLongOrNull() ?: 0L
            if (stamp < before && f.delete()) t.remove(f.name)
        }
        writeTrash(t)
    }

    fun purgeTrash() = emptyTrash(System.currentTimeMillis() - TRASH_DAYS * 86_400_000L)

    companion object {
        const val FOLDER = "PhotoArchive"
        const val TRASH_DAYS = 30
        private val yearDir = Regex("\\d{4}")
    }
}
