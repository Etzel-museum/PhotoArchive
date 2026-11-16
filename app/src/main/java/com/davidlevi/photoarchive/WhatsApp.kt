package com.davidlevi.photoarchive

import android.os.Environment
import java.io.File
import java.time.LocalDate

/** One WhatsApp data folder on the phone. [root] holds Media/, Databases/ and Backups/. */
class WaApp(val name: String, val pkg: String, val root: File) {
    /** Folder name used under the archive's WhatsApp-backup/. */
    val folder: String get() = root.name
}

object WhatsApp {
    fun installs(): List<WaApp> {
        val ext = Environment.getExternalStorageDirectory()
        return listOf(
            WaApp("וואטסאפ", "com.whatsapp", File(ext, "Android/media/com.whatsapp/WhatsApp")),
            WaApp("וואטסאפ ביזנס", "com.whatsapp.w4b", File(ext, "Android/media/com.whatsapp.w4b/WhatsApp Business")),
            // Before 2021 WhatsApp kept its files here; old phones often still have media left in it.
            WaApp("וואטסאפ (תיקייה ישנה)", "com.whatsapp", File(ext, "WhatsApp"))
        ).filter { File(it.root, "Media").isDirectory || File(it.root, "Databases").isDirectory }
    }

    /** The newest chat backup WhatsApp made on the phone (it makes one every night). */
    fun latestBackup(w: WaApp): File? =
        File(w.root, "Databases").listFiles()
            ?.filter { it.isFile && it.name.startsWith("msgstore") && ".crypt" in it.name }
            ?.maxByOrNull { it.lastModified() }

    /** The newest copy this app saved in the archive (a folder named by date). */
    fun lastCopy(w: WaApp, archive: Archive): File? =
        File(archive.waBackupDir, w.folder).listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }

    /**
     * Copies the newest chat backup (plus Backups/: contacts, settings, stickers) to
     * archive/WhatsApp-backup/<folder>/<date>. Keeps the 3 newest copies.
     */
    fun copyBackup(w: WaApp, archive: Archive): File {
        val latest = latestBackup(w) ?: error("לא נמצא גיבוי של וואטסאפ בטלפון")
        archive.ensure()
        val base = File(archive.waBackupDir, w.folder)
        val dest = File(base, LocalDate.now().toString())
        dest.mkdirs()
        if (!copyVerified(latest, File(dest, latest.name))) error("ההעתקה נכשלה – אולי אין מספיק מקום")
        File(w.root, "Backups").listFiles()?.filter { it.isFile }?.forEach {
            val d = File(dest, "Backups")
            d.mkdirs()
            copyVerified(it, File(d, it.name))
        }
        base.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(3)
            ?.forEach { it.deleteRecursively() }
        return dest
    }

    private fun mediaDirs(w: WaApp): List<File> =
        File(w.root, "Media").listFiles()?.filter {
            it.isDirectory && (it.name.endsWith("Images") || it.name.endsWith("Video") || it.name.endsWith("Animated Gifs"))
        } ?: emptyList()

    /** WhatsApp's photos/videos where they are (they are never moved – that would break the chats). */
    fun mediaItems(w: WaApp): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        for (d in mediaDirs(w)) {
            d.walkTopDown().filter { it.isFile && Media.isMedia(it) }.forEach { f ->
                out += MediaItem(
                    f,
                    Media.dateFromName(f.name) ?: f.lastModified(),
                    if (f.parentFile?.name == "Sent") Src.WA_SENT else Src.WA_RECEIVED,
                    Media.isVideo(f),
                    f.length()
                )
            }
        }
        out.sortByDescending { it.time }
        return out
    }
}

/** WhatsApp's media as a gallery library. */
class WaLibrary(private val w: WaApp) : Library {
    override val title = "${w.name} – תמונות וסרטונים"
    override val sources = listOf(Src.WA_RECEIVED, Src.WA_SENT)
    override fun load() = WhatsApp.mediaItems(w)
}
