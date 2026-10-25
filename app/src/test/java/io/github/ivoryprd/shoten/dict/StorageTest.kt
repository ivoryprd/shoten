package io.github.ivoryprd.shoten.dict

import android.app.Application
import android.text.Spanned
import io.github.ivoryprd.shoten.dict.importer.ImportException
import io.github.ivoryprd.shoten.dict.importer.ImportProgress
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.LookupEngine
import io.github.ivoryprd.shoten.render.ChipSpan
import io.github.ivoryprd.shoten.render.GlossBlock
import io.github.ivoryprd.shoten.render.GlossaryRenderer
import io.github.ivoryprd.shoten.render.LinkHandler
import io.github.ivoryprd.shoten.render.RenderTheme
import io.github.ivoryprd.shoten.render.RubySpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises the SQLite storage, installer and renderer on the Android framework (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val links = object : LinkHandler {
        override fun onLookup(query: String) = Unit
        override fun onOpenUrl(url: String) = Unit
        override fun onShowNote(text: String) = Unit
    }
    private val theme = RenderTheme(2f, 30f, 0xFF000000.toInt(), 0xFF666666.toInt(), 0xFF3F51B5.toInt(), 0xFF1A62D6.toInt(), 0xFFDDDDDD.toInt(), 0xFF222222.toInt(), false)

    private fun zip(name: String, vararg files: Pair<String, String>): File {
        val file = File(tmp.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((entry, content) in files) {
                out.putNextEntry(ZipEntry(entry))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun sampleDictionary(revision: String) = zip(
        "sample-$revision.zip",
        "index.json" to """{"title":"Sample","revision":"$revision","format":3,"sourceLanguage":"ja","indexUrl":"https://example.com/index.json","isUpdatable":true}""",
        "term_bank_1.json" to (1..3000).joinToString(",", "[", "]") { i ->
            """["語$i","ご$i","","",0,[{"type":"structured-content","content":{"tag":"ul","content":[{"tag":"li","content":"word number $i"}]}}],$i,""]"""
        },
        "term_bank_2.json" to """[["食べる","たべる","★","v1",200,[{"type":"structured-content","content":[{"tag":"span","data":{"class":"tag"},"title":"Ichidan verb","content":"v1"},{"tag":"div","content":["to eat ",{"tag":"ruby","content":["食",{"tag":"rt","content":"た"}]}]}]}],9000,""]]""",
        "term_meta_bank_1.json" to """[["食べる","freq",{"reading":"たべる","frequency":{"value":120,"displayValue":"120"}}]]""",
        "kanji_bank_1.json" to """[["食","ショク","た.べる","",["eat","food"],{"strokes":"9"}]]""",
        "tag_bank_1.json" to """[["★","popular",2,"common",2]]""",
        "styles.css" to """span[data-sc-class="tag"] { background-color: #565656; color: white; }""",
        "img/x.png" to "not really a png",
    )

    @Test
    fun installLookupUpdateDelete() {
        val dir = File(tmp.root, "dicts")
        val repo = DictionaryRepository(dir)
        val installer = DictionaryInstaller(repo)
        val installed = installer.install(sampleDictionary("1"), ImportProgress.None)
        assertEquals("Sample", installed.title)
        assertEquals(3001, installed.terms)
        assertEquals(1, installed.kanji)
        assertEquals(Language.JAPANESE, installed.language)
        assertTrue(repo.hasDictionaries(Language.JAPANESE))
        assertFalse(repo.hasDictionaries(Language.CHINESE))

        val engine = LookupEngine(repo.termSource)
        val result = engine.lookup("食べられない", Language.JAPANESE)
        val group = result.groups.first()
        assertEquals("食べる", group.expression)
        assertEquals(listOf("passive/potential", "negative"), group.reasons)
        assertEquals("freq", group.meta.single().mode)
        assertEquals("食", result.kanji.single().character)
        assertEquals("9", result.kanji.single().stats["strokes"])
        val db = repo.database(installed.id)!!
        assertEquals("popular", db.tag("★")!!.category)
        assertNotNull(db.media("img/x.png"))
        // Glossaries survive compression.
        val word = engine.lookup("語2999", Language.JAPANESE).groups.first()
        assertEquals("語2999", word.expression)
        assertTrue(word.entries.single().row.glossary.toString().contains("word number 2999"))

        // Rendering: chip for the styled tag, ruby inside the definition.
        val blocks = GlossaryRenderer(theme, links).render(group.entries.single().row)
        val text = (blocks.single() as GlossBlock.Text).text as Spanned
        assertTrue(text.toString().contains("to eat 食"))
        assertEquals(1, text.getSpans(0, text.length, ChipSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, RubySpan::class.java).size)

        // Same revision again is rejected; a new revision replaces the old one in place.
        try {
            installer.install(sampleDictionary("1"), ImportProgress.None)
            fail("Duplicate import should fail")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("already installed"))
        }
        repo.setEnabled(installed.id, false)
        val updated = installer.install(sampleDictionary("2"), ImportProgress.None)
        assertEquals(1, repo.installed.size)
        assertEquals("2", repo.installed.single().revision)
        assertFalse("the update keeps the enabled state", repo.installed.single().enabled)
        assertTrue(engine.lookup("食べる", Language.JAPANESE).groups.isEmpty())
        repo.setEnabled(updated.id, true)
        assertEquals("食べる", engine.lookup("食べる", Language.JAPANESE).groups.first().expression)

        // The registry is persisted.
        val reopened = DictionaryRepository(dir)
        assertEquals(listOf(updated.id), reopened.installed.map { it.id })

        repo.delete(updated.id)
        assertTrue(repo.installed.isEmpty())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".db") })
    }

    @Test
    fun failedImportLeavesNothingBehind() {
        val dir = File(tmp.root, "dicts")
        val repo = DictionaryRepository(dir)
        val bad = zip("bad.zip", "index.json" to """{"title":"Bad","revision":"1","format":3}""", "term_bank_1.json" to """[["a","a","","",0,["x"],1,""],""")
        try {
            DictionaryInstaller(repo).install(bad, ImportProgress.None)
            fail("Import should fail")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("term_bank_1.json"))
        }
        assertTrue(repo.installed.isEmpty())
        assertTrue(dir.listFiles()!!.none { it.name != "registry.json" })
    }

    @Test
    fun realDictionaries() {
        val data = System.getenv("SHOTEN_TEST_DATA")?.let(::File)
        assumeTrue(data != null && File(data, "jitendex-yomitan.zip").exists())
        val dir = File(tmp.root, "dicts")
        val repo = DictionaryRepository(dir)
        val installer = DictionaryInstaller(repo)
        val t0 = System.nanoTime()
        val jitendex = installer.install(File(data, "jitendex-yomitan.zip"), ImportProgress.None)
        val t1 = System.nanoTime()
        val dbFile = File(dir, jitendex.id + ".db")
        println("Jitendex: ${jitendex.terms} terms imported in ${(t1 - t0) / 1_000_000} ms, database ${dbFile.length() / 1_000_000} MB")
        assertTrue(dbFile.length() < 200_000_000)
        val cedictFile = File(data, "cedict.zip")
        if (cedictFile.exists()) {
            val cedict = installer.install(cedictFile, ImportProgress.None)
            println("CC-CEDICT: ${cedict.terms} rows, revision ${cedict.revision}, database ${File(dir, cedict.id + ".db").length() / 1_000_000} MB")
            assertEquals(Language.CHINESE, cedict.language)
        }

        val engine = LookupEngine(repo.termSource)
        val cases = mapOf(
            "食べさせられなかった" to "食べる",
            "読んでいます" to "読む",
            "喰べた" to "食べる",
            "コ一ヒ一を飲む" to "コーヒー",
            "美味しかった" to "美味しい",
        )
        for ((text, expected) in cases) {
            val start = System.nanoTime()
            val r = engine.lookup(text, Language.JAPANESE)
            println("lookup $text: ${(System.nanoTime() - start) / 1_000_000} ms -> ${r.groups.firstOrNull()?.expression}")
            assertEquals(text, expected, r.groups.first().expression)
        }
        if (cedictFile.exists()) {
            val r = engine.lookup("图书馆在哪里", Language.CHINESE)
            assertEquals("图书馆", r.groups.first().expression)
            assertEquals("圖書館", r.groups.first().alt)
        }

        // Render a large sample of real entries to make sure nothing in Jitendex breaks the renderer.
        val renderer = GlossaryRenderer(theme, links)
        val db = repo.database(jitendex.id)!!
        val sample = db.findTerms(listOf("する", "行く", "馬酔木", "見る", "ない", "Ｎ響", "為る", "かわいい", "取る", "上がる"), true)
        assertTrue(sample.size > 10)
        var blocks = 0
        var tables = 0
        var images = 0
        for (row in sample) {
            for (block in renderer.render(row)) {
                blocks++
                if (block is GlossBlock.Table) tables++
                if (block is GlossBlock.Image) images++
                if (block is GlossBlock.Text) assertFalse(block.text.isBlank())
            }
        }
        assertTrue(tables > 0)
        assertTrue(images > 0)
        println("Rendered ${sample.size} entries into $blocks blocks ($tables tables, $images images)")
    }

    @Test
    fun renderEveryTenthJitendexEntry() {
        val data = System.getenv("SHOTEN_TEST_DATA")?.let(::File)
        assumeTrue(data != null && File(data, "jitendex-yomitan.zip").exists())
        val dict = io.github.ivoryprd.shoten.testing.MemoryDictionary("jitendex")
        io.github.ivoryprd.shoten.dict.importer.YomitanImporter.import(File(data, "jitendex-yomitan.zip"), dict)
        val renderer = GlossaryRenderer(theme, links)
        var rendered = 0
        for (i in dict.terms.indices step 10) {
            val blocks = renderer.render(dict.terms[i])
            assertTrue("entry ${dict.terms[i].expression} rendered nothing", blocks.isNotEmpty())
            rendered++
        }
        println("Rendered $rendered Jitendex entries")
    }
}
