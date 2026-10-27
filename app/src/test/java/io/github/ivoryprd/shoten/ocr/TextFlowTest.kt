package io.github.ivoryprd.shoten.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextFlowTest {
    /** A line of [text] as ML Kit reports it: one piece, whose box is fitted into characters. */
    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        OcrResult.buildLine(listOf(text to Box(left, top, right, bottom)), null)

    /** A horizontal line of 40 px characters starting at ([x], [y]). */
    private fun row(text: String, x: Float, y: Float) = line(text, x, y, x + 40f * text.length, y + 37f)

    /** A vertical column of 40 px characters starting at ([x], [y]). */
    private fun column(text: String, x: Float, y: Float) = line(text, x, y, x + 37f, y + 40f * text.length)

    /** A result with every line in a block of its own, as ML Kit often reports wrapped text. */
    private fun separate(vararg lines: OcrLine) = OcrResult(lines.map { OcrBlock(listOf(it)) }, 1080, 2400)

    private fun scan(result: OcrResult, block: Int, char: Int) = result.scanText(CharPosition(block, 0, char), 24).text

    @Test
    fun wrappedLinesInSeparateBlocksAreReadAsOne() {
        val result = separate(row("今日はとても良い天", 100f, 100f), row("気ですね", 100f, 160f))
        assertEquals(LineRef(1, 0), result.nextLine(LineRef(0, 0)))
        assertEquals("い天気ですね", scan(result, 0, 7))
        // The highlight covers characters on both lines.
        val positions = result.scanText(CharPosition(0, 0, 8), 24).positionsCovering(2)
        assertEquals(listOf(CharPosition(0, 0, 8), CharPosition(1, 0, 0)), positions)
    }

    @Test
    fun furiganaBetweenLinesIsSkipped() {
        val result = separate(
            row("今日はとても良い天", 100f, 100f),
            line("き", 100f, 145f, 120f, 163f), // Over 気 at the start of the next line.
            row("気ですね", 100f, 165f),
        )
        assertEquals(LineRef(2, 0), result.nextLine(LineRef(0, 0)))
        assertNull(result.nextLine(LineRef(1, 0)))
        assertEquals("天気ですね", scan(result, 0, 8))
        // Furigana can still be looked up by itself.
        assertEquals("き", scan(result, 1, 0))
    }

    @Test
    fun separateParagraphsAreNotJoined() {
        // A short line followed by a longer one: the first one ends a paragraph.
        assertNull(separate(row("今日は", 100f, 100f), row("とても良い天気です", 100f, 160f)).nextLine(LineRef(0, 0)))
        // A sentence ends at the end of the line.
        assertNull(separate(row("今日はとても良い。", 100f, 100f), row("天気ですね", 100f, 160f)).nextLine(LineRef(0, 0)))
        // A translation below the Japanese text.
        assertNull(separate(row("今日はとても良い天", 100f, 100f), line("Nice weather", 100f, 160f, 400f, 197f)).nextLine(LineRef(0, 0)))
        // Far apart (like rows of a list), or of a different size.
        assertNull(separate(row("今日はとても良い天", 100f, 100f), row("気ですね", 100f, 240f)).nextLine(LineRef(0, 0)))
        assertNull(separate(row("今日はとても良い天", 100f, 100f), line("気ですね", 100f, 160f, 340f, 215f)).nextLine(LineRef(0, 0)))
        // Not below each other.
        assertNull(separate(row("今日はとても良い天", 100f, 100f), row("気ですね", 600f, 160f)).nextLine(LineRef(0, 0)))
    }

    @Test
    fun columnsOfTextNextToEachOtherStaySeparate() {
        val result = separate(
            row("左の段落の文", 0f, 0f), row("右の段落の文", 400f, 0f),
            row("章です", 0f, 60f), row("章です", 400f, 60f),
        )
        assertEquals(LineRef(2, 0), result.nextLine(LineRef(0, 0)))
        assertEquals(LineRef(3, 0), result.nextLine(LineRef(1, 0)))
    }

    @Test
    fun verticalColumnsContinueToTheLeft() {
        val result = separate(column("今日はとても良い天", 400f, 100f), column("気ですね", 340f, 100f))
        assertEquals(LineRef(1, 0), result.nextLine(LineRef(0, 0)))
        assertEquals("天気です", scan(result, 0, 8).take(4))
    }

    @Test
    fun paragraphsOfSeveralLines() {
        val result = separate(row("今日はとても良い天", 100f, 40f), row("気ですね。明日も晴", 100f, 100f), row("れるでしょう", 100f, 160f))
        assertEquals(LineRef(1, 0), result.nextLine(LineRef(0, 0)))
        assertEquals(LineRef(2, 0), result.nextLine(LineRef(1, 0)))
        assertEquals("天気ですね。明日も晴れるでしょう", scan(result, 0, 8))
    }

    @Test
    fun onlyOneLineContinuesALine() {
        // Two lines could continue on the last one; the one in the same block of ML Kit's wins.
        val result = OcrResult(
            listOf(
                OcrBlock(listOf(row("今日はとても良い天", 100f, 40f), row("気ですね", 100f, 160f))),
                OcrBlock(listOf(row("今日はとても良い天", 100f, 100f))),
            ),
            1080, 2400,
        )
        assertEquals(LineRef(0, 1), result.nextLine(LineRef(0, 0)))
        assertNull(result.nextLine(LineRef(1, 0)))
    }

    @Test
    fun latinWordsAcrossLinesAreSpaced() {
        val result = separate(line("to examine; to", 90f, 1509f, 400f, 1543f), line("judge", 141f, 1563f, 237f, 1602f))
        assertEquals("to judge", scan(result, 0, 12))
    }

    @Test
    fun realScreenshot() {
        // Boxes ML Kit reported for an entry with example sentences: each line in a block of its
        // own, with furigana between the lines.
        val result = separate(
            line("あたま", 178f, 1634f, 240f, 1655f),
            line("ふか かんが", 745f, 1629f, 885f, 1655f),
            line("頭がくらくらしているので、深く考えずに", 172f, 1659f, 1017f, 1707f),
            line("かげん", 260f, 1716f, 355f, 1745f),
            line("か", 533f, 1722f, 554f, 1742f),
            line("いい加減なことを書いてみる。", 181f, 1750f, 750f, 1791f),
            line("My head is swimming, so I'll try writing any old stuff", 178f, 1813f, 981f, 1847f),
        )
        assertEquals(LineRef(5, 0), result.nextLine(LineRef(2, 0)))
        for (furigana in listOf(0, 1, 3, 4)) assertNull(result.nextLine(LineRef(furigana, 0)))
        // The sentence ends; the translation is not part of it.
        assertNull(result.nextLine(LineRef(5, 0)))
        assertEquals("ずにいい加減", scan(result, 2, 17).take(6))
    }

    @Test
    fun realGameDialog() {
        // Boxes ML Kit reported for a dialog box with widely spaced lines (44 px text, 100 px apart).
        val result = separate(
            line("ここから先は危険だから気を付", 98f, 1563f, 703f, 1602f),
            line("けて進むんだぞ", 96f, 1663f, 396f, 1701f),
        )
        assertEquals("付けて進むんだぞ", scan(result, 0, 13))
    }
}
