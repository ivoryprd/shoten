package io.github.ivoryprd.shoten.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

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

        /** The vertical forms of 。 (a ring) and 、 (a dot) in the upper right of the cell at ([x], [y]) of [size]. */
        fun period(x: Int, y: Int, size: Int) {
            val t = (size / 20).coerceAtLeast(2)
            val l = x + size * 6 / 10
            val top = y + size / 8
            val d = size / 4
            rect(l, top, l + d, top + t); rect(l, top + d - t, l + d, top + d); rect(l, top, l + t, top + d); rect(l + d - t, top, l + d, top + d)
        }

        fun comma(x: Int, y: Int, size: Int) = rect(x + size * 6 / 10, y + size / 8, x + size * 6 / 10 + size / 6, y + size / 8 + size / 5)

        /** The vertical form of the long vowel mark: a bar down the middle of the cell. */
        fun bar(x: Int, y: Int, size: Int) = rect(x + size / 2 - size / 20, y + size / 10, x + size / 2 + size / 20, y + size * 9 / 10)

        /** ！: a bar and a dot. */
        fun exclamation(x: Int, y: Int, size: Int) {
            rect(x + size / 2 - size / 20, y + size / 10, x + size / 2 + size / 20, y + size * 6 / 10)
            rect(x + size / 2 - size / 20, y + size * 3 / 4, x + size / 2 + size / 20, y + size * 85 / 100)
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
    fun furiganaOfSeparateWordsIsNotOnOneGrid() {
        // Readings half the size of the text, centered on their words: two over the first
        // character, one over the second, two over the third. The gaps between them aren't whole
        // cells, so no grid fits all of them.
        val canvas = Canvas(400, 500)
        canvas.column(200, 40, 50, 7)
        canvas.column(252, 40, 25, 2, seed = 1)
        canvas.column(252, 102, 25, 1, seed = 2)
        canvas.column(252, 140, 25, 2, seed = 0)
        val columns = find(canvas, 46f).sortedByDescending { it.box.right }
        assertEquals(2, columns.size)
        val furigana = columns[0]
        assertEquals(5, furigana.cells.size)
        furigana.cells.zip(listOf(40, 65, 102, 140, 165)).forEach { (cell, top) ->
            assertEquals("cell at $top", top.toFloat(), cell.top, 3f)
            assertEquals(25f, cell.height, 2f)
        }
        assertCells(columns[1], 200, 40, 50, 7)
    }

    @Test
    fun cleaningKeepsInkAndRemovesFaintPatterns() {
        val white = 0xFFFFFFFF.toInt()
        fun gray(l: Int) = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        fun clean(pixel: Int, background: Int, contrast: VerticalLayout.Contrast) =
            VerticalLayout.clean(intArrayOf(pixel), background, contrast)[0] and 0xFF
        // Black text on white with gray screentone: the tone goes, the text stays.
        val text = VerticalLayout.Contrast(127, 255)
        assertTrue(clean(gray(150), white, text) > 245)
        assertEquals(0, clean(gray(0), white, text))
        assertEquals(255, clean(white, white, text))
        // Colors become gray by their brightness.
        assertEquals(0, clean(0xFF000000.toInt(), white, text))
        // Black text outlined in white on a gray picture: both stay as they are.
        val outlined = VerticalLayout.Contrast(64, 128)
        assertEquals(0, clean(gray(0), gray(128), outlined))
        assertEquals(255, clean(gray(255), gray(128), outlined))
        assertEquals(128, clean(gray(150), gray(128), outlined))
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
    fun marksWithVerticalForms() {
        // 私は。そこへ、行 in a column: punctuation where vertical text sets it.
        val canvas = Canvas(300, 600)
        for (i in listOf(0, 1, 3, 4, 6)) canvas.glyph(150, 40 + i * 40, 40, i)
        canvas.period(150, 40 + 2 * 40, 40)
        canvas.comma(150, 40 + 5 * 40, 40)
        // コーヒー！ in the next one: long vowel marks are bars, unlike ！.
        canvas.glyph(90, 40, 40, 0)
        canvas.bar(90, 80, 40)
        canvas.glyph(90, 120, 40, 2)
        canvas.bar(90, 160, 40)
        canvas.exclamation(90, 200, 40)
        val columns = find(canvas, 37f).sortedByDescending { it.box.right }
        assertEquals(2, columns.size)
        assertCells(columns[0], 150, 40, 40, 7)
        assertEquals(mapOf(2 to "。", 5 to "、"), columns[0].marks)
        assertCells(columns[1], 90, 40, 40, 5)
        assertEquals(mapOf(1 to "ー", 3 to "ー"), columns[1].marks)
        // The ink of every cell: the period's in the upper right of its cell.
        assertEquals(7, columns[0].inks.size)
        val period = columns[0].inks[2]!!
        assertTrue(period.left >= 150 + 20 && period.bottom <= 40 + 2 * 40 + 20)
    }

    @Test
    fun horizontalTextBesideColumnsIsNotAColumn() {
        // A short horizontal word beside the columns (a caption, a button) is no column of its own.
        val canvas = Canvas(400, 500)
        canvas.column(300, 40, 40, 9)
        canvas.column(240, 40, 40, 7, seed = 1)
        canvas.glyph(100, 300, 28, 0)
        canvas.glyph(128, 300, 28, 2)
        val columns = find(canvas, 45f).sortedByDescending { it.box.right }
        assertEquals(2, columns.size)
        assertCells(columns[0], 300, 40, 40, 9)
        assertCells(columns[1], 240, 40, 40, 7)
    }

    @Test
    fun theEndOfACharacterCutOffIsNoMark() {
        // A column whose grid cuts off the bottom of a character: a small piece of it at the top
        // of the next cell, in its upper right, where 、 would be.
        val canvas = Canvas(300, 500)
        canvas.column(150, 40, 40, 5)
        canvas.rect(150 + 26, 40 + 5 * 40, 150 + 32, 40 + 5 * 40 + 8)
        val columns = find(canvas, 37f)
        assertEquals(1, columns.size)
        assertTrue(columns[0].marks.isEmpty())
    }

    @Test
    fun marksArePutInWhereTheyWereMissedOrMisread() {
        val cells = List(4) { Box(100f, 40f + it * 40, 140f, 80f + it * 40) }
        val column = VerticalLayout.Column(Box(100f, 40f, 140f, 200f), cells, white, mapOf(1 to "。", 3 to "、"))
        fun read(column: VerticalLayout.Column, vararg chars: Pair<Int, String>) =
            VerticalLayout.readColumn(column, chars.map { (k, text) -> OcrChar(text, cells[k]) }).joinToString("") { it.text }
        // Not read at all, or read as look-alikes.
        assertEquals("す。そ、", read(column, 0 to "す", 2 to "そ"))
        assertEquals("す。そ、", read(column, 0 to "す", 1 to "o", 2 to "そ", 3 to ","))
        // Read as punctuation: the recognizer knows better than the shape.
        assertEquals("す、そ。", read(column, 0 to "す", 1 to "、", 2 to "そ", 3 to "。"))
        // Read as something else: a small character, say, that only looked like punctuation.
        assertEquals("すっそ、", read(column, 0 to "す", 1 to "っ", 2 to "そ"))
        // A mark carries the box of its cell.
        assertEquals(cells[1], VerticalLayout.readColumn(column, emptyList())[0].box)

        // Long vowel marks: read as a dash or the kanji 一, or not at all. At the end of the
        // column, it stays one (other vertical forms make a horizontal ー there a bracket).
        val coffee = VerticalLayout.Column(Box(100f, 40f, 140f, 200f), cells, white, mapOf(1 to "ー", 3 to "ー"))
        assertEquals("コーヒー", read(coffee, 0 to "コ", 1 to "一", 2 to "ヒ", 3 to "ー"))
        assertEquals("コーヒー", read(coffee, 0 to "コ", 2 to "ヒ", 3 to "-"))
        // A bar read as a dash is one: ――.
        assertEquals("そ――", read(coffee, 0 to "そ", 1 to "―", 3 to "―"))
        assertEquals("コーヒ」", read(column = VerticalLayout.Column(column.box, cells, white), 0 to "コ", 1 to "|", 2 to "ヒ", 3 to "ー"))
    }

    @Test
    fun punctuationMadeUpBesideACharacterIsDropped() {
        val cells = List(3) { Box(100f, 40f + it * 40, 140f, 80f + it * 40) }
        val column = VerticalLayout.Column(Box(100f, 40f, 140f, 160f), cells, white)
        fun read(vararg chars: Pair<Int, String>) =
            VerticalLayout.readColumn(column, chars.map { (k, text) -> OcrChar(text, cells[k]) }).joinToString("") { it.text }
        // The recognizer read し、 in the last cell of a row (seen on a phone).
        assertEquals("強し", read(0 to "強", 1 to "し", 1 to "、"))
        // Punctuation by itself in a cell stays.
        assertEquals("強し、", read(0 to "強", 1 to "し", 2 to "、"))
        // So do two characters (!? set in one cell).
        assertEquals("強!?", read(0 to "強", 1 to "!", 1 to "?"))
    }

    @Test
    fun screentoneIsToldFromPlainText() {
        val plain = Canvas(300, 400)
        plain.column(150, 30, 40, 6)
        plain.column(90, 30, 40, 5, seed = 1)
        assertFalse(find(plain, 37f).first().contrast.textured)
        // The same on gray dots.
        val tone = Canvas(300, 400)
        for (y in 0 until 400 step 4) for (x in 0 until 300 step 4) tone.rect(x, y, x + 2, y + 2, 0xFF969696.toInt())
        tone.column(150, 30, 40, 6)
        tone.column(90, 30, 40, 5, seed = 1)
        assertTrue(find(tone, 37f).first().contrast.textured)
    }

    @Test
    fun bothReadingsAreMerged() {
        val cells = List(5) { Box(100f, 40f + it * 40, 140f, 80f + it * 40) }
        val column = VerticalLayout.Column(Box(100f, 40f, 140f, 240f), cells, white, marks = mapOf(4 to "。"))
        val sheet = listOf("待", "つ", "天", "く", "。").mapIndexed { k, t -> OcrChar(t, cells[k]) }
        // What the cells look like, for the judge: the native reading got 大 wrong, the sheet っ.
        val truth = listOf("待", "っ", "天", "く", "。")
        val asked = ArrayList<List<String>>()
        fun merge(native: Map<Int, String>) = VerticalLayout.merge(column, sheet, native) { k, candidates ->
            asked += candidates
            if (truth[k] in candidates) truth[k] else candidates.first()
        }.joinToString("") { it.text }
        assertEquals("待っ天く。", merge(mapOf(0 to "待", 1 to "っ", 2 to "大", 3 to "く", 4 to "o")))
        // Asked only where they differ (and for kana that have a small form), the sheet's reading first.
        assertTrue(listOf("つ", "っ") in asked && listOf("天", "大") in asked)
        assertTrue(asked.none { "待" in it || "く" in it })
        // The mark in cell 4 isn't questioned.
        assertTrue(asked.none { "o" in it })
        // Without a native reading: the sheet's (small kana still told by the image).
        assertEquals("待っ天く。", merge(emptyMap()))
        // What only the native reading has is taken.
        assertEquals("待っ天く。", VerticalLayout.merge(column, sheet.filterIndexed { k, _ -> k != 2 }, mapOf(2 to "天")) { k, c -> if (truth[k] in c) truth[k] else c.first() }.joinToString("") { it.text })
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
    fun regionsGrowToTakeInColumnsBeyondThem() {
        // Four columns, the last one longer; the recognizer found only the first.
        val canvas = Canvas(500, 700)
        canvas.column(400, 40, 40, 6)
        canvas.column(344, 40, 40, 6, seed = 1)
        canvas.column(288, 40, 40, 6, seed = 2)
        canvas.column(232, 40, 40, 12, seed = 1)
        var region = VerticalLayout.regions(listOf(Box(400f, 40f, 440f, 280f) to 6), canvas.width, canvas.height).single()
        fun columns(r: VerticalLayout.Region) =
            VerticalLayout.columns(IntArray(r.width * r.height) { i -> canvas.pixels[(r.top + i / r.width) * canvas.width + r.left + i % r.width] }, r)
        var columns = columns(region)
        // The region around it cuts the third column off.
        assertTrue(columns.size < 4)
        for (i in 0 until 4) {
            region = VerticalLayout.grown(region, columns, canvas.width, canvas.height) ?: break
            columns = columns(region)
        }
        columns = columns.sortedByDescending { it.box.right }
        assertEquals(4, columns.size)
        assertCells(columns[2], 288, 40, 40, 6)
        assertCells(columns[3], 232, 40, 40, 12)
        // Nothing to grow for when the columns are well inside.
        assertEquals(null, VerticalLayout.grown(VerticalLayout.Region(0, 0, 500, 700, 37f), columns, canvas.width, canvas.height))
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
