package io.github.ivoryprd.shoten.lookup

import io.github.ivoryprd.shoten.util.Cjk

/** A piece of a headword with the reading that goes above it (null if no reading is needed). */
data class FuriganaSegment(val text: String, val ruby: String?)

object Furigana {
    /**
     * Splits [expression] into segments so that readings are shown above kanji only, e.g.
     * 食べ物/たべもの becomes 食(た) べ 物(もの). Falls back to one segment with the whole reading.
     */
    fun distribute(expression: String, reading: String): List<FuriganaSegment> {
        if (reading.isEmpty() || reading == expression) return listOf(FuriganaSegment(expression, null))
        if (Cjk.katakanaToHiragana(reading) == Cjk.katakanaToHiragana(expression)) {
            return listOf(FuriganaSegment(expression, null))
        }
        val runs = splitRuns(expression)
        if (runs.none { !it.second }) {
            // Pure kana headword with a different reading: show the reading over everything.
            return listOf(FuriganaSegment(expression, reading))
        }
        val normalizedReading = Cjk.katakanaToHiragana(reading)
        val result = match(runs, 0, normalizedReading, reading, 0)
        return result ?: listOf(FuriganaSegment(expression, reading))
    }

    /** Splits text into runs of kana (true) and non-kana (false). */
    private fun splitRuns(text: String): List<Pair<String, Boolean>> {
        val runs = ArrayList<Pair<String, Boolean>>()
        val current = StringBuilder()
        var currentKana: Boolean? = null
        for (cp in Cjk.codePoints(text)) {
            val kana = Cjk.isKana(cp)
            if (currentKana != null && kana != currentKana) {
                runs.add(current.toString() to currentKana)
                current.setLength(0)
            }
            current.appendCodePoint(cp)
            currentKana = kana
        }
        if (currentKana != null) runs.add(current.toString() to currentKana)
        return runs
    }

    private fun match(
        runs: List<Pair<String, Boolean>>,
        index: Int,
        normalized: String,
        original: String,
        pos: Int,
    ): List<FuriganaSegment>? {
        if (index == runs.size) return if (pos == normalized.length) emptyList() else null
        val (text, kana) = runs[index]
        if (kana) {
            val expected = Cjk.katakanaToHiragana(text)
            if (!normalized.startsWith(expected, pos)) return null
            val rest = match(runs, index + 1, normalized, original, pos + expected.length) ?: return null
            return listOf(FuriganaSegment(text, null)) + rest
        }
        // Non-kana run: it must consume at least one character of the reading.
        val maxEnd = normalized.length - remainingKanaLength(runs, index + 1)
        for (end in (pos + 1)..maxEnd) {
            val rest = match(runs, index + 1, normalized, original, end) ?: continue
            return listOf(FuriganaSegment(text, original.substring(pos, end))) + rest
        }
        return null
    }

    private fun remainingKanaLength(runs: List<Pair<String, Boolean>>, from: Int): Int {
        var total = 0
        for (i in from until runs.size) {
            total += if (runs[i].second) runs[i].first.length else 1
        }
        return total
    }
}
