package com.davidlevi.photoarchive

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/** One photo/video full screen. Swipe to the next/previous one; videos open in the phone's player. */
class ViewerScreen(
    a: MainActivity,
    private val items: MutableList<MediaItem>,
    private var pos: Int,
    private val onDeleted: (MediaItem) -> Unit
) : Screen(a) {
    private val image = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val play = a.label("▶", 38f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = roundRect(0x88000000.toInt(), a.dp(44f))
        setOnClickListener { a.openVideo(items[pos].file) }
    }
    private val info = a.label("", 14f, Color.WHITE)
    private val maxPx = a.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }
    private var token = 0

    override val view: View = FrameLayout(a).apply {
        setBackgroundColor(Color.BLACK)
        addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(play, FrameLayout.LayoutParams(a.dpi(88), a.dpi(88), Gravity.CENTER))
        addView(a.hbox().apply {
            setBackgroundColor(0x66000000)
            setPaddingRelative(a.dpi(16), a.dpi(12), a.dpi(16), a.dpi(12))
            addView(a.label("→", 26f, Color.WHITE, bold = true).apply {
                setPaddingRelative(a.dpi(4), a.dpi(2), a.dpi(16), a.dpi(2))
                setOnClickListener { a.back() }
            })
            addView(info, lp(0, WRAP, 1f))
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        addView(a.hbox().apply {
            setBackgroundColor(0x66000000)
            setPadding(a.dpi(8), a.dpi(8), a.dpi(8), a.dpi(8))
            addView(darkButton("שיתוף") { a.share(listOf(items[pos])) }, lp(0, WRAP, 1f))
            addView(darkButton("מחיקה") { confirmDelete() }, lp(0, WRAP, 1f))
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    }

    private val gestures = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityX) < abs(velocityY) || abs(velocityX) < 600) return false
            // Right-to-left order: the next (older) item sits to the left, so a swipe to the right shows it.
            go(if (velocityX > 0) 1 else -1)
            return true
        }
    })

    init {
        image.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
        show()
    }

    private fun darkButton(text: String, onClick: () -> Unit): TextView =
        a.label(text, 17f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, a.dpi(12), 0, a.dpi(12))
            setOnClickListener { onClick() }
        }

    private fun go(d: Int) {
        val n = pos + d
        if (n !in items.indices) return
        pos = n
        show()
    }

    private fun show() {
        if (items.isEmpty()) { a.pop(); return }
        pos = pos.coerceIn(0, items.size - 1)
        val m = items[pos]
        play.visibility = if (m.video) View.VISIBLE else View.GONE
        info.text = "${fmtDate(m.time)} · ${fmtSize(m.size)} · ${m.src.he}\n${pos + 1} מתוך ${fmtCount(items.size)}"
        image.setImageDrawable(null)
        val t = ++token
        a.async({ runCatching { decode(m) }.getOrNull() }) { if (t == token) image.setImageBitmap(it) }
    }

    private fun decode(m: MediaItem): Bitmap =
        if (m.video) ThumbnailUtils.createVideoThumbnail(m.file, Size(maxPx / 2, maxPx / 2), null)
        else ImageDecoder.decodeBitmap(ImageDecoder.createSource(m.file)) { d, info, _ ->
            val s = max(info.size.width, info.size.height) / maxPx
            if (s > 1) d.setTargetSampleSize(s)
        }

    private fun confirmDelete() {
        val m = items.getOrNull(pos) ?: return
        AlertDialog.Builder(a)
            .setMessage("להעביר לסל המחזור? אפשר לשחזר בהגדרות במשך ${Archive.TRASH_DAYS} יום.")
            .setPositiveButton("מחיקה") { _, _ ->
                a.async({ a.archive.trash(listOf(m)) }) { n ->
                    if (n == 0) { a.toast("המחיקה נכשלה"); return@async }
                    a.rescan(listOf(m.file.path))
                    items.removeAt(pos)
                    onDeleted(m)
                    show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }
}
