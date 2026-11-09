package io.github.ivoryprd.shoten.render

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.dict.DictionaryDb
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Shows rendered lookup results as a vertical list of entries. */
class ResultsView(context: Context) : LinearLayout(context) {
    private val ui = Ui(context)
    var textScale: Float = 1f
    private var pendingGroups: List<RenderedGroup> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0

    init {
        orientation = VERTICAL
    }

    fun showMessage(text: CharSequence) {
        generation++
        removeAllViews()
        addView(ui.text(text, 15f * textScale, ui.muted).apply {
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
        })
    }

    /** How new results come in: as they are, or sliding in from the side a link or back leads to. */
    enum class Transition { NONE, FORWARD, BACK }

    fun bind(rendered: RenderedResult, initialGroups: Int = 8, transition: Transition = Transition.NONE) {
        generation++
        removeAllViews()
        val groups = rendered.groups
        groups.take(initialGroups).forEach { addView(groupView(it)) }
        pendingGroups = groups.drop(initialGroups)
        if (pendingGroups.isNotEmpty()) addMoreButton()
        if (rendered.kanji.isNotEmpty()) {
            addView(ui.text(context.getString(R.string.results_kanji), 12f * textScale, ui.muted).apply {
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), 0)
                typeface = Typeface.DEFAULT_BOLD
            })
            rendered.kanji.forEach { addView(kanjiView(it)) }
        }
        if (transition != Transition.NONE) {
            val dx = ui.dp(28).toFloat() * if (transition == Transition.FORWARD) 1 else -1
            for (i in 0 until childCount) getChildAt(i).enter(i, translationX = dx)
        }
    }

    /** Fades the [index]th of some new views in from ([translationX], [translationY]), a little after the one before. */
    private fun View.enter(index: Int, translationX: Float = 0f, translationY: Float = 0f) {
        alpha = 0f
        this.translationX = translationX
        this.translationY = translationY
        animate().alpha(1f).translationX(0f).translationY(0f)
            .setStartDelay(minOf(index, 5) * 30L).setDuration(240).setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    private fun addMoreButton() {
        val button = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = context.resources.getQuantityString(R.plurals.results_more, pendingGroups.size, pendingGroups.size)
            setTextColor(ui.accent)
            isAllCaps = false
        }
        button.setOnClickListener {
            var insertAt = indexOfChild(button).let { if (it < 0) childCount else it }
            removeView(button)
            pendingGroups.forEachIndexed { i, g -> addView(groupView(g).apply { enter(i, translationY = ui.dp(12).toFloat()) }, insertAt++) }
            pendingGroups = emptyList()
        }
        addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun groupView(g: RenderedGroup): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(12))
        }
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = ui.text(g.header, 24f * textScale, ui.text).apply {
            textLocale = g.locale
            setLineSpacing(0f, 1.05f)
        }
        // Its kanji open their entries (see ResultPresenter).
        if ((g.header as? Spanned)?.getSpans(0, g.header.length, ActionSpan::class.java)?.isNotEmpty() == true) onCharTapped(header)
        headerRow.addView(header, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(ui.iconButton(R.drawable.ic_copy, context.getString(R.string.action_copy)) { copy(g.copyText) })
        container.addView(headerRow)
        g.subtitle?.let { container.addView(ui.text(it, 16f * textScale, ui.text).apply { setPadding(0, ui.dp(2), 0, 0) }) }
        g.reasons?.let { container.addView(ui.text(it, 13f * textScale, ui.muted).apply { setPadding(0, ui.dp(2), 0, 0) }) }
        g.badges?.let { container.addView(chipLine(it).apply { textLocale = g.locale }) }

        for (entry in g.entries) {
            container.addView(ui.text(entry.dictionaryTitle, 11f * textScale, ui.muted).apply {
                setPadding(0, ui.dp(12), 0, ui.dp(2))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isAllCaps = true
                letterSpacing = 0.08f
            })
            entry.tags?.let { container.addView(chipLine(it).apply { textLocale = g.locale }) }
            for (block in entry.blocks) container.addView(blockView(block, g.locale))
        }

        val wrapper = LinearLayout(context).apply { orientation = VERTICAL }
        wrapper.addView(container)
        wrapper.addView(View(context).apply { setBackgroundColor(ui.divider) }, LayoutParams(LayoutParams.MATCH_PARENT, 1))
        return wrapper
    }

    private fun chipLine(text: CharSequence) = ui.text(text, 14f * textScale, ui.text).apply {
        setPadding(0, ui.dp(4), 0, ui.dp(2))
        setLineSpacing(ui.dp(4).toFloat(), 1f)
        movementMethod = LinkMovementMethod.getInstance()
    }

    private fun blockView(block: GlossBlock, locale: Locale): View = when (block) {
        is GlossBlock.Text -> ui.text(block.text, 15f * textScale, ui.text).apply {
            textLocale = locale
            setLineSpacing(ui.dp(3).toFloat(), 1f)
            setPadding(0, ui.dp(2), 0, ui.dp(2))
            movementMethod = LinkMovementMethod.getInstance()
        }
        is GlossBlock.Table -> tableView(block, locale)
        is GlossBlock.Image -> imageView(block)
    }

    private fun tableView(table: GlossBlock.Table, locale: Locale): View {
        val layout = TableLayout(context)
        val border = GradientDrawable().apply { setStroke(1, ui.divider) }
        for (row in table.rows) {
            val tr = TableRow(context)
            for (cell in row) {
                val tv = ui.text(cell.text, 14f * textScale, ui.text).apply {
                    textLocale = locale
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
                    background = border.constantState?.newDrawable()?.mutate() ?: border
                    if (cell.header) typeface = Typeface.DEFAULT_BOLD
                    movementMethod = LinkMovementMethod.getInstance()
                }
                tr.addView(tv, TableRow.LayoutParams().apply { span = cell.colSpan })
            }
            layout.addView(tr)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, ui.dp(6), 0, ui.dp(6))
            addView(layout)
        }
    }

    private fun imageView(block: GlossBlock.Image): View {
        val image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = block.description
            visibility = View.GONE
        }
        val maxWidth = ui.dp(block.widthDp?.coerceIn(16f, 320f) ?: 240f)
        val db = block.dictionary as? DictionaryDb ?: return image
        val requestGeneration = generation
        IMAGE_EXECUTOR.execute {
            val bitmap = runCatching { decode(db.media(block.path), maxWidth) }.getOrNull()
            mainHandler.post {
                if (bitmap != null && requestGeneration == generation) {
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                }
            }
        }
        return image.apply {
            layoutParams = LayoutParams(maxWidth, LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(6) }
        }
    }

    private fun decode(data: ByteArray?, maxWidth: Int): Bitmap? {
        if (data == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder handles more formats than BitmapFactory (e.g. AVIF on Android 12+).
            val source = ImageDecoder.createSource(ByteBuffer.wrap(data))
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                if (width > maxWidth) {
                    val scale = maxWidth.toFloat() / width
                    decoder.setTargetSize(maxWidth, (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun kanjiView(k: RenderedKanji): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12))
        }
        row.addView(ui.text(k.character, 44f * textScale, ui.text).apply {
            textLocale = Locale.JAPANESE
            setPadding(0, 0, ui.dp(14), 0)
            setOnLongClickListener { copy(k.character); true }
        })
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(ui.text(k.body, 15f * textScale, ui.text).apply { textLocale = Locale.JAPANESE })
        column.addView(ui.text(k.dictionaryTitle, 11f * textScale, ui.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
        row.addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /**
     * Makes taps on characters of [view] with an [ActionSpan] run it. Unlike [LinkMovementMethod],
     * this finds the character tapped within ruby too (where the layout knows only the whole
     * base text): CJK characters are as wide as each other.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun onCharTapped(view: TextView) {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val text = view.text as? Spanned ?: return false
                val index = charAt(view, text, e.x, e.y) ?: return false
                val span = text.getSpans(index, index + 1, ActionSpan::class.java).firstOrNull() ?: return false
                span.onClick(view)
                return true
            }
        })
        view.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
    }

    private fun charAt(view: TextView, text: Spanned, x: Float, y: Float): Int? {
        val layout = view.layout ?: return null
        val line = layout.getLineForVertical((y - view.totalPaddingTop + view.scrollY).toInt())
        val h = x - view.totalPaddingLeft + view.scrollX
        if (h < layout.getLineLeft(line) || h > layout.getLineRight(line)) return null
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        fun position(offset: Int) = if (offset >= lineEnd) layout.getLineRight(line) else layout.getPrimaryHorizontal(offset)
        for (ruby in text.getSpans(lineStart, lineEnd, RubySpan::class.java)) {
            val a = text.getSpanStart(ruby)
            val b = text.getSpanEnd(ruby)
            val x0 = position(a)
            val x1 = position(b)
            if (h < x0 || h >= x1) continue
            // The base text, centered under its ruby.
            val base = view.paint.measureText(text, a, b)
            val left = (x0 + x1 - base) / 2
            val k = ((h - left) / base * (b - a)).toInt().coerceIn(0, b - a - 1)
            return if (k > 0 && Character.isLowSurrogate(text[a + k])) a + k - 1 else a + k
        }
        val offset = layout.getOffsetForHorizontal(line, h)
        val index = if (offset > lineStart && (offset >= lineEnd || h < position(offset))) offset - 1 else offset
        return index.takeIf { it < text.length }?.let { if (it > 0 && Character.isLowSurrogate(text[it])) it - 1 else it }
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Shoten", text))
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, context.getString(R.string.copied, text), Toast.LENGTH_SHORT).show()
        }
    }

    /** Small helpers for building views with the app's colors. */
    class Ui(private val context: Context) {
        val text = context.getColor(R.color.text_primary)
        val muted = context.getColor(R.color.text_secondary)
        val accent = context.getColor(R.color.accent)
        val divider = context.getColor(R.color.divider)

        fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()
        fun dp(v: Float): Int = (v * context.resources.displayMetrics.density).roundToInt()

        fun text(value: CharSequence, sizeSp: Float, color: Int) = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
        }

        fun iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            contentDescription = description
            setColorFilter(muted)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
            layoutParams = ViewGroup.LayoutParams(dp(44), dp(44))
        }
    }

    companion object {
        private val IMAGE_EXECUTOR = Executors.newSingleThreadExecutor()

        /** Builds the render theme from the current configuration and settings. */
        fun theme(context: Context, textScale: Float): RenderTheme {
            val dm = context.resources.displayMetrics
            val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            return RenderTheme(
                density = dm.density,
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f * textScale, dm),
                textColor = context.getColor(R.color.text_primary),
                mutedColor = context.getColor(R.color.text_secondary),
                accentColor = context.getColor(R.color.accent),
                linkColor = context.getColor(R.color.link),
                chipBackground = context.getColor(R.color.chip_background),
                chipText = context.getColor(R.color.chip_text),
                dark = night,
            )
        }
    }
}
