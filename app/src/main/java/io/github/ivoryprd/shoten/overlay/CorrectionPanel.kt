package io.github.ivoryprd.shoten.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.render.ResultsView
import kotlin.math.max

/**
 * Corrects recognized text by hand: the characters of the text being looked up as keys, and for
 * the selected one, the characters it may really be, and a key that deletes it.
 */
@SuppressLint("ViewConstructor")
class CorrectionPanel(context: Context, private val ui: ResultsView.Ui, private val listener: Listener) : LinearLayout(context) {
    interface Listener {
        /** The character at [index] of the text was selected. */
        fun onCharSelected(index: Int)

        /** [replacement] was picked for the character at [index] (null: delete it). */
        fun onCorrect(index: Int, replacement: String?)

        fun onDone()
    }

    /** A character to put in; [makesWord] if the text then reads as a dictionary word through it. */
    class Suggestion(val text: String, val makesWord: Boolean)

    private val chars = LinearLayout(context)
    private val scroll = HorizontalScrollView(context)
    private val label = ui.text("", 13f, ui.muted)
    private val suggestions = FlowLayout(context, ui.dp(6))
    private var selected = -1

    init {
        orientation = VERTICAL
        setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8))
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(chars)
        top.addView(scroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ui.iconButton(R.drawable.ic_check, context.getString(R.string.action_done)) { listener.onDone() }.apply { setColorFilter(ui.accent) })
        addView(top)
        label.setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(6))
        addView(label)
        addView(suggestions)
    }

    /** Shows the characters of the text, [selected] one of them. */
    fun setText(text: List<String>, selected: Int) {
        this.selected = selected
        chars.removeAllViews()
        text.forEachIndexed { i, c ->
            chars.addView(key(c, if (i == selected) Style.SELECTED else Style.PLAIN) {
                if (i != this.selected) listener.onCharSelected(i)
            }.apply { contentDescription = c }, keyParams().apply { marginEnd = ui.dp(6) })
        }
        // Keep the selected character in sight.
        scroll.post { chars.getChildAt(selected)?.let { scroll.smoothScrollTo(max(0, it.left - ui.dp(48)), 0) } }
        suggestions.removeAllViews()
        label.text = context.getString(R.string.correction_loading)
    }

    /** Shows what the selected character may be; unless [final], more are still being looked for. */
    fun setSuggestions(list: List<Suggestion>, final: Boolean = true) {
        suggestions.removeAllViews()
        label.text = context.getString(
            when {
                !final -> R.string.correction_loading
                list.isEmpty() -> R.string.correction_none
                else -> R.string.correction_suggestions
            },
        )
        for (s in list) {
            suggestions.addView(key(s.text, if (s.makesWord) Style.WORD else Style.PLAIN) { listener.onCorrect(selected, s.text) }.apply {
                contentDescription = if (s.makesWord) context.getString(R.string.correction_makes_word, s.text) else s.text
            }, keyParams())
        }
        val delete = ImageView(context).apply {
            setImageResource(R.drawable.ic_backspace)
            setColorFilter(ui.muted)
            scaleType = ImageView.ScaleType.CENTER
            background = keyBackground(Style.PLAIN)
            contentDescription = context.getString(R.string.action_delete_character)
            setOnClickListener { listener.onCorrect(selected, null) }
        }
        suggestions.addView(delete, keyParams())
    }

    private enum class Style { PLAIN, SELECTED, WORD }

    private fun keyParams() = LayoutParams(ui.dp(48), ui.dp(48))

    private fun key(text: String, style: Style, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(if (style == Style.PLAIN) ui.text else ui.accent)
        if (style != Style.PLAIN) typeface = Typeface.DEFAULT_BOLD
        background = keyBackground(style)
        setOnClickListener { onClick() }
    }

    private fun keyBackground(style: Style) = GradientDrawable().apply {
        cornerRadius = ui.dp(10).toFloat()
        setStroke(ui.dp(if (style == Style.SELECTED) 2 else 1), if (style == Style.PLAIN) ui.divider else ui.accent)
    }

    /** Lays out its children in rows, as many to a row as fit. */
    private class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                val lp = child.layoutParams
                child.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY))
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0
                    y += rowHeight + gap
                    rowHeight = 0
                }
                x += child.measuredWidth + gap
                rowHeight = max(rowHeight, child.measuredHeight)
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowHeight + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0
                    y += rowHeight + gap
                    rowHeight = 0
                }
                child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
                x += child.measuredWidth + gap
                rowHeight = max(rowHeight, child.measuredHeight)
            }
        }
    }
}
