package io.github.ivoryprd.shoten

import io.github.ivoryprd.shoten.dict.importer.CedictImporter
import io.github.ivoryprd.shoten.dict.importer.YomitanImporter
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.LookupEngine
import io.github.ivoryprd.shoten.lookup.LookupResult
import io.github.ivoryprd.shoten.testing.MemoryDictionary
import io.github.ivoryprd.shoten.testing.MemoryTermSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Lookups against the real Jitendex and CC-CEDICT downloads. Skipped unless the environment
 * variable SHOTEN_TEST_DATA points to a directory containing jitendex-yomitan.zip and/or
 * cedict.zip.
 */
class RealDictionaryTest {
    companion object {
        private var jitendex: MemoryDictionary? = null
        private var cedict: MemoryDictionary? = null

        @BeforeClass
        @JvmStatic
        fun load() {
            val dir = System.getenv("SHOTEN_TEST_DATA")?.let(::File) ?: return
            File(dir, "jitendex-yomitan.zip").takeIf { it.exists() }?.let { file ->
                jitendex = MemoryDictionary("jitendex", 0).also { YomitanImporter.import(file, it) }
            }
            File(dir, "cedict.zip").takeIf { it.exists() }?.let { file ->
                cedict = MemoryDictionary("cedict", 1).also { CedictImporter.import(file, it) }
            }
        }
    }

    private fun ja(text: String): LookupResult {
        val dict = jitendex
        assumeTrue("Jitendex not available", dict != null)
        return LookupEngine(MemoryTermSource(listOf(dict!!))).lookup(text, Language.JAPANESE)
    }

    private fun zh(text: String): LookupResult {
        val dict = cedict
        assumeTrue("CC-CEDICT not available", dict != null)
        return LookupEngine(MemoryTermSource(listOf(dict!!))).lookup(text, Language.CHINESE)
    }

    private fun assertTop(result: LookupResult, expression: String, reading: String? = null, length: Int? = null) {
        val top = result.groups.firstOrNull()
        val summary = result.groups.take(5).map { "${it.expression}[${it.reading}] ${it.sourceLength} ${it.reasons}" }
        assertTrue("expected $expression for '${result.text}', got $summary", top != null && top.expression == expression)
        if (reading != null) assertEquals("reading for ${result.text}: $summary", reading, top!!.reading)
        if (length != null) assertEquals("length for ${result.text}: $summary", length, top!!.sourceLength)
    }

    @Test
    fun jitendexMetadata() {
        val meta = jitendex.also { assumeTrue(it != null) }!!.metadata!!
        assertEquals(Language.JAPANESE, meta.sourceLanguage)
        assertTrue(meta.isUpdatable)
        assertTrue(meta.title.startsWith("Jitendex"))
        assertTrue(jitendex!!.terms.size > 300_000)
        assertTrue(jitendex!!.styles!!.contains("data-sc-content"))
        assertTrue(jitendex!!.media.isNotEmpty())
    }

    @Test
    fun japaneseSentences() {
        assertTop(ja("食べさせられなかった"), "食べる", "たべる", 10)
        val ikanakereba = ja("行かなければならない")
        assertTop(ikanakereba, "行く", "いく", 6)
        assertEquals(listOf("negative", "-ba"), ikanakereba.groups[0].reasons)
        assertTop(ja("なければならない"), "なければならない")
        assertTop(ja("読んでいます"), "読む", "よむ", 6)
        // Jitendex has its own entry for 見られる, which needs fewer transformations than 見る.
        val mirareteiru = ja("見られている")
        assertTop(mirareteiru, "見られる", length = 6)
        assertEquals("見る", mirareteiru.groups[1].expression)
        assertEquals(listOf("passive/potential", "progressive"), mirareteiru.groups[1].reasons)
        assertTop(ja("美味しかった"), "美味しい", length = 6)
        assertTop(ja("コーヒーを飲みたい"), "コーヒー", length = 4)
        assertTop(ja("飲みたい"), "飲む", length = 4)
        assertTop(ja("してしまった"), "する", length = 6)
        assertTop(ja("わからない"), "分かる", "わかる", 5)
        assertTop(ja("勉強しています"), "勉強")
        assertTop(ja("いらっしゃいませ"), "いらっしゃいませ")
        assertTop(ja("喰べた"), "食べる", "たべる", 3)
        assertTop(ja("きれいな花"), "綺麗", "きれい")
        assertTop(ja("東京都に住んでいる"), "東京都")
        assertTop(ja("住んでいる"), "住む", length = 5)
        assertTop(ja("来なかった"), "来る", length = 5)
        // Kana-only text matches the kanji headword through its reading.
        val arigatou = ja("ありがとうございました")
        assertEquals("ありがとうございました", arigatou.groups[0].reading)
        assertEquals(11, arigatou.groups[0].sourceLength)
    }

    @Test
    fun ocrQuirks() {
        assertTop(ja("コ一ヒ一"), "コーヒー", length = 4)
        assertTop(ja("ﾃﾞｰﾀ"), "データ", length = 3)
    }

    @Test
    fun chineseSimplifiedAndTraditional() {
        val simplified = zh("中国人很好")
        assertTop(simplified, "中国人", length = 3)
        assertEquals("中國人", simplified.groups[0].alt)
        val traditional = zh("傳統文化")
        assertTop(traditional, "傳統", length = 2)
        assertEquals("传统", traditional.groups[0].alt)
        val r = zh("图书馆在哪里")
        assertTop(r, "图书馆", length = 3)
        assertEquals("圖書館", r.groups[0].alt)
        assertTrue(zh("了").groups.size >= 2)
    }
}
