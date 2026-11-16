package com.davidlevi.photoarchive

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/** Loads grid thumbnails straight from the files (the archive is hidden from MediaStore). */
class Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())

    fun load(item: MediaItem, iv: ImageView, sizePx: Int) {
        val key = item.file.path
        iv.tag = key
        cache.get(key)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        pool.execute {
            // The cell was recycled for another item while this waited in the queue.
            if (iv.tag != key) return@execute
            val bmp = runCatching {
                val s = Size(sizePx, sizePx)
                if (item.video) ThumbnailUtils.createVideoThumbnail(item.file, s, null)
                else ThumbnailUtils.createImageThumbnail(item.file, s, null)
            }.getOrNull()
            if (bmp != null) cache.put(key, bmp)
            ui.post { if (iv.tag == key) iv.setImageBitmap(bmp) }
        }
    }
}
