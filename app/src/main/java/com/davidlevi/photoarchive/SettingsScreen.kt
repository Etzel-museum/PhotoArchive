package com.davidlevi.photoarchive

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import java.io.File

val KEEP_CHOICES = intArrayOf(0, 7, 30, 90, 365)

/** "ב-30 הימים האחרונים", to follow "מה שצולם". */
fun keepLabel(days: Int): String = when (days) {
    90 -> "ב-3 החודשים האחרונים"
    365 -> "בשנה האחרונה"
    else -> "ב-$days הימים האחרונים"
}

fun pickKeepDays(a: MainActivity, done: () -> Unit) {
    val labels = KEEP_CHOICES.map { if (it == 0) "שום דבר – להעביר הכול" else "מה שצולם ${keepLabel(it)}" }
    AlertDialog.Builder(a).setTitle("מה להשאיר בגלריה הרגילה?")
        .setItems(labels.toTypedArray()) { _, i -> a.archive.keepDays = KEEP_CHOICES[i]; done() }
        .show()
}

class SettingsScreen(a: MainActivity) : Screen(a) {
    override val view: View
    private val body: LinearLayout

    init {
        val (v, b) = a.page("הגדרות")
        view = v
        body = b
    }

    override fun onShow() = build()

    private fun section(title: String): LinearLayout {
        val c = a.card()
        c.addView(a.label(title, 18f, pal.text, bold = true))
        body.addView(c, lp(MATCH, WRAP).apply { topMargin = a.dpi(12) })
        return c
    }

    private fun build() {
        body.removeAllViews()

        section("מיקום הארכיון").apply {
            addView(a.label(a.archive.root.path, 14f, pal.text2))
            addView(a.button("שינוי מיקום", pal) { changeLocation() }, lp(MATCH, WRAP).apply { topMargin = a.dpi(10) })
        }

        section("סריקת הטלפון").apply {
            addView(a.label(
                if (a.archive.keepDays == 0) "להעביר הכול לארכיון"
                else "להשאיר בגלריה את מה שצולם ${keepLabel(a.archive.keepDays)}",
                15f, pal.text2
            ))
            addView(a.button("שינוי", pal) { pickKeepDays(a) { build() } }, lp(MATCH, WRAP).apply { topMargin = a.dpi(10) })
        }

        val trash = section("סל המחזור")
        val trashInfo = a.label("בודק…", 15f, pal.text2)
        trash.addView(trashInfo)
        a.async({ a.archive.trashInfo() }) { t ->
            trashInfo.text = if (t.count == 0) "סל המחזור ריק."
            else "${fmtCount(t.count)} פריטים · ${fmtSize(t.bytes)}\nנמחקים סופית ${Archive.TRASH_DAYS} יום אחרי המחיקה."
            if (t.count == 0) return@async
            trash.addView(a.button("שחזור הכול", pal) { restoreAll() }, lp(MATCH, WRAP).apply { topMargin = a.dpi(10) })
            trash.addView(a.button("ריקון עכשיו", pal, pal.dangerFill) { confirmEmpty(t) }, lp(MATCH, WRAP).apply {
                topMargin = a.dpi(8)
            })
        }

        val version = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: ""
        body.addView(a.label("גרסה $version", 13f, pal.muted).apply { setPadding(0, a.dpi(16), 0, 0) })
    }

    private fun changeLocation() {
        val vols = Scanner.volumes(a)
        AlertDialog.Builder(a).setTitle("איפה לשמור את הארכיון?")
            .setItems(vols.map { it.first }.toTypedArray()) { _, i ->
                val newRoot = File(vols[i].second, Archive.FOLDER)
                if (newRoot.path == a.archive.root.path) return@setItems
                a.async({ a.archive.items().size }) { n ->
                    if (n > 0) {
                        AlertDialog.Builder(a).setMessage(
                            "אפשר לשנות את המיקום רק כשהארכיון ריק. העברה של ארכיון קיים למקום אחר תגיע בגרסה הבאה."
                        ).setPositiveButton("אישור", null).show()
                        return@async
                    }
                    val old = a.archive.root
                    a.archive.root = newRoot
                    a.async({ a.archive.ensure(); newRoot.isDirectory }) { ok ->
                        if (!ok) {
                            a.archive.root = old
                            a.toast("אין אפשרות לכתוב במיקום הזה")
                        }
                        build()
                    }
                }
            }.show()
    }

    private fun restoreAll() {
        a.async({ a.archive.restoreAll() }) { paths ->
            a.rescan(paths)
            a.toast("שוחזרו ${fmtCount(paths.size)} פריטים")
            build()
        }
    }

    private fun confirmEmpty(t: Archive.TrashInfo) {
        AlertDialog.Builder(a)
            .setTitle("ריקון סל המחזור")
            .setMessage("${fmtCount(t.count)} פריטים (${fmtSize(t.bytes)}) יימחקו לצמיתות. אי אפשר לבטל את זה.")
            .setPositiveButton("מחיקה לצמיתות") { _, _ ->
                a.async({ a.archive.emptyTrash() }) {
                    a.toast("סל המחזור רוקן")
                    build()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }
}
