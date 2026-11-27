package io.github.ivoryprd.shoten.ocr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineSheetTest {
    private fun line(text: String, box: Box, confidence: Float): OcrLine {
        val step = box.width / text.length
        return OcrLine(text.mapIndexed { i, c -> OcrChar(c.toString(), Box(box.left + i * step, box.top, box.left + (i + 1) * step, box.bottom), confidence) }, box)
    }

    @Test
    fun whatIsReadOnTheSheetIsMappedBackToTheImage() {
        val image = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)
        // Two lines of 40 px characters; on the sheet they are 48 px, as rows from the top.
        val lines = listOf(line("今日は", Box(100f, 50f, 220f, 90f), 0.3f), line("明日", Box(300f, 200f, 380f, 240f), 0.3f))
        val sheet = LineSheet.create(image, lines)!!
        // The first line read on the sheet where it was drawn: cut out 0.4 em to the left and
        // 0.25 em above it, 1.2 times as large, from (30, 30).
        fun onSheet(x: Float, y: Float) = 30f + (x - 84f) * 1.2f to 30f + (y - 40f) * 1.2f
        val (l, t) = onSheet(100f, 50f)
        val (r, b) = onSheet(140f, 90f)
        val read = OcrResult(listOf(OcrBlock(listOf(OcrLine(listOf(OcrChar("今", Box(l, t, r, b), 0.9f)), Box(l, t, r, b))))), sheet.bitmap.width, sheet.bitmap.height)
        val back = sheet.mapBack(read)
        val box = back[0]!!.chars.single().box!!
        assertEquals(100f, box.left, 0.5f)
        assertEquals(50f, box.top, 0.5f)
        assertEquals(140f, box.right, 0.5f)
        assertEquals(90f, box.bottom, 0.5f)
        assertEquals(0.9f, back[0]!!.chars.single().confidence, 0f)
        // Nothing was read in the second row.
        assertNull(back[1])
    }

    @Test
    fun onlyWhatIsBesideTheLineIsReadWithIt() {
        val image = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)
        val sheet = LineSheet.create(image, listOf(line("今日は晴れ", Box(100f, 50f, 300f, 90f), 0.3f)))!!
        fun read(text: String, left: Float, top: Float, width: Float) = line(text, Box(left, top, left + width, top + 48f), 0.8f)
        // The line in two pieces, and the bottom of a sign above it, cut into the row with it.
        val pieces = listOf(read("今日は", 40f, 36f, 144f), read("晴れ", 196f, 38f, 96f), read("081", 120f, 4f, 60f))
        val back = sheet.mapBack(OcrResult(listOf(OcrBlock(pieces)), sheet.bitmap.width, sheet.bitmap.height))
        assertEquals("今日は 晴れ", back[0]!!.text)
    }

    @Test
    fun theReadingTheRecognizerIsSurerOfIsKept() = runBlocking {
        val image = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)
        val unsure = line("今日け", Box(100f, 50f, 220f, 90f), 0.2f)
        val sure = line("明日", Box(300f, 200f, 380f, 240f), 0.5f)
        // The gray sheet reads both again: the first with more confidence, the second with less
        // (and the outlined one, which the second is read on as well, nothing).
        var sheets = 0
        val kept = LineSheet.reread(image, listOf(unsure, sure)) { sheet ->
            fun row(text: String, top: Float, confidence: Float) = line(text, Box(40f, top, 40f + 48f * text.length, top + 48f), confidence)
            if (sheets++ > 0) return@reread OcrResult(emptyList(), sheet.width, sheet.height)
            OcrResult(listOf(OcrBlock(listOf(row("今日は", 42f, 0.8f), row("明目", 138f, 0.3f)))), sheet.width, sheet.height)
        }
        assertEquals(2, sheets)
        assertEquals("今日は", kept[0].text)
        assertSame(sure, kept[1])

        // A reading with a character left out isn't taken, however sure.
        val shorter = LineSheet.reread(image, listOf(unsure)) { sheet ->
            OcrResult(listOf(OcrBlock(listOf(line("今日", Box(40f, 42f, 136f, 90f), 0.9f)))), sheet.width, sheet.height)
        }
        assertSame(unsure, shorter[0])
        // One with a character the first reading left out is.
        val dropped = line("今は", Box(100f, 50f, 220f, 90f), 0.2f)
        val longer = LineSheet.reread(image, listOf(dropped)) { sheet ->
            OcrResult(listOf(OcrBlock(listOf(line("今日は", Box(40f, 42f, 184f, 90f), 0.7f)))), sheet.width, sheet.height)
        }
        assertEquals("今日は", longer[0].text)
    }

    @Test
    fun outlinedLettersAreFilledIn() {
        // A letter on a gray photo: white, outlined in black, with a hole in it (outlined too).
        val width = 60
        val height = 40
        val gray = Color.rgb(150, 150, 150)
        val pixels = IntArray(width * height) { gray }
        fun fill(l: Int, t: Int, r: Int, b: Int, color: Int) {
            for (y in t until b) for (x in l until r) pixels[y * width + x] = color
        }
        fill(10, 8, 34, 32, Color.BLACK)
        fill(12, 10, 32, 30, Color.WHITE)
        fill(18, 16, 26, 24, Color.BLACK)
        fill(20, 18, 24, 22, gray)
        val letters = LineSheet.outlined(pixels, width, height, 8..32)
        fun at(x: Int, y: Int) = letters[y * width + x]
        // What the outline encloses is the letter, in solid black.
        assertEquals(Color.BLACK, at(14, 12))
        assertEquals(Color.BLACK, at(30, 28))
        // The photo around it, the outline itself and the hole are white.
        assertEquals(Color.WHITE, at(4, 4))
        assertEquals(Color.WHITE, at(50, 20))
        assertEquals(Color.WHITE, at(11, 20))
        assertEquals(Color.WHITE, at(22, 20))
    }

    @Test
    fun confidenceIsTheAverageOfTheCharacters() {
        assertEquals(0.4f, LineSheet.confidence(OcrLine(listOf(OcrChar("a", null, 0.2f), OcrChar(" ", null), OcrChar("b", null, 0.6f))))!!, 1e-6f)
        // None given: the recognizer's confidence isn't known.
        assertNull(LineSheet.confidence(OcrLine(listOf(OcrChar("a", null), OcrChar("b", null)))))
    }
}
