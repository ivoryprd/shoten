package io.github.ivoryprd.shoten.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VerticalLayoutTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** An image to draw glyph-like shapes on. */
    private inner class Canvas(val width: Int, val height: Int, background: Int = white) {
        val pixels = IntArray(width * height) { background }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int = black) {
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(height)) for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) pixels[y * width + x] = color
        }

        /**
         * A character-like shape in the cell at ([x], [y]) of [size]: 口, 三 or 十, inked over
         * about 85% of the cell. 三 has gaps inside, like many kana.
         */
        fun glyph(x: Int, y: Int, size: Int, kind: Int, color: Int = black) {
            val m = size * 8 / 100
            val s = size - 2 * m
            val t = (size / 10).coerceAtLeast(2)
            val l = x + m
            val top = y + m
            when (kind % 3) {
                0 -> { rect(l, top, l + s, top + t, color); rect(l, top + s - t, l + s, top + s, color); rect(l, top, l + t, top + s, color); rect(l + s - t, top, l + s, top + s, color) }
                1 -> for (k in 0..2) rect(l, top + k * (s - t) / 2, l + s, top + k * (s - t) / 2 + t, color)
                else -> { rect(l, top + s / 2 - t / 2, l + s, top + s / 2 + t / 2, color); rect(l + s / 2 - t / 2, top, l + s / 2 + t / 2, top + s, color) }
            }
        }

        /** A column of [count] characters, cells of [size] from ([x], [y]) down. */
        fun column(x: Int, y: Int, size: Int, count: Int, seed: Int = 0, color: Int = black) {
            for (i in 0 until count) glyph(x, y + i * size, size, i + seed, color)
        }
    }

    private fun find(canvas: Canvas, em: Float) =
        VerticalLayout.columns(canvas.pixels, VerticalLayout.Region(0, 0, canvas.width, canvas.height, em))

    private fun assertCells(column: VerticalLayout.Column, x: Int, y: Int, size: Int, count: Int) {
        assertEquals("cells of the column at $x", count, column.cells.size)
        column.cells.forEachIndexed { i, cell ->
            assertEquals("pitch", size.toFloat(), cell.height, 1.5f)
            assertTrue("cell $i at ${cell.top}, expected ${y + i * size}", abs(cell.top - (y + i * size)) <= 3f)
            assertEquals(x + size / 2f, cell.centerX, 3f)
        }
    }

    @Test
    fun findsColumnsAndTheirCharacters() {
        val canvas = Canvas(400, 500)
        canvas.column(300, 40, 40, 9)
        canvas.column(240, 40, 40, 7, seed = 1)
        canvas.column(180, 40, 40, 3, seed = 2) // Short, but sharing the others' pitch.
        val columns = find(canvas, 37f).sortedByDescending { it.box.right }
        assertEquals(3, columns.size)
        assertCells(columns[0], 300, 40, 40, 9)
        assertCells(columns[1], 240, 40, 40, 7)
        assertCells(columns[2], 180, 40, 40, 3)
    }

    @Test
    fun splitsColumnsThatTouch() {
        val canvas = Canvas(400, 400)
        canvas.column(200, 40, 50, 5)
        canvas.column(150, 40, 50, 5, seed = 2)
        val columns = find(canvas, 46f).sortedByDescending { it.box.right }
        assertEquals(2, columns.size)
        assertCells(columns[0], 200, 40, 50, 5)
        assertCells(columns[1], 150, 40, 50, 5)
    }

    @Test
    fun ignoresShapesThatAreNotColumns() {
        // A speech bubble's outline next to the text, too wide to be a column.
        val canvas = Canvas(400, 500)
        canvas.column(200, 60, 40, 8)
        for (y in 40 until 420) canvas.rect(260 + (y - 40) / 6, y, 266 + (y - 40) / 6, y + 1)
        val columns = find(canvas, 37f)
        assertEquals(1, columns.size)
        assertCells(columns[0], 200, 60, 40, 8)
    }

    @Test
    fun furiganaGetsItsOwnPitch() {
        val canvas = Canvas(400, 500)
        canvas.column(200, 40, 50, 7)
        canvas.column(252, 40, 22, 4, seed = 1) // Readings beside the first characters.
        val columns = find(canvas, 46f).sortedByDescending { it.box.right }
        assertEquals(2, columns.size)
        assertCells(columns[0], 252, 40, 22, 4)
        assertCells(columns[1], 200, 40, 50, 7)
    }

    @Test
    fun lightTextOnDarkBackground() {
        val canvas = Canvas(300, 400, background = 0xFF141828.toInt())
        canvas.column(150, 30, 40, 6, color = 0xFFF0F0F0.toInt())
        canvas.column(90, 30, 40, 4, seed = 1, color = 0xFFF0F0F0.toInt())
        val columns = find(canvas, 37f).sortedByDescending { it.box.right }
        assertEquals(2, columns.size)
        assertCells(columns[0], 150, 30, 40, 6)
        assertEquals(0xFF141828.toInt(), columns[0].background)
    }

    @Test
    fun aLineOfHorizontalTextIsNotVertical() {
        val canvas = Canvas(500, 200)
        for (i in 0 until 8) canvas.glyph(40 + i * 44, 60, 40, i)
        assertTrue(find(canvas, 37f).isEmpty())
        // Nor is an empty or flat region.
        assertTrue(find(Canvas(200, 200), 37f).isEmpty())
    }

    private fun fix(vararg texts: String) =
        VerticalLayout.fixVerticalForms(texts.map { OcrChar(it, null) }).joinToString("") { it.text }

    @Test
    fun verticalFormsReadAsOtherCharacters() {
        // The long vowel mark is a vertical bar in a column.
        assertEquals("コーヒー", fix("コ", "|", "ヒ", "|"))
        assertEquals("a|b", fix("a", "|", "b"))
        // The vertical ellipsis is a column of dots.
        assertEquals("な…", fix("な", ":"))
        // Corner brackets, turned: Latin look-alikes, or a horizontal long vowel mark at the ends.
        assertEquals("「ここ」", fix("J", "こ", "こ", "r"))
        assertEquals("「ここから", fix("ー", "こ", "こ", "か", "ら"))
        assertEquals("進むんだぞ」", fix("進", "む", "ん", "だ", "ぞ", "ー"))
        // A horizontal long vowel mark inside a column stays (a dash, or text set without vertical forms).
        assertEquals("あーい", fix("あ", "ー", "い"))
        // The kanji 一 is the same in columns.
        assertEquals("一つ", fix("一", "つ"))
    }

    @Test
    fun linesCoveredByColumns() {
        val columns = listOf(
            VerticalLayout.Column(Box(300f, 40f, 340f, 400f), emptyList(), white),
            VerticalLayout.Column(Box(240f, 40f, 280f, 320f), emptyList(), white),
        )
        // A column as the recognizer found it, its box a little larger than the ink.
        assertTrue(VerticalLayout.isCovered(Box(296f, 36f, 344f, 404f), columns))
        // A row of characters read across the columns.
        assertTrue(VerticalLayout.isCovered(Box(240f, 100f, 340f, 140f), columns))
        // Horizontal text above the columns, and beside them.
        assertFalse(VerticalLayout.isCovered(Box(200f, 0f, 400f, 30f), columns))
        assertFalse(VerticalLayout.isCovered(Box(100f, 200f, 230f, 240f), columns))
        assertFalse(VerticalLayout.isCovered(Box(100f, 200f, 100f, 240f), columns))
    }

    @Test
    fun regionsAroundRecognizedLines() {
        val regions = VerticalLayout.regions(
            listOf(
                Box(300f, 40f, 340f, 400f) to 9, // A column.
                Box(242f, 40f, 280f, 320f) to 7, // The next column: one region with the first.
                Box(344f, 40f, 362f, 120f) to 3, // Furigana: small, so it barely changes the size.
                Box(300f, 900f, 340f, 1000f) to 2, // Far away: a region of its own.
            ),
            1080, 2400,
        )
        assertEquals(2, regions.size)
        val main = regions.first { it.top < 500 }
        assertEquals(40f, main.em, 3f)
        // Widened sideways to find columns next to the recognized ones.
        assertTrue(main.left <= 242 - 2 * 38 && main.right >= 362 + 2 * 18)
    }
}
