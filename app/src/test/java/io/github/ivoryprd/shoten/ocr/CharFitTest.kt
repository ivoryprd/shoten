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
}
