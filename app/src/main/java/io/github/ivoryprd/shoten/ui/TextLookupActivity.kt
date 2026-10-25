package io.github.ivoryprd.shoten.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ivoryprd.shoten.OcrMode
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.LookupResult
import io.github.ivoryprd.shoten.render.ActionSpan
import io.github.ivoryprd.shoten.render.ResultsView
import io.github.ivoryprd.shoten.util.Cjk
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import java.util.Locale

/**
 * A popup for text selected in another app ("Look up in Shoten" in the selection menu) or shared
 * to Shoten. Tapping a character in the text looks up from there.
 */
class TextLookupActivity : Activity() {
    private val app by lazy { ShotenApp.get(this) }
    private val scope = MainScope()
    private lateinit var lookups: LookupController
    private lateinit var resultsView: ResultsView
    private lateinit var textView: TextView
    private lateinit var languageChip: TextView
    private var text = ""
    private var start = 0
    private var language = Language.JAPANESE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        text = readText(intent).trim().take(MAX_TEXT)
        if (text.isEmpty()) {
            finish()
            return
        }
        val ui = ResultsView.Ui(this)
        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(getColor(R.color.surface))
                cornerRadius = ui.dp(24).toFloat()
            }
            clipToOutline = true
            elevation = ui.dp(8).toFloat()
            isClickable = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(8), ui.dp(4), 0)
        }
        textView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(getColor(R.color.text_primary))
            maxLines = 4
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(0, ui.dp(6), ui.dp(8), ui.dp(6))
        }
        header.addView(textView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        languageChip = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.chip_text))
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            contentDescription = getString(R.string.action_switch_language)
            setOnClickListener {
                language = if (language == Language.CHINESE) Language.JAPANESE else Language.CHINESE
                lookupFrom(start)
            }
        }
        header.addView(languageChip)
        header.addView(ui.iconButton(R.drawable.ic_close, getString(R.string.action_close)) { finish() })
        card.addView(header)

        resultsView = ResultsView(this)
        val scroll = BoundedScrollView(this).apply {
            maxHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            addView(resultsView)
        }
        card.addView(scroll)

        root.addView(card, FrameLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels - ui.dp(24), ui.dp(600)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = ui.dp(12) })
        setContentView(root)
        root.applySystemBarPadding()

        lookups = LookupController(this, scope, resultsView, object : LookupController.Listener {
            override fun onResult(result: LookupResult, nested: Boolean) {
                if (!nested) updateText(result.matchLength)
            }

            override fun onLeaving() = finish()
        })
        language = detectLanguage(text)
        lookupFrom(0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun readText(intent: Intent?): String {
        if (intent == null) return ""
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> intent.getStringExtra(MainActivity.EXTRA_QUERY)
        }.orEmpty()
    }

    private fun detectLanguage(text: String): Language {
        val repo = app.dictionaries
        return when (app.prefs.ocrMode) {
            OcrMode.JAPANESE -> Language.JAPANESE
            OcrMode.CHINESE -> Language.CHINESE
            OcrMode.AUTO -> when {
                Cjk.containsKana(text) -> Language.JAPANESE
                repo.hasDictionaries(Language.CHINESE) && !repo.hasDictionaries(Language.JAPANESE) -> Language.CHINESE
                Cjk.containsHan(text) && repo.hasDictionaries(Language.CHINESE) && app.prefs.searchLanguage == Language.CHINESE -> Language.CHINESE
                else -> Language.JAPANESE
            }
        }
    }

    private fun lookupFrom(index: Int) {
        start = index
        languageChip.text = getString(if (language == Language.CHINESE) R.string.language_chinese_short else R.string.language_japanese_short)
        textView.textLocale = if (language == Language.CHINESE) Locale.SIMPLIFIED_CHINESE else Locale.JAPANESE
        updateText(0)
        lookups.lookup(text.substring(index), language)
    }

    /** Shows the text with each character clickable and the matched part highlighted. */
    private fun updateText(matchLength: Int) {
        val sb = SpannableStringBuilder(text)
        var i = 0
        while (i < text.length) {
            val next = i + Character.charCount(text.codePointAt(i))
            val index = i
            sb.setSpan(ActionSpan(null, false) { lookupFrom(index) }, i, next, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = next
        }
        val end = (start + maxOf(matchLength, 1)).coerceAtMost(text.length)
        if (start < end) {
            sb.setSpan(BackgroundColorSpan(getColor(R.color.chip_background)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = sb
    }

    /** A ScrollView with a maximum height. */
    private class BoundedScrollView(context: android.content.Context) : ScrollView(context) {
        var maxHeight = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val limited = if (mode == MeasureSpec.UNSPECIFIED) maxHeight else minOf(size, maxHeight)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limited, MeasureSpec.AT_MOST))
        }
    }

    companion object {
        private const val MAX_TEXT = 300
    }
}
