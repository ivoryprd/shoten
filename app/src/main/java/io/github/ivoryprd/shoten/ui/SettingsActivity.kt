package io.github.ivoryprd.shoten.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.ivoryprd.shoten.OcrMode
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ShotenApp

class SettingsActivity : Activity() {
    private val prefs by lazy { ShotenApp.get(this).prefs }
    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(content)
        setContentView(scroll)
        scroll.applySystemBarPadding()

        content.addView(header())

        content.addView(card().apply {
            addView(title(R.string.settings_ocr_language))
            val options = listOf(
                OcrMode.AUTO to getString(R.string.settings_ocr_auto),
                OcrMode.JAPANESE to getString(R.string.language_japanese),
                OcrMode.CHINESE to getString(R.string.language_chinese),
            )
            addView(radioGroup(options.map { it.second }, options.indexOfFirst { it.first == prefs.ocrMode }) { i ->
                prefs.ocrMode = options[i].first
            })
            addView(switch(R.string.settings_vertical_text, R.string.settings_vertical_text_summary, prefs.verticalText) { prefs.verticalText = it })
        })

        content.addView(card().apply {
            addView(title(R.string.settings_text_size))
            val sizes = listOf(0.85f to R.string.settings_text_small, 1f to R.string.settings_text_normal, 1.2f to R.string.settings_text_large)
            val current = sizes.indices.minByOrNull { kotlin.math.abs(sizes[it].first - prefs.textScale) } ?: 1
            addView(radioGroup(sizes.map { getString(it.second) }, current) { i -> prefs.textScale = sizes[i].first })
        })

        content.addView(card().apply {
            addView(switch(R.string.settings_show_boxes, null, prefs.showTextBoxes) { prefs.showTextBoxes = it })
            addView(switch(R.string.settings_show_frequencies, null, prefs.showFrequencies) { prefs.showFrequencies = it })
            addView(switch(R.string.settings_show_pitch, null, prefs.showPitch) { prefs.showPitch = it })
            addView(switch(R.string.settings_keep_ocr, R.string.settings_keep_ocr_summary, prefs.keepOcrMinutes > 0) {
                prefs.keepOcrMinutes = if (it) 5 else 0
            })
        })

        content.addView(card().apply {
            addView(Button(this@SettingsActivity, null, 0, R.style.Shoten_TextButton).apply {
                text = getString(R.string.settings_assistant)
                setOnClickListener { openAssistantSettings() }
            })
        })

        content.addView(card().apply {
            addView(title(R.string.settings_about))
            addView(TextView(this@SettingsActivity).apply {
                text = getString(R.string.settings_about_text)
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(6), 0, 0)
            })
        })
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = getString(R.string.action_back)
            setColorFilter(getColor(R.color.text_secondary))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        })
        return row
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(16), dp(14), dp(16), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
    }

    private fun title(text: Int) = TextView(this).apply {
        setText(text)
        setTextColor(getColor(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun radioGroup(labels: List<String>, checked: Int, onChange: (Int) -> Unit) = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        labels.forEachIndexed { i, label ->
            addView(RadioButton(this@SettingsActivity).apply {
                id = View.generateViewId()
                text = label
                setTextColor(getColor(R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minHeight = dp(44)
                isChecked = i == checked
                setOnCheckedChangeListener { _, isChecked -> if (isChecked) onChange(i) }
            })
        }
    }

    private fun switch(label: Int, summary: Int?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            setText(label)
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        if (summary != null) {
            texts.addView(TextView(this).apply {
                setText(summary)
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this).apply {
            isChecked = checked
            contentDescription = getString(label)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.toggle() }
        return row
    }
}
