package com.davidlevi.photoarchive

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** Finds photo folders on the phone and moves their contents into the archive. */
object Scanner {
    class Folder(val dir: File, val label: String, val files: List<File>, val bytes: Long, var checked: Boolean)

    class MoveResult {
        var moved = 0
        var bytes = 0L
        var dup = 0
        var kept = 0
        var failed = 0
        /** Paths that no longer exist, so the gallery can forget them. */
        val oldPaths = ArrayList<String>()
    }

    /** Internal storage plus any mounted SD card / USB drive, with a display name. */
    fun volumes(ctx: Context): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>("זיכרון הטלפון" to Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= 30) {
            val sm = ctx.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                if (v.isPrimary || v.state != Environment.MEDIA_MOUNTED) continue
                val d = v.directory ?: continue
                out += v.getDescription(ctx) to d
            }
        }
        return out
    }

    /** Folders that directly contain photos/videos, biggest first. Skips the archive, WhatsApp and hidden folders. */
    fun findFolders(ctx: Context, archive: File): List<Folder> {
        val out = ArrayList<Folder>()
        val vols = volumes(ctx)
        for ((i, v) in vols.withIndex()) walk(v.second, v.second, if (i == 0) "" else "${v.first}: ", archive, 0, out)
        return out.sortedByDescending { it.bytes }
    }

    private fun walk(dir: File, volRoot: File, prefix: String, archive: File, depth: Int, out: MutableList<Folder>) {
        val kids = dir.listFiles() ?: return
        // Folders the gallery itself ignores (app caches, WhatsApp "Sent", …).
        if (depth > 0 && kids.any { it.name == ".nomedia" }) return
        val media = kids.filter { it.isFile && Media.isMedia(it) }
        if (media.isNotEmpty()) {
            val rel = dir.path.removePrefix(volRoot.path).trimStart('/')
            out += Folder(dir, prefix + labelFor(rel), media, media.sumOf { it.length() }, defaultOn(rel))
        }
        if (depth >= 8) return
        for (k in kids) {
            if (!k.isDirectory || k.name.startsWith(".")) continue
            if (k.path == archive.path) continue
            if (depth == 0 && k.name == "Android") continue
            // WhatsApp's media must stay where it is, or the photos inside the chats break.
            if (k.name.contains("whatsapp", ignoreCase = true)) continue
            walk(k, volRoot, prefix, archive, depth + 1, out)
        }
    }

    private fun labelFor(rel: String): String {
        val l = rel.lowercase()
        return when {
            l == "dcim/camera" -> "מצלמה"
            l.endsWith("screenshots") -> "צילומי מסך"
            l == "download" || l == "downloads" -> "הורדות"
            "telegram" in l -> "טלגרם ($rel)"
            rel.isEmpty() -> "התיקייה הראשית"
            else -> rel
        }
    }

    private fun defaultOn(rel: String): Boolean {
        val l = rel.lowercase()
        return l == "dcim/camera" || l.endsWith("screenshots")
    }

    fun move(
        archive: Archive,
        folders: List<Folder>,
        keepDays: Int,
        progress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean
    ): MoveResult {
        val r = MoveResult()
        archive.ensure()
        val sizes = archive.sizeIndex()
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        val files = folders.flatMap { it.files }
        for ((i, f) in files.withIndex()) {
            if (cancelled()) break
            if (i % 10 == 0) progress(i, files.size)
            if (!f.exists()) continue
            val t = Media.takenTime(f)
            if (keepDays > 0 && t >= cutoff) { r.kept++; continue }
            val len = f.length()
            when (archive.add(f, t, Media.sourceOf(f), sizes)) {
                Archive.Added.MOVED -> { r.moved++; r.bytes += len; r.oldPaths += f.path }
                Archive.Added.DUPLICATE -> { r.dup++; r.oldPaths += f.path }
                Archive.Added.FAILED -> r.failed++
            }
            if (i % 200 == 199) archive.saveIndex()
        }
        progress(files.size, files.size)
        archive.saveIndex()
        return r
    }
}
