package com.davidlevi.photoarchive

import android.os.Environment
import android.os.StatFs
import android.view.View
import android.widget.LinearLayout

/** Big buttons, one per action, and a one-line summary of the archive. */
class HomeScreen(a: MainActivity) : Screen(a) {
    private val summary = a.label("", 16f, pal.text2)
    override val view: View

    init {
        val (v, body) = a.page("ארכיון תמונות", back = false)
        view = v
        body.addView(a.card().apply { addView(summary) }, lp(MATCH, WRAP).apply { bottomMargin = a.dpi(8) })
        body.addView(big("התמונות שלי", "צפייה, סינון ומחיקה") { a.push(GalleryScreen(a, ArchiveLibrary(a))) })
        body.addView(big("איסוף תמונות מהטלפון", "העברת התמונות מהגלריה לארכיון") { a.push(ScanScreen(a)) })
        body.addView(big("וואטסאפ", "גיבוי הצ'אטים וניקוי תמונות וסרטונים") { a.push(WhatsAppScreen(a)) })
        body.addView(big("העברה מ-Google Photos", "בגרסה הבאה", enabled = false) {})
        body.addView(big("הגדרות", "מיקום הארכיון וסל המחזור") { a.push(SettingsScreen(a)) })
    }

    private fun big(title: String, sub: String, enabled: Boolean = true, onClick: () -> Unit): View =
        a.card().apply {
            setPadding(a.dpi(18), a.dpi(18), a.dpi(18), a.dpi(18))
            addView(a.label(title, 21f, pal.text, bold = true))
            addView(a.label(sub, 14f, pal.muted))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dpi(10) }
            if (enabled) setOnClickListener { onClick() } else alpha = 0.45f
        }

    override fun onShow() {
        summary.text = "טוען…"
        a.async({
            val items = a.archive.items()
            val root = a.archive.root
            val free = StatFs((if (root.exists()) root else Environment.getExternalStorageDirectory()).path).availableBytes
            Triple(items.size, items.sumOf { it.size }, free)
        }) { (n, bytes, free) ->
            summary.text = (if (n == 0) "הארכיון עדיין ריק" else "בארכיון: ${fmtCount(n)} תמונות וסרטונים · ${fmtSize(bytes)}") +
                "\nמקום פנוי: ${fmtSize(free)}"
        }
    }
}
