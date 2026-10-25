package io.github.ivoryprd.shoten.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkTest {
    @Test
    fun classifiesCharacters() {
        assertTrue(Cjk.isHiragana('あ'.code))
        assertTrue(Cjk.isKatakana('ア'.code))
        assertTrue(Cjk.isKana('ー'.code))
        assertTrue(Cjk.isHan('漢'.code))
        assertTrue(Cjk.isHan('々'.code))
        assertTrue(Cjk.isHan(0x20B9F)) // 𠮟
        assertFalse(Cjk.isHan('あ'.code))
        assertFalse(Cjk.isKana('A'.code))
    }

    @Test
    fun convertsKana() {
        assertEquals("こーひー", Cjk.katakanaToHiragana("コーヒー"))
        assertEquals("ゔぁ", Cjk.katakanaToHiragana("ヴァ"))
        assertEquals("タベル", Cjk.hiraganaToKatakana("たべる"))
        assertEquals("漢字かな", Cjk.katakanaToHiragana("漢字カナ"))
    }

    @Test
    fun halfWidthTablesLineUp() {
        // Every half-width katakana code point has a full-width counterpart.
        for (cp in 0xFF61..0xFF9F) {
            val converted = Cjk.halfWidthKanaToFull(cp.toChar())
            assertTrue("U+%04X".format(cp), converted.code !in 0xFF61..0xFF9F)
        }
        assertEquals("ガギグ", Cjk.normalizeHalfWidthKana("ｶﾞｷﾞｸﾞ"))
        assertEquals("パンダ", Cjk.normalizeHalfWidthKana("ﾊﾟﾝﾀﾞ"))
        assertEquals("コーヒー", Cjk.normalizeHalfWidthKana("ｺｰﾋｰ"))
        assertEquals("ヴ", Cjk.normalizeHalfWidthKana("ｳﾞ"))
    }

    @Test
    fun fixesOcrLookalikesOnlyNearKatakana() {
        assertEquals("コーヒー", Cjk.fixKatakanaLookalikes("コ一ヒ一"))
        assertEquals("カード", Cjk.fixKatakanaLookalikes("カ一ド"))
        assertEquals("一つ", Cjk.fixKatakanaLookalikes("一つ"))
        assertEquals("入口", Cjk.fixKatakanaLookalikes("入口"))
        assertEquals("ロボット", Cjk.fixKatakanaLookalikes("口ボット"))
    }

    @Test
    fun takesCodePoints() {
        val s = "a" + String(Character.toChars(0x20B9F)) + "b"
        assertEquals("a" + String(Character.toChars(0x20B9F)), Cjk.takeCodePoints(s, 2))
        assertEquals(s, Cjk.takeCodePoints(s, 10))
        assertEquals(3, Cjk.codePointCount(s))
    }
}
