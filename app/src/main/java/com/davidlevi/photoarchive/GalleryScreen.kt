package com.davidlevi.photoarchive

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** A set of photos/videos the gallery can show: the archive, or WhatsApp's folders. */
interface Library {
    val title: String
    /** Choices for the "source" filter. */
    val sources: List<Src>
    fun load(): List<MediaItem>
}

class ArchiveLibrary(private val a: MainActivity) : Library {
    override val title = "התמונות שלי"
    override val sources = listOf(Src.CAMERA, Src.SCREENSHOT, Src.WHATSAPP, Src.OTHER)
    override fun load() = a.archive.items()
}

/** A frame as tall as it is wide. */
class SquareFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}

/**
 * Grid by month with filters (year, photo/video, source, biggest first).
 * Long-press or a month header starts selecting; selected items go to the trash together.
 */
class GalleryScreen(a: MainActivity, private val lib: Library) : Screen(a) {
    private sealed class Row {
        class Header(val label: String, val items: List<MediaItem>) : Row()
        class Cell(val item: MediaItem) : Row()
    }

    private var all: List<MediaItem> = emptyList()
    private var shown: List<MediaItem> = emptyList()
    private var rows: List<Row> = emptyList()
    private val selected = LinkedHashMap<String, MediaItem>()
    private var selecting = false
    private var loaded = false

    private var year: Int? = null
    private var type = 0 // 0 everything, 1 photos, 2 videos
    private var src: Src? = null
    private var bySize = false

    private val span = 4
    private val cellPx = a.resources.displayMetrics.widthPixels / span

    private val title = a.label(lib.title, 20f, pal.text, bold = true)
    private val subtitle = a.label("טוען…", 14f, pal.muted)
    private val yearChip = chip { pickYear() }
    private val typeChip = chip { pickType() }
    private val srcChip = chip { pickSrc() }
    private val sortChip = chip { pickSort() }
    private val empty = a.label("", 17f, pal.muted).apply {
        gravity = Gravity.CENTER
        setPadding(a.dpi(32), 0, a.dpi(32), 0)
        visibility = View.GONE
    }
    private val adapter = Adapter()
    private val grid = RecyclerView(a).apply {
        layoutManager = GridLayoutManager(a, span).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (rows[position] is Row.Header) span else 1
            }
        }
        adapter = this@GalleryScreen.adapter
    }

    private val selInfo = a.label("", 16f, pal.text, bold = true)
    private val selBar = a.vbox().apply {
        setBackgroundColor(pal.card)
        setPadding(a.dpi(16), a.dpi(10), a.dpi(16), a.dpi(12))
        visibility = View.GONE
        addView(a.hbox().apply {
            addView(selInfo, lp(0, WRAP, 1f))
            addView(textButton("בחר הכול") { selectAll() })
            addView(textButton("ביטול") { endSelection() })
        })
        addView(a.hbox().apply {
            addView(a.button("מחיקה", pal, pal.dangerFill) { confirmDelete() }, lp(0, WRAP, 1f))
            addView(View(a), lp(a.dpi(10), 1))
            addView(a.button("שיתוף", pal) { a.share(selected.values.toList()) }, lp(0, WRAP, 1f))
        }, lp(MATCH, WRAP).apply { topMargin = a.dpi(8) })
    }

    override val view: View = a.vbox().apply {
        addView(a.hbox().apply {
            setPaddingRelative(a.dpi(16), a.dpi(12), a.dpi(8), a.dpi(8))
            addView(a.label("→", 26f, pal.accent, bold = true).apply {
                setPaddingRelative(a.dpi(4), a.dpi(2), a.dpi(16), a.dpi(2))
                setOnClickListener { a.back() }
            })
            addView(a.vbox().apply { addView(title); addView(subtitle) }, lp(0, WRAP, 1f))
        })
        addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(a.hbox().apply {
                setPadding(a.dpi(12), 0, a.dpi(12), a.dpi(8))
                for (c in listOf(yearChip, typeChip, srcChip, sortChip)) {
                    addView(c, lp(WRAP, WRAP).apply { marginEnd = a.dpi(8) })
                }
            })
        })
        addView(FrameLayout(a).apply {
            addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        }, lp(MATCH, 0, 1f))
        addView(selBar, lp(MATCH, WRAP))
    }

    init {
        load()
    }

    private fun load() {
        subtitle.text = "טוען…"
        a.async({ lib.load() }) { list ->
            all = list
            loaded = true
            val paths = list.mapTo(HashSet()) { it.file.path }
            selected.keys.retainAll(paths)
            refilter()
        }
    }

    override fun onBack(): Boolean {
        if (!selecting) return false
        endSelection()
        return true
    }

    // ---- filters ----

    private fun chip(onClick: () -> Unit): TextView = a.label("", 14f, pal.text).apply {
        background = roundRect(pal.card, a.dp(18f), pal.line, a.dpi(1))
        setPadding(a.dpi(14), a.dpi(8), a.dpi(14), a.dpi(8))
        setOnClickListener { onClick() }
    }

    private fun textButton(text: String, onClick: () -> Unit): TextView =
        a.label(text, 15f, pal.accent, bold = true).apply {
            setPadding(a.dpi(10), a.dpi(6), a.dpi(10), a.dpi(6))
            setOnClickListener { onClick() }
        }

    private fun matches(m: MediaItem, checkYear: Boolean = true, checkSrc: Boolean = true) =
        (!checkYear || year == null || m.ym / 100 == year) &&
            (type == 0 || (type == 2) == m.video) &&
            (!checkSrc || src == null || m.src == src)

    private fun refilter() {
        shown = all.filter { matches(it) }
        if (bySize) shown = shown.sortedByDescending { it.size }
        rows = if (bySize) shown.map { Row.Cell(it) } else monthRows(shown)

        yearChip.text = "${year ?: "כל השנים"} ▾"
        typeChip.text = "${arrayOf("תמונות וסרטונים", "רק תמונות", "רק סרטונים")[type]} ▾"
        srcChip.text = "${src?.he ?: "כל המקורות"} ▾"
        sortChip.text = "${if (bySize) "הגדולים קודם" else "לפי תאריך"} ▾"
        subtitle.text = "${fmtCount(shown.size)} פריטים · ${fmtSize(shown.sumOf { it.size })}"

        empty.text = if (all.isEmpty()) "אין כאן עדיין תמונות.\n\nאפשר להעביר תמונות לכאן במסך „איסוף תמונות מהטלפון“."
        else "אין פריטים שמתאימים לסינון."
        empty.visibility = if (loaded && shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        updateSelBar()
    }

    /** Month headers followed by that month's items; [list] is newest first. */
    private fun monthRows(list: List<MediaItem>): List<Row> {
        val out = ArrayList<Row>(list.size + 64)
        var i = 0
        while (i < list.size) {
            val ym = list[i].ym
            var j = i
            while (j < list.size && list[j].ym == ym) j++
            val part = list.subList(i, j)
            out += Row.Header("${MONTHS_HE[ym % 100 - 1]} ${ym / 100}", part)
            for (m in part) out += Row.Cell(m)
            i = j
        }
        return out
    }

    private fun pickYear() {
        val counts = all.filter { matches(it, checkYear = false) }.groupingBy { it.ym / 100 }.eachCount()
        val keys = listOf<Int?>(null) + counts.keys.sortedDescending()
        val labels = keys.map {
            if (it == null) "כל השנים (${fmtCount(counts.values.sum())})" else "$it (${fmtCount(counts[it] ?: 0)})"
        }
        AlertDialog.Builder(a).setTitle("שנה")
            .setItems(labels.toTypedArray()) { _, i -> year = keys[i]; refilter() }.show()
    }

    private fun pickType() {
        AlertDialog.Builder(a).setTitle("סוג")
            .setItems(arrayOf("תמונות וסרטונים", "רק תמונות", "רק סרטונים")) { _, i -> type = i; refilter() }.show()
    }

    private fun pickSrc() {
        val counts = all.filter { matches(it, checkSrc = false) }.groupingBy { it.src }.eachCount()
        val keys = listOf<Src?>(null) + lib.sources
        val labels = keys.map {
            if (it == null) "כל המקורות (${fmtCount(counts.values.sum())})" else "${it.he} (${fmtCount(counts[it] ?: 0)})"
        }
        AlertDialog.Builder(a).setTitle("מקור")
            .setItems(labels.toTypedArray()) { _, i -> src = keys[i]; refilter() }.show()
    }

    private fun pickSort() {
        AlertDialog.Builder(a).setTitle("סדר")
            .setItems(arrayOf("לפי תאריך (מחולק לחודשים)", "הגדולים קודם")) { _, i -> bySize = i == 1; refilter() }.show()
    }

    // ---- selection ----

    private fun toggle(m: MediaItem) {
        val k = m.file.path
        if (selected.remove(k) == null) selected[k] = m
        if (selected.isEmpty()) selecting = false
        adapter.notifyDataSetChanged()
        updateSelBar()
    }

    private fun toggleGroup(items: List<MediaItem>, on: Boolean) {
        selecting = true
        for (m in items) if (on) selected[m.file.path] = m else selected.remove(m.file.path)
        if (selected.isEmpty()) selecting = false
        adapter.notifyDataSetChanged()
        updateSelBar()
    }

    private fun selectAll() = toggleGroup(shown, true)

    private fun endSelection() {
        selected.clear()
        selecting = false
        adapter.notifyDataSetChanged()
        updateSelBar()
    }

    private fun updateSelBar() {
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        selInfo.text = "נבחרו ${fmtCount(selected.size)} · ${fmtSize(selected.values.sumOf { it.size })}"
    }

    private fun confirmDelete() {
        val items = selected.values.toList()
        if (items.isEmpty()) return
        AlertDialog.Builder(a)
            .setTitle("מחיקה")
            .setMessage(
                "להעביר ${fmtCount(items.size)} פריטים (${fmtSize(items.sumOf { it.size })}) לסל המחזור?\n\n" +
                    "הם יימחקו סופית אחרי ${Archive.TRASH_DAYS} יום, ועד אז אפשר לשחזר אותם בהגדרות. " +
                    "כדי לפנות את המקום מיד: הגדרות ← ריקון סל המחזור."
            )
            .setPositiveButton("מחיקה") { _, _ -> delete(items) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun delete(items: List<MediaItem>) {
        subtitle.text = "מוחק…"
        a.async({ a.archive.trash(items) }) { n ->
            a.rescan(items.map { it.file.path })
            a.toast("${fmtCount(n)} פריטים הועברו לסל המחזור")
            endSelection()
            removeGone(items)
        }
    }

    /** Drops items whose files are gone (deleted here or in the viewer). */
    private fun removeGone(items: List<MediaItem>) {
        val gone = items.mapTo(HashSet()) { it.file.path }
        all = all.filter { it.file.path !in gone || it.file.exists() }
        refilter()
    }

    private fun openViewer(m: MediaItem) {
        val i = shown.indexOf(m)
        if (i < 0) return
        a.push(ViewerScreen(a, shown.toMutableList(), i) { removeGone(listOf(it)) })
    }

    // ---- grid ----

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) HeaderHolder(a.hbox()) else CellHolder(SquareFrame(a))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(r)
                is Row.Cell -> (holder as CellHolder).bind(r.item)
            }
        }
    }

    private inner class HeaderHolder(val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        val text = a.label("", 16f, pal.text, bold = true)
        val action = a.label("", 14f, pal.accent, bold = true)

        init {
            box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            box.setPadding(a.dpi(14), a.dpi(16), a.dpi(14), a.dpi(8))
            box.addView(text, lp(0, WRAP, 1f))
            box.addView(action)
        }

        fun bind(h: Row.Header) {
            text.text = "${h.label} · ${fmtCount(h.items.size)}"
            val allOn = h.items.all { selected.containsKey(it.file.path) }
            action.text = if (allOn) "ביטול הבחירה" else "בחירת החודש"
            box.setOnClickListener { toggleGroup(h.items, !allOn) }
        }
    }

    private inner class CellHolder(val box: SquareFrame) : RecyclerView.ViewHolder(box) {
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(pal.line)
        }
        val shade = View(a).apply { setBackgroundColor(0x66000000) }
        val play = a.label("▶", 13f, Color.WHITE, bold = true).apply { setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        val check = a.label("", 14f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }

        init {
            box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            box.setPadding(a.dpi(1), a.dpi(1), a.dpi(1), a.dpi(1))
            box.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            box.addView(shade, FrameLayout.LayoutParams(MATCH, MATCH))
            box.addView(play, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(a.dpi(6), 0, a.dpi(6), a.dpi(4))
            })
            box.addView(check, FrameLayout.LayoutParams(a.dpi(24), a.dpi(24), Gravity.TOP or Gravity.END).apply {
                setMargins(a.dpi(6), a.dpi(6), a.dpi(6), 0)
            })
        }

        fun bind(m: MediaItem) {
            a.thumbs.load(m, img, cellPx)
            val on = selected.containsKey(m.file.path)
            play.visibility = if (m.video) View.VISIBLE else View.GONE
            shade.visibility = if (on) View.VISIBLE else View.GONE
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            check.text = if (on) "✓" else ""
            check.background = if (on) roundRect(pal.accentFill, a.dp(12f))
            else roundRect(0x33000000, a.dp(12f), Color.WHITE, a.dpi(2))
            box.setOnClickListener { if (selecting) toggle(m) else openViewer(m) }
            box.setOnLongClickListener {
                selecting = true
                toggle(m)
                true
            }
        }
    }
}
