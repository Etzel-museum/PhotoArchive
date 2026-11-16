package com.davidlevi.photoarchive

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** "Access all files" on Android 11+, the old storage permission on Android 10. */
fun Context.hasStorageAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** A full-screen page; MainActivity keeps them on a back stack. */
abstract class Screen(val a: MainActivity) {
    val pal: Palette get() = a.pal
    abstract val view: View
    /** Called each time the screen comes to the front, also when returning from another app. */
    open fun onShow() {}
    /** True if the back press was handled inside the screen (e.g. it ended a selection). */
    open fun onBack(): Boolean = false
}

class MainActivity : Activity() {
    lateinit var pal: Palette
    lateinit var archive: Archive
    lateinit var thumbs: Thumbs

    private lateinit var frame: FrameLayout
    private val stack = ArrayList<Screen>()
    private val ui = Handler(Looper.getMainLooper())
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = Palette.of(this)
        archive = Archive(this)
        thumbs = Thumbs()
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        if (!pal.dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        frame = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(pal.bg)
        }
        setContentView(frame)
        if (hasAccess()) start() else push(PermissionScreen(this))
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) { firstResume = false; return }
        val top = stack.lastOrNull() ?: return
        if (top is PermissionScreen) { if (hasAccess()) start() } else top.onShow()
    }

    fun hasAccess(): Boolean = hasStorageAccess()

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAccess()) start()
    }

    private fun start() {
        stack.clear()
        async({ archive.ensure(); archive.purgeTrash() }) {}
        push(HomeScreen(this))
    }

    fun push(s: Screen) {
        stack += s
        display(s)
    }

    fun pop() {
        if (stack.size <= 1) { finish(); return }
        stack.removeAt(stack.size - 1)
        display(stack[stack.size - 1])
    }

    private fun display(s: Screen) {
        frame.removeAllViews()
        (s.view.parent as? FrameLayout)?.removeView(s.view)
        frame.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        s.onShow()
    }

    fun back() {
        val top = stack.lastOrNull()
        if (top == null || !top.onBack()) pop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    /** Runs [work] on a background thread and hands its result to [done] on the main thread. */
    fun <T> async(work: () -> T, done: (T) -> Unit) {
        Thread {
            val r = runCatching(work)
            ui.post {
                if (isDestroyed) return@post
                r.onSuccess(done).onFailure { toast("שגיאה: ${it.message}") }
            }
        }.start()
    }

    fun post(r: () -> Unit) {
        ui.post { r() }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Tells the system gallery that these paths changed (moved away, deleted or restored). */
    fun rescan(paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(this, paths.toTypedArray(), null, null)
    }

    fun uriOf(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    fun share(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val list = items.take(100)
        val uris = ArrayList(list.map { uriOf(it.file) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.type = when {
            list.all { it.video } -> "video/*"
            list.none { it.video } -> "image/*"
            else -> "*/*"
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "שיתוף"))
        if (items.size > list.size) toast("אפשר לשתף עד 100 פריטים בבת אחת")
    }

    fun openVideo(f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(f), "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: ActivityNotFoundException) { toast("אין בטלפון אפליקציה לניגון סרטונים") }
    }

    fun openApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) toast("האפליקציה לא מותקנת") else startActivity(i)
    }

    /** Title row with a back arrow (pointing right, since the app is right-to-left). */
    fun topBar(title: String, back: Boolean = true): LinearLayout = hbox().apply {
        setPaddingRelative(dpi(16), dpi(12), dpi(8), dpi(12))
        if (back) addView(label("→", 26f, pal.accent, bold = true).apply {
            setPaddingRelative(dpi(4), dpi(2), dpi(16), dpi(2))
            setOnClickListener { back() }
        })
        addView(label(title, 21f, pal.text, bold = true), lp(0, WRAP, 1f))
    }

    /** A rounded card container. */
    fun card(): LinearLayout = vbox().apply {
        background = roundRect(pal.card, dp(16f))
        setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
    }

    /** Top bar + a scrolling column; returns the page and the column to fill. */
    fun page(title: String, back: Boolean = true): Pair<View, LinearLayout> {
        val body = vbox().apply { setPadding(dpi(16), dpi(4), dpi(16), dpi(24)) }
        val v = vbox().apply {
            addView(topBar(title, back))
            addView(ScrollView(this@MainActivity).apply { addView(body) }, lp(MATCH, 0, 1f))
        }
        return v to body
    }
}

/** Shown until the user grants "access all files". */
class PermissionScreen(a: MainActivity) : Screen(a) {
    override val view: View

    init {
        val (v, body) = a.page("ארכיון תמונות", back = false)
        view = v
        body.addView(a.label(
            "כדי לסדר את התמונות והסרטונים, האפליקציה צריכה גישה לקבצים בטלפון.\n\n" +
                "שום דבר לא נשלח לאינטרנט – הכול נשאר בטלפון.\n\n" +
                "במסך שייפתח, הדליקו את „גישה לכל הקבצים“ (או „אפשר ניהול של כל הקבצים“) וחזרו לכאן.",
            18f, pal.text
        ))
        body.addView(a.button("מתן הרשאה", pal, pal.accentFill) { a.requestAccess() }, lp(MATCH, WRAP).apply {
            topMargin = a.dpi(24)
        })
    }
}
