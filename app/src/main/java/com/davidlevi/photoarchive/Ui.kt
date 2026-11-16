package com.davidlevi.photoarchive

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Screen colors for light and dark mode. */
class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF1A1A19.toInt() else 0xFFF7F6F2.toInt()
    val card = if (dark) 0xFF262624.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFF1EFE8.toInt() else 0xFF2C2C2A.toInt()
    val text2 = if (dark) 0xFFB4B2A9.toInt() else 0xFF5F5E5A.toInt()
    val muted = 0xFF888780.toInt()
    val line = if (dark) 0x33FFFFFF else 0x1F000000
    val accent = if (dark) 0xFF85B7EB.toInt() else 0xFF185FA5.toInt()
    /** Button fills; white text reads on both in light and dark mode. */
    val accentFill = 0xFF378ADD.toInt()
    val dangerFill = 0xFFA32D2D.toInt()

    companion object {
        fun of(ctx: Context) = Palette(
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        )
    }
}

fun Context.dp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.dpi(v: Int): Int = dp(v.toFloat()).toInt()

fun Context.label(text: String, sizeSp: Float = 15f, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

/** A big tappable button. With [fill] it is a solid colored button, otherwise an outlined one. */
fun Context.button(text: String, pal: Palette, fill: Int? = null, onClick: () -> Unit): TextView =
    label(text, 17f, if (fill != null) Color.WHITE else pal.accent, bold = true).apply {
        gravity = Gravity.CENTER
        background = if (fill != null) roundRect(fill, dp(14f))
        else roundRect(pal.card, dp(14f), pal.line, dpi(1))
        setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
        setOnClickListener { onClick() }
    }

fun roundRect(color: Int, radiusPx: Float, strokeColor: Int = Color.TRANSPARENT, strokePx: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

fun View.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) {
    (layoutParams as? LinearLayout.LayoutParams)?.setMargins(l, t, r, b)
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.1fGB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.0fMB", b / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.0fKB", b / 1024.0)
}

fun fmtCount(n: Int): String = String.format(Locale.US, "%,d", n)

fun zoned(t: Long): ZonedDateTime = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())

/** "12.3.2019". */
fun fmtDate(t: Long): String = zoned(t).let { "${it.dayOfMonth}.${it.monthValue}.${it.year}" }

/** "12.3.2019 02:00". */
fun fmtDateTime(t: Long): String =
    zoned(t).let { String.format(Locale.US, "%s %02d:%02d", fmtDate(t), it.hour, it.minute) }

val MONTHS_HE = arrayOf(
    "ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני",
    "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר"
)
