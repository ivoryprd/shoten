package io.github.ivoryprd.shoten.lookup

import io.github.ivoryprd.shoten.util.Cjk
import kotlin.math.abs

/**
 * Finds dictionary entries for the text starting at the scan position, Yomitan style: every
 * prefix of the text is tried (longest first), Japanese prefixes are also deinflected, and the
 * longest matches are shown first.
 */
class LookupEngine(private val source: TermSource) {

    private class Candidate(val sourceLength: Int, val conditions: Int, val reasons: List<String>, val variant: Int)

    fun lookup(rawText: String, language: Language, maxGroups: Int = MAX_GROUPS): LookupResult {
        val text = prepareScanText(rawText, if (language == Language.CHINESE) MAX_SCAN_CHINESE else MAX_SCAN)
        if (text.isEmpty()) return LookupResult.empty(text, language)

        val ends = codePointEnds(text)
        val candidates = HashMap<String, MutableList<Candidate>>()
        val japanese = language == Language.JAPANESE
        if (japanese) {
            val fixed = Cjk.fixKatakanaLookalikes(text)
            val variants = listOf(text, Cjk.katakanaToHiragana(text), fixed, Cjk.katakanaToHiragana(fixed)).distinct()
            variants.forEachIndexed { variantIndex, variant ->
                for (end in ends.asReversed()) {
                    for (d in Deinflector.deinflect(variant.substring(0, end))) {
                        candidates.getOrPut(d.text) { ArrayList(2) }
                            .add(Candidate(end, d.conditions, d.reasons, variantIndex))
                    }
                }
            }
        } else {
            for (end in ends) {
                candidates.getOrPut(text.substring(0, end)) { ArrayList(1) }.add(Candidate(end, 0, emptyList(), 0))
            }
        }

        val rows = source.findTerms(candidates.keys, language, includeReadings = japanese)
        val best = LinkedHashMap<String, TermMatch>()
        for (row in rows) {
            val rowConditions = Deinflector.conditionsFromRules(row.rules)
            var match: TermMatch? = null
            for (byReading in booleanArrayOf(false, true)) {
                if (byReading && (!japanese || row.reading == row.expression)) continue
                val key = if (byReading) row.reading else row.expression
                for (c in candidates[key] ?: continue) {
                    if (c.conditions != 0 && rowConditions and c.conditions == 0) continue
                    val m = TermMatch(row, c.sourceLength, c.reasons, c.variant, byReading)
                    if (match == null || MATCH_ORDER.compare(m, match) < 0) match = m
                }
            }
            if (match != null) put(best, match)
        }

        resolveRedirects(best)

        val sorted = best.values.sortedWith(MATCH_ORDER)
        val groups = group(sorted, maxGroups)
        val withMeta = attachMeta(groups, language)
        // The kanji of the word found (or of the first character, if none is), in order.
        val kanji = if (japanese) {
            val word = text.take(groups.maxOfOrNull { it.sourceLength }?.takeIf { it > 0 } ?: Character.charCount(text.codePointAt(0)))
            val characters = Cjk.codePoints(word).filter { Cjk.isHan(it) }.map { String(Character.toChars(it)) }.distinct().toList()
            if (characters.isEmpty()) emptyList() else source.findKanji(characters).sortedBy { characters.indexOf(it.character) }
        } else {
            emptyList()
        }
        return LookupResult(text, language, withMeta, kanji)
    }

    /** The entries of kanji dictionaries for [character]. */
    fun lookupKanji(character: String): LookupResult =
        LookupResult(character, Language.JAPANESE, emptyList(), source.findKanji(listOf(character)))

    private fun put(best: MutableMap<String, TermMatch>, match: TermMatch) {
        val key = match.row.dictionary.id + ':' + match.row.rowId
        val existing = best[key]
        if (existing == null || MATCH_ORDER.compare(match, existing) < 0) best[key] = match
    }

    /**
     * Some dictionaries (e.g. Jitendex) have entries for rare spellings whose glossary is just a
     * pointer to the main entry, encoded as a [term, [reasons]] item. Replace those entries with
     * the entries they point to.
     */
    private fun resolveRedirects(best: MutableMap<String, TermMatch>) {
        val redirects = best.entries.mapNotNull { (key, match) ->
            val targets = match.row.glossary.mapNotNull { item ->
                val list = item as? List<*> ?: return@mapNotNull null
                val term = list.firstOrNull() as? String ?: return@mapNotNull null
                val reasons = (list.getOrNull(1) as? List<*>)?.filterIsInstance<String>().orEmpty()
                term to reasons
            }
            if (targets.isEmpty()) null else Triple(key, match, targets)
        }
        if (redirects.isEmpty()) return
        for (items in redirects.groupBy { it.second.row.dictionary.id }.values) {
            val dict = items.first().second.row.dictionary
            val terms = items.flatMap { item -> item.third.map { it.first } }.toSet()
            val rows = source.findTermsByExpression(dict, terms).groupBy { it.expression }
            for ((key, match, targets) in items) {
                val resolved = ArrayList<TermMatch>()
                for ((term, extraReasons) in targets) {
                    val candidates = rows[term].orEmpty().filter { it.rowId != match.row.rowId }
                    if (candidates.isEmpty()) continue
                    // Jitendex marks redirect entries with the negated sequence number of their target.
                    val seq = match.row.sequence
                    val preferred = if (seq != null && seq != 0L) candidates.filter { it.sequence == abs(seq) } else emptyList()
                    for (target in preferred.ifEmpty { candidates }) {
                        resolved.add(TermMatch(target, match.sourceLength, match.reasons + extraReasons, match.variant, match.byReading))
                    }
                }
                if (resolved.isNotEmpty()) {
                    best.remove(key)
                    for (m in resolved) put(best, m)
                }
            }
        }
    }

    private fun group(sorted: List<TermMatch>, maxGroups: Int): List<TermGroup> {
        val groups = LinkedHashMap<String, MutableList<TermMatch>>()
        for (m in sorted) {
            val key = m.row.expression + '\u0000' + m.row.reading
            val list = groups[key]
            if (list == null) {
                if (groups.size >= maxGroups) continue
                groups[key] = mutableListOf(m)
            } else {
                list.add(m)
            }
        }
        return groups.values.map { matches ->
            val first = matches.first()
            val entries = matches.sortedWith(compareBy({ it.row.dictionary.priority }, { -it.row.score }, { it.row.rowId }))
            TermGroup(
                expression = first.row.expression,
                reading = first.row.reading,
                alt = matches.firstNotNullOfOrNull { it.row.alt },
                sourceLength = first.sourceLength,
                reasons = first.reasons,
                entries = entries,
                meta = emptyList(),
            )
        }
    }

    private fun attachMeta(groups: List<TermGroup>, language: Language): List<TermGroup> {
        if (groups.isEmpty()) return groups
        val metaRows = source.findTermMeta(groups.map { it.expression }.toSet(), language)
        if (metaRows.isEmpty()) return groups
        val byExpression = metaRows.groupBy { it.expression }
        return groups.map { g ->
            val rows = byExpression[g.expression].orEmpty().filter { metaMatchesReading(it, g) }
            if (rows.isEmpty()) g else TermGroup(g.expression, g.reading, g.alt, g.sourceLength, g.reasons, g.entries, rows)
        }
    }

    private fun metaMatchesReading(row: TermMetaRow, group: TermGroup): Boolean {
        val data = row.data as? Map<*, *> ?: return true
        val reading = data["reading"] as? String ?: return true
        return Cjk.katakanaToHiragana(reading) == Cjk.katakanaToHiragana(group.reading)
    }

    companion object {
        const val MAX_SCAN = 24
        const val MAX_SCAN_CHINESE = 16
        const val MAX_GROUPS = 32

        private val MATCH_ORDER: Comparator<TermMatch> = compareBy(
            { -it.sourceLength },
            { it.reasons.size },
            { it.variant },
            { if (it.byReading) 1 else 0 },
            { it.row.dictionary.priority },
            { -it.row.score },
            { it.row.rowId },
        )

        /**
         * Normalizes the text for scanning: half-width katakana become full width, and the text is
         * cut at the first whitespace or sentence punctuation and after [maxCodePoints].
         */
        fun prepareScanText(raw: String, maxCodePoints: Int = MAX_SCAN): String {
            val normalized = Cjk.normalizeHalfWidthKana(raw)
            val out = StringBuilder()
            var count = 0
            var i = 0
            while (i < normalized.length && count < maxCodePoints) {
                val cp = normalized.codePointAt(i)
                if (Cjk.isScanTerminator(cp) || Character.isISOControl(cp)) break
                out.appendCodePoint(cp)
                count++
                i += Character.charCount(cp)
            }
            return out.toString()
        }

        private fun codePointEnds(text: String): List<Int> {
            val ends = ArrayList<Int>()
            var i = 0
            while (i < text.length) {
                i += Character.charCount(text.codePointAt(i))
                ends.add(i)
            }
            return ends
        }
    }
}
