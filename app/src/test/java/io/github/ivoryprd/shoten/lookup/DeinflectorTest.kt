package io.github.ivoryprd.shoten.lookup

import io.github.ivoryprd.shoten.lookup.Deinflector.ADJ_I
import io.github.ivoryprd.shoten.lookup.Deinflector.V1
import io.github.ivoryprd.shoten.lookup.Deinflector.V5
import io.github.ivoryprd.shoten.lookup.Deinflector.VK
import io.github.ivoryprd.shoten.lookup.Deinflector.VS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeinflectorTest {
    private fun find(text: String, base: String, cls: Int): Deinflection? =
        Deinflector.deinflect(text).firstOrNull { it.text == base && it.conditions and cls != 0 }

    private fun assertDeinflects(text: String, base: String, cls: Int, reasons: List<String>? = null) {
        val d = find(text, base, cls)
        assertNotNull("$text should deinflect to $base; got ${Deinflector.deinflect(text).map { it.text + it.reasons }}", d)
        if (reasons != null) assertEquals("reasons for $text", reasons, d!!.reasons)
    }

    @Test
    fun originalTextComesFirst() {
        val results = Deinflector.deinflect("食べる")
        assertEquals(Deinflection("食べる", 0, emptyList()), results.first())
    }

    @Test
    fun ichidan() {
        assertDeinflects("食べた", "食べる", V1, listOf("past"))
        assertDeinflects("食べて", "食べる", V1, listOf("-te"))
        assertDeinflects("食べない", "食べる", V1, listOf("negative"))
        assertDeinflects("食べなかった", "食べる", V1, listOf("negative", "past"))
        assertDeinflects("食べます", "食べる", V1, listOf("polite"))
        assertDeinflects("食べました", "食べる", V1, listOf("polite past"))
        assertDeinflects("食べませんでした", "食べる", V1, listOf("polite past negative"))
        assertDeinflects("食べたい", "食べる", V1, listOf("-tai"))
        assertDeinflects("食べたくなかった", "食べる", V1, listOf("-tai", "negative", "past"))
        assertDeinflects("食べられる", "食べる", V1, listOf("passive/potential"))
        assertDeinflects("食べさせる", "食べる", V1, listOf("causative"))
        assertDeinflects("食べさせられなかった", "食べる", V1, listOf("causative", "passive/potential", "negative", "past"))
        assertDeinflects("食べている", "食べる", V1, listOf("progressive"))
        assertDeinflects("食べてる", "食べる", V1, listOf("progressive"))
        assertDeinflects("食べていた", "食べる", V1, listOf("progressive", "past"))
        assertDeinflects("食べています", "食べる", V1, listOf("progressive", "polite"))
        assertDeinflects("食べちゃった", "食べる", V1, listOf("-te shimau", "past"))
        assertDeinflects("食べてしまった", "食べる", V1, listOf("-te shimau", "past"))
        assertDeinflects("食べれば", "食べる", V1, listOf("-ba"))
        assertDeinflects("食べよう", "食べる", V1, listOf("volitional"))
        assertDeinflects("食べろ", "食べる", V1, listOf("imperative"))
        assertDeinflects("食べず", "食べる", V1, listOf("-zu"))
        assertDeinflects("食べなければ", "食べる", V1, listOf("negative", "-ba"))
        assertDeinflects("食べなきゃ", "食べる", V1, listOf("-nakya"))
        assertDeinflects("食べたら", "食べる", V1, listOf("-tara"))
        assertDeinflects("食べてください", "食べる", V1, listOf("-te kudasai"))
        assertDeinflects("食べれる", "食べる", V1, listOf("potential"))
        assertDeinflects("食べ", "食べる", V1, listOf("continuative"))
    }

    @Test
    fun godan() {
        assertDeinflects("書いた", "書く", V5, listOf("past"))
        assertDeinflects("泳いだ", "泳ぐ", V5, listOf("past"))
        assertDeinflects("話した", "話す", V5, listOf("past"))
        assertDeinflects("待った", "待つ", V5, listOf("past"))
        assertDeinflects("買った", "買う", V5, listOf("past"))
        assertDeinflects("帰った", "帰る", V5, listOf("past"))
        assertDeinflects("死んだ", "死ぬ", V5, listOf("past"))
        assertDeinflects("遊んだ", "遊ぶ", V5, listOf("past"))
        assertDeinflects("読んで", "読む", V5, listOf("-te"))
        assertDeinflects("行った", "行く", V5, listOf("past"))
        assertDeinflects("行って", "行く", V5, listOf("-te"))
        assertDeinflects("書かない", "書く", V5, listOf("negative"))
        assertDeinflects("買わない", "買う", V5, listOf("negative"))
        assertDeinflects("書きます", "書く", V5, listOf("polite"))
        assertDeinflects("書ける", "書く", V5, listOf("potential"))
        assertDeinflects("書かれる", "書く", V5, listOf("passive"))
        assertDeinflects("書かせる", "書く", V5, listOf("causative"))
        assertDeinflects("書かされる", "書く", V5, listOf("causative passive"))
        assertDeinflects("書こう", "書く", V5, listOf("volitional"))
        assertDeinflects("書け", "書く", V5, listOf("imperative"))
        assertDeinflects("書けば", "書く", V5, listOf("-ba"))
        assertDeinflects("読み", "読む", V5, listOf("continuative"))
        assertDeinflects("読みながら", "読む", V5, listOf("-nagara"))
        assertDeinflects("読んでいる", "読む", V5, listOf("progressive"))
        assertDeinflects("読んでる", "読む", V5, listOf("progressive"))
        assertDeinflects("知らん", "知る", V5, listOf("negative"))
        assertDeinflects("やっとく", "やる", V5, listOf("-te oku"))
        assertDeinflects("読んじゃった", "読む", V5, listOf("-te shimau", "past"))
        assertDeinflects("いらっしゃいませ", "いらっしゃる", V5, listOf("polite imperative"))
        assertDeinflects("分からなかった", "分かる", V5, listOf("negative", "past"))
    }

    @Test
    fun irregular() {
        assertDeinflects("した", "する", VS, listOf("past"))
        assertDeinflects("しない", "する", VS, listOf("negative"))
        assertDeinflects("します", "する", VS, listOf("polite"))
        assertDeinflects("される", "する", VS, listOf("passive"))
        assertDeinflects("させる", "する", VS, listOf("causative"))
        assertDeinflects("しよう", "する", VS, listOf("volitional"))
        assertDeinflects("すれば", "する", VS, listOf("-ba"))
        assertDeinflects("勉強した", "勉強する", VS, listOf("past"))
        assertDeinflects("きた", "くる", VK, listOf("past"))
        assertDeinflects("こない", "くる", VK, listOf("negative"))
        assertDeinflects("来ない", "来る", VK, listOf("negative"))
        assertDeinflects("来ます", "来る", VK, listOf("polite"))
        assertDeinflects("こられる", "くる", VK, listOf("passive/potential"))
        assertDeinflects("こい", "くる", VK, listOf("imperative"))
    }

    @Test
    fun adjectives() {
        assertDeinflects("高かった", "高い", ADJ_I, listOf("past"))
        assertDeinflects("高くない", "高い", ADJ_I, listOf("negative"))
        assertDeinflects("高くなかった", "高い", ADJ_I, listOf("negative", "past"))
        assertDeinflects("高くて", "高い", ADJ_I, listOf("-te"))
        assertDeinflects("高ければ", "高い", ADJ_I, listOf("-ba"))
        assertDeinflects("高く", "高い", ADJ_I, listOf("adverbial"))
        assertDeinflects("高さ", "高い", ADJ_I, listOf("noun"))
        assertDeinflects("高すぎる", "高い", ADJ_I, listOf("-sugiru"))
        assertDeinflects("よかった", "よい", ADJ_I, listOf("past"))
    }

    @Test
    fun intermediateFormsAreNotReturned() {
        val results = Deinflector.deinflect("食べている")
        assertFalse(results.any { it.text == "食べて" && it.conditions != 0 })
        assertTrue(results.all { it.conditions == 0 || it.conditions and Deinflector.BASE != 0 })
    }

    @Test
    fun conditionsFromRules() {
        assertEquals(V1, Deinflector.conditionsFromRules("v1"))
        assertEquals(V5, Deinflector.conditionsFromRules("v5"))
        assertEquals(V5, Deinflector.conditionsFromRules("v5k-s"))
        assertEquals(V1 or VS, Deinflector.conditionsFromRules("v1 vs"))
        assertEquals(ADJ_I, Deinflector.conditionsFromRules("adj-i"))
        assertEquals(0, Deinflector.conditionsFromRules(""))
        assertEquals(0, Deinflector.conditionsFromRules(null))
    }

    @Test
    fun staysBounded() {
        val long = "あいうえおかきくけこさしすせそたちつてとなにぬねの"
        val results = Deinflector.deinflect(long)
        assertTrue(results.size < 400)
    }
}
