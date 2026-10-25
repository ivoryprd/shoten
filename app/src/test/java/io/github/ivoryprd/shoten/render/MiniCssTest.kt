package io.github.ivoryprd.shoten.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniCssTest {
    private val css = """
        /* comment { not a rule } */
        span[title] { cursor: help; }
        ul[data-sc-content="sense-groups"] { list-style-type: "＊"; }
        li[data-sc-content="sense"] {
            padding-left: 0.25em;
            & ul[data-sc-content="glossary"] { list-style-type: none; }
        }
        span[data-sc-class="tag"] { font-size: 0.8em; font-weight: bold; background-color: gray; }
        span[data-sc-content="part-of-speech-info"] { background-color: #565656; color: white; }
        td[data-sc-class="form-pri"] > span { color: white; }
        div[data-sc-content="xref"] { border-color: #1A73E8; background-color: color-mix(in srgb, #1A73E8 5%, transparent); }
        @media (prefers-color-scheme: dark) { span[title] { color: red; } }
        div[data-sc-content="attribution"], span[data-sc-content='x'] { font-size: 0.7em; text-align: right !important; }
    """.trimIndent()

    private val sheet = StyleSheet.parse(css)

    @Test
    fun matchesCompoundSelectorsInCascadeOrder() {
        val tag = sheet.compute("span", mapOf("data-sc-class" to "tag", "data-sc-content" to "part-of-speech-info", "title" to "noun"))
        assertEquals("#565656", tag["background-color"])
        assertEquals("white", tag["color"])
        assertEquals("0.8em", tag["font-size"])
        assertEquals("help", tag["cursor"])
        assertEquals("\"＊\"", sheet.compute("ul", mapOf("data-sc-content" to "sense-groups"))["list-style-type"])
    }

    @Test
    fun ignoresNestedRulesCombinatorsAndAtRules() {
        val sense = sheet.compute("li", mapOf("data-sc-content" to "sense"))
        assertEquals("0.25em", sense["padding-left"])
        assertNull(sense["list-style-type"])
        assertTrue(sheet.compute("ul", mapOf("data-sc-content" to "glossary")).isEmpty())
        assertTrue(sheet.compute("td", mapOf("data-sc-class" to "form-pri")).isEmpty())
        assertNull(sheet.compute("span", mapOf("title" to "x"))["color"])
    }

    @Test
    fun selectorListsAndImportant() {
        assertEquals("right", sheet.compute("div", mapOf("data-sc-content" to "attribution"))["text-align"])
        assertEquals("0.7em", sheet.compute("span", mapOf("data-sc-content" to "x"))["font-size"])
        assertTrue(sheet.compute("div", mapOf("data-sc-content" to "other")).isEmpty())
    }

    @Test
    fun parsesColors() {
        assertEquals(0xFF565656.toInt(), CssColors.parse("#565656"))
        assertEquals(0xFFFF0000.toInt(), CssColors.parse("#f00"))
        assertEquals(0x80FF0000.toInt(), CssColors.parse("#ff000080"))
        assertEquals(0xFFA52A2A.toInt(), CssColors.parse("brown"))
        assertEquals(0xFF1A73E8.toInt(), CssColors.parse("#1A73E8"))
        assertEquals(0x7F0080FF, CssColors.parse("rgba(0, 128, 255, 0.5)"))
        assertEquals(0, CssColors.parse("transparent"))
        assertNull(CssColors.parse("var(--text-color)"))
        assertNull(CssColors.parse("color-mix(in srgb, red 5%, transparent)"))
        assertNull(CssColors.parse("#12345"))
    }

    @Test
    fun handlesEmptyAndBrokenCss() {
        assertTrue(StyleSheet.parse(null).isEmpty)
        assertTrue(StyleSheet.parse("").isEmpty)
        assertTrue(StyleSheet.parse("span[title { color: red").isEmpty)
        assertTrue(StyleSheet.parse("}}}{{{").isEmpty)
    }
}
