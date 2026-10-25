package io.github.ivoryprd.shoten.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHelpersTest {
    @Test
    fun pinyinToneMarks() {
        assertEquals("chuán tǒng", Pinyin.toMarked("chuan2 tong3"))
        assertEquals("Zhōng guó", Pinyin.toMarked("Zhong1 guo2"))
        assertEquals("nǚ ér", Pinyin.toMarked("nu:3 er2"))
        assertEquals("lüè", Pinyin.toMarked("lu:e4"))
        assertEquals("liù", Pinyin.toMarked("liu4"))
        assertEquals("guì", Pinyin.toMarked("gui4"))
        assertEquals("zǒu", Pinyin.toMarked("zou3"))
        assertEquals("ma", Pinyin.toMarked("ma5"))
        assertEquals("T xù", Pinyin.toMarked("T xu4"))
        assertEquals("V zì", Pinyin.toMarked("V zi4"))
        assertEquals("Ēr", Pinyin.toMarked("Er1"))
        assertEquals(listOf(2, 3), Pinyin.syllables("chuan2 tong3").map { it.tone })
        assertEquals("CL:個|个[gè]", Pinyin.convertBracketed("CL:個|个[ge4]"))
        assertEquals("see 北京[Běi jīng]", Pinyin.convertBracketed("see 北京[Bei3 jing1]"))
        assertTrue(Pinyin.looksNumbered("ni3 hao3"))
        assertFalse(Pinyin.looksNumbered("nǐ hǎo"))
        val ng = Pinyin.toMarked("ng2")
        assertEquals(3, ng.length)
        assertTrue(ng.startsWith("n"))
    }

    @Test
    fun furigana() {
        assertEquals(
            listOf(FuriganaSegment("食", "た"), FuriganaSegment("べ", null), FuriganaSegment("物", "もの")),
            Furigana.distribute("食べ物", "たべもの"),
        )
        assertEquals(listOf(FuriganaSegment("漢字", "かんじ")), Furigana.distribute("漢字", "かんじ"))
        assertEquals(listOf(FuriganaSegment("たべる", null)), Furigana.distribute("たべる", "たべる"))
        assertEquals(listOf(FuriganaSegment("コーヒー", null)), Furigana.distribute("コーヒー", "こーひー"))
        assertEquals(
            listOf(FuriganaSegment("引", "ひ"), FuriganaSegment("き", null), FuriganaSegment("出", "だ"), FuriganaSegment("し", null)),
            Furigana.distribute("引き出し", "ひきだし"),
        )
        assertEquals(
            listOf(FuriganaSegment("アメリカ", null), FuriganaSegment("人", "じん")),
            Furigana.distribute("アメリカ人", "アメリカじん"),
        )
        // Impossible split falls back to the whole reading.
        assertEquals(listOf(FuriganaSegment("お茶", "ちゃ")), Furigana.distribute("お茶", "ちゃ"))
        assertEquals(listOf(FuriganaSegment("今日", "きょう")), Furigana.distribute("今日", "きょう"))
    }

    @Test
    fun scanTextPreparation() {
        assertEquals("食べる", LookupEngine.prepareScanText("食べる。次"))
        assertEquals("", LookupEngine.prepareScanText("「食べる」"))
        assertEquals("コーヒー", LookupEngine.prepareScanText("ｺｰﾋｰ"))
        assertEquals("あいう", LookupEngine.prepareScanText("あいうえお", 3))
        assertEquals("abc", LookupEngine.prepareScanText("abc def"))
    }
}
