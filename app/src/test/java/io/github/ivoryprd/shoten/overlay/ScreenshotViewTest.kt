package io.github.ivoryprd.shoten.overlay

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import io.github.ivoryprd.shoten.ocr.Box
import io.github.ivoryprd.shoten.ocr.CharPosition
import io.github.ivoryprd.shoten.ocr.OcrBlock
import io.github.ivoryprd.shoten.ocr.OcrResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenshotViewTest {
    private val touched = ArrayList<CharPosition>()
    private lateinit var view: ScreenshotView

    /** A line of 40 px wide characters starting at x = 100. */
    private fun line(text: String, top: Float) = OcrResult.buildLine(
        listOf(text to Box(100f, top, 100f + 40 * text.length, top + 40)),
        listOf(text.mapIndexed { i, c -> c.toString() to Box(100f + 40 * i, top, 140f + 40 * i, top + 40) }),
    )

    @Before
    fun setUp() {
        setDurationScale(0f) // Moves and fades happen at once.
        view = ScreenshotView(RuntimeEnvironment.getApplication(), object : ScreenshotView.Listener {
            override fun onCharTouched(position: CharPosition, x: Float, y: Float, initial: Boolean) {
                touched.add(position)
            }

            override fun onEmptyTap() = Unit
        })
        view.setImage(Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888), ScreenshotView.Mode.FIT)
        view.layout(0, 0, 1000, 2000)
        view.showResult(OcrResult(listOf(OcrBlock(listOf(line("あいう", 100f), line("かきく", 300f)))), 1000, 2000))
    }

    @After
    fun tearDown() = setDurationScale(1f)

    private fun setDurationScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    @Test
    fun aFingerStaysOnItsTextWhileTheImageMoves() {
        touch(MotionEvent.ACTION_DOWN, 120f, 320f)
        assertEquals(CharPosition(0, 1, 0), touched.last())
        // The result card appears and the image moves up; the finger slides one character right.
        view.panTo(0f, -200f)
        touch(MotionEvent.ACTION_MOVE, 160f, 320f)
        assertEquals(CharPosition(0, 1, 1), touched.last())
        touch(MotionEvent.ACTION_UP, 160f, 320f)

        // A new touch picks the text that is shown under the finger now.
        touch(MotionEvent.ACTION_DOWN, 120f, 120f)
        assertEquals(CharPosition(0, 1, 0), touched.last())
    }

    @Test
    fun selectionBoundsIgnoreTheImagesMove() {
        view.setSelection(listOf(CharPosition(0, 1, 0), CharPosition(0, 1, 1)))
        view.panTo(0f, -200f)
        val bounds = RectF()
        assertTrue(view.selectionBounds(bounds))
        // The characters at 100..180 x 300..340, with some room around them.
        assertTrue(bounds.toString(), bounds.left < 100f && bounds.left > 90f)
        assertTrue(bounds.toString(), bounds.top < 300f && bounds.top > 290f)
        assertTrue(bounds.toString(), bounds.right > 180f && bounds.bottom > 340f)
        view.setSelection(emptyList())
        assertTrue(!view.selectionBounds(bounds))
    }

    @Test
    fun theImageMovesAsLittleAsNeeded() {
        // Already visible above the card (whose top is at 1000).
        assertEquals(0f, ScreenLookupView.fit(100f, 200f, 50f, 1000f, 0f), 0f)
        // Behind the card: moves up just enough.
        assertEquals(-150f, ScreenLookupView.fit(900f, 1150f, 50f, 1000f, 0f), 0f)
        // Still visible where it was moved to: stays.
        assertEquals(-150f, ScreenLookupView.fit(900f, 1000f, 50f, 1000f, -150f), 0f)
        // Taller than the space: its start is shown.
        assertEquals(-850f, ScreenLookupView.fit(900f, 2000f, 50f, 1000f, 0f), 0f)
    }
}
