package io.github.ivoryprd.shoten.dict

import io.github.ivoryprd.shoten.dict.importer.CedictImporter
import io.github.ivoryprd.shoten.dict.importer.ImportException
import io.github.ivoryprd.shoten.dict.importer.YomitanImporter
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.testing.MemoryDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, String>): File {
        val file = tmp.newFile("dict.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun importsYomitanV3() {
        val file = zip(
            "index.json" to """{"title":"Test","revision":"r1","format":3,"sourceLanguage":"ja","targetLanguage":"en","isUpdatable":true,"indexUrl":"https://x/i.json","downloadUrl":"https://x/d.zip"}""",
            "term_bank_1.json" to """[["食べる","たべる","★","v1",200,[{"type":"structured-content","content":{"tag":"div","content":"to eat"}}],1358280,""],["猫","",null,"",0,["cat"],5,"common"]]""",
            "term_bank_2.json" to """[["喰べる","","rare","v1",-101,[["食べる",["redirected from 喰べる"]]],-1358280,""]]""",
            "term_meta_bank_1.json" to """[["食べる","freq",{"reading":"たべる","frequency":{"value":500,"displayValue":"500㋕"}}],["猫","pitch",{"reading":"ねこ","pitches":[{"position":1}]}]]""",
            "kanji_bank_1.json" to """[["食","ショク ジキ","く.う た.べる","jouyou",["eat","food"],{"grade":"2","strokes":"9"}]]""",
            "tag_bank_1.json" to """[["★","popular",2,"high priority entry",2]]""",
            "styles.css" to "span[data-sc-content=\"x\"] { color: red; }",
            "img/a.png" to "PNGDATA",
            "readme.txt" to "ignored",
        )
        val dict = MemoryDictionary("d")
        val meta = YomitanImporter.import(file, dict)
        assertEquals("Test", meta.title)
        assertEquals(Language.JAPANESE, meta.sourceLanguage)
        assertTrue(meta.isUpdatable)
        assertEquals("https://x/d.zip", meta.downloadUrl)
        assertEquals(3, dict.terms.size)
        val taberu = dict.terms[0]
        assertEquals("食べる", taberu.expression)
        assertEquals("たべる", taberu.reading)
        assertEquals("v1", taberu.rules)
        assertEquals(200, taberu.score)
        assertEquals(1358280L, taberu.sequence)
        assertEquals("structured-content", (taberu.glossary[0] as Map<*, *>)["type"])
        val neko = dict.terms[1]
        assertEquals("猫", neko.reading) // empty reading means same as the expression
        assertEquals("", neko.definitionTags)
        assertEquals("common", neko.termTags)
        assertEquals(listOf("cat"), neko.glossary)
        assertEquals(2, dict.termMeta.size)
        assertEquals("ショク ジキ", dict.kanji[0].onyomi)
        assertEquals(listOf("eat", "food"), dict.kanji[0].meanings)
        assertEquals("9", dict.kanji[0].stats["strokes"])
        assertEquals("popular", dict.tags["★"]!!.category)
        assertNotNull(dict.styles)
        assertEquals(setOf("img/a.png"), dict.media.keys)
    }

    @Test
    fun importsYomitanV1InSubfolder() {
        val file = zip(
            "dict/index.json" to """{"title":"Old","revision":"x","version":1,"tagMeta":{"n":{"category":"partOfSpeech","order":1,"notes":"noun"}}}""",
            "dict/term_bank_1.json" to """[["本","ほん","n","",5,"book","origin"]]""",
            "dict/kanji_bank_1.json" to """[["本","ホン","もと","",  "book", "origin"]]""",
        )
        val dict = MemoryDictionary("d")
        val meta = YomitanImporter.import(file, dict)
        assertEquals(1, meta.format)
        assertEquals(listOf("book", "origin"), dict.terms[0].glossary)
        assertNull(dict.terms[0].sequence)
        assertEquals(listOf("book", "origin"), dict.kanji[0].meanings)
        assertEquals("noun", dict.tags["n"]!!.notes)
        // No declared language, but the headwords contain kana/kanji.
        assertTrue(meta.sourceLanguage == Language.CHINESE || meta.sourceLanguage == Language.JAPANESE)
    }

    @Test
    fun kanjiDictionariesAreJapanese() {
        // As KANJIDIC: kanji only, no language declared.
        val file = zip(
            "index.json" to """{"title":"KANJIDIC [2026-310]","revision":"kanjidic2.2026-310","format":3}""",
            "kanji_bank_1.json" to """[["強","キョウ ゴウ","つよ.い","jouyou",["strong"],{"strokes":"11"}]]""",
        )
        val dict = MemoryDictionary("d")
        assertEquals(Language.JAPANESE, YomitanImporter.import(file, dict).sourceLanguage)
        assertEquals("強", dict.kanji.single().character)
    }

    @Test
    fun rejectsInvalidZips() {
        for (files in listOf(
            arrayOf("readme.txt" to "x"),
            arrayOf("index.json" to "{\"title\":\"T\",\"revision\":\"1\",\"format\":3}"),
            arrayOf("index.json" to "{\"revision\":\"1\",\"format\":3}", "term_bank_1.json" to "[]"),
            arrayOf("index.json" to "{\"title\":\"T\",\"format\":3}", "term_bank_1.json" to "[[\"a\""),
        )) {
            tmp.root.resolve("dict.zip").delete()
            try {
                YomitanImporter.import(zip(*files), MemoryDictionary("d"))
                fail("Expected import of ${files.toList()} to fail")
            } catch (e: ImportException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun parsesCedictLines() {
        val e = CedictImporter.parseLine("傳統 传统 [chuan2 tong3] /tradition/traditional/convention/")!!
        assertEquals("傳統", e.traditional)
        assertEquals("传统", e.simplified)
        assertEquals("chuan2 tong3", e.pinyin)
        assertEquals(listOf("tradition", "traditional", "convention"), e.definitions)
        assertNull(CedictImporter.parseLine("# comment"))
        assertNull(CedictImporter.parseLine("garbage"))
        val withBrackets = CedictImporter.parseLine("個 个 [ge4] /individual/CL:個|个[ge4]/")!!
        assertEquals(listOf("individual", "CL:個|个[ge4]"), withBrackets.definitions)
    }

    private val cedictText = """
        # CC-CEDICT
        #! version=1
        #! date=2026-10-25T09:04:00Z
        中文 中文 [Zhong1 wen2] /Chinese language/
        傳統 传统 [chuan2 tong3] /tradition/traditional/
        了 了 [le5] /(modal particle)/
        了 了 [liao3] /to finish/
    """.trimIndent() + "\n"

    @Test
    fun importsCedictInAllContainers() {
        val plain = tmp.newFile("cedict.u8").apply { writeText(cedictText) }
        val gz = tmp.newFile("cedict.txt.gz").apply { GZIPOutputStream(outputStream()).use { it.write(cedictText.toByteArray()) } }
        val zipped = tmp.newFile("cedict.zip").apply {
            ZipOutputStream(outputStream()).use { out ->
                out.putNextEntry(ZipEntry("cedict_ts.u8"))
                out.write(cedictText.toByteArray())
                out.closeEntry()
            }
        }
        for (file in listOf(plain, gz, zipped)) {
            assertTrue(CedictImporter.looksLikeCedict(file))
            val dict = MemoryDictionary("c")
            val meta = CedictImporter.import(file, dict)
            assertEquals("2026-10-25", meta.revision)
            assertEquals(Language.CHINESE, meta.sourceLanguage)
            assertEquals("pinyin-numbered", meta.readingFormat)
            // 传统/傳統 is stored under both forms.
            assertEquals(5, dict.terms.size)
            val simplified = dict.terms.first { it.expression == "传统" }
            assertEquals("傳統", simplified.alt)
            val traditional = dict.terms.first { it.expression == "傳統" }
            assertEquals("传统", traditional.alt)
            assertEquals(listOf("tradition", "traditional"), traditional.glossary)
            assertNull(dict.terms.first { it.expression == "中文" }.alt)
        }
        val notCedict = tmp.newFile("other.txt").apply { writeText("hello\nworld\n") }
        assertTrue(!CedictImporter.looksLikeCedict(notCedict))
    }

    @Test
    fun codecRoundTrips() {
        val samples = (0 until 500).map { i ->
            """[{"type":"structured-content","content":[{"tag":"span","data":{"class":"tag","content":"part-of-speech-info"},"content":"noun"},{"tag":"ul","content":{"tag":"li","content":"meaning $i"}}]}]"""
        }
        val dict = GlossaryCodec.trainDictionary(samples)
        assertNotNull(dict)
        assertTrue(dict!!.size <= GlossaryCodec.MAX_DICTIONARY_SIZE)
        val codec = GlossaryCodec(dict)
        val encoder = codec.Encoder()
        var raw = 0
        var encoded = 0
        for (s in samples + listOf("[]", "[\"短\"]", "[\"" + "長い説明".repeat(2000) + "\"]")) {
            val bytes = encoder.encode(s)
            raw += s.toByteArray().size
            encoded += bytes.size
            assertEquals(s, codec.decode(bytes))
        }
        encoder.close()
        assertTrue("compression ratio $encoded/$raw", encoded * 3 < raw)
        // Without a preset dictionary.
        val plain = GlossaryCodec(null)
        val e2 = plain.Encoder()
        val text = "[\"" + "abc".repeat(100) + "\"]"
        val bytes = e2.encode(text)
        assertEquals(GlossaryCodec.DEFLATE, bytes[0])
        assertEquals(text, plain.decode(bytes))
        e2.close()
    }
}
