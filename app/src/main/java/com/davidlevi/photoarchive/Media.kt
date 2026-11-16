package com.davidlevi.photoarchive

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where a photo came from. The WA_* values are used only inside WhatsApp's own folders. */
enum class Src(val key: String, val he: String) {
    CAMERA("cam", "מצלמה"),
    SCREENSHOT("shot", "צילומי מסך"),
    WHATSAPP("wa", "וואטסאפ"),
    OTHER("other", "אחר"),
    WA_RECEIVED("wa_in", "התקבלו"),
    WA_SENT("wa_out", "נשלחו");

    companion object {
        fun of(key: String?): Src = values().firstOrNull { it.key == key } ?: OTHER
    }
}

class MediaItem(val file: File, val time: Long, val src: Src, val video: Boolean, val size: Long) {
    /** year*100+month, for month headers and the year filter. */
    val ym: Int = zoned(time).let { it.year * 100 + it.monthValue }
}

object Media {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "bmp")
    private val VIDEO_EXT = setOf("mp4", "3gp", "mkv", "mov", "webm", "avi", "m4v")

    fun isMedia(f: File): Boolean = f.extension.lowercase().let { it in IMAGE_EXT || it in VIDEO_EXT }
    fun isVideo(f: File): Boolean = f.extension.lowercase() in VIDEO_EXT

    // IMG_20190312_101530.jpg, IMG-20190312-WA0012.jpg, Screenshot_2019-03-12-10-15-30.png, 20190312T101530.000Z …
    private val nameDate = Regex(
        "(?<!\\d)((?:19|20)\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})(?:[-_ T.]?(\\d{2})[-_.:]?(\\d{2})[-_.:]?(\\d{2}))?"
    )
    private val waName = Regex("-WA\\d{4}", RegexOption.IGNORE_CASE)
    private val exifFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun isWhatsAppName(name: String): Boolean = waName.containsMatchIn(name)

    /** A date written in a file name (or a metadata string), or null. */
    fun dateFromName(name: String): Long? {
        for (m in nameDate.findAll(name)) {
            val g = m.groupValues
            val y = g[1].toInt()
            val mo = g[2].toInt()
            val d = g[3].toInt()
            if (y < 1990 || mo !in 1..12 || d !in 1..31) continue
            var h = g[4].toIntOrNull() ?: 12
            var mi = g[5].toIntOrNull() ?: 0
            var s = g[6].toIntOrNull() ?: 0
            if (h > 23 || mi > 59 || s > 59) { h = 12; mi = 0; s = 0 }
            val t = runCatching {
                LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: continue
            return t
        }
        return null
    }

    private fun exifDate(f: File): Long? = runCatching {
        val e = ExifInterface(f)
        val s = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: e.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        LocalDateTime.parse(s.trim(), exifFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()?.takeIf { zoned(it).year >= 1990 }

    private fun videoDate(f: File): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { dateFromName(it) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Best guess of when a photo/video was taken: EXIF, then the file name, then video metadata, then mtime. */
    fun takenTime(f: File): Long {
        val video = isVideo(f)
        return (if (video) null else exifDate(f))
            ?: dateFromName(f.name)
            ?: (if (video) videoDate(f) else null)
            ?: f.lastModified()
    }

    fun sourceOf(f: File): Src {
        val p = f.path.lowercase()
        return when {
            isWhatsAppName(f.name) || "whatsapp" in p -> Src.WHATSAPP
            "screenshot" in p -> Src.SCREENSHOT
            "/dcim/" in p -> Src.CAMERA
            else -> Src.OTHER
        }
    }
}

/** Moves a file, falling back to copy + delete across volumes (e.g. to an SD card). */
fun moveFile(src: File, dest: File): Boolean {
    if (src.renameTo(dest)) return true
    return try {
        if (!copyVerified(src, dest)) return false
        src.delete()
        true
    } catch (e: Exception) {
        dest.delete()
        false
    }
}

/** Copies and checks that the whole file arrived. Keeps the modification time. */
fun copyVerified(src: File, dest: File): Boolean {
    src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
    if (dest.length() != src.length()) {
        dest.delete()
        return false
    }
    dest.setLastModified(src.lastModified())
    return true
}

private fun readFull(s: InputStream, buf: ByteArray): Int {
    var n = 0
    while (n < buf.size) {
        val r = s.read(buf, n, buf.size - n)
        if (r < 0) break
        n += r
    }
    return n
}

/** Byte-for-byte comparison. */
fun sameContent(a: File, b: File): Boolean {
    if (a.length() != b.length()) return false
    return try {
        a.inputStream().use { x ->
            b.inputStream().use { y ->
                val bx = ByteArray(1 shl 16)
                val by = ByteArray(1 shl 16)
                var same = true
                while (true) {
                    val n1 = readFull(x, bx)
                    val n2 = readFull(y, by)
                    if (n1 != n2) { same = false; break }
                    if (n1 == 0) break
                    if ((0 until n1).any { bx[it] != by[it] }) { same = false; break }
                }
                same
            }
        }
    } catch (e: Exception) {
        false
    }
}

/** [name] in [dir], or "name(1).ext", "name(2).ext"… if taken. */
fun freeName(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "")
    var i = 1
    while (true) {
        f = File(dir, if (ext.isEmpty()) "$base($i)" else "$base($i).$ext")
        if (!f.exists()) return f
        i++
    }
}
