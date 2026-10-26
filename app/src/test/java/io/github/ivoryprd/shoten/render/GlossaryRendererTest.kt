package io.github.ivoryprd.shoten.render

import android.app.Application
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import io.github.ivoryprd.shoten.testing.MemoryDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GlossaryRendererTest {
    private val links = object : LinkHandler {
        override fun onLookup(query: String) = Unit
        override fun onOpenUrl(url: String) = Unit
        override fun onShowNote(text: String) = Unit
    }
    private val accent = 0xFF3F51B5.toInt()
    private val theme = RenderTheme(2f, 30f, 0xFF000000.toInt(), 0xFF666666.toInt(), accent, 0xFF1A62D6.toInt(), 0xFFDDDDDD.toInt(), 0xFF222222.toInt(), false)

    private fun render(glossaryJson: String, styles: String? = null): Spanned {
        val dict = MemoryDictionary("test").apply { this.styles = styles }
        dict.term("見る", "みる", null, "", "v1", 0, glossaryJson, null, "")
        val blocks = GlossaryRenderer(theme, links).render(dict.terms.single())
        return (blocks.single() as GlossBlock.Text).text as Spanned
    }

    /** Jitendex's structure: groups of senses, each sense a list of glosses and an example. */
    private val jitendex = """
        [{"type": "structured-content", "content": {"tag": "ul", "data": {"content": "sense-groups"}, "content": [
          {"tag": "li", "data": {"content": "sense-group"}, "content": [
            {"tag": "span", "data": {"class": "tag", "content": "part-of-speech-info"}, "content": "1-dan"},
            {"tag": "ol", "content": [
              {"tag": "li", "style": {"listStyleType": "\"①\""}, "data": {"content": "sense"}, "content": [
                {"tag": "ul", "data": {"content": "glossary"}, "content": [{"tag": "li", "content": "to see"}, {"tag": "li", "content": "to look"}]},
                {"tag": "div", "data": {"class": "extra-box", "content": "example-sentence"}, "content": [
                  {"tag": "div", "data": {"content": "example-sentence-a"}, "content": ["本を", {"tag": "span", "data": {"content": "example-keyword"}, "content": "見る"}]},
                  {"tag": "div", "data": {"content": "example-sentence-b"}, "content": "I read a book."}
                ]}
              ]},
              {"tag": "li", "style": {"listStyleType": "\"②\""}, "data": {"content": "sense"}, "content": [
                {"tag": "ul", "data": {"content": "glossary"}, "content": [{"tag": "li", "content": "to examine"}]}
              ]}
            ]}
          ]},
          {"tag": "li", "data": {"content": "sense-group"}, "content": [
            {"tag": "ol", "content": [
              {"tag": "li", "style": {"listStyleType": "\"③\""}, "data": {"content": "sense"}, "content": "to try"}
            ]}
          ]}
        ]}}]
    """.trimIndent()

    private val jitendexStyles = """
        ul[data-sc-content="sense-groups"] { list-style-type: "＊"; }
        div[data-sc-class="extra-box"] { border-style: none none none solid; margin-top: 0.5rem; margin-bottom: 0.5rem; }
        div[data-sc-content="example-sentence"] { border-color: var(--text-color, #333); }
    """.trimIndent()

    @Test
    fun glossesOfASenseShareALine() {
        val text = render(jitendex, jitendexStyles)
        val lines = text.toString().split('\n')
        assertTrue(lines.toString(), "to see; to look" in lines)
        assertTrue(lines.toString(), "to examine" in lines)
        assertTrue(lines.toString(), "to try" in lines)
    }

    @Test
    fun sensesAreNumberedInOrder() {
        val text = render(jitendex, jitendexStyles)
        val markers = text.getSpans(0, text.length, NumberMarkerSpan::class.java).sortedBy { text.getSpanStart(it) }
        assertEquals(listOf("to see", "to examine", "to try"), markers.map { text.substring(text.getSpanStart(it)).substringBefore(';').substringBefore('\n') })
        // No "＊" markers for groups of senses.
        assertTrue(text.getSpans(0, text.length, MarkerSpan::class.java).isEmpty())
    }

    @Test
    fun outerParagraphSpansComeFirst() {
        val text = render(jitendex, jitendexStyles)
        // The example's bar is inside the sense; the sense's number must be laid out (indent) first.
        val example = text.indexOf("本を")
        val spans = text.getSpans(example, example + 1, android.text.style.LeadingMarginSpan::class.java)
        assertEquals(2, spans.size)
        assertTrue(spans[0] is NumberMarkerSpan)
        assertTrue(spans[1] is BarSpan)
    }

    @Test
    fun sensesAndGroupsAreSpacedApart() {
        val text = render(jitendex, jitendexStyles)
        fun spaceBefore(s: String): Int {
            val at = text.indexOf(s)
            return text.getSpans(at - 1, at, SpaceBelowSpan::class.java).filter { text.getSpanEnd(it) == at }.maxOfOrNull { it.space } ?: 0
        }
        // The example box's margins are 0.5rem (15 px); like in CSS, adjacent margins collapse.
        assertEquals(15, spaceBefore("本を"))
        assertEquals(maxOf(15, (6 * theme.density).toInt()), spaceBefore("to examine"))
        assertEquals((12 * theme.density).toInt(), spaceBefore("to try"))
    }

    @Test
    fun exampleKeywordUsesTheAccentColor() {
        val text = render(jitendex, jitendexStyles)
        val at = text.indexOf("本を見る") + 2
        val colors = text.getSpans(at, at + 1, ForegroundColorSpan::class.java)
        assertEquals(accent, colors.last().foregroundColor)
    }

    @Test
    fun innerColorsWin() {
        val text = render("""[{"type": "structured-content", "content": {"tag": "span", "style": {"color": "red"}, "content": ["a", {"tag": "span", "style": {"color": "blue"}, "content": "b"}]}}]""")
        val at = text.indexOf('b')
        // Spans are applied in order, so the last one decides the color.
        assertEquals(0xFF0000FF.toInt(), text.getSpans(at, at + 1, ForegroundColorSpan::class.java).last().foregroundColor)
    }

    @Test
    fun plainGlossariesAreNumbered() {
        val text = render("""["to see", "to look"]""")
        assertEquals("to see\nto look", text.toString())
        assertEquals(2, text.getSpans(0, text.length, NumberMarkerSpan::class.java).size)
    }

    @Test
    fun numbersOfMarkers() {
        assertEquals(3, GlossaryRenderer.numberOf("③"))
        assertEquals(21, GlossaryRenderer.numberOf("㉑"))
        assertEquals(12, GlossaryRenderer.numberOf("12."))
        assertNull(GlossaryRenderer.numberOf("•"))
        assertNull(GlossaryRenderer.numberOf("a."))
        assertNull(GlossaryRenderer.numberOf(""))
    }
}
