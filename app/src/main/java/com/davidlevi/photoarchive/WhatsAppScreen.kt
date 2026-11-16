package com.davidlevi.photoarchive

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout

/**
 * Per WhatsApp folder: copy the nightly chat backup into the archive, and browse/delete media in place.
 * Plus how to keep the Google Drive backup small (chats only, no videos).
 */
class WhatsAppScreen(a: MainActivity) : Screen(a) {
    override val view: View
    private val body: LinearLayout

    init {
        val (v, b) = a.page("וואטסאפ")
        view = v
        body = b
    }

    // Rebuilt on every return, e.g. after making a fresh backup inside WhatsApp.
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

    private fun build() {
        body.removeAllViews()
        val apps = WhatsApp.installs()
        if (apps.isEmpty()) {
            body.addView(text("לא נמצאו בטלפון קבצים של וואטסאפ.", pal.text, 17f))
            return
        }
        for (w in apps) {
            if (apps.size > 1) body.addView(a.label(w.name, 20f, pal.text, bold = true).apply {
                setPadding(0, a.dpi(16), 0, 0)
            })
            backupCard(w)
            mediaCard(w)
        }
        groupsCard(apps.first())
        driveCard(apps.first())
    }

    private fun groupsCard(w: WaApp) {
        val c = card("תיוג לפי קבוצה")
        c.addView(text(
            "כדי לסנן תמונות לפי קבוצה, מייצאים את הקבוצה מוואטסאפ לכאן – פעם אחת לכל קבוצה חשובה. " +
                "שום דבר לא מועתק; האפליקציה רק קוראת אילו קבצים שייכים לקבוצה."
        ))
        c.addView(text(
            "בוואטסאפ, בתוך הקבוצה: ⋮ ← עוד ← ייצוא צ'אט ← „כולל מדיה“ ← בוחרים „תיוג קבוצה בארכיון“.",
            pal.text
        ))
        val status = text("", pal.text2, 14f)
        c.addView(status)
        a.async({ a.archive.groups().values.groupingBy { it }.eachCount() }) { counts ->
            status.text = if (counts.isEmpty()) "עדיין לא תויגו קבוצות."
            else "תויגו: " + counts.entries.sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key} (${fmtCount(it.value)})" }
        }
        c.addView(a.button("פתיחת וואטסאפ", pal) { a.openApp(w.pkg) }, buttonLp())
    }

    private fun backupCard(w: WaApp) {
        val c = card("גיבוי הצ'אטים")
        c.addView(text(
            "וואטסאפ שומרת כל לילה גיבוי של כל הצ'אטים בזיכרון הטלפון. אם מוחקים את וואטסאפ, הגיבוי הזה נמחק איתה – " +
                "לכן כדאי לשמור ממנו עותק בארכיון."
        ))
        val status = text("בודק…", pal.text)
        c.addView(status)
        a.async({ WhatsApp.latestBackup(w) to WhatsApp.lastCopy(w, a.archive) }) { (latest, copy) ->
            status.text = (if (latest == null) "לא נמצא גיבוי בטלפון."
            else "הגיבוי האחרון בטלפון: ${fmtDateTime(latest.lastModified())} · ${fmtSize(latest.length())}") +
                "\n" + (if (copy == null) "עדיין לא נשמר עותק בארכיון." else "עותק אחרון בארכיון: ${copy.name}")
            if (latest == null) return@async
            c.addView(a.button("שמירת עותק בארכיון", pal, pal.accentFill) {
                status.text = "מעתיק…"
                a.async({ WhatsApp.copyBackup(w, a.archive) }) {
                    a.toast("העותק נשמר")
                    build()
                }
            }, buttonLp())
            c.addView(a.label("איך משחזרים?", 15f, pal.accent, bold = true).apply {
                setPadding(0, a.dpi(12), 0, 0)
                setOnClickListener { restoreHelp(w) }
            })
        }
    }

    private fun restoreHelp(w: WaApp) {
        AlertDialog.Builder(a).setTitle("שחזור מהעותק")
            .setMessage(
                "1. מתקינים את וואטסאפ, אבל עדיין לא פותחים אותה.\n" +
                    "2. מעתיקים את הקובץ שמתחיל ב-msgstore מהתיקייה\n${a.archive.waBackupDir.path}/${w.folder}/<תאריך>\n" +
                    "אל התיקייה\n${w.root.path}/Databases\n" +
                    "(אם שם הקובץ כולל תאריך, משנים אותו ל-msgstore.db.crypt עם אותו מספר בסוף.)\n" +
                    "3. פותחים את וואטסאפ ומאמתים את מספר הטלפון. וואטסאפ תציע לשחזר מהגיבוי."
            )
            .setPositiveButton("הבנתי", null).show()
    }

    private fun mediaCard(w: WaApp) {
        val c = card("תמונות וסרטונים מוואטסאפ")
        val status = text("סופר…", pal.text)
        c.addView(status)
        a.async({ WhatsApp.mediaItems(w, a.archive.groups()) }) { items ->
            val photos = items.filter { !it.video }
            val videos = items.filter { it.video }
            status.text = "תמונות: ${fmtCount(photos.size)} · ${fmtSize(photos.sumOf { it.size })}\n" +
                "סרטונים: ${fmtCount(videos.size)} · ${fmtSize(videos.sumOf { it.size })}"
        }
        c.addView(a.button("עיון ומחיקה", pal, pal.accentFill) { a.push(GalleryScreen(a, WaLibrary(a, w))) }, buttonLp())
        c.addView(a.button("מחיקה לפי קבוצה", pal) { byGroupHelp(w) }, buttonLp())
    }

    private fun byGroupHelp(w: WaApp) {
        AlertDialog.Builder(a).setTitle("מחיקה לפי קבוצה")
            .setMessage(
                "את המידע מאיזו קבוצה הגיעה כל תמונה רק וואטסאפ עצמה יודעת, ולכן עושים את זה שם:\n\n" +
                    "וואטסאפ ← ⋮ ← הגדרות ← אחסון ונתונים ← ניהול אחסון\n\n" +
                    "בוחרים קבוצה, מסמנים „בחר הכול“ ומוחקים."
            )
            .setPositiveButton("פתיחת וואטסאפ") { _, _ -> a.openApp(w.pkg) }
            .setNegativeButton("סגירה", null).show()
    }

    private fun driveCard(w: WaApp) {
        val c = card("הגיבוי לחשבון גוגל")
        c.addView(text(
            "ההמלצה: להשאיר את הגיבוי לגוגל רק לצ'אטים, בלי סרטונים. כך הוא תופס מעט מקום, " +
                "ואם הטלפון אובד – ההודעות לא הולכות לאיבוד. התמונות והסרטונים נשמרים בארכיון."
        ))
        c.addView(text(
            "בוואטסאפ: ⋮ ← הגדרות ← צ'אטים ← גיבוי צ'אטים ← לכבות את „כולל סרטונים“.\n" +
                "הגיבוי הבא יחליף את הקודם בחשבון גוגל ויפנה את המקום.",
            pal.text
        ))
        c.addView(a.button("פתיחת וואטסאפ", pal) { a.openApp(w.pkg) }, buttonLp())
    }
}
