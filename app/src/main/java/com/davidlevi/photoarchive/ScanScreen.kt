package com.davidlevi.photoarchive

import android.app.AlertDialog
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ProgressBar
import java.util.concurrent.atomic.AtomicBoolean

/** Lists the phone's photo folders; the checked ones are moved into the archive. */
class ScanScreen(a: MainActivity) : Screen(a) {
    private var folders: List<Scanner.Folder> = emptyList()
    private val status = a.label("מחפש תמונות וסרטונים בטלפון…", 16f, pal.text2)
    private val list = a.vbox()
    private val keepText = a.label("", 16f, pal.text)
    private val goBtn = a.button("", pal, pal.accentFill) { confirmMove() }
    override val view: View

    init {
        val (v, body) = a.page("איסוף תמונות מהטלפון")
        view = v
        body.addView(status)
        body.addView(a.card().apply { addView(list) }, lp(MATCH, WRAP).apply { topMargin = a.dpi(12) })
        body.addView(a.card().apply {
            addView(a.label("מה להשאיר בגלריה הרגילה", 14f, pal.muted))
            addView(keepText)
            addView(a.label("שינוי", 15f, pal.accent, bold = true).apply { setPadding(0, a.dpi(6), 0, 0) })
            setOnClickListener { pickKeepDays(a) { updateKeep() } }
        }, lp(MATCH, WRAP).apply { topMargin = a.dpi(12) })
        body.addView(a.label(
            "התמונות שיועברו ייעלמו מהגלריה הרגילה של הטלפון ויופיעו ב„התמונות שלי“. " +
                "הן לא יגובו יותר ל-Google Photos (מה שכבר גובה נשאר בענן). " +
                "זיכרון הטלפון אינו גיבוי – כדאי לשמור עותק גם בדיסק חיצוני או במחשב.",
            14f, pal.muted
        ), lp(MATCH, WRAP).apply { topMargin = a.dpi(12) })
        body.addView(goBtn, lp(MATCH, WRAP).apply { topMargin = a.dpi(16) })
        updateKeep()
        scan()
    }

    private fun updateKeep() {
        keepText.text = if (a.archive.keepDays == 0) "שום דבר – להעביר הכול"
        else "את מה שצולם ${keepLabel(a.archive.keepDays)}"
    }

    private fun scan() {
        status.text = "מחפש תמונות וסרטונים בטלפון…"
        list.removeAllViews()
        updateGo()
        a.async({ Scanner.findFolders(a, a.archive.root) }) {
            folders = it
            status.text = if (it.isEmpty()) "לא נמצאו בטלפון תמונות להעברה."
            else "סמנו את התיקיות שרוצים להעביר לארכיון:"
            for ((i, f) in it.withIndex()) {
                if (i > 0) list.addView(View(a).apply { setBackgroundColor(pal.line) }, lp(MATCH, a.dpi(1)))
                list.addView(row(f))
            }
            updateGo()
        }
    }

    private fun row(f: Scanner.Folder): View = a.hbox().apply {
        setPadding(0, a.dpi(8), 0, a.dpi(8))
        val cb = CheckBox(a).apply {
            isChecked = f.checked
            setOnCheckedChangeListener { _, on -> f.checked = on; updateGo() }
        }
        addView(cb)
        addView(a.vbox().apply {
            addView(a.label(f.label, 16f, pal.text, bold = true))
            addView(a.label("${fmtCount(f.files.size)} פריטים · ${fmtSize(f.bytes)}", 14f, pal.muted))
        }, lp(0, WRAP, 1f))
        setOnClickListener { cb.toggle() }
    }

    private fun updateGo() {
        val chosen = folders.filter { it.checked }
        val n = chosen.sumOf { it.files.size }
        goBtn.text = if (n == 0) "סמנו תיקיות להעברה"
        else "העברה לארכיון: ${fmtCount(n)} פריטים · ${fmtSize(chosen.sumOf { it.bytes })}"
        goBtn.isEnabled = n > 0
        goBtn.alpha = if (n > 0) 1f else 0.45f
    }

    private fun confirmMove() {
        val chosen = folders.filter { it.checked }
        if (chosen.isEmpty()) return
        val keep = if (a.archive.keepDays == 0) "" else "\n\nמה שצולם ${keepLabel(a.archive.keepDays)} יישאר בגלריה."
        AlertDialog.Builder(a)
            .setTitle("העברה לארכיון")
            .setMessage("להעביר את התמונות והסרטונים מ-${chosen.size} תיקיות לארכיון?$keep")
            .setPositiveButton("העברה") { _, _ -> runMove(chosen) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun runMove(chosen: List<Scanner.Folder>) {
        val total = chosen.sumOf { it.files.size }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = total }
        val text = a.label("מתחיל…", 15f, pal.text)
        val cancel = AtomicBoolean(false)
        val dlg = AlertDialog.Builder(a)
            .setTitle("מעביר לארכיון…")
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
            Scanner.move(a.archive, chosen, a.archive.keepDays, { done, all ->
                a.post {
                    bar.progress = done
                    text.text = "${fmtCount(done)} מתוך ${fmtCount(all)}"
                }
            }, { cancel.get() })
        }) { r ->
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (dlg.isShowing) dlg.dismiss()
            a.rescan(r.oldPaths)
            val lines = mutableListOf("הועברו לארכיון: ${fmtCount(r.moved)} (${fmtSize(r.bytes)})")
            if (r.dup > 0) lines += "כבר היו בארכיון ולכן הוסרו מהגלריה: ${fmtCount(r.dup)}"
            if (r.kept > 0) lines += "נשארו בגלריה כי הם חדשים: ${fmtCount(r.kept)}"
            if (r.failed > 0) lines += "לא הצליחו לעבור: ${fmtCount(r.failed)} (אולי אין מספיק מקום)"
            if (cancel.get()) lines += "\nההעברה נעצרה לפני הסוף."
            AlertDialog.Builder(a).setTitle("סיום").setMessage(lines.joinToString("\n"))
                .setPositiveButton("אישור", null).show()
            scan()
        }
    }
}
