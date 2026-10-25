package io.github.ivoryprd.shoten.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrResultTest {
    /** A horizontal line of 20px wide characters starting at x, y. */
    private fun line(text: String, x: Float, y: Float): List<Pair<String, Box?>> =
        text.mapIndexed { i, c -> c.toString() to Box(x + i * 20, y, x + i * 20 + 20, y + 20) }

    private val result = OcrResult(
        listOf(
            OcrBlock(
                listOf(
                    OcrResult.buildLine(listOf("食べさせ" to Box(0f, 0f, 80f, 20f)), listOf(line("食べさせ", 0f, 0f))),
                    OcrResult.buildLine(listOf("られなかった" to Box(0f, 30f, 120f, 50f)), listOf(line("られなかった", 0f, 30f))),
                ),
            ),
            OcrBlock(listOf(OcrResult.buildLine(listOf("Hello" to Box(0f, 100f, 100f, 120f), "world" to Box(110f, 100f, 210f, 120f)), null))),
        ),
        300, 200,
    )

    @Test
    fun hitTestsCharacters() {
        assertEquals(CharPosition(0, 0, 1), result.hitTest(30f, 10f, 5f))
        assertEquals(CharPosition(0, 1, 0), result.hitTest(10f, 40f, 5f))
        // Slightly outside a character but within the slop.
        assertEquals(CharPosition(0, 0, 0), result.hitTest(-3f, 10f, 5f))
        assertNull(result.hitTest(250f, 10f, 5f))
        assertNull(result.hitTest(10f, 25f, 2f))
    }

    @Test
    fun scanTextContinuesIntoTheNextLineOfTheBlock() {
        val scan = result.scanText(CharPosition(0, 0, 1), 24)
        assertEquals("べさせられなかった", scan.text)
        assertEquals(
            listOf(CharPosition(0, 0, 1), CharPosition(0, 0, 2), CharPosition(0, 0, 3), CharPosition(0, 1, 0)),
            scan.positionsCovering(4),
        )
        assertEquals("べさ", result.scanText(CharPosition(0, 0, 1), 2).text)
    }

    @Test
    fun splitsElementsWithoutSymbolsAndSeparatesLatinWords() {
        val latin = result.blocks[1].lines[0]
        assertEquals("Hello world", latin.text)
        assertNull(latin.chars[5].box)
        val first = latin.chars[0].box!!
        assertEquals(0f, first.left, 0.01f)
        assertEquals(20f, first.right, 0.01f)
        assertFalse(latin.vertical)
    }

    @Test
    fun mergesHalfWidthKanaAndDetectsVerticalLines() {
        val chars = listOf("ｶ" to Box(0f, 0f, 10f, 20f), "ﾞ" to Box(10f, 0f, 15f, 20f), "ｰ" to Box(15f, 0f, 25f, 20f))
        val l = OcrResult.buildLine(listOf("ｶﾞｰ" to Box(0f, 0f, 25f, 20f)), listOf(chars))
        assertEquals("ガー", l.text)
        assertEquals(Box(0f, 0f, 15f, 20f), l.chars[0].box)

        val vertical = OcrResult.buildLine(listOf("縦書き" to Box(0f, 0f, 20f, 60f)), null)
        assertTrue(vertical.vertical)
        assertEquals(Box(0f, 20f, 20f, 40f), vertical.chars[1].box)
    }

    @Test
    fun fullTextAndEmptiness() {
        assertEquals("食べさせ\nられなかった\n\nHello world", result.fullText)
        assertFalse(result.isEmpty)
        assertTrue(OcrResult(emptyList(), 1, 1).isEmpty)
    }
}
