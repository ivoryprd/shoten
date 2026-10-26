package io.github.ivoryprd.shoten.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ocr.Box
import io.github.ivoryprd.shoten.ocr.CharPosition
import io.github.ivoryprd.shoten.ocr.OcrResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the frozen screenshot (or a shared image) with boxes around recognized text, and turns
 * touches into character selections. While text is being recognized it shows a glowing edge and
 * a light sweeping over the screen; afterwards the text boxes fade in.
 */
@SuppressLint("ViewConstructor")
class ScreenshotView(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        /** A character was touched (or the finger moved onto another character). */
        fun onCharTouched(position: CharPosition, x: Float, y: Float, initial: Boolean)

        /** A tap outside of any text. */
        fun onEmptyTap()
    }

    /** How the bitmap maps to the view: 1:1 with the physical screen, or scaled to fit. */
    enum class Mode { SCREEN, FIT }

    var bitmap: Bitmap? = null
        private set
    var mode = Mode.SCREEN
        private set
    var ocr: OcrResult? = null
        private set
    var showBoxes = true

    private var selection: List<CharPosition> = emptyList()
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val density = resources.displayMetrics.density
    private val accent = context.getColor(R.color.accent)
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
    }
    private val dimPaint = Paint()
    private val sweepPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val lineRect = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val location = IntArray(2)
    private val lastLocation = IntArray(2)
    private val glowMatrix = Matrix()
    private var glowShader: SweepGradient? = null
    private var sweepShader: LinearGradient? = null
    private var cornerRadius = 0f

    // Animation state, all in 0..1 (glowAngle in degrees, sweep is the band's position).
    private var dim = 0f
    private var glow = 0f
    private var glowAngle = 0f
    private var sweep = NO_SWEEP
    private var boxes = 0f
    private var selectionAlpha = 1f
    private var dimAnimator: ValueAnimator? = null
    private var glowAnimator: ValueAnimator? = null
    private var boxesAnimator: ValueAnimator? = null
    private var selectionAnimator: ValueAnimator? = null
    private var spinAnimator: ValueAnimator? = null
    private var sweepAnimator: ValueAnimator? = null

    fun setImage(bitmap: Bitmap?, mode: Mode) {
        this.bitmap = bitmap
        this.mode = mode
        selection = emptyList()
        ocr = null
        if (bitmap == null) {
            cancelAnimations()
            dim = 0f
            glow = 0f
            boxes = 0f
            sweep = NO_SWEEP
        }
        updateMatrix()
        invalidate()
    }

    /** Shows a glowing edge around the screen, e.g. while the screenshot is being taken. */
    fun startGlow() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        glowAnimator = fade(glowAnimator, glow, 1f, 320) { glow = it }
        if (spinAnimator == null) {
            spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 3200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { glowAngle = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    /** Starts the "reading the screen" animation: dim, glowing edge and a light sweeping down. */
    fun startScanning() {
        boxesAnimator?.cancel()
        boxes = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            dim = 1f
            invalidate()
            return
        }
        dimAnimator = fade(dimAnimator, dim, 1f, 260) { dim = it }
        startGlow()
        if (sweepAnimator == null) {
            sweepAnimator = ValueAnimator.ofFloat(-0.35f, 1.35f).apply {
                duration = 1500
                startDelay = 120
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    /** Ends the glow and the sweeping light (they fade out). */
    fun stopScanning() {
        if (glow == 0f) {
            stopLoops()
            return
        }
        glowAnimator = fade(glowAnimator, glow, 0f, 450, end = ::stopLoops) { glow = it }
    }

    /** Shows the recognized text: the scanning animation ends and the text boxes fade in. */
    fun showResult(result: OcrResult) {
        ocr = result
        stopScanning()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            dim = 1f
            boxes = 1f
            invalidate()
            return
        }
        dimAnimator = fade(dimAnimator, dim, 1f, 200) { dim = it }
        boxes = 0f
        boxesAnimator = fade(boxesAnimator, 0f, 1f, 280, delay = 60) { boxes = it }
    }

    fun setSelection(positions: List<CharPosition>) {
        val appearing = selection.isEmpty() && positions.isNotEmpty()
        selection = positions
        if (appearing && ValueAnimator.areAnimatorsEnabled()) {
            selectionAlpha = 0f
            selectionAnimator = fade(selectionAnimator, 0f, 1f, 140) { selectionAlpha = it }
        } else if (selectionAnimator?.isRunning != true) {
            selectionAlpha = 1f
        }
        invalidate()
    }

    /** Converts a box in bitmap coordinates to view coordinates. */
    fun mapToView(box: Box, out: RectF) {
        out.set(box.left, box.top, box.right, box.bottom)
        matrix.mapRect(out)
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glowShader = null
        sweepShader = null
    }

    @Suppress("DEPRECATION")
    private fun updateMatrix() {
        val b = bitmap ?: return
        matrix.reset()
        if (width == 0 || height == 0) return
        when (mode) {
            Mode.SCREEN -> {
                // The screenshot covers the whole display; place it where the display is.
                val real = Point()
                val display = display
                if (display != null) display.getRealSize(real) else real.set(rootView.width, rootView.height)
                val scale = if (b.width > 0) real.x.toFloat() / b.width else 1f
                getLocationOnScreen(location)
                location.copyInto(lastLocation)
                matrix.setScale(scale, scale)
                matrix.postTranslate(-location[0].toFloat(), -location[1].toFloat())
            }
            Mode.FIT -> {
                val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
                matrix.setScale(scale, scale)
                matrix.postTranslate((width - b.width * scale) / 2, (height - b.height * scale) / 2)
            }
        }
        matrix.invert(inverse)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap?.takeUnless { it.isRecycled }
        if (b != null) {
            if (mode == Mode.SCREEN) {
                // The window can move without being laid out again (e.g. when system bars change).
                getLocationOnScreen(location)
                if (location[0] != lastLocation[0] || location[1] != lastLocation[1]) updateMatrix()
            }
            canvas.drawBitmap(b, matrix, bitmapPaint)
            if (dim > 0f) {
                dimPaint.color = Color.argb((dim * 56).toInt(), 0, 0, 0)
                canvas.drawPaint(dimPaint)
            }
            if (sweep != NO_SWEEP) drawSweep(canvas)
        }
        if (glow > 0f) drawGlow(canvas)
        if (b == null) return
        val result = ocr ?: return
        if (showBoxes && boxes > 0f) drawBoxes(canvas, result)
        if (selection.isNotEmpty()) drawSelection(canvas, result)
    }

    private fun drawBoxes(canvas: Canvas, result: OcrResult) {
        boxFill.color = Color.argb((boxes * 40).toInt(), 255, 255, 255)
        boxStroke.color = Color.argb((boxes * 170).toInt(), 255, 255, 255)
        for (block in result.blocks) for (line in block.lines) {
            val box = line.box ?: continue
            mapToView(box, rect)
            drawPadded(canvas, rect, rect, boxFill, boxStroke)
        }
    }

    private fun drawSelection(canvas: Canvas, result: OcrResult) {
        selectionPaint.color = Color.argb((selectionAlpha * 110).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
        selectionStroke.color = Color.argb((selectionAlpha * 255).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
        // One highlight per line covered by the selection, as high (or wide) as the line's box.
        for ((key, positions) in selection.groupBy { it.block to it.line }) {
            val line = result.blocks.getOrNull(key.first)?.lines?.getOrNull(key.second) ?: continue
            var union: Box? = null
            for (p in positions) {
                val box = line.chars.getOrNull(p.char)?.box ?: continue
                union = union?.union(box) ?: box
            }
            val u = union ?: continue
            val lineBox = line.box ?: u
            val selected = if (line.vertical) {
                Box(lineBox.left, u.top, lineBox.right, u.bottom)
            } else {
                Box(u.left, lineBox.top, u.right, lineBox.bottom)
            }
            mapToView(lineBox, lineRect)
            mapToView(selected, rect)
            drawPadded(canvas, rect, lineRect, selectionPaint, selectionStroke)
        }
    }

    /**
     * Draws [r] as a rounded box with some room around the text. The padding and corners grow
     * with the text size (taken from the line's box [line]), so boxes look alike for small and
     * large text and small inaccuracies of the recognizer don't make them cut into the text.
     */
    private fun drawPadded(canvas: Canvas, r: RectF, line: RectF, fill: Paint, stroke: Paint) {
        val textSize = min(line.width(), line.height())
        val pad = (textSize * 0.16f).coerceIn(density * 2f, density * 8f)
        r.inset(-pad, -pad)
        val corner = (textSize * 0.22f + pad).coerceAtMost(density * 12f)
        canvas.drawRoundRect(r, corner, corner, fill)
        canvas.drawRoundRect(r, corner, corner, stroke)
    }

    /** A soft band of light moving down the screen. */
    private fun drawSweep(canvas: Canvas) {
        val band = height * 0.28f
        val shader = sweepShader ?: LinearGradient(
            0f, 0f, 0f, band,
            intArrayOf(Color.TRANSPARENT, Color.argb(56, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        ).also { sweepShader = it }
        val top = sweep * height - band / 2
        glowMatrix.setTranslate(0f, top)
        shader.setLocalMatrix(glowMatrix)
        sweepPaint.shader = shader
        sweepPaint.alpha = (glow * 255).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, max(0f, top), width.toFloat(), min(height.toFloat(), top + band), sweepPaint)
    }

    /** A rotating multi-colored glow along the edges of the screen. */
    private fun drawGlow(canvas: Canvas) {
        val shader = glowShader ?: SweepGradient(width / 2f, height / 2f, GLOW_COLORS, null).also {
            glowShader = it
            cornerRadius = screenCornerRadius()
        }
        glowMatrix.setRotate(glowAngle, width / 2f, height / 2f)
        shader.setLocalMatrix(glowMatrix)
        glowPaint.shader = shader
        // A wide faint stroke for the glow and a narrow bright one for the edge.
        for ((strokeDp, alpha) in GLOW_STROKES) {
            val stroke = strokeDp * density
            glowPaint.strokeWidth = stroke
            glowPaint.alpha = (alpha * glow).toInt().coerceIn(0, 255)
            rect.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
            val r = max(0f, cornerRadius - stroke / 2)
            canvas.drawRoundRect(rect, r, r, glowPaint)
        }
    }

    private fun screenCornerRadius(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val corner = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            if (corner != null) return corner.radius.toFloat()
        }
        return density * 16f
    }

    private fun fade(
        previous: ValueAnimator?,
        from: Float,
        to: Float,
        duration: Long,
        delay: Long = 0,
        end: (() -> Unit)? = null,
        update: (Float) -> Unit,
    ): ValueAnimator {
        previous?.cancel()
        return ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            startDelay = delay
            interpolator = DecelerateInterpolator()
            addUpdateListener { update(it.animatedValue as Float); invalidate() }
            if (end != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) end()
                    }
                })
            }
            start()
        }
    }

    /** Stops the endless glow rotation and sweep. */
    private fun stopLoops() {
        spinAnimator?.cancel()
        spinAnimator = null
        sweepAnimator?.cancel()
        sweepAnimator = null
        sweep = NO_SWEEP
        invalidate()
    }

    private fun cancelAnimations() {
        for (a in listOf(dimAnimator, glowAnimator, boxesAnimator, selectionAnimator)) a?.cancel()
        dimAnimator = null
        glowAnimator = null
        boxesAnimator = null
        selectionAnimator = null
        selectionAlpha = 1f
        stopLoops()
    }

    private var downX = 0f
    private var downY = 0f
    private var selecting = false
    private var moved = false
    private var lastPosition: CharPosition? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                lastPosition = null
                val hit = hitTest(event.x, event.y)
                selecting = hit != null
                if (hit != null) {
                    lastPosition = hit
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onCharTouched(hit, event.x, event.y, initial = true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) moved = true
                if (moved) {
                    val hit = hitTest(event.x, event.y)
                    if (hit != null && hit != lastPosition) {
                        val initial = !selecting
                        selecting = true
                        lastPosition = hit
                        listener.onCharTouched(hit, event.x, event.y, initial)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!selecting && !moved) {
                    performClick()
                    listener.onEmptyTap()
                }
                selecting = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                selecting = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun hitTest(x: Float, y: Float): CharPosition? {
        val result = ocr ?: return null
        if (bitmap == null) return null
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        val scale = max(matrix.mapRadius(1f), 0.01f)
        val slop = density * 14f / scale
        return result.hitTest(pts[0], pts[1], slop)
    }

    companion object {
        // Blue, violet, pink, orange: the colors of the glow, going around the screen.
        private val GLOW_COLORS = intArrayOf(
            0xFF4285F4.toInt(), 0xFF9B72CB.toInt(), 0xFFD96570.toInt(), 0xFFF2A65A.toInt(), 0xFF4285F4.toInt(),
        )
        private val GLOW_STROKES = listOf(18f to 70, 8f to 120, 3f to 255)
        private const val NO_SWEEP = -1f
    }
}
