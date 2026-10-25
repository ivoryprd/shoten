package io.github.ivoryprd.shoten.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ocr.Box
import io.github.ivoryprd.shoten.ocr.CharPosition
import io.github.ivoryprd.shoten.ocr.OcrResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the frozen screenshot (or a shared image) with boxes around recognized text, and turns
 * touches into character selections.
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
        set(value) {
            field = value
            invalidate()
        }
    var showBoxes = true
    var dimmed = false
        set(value) {
            field = value
            invalidate()
        }

    private var selection: List<CharPosition> = emptyList()
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val accent = context.getColor(R.color.accent)
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
        color = Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent))
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent))
    }
    private val selectionStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = accent
    }
    private val rect = RectF()
    private val radius = resources.displayMetrics.density * 4f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val location = IntArray(2)

    fun setImage(bitmap: Bitmap?, mode: Mode) {
        this.bitmap = bitmap
        this.mode = mode
        selection = emptyList()
        updateMatrix()
        invalidate()
    }

    fun setSelection(positions: List<CharPosition>) {
        selection = positions
        invalidate()
    }

    /** Converts a box in bitmap coordinates to view coordinates. */
    fun mapToView(box: Box, out: RectF) {
        out.set(box.left, box.top, box.right, box.bottom)
        matrix.mapRect(out)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateMatrix()
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
        val b = bitmap ?: return
        if (b.isRecycled) return
        canvas.drawBitmap(b, matrix, bitmapPaint)
        if (dimmed) canvas.drawColor(Color.argb(40, 0, 0, 0))
        val result = ocr ?: return
        if (showBoxes) {
            for (block in result.blocks) for (line in block.lines) {
                val box = line.box ?: continue
                mapToView(box, rect)
                rect.inset(-radius / 2, -radius / 2)
                canvas.drawRoundRect(rect, radius, radius, boxFill)
                canvas.drawRoundRect(rect, radius, radius, boxStroke)
            }
        }
        if (selection.isNotEmpty()) {
            // One highlight per line covered by the selection.
            for ((key, positions) in selection.groupBy { it.block to it.line }) {
                val line = result.blocks.getOrNull(key.first)?.lines?.getOrNull(key.second) ?: continue
                var union: Box? = null
                for (p in positions) {
                    val box = line.chars.getOrNull(p.char)?.box ?: continue
                    union = union?.union(box) ?: box
                }
                val u = union ?: continue
                mapToView(u, rect)
                rect.inset(-radius / 2, -radius / 2)
                canvas.drawRoundRect(rect, radius, radius, selectionPaint)
                canvas.drawRoundRect(rect, radius, radius, selectionStroke)
            }
        }
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
        val slop = resources.displayMetrics.density * 14f / scale
        return result.hitTest(pts[0], pts[1], slop)
    }
}
