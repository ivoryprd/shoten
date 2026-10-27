package io.github.ivoryprd.shoten.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ocr.Box
import io.github.ivoryprd.shoten.ocr.CharPosition
import io.github.ivoryprd.shoten.ocr.OcrResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the frozen screenshot (or a shared image) with boxes around recognized text, and turns
 * touches into character selections. While text is being recognized the screen edge glows blue
 * and a soft light sweeps down; afterwards the text boxes fade in. The image can be moved
 * ([panTo]) to keep the selection clear of the result card.
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
    private val shimmerPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val lineRect = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val location = IntArray(2)
    private val lastLocation = IntArray(2)
    private val shaderMatrix = Matrix()
    private var glowShader: SweepGradient? = null
    private var edgeGlow: EdgeGlow? = null
    private var edgeGlowFailed = false
    private var shimmerShader: LinearGradient? = null
    private var cornerRadius = -1f

    // Animation state: intensities in 0..1, driven by fades; the looping effects follow [time].
    private var dim = 0f
    private var glow = 0f
    private var shimmer = 0f
    private var boxes = 0f
    private var selectionAlpha = 1f
    private var time = 0L
    private var dimAnimator: ValueAnimator? = null
    private var glowAnimator: ValueAnimator? = null
    private var shimmerAnimator: ValueAnimator? = null
    private var boxesAnimator: ValueAnimator? = null
    private var selectionAnimator: ValueAnimator? = null
    private var clock: ValueAnimator? = null

    // How far the image is moved, and where it is moving to.
    private var panX = 0f
    private var panY = 0f
    var panTargetX = 0f
        private set
    var panTargetY = 0f
        private set
    private var panAnimator: ValueAnimator? = null

    fun setImage(bitmap: Bitmap?, mode: Mode) {
        this.bitmap = bitmap
        this.mode = mode
        selection = emptyList()
        ocr = null
        panAnimator?.cancel()
        panX = 0f
        panY = 0f
        panTargetX = 0f
        panTargetY = 0f
        if (bitmap == null) {
            cancelAnimations()
            dim = 0f
            glow = 0f
            shimmer = 0f
            boxes = 0f
        }
        updateMatrix()
        invalidate()
    }

    /** Shows a glowing edge around the screen, e.g. while the screenshot is being taken. */
    fun startGlow() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        glowAnimator = fade(glowAnimator, glow, 1f, 450) { glow = it }
        startClock()
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
        dimAnimator = fade(dimAnimator, dim, 1f, 350) { dim = it }
        shimmerAnimator = fade(shimmerAnimator, shimmer, 1f, 450) { shimmer = it }
        startGlow()
    }

    /** Ends the glow and the sweeping light (they fade out). */
    fun stopScanning() {
        shimmerAnimator = fade(shimmerAnimator, shimmer, 0f, 350) { shimmer = it }
        glowAnimator = fade(glowAnimator, glow, 0f, 600, end = ::stopClock) { glow = it }
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
        dimAnimator = fade(dimAnimator, dim, 1f, 250) { dim = it }
        boxes = 0f
        boxesAnimator = fade(boxesAnimator, 0f, 1f, 400, delay = 100) { boxes = it }
    }

    /** Replaces the shown result with [result], a corrected version of it, without animating. */
    fun updateResult(result: OcrResult) {
        ocr = result
        invalidate()
    }

    fun setSelection(positions: List<CharPosition>) {
        val appearing = selection.isEmpty() && positions.isNotEmpty()
        selection = positions
        if (appearing && ValueAnimator.areAnimatorsEnabled()) {
            selectionAlpha = 0f
            selectionAnimator = fade(selectionAnimator, 0f, 1f, 160) { selectionAlpha = it }
        } else if (selectionAnimator?.isRunning != true) {
            selectionAlpha = 1f
        }
        invalidate()
    }

    /**
     * Moves the image by ([x], [y]) pixels from its normal place, smoothly. Touches that began
     * before stay on the text they were on while it moves.
     */
    fun panTo(x: Float, y: Float) {
        if (x == panTargetX && y == panTargetY) return
        panTargetX = x
        panTargetY = y
        panAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || bitmap == null) {
            setPan(x, y)
            return
        }
        val fromX = panX
        val fromY = panY
        // Moving away (making room) eases out like the card coming in; moving back is standard.
        val away = abs(x) + abs(y) > abs(fromX) + abs(fromY)
        panAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (away) 450 else 350
            interpolator = if (away) EMPHASIZED_DECELERATE else STANDARD
            addUpdateListener {
                val t = it.animatedValue as Float
                setPan(fromX + (x - fromX) * t, fromY + (y - fromY) * t)
            }
            start()
        }
    }

    private fun setPan(x: Float, y: Float) {
        panX = x
        panY = y
        updateMatrix()
        invalidate()
    }

    /**
     * The selection's bounds in view coordinates as they are without moving the image (see
     * [panTo]), including the room drawn around it. Returns false if nothing is selected.
     */
    fun selectionBounds(out: RectF): Boolean {
        val result = ocr ?: return false
        var any = false
        forEachSelectedLine(result) { selected, line ->
            mapToView(line, lineRect)
            mapToView(selected, rect)
            pad(rect, lineRect)
            if (any) out.union(rect) else out.set(rect)
            any = true
        }
        if (any) out.offset(-panX, -panY)
        return any
    }

    /** Converts a box in bitmap coordinates to view coordinates. */
    fun mapToView(box: Box, out: RectF) {
        out.set(box.left, box.top, box.right, box.bottom)
        matrix.mapRect(out)
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        panAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glowShader = null
        shimmerShader = null
        cornerRadius = -1f
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
        matrix.postTranslate(panX, panY)
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
            // Space uncovered by moving the image (mostly hidden behind the result card).
            if (panX != 0f || panY != 0f) canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(b, matrix, bitmapPaint)
            if (dim > 0f) {
                dimPaint.color = Color.argb((dim * DIM_ALPHA).toInt(), 8, 16, 36)
                canvas.drawPaint(dimPaint)
            }
            if (shimmer > 0f) drawShimmer(canvas)
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
        forEachSelectedLine(result) { selected, line ->
            mapToView(line, lineRect)
            mapToView(selected, rect)
            drawPadded(canvas, rect, lineRect, selectionPaint, selectionStroke)
        }
    }

    /** Calls [action] with the selected part of each line (as high or wide as the line) and the line's box. */
    private inline fun forEachSelectedLine(result: OcrResult, action: (selected: Box, line: Box) -> Unit) {
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
            action(selected, lineBox)
        }
    }

    /**
     * Grows [r] by some room around the text and returns the corner radius to draw it with. Both
     * scale with the text size (taken from the line's box [line]), so boxes look alike for small
     * and large text and small inaccuracies of the recognizer don't make them cut into the text.
     */
    private fun pad(r: RectF, line: RectF): Float {
        val textSize = min(line.width(), line.height())
        val pad = (textSize * 0.16f).coerceIn(density * 2f, density * 8f)
        r.inset(-pad, -pad)
        return (textSize * 0.22f + pad).coerceAtMost(density * 12f)
    }

    private fun drawPadded(canvas: Canvas, r: RectF, line: RectF, fill: Paint, stroke: Paint) {
        val corner = pad(r, line)
        canvas.drawRoundRect(r, corner, corner, fill)
        canvas.drawRoundRect(r, corner, corner, stroke)
    }

    /** A soft band of light moving down the screen; it fades in and out, so the loop is seamless. */
    private fun drawShimmer(canvas: Canvas) {
        val band = height * 0.35f
        val shader = shimmerShader ?: LinearGradient(
            0f, 0f, 0f, band,
            intArrayOf(Color.TRANSPARENT, SHIMMER_COLOR, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        ).also { shimmerShader = it }
        val phase = (time % SHIMMER_PERIOD).toFloat() / SHIMMER_PERIOD
        val eased = (1 - cos(PI * phase).toFloat()) / 2
        val top = -band + (height + band) * eased
        shaderMatrix.setTranslate(0f, top)
        shader.setLocalMatrix(shaderMatrix)
        shimmerPaint.shader = shader
        shimmerPaint.alpha = (255 * shimmer * sin(PI * phase).toFloat()).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, max(0f, top), width.toFloat(), min(height.toFloat(), top + band), shimmerPaint)
    }

    /**
     * A soft blue glow along the edges of the screen, slowly turning through shades of blue and
     * gently breathing.
     */
    private fun drawGlow(canvas: Canvas) {
        if (cornerRadius < 0f) cornerRadius = screenCornerRadius()
        val seconds = time / 1000f
        val breath = 0.85f + 0.15f * sin(2 * PI * seconds / BREATH_SECONDS).toFloat()
        val depth = GLOW_WIDTH * density * (0.92f + 0.08f * breath)
        val intensity = glow * breath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !edgeGlowFailed) {
            val shaderGlow = edgeGlow ?: EdgeGlow.create(GLOW_DEEP, GLOW_LIGHT).also {
                edgeGlow = it
                edgeGlowFailed = it == null
            }
            if (shaderGlow != null) {
                try {
                    shaderGlow.draw(canvas, width, height, cornerRadius, depth, density * 2.5f, seconds, intensity)
                    return
                } catch (e: IllegalArgumentException) {
                    // A software canvas (e.g. drawing the view into a bitmap) can't run shaders.
                }
            }
        }
        drawLayeredGlow(canvas, seconds, depth, intensity)
    }

    /**
     * The glow for Android versions without runtime shaders: strokes of growing width overlap at
     * the edge, so it fades out towards the middle in small steps.
     */
    private fun drawLayeredGlow(canvas: Canvas, seconds: Float, depth: Float, intensity: Float) {
        val shader = glowShader ?: SweepGradient(width / 2f, height / 2f, GLOW_COLORS, null).also { glowShader = it }
        shaderMatrix.setRotate(seconds * 360f / GLOW_TURN_SECONDS % 360f, width / 2f, height / 2f)
        shader.setLocalMatrix(shaderMatrix)
        glowPaint.shader = shader
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        glowPaint.alpha = (GLOW_LAYER_ALPHA * intensity).toInt().coerceIn(0, 255)
        for (i in 1..GLOW_LAYERS) {
            // A stroke centered on the edge: half of it is outside the view.
            glowPaint.strokeWidth = 2 * depth * i / GLOW_LAYERS
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, glowPaint)
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
            interpolator = STANDARD
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

    /**
     * One clock drives the looping effects (glow, shimmer), so they stay in step. It repeats after
     * a multiple of every effect's period, so the repeat can't be seen.
     */
    private fun startClock() {
        if (clock != null) return
        clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CLOCK_PERIOD
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                time = (it.animatedFraction * CLOCK_PERIOD).toLong()
                invalidate()
            }
            start()
        }
    }

    private fun stopClock() {
        clock?.cancel()
        clock = null
        invalidate()
    }

    private fun cancelAnimations() {
        for (a in listOf(dimAnimator, glowAnimator, shimmerAnimator, boxesAnimator, selectionAnimator)) a?.cancel()
        dimAnimator = null
        glowAnimator = null
        shimmerAnimator = null
        boxesAnimator = null
        selectionAnimator = null
        selectionAlpha = 1f
        stopClock()
    }

    private var downX = 0f
    private var downY = 0f
    private var downPanX = 0f
    private var downPanY = 0f
    private var selecting = false
    private var moved = false
    private var lastPosition: CharPosition? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downPanX = panX
                downPanY = panY
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
        // If the image moved since the finger went down, the finger moves along with it: it keeps
        // selecting the text it started on, wherever that text is shown now.
        val pts = floatArrayOf(x + panX - downPanX, y + panY - downPanY)
        inverse.mapPoints(pts)
        val scale = max(matrix.mapRadius(1f), 0.01f)
        val slop = density * 14f / scale
        return result.hitTest(pts[0], pts[1], slop)
    }

    companion object {
        /** The shades of blue of the glow. */
        private const val GLOW_DEEP = 0xFF1A73E8.toInt()
        private const val GLOW_LIGHT = 0xFF8AB4F8.toInt()
        private val GLOW_COLORS = intArrayOf(GLOW_DEEP, GLOW_LIGHT, 0xFF4285F4.toInt(), 0xFFAECBFA.toInt(), GLOW_DEEP)
        private const val GLOW_WIDTH = 30f // dp
        private const val GLOW_LAYERS = 20
        private const val GLOW_LAYER_ALPHA = 21
        // Periods of the looping effects; the clock's period is a multiple of all of them.
        private const val GLOW_TURN_SECONDS = 7f
        private const val BREATH_SECONDS = 2.8f
        private const val SHIMMER_PERIOD = 2400L // ms
        private const val CLOCK_PERIOD = 84_000L // ms
        private val SHIMMER_COLOR = Color.argb(64, 190, 215, 255)
        private const val DIM_ALPHA = 80

        // Material motion curves.
        private val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        private val EMPHASIZED_DECELERATE: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}

/**
 * The edge glow as a runtime shader (Android 13+): for every pixel near the edge it computes the
 * distance to the rounded edge of the screen, so the glow fades out perfectly smoothly and follows
 * the screen's corners. Its color drifts between two shades of blue along the edge over time.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeGlow private constructor(private val shader: RuntimeShader) {
    private val paint = Paint().apply { shader = this@EdgeGlow.shader }
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val rect = RectF()
    private var pathWidth = 0
    private var pathHeight = 0
    private var pathDepth = 0f
    private var pathRadius = 0f

    fun draw(canvas: Canvas, width: Int, height: Int, radius: Float, depth: Float, rim: Float, seconds: Float, intensity: Float) {
        // Only the band along the edge is drawn; further in, the glow has faded out completely.
        if (width != pathWidth || height != pathHeight || radius != pathRadius || depth > pathDepth) {
            pathWidth = width
            pathHeight = height
            pathRadius = radius
            pathDepth = depth * 1.1f + 2f
            path.rewind()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            path.addRect(rect, Path.Direction.CW)
            rect.inset(pathDepth, pathDepth)
            val inner = max(0f, radius - pathDepth)
            path.addRoundRect(rect, inner, inner, Path.Direction.CW)
        }
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("depth", depth)
        shader.setFloatUniform("rim", rim)
        shader.setFloatUniform("time", seconds)
        shader.setFloatUniform("intensity", intensity)
        canvas.drawPath(path, paint)
    }

    companion object {
        private const val AGSL = """
            uniform float2 size;
            uniform float radius;
            uniform float depth;
            uniform float rim;
            uniform float time;
            uniform float intensity;
            uniform half4 deep;
            uniform half4 light;

            // Distance from the edge of the rounded screen, positive inside.
            float inside(float2 p) {
                float2 halfSize = size * 0.5;
                float2 q = abs(p - halfSize) - halfSize + radius;
                return radius - length(max(q, 0.0)) - min(max(q.x, q.y), 0.0);
            }

            half4 main(float2 coord) {
                float d = inside(coord);
                float t = clamp(d / depth, 0.0, 1.0);
                float glow = pow(1.0 - t, 1.8) * 0.85 + (1.0 - smoothstep(0.0, rim, d)) * 0.45;
                // The shades of blue drift along the edge.
                float angle = atan(coord.y - size.y * 0.5, coord.x - size.x * 0.5);
                float wave = 0.5 + 0.5 * sin(2.0 * angle - time * 0.8975979); // 2 pi / 7 s
                float3 color = mix(float3(deep.rgb), float3(light.rgb), wave);
                float alpha = clamp(glow, 0.0, 1.0) * intensity * smoothstep(-1.5, 0.0, d);
                return half4(half3(color * alpha), half(alpha));
            }
        """

        fun create(deep: Int, light: Int): EdgeGlow? = try {
            EdgeGlow(
                RuntimeShader(AGSL).apply {
                    setFloatUniform("deep", Color.red(deep) / 255f, Color.green(deep) / 255f, Color.blue(deep) / 255f, 1f)
                    setFloatUniform("light", Color.red(light) / 255f, Color.green(light) / 255f, Color.blue(light) / 255f, 1f)
                },
            )
        } catch (e: RuntimeException) {
            // Not expected; the layered glow is drawn instead.
            Log.w("ScreenshotView", "Cannot create the glow shader", e)
            null
        }
    }
}
