package io.github.ivoryprd.shoten.lookup

import io.github.ivoryprd.shoten.dict.importer.DictionaryMetadata
import io.github.ivoryprd.shoten.testing.MemoryDictionary
import io.github.ivoryprd.shoten.testing.MemoryTermSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupEngineTest {
    private fun japanese(id: String = "ja", priority: Int = 0, block: MemoryDictionary.() -> Unit) =
        MemoryDictionary(id, priority).apply {
            block()
            finish(DictionaryMetadata(id, "1", 3, Language.JAPANESE, "en"))
        }

    private fun MemoryDictionary.add(expression: String, reading: String, rules: String = "", score: Int = 0, gloss: String = "\"$expression\"", sequence: Long? = null) =
        term(expression, reading, null, "", rules, score, "[$gloss]", sequence, "")

    private val dict = japanese {
        add("食べる", "たべる", "v1", 200, sequence = 1)
        add("食べ物", "たべもの", score = 200)
        add("物", "もの", score = 100)
        add("食", "しょく")
        add("喰べる", "喰べる", "v1", -101, gloss = """["食べる",["redirected from 喰べる"]]""", sequence = -1)
        add("行く", "いく", "v5", 200)
        add("行く", "ゆく", "v5", 198)
        add("言う", "いう", "v5", 200)
        add("コーヒー", "コーヒー", score = 100)
        add("私", "わたし", score = 200)
        add("高い", "たかい", "adj-i", 200)
        add("読む", "よむ", "v5", 200)
        add("読み", "よみ", score = 50)
        add("する", "する", "vs", 200)
        add("勉強", "べんきょう", "", 200)
        termMeta("食べる", "freq", """{"reading":"たべる","frequency":500}""")
        termMeta("行く", "pitch", """{"reading":"ゆく","pitches":[{"position":0}]}""")
        kanji("食", "ショク", "た.べる", "", "[\"eat\"]", "{}")
        kanji("強", "キョウ", "つよ.い", "", "[\"strong\"]", "{}")
        kanji("勉", "ベン", "", "", "[\"exertion\"]", "{}")
    }

    private val engine = LookupEngine(MemoryTermSource(listOf(dict)))

    @Test
    fun longestMatchFirst() {
        val r = engine.lookup("食べ物を買う", Language.JAPANESE)
        assertEquals("食べ物", r.groups[0].expression)
        assertEquals(3, r.groups[0].sourceLength)
        assertEquals(3, r.matchLength)
        // Shorter matches follow.
        assertTrue(r.groups.any { it.expression == "食べる" })
        assertTrue(r.groups.any { it.expression == "食" })
        assertEquals("食", r.kanji.single().character)
    }

    @Test
    fun deinflectsAndValidatesWordClass() {
        val r = engine.lookup("食べさせられなかった。", Language.JAPANESE)
        val g = r.groups[0]
        assertEquals("食べる", g.expression)
        assertEquals(10, g.sourceLength)
        assertEquals(listOf("causative", "passive/potential", "negative", "past"), g.reasons)
        assertEquals(500L, (g.meta.single().data as Map<*, *>)["frequency"])

        // 勉強 is not a verb, so "勉強した" can't be the past tense of a verb "勉強す".
        val r2 = engine.lookup("勉強した", Language.JAPANESE)
        assertEquals("勉強", r2.groups[0].expression)
        assertEquals(2, r2.groups[0].sourceLength)
    }

    @Test
    fun matchesReadingsAndKatakana() {
        assertEquals("食べる", engine.lookup("たべた", Language.JAPANESE).groups[0].expression)
        assertEquals("私", engine.lookup("ワタシ", Language.JAPANESE).groups[0].expression)
        val coffee = engine.lookup("コ一ヒ一を", Language.JAPANESE).groups[0]
        assertEquals("コーヒー", coffee.expression)
        assertEquals(4, coffee.sourceLength)
    }

    @Test
    fun groupsByReadingAndSortsByScore() {
        val r = engine.lookup("行った", Language.JAPANESE)
        val groups = r.groups.filter { it.sourceLength == 3 }
        assertEquals(listOf("いく", "ゆく"), groups.filter { it.expression == "行く" }.map { it.reading })
        // Pitch data for ゆく must not be attached to いく.
        assertTrue(groups.first { it.reading == "いく" }.meta.isEmpty())
        assertEquals(1, groups.first { it.reading == "ゆく" }.meta.size)
    }

    @Test
    fun resolvesRedirects() {
        val r = engine.lookup("喰べた", Language.JAPANESE)
        val g = r.groups[0]
        assertEquals("食べる", g.expression)
        assertEquals(listOf("past", "redirected from 喰べる"), g.reasons)
        assertTrue(r.groups.none { it.expression == "喰べる" })
    }

    @Test
    fun exactMatchBeatsDeinflection() {
        val r = engine.lookup("読み", Language.JAPANESE)
        assertEquals("読み", r.groups[0].expression)
        assertEquals("読む", r.groups[1].expression)
        assertEquals(listOf("continuative"), r.groups[1].reasons)
    }

    @Test
    fun chineseLongestMatchWithoutDeinflection() {
        val zh = MemoryDictionary("zh").apply {
            term("中国", "Zhong1 guo2", "中國", "", "", 0, "[\"China\"]", 1, "")
            term("中國", "Zhong1 guo2", "中国", "", "", 0, "[\"China\"]", 1, "")
            term("中", "zhong1", null, "", "", 0, "[\"middle\"]", 2, "")
            term("人", "ren2", null, "", "", 0, "[\"person\"]", 3, "")
            term("中国人", "Zhong1 guo2 ren2", "中國人", "", "", 0, "[\"Chinese person\"]", 4, "")
            finish(DictionaryMetadata("zh", "1", 3, Language.CHINESE, "en"))
        }
        val e = LookupEngine(MemoryTermSource(listOf(dict, zh)))
        val r = e.lookup("中國人很好", Language.CHINESE)
        assertEquals("中國", r.groups[0].expression)
        assertEquals("中国", r.groups[0].alt)
        assertEquals(listOf("中國", "中"), r.groups.map { it.expression })
        val r2 = e.lookup("中国人", Language.CHINESE)
        assertEquals("中国人", r2.groups[0].expression)
        // Japanese dictionaries are not used for Chinese lookups.
        assertTrue(e.lookup("食べ物", Language.CHINESE).groups.isEmpty())
    }

    @Test
    fun emptyAndPunctuation() {
        assertTrue(engine.lookup("", Language.JAPANESE).isEmpty)
        assertTrue(engine.lookup("。食べる", Language.JAPANESE).isEmpty)
        assertTrue(engine.lookup("xyz", Language.JAPANESE).isEmpty)
    }

    @Test
    fun kanjiOfTheWordFound() {
        // Every kanji of the longest match, in order; not those after it.
        val r = engine.lookup("勉強した食", Language.JAPANESE)
        assertEquals(listOf("勉", "強"), r.kanji.map { it.character })
        // With no word found, the first character's.
        assertEquals(listOf("強"), engine.lookup("強", Language.JAPANESE).kanji.map { it.character })
        assertTrue(engine.lookup("食べ物", Language.CHINESE).kanji.isEmpty())
    }

    @Test
    fun looksUpSingleKanji() {
        val r = engine.lookupKanji("強")
        assertTrue(r.groups.isEmpty())
        assertEquals("strong", r.kanji.single().meanings.single())
        assertTrue(engine.lookupKanji("猫").isEmpty)
    }
}
