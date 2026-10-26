package io.github.ivoryprd.shoten.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.ivoryprd.shoten.OcrMode
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.LookupResult
import io.github.ivoryprd.shoten.ocr.CharPosition
import io.github.ivoryprd.shoten.ocr.OcrModelUnavailableException
import io.github.ivoryprd.shoten.ocr.OcrModels
import io.github.ivoryprd.shoten.ocr.OcrResult
import io.github.ivoryprd.shoten.ocr.OcrScript
import io.github.ivoryprd.shoten.ocr.ScanText
import io.github.ivoryprd.shoten.render.ResultsView
import io.github.ivoryprd.shoten.ui.LookupController
import io.github.ivoryprd.shoten.util.Cjk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.EnumMap
import kotlin.math.roundToInt

/**
 * The screen lookup UI: a frozen screenshot with recognized text. Touching a character looks up
 * the text from there; the results appear in a card on the other half of the screen.
 */
@SuppressLint("ViewConstructor")
class ScreenLookupView(context: Context, private val host: Host) : FrameLayout(context), ScreenshotView.Listener {
    interface Host {
        fun dismiss()
        fun openApp()
        fun openAssistSettings()
    }

    private val app = ShotenApp.get(context)
    private val scope = MainScope()
    private val ui = ResultsView.Ui(context)
    private val density = resources.displayMetrics.density

    private val screenshot = ScreenshotView(context, this)
    private val resultsView = ResultsView(context)
    private val scroll = ScrollView(context)
    private val card = BoundedFrameLayout(context)
    private val cardHeader = TextView(context)
    private val backButton: ImageView
    private val toolbar = LinearLayout(context)
    private val languageChip = TextView(context)
    private val status = TextView(context)
    private val statusAction = Button(context, null, android.R.attr.borderlessButtonStyle)
    private val statusBox = LinearLayout(context)
    private val lookups: LookupController

    /** System bar and display cutout insets (left, top, right, bottom). */
    private val insets = Rect()
    private var ocrJob: Job? = null
    private val ocrResults = EnumMap<OcrScript, OcrResult>(OcrScript::class.java)
    private var language = Language.JAPANESE
    private var scan: ScanText? = null
    private var scanStart: CharPosition? = null
    private var cardHiding = false
    /** In landscape, whether the card is at the start (left) side instead of the end. */
    private var cardAtStart = false
    private val selectionRect = RectF()
    private val cardBackground = GradientDrawable().apply { setColor(context.getColor(R.color.surface)) }
    private var initialOrientation = 0
    private var released = false

    init {
        clipChildren = false
        addView(screenshot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Result card (its shape is set in layoutChrome).
        card.background = cardBackground
        card.elevation = 10 * density
        card.clipToOutline = true
        card.visibility = GONE
        val cardContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(12), 0)
        }
        backButton = ui.iconButton(R.drawable.ic_back, context.getString(R.string.action_back)) { lookups.back() }
        backButton.visibility = GONE
        headerRow.addView(backButton)
        cardHeader.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ui.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(12), ui.dp(8), 0, ui.dp(4))
        }
        headerRow.addView(cardHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cardContent.addView(headerRow)
        scroll.isFillViewport = false
        scroll.addView(resultsView)
        cardContent.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(cardContent)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // The card grows and shrinks with its content; keep the selection clear of it.
        card.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) updatePan()
        }

        // Toolbar with language switch, copy and close.
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        languageChip.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ui.text)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            background = pill()
            contentDescription = context.getString(R.string.action_switch_language)
            setOnClickListener { switchLanguage() }
        }
        toolbar.addView(languageChip)
        toolbar.addView(toolbarButton(R.drawable.ic_copy, R.string.action_copy_all) { copyAll() })
        toolbar.addView(toolbarButton(R.drawable.ic_close, R.string.action_close) { host.dismiss() })
        toolbar.visibility = GONE
        addView(toolbar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        // Status message.
        statusBox.orientation = LinearLayout.VERTICAL
        statusBox.gravity = Gravity.CENTER_HORIZONTAL
        statusBox.background = pill()
        statusBox.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10))
        status.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ui.text)
            gravity = Gravity.CENTER
        }
        statusBox.addView(status)
        statusAction.apply {
            setTextColor(ui.accent)
            isAllCaps = false
            visibility = GONE
        }
        statusBox.addView(statusAction)
        statusBox.visibility = GONE
        addView(statusBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))

        lookups = LookupController(context, scope, resultsView, object : LookupController.Listener {
            override fun onResult(result: LookupResult, nested: Boolean) = onLookupResult(result, nested)
            override fun onLeaving() = host.dismiss()
        })

        setOnApplyWindowInsetsListener { _, windowInsets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insets.set(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                insets.set(
                    windowInsets.systemWindowInsetLeft, windowInsets.systemWindowInsetTop,
                    windowInsets.systemWindowInsetRight, windowInsets.systemWindowInsetBottom,
                )
            }
            layoutChrome()
            windowInsets
        }
    }

    private fun pill() = GradientDrawable().apply {
        setColor(context.getColor(R.color.surface))
        cornerRadius = 100 * density
    }

    private fun toolbarButton(icon: Int, label: Int, onClick: () -> Unit) =
        ui.iconButton(icon, context.getString(label), onClick).apply {
            background = pill()
            setColorFilter(ui.text)
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginStart = ui.dp(8) }
        }

    // --- Public API -----------------------------------------------------------------------

    /** The window is being shown: the screen edge starts glowing until the screenshot arrives. */
    fun onShown() {
        if (!released) screenshot.startGlow()
    }

    /** Shows [bitmap] and starts recognizing text in it. */
    fun showImage(bitmap: Bitmap, mode: ScreenshotView.Mode) {
        if (released) return
        initialOrientation = resources.configuration.orientation
        screenshot.showBoxes = app.prefs.showTextBoxes
        screenshot.setImage(bitmap, mode)
        toolbar.fadeIn(-ui.dp(8).toFloat())
        hideCard()
        ocrResults.clear()
        language = initialLanguage()
        runOcr(firstPass = true)
    }

    /** Shows an error instead of a screenshot (e.g. screenshots are disabled). */
    fun showError(message: CharSequence, actionLabel: CharSequence?, action: (() -> Unit)?) {
        if (released) return
        screenshot.stopScanning()
        setBackgroundColor(Color.argb(140, 0, 0, 0))
        toolbar.fadeIn(-ui.dp(8).toFloat())
        languageChip.visibility = GONE
        showStatus(message, actionLabel, action = action)
    }

    /** Returns true if the back press was handled. */
    fun onBackPressed(): Boolean {
        if (lookups.back()) return true
        if (isCardShown) {
            hideCard()
            return true
        }
        return false
    }

    fun release() {
        released = true
        lookups.cancel()
        ocrJob?.cancel()
        scope.cancel()
        screenshot.setImage(null, ScreenshotView.Mode.SCREEN)
    }

    // --- OCR --------------------------------------------------------------------------------

    private fun initialLanguage(): Language {
        val repo = app.dictionaries
        val hasJa = repo.hasDictionaries(Language.JAPANESE)
        val hasZh = repo.hasDictionaries(Language.CHINESE)
        return when (app.prefs.ocrMode) {
            OcrMode.JAPANESE -> Language.JAPANESE
            OcrMode.CHINESE -> Language.CHINESE
            OcrMode.AUTO -> when {
                hasZh && !hasJa -> Language.CHINESE
                else -> Language.JAPANESE
            }
        }
    }

    private fun scriptFor(language: Language) = if (language == Language.CHINESE) OcrScript.CHINESE else OcrScript.JAPANESE

    private fun runOcr(firstPass: Boolean) {
        val bitmap = screenshot.bitmap ?: return
        updateLanguageChip()
        val script = scriptFor(language)
        ocrResults[script]?.let {
            onOcrResult(it)
            return
        }
        showStatus(context.getString(R.string.status_reading))
        screenshot.startScanning()
        ocrJob?.cancel()
        ocrJob = scope.launch {
            val result = try {
                withContext(Dispatchers.Default) { app.ocr.recognize(bitmap, script) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrModelUnavailableException) {
                onModelMissing(script)
                return@launch
            } catch (e: Exception) {
                screenshot.stopScanning()
                showStatus(context.getString(R.string.status_ocr_failed, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            ocrResults[script] = result
            // Automatic mode: Japanese text nearly always contains kana. Without any, the text is
            // probably Chinese, so try the Chinese recognizer if Chinese dictionaries are installed.
            if (firstPass && app.prefs.ocrMode == OcrMode.AUTO && script == OcrScript.JAPANESE &&
                !result.isEmpty && !Cjk.containsKana(result.fullText) && Cjk.containsHan(result.fullText) &&
                app.dictionaries.hasDictionaries(Language.CHINESE)
            ) {
                language = Language.CHINESE
                runOcr(firstPass = false)
                return@launch
            }
            onOcrResult(result)
        }
    }

    private fun onModelMissing(script: OcrScript) {
        if (!OcrModels.DOWNLOADED_BY_PLAY_SERVICES) {
            screenshot.stopScanning()
            showStatus(context.getString(R.string.status_ocr_failed, "model unavailable"))
            return
        }
        showStatus(context.getString(R.string.status_downloading_model, ""))
        OcrModels.install(context, app.ocr.recognizer(script), { percent ->
            if (!released) {
                showStatus(context.getString(R.string.status_downloading_model, if (percent >= 0) "$percent%" else ""))
            }
        }) { ok, error ->
            if (released) return@install
            if (ok) {
                runOcr(firstPass = false)
            } else {
                screenshot.stopScanning()
                showStatus(context.getString(R.string.status_model_failed, error ?: ""))
            }
        }
    }

    private fun onOcrResult(result: OcrResult) {
        screenshot.showResult(result)
        when {
            result.isEmpty -> showStatus(context.getString(R.string.status_no_text), context.getString(R.string.action_switch_language)) { switchLanguage() }
            !app.dictionaries.hasDictionaries(language) -> showStatus(
                context.getString(if (language == Language.CHINESE) R.string.status_no_chinese_dictionary else R.string.status_no_japanese_dictionary),
                context.getString(R.string.action_open_app),
            ) { host.openApp() }
            else -> showStatus(context.getString(R.string.status_tap_text), autoHide = true)
        }
    }

    private fun switchLanguage() {
        language = if (language == Language.CHINESE) Language.JAPANESE else Language.CHINESE
        if (app.prefs.ocrMode != OcrMode.AUTO) {
            app.prefs.ocrMode = if (language == Language.CHINESE) OcrMode.CHINESE else OcrMode.JAPANESE
        }
        hideCard()
        screenshot.setSelection(emptyList())
        runOcr(firstPass = false)
    }

    private fun updateLanguageChip() {
        languageChip.visibility = VISIBLE
        languageChip.text = context.getString(if (language == Language.CHINESE) R.string.language_chinese_short else R.string.language_japanese_short)
    }

    private fun copyAll() {
        val text = ocrResults[scriptFor(language)]?.fullText ?: return
        if (text.isBlank()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Shoten", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.copied_all, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Lookup -----------------------------------------------------------------------------

    override fun onCharTouched(position: CharPosition, x: Float, y: Float, initial: Boolean) {
        val result = screenshot.ocr ?: return
        // In landscape the card goes to the other half of the screen.
        if (initial && width > height) cardAtStart = x > width / 2f
        val text = result.scanText(position, MAX_SCAN_CHARS)
        if (text.text.isBlank()) return
        scan = text
        scanStart = position
        screenshot.setSelection(listOf(position))
        hideStatus()
        lookups.lookup(text.text, language)
    }

    override fun onEmptyTap() {
        if (isCardShown) {
            hideCard()
        } else {
            host.dismiss()
        }
    }

    private fun onLookupResult(result: LookupResult, nested: Boolean) {
        backButton.visibility = if (lookups.canGoBack) VISIBLE else GONE
        val header = SpannableStringBuilder()
        if (nested) {
            header.append(result.text)
        } else {
            val s = scan
            val length = result.matchLength
            if (s != null) {
                screenshot.setSelection(if (length > 0) s.positionsCovering(length) else listOfNotNull(scanStart))
                val matched = s.text.take(length)
                header.append(matched)
                header.setSpan(StyleSpan(Typeface.BOLD), 0, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                header.setSpan(ForegroundColorSpan(ui.text), 0, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                header.append(s.text.substring(length.coerceAtMost(s.text.length)))
            }
        }
        cardHeader.text = header
        showCard()
        updatePan()
    }

    /**
     * Moves the screenshot so that the selected text isn't covered by the card, like Circle to
     * Search does: in portrait the card is at the bottom and the screenshot slides up, in
     * landscape the card is at one side and the screenshot slides sideways. The screenshot moves
     * as little as possible, and never so far that the space it uncovers shows beside the card.
     */
    private fun updatePan() {
        if (!isCardShown || card.width == 0 || !screenshot.selectionBounds(selectionRect)) {
            screenshot.panTo(0f, 0f)
            return
        }
        val margin = ui.dp(16).toFloat()
        if (width > height) {
            val pan = if (cardAtStart) {
                fit(selectionRect.left, selectionRect.right, card.right + margin, width - insets.right - margin, screenshot.panTargetX)
                    .coerceIn(0f, card.right.toFloat())
            } else {
                fit(selectionRect.left, selectionRect.right, insets.left + margin, card.left - margin, screenshot.panTargetX)
                    .coerceIn(-(width - card.left).toFloat(), 0f)
            }
            screenshot.panTo(pan, 0f)
        } else {
            val top = maxOf(insets.top + margin, toolbar.bottom + margin / 2)
            val pan = fit(selectionRect.top, selectionRect.bottom, top, card.top - margin, screenshot.panTargetY)
                .coerceIn(-(height - card.top).toFloat(), 0f)
            screenshot.panTo(0f, pan)
        }
    }

    private val isCardShown get() = card.visibility == VISIBLE && !cardHiding

    private fun showCard() {
        scroll.scrollTo(0, 0)
        val appearing = !isCardShown
        card.visibility = VISIBLE
        layoutChrome()
        if (!appearing) return
        // Slide in from the edge of the screen the card is placed at.
        card.animate().cancel()
        cardHiding = false
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetCardTransform()
            return
        }
        val offset = cardOffset()
        if (card.alpha == 1f) {
            card.alpha = 0f
            card.translationX = offset[0]
            card.translationY = offset[1]
            card.scaleX = 0.96f
            card.scaleY = 0.96f
        }
        card.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(450).setInterpolator(EMPHASIZED_DECELERATE).start()
    }

    private fun hideCard() {
        lookups.cancel()
        screenshot.setSelection(emptyList())
        screenshot.panTo(0f, 0f)
        if (!isCardShown) return
        card.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            card.visibility = GONE
            return
        }
        cardHiding = true
        val offset = cardOffset()
        card.animate().alpha(0f).translationX(offset[0] / 2).translationY(offset[1] / 2).scaleX(0.98f).scaleY(0.98f)
            .setDuration(200).setInterpolator(EMPHASIZED_ACCELERATE)
            .withEndAction {
                cardHiding = false
                card.visibility = GONE
                resetCardTransform()
            }
            .start()
    }

    /** The direction the card comes from: the screen edge it is placed at. */
    private fun cardOffset(): FloatArray {
        val distance = ui.dp(48).toFloat()
        return if (width > height) {
            floatArrayOf(if (cardAtStart) -distance else distance, 0f)
        } else {
            floatArrayOf(0f, distance)
        }
    }

    private fun resetCardTransform() {
        card.alpha = 1f
        card.translationX = 0f
        card.translationY = 0f
        card.scaleX = 1f
        card.scaleY = 1f
    }

    // --- Layout -----------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 && screenshot.mode == ScreenshotView.Mode.SCREEN && resources.configuration.orientation != initialOrientation &&
            initialOrientation != Configuration.ORIENTATION_UNDEFINED && initialOrientation != 0
        ) {
            // The screenshot no longer matches the screen.
            post { host.dismiss() }
            return
        }
        post { layoutChrome() }
    }

    private fun layoutChrome() {
        if (width == 0) return
        val margin = ui.dp(12)
        val landscape = width > height
        (toolbar.layoutParams as LayoutParams).apply {
            topMargin = insets.top + margin
            marginEnd = insets.right + margin
            toolbar.layoutParams = this
        }
        (statusBox.layoutParams as LayoutParams).apply {
            bottomMargin = insets.bottom + ui.dp(28)
            leftMargin = insets.left + margin
            rightMargin = insets.right + margin
            statusBox.layoutParams = this
        }
        val lp = card.layoutParams as LayoutParams
        val toolbarSpace = ui.dp(44) + margin
        if (landscape) {
            val cardWidth = (width * 0.46f).roundToInt().coerceAtMost(ui.dp(520))
            lp.width = cardWidth
            lp.height = LayoutParams.WRAP_CONTENT
            lp.gravity = (if (cardAtStart) Gravity.START else Gravity.END) or Gravity.TOP
            lp.topMargin = insets.top + margin + if (cardAtStart) 0 else toolbarSpace
            lp.bottomMargin = insets.bottom + margin
            lp.leftMargin = insets.left + margin
            lp.rightMargin = insets.right + margin
            card.maxHeight = height - lp.topMargin - lp.bottomMargin
        } else {
            // A sheet along the bottom edge (on phones as wide as the screen), which also hides the
            // space uncovered when the screenshot moves up.
            lp.width = width.coerceAtMost(ui.dp(600))
            lp.height = LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.topMargin = insets.top + margin + toolbarSpace
            lp.bottomMargin = 0
            lp.leftMargin = 0
            lp.rightMargin = 0
            card.maxHeight = (height * 0.5f).roundToInt() + insets.bottom
        }
        card.layoutParams = lp
        // The sheet's content stays clear of the navigation bar.
        card.setPadding(0, 0, 0, if (landscape) 0 else insets.bottom)
        val corner = 24 * density
        cardBackground.cornerRadii = if (landscape) {
            FloatArray(8) { corner }
        } else {
            floatArrayOf(corner, corner, corner, corner, 0f, 0f, 0f, 0f)
        }
    }

    private val hideStatusRunnable = Runnable { hideStatus() }

    private fun showStatus(
        message: CharSequence,
        actionLabel: CharSequence? = null,
        autoHide: Boolean = false,
        action: (() -> Unit)? = null,
    ) {
        removeCallbacks(hideStatusRunnable)
        status.text = message
        if (actionLabel != null && action != null) {
            statusAction.text = actionLabel
            statusAction.setOnClickListener { action() }
            statusAction.visibility = VISIBLE
        } else {
            statusAction.visibility = GONE
        }
        statusBox.fadeIn(ui.dp(8).toFloat())
        if (autoHide) postDelayed(hideStatusRunnable, 2500)
    }

    private fun hideStatus() {
        removeCallbacks(hideStatusRunnable)
        statusBox.fadeOut()
    }

    /** Shows the view, fading it in and moving it by [fromY] into place. */
    private fun View.fadeIn(fromY: Float) {
        animate().cancel()
        if (visibility != VISIBLE) {
            alpha = 0f
            translationY = fromY
            visibility = VISIBLE
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            alpha = 1f
            translationY = 0f
            return
        }
        animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(STANDARD).start()
    }

    private fun View.fadeOut() {
        if (visibility != VISIBLE) return
        animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            visibility = GONE
            return
        }
        animate().alpha(0f).setDuration(200).setInterpolator(STANDARD).withEndAction { visibility = GONE }.start()
    }

    /** A FrameLayout whose height is limited to [maxHeight]. */
    private class BoundedFrameLayout(context: Context) : FrameLayout(context) {
        var maxHeight = Int.MAX_VALUE
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                }
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var hSpec = heightMeasureSpec
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            if (maxHeight in 1 until Int.MAX_VALUE) {
                hSpec = when (mode) {
                    MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
                    else -> MeasureSpec.makeMeasureSpec(minOf(size, maxHeight), mode)
                }
            }
            super.onMeasure(widthMeasureSpec, hSpec)
        }
    }

    companion object {
        private const val MAX_SCAN_CHARS = 24

        /**
         * The offset closest to [current] that puts the range [start, end] within [min, max]. If
         * the range doesn't fit, its start is shown.
         */
        internal fun fit(start: Float, end: Float, min: Float, max: Float, current: Float): Float {
            var offset = current
            if (end + offset > max) offset = max - end
            if (start + offset < min) offset = min - start
            return offset
        }

        // Material motion curves.
        private val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        private val EMPHASIZED_DECELERATE: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EMPHASIZED_ACCELERATE: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
