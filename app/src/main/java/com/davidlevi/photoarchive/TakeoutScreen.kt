package com.davidlevi.photoarchive

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step-by-step move from Google Photos: request a Takeout export, download the parts,
 * import them here, check that every part arrived, and only then delete from Google.
 */
class TakeoutScreen(a: MainActivity) : Screen(a) {
    override val view: View
    private val body: LinearLayout
    /** Bumped by every build(), so answers from an older build's background work are dropped. */
    private var gen = 0

    init {
        val (v, b) = a.page("העברה מ-Google Photos")
        view = v
        body = b
    }

    // Rebuilt on every return, e.g. after downloading a part in the browser.
    override fun onShow() = build()

    private fun card(title: String): LinearLayout {
        val c = a.card()
        c.addView(a.label(title, 18f, pal.text, bold = true))
        body.addView(c, lp(MATCH, WRAP).apply { topMargin = a.dpi(12) })
        return c
    }

    private fun text(s: String, color: Int = pal.text2, size: Float = 15f) =
        a.label(s, size, color).apply { setPadding(0, a.dpi(6), 0, 0) }

    private fun buttonLp() = lp(MATCH, WRAP).apply { topMargin = a.dpi(10) }

    private fun openUrl(url: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            a.toast("אין דפדפן בטלפון")
        }
    }

    private fun build() {
        body.removeAllViews()
        gen++
        body.addView(text(
            "ההעברה לא מוחקת כלום מגוגל. המחיקה מהענן היא צעד נפרד, בסוף, רק אחרי שכל החלקים יובאו.",
            pal.text
        ))

        card("1. בקשת ייצוא מגוגל").apply {
            addView(text(
                "בדף שנפתח רק Google Photos מסומן. לוחצים „השלב הבא“ בתחתית ובוחרים:\n" +
                    "• שיטת המסירה: „שליחת קישור להורדה באימייל“\n" +
                    "• תדירות: „ייצוא פעם אחת“\n" +
                    "• סוג הקובץ: ‎.zip · גודל: 2GB\n" +
                    "ואז „יצירת ייצוא“."
            ))
            addView(text(
                "⚠️ לא לבחור „הוספה ל-Drive“ – הייצוא יישמר בחשבון וימלא אותו שוב.",
                pal.text, 14f
            ))
            addView(a.button("פתיחת Google Takeout", pal, pal.accentFill) {
                openUrl("https://takeout.google.com/settings/takeout/custom/photos")
            }, buttonLp())
        }

        card("2. מחכים למייל מגוגל").apply {
            addView(text("בין כמה שעות לכמה ימים, לפי גודל הספרייה. בינתיים אפשר לסגור את האפליקציה."))
        }

        card("3. מורידים את הקבצים").apply {
            addView(text(
                "במייל לוחצים „הורדת הקבצים“. גוגל תבקש להתחבר שוב. מורידים כל חלק " +
                    "(takeout-…-001.zip, ‏002 …) לתיקיית ההורדות. הקישורים בתוקף כשבוע.\n\n" +
                    "אם אין הרבה מקום פנוי: מורידים חלק אחד, מייבאים אותו (הוא נמחק אחרי הייבוא), ואז את הבא."
            ))
        }

        importCard()
        progressCard()
    }

    private fun importCard() {
        val c = card("4. ייבוא לארכיון")
        val status = text("מחפש קבצי ייצוא בהורדות…", pal.text)
        c.addView(status)
        val g = gen
        a.async({ Takeout.findParts() }) { parts ->
            if (g != gen) return@async
            if (parts.isEmpty()) {
                status.text = "לא נמצאו בתיקיית ההורדות קבצים בשם takeout-….zip."
                c.addView(a.button("חיפוש שוב", pal) { build() }, buttonLp())
                return@async
            }
            status.text = parts.joinToString("\n") {
                "חלק ${it.number} (ייצוא מ-${Takeout.exportDate(it.exportId)}) · ${fmtSize(it.file.length())}"
            }
            c.addView(a.button("ייבוא ${fmtCount(parts.size)} חלקים · ${fmtSize(parts.sumOf { it.file.length() })}", pal, pal.accentFill) {
                confirmImport(parts)
            }, buttonLp())
        }
    }

    private fun confirmImport(parts: List<Takeout.Part>) {
        AlertDialog.Builder(a)
            .setTitle("ייבוא לארכיון")
            .setMessage(
                "התמונות והסרטונים מ-${parts.size} החלקים ייכנסו לארכיון. " +
                    "כל חלק שיובא בהצלחה יימחק מההורדות כדי לפנות מקום.\n\nזה יכול לקחת זמן – כדאי שהטלפון יהיה בטעינה."
            )
            .setPositiveButton("ייבוא") { _, _ -> runImport(parts) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun runImport(parts: List<Takeout.Part>) {
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        val text = a.label("מתחיל…", 15f, pal.text)
        val cancel = AtomicBoolean(false)
        val dlg = AlertDialog.Builder(a)
            .setTitle("מייבא…")
            .setView(a.vbox().apply {
                setPadding(a.dpi(24), a.dpi(8), a.dpi(24), 0)
                addView(text)
                addView(bar, lp(MATCH, WRAP))
            })
            .setCancelable(false)
            .setNegativeButton("עצירה") { _, _ -> cancel.set(true) }
            .show()
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        a.async({
            Takeout.import(a.archive, parts, { what, done, total ->
                a.post {
                    bar.isIndeterminate = total == 0
                    if (total > 0) { bar.max = total; bar.progress = done }
                    text.text = if (total > 0) "$what: ${fmtCount(done)} מתוך ${fmtCount(total)}" else "$what…"
                }
            }, { cancel.get() })
        }) { r ->
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (dlg.isShowing) dlg.dismiss()
            a.rescan(r.done.map { it.file.path })
            val lines = mutableListOf("יובאו לארכיון: ${fmtCount(r.items)} (${fmtSize(r.bytes)})")
            if (r.dups > 0) lines += "כפולים שדולגו (למשל תמונה שמופיעה גם באלבום): ${fmtCount(r.dups)}"
            if (r.dated > 0) lines += "תאריכים שתוקנו: ${fmtCount(r.dated)}"
            if (r.failed > 0) lines += "לא הצליחו: ${fmtCount(r.failed)}"
            if (r.skipped > 0) lines += "קבצים שאינם תמונה או סרטון ולכן לא יובאו: ${fmtCount(r.skipped)}"
            if (r.done.isNotEmpty()) lines += "חלקים שהסתיימו ונמחקו מההורדות: ${r.done.joinToString(", ") { it.number.toString() }}"
            if (r.problems.isNotEmpty()) lines += "\n" + r.problems.joinToString("\n")
            if (cancel.get()) lines += "\nהייבוא נעצר. אפשר להמשיך אחר כך – מה שכבר יובא לא ייובא פעמיים."
            AlertDialog.Builder(a).setTitle("סיום").setMessage(lines.joinToString("\n"))
                .setPositiveButton("אישור", null).show()
            build()
        }
    }

    /** Per export: which parts are in, which are missing, and – when all are in – how to delete from Google. */
    private fun progressCard() {
        val g = gen
        a.async({ Takeout.stateOf(a.archive).exports.toList().sortedByDescending { it.first } }) { exports ->
            if (g != gen) return@async
            for ((id, e) in exports) {
                val c = card("5. בדיקה – ייצוא מ-${Takeout.exportDate(id)}")
                c.addView(text(
                    "יובאו: ${fmtCount(e.items)} פריטים · ${fmtSize(e.bytes)}" +
                        (if (e.dups > 0) " · ${fmtCount(e.dups)} כפולים דולגו" else "") +
                        "\nחלקים שיובאו: ${if (e.parts.isEmpty()) "—" else e.parts.joinToString(", ")}",
                    pal.text
                ))
                val check = when {
                    e.total == 0 -> "כמה קבצים הופיעו במייל של גוגל? צריך את המספר כדי לוודא ששום חלק לא חסר."
                    e.complete -> "✓ כל ${e.total} החלקים יובאו."
                    else -> "חסרים חלקים: ${e.missing.joinToString(", ")}. מורידים אותם מהמייל ומייבאים."
                }
                c.addView(text(check, pal.text))
                c.addView(a.button(if (e.total == 0) "הזנת מספר הקבצים" else "שינוי מספר הקבצים", pal) {
                    askTotal(id, e)
                }, buttonLp())
                if (e.complete) deleteCard()
            }
        }
    }

    private fun askTotal(id: String, e: Takeout.Export) {
        val input = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            if (e.total > 0) setText(e.total.toString())
        }
        AlertDialog.Builder(a)
            .setTitle("כמה קבצים היו במייל?")
            .setMessage("במייל של גוגל מופיעה רשימת קבצים להורדה (חלק 1 מתוך …). כמה יש בסך הכול?")
            .setView(a.vbox().apply { setPadding(a.dpi(24), 0, a.dpi(24), 0); addView(input) })
            .setPositiveButton("שמירה") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                a.async({
                    val st = Takeout.stateOf(a.archive)
                    st.export(id).total = n
                    st.save()
                }) { build() }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun deleteCard() {
        val c = card("6. מחיקה מ-Google Photos")
        c.addView(text(
            "כל החלקים בארכיון, ואפשר לפנות את המקום בחשבון. לפני כן:\n" +
                "• מומלץ מאוד לשמור עותק של התיקייה ${Archive.FOLDER} גם בדיסק חיצוני או במחשב – אחרי המחיקה הטלפון הוא המקום היחיד שבו התמונות נמצאות.\n" +
                "• באפליקציית Google Photos: כיבוי „גיבוי“, כדי שלא יעלו שוב."
        ))
        c.addView(text(
            "המחיקה נוחה במחשב, באתר photos.google.com:\n" +
                "1. מחפשים שנה, למשל 2018.\n" +
                "2. מסמנים את התמונה הראשונה, גוללים לסוף, ולוחצים על האחרונה כשמקש Shift לחוץ.\n" +
                "3. מוחקים, וחוזרים על זה לכל שנה.\n" +
                "4. בסוף: „אשפה“ ← „ריקון האשפה“. בלי זה המקום לא מתפנה עד 60 יום.",
            pal.text
        ))
        c.addView(a.button("פתיחת Google Photos", pal) { openUrl("https://photos.google.com") }, buttonLp())
    }
}
