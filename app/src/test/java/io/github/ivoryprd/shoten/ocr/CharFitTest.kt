package io.github.ivoryprd.shoten.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class CharFitTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A line of characters of the given [widths] (their cells; ink 10% in from each side), height 40, from x = 20. */
    private fun line(widths: List<Int>, w: Int, h: Int, vertical: Boolean): Pair<IntArray, List<Pair<Float, Float>>> {
        val pixels = IntArray(w * h) { white }
        val cells = ArrayList<Pair<Float, Float>>()
        var x = 20
        for (cw in widths) {
            val m = cw / 10
            for (a in x + m until x + cw - m) for (b in 10 until 50) {
                // Two strokes, so characters have gaps inside too, like ハ or 川.
                if ((a - x - m) < cw / 4 || (a - x) > cw * 3 / 5) {
                    if (vertical) pixels[a * w + b] = black else pixels[b * w + a] = black
                }
            }
            cells += x.toFloat() to (x + cw).toFloat()
            x += cw
        }
        return pixels to cells
    }

    private fun check(
        vertical: Boolean,
        shift: Float,
        scale: Float,
        // Full-width characters and half-width ones (Latin, digits), with gaps inside like ハ.
        widths: List<Int> = listOf(40, 40, 20, 40, 20, 20, 40, 40),
    ) {
        val length = 20 + widths.sum() + 20
        val w = if (vertical) 60 else length
        val h = if (vertical) length else 60
        val (pixels, cells) = line(widths, w, h, vertical)
        // The recognizer's boxes: its box of the whole piece of text a little off (shifted, too
        // wide or too narrow), the characters fitted into it.
        fun off(p: Float) = 20 + (p - 20) * scale + shift
        val chars = cells.map { (a0, z0) ->
            val a = off(a0)
            val z = off(z0)
            OcrChar(if (z0 - a0 < 36) "a" else "字", if (vertical) Box(10f, a, 50f, z) else Box(a, 10f, z, 50f))
        }
        val lineBox = chars.mapNotNull { it.box }.reduce { a, b -> a.union(b) }
        val fitted = CharFit.fit(OcrLine(chars, lineBox, forceVertical = vertical), pixels, w, h, 0, 0)
        fitted.chars.zip(cells).forEachIndexed { i, (c, cell) ->
            val b = c.box!!
            val (a, z) = if (vertical) b.top to b.bottom else b.left to b.right
            // Each box within a few pixels of its character's cell (gaps split in the middle).
            assertEquals("start of $i", cell.first, a, 5f)
            assertEquals("end of $i", cell.second, z, 5f)
        }
    }

    @Test
    fun horizontalBoxesFitTheInk() {
        check(vertical = false, shift = 9f, scale = 1f)
        check(vertical = false, shift = -8f, scale = 1.04f)
        check(vertical = false, shift = 4f, scale = 0.96f)
    }

    @Test
    fun proportionalLatinInCjkText() {
        // 私はiMacとWindowsです: Latin letters from a third to three quarters of an em wide. Taken
        // all as half an em, the boxes after a long word were a character off.
        val widths = listOf(40, 40, 12, 32, 22, 20, 40, 34, 12, 22, 22, 22, 30, 18, 40, 40)
        check(vertical = false, shift = 6f, scale = 1f, widths = widths)
        check(vertical = false, shift = -5f, scale = 1.03f, widths = widths)
    }

    @Test
    fun verticalBoxesFitTheInk() = check(vertical = true, shift = -9f, scale = 1.03f)

    @Test
    fun blankImagesChangeNothing() {
        val chars = listOf(OcrChar("あ", Box(0f, 0f, 20f, 20f)), OcrChar("い", Box(20f, 0f, 40f, 20f)))
        val line = OcrLine(chars, Box(0f, 0f, 40f, 20f))
        val fitted = CharFit.fit(line, IntArray(40 * 20) { white }, 40, 20, 0, 0)
        assertEquals(chars.map { it.box }, fitted.chars.map { it.box })
    }

    /**
     * A line of 40 px characters drawn as two strokes each in [colors] (null: an emoji there, a
     * solid yellow and orange disk 48 px wide), from x = 20; the cells of the characters.
     */
    private fun picture(colors: List<Int?>): Triple<IntArray, Int, List<Pair<Float, Float>>> {
        val w = 20 + colors.sumOf { if (it == null) 48 else 40 } + 20
        val h = 60
        val pixels = IntArray(w * h) { white }
        val cells = ArrayList<Pair<Float, Float>>()
        var x = 20
        for (color in colors) {
            if (color == null) {
                for (a in x + 2 until x + 46) for (b in 10 until 50) {
                    val dx = a - (x + 24)
                    val dy = b - 30
                    if (dx * dx + dy * dy <= 400) pixels[b * w + a] = if (dy > 8) 0xFFE65100.toInt() else 0xFFFFC107.toInt()
                }
                x += 48
                continue
            }
            for (a in x + 4 until x + 36) for (b in 10 until 50) {
                if ((a - x - 4) < 3 || (a - x) in 24..26 || b in 28..30) pixels[b * w + a] = color
            }
            cells += x.toFloat() to (x + 40).toFloat()
            x += 40
        }
        return Triple(pixels, w, cells)
    }

    /** The recognizer's boxes for [cells]: its box of the whole text spread evenly over the characters, as without the emoji. */
    private fun read(texts: String, from: Float, to: Float): OcrLine {
        val step = (to - from) / texts.length
        val chars = texts.mapIndexed { i, c -> OcrChar(c.toString(), Box(from + i * step, 10f, from + (i + 1) * step, 50f)) }
        return OcrLine(chars, Box(from, 10f, to, 50f))
    }

    @Test
    fun emojiAreCutOutOfTheLine() {
        // 今日は😀いい天気: the emoji is ink, but not read.
        val (pixels, w, cells) = picture(listOf(black, black, black, null, black, black, black, black))
        val fitted = CharFit.fit(read("今日はいい天気", 20f, w - 20f), pixels, w, 60, 0, 0)
        fitted.chars.zip(cells).forEachIndexed { i, (c, cell) ->
            assertEquals("start of $i", cell.first, c.box!!.left, 5f)
            assertEquals("end of $i", cell.second, c.box!!.right, 5f)
        }
    }

    @Test
    fun whatTheRecognizerMadeOfAnEmojiIsLeftOut() {
        // 安い💰 read as 安い。: the 。 is the emoji.
        val (pixels, w, cells) = picture(listOf(black, black, black, null))
        val line = read("価安い", 20f, 140f).let { OcrLine(it.chars + OcrChar("。", Box(140f, 10f, w - 20f, 50f)), Box(20f, 10f, w - 20f, 50f)) }
        val fitted = CharFit.fit(line, pixels, w, 60, 0, 0)
        assertEquals("価安い", fitted.text)
        fitted.chars.zip(cells).forEachIndexed { i, (c, cell) -> assertEquals("end of $i", cell.second, c.box!!.right, 5f) }
    }

    @Test
    fun coloredTextIsNotTakenForEmoji() {
        // Black text with a blue word (a link) and a red one in it.
        val blue = 0xFF1A73E8.toInt()
        val red = 0xFFD32F2F.toInt()
        val (pixels, w, cells) = picture(listOf(black, blue, blue, black, red, black))
        val fitted = CharFit.fit(read("詳公式を赤字", 20f, w - 20f), pixels, w, 60, 0, 0)
        assertEquals(6, fitted.chars.size)
        fitted.chars.zip(cells).forEachIndexed { i, (c, cell) ->
            assertEquals("start of $i", cell.first, c.box!!.left, 5f)
            assertEquals("end of $i", cell.second, c.box!!.right, 5f)
        }
    }

    @Test
    fun anEmojiReadAsACharacterIsLeftOutIfTheRecognizerWasUnsure() {
        // 😂笑い read as の笑い, the の with a low confidence; a sure one is kept.
        val (pixels, w, cells) = picture(listOf(null, black, black, black))
        fun line(confidence: Float): OcrLine {
            val step = (w - 40f) / 4
            val chars = "の笑いす".mapIndexed { i, c -> OcrChar(c.toString(), Box(20f + i * step, 10f, 20f + (i + 1) * step, 50f), if (i == 0) confidence else 0.9f) }
            return OcrLine(chars, Box(20f, 10f, w - 20f, 50f))
        }
        val fitted = CharFit.fit(line(0.03f), pixels, w, 60, 0, 0)
        assertEquals("笑いす", fitted.text)
        fitted.chars.zip(cells).forEachIndexed { i, (c, cell) ->
            assertEquals("start of $i", cell.first, c.box!!.left, 5f)
            assertEquals("end of $i", cell.second, c.box!!.right, 5f)
        }
        assertEquals("の笑いす", CharFit.fit(line(0.9f), pixels, w, 60, 0, 0).text)
    }
}
