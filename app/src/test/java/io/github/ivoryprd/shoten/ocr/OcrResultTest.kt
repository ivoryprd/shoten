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
    fun spacesBetweenLatinWordsAndPunctuation() {
        fun words(vararg w: String) = OcrResult.buildLine(w.mapIndexed { i, t -> t to Box(i * 100f, 0f, i * 100f + 80f, 20f) }, null).text
        assertEquals("to see; to look", words("to", "see;", "to", "look"))
        assertEquals("page 10. [1]", words("page", "10.", "[1]"))
        assertEquals("見るABC", words("見る", "ABC"))
        assertEquals("来た!勇者", words("来た", "!", "勇者"))
    }

    @Test
    fun implausibleSymbolBoxesAreReplaced() {
        val piece = "日本語" to Box(0f, 0f, 60f, 20f)
        // Out of order, and one far outside the piece.
        val bad = listOf("日" to Box(40f, 0f, 60f, 20f), "本" to Box(0f, 0f, 20f, 20f), "語" to Box(200f, 0f, 220f, 20f))
        val line = OcrResult.buildLine(listOf(piece), listOf(bad))
        assertEquals(Box(0f, 0f, 20f, 20f), line.chars[0].box)
        assertEquals(Box(40f, 0f, 60f, 20f), line.chars[2].box)
        // Plausible symbols that stick out a little are kept, limited to the piece's box.
        val word = "abc" to Box(0f, 0f, 30f, 20f)
        val good = listOf("a" to Box(-2f, -1f, 11f, 21f), "b" to Box(11f, 0f, 20f, 20f), "c" to Box(20f, 0f, 31f, 20f))
        val kept = OcrResult.buildLine(listOf(word), listOf(good))
        assertEquals(Box(0f, 0f, 11f, 20f), kept.chars[0].box)
        assertEquals(Box(11f, 0f, 20f, 20f), kept.chars[1].box)
    }

    @Test
    fun fitsCjkCharactersIntoTheirBox() {
        // ML Kit's boxes, measured on 「ここから先は危険だ。 in a 44 px font starting at x = 90: shifted to the right.
        val symbols = listOf(
            "「" to Box(119f, 0f, 152f, 40f), "こ" to Box(152f, 0f, 191f, 40f), "こ" to Box(191f, 0f, 238f, 40f),
            "か" to Box(238f, 0f, 277f, 40f), "ら" to Box(277f, 0f, 320f, 40f), "先" to Box(320f, 0f, 371f, 40f),
            "は" to Box(371f, 0f, 414f, 40f), "危" to Box(414f, 0f, 457f, 40f), "険" to Box(457f, 0f, 500f, 40f),
            "だ" to Box(500f, 0f, 527f, 40f), "。" to Box(527f, 0f, 544f, 40f),
        )
        val line = OcrResult.buildLine(listOf("「ここから先は危険だ。" to Box(119f, 0f, 544f, 40f)), listOf(symbols))
        // The real cells are 44 px wide: 先 is at 310..354.
        val cell = line.chars[5].box!!
        assertEquals(310f, cell.left, 3f)
        assertEquals(354f, cell.right, 3f)
        assertEquals(119f, line.chars[0].box!!.left, 0.01f)

        // Full-width digits (reported as ASCII, but as wide as the other characters) and half-width ones.
        fun digits(text: String, widths: List<Float>): List<Box> {
            var x = 0f
            val boxes = text.mapIndexed { i, c -> (c.toString() to Box(x, 0f, x + widths[i], 40f)).also { x += widths[i] } }
            return OcrResult.buildLine(listOf(text to Box(0f, 0f, x, 40f)), listOf(boxes)).chars.map { it.box!! }
        }
        val full = digits("10ページ", listOf(40f, 40f, 40f, 40f, 40f))
        assertEquals(40f, full[0].width, 0.5f)
        val half = digits("10ページ", listOf(20f, 20f, 40f, 40f, 40f))
        assertEquals(20f, half[0].width, 0.5f)
        assertEquals(40f, half[2].left, 0.5f)
    }

    @Test
    fun latinLettersInCjkTextKeepTheirWidths() {
        fun parse(s: String) = Regex("""(\S+?)\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").findAll(s).map { m ->
            val (t, l, top, r, b) = m.destructured
            t to Box(l.toFloat(), top.toFloat(), r.toFloat(), b.toFloat())
        }.toList()
        // ML Kit's boxes, measured on メールはinfo@example.comまで送ってください。 in a 40 px font from
        // x = 40 (every Latin character 20 px): two pieces, split at the dot.
        val first = parse(
            "メ[34,633][95,670] ー[95,633][138,670] ル[138,633][175,670] は[175,633][203,670] i[203,633][228,670] " +
                "n[228,633][249,670] f[249,633][268,670] o[268,633][286,670] @[286,633][305,670] e[305,633][326,670] " +
                "x[326,633][348,670] a[348,633][369,670] m[369,633][391,670] p[391,633][409,670] l[409,633][428,670] " +
                "e[428,633][443,670] .[443,633][456,670]",
        )
        val second = parse(
            "c[471,633][486,670] o[486,633][508,670] m[508,633][539,670] ま[539,633][576,670] で[576,633][619,670] " +
                "送[619,633][659,670] っ[659,633][696,670] て[696,633][733,670] く[733,633][773,670] だ[773,633][816,670] " +
                "さ[816,633][853,670] い[853,633][881,670] 。[881,633][903,670]",
        )
        val line = OcrResult.buildLine(
            listOf("メールはinfo@example." to Box(34f, 633f, 456f, 670f), "comまで送ってください。" to Box(471f, 633f, 903f, 670f)),
            listOf(first, second),
        )
        // ML Kit's kana boxes are narrower than their cells; measured by them, the Latin letters
        // came out about 15% too wide (a letter over a word). "info@example" is 6 em wide.
        val chars = line.chars.filter { it.box != null }
        val run = chars.subList(chars.indexOfFirst { it.text == "i" }, chars.indexOfFirst { it.text == "." })
        val em = line.chars.first { it.text == "ル" }.box!!.width
        assertEquals(6f, (run.last().box!!.right - run.first().box!!.left) / em, 0.4f)
        val dot = line.chars.first { it.text == "." }.box!!
        assertTrue("the dot is half width at most: ${dot.width}", dot.width <= 22f)
    }

    @Test
    fun usesTheRecognizersLineBox() {
        val lineBox = Box(0f, -2f, 62f, 22f)
        val line = OcrResult.buildLine(listOf("日本語" to Box(0f, 0f, 60f, 20f)), listOf(line("日本語", 0f, 0f)), lineBox)
        assertEquals(lineBox, line.box)
        assertEquals(Box(0f, 0f, 60f, 20f), OcrResult.buildLine(listOf("日本語" to Box(0f, 0f, 60f, 20f)), null).box)
    }

    @Test
    fun fullTextAndEmptiness() {
        assertEquals("食べさせ\nられなかった\n\nHello world", result.fullText)
        assertFalse(result.isEmpty)
        assertTrue(OcrResult(emptyList(), 1, 1).isEmpty)
    }

    @Test
    fun correctingCharacters() {
        val corrected = result.withChar(CharPosition(0, 0, 1), "ベ")
        assertEquals("食ベさせ", corrected.blocks[0].lines[0].text)
        // The box stays; the rest of the result too.
        assertEquals(result.charAt(CharPosition(0, 0, 1))?.box, corrected.charAt(CharPosition(0, 0, 1))?.box)
        assertEquals(result.blocks[0].lines[1].text, corrected.blocks[0].lines[1].text)
        assertEquals("食ベさせられなかった", corrected.scanText(CharPosition(0, 0, 0), 24).text)
        // Deleting.
        val deleted = result.withChar(CharPosition(0, 1, 0), null)
        assertEquals("れなかった", deleted.blocks[0].lines[1].text)
        assertEquals("せれなかった", deleted.scanText(CharPosition(0, 0, 3), 24).text)
        // Positions that don't exist change nothing.
        assertEquals(result.fullText, result.withChar(CharPosition(5, 0, 0), "x").fullText)
        assertEquals(result.fullText, result.withChar(CharPosition(0, 0, 9), "x").fullText)
    }

    @Test
    fun correctedColumnsStayVertical() {
        // A column of two characters, corrected down to one (a single character's box doesn't tell).
        val column = OcrLine(listOf(OcrChar("今", Box(0f, 0f, 20f, 20f)), OcrChar("日", Box(0f, 20f, 20f, 40f))))
        assertTrue(column.vertical)
        val corrected = OcrResult(listOf(OcrBlock(listOf(column))), 100, 100).withChar(CharPosition(0, 0, 1), null)
        assertTrue(corrected.blocks[0].lines[0].vertical)
        assertEquals("今", corrected.blocks[0].lines[0].text)
    }

    @Test
    fun heIsHiraganaOrKatakanaByItsNeighbors() {
        fun fix(text: String) = OcrResult.fixHe(text.map { OcrChar(it.toString(), null) }).joinToString("") { it.text }
        assertEquals("べんきょう", fix("ベんきょう"))
        assertEquals("ヘリコプター", fix("へリコプター"))
        assertEquals("ベース", fix("べース"))
        // Next to kanji only, or to both kinds of kana: as read.
        assertEquals("東ヘ行く", fix("東ヘ行く"))
        assertEquals("東へ行く", fix("東へ行く"))
        assertEquals("あベン", fix("あベン"))
        assertEquals("ペン", fix("ぺン"))
    }
}
