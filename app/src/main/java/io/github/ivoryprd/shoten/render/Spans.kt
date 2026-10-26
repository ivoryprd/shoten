package io.github.ivoryprd.shoten.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws [ruby] (furigana) centered above the spanned base text. Like in print, ruby that is wider
 * than its base may overhang neighboring characters that have no ruby of their own, so that long
 * readings don't tear the text apart.
 */
class RubySpan(private val ruby: String, private val scale: Float = 0.55f, private val rubyColor: Int? = null) : ReplacementSpan() {
    private val padding = FloatArray(2)

    private fun rubyPaint(paint: Paint) = TextPaint(paint).apply {
        textSize = paint.textSize * scale
        if (rubyColor != null) color = rubyColor
    }

    /** Computes the space needed left and right of the base text into [padding]. */
    private fun measure(paint: Paint, text: CharSequence, start: Int, end: Int, baseWidth: Float, rubyWidth: Float) {
        val excess = (rubyWidth - baseWidth) / 2
        if (excess <= 0f) {
            padding[0] = 0f
            padding[1] = 0f
            return
        }
        val overhang = paint.textSize * OVERHANG
        padding[0] = max(0f, excess - if (canOverhang(text, start - 1)) overhang else 0f)
        padding[1] = max(0f, excess - if (canOverhang(text, end)) overhang else 0f)
    }

    private fun canOverhang(text: CharSequence, index: Int): Boolean {
        if (index < 0 || index >= text.length || text[index].isWhitespace()) return false
        return (text as? Spanned)?.getSpans(index, index + 1, RubySpan::class.java).isNullOrEmpty()
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val baseWidth = paint.measureText(text, start, end)
        val rp = rubyPaint(paint)
        measure(paint, text, start, end, baseWidth, rp.measureText(ruby))
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val rubyMetrics = rp.fontMetricsInt
            val extra = rubyMetrics.descent - rubyMetrics.ascent
            fm.ascent -= extra
            fm.top -= extra
        }
        return ceil(padding[0] + baseWidth + padding[1]).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = styled(paint, text, start, end)
        val baseWidth = p.measureText(text, start, end)
        val rp = rubyPaint(p)
        val rubyWidth = rp.measureText(ruby)
        measure(p, text, start, end, baseWidth, rubyWidth)
        val baseX = x + padding[0]
        canvas.drawText(text, start, end, baseX, y.toFloat(), p)
        canvas.drawText(ruby, baseX + (baseWidth - rubyWidth) / 2, y + p.ascent() - rp.descent(), rp)
    }

    /**
     * Android doesn't apply character styles (colors of links, for example) to replacement spans,
     * only metric affecting ones; apply them here.
     */
    private fun styled(paint: Paint, text: CharSequence, start: Int, end: Int): Paint {
        val styles = (text as? Spanned)?.getSpans(start, end, CharacterStyle::class.java) ?: return paint
        if (styles.all { it is MetricAffectingSpan }) return paint
        val styled = TextPaint(paint)
        for (style in styles) if (style !is MetricAffectingSpan) style.updateDrawState(styled)
        return styled
    }

    private companion object {
        /** How far (in em of the base text) ruby may extend over each neighbor. */
        const val OVERHANG = 0.3f
    }
}

/** A rounded "chip" behind short text, used for tags such as parts of speech. */
class ChipSpan(
    private val background: Int,
    private val foreground: Int?,
    private val textScale: Float,
    private val bold: Boolean,
    private val paddingH: Float,
    private val paddingV: Float,
    private val marginEnd: Float,
    private val radius: Float,
) : ReplacementSpan() {
    private val rect = RectF()

    private fun chipPaint(paint: Paint) = TextPaint(paint).apply {
        textSize = paint.textSize * textScale
        isFakeBoldText = bold
        if (foreground != null) color = foreground
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return ceil(chipPaint(paint).measureText(text, start, end) + 2 * paddingH + marginEnd).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val tp = chipPaint(paint)
        val textWidth = tp.measureText(text, start, end)
        val metrics = tp.fontMetrics
        val center = y + (paint.ascent() + paint.descent()) / 2
        val half = (metrics.descent - metrics.ascent) / 2 + paddingV
        rect.set(x, center - half, x + textWidth + 2 * paddingH, center + half)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.drawRoundRect(rect, radius, radius, bg)
        canvas.drawText(text, start, end, x + paddingH, center - (metrics.ascent + metrics.descent) / 2, tp)
    }
}

/** Hanging indent with a list marker (bullet, number, ①...) drawn in the margin. */
class MarkerSpan(
    private val marker: String,
    private val indent: Int,
    private val markerWidth: Int,
    private val gap: Int,
    private val color: Int? = null,
) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = indent + markerWidth + gap

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        if (!first || marker.isEmpty()) return
        if ((text as? Spanned)?.getSpanStart(this) != start) return
        val oldColor = p.color
        val oldStyle = p.style
        if (color != null) p.color = color
        p.style = Paint.Style.FILL
        val w = p.measureText(marker)
        val mx = if (dir >= 0) x + indent + (markerWidth - w) else x - indent - markerWidth.toFloat()
        c.drawText(marker, mx, baseline.toFloat(), p)
        p.color = oldColor
        p.style = oldStyle
    }
}

/** A list item number drawn in a tinted circle in the margin. */
class NumberMarkerSpan(
    private val number: String,
    private val color: Int,
    private val background: Int,
    private val diameter: Int,
    private val gap: Int,
) : LeadingMarginSpan {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = diameter * if (number.length > 1) 0.54f else 0.62f
    }

    override fun getLeadingMargin(first: Boolean): Int = diameter + gap

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        if (!first) return
        if ((text as? Spanned)?.getSpanStart(this) != start) return
        val radius = diameter / 2f
        val cx = if (dir >= 0) x + radius else x - radius
        // Centered on the text of the first line (not on its ruby).
        val cy = baseline + (p.ascent() + p.descent()) / 2
        paint.color = background
        c.drawCircle(cx, cy, radius, paint)
        paint.color = color
        val metrics = paint.fontMetrics
        c.drawText(number, cx, cy - (metrics.ascent + metrics.descent) / 2, paint)
    }
}

/** A vertical bar in the margin, like a block quote. */
class BarSpan(private val color: Int, private val stripe: Int, private val gap: Int) : LeadingMarginSpan {
    private val rect = RectF()

    override fun getLeadingMargin(first: Boolean): Int = stripe + gap

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        // The last line may have extra space below it (see SpaceBelowSpan); the bar ends at the text.
        // (The end of the last line excludes its line break, which the span includes.)
        val spanned = text as? Spanned
        val last = spanned != null && end >= spanned.getSpanEnd(this) - 1
        val lineBottom = if (last) minOf(bottom, baseline + p.fontMetricsInt.descent) else bottom
        val oldColor = p.color
        val oldStyle = p.style
        p.color = color
        p.style = Paint.Style.FILL
        val edge = x + dir * stripe
        rect.set(minOf(x, edge).toFloat(), top.toFloat(), maxOf(x, edge).toFloat(), lineBottom.toFloat())
        c.drawRect(rect, p)
        p.color = oldColor
        p.style = oldStyle
    }
}

/**
 * Extra space below the last line of the spanned text (used for paragraph margins). Space above
 * a paragraph is added below the previous one instead: StaticLayout carries a changed ascent of
 * a line over to the next line of the paragraph, but the last line of a paragraph is safe.
 */
class SpaceBelowSpan(var space: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val spanned = text as? Spanned ?: return
        if (end < text.length && end >= spanned.getSpanEnd(this)) {
            fm.descent += space
            fm.bottom += space
        }
    }
}

/** A clickable span running [action]. */
class ActionSpan(private val color: Int?, private val underline: Boolean, private val action: (View) -> Unit) : ClickableSpan() {
    override fun onClick(widget: View) = action(widget)

    override fun updateDrawState(ds: TextPaint) {
        if (color != null) ds.color = color
        ds.isUnderlineText = underline
    }
}
