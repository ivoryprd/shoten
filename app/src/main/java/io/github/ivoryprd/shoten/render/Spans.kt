package io.github.ivoryprd.shoten.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/** Draws [ruby] (furigana) centered above the spanned base text. */
class RubySpan(private val ruby: String, private val scale: Float = 0.55f, private val rubyColor: Int? = null) : ReplacementSpan() {
    private fun rubyPaint(paint: Paint) = TextPaint(paint).apply {
        textSize = paint.textSize * scale
        if (rubyColor != null) color = rubyColor
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val baseWidth = paint.measureText(text, start, end)
        val rp = rubyPaint(paint)
        val rubyWidth = rp.measureText(ruby)
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val rubyMetrics = rp.fontMetricsInt
            val extra = rubyMetrics.descent - rubyMetrics.ascent
            fm.ascent -= extra
            fm.top -= extra
        }
        return ceil(max(baseWidth, rubyWidth)).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val baseWidth = paint.measureText(text, start, end)
        val rp = rubyPaint(paint)
        val rubyWidth = rp.measureText(ruby)
        val width = max(baseWidth, rubyWidth)
        canvas.drawText(text, start, end, x + (width - baseWidth) / 2, y.toFloat(), paint)
        canvas.drawText(ruby, x + (width - rubyWidth) / 2, y + paint.ascent() - rp.descent(), rp)
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

/** A vertical bar in the margin, like a block quote. */
class BarSpan(private val color: Int, private val stripe: Int, private val gap: Int) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = stripe + gap

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        val oldColor = p.color
        val oldStyle = p.style
        p.color = color
        p.style = Paint.Style.FILL
        val edge = x + dir * stripe
        c.drawRect(minOf(x, edge).toFloat(), top.toFloat(), maxOf(x, edge).toFloat(), bottom.toFloat(), p)
        p.color = oldColor
        p.style = oldStyle
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
