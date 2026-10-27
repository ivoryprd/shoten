package io.github.ivoryprd.shoten.ocr

import io.github.ivoryprd.shoten.lookup.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SimilarCharsTest {
    /** The tables shipped with the app. */
    private val similar = SimilarChars { File("src/main/assets", it).inputStream() }

    @Test
    fun readsTables() {
        val table = SimilarChars.parse("# A comment\nま\tよほ\n\nは\tばぱ\n".byteInputStream())
        assertEquals(mapOf("ま" to "よほ", "は" to "ばぱ"), table)
    }

    @Test
    fun japaneseLookAlikes() {
        fun assertSimilar(char: String, vararg others: String) {
            val list = similar.of(char, Language.JAPANESE)
            for (o in others) assertTrue("$o for $char: $list", o in list)
        }
        // Common misreads, and the hand-made groups.
        assertSimilar("ま", "よ")
        assertSimilar("れ", "わ", "ね")
        assertSimilar("未", "末")
        assertSimilar("土", "士")
        assertSimilar("己", "已", "巳")
        assertSimilar("シ", "ツ", "ン")
        assertSimilar("つ", "っ")
        assertSimilar("は", "ば", "ぱ")
        assertSimilar("ロ", "口")
        assertSimilar("ー", "一")
        assertSimilar("。", ".")
        // Look-alikes both ways.
        assertSimilar("末", "未")
        assertSimilar("よ", "ま")
        // Not itself, and nothing for what isn't in the table.
        assertFalse("ま" in similar.of("ま", Language.JAPANESE))
        assertTrue(similar.of("x", Language.JAPANESE).isEmpty())
    }

    @Test
    fun chineseLookAlikes() {
        assertTrue("末" in similar.of("未", Language.CHINESE))
        assertTrue("曰" in similar.of("日", Language.CHINESE))
        assertTrue("入" in similar.of("人", Language.CHINESE))
        // Simplified and traditional characters.
        assertTrue(similar.of("这", Language.CHINESE).isNotEmpty())
        assertTrue(similar.of("們", Language.CHINESE).isNotEmpty())
    }

    @Test
    fun tablesAreWellFormed() {
        for (path in listOf("similar/ja.txt", "similar/zh.txt")) {
            val table = File("src/main/assets", path).inputStream().use(SimilarChars::parse)
            assertTrue(path, table.size > 2000)
            for ((char, list) in table) {
                assertEquals("$path: $char", 1, char.codePointCount(0, char.length))
                assertTrue("$path: $char", list.isNotEmpty() && char !in list.codePoints().toArray().map { String(Character.toChars(it)) })
            }
        }
    }

    @Test
    fun commonCharacters() {
        val ja = similar.characters(Language.JAPANESE)
        assertTrue("あ" in ja && "ア" in ja && "日" in ja && "語" in ja)
        // Only kana and kanji: no punctuation or Latin letters.
        assertFalse("。" in ja || "." in ja || "o" in ja)
        assertTrue(ja.size in 3000..3400)
        val zh = similar.characters(Language.CHINESE)
        assertTrue("这" in zh && "這" in zh && "日" in zh)
        assertFalse("あ" in zh)
    }
}
