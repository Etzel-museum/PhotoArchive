package com.davidlevi.photoarchive

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.Toast
import java.util.zip.ZipInputStream

/**
 * Receives a WhatsApp "Export chat" share (a .zip, or a .txt plus media files) and tags every
 * WhatsApp file named in it with the chat's name. Only names are read; nothing is copied.
 */
class GroupTagActivity : Activity() {
    private lateinit var archive: Archive

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archive = Archive(this)
        if (!hasStorageAccess()) {
            Toast.makeText(this, "צריך קודם לפתוח את ארכיון תמונות ולאשר גישה לקבצים", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val uris = streams(intent)
        if (uris.isEmpty()) { finish(); return }
        val wait = AlertDialog.Builder(this).setMessage("קורא את הייצוא…").setCancelable(false).show()
        Thread {
            val r = runCatching { read(uris) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                wait.dismiss()
                val (guess, names) = r.getOrElse { null to emptySet() }
                if (names.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setMessage("לא נמצאו בייצוא תמונות או סרטונים.\n\nבייצוא הצ'אט צריך לבחור „כולל מדיה“.")
                        .setPositiveButton("אישור", null)
                        .setOnDismissListener { finish() }.show()
                } else ask(intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { chatName(it, hasExtension = false) } ?: guess, names)
            }
        }.start()
    }

    private fun ask(guess: String?, names: Set<String>) {
        val input = EditText(this).apply {
            setText(guess ?: "")
            setSingleLine()
        }
        val box = vbox().apply {
            setPadding(dpi(24), dpi(8), dpi(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("תיוג לפי קבוצה")
            .setMessage("נמצאו בייצוא ${fmtCount(names.size)} קבצים. מה שם הקבוצה?")
            .setView(box)
            .setPositiveButton("שמירה") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                Thread {
                    val ok = runCatching { archive.tagGroup(names, name) }.isSuccess
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) "תויגו ${fmtCount(names.size)} קבצים בשם „$name“" else "השמירה נכשלה",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            }
            .setNegativeButton("ביטול", null)
            .setOnDismissListener { finish() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
        else -> emptyList()
    }

    private fun displayName(u: Uri): String =
        runCatching {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: u.lastPathSegment ?: ""

    /** (chat name guessed from the file name, WhatsApp file names in the export). */
    private fun read(uris: List<Uri>): Pair<String?, Set<String>> {
        val names = HashSet<String>()
        var guess: String? = null
        for (u in uris) {
            val name = displayName(u)
            names += waNames(name)
            when {
                name.endsWith(".zip", ignoreCase = true) -> {
                    guess = guess ?: chatName(name)
                    runCatching {
                        contentResolver.openInputStream(u)?.use { s ->
                            ZipInputStream(s).use { z ->
                                while (true) {
                                    val e = z.nextEntry ?: break
                                    names += waNames(e.name)
                                    if (e.name.endsWith(".txt", ignoreCase = true)) {
                                        names += waNames(z.readBytes().toString(Charsets.UTF_8))
                                    }
                                }
                            }
                        }
                    }
                }
                name.endsWith(".txt", ignoreCase = true) -> {
                    guess = guess ?: chatName(name)
                    runCatching {
                        contentResolver.openInputStream(u)?.use { names += waNames(it.readBytes().toString(Charsets.UTF_8)) }
                    }
                }
            }
        }
        return guess to names
    }

    companion object {
        private val waFile = Regex("(?:IMG|VID|GIF|STK|AUD|PTT|DOC)-\\d{8}-WA\\d+\\.[A-Za-z0-9]{2,5}")
        private val chatPrefix = Regex(
            "^(?:WhatsApp Chat(?: with|\\s*-)?|צ['׳]אט (?:ב-?)?WhatsApp(?: עם)?|WhatsApp צ['׳]אט(?: עם)?)\\s*",
            RegexOption.IGNORE_CASE
        )

        fun waNames(text: String): Set<String> = waFile.findAll(text).map { it.value }.toSet()

        /** "WhatsApp Chat with משפחה.zip" → "משפחה". */
        fun chatName(fileName: String, hasExtension: Boolean = true): String? =
            (if (hasExtension) fileName.substringBeforeLast('.') else fileName)
                .replace(chatPrefix, "").trim().ifEmpty { null }
    }
}
