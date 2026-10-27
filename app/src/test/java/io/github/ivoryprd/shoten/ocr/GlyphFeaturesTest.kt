package io.github.ivoryprd.shoten.ocr

import io.github.ivoryprd.shoten.lookup.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphFeaturesTest {
    private val size = GlyphFeatures.SIZE

    /** An image with ink in the rectangles ([left], [top], [right], [bottom], in pixels). */
    private fun image(vararg rects: IntArray): FloatArray {
        val ink = FloatArray(size * size)
        for ((l, t, r, b) in rects) for (y in t until b) for (x in l until r) ink[y * size + x] = 1f
        return ink
    }

    // Shapes like characters: 一, 丨, 十, 口, 二 (in the em box from 3 to 45).
    private val one = image(intArrayOf(6, 22, 42, 26))
    private val bar = image(intArrayOf(22, 6, 26, 42))
    private val cross = image(intArrayOf(6, 22, 42, 26), intArrayOf(22, 6, 26, 42))
    private val box = image(intArrayOf(8, 8, 40, 12), intArrayOf(8, 36, 40, 40), intArrayOf(8, 8, 12, 40), intArrayOf(36, 8, 40, 40))
    private val two = image(intArrayOf(10, 14, 38, 18), intArrayOf(6, 32, 42, 36))

    private fun similarity(a: FloatArray, b: FloatArray) = GlyphFeatures.similarity(GlyphFeatures.of(a), GlyphFeatures.of(b))

    @Test
    fun theSameLooksTheSame() {
        for (ink in listOf(one, bar, cross, box, two)) assertEquals(1f, similarity(ink, ink), 1e-4f)
    }

    @Test
    fun alikeShapesAreCloser() {
        // Slightly moved and thicker: still the same character.
        val thick = image(intArrayOf(7, 21, 43, 27))
        assertTrue(similarity(one, thick) > 0.9f)
        // 一 is more like 二 than like 丨 (another direction) or 口.
        assertTrue(similarity(one, two) > similarity(one, bar))
        assertTrue(similarity(one, two) > similarity(one, box))
        // 十 has both 一 and 丨 in it.
        assertTrue(similarity(cross, one) > similarity(box, one))
        assertTrue(similarity(cross, bar) > similarity(box, bar))
    }

    @Test
    fun cellsOfDarkOrLightTextLookTheSame() {
        // 十 in a 30 px cell, black on white and white on black, with faint dots around it.
        fun cell(ink: Int, background: Int, dots: Int): IntArray = IntArray(30 * 30) { i ->
            val x = i % 30
            val y = i / 30
            when {
                (y in 14..16 && x in 3..26) || (x in 14..16 && y in 3..26) -> ink
                x % 3 == 0 && y % 3 == 0 -> dots
                else -> background
            }
        }
        val dark = GlyphFeatures.cellInk(cell(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFA0A0A0.toInt()), 30, 30)
        val light = GlyphFeatures.cellInk(cell(0xFFFFFFFF.toInt(), 0xFF101010.toInt(), 0xFF606060.toInt()), 30, 30)
        assertTrue(similarity(dark, light) > 0.99f)
        // The dots don't count (they are fainter than the strokes), so it looks like 十.
        assertTrue(similarity(dark, cross) > similarity(dark, box))
        assertTrue(similarity(dark, cross) > 0.8f)
    }

    @Test
    fun suggestionsLookLikeTheImage() {
        // Four characters, drawn as the shapes above; 二 looks like 一 and the other way round.
        val shapes = mapOf("い" to one, "う" to bar, "え" to cross, "お" to box, "か" to two)
        val similar = SimilarChars { "い\tか\nか\tい\nう\tえ\nえ\tう\nお\tえ\n".byteInputStream() }
        val suggestions = CharSuggestions(similar) { text, _, _ -> shapes.getValue(text) }
        // Without an image: the look-alikes.
        assertEquals(listOf("か"), suggestions.suggest("い", null, Language.JAPANESE))
        // With one: what looks like it first (here 十 read as 一), never what was read.
        val list = suggestions.suggest("い", cross, Language.JAPANESE)
        assertEquals("え", list.first())
        assertTrue("い" !in list)
        assertEquals(4, list.size)
    }
}
