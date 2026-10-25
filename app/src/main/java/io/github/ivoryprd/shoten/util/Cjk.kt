package io.github.ivoryprd.shoten.util

/** Character classification and conversion helpers for Japanese and Chinese text. */
object Cjk {
    fun isHiragana(cp: Int): Boolean = cp in 0x3041..0x3096 || cp in 0x309D..0x309F

    fun isKatakana(cp: Int): Boolean =
        cp in 0x30A1..0x30FA || cp in 0x30FD..0x30FF || cp in 0x31F0..0x31FF ||
            cp in 0x32D0..0x32FE || cp in 0xFF66..0xFF9D

    /** Prolonged sound mark and kana iteration marks count as kana. */
    fun isKana(cp: Int): Boolean = isHiragana(cp) || isKatakana(cp) || cp == 0x30FC || cp == 0xFF70

    fun isHan(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x323AF ||
            cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F ||
            cp == 0x3005 || cp == 0x3006 || cp == 0x3007 || cp == 0x303B

    fun isJapanese(cp: Int): Boolean = isKana(cp) || isHan(cp)

    fun containsKana(s: CharSequence): Boolean = codePoints(s).any { isKana(it) }

    fun containsHan(s: CharSequence): Boolean = codePoints(s).any { isHan(it) }

    fun codePoints(s: CharSequence): Sequence<Int> = sequence {
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            yield(cp)
            i += Character.charCount(cp)
        }
    }

    fun codePointCount(s: CharSequence): Int = Character.codePointCount(s, 0, s.length)

    /** Returns the first [count] code points of [s]. */
    fun takeCodePoints(s: String, count: Int): String {
        if (count <= 0) return ""
        val end = runCatching { s.offsetByCodePoints(0, count) }.getOrElse { return s }
        return s.substring(0, end)
    }

    fun katakanaToHiragana(s: String): String {
        var changed = false
        val out = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            val converted = when {
                c in 0x30A1..0x30F6 -> c - 0x60
                c == 0x30FD || c == 0x30FE -> c - 0x60
                else -> c
            }
            if (converted != c) changed = true
            out.append(converted.toChar())
        }
        return if (changed) out.toString() else s
    }

    fun hiraganaToKatakana(s: String): String {
        var changed = false
        val out = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            val converted = when {
                c in 0x3041..0x3096 -> c + 0x60
                c == 0x309D || c == 0x309E -> c + 0x60
                else -> c
            }
            if (converted != c) changed = true
            out.append(converted.toChar())
        }
        return if (changed) out.toString() else s
    }

    // Half-width katakana U+FF61..U+FF9F mapped to full-width forms.
    private const val HALF_WIDTH_SOURCE =
        "｡｢｣､･ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝﾞﾟ"
    private const val HALF_WIDTH_TARGET =
        "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン゛゜"

    private const val DAKUTEN_BASE = "カキクケコサシスセソタチツテトハヒフヘホウ"
    private const val DAKUTEN_RESULT = "ガギグゲゴザジズゼゾダヂヅデドバビブベボヴ"
    private const val HANDAKUTEN_BASE = "ハヒフヘホ"
    private const val HANDAKUTEN_RESULT = "パピプペポ"

    /** Converts a single half-width katakana character to full width, or returns it unchanged. */
    fun halfWidthKanaToFull(ch: Char): Char {
        val i = HALF_WIDTH_SOURCE.indexOf(ch)
        return if (i >= 0) HALF_WIDTH_TARGET[i] else ch
    }

    fun isVoicingMark(ch: Char): Boolean = ch == 'ﾞ' || ch == 'ﾟ' || ch == '゛' || ch == '゜'

    /** Combines [base] with a (half- or full-width) voicing mark, or returns null if impossible. */
    fun combineVoicing(base: Char, mark: Char): Char? {
        val dakuten = mark == 'ﾞ' || mark == '゛'
        val baseSet = if (dakuten) DAKUTEN_BASE else HANDAKUTEN_BASE
        val resultSet = if (dakuten) DAKUTEN_RESULT else HANDAKUTEN_RESULT
        val i = baseSet.indexOf(base)
        return if (i >= 0) resultSet[i] else null
    }

    /** Converts half-width katakana (including separate voicing marks) to full-width katakana. */
    fun normalizeHalfWidthKana(s: String): String {
        if (s.none { it.code in 0xFF61..0xFF9F }) return s
        val out = StringBuilder(s.length)
        for (ch in s) {
            if (isVoicingMark(ch) && out.isNotEmpty()) {
                val combined = combineVoicing(out[out.length - 1], ch)
                if (combined != null) {
                    out.setCharAt(out.length - 1, combined)
                    continue
                }
            }
            out.append(halfWidthKanaToFull(ch))
        }
        return out.toString()
    }

    // Kanji (and other characters) that OCR engines commonly output in place of similar-looking katakana.
    private val KATAKANA_LOOKALIKES = mapOf(
        '一' to 'ー', '－' to 'ー', '―' to 'ー', '—' to 'ー', '‐' to 'ー',
        '口' to 'ロ', '力' to 'カ', '工' to 'エ', '夕' to 'タ', '卜' to 'ト',
        '二' to 'ニ', '八' to 'ハ', '才' to 'オ', '千' to 'チ', 'へ' to 'ヘ', 'べ' to 'ベ', 'ぺ' to 'ペ',
    )

    /**
     * Replaces characters that are probably OCR mistakes for katakana when they appear next to
     * katakana, e.g. "コ一ヒ一" becomes "コーヒー". The result has the same length as the input.
     */
    fun fixKatakanaLookalikes(s: String): String {
        if (s.none { it in KATAKANA_LOOKALIKES }) return s
        val chars = s.toCharArray()
        var changed = false
        for (i in chars.indices) {
            val replacement = KATAKANA_LOOKALIKES[s[i]] ?: continue
            val prev = if (i > 0) s[i - 1] else null
            val next = if (i + 1 < s.length) s[i + 1] else null
            val nearKatakana = (prev != null && isKatakanaChar(prev)) || (next != null && isKatakanaChar(next))
            if (nearKatakana) {
                chars[i] = replacement
                changed = true
            }
        }
        return if (changed) String(chars) else s
    }

    private fun isKatakanaChar(ch: Char): Boolean = isKatakana(ch.code) || ch == 'ー'

    /** Whether the character ends a lookup scan (whitespace and sentence punctuation). */
    fun isScanTerminator(cp: Int): Boolean {
        if (Character.isWhitespace(cp) || cp == 0x3000) return true
        return when (cp) {
            '。'.code, '、'.code, '，'.code, '．'.code, '！'.code, '？'.code, '!'.code, '?'.code,
            '「'.code, '」'.code, '『'.code, '』'.code, '（'.code, '）'.code, '('.code, ')'.code,
            '【'.code, '】'.code, '〔'.code, '〕'.code, '［'.code, '］'.code, '['.code, ']'.code,
            '｛'.code, '｝'.code, '〈'.code, '〉'.code, '《'.code, '》'.code, '“'.code, '”'.code,
            '‘'.code, '’'.code, '"'.code, '…'.code, '‥'.code, '：'.code, '；'.code, ':'.code,
            ';'.code, ','.code, '.'.code, '｡'.code, '､'.code, '｢'.code, '｣'.code, '／'.code, '/'.code,
            '♪'.code, '☆'.code, '★'.code, '～'.code, '〜'.code -> true
            else -> false
        }
    }
}
