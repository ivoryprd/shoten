package io.github.ivoryprd.shoten.lookup

/** Conversion of numbered pinyin (CC-CEDICT style, e.g. "zhong1 wen2") to tone marks. */
object Pinyin {
    data class Syllable(val text: String, val tone: Int)

    private val SYLLABLE = Regex("^([A-Za-zÜü:]+?)([1-5])$")
    private val BRACKETED = Regex("\\[([A-Za-zÜü:]+[1-5](?: [A-Za-zÜü:,·]+[1-5]?)*)]")

    private const val VOWELS = "aeiouüAEIOUÜ"
    private val MARKS = mapOf(
        'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì", 'o' to "ōóǒò", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
        'A' to "ĀÁǍÀ", 'E' to "ĒÉĚÈ", 'I' to "ĪÍǏÌ", 'O' to "ŌÓǑÒ", 'U' to "ŪÚǓÙ", 'Ü' to "ǕǗǙǛ",
    )
    private val COMBINING = intArrayOf(0x0304, 0x0301, 0x030C, 0x0300).map { it.toChar() }

    /** Splits numbered pinyin into syllables with tones (0 for tokens without a tone number). */
    fun syllables(numbered: String): List<Syllable> =
        numbered.split(' ').filter { it.isNotEmpty() }.map { token ->
            val m = SYLLABLE.find(token)
            if (m == null) {
                Syllable(token.replace("u:", "ü").replace("U:", "Ü"), 0)
            } else {
                val tone = m.groupValues[2].toInt()
                Syllable(mark(normalizeU(m.groupValues[1]), tone), tone)
            }
        }

    fun toMarked(numbered: String): String = syllables(numbered).joinToString(" ") { it.text }

    /** Converts bracketed pinyin inside definitions, e.g. "CL:個|个[ge4]" becomes "CL:個|个[gè]". */
    fun convertBracketed(text: String): String =
        BRACKETED.replace(text) { m -> "[" + toMarked(m.groupValues[1]) + "]" }

    fun looksNumbered(text: String): Boolean = text.split(' ').any { SYLLABLE.matches(it) }

    private fun normalizeU(s: String): String =
        s.replace("u:", "ü").replace("U:", "Ü").replace("v", "ü").replace("V", "Ü")

    private fun mark(syllable: String, tone: Int): String {
        if (tone !in 1..4) return syllable
        val index = markIndex(syllable)
        if (index < 0) {
            // Syllables without vowels (m, n, ng, hm): put a combining mark on the first letter.
            if (syllable.isEmpty()) return syllable
            return syllable.substring(0, 1) + COMBINING[tone - 1] + syllable.substring(1)
        }
        val ch = syllable[index]
        val marked = MARKS[ch]?.get(tone - 1) ?: return syllable
        return syllable.substring(0, index) + marked + syllable.substring(index + 1)
    }

    private fun markIndex(s: String): Int {
        s.indexOfFirst { it == 'a' || it == 'A' }.let { if (it >= 0) return it }
        s.indexOfFirst { it == 'e' || it == 'E' }.let { if (it >= 0) return it }
        val ou = s.indexOf("ou", ignoreCase = true)
        if (ou >= 0) return ou
        return s.indexOfLast { it in VOWELS }
    }
}
