package io.github.ivoryprd.shoten.render

import android.app.Application
import android.text.Spanned
import android.view.View
import io.github.ivoryprd.shoten.Prefs
import io.github.ivoryprd.shoten.dict.importer.DictionaryMetadata
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.LookupEngine
import io.github.ivoryprd.shoten.testing.MemoryDictionary
import io.github.ivoryprd.shoten.testing.MemoryTermSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResultPresenterTest {
    private val opened = ArrayList<String>()
    private val links = object : LinkHandler {
        override fun onLookup(query: String) = Unit
        override fun onOpenUrl(url: String) = Unit
        override fun onShowNote(text: String) = Unit
        override fun onLookupKanji(character: String) {
            opened += character
        }
    }
    private val theme = RenderTheme(2f, 30f, 0xFF000000.toInt(), 0xFF666666.toInt(), 0xFF3F51B5.toInt(), 0xFF1A62D6.toInt(), 0xFFDDDDDD.toInt(), 0xFF222222.toInt(), false)
    private val presenter = ResultPresenter(theme, Prefs(RuntimeEnvironment.getApplication()), links)

    private val words = MemoryDictionary("words").apply {
        term("強い", "つよい", null, "", "adj-i", 0, "[\"strong\"]", null, "")
        finish(DictionaryMetadata("words", "1", 3, Language.JAPANESE, "en"))
    }

    /** As in KANJIDIC: dozens of stats, most of them indices of other dictionaries. */
    private val kanjidic = MemoryDictionary("KANJIDIC").apply {
        tag("jouyou", "frequent", -5, "included in list of regular-use characters", 0)
        tag("strokes", "misc", 0, "Stroke count", 0)
        tag("grade", "misc", 0, "Grade level", 0)
        tag("jlpt", "misc", 0, "JLPT level", 0)
        tag("freq", "misc", 0, "Frequency", 0)
        tag("skip", "class", 0, "SKIP code", 0)
        tag("ucs", "code", 0, "Unicode", 0)
        tag("heisig", "index", 0, "Remembering the Kanji", 0)
        kanji(
            "強", "キョウ ゴウ", "つよ.い し.いる -づよ", "jouyou", "[\"strong\"]",
            """{"strokes":"11","grade":"2","jlpt":"3","freq":"112","skip":"1-3-8","ucs":"5f37","heisig":"1235"}""",
        )
        finish(DictionaryMetadata("KANJIDIC", "1", 3, Language.JAPANESE, "en"))
    }

    private fun present(vararg dictionaries: MemoryDictionary, text: String = "強い") =
        presenter.present(LookupEngine(MemoryTermSource(dictionaries.toList())).lookup(text, Language.JAPANESE))

    @Test
    fun kanjiEntryShowsReadingsMeaningsAndKeyFacts() {
        val body = present(words, kanjidic).kanji.single().body.toString()
        assertEquals(
            """
            strong
            On  キョウ、ゴウ
            Kun  つよい、しいる、-づよ
            11 strokes  ·  Grade 2  ·  JLPT 3 (old)  ·  Frequency #112
            """.trimIndent(),
            body,
        )
        assertFalse(body.contains("1235"))
        assertFalse(body.contains("1-3-8"))
    }

    @Test
    fun okuriganaIsMuted() {
        val body = present(words, kanjidic).kanji.single().body as Spanned
        val i = body.indexOf("つよい") + 2
        assertTrue(body.getSpans(i, i + 1, android.text.style.ForegroundColorSpan::class.java).isNotEmpty())
        assertTrue(body.getSpans(i - 2, i - 1, android.text.style.ForegroundColorSpan::class.java).isEmpty())
    }

    @Test
    fun headwordKanjiOpenTheirEntries() {
        val header = present(words, kanjidic).groups.single().header as Spanned
        val spans = header.getSpans(0, header.length, ActionSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("強", header.subSequence(header.getSpanStart(spans[0]), header.getSpanEnd(spans[0])).toString())
        spans[0].onClick(View(RuntimeEnvironment.getApplication()))
        assertEquals(listOf("強"), opened)

        // Without a kanji dictionary they are plain text.
        val plain = present(words).groups.single().header as Spanned
        assertTrue(plain.getSpans(0, plain.length, ActionSpan::class.java).isEmpty())
    }
}
