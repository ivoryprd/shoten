package io.github.ivoryprd.shoten.lookup

/**
 * A candidate dictionary form produced by deinflecting some text.
 *
 * @property conditions what kind of word the candidate must be ([Deinflector.BASE] bits), or 0 if
 *   the candidate is the original text (which may be any kind of word).
 * @property reasons the applied transformations, from the dictionary form outwards (e.g.
 *   ["negative", "past"] for 食べなかった).
 */
data class Deinflection(val text: String, val conditions: Int, val reasons: List<String>)

/**
 * Rule-based Japanese deinflector. Each rule replaces an inflected suffix with a less inflected
 * one. Rules are chained through grammatical conditions: a rule applies to a candidate only if the
 * candidate is the original text or matches one of the rule's input conditions.
 */
object Deinflector {
    // Dictionary word classes (these match the "rules" of Yomitan dictionary entries).
    const val V1 = 1 shl 0 // ichidan verb
    const val V5 = 1 shl 1 // godan verb
    const val VK = 1 shl 2 // くる
    const val VS = 1 shl 3 // する
    const val VZ = 1 shl 4 // ずる
    const val ADJ_I = 1 shl 5 // i-adjective
    const val BASE = V1 or V5 or VK or VS or VZ or ADJ_I

    // Intermediate forms that only exist inside a chain of rules.
    const val TE = 1 shl 6 // te-form (食べて)
    const val STEM = 1 shl 7 // continuative / masu stem (食べ, 書き)
    const val A_STEM = 1 shl 8 // irrealis / nai stem (食べ, 書か)

    private const val VERB = V1 or V5 or VK or VS or VZ

    private class Rule(
        val from: String,
        val to: String,
        /** Conditions the inflected word must satisfy; 0 means the rule only applies to the original text. */
        val inMask: Int,
        val outMask: Int,
        val reason: String?,
        /** Only apply as part of a chain, never to the original text. */
        val chainedOnly: Boolean = false,
        /** Don't record [reason] when this rule continues a chain (used for stems and te-forms). */
        val silentInChain: Boolean = false,
    )

    // Lazy because the rule tables below are initialized after these properties.
    private val rulesByLastChar: Map<Char?, List<Rule>> by lazy { buildRules().groupBy { it.from.lastOrNull() } }

    private const val MAX_CANDIDATES = 400

    /** Maps the space separated "rules" of a dictionary entry to condition bits. */
    fun conditionsFromRules(rules: String?): Int {
        if (rules.isNullOrBlank()) return 0
        var mask = 0
        for (token in rules.split(' ', '\t')) {
            mask = mask or when {
                token.isEmpty() -> 0
                token == "v1" || token.startsWith("v1-") -> V1
                token.startsWith("v5") -> V5
                token == "vk" -> VK
                token == "vs" || token == "vs-i" || token == "vs-s" -> VS
                token == "vz" -> VZ
                token == "adj-i" || token == "adj-ix" -> ADJ_I
                else -> 0
            }
        }
        return mask
    }

    /**
     * Returns all candidate dictionary forms of [text], starting with the text itself. Candidates
     * whose conditions are purely intermediate are not returned.
     */
    fun deinflect(text: String): List<Deinflection> {
        if (text.isEmpty()) return emptyList()
        val results = ArrayList<Deinflection>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Deinflection>()
        val initial = Deinflection(text, 0, emptyList())
        queue.add(initial)
        seen.add(key(initial))
        while (queue.isNotEmpty() && seen.size < MAX_CANDIDATES) {
            val current = queue.removeFirst()
            if (current.conditions == 0 || current.conditions and BASE != 0) {
                results.add(current)
            }
            val candidates = rulesByLastChar[current.text.lastOrNull()].orEmpty() + rulesByLastChar[null].orEmpty()
            for (rule in candidates) {
                if (!applies(rule, current)) continue
                val stem = current.text.substring(0, current.text.length - rule.from.length)
                val newText = stem + rule.to
                if (newText.isEmpty()) continue
                val reasons = if (rule.reason == null || (rule.silentInChain && current.conditions != 0)) {
                    current.reasons
                } else {
                    listOf(rule.reason) + current.reasons
                }
                val next = Deinflection(newText, rule.outMask, reasons)
                if (seen.add(key(next))) queue.add(next)
            }
        }
        return results
    }

    private fun key(d: Deinflection) = d.text + '\u0000' + d.conditions

    private fun applies(rule: Rule, current: Deinflection): Boolean {
        val text = current.text
        if (!text.endsWith(rule.from)) return false
        if (rule.from.isEmpty() && text.isEmpty()) return false
        return if (current.conditions == 0) {
            !rule.chainedOnly
        } else {
            current.conditions and rule.inMask != 0
        }
    }

    // --- Rule table -------------------------------------------------------------------------

    private class RuleBuilder {
        val rules = ArrayList<Rule>()

        fun rule(
            from: String, to: String, inMask: Int, outMask: Int, reason: String?,
            chainedOnly: Boolean = false, silentInChain: Boolean = false,
        ) {
            rules.add(Rule(from, to, inMask, outMask, reason, chainedOnly, silentInChain))
        }
    }

    // Godan verb endings and their conjugation rows: dictionary, a-row, i-row, e-row, o-row.
    private val GODAN = listOf(
        listOf("う", "わ", "い", "え", "お"),
        listOf("く", "か", "き", "け", "こ"),
        listOf("ぐ", "が", "ぎ", "げ", "ご"),
        listOf("す", "さ", "し", "せ", "そ"),
        listOf("つ", "た", "ち", "て", "と"),
        listOf("ぬ", "な", "に", "ね", "の"),
        listOf("ぶ", "ば", "び", "べ", "ぼ"),
        listOf("む", "ま", "み", "め", "も"),
        listOf("る", "ら", "り", "れ", "ろ"),
    )

    /** te-form endings (with て/で) mapped to dictionary endings and word classes. */
    private val TE_FORMS = listOf(
        Triple("て", "る", V1),
        Triple("いて", "く", V5),
        Triple("いで", "ぐ", V5),
        Triple("して", "す", V5),
        Triple("って", "う", V5),
        Triple("って", "つ", V5),
        Triple("って", "る", V5),
        Triple("んで", "む", V5),
        Triple("んで", "ぬ", V5),
        Triple("んで", "ぶ", V5),
        Triple("いって", "いく", V5),
        Triple("行って", "行く", V5),
        Triple("逝って", "逝く", V5),
        Triple("往って", "往く", V5),
        Triple("きて", "くる", VK),
        Triple("来て", "来る", VK),
        Triple("來て", "來る", VK),
        Triple("して", "する", VS),
        Triple("為て", "為る", VS),
        Triple("じて", "ずる", VZ),
    )

    private fun buildRules(): List<Rule> = RuleBuilder().apply {
        // te-form and the forms built on it (past, -tara, -tari).
        for ((te, base, cls) in TE_FORMS) {
            rule(te, base, TE, cls, "-te", silentInChain = true)
            val past = te.dropLast(1) + if (te.endsWith("で")) "だ" else "た"
            rule(past, base, 0, cls, "past")
            rule(past + "ら", base, 0, cls, "-tara")
            rule(past + "り", base, 0, cls, "-tari")
        }

        // i-adjectives.
        rule("くて", "い", TE, ADJ_I, "-te", silentInChain = true)
        rule("かった", "い", 0, ADJ_I, "past")
        rule("かったら", "い", 0, ADJ_I, "-tara")
        rule("かったり", "い", 0, ADJ_I, "-tari")
        rule("くない", "い", ADJ_I, ADJ_I, "negative")
        rule("ければ", "い", 0, ADJ_I, "-ba")
        rule("けりゃ", "い", 0, ADJ_I, "-ba")
        rule("く", "い", 0, ADJ_I, "adverbial")
        rule("さ", "い", 0, ADJ_I, "noun")
        rule("そう", "い", 0, ADJ_I, "-sou")
        rule("すぎる", "い", V1, ADJ_I, "-sugiru")
        rule("過ぎる", "い", V1, ADJ_I, "-sugiru")
        rule("かろう", "い", 0, ADJ_I, "volitional")

        // Continuative stem (連用形) back to the dictionary form.
        rule("", "る", STEM, V1, "continuative", silentInChain = true)
        for (row in GODAN) rule(row[2], row[0], STEM, V5, "continuative", silentInChain = true)
        rule("き", "くる", STEM, VK, "continuative", silentInChain = true)
        rule("来", "来る", STEM, VK, "continuative", silentInChain = true)
        rule("し", "する", STEM, VS, "continuative", silentInChain = true)
        rule("じ", "ずる", STEM, VZ, "continuative", silentInChain = true)
        // Honorific godan verbs whose stem ends in い instead of り.
        for (verb in listOf("いらっしゃ", "おっしゃ", "仰しゃ", "くださ", "下さ", "なさ", "ござ", "御座")) {
            rule(verb + "い", verb + "る", STEM, V5, "continuative", silentInChain = true)
        }

        // Auxiliaries attached to the continuative stem.
        rule("ます", "", 0, STEM, "polite")
        rule("ました", "", 0, STEM, "polite past")
        rule("ません", "", 0, STEM, "polite negative")
        rule("ませんでした", "", 0, STEM, "polite past negative")
        rule("ましょう", "", 0, STEM, "polite volitional")
        rule("まして", "", 0, STEM, "polite -te")
        rule("ませ", "", 0, STEM, "polite imperative")
        rule("たい", "", ADJ_I, STEM, "-tai")
        rule("たがる", "", V5, STEM, "-tagaru")
        rule("なさい", "", 0, STEM, "-nasai")
        rule("ながら", "", 0, STEM, "-nagara")
        rule("そう", "", 0, STEM, "-sou")
        rule("すぎる", "", V1, STEM, "-sugiru")
        rule("過ぎる", "", V1, STEM, "-sugiru")
        rule("やすい", "", ADJ_I, STEM, "-yasui")
        rule("にくい", "", ADJ_I, STEM, "-nikui")
        rule("づらい", "", ADJ_I, STEM, "-zurai")

        // Irrealis stem (未然形) back to the dictionary form. Only reachable through an auxiliary.
        rule("", "る", A_STEM, V1, null, chainedOnly = true)
        for (row in GODAN) rule(row[1], row[0], A_STEM, V5, null, chainedOnly = true)
        rule("こ", "くる", A_STEM, VK, null, chainedOnly = true)
        rule("来", "来る", A_STEM, VK, null, chainedOnly = true)
        rule("し", "する", A_STEM, VS, null, chainedOnly = true)
        rule("さ", "する", A_STEM, VS, null, chainedOnly = true)
        rule("せ", "する", A_STEM, VS, null, chainedOnly = true)
        rule("じ", "ずる", A_STEM, VZ, null, chainedOnly = true)
        rule("ぜ", "ずる", A_STEM, VZ, null, chainedOnly = true)

        // Auxiliaries attached to the irrealis stem.
        rule("ない", "", ADJ_I, A_STEM, "negative")
        rule("ないで", "", 0, A_STEM, "negative -te")
        rule("なきゃ", "", 0, A_STEM, "-nakya")
        rule("ず", "", 0, A_STEM, "-zu")
        rule("ずに", "", 0, A_STEM, "-zu ni")
        rule("ぬ", "", 0, A_STEM, "negative")
        rule("ん", "", 0, A_STEM, "negative")
        rule("せる", "", V1, A_STEM, "causative")
        rule("させる", "", V1, A_STEM, "causative")
        rule("される", "", V1, A_STEM, "causative passive")
        rule("れる", "", V1, A_STEM, "passive")
        rule("られる", "", V1, A_STEM, "passive/potential")

        // Potential forms.
        for (row in GODAN) rule(row[3] + "る", row[0], V1, V5, "potential")
        rule("れる", "る", V1, V1, "potential") // ら抜き言葉 (食べれる)
        rule("これる", "くる", V1, VK, "potential")
        rule("来れる", "来る", V1, VK, "potential")

        // Imperative.
        rule("ろ", "る", 0, V1, "imperative")
        rule("よ", "る", 0, V1, "imperative")
        for (row in GODAN) rule(row[3], row[0], 0, V5, "imperative")
        rule("こい", "くる", 0, VK, "imperative")
        rule("来い", "来る", 0, VK, "imperative")
        rule("しろ", "する", 0, VS, "imperative")
        rule("せよ", "する", 0, VS, "imperative")
        rule("じろ", "ずる", 0, VZ, "imperative")
        rule("ぜよ", "ずる", 0, VZ, "imperative")

        // Volitional.
        rule("よう", "る", 0, V1, "volitional")
        for (row in GODAN) rule(row[4] + "う", row[0], 0, V5, "volitional")
        rule("こよう", "くる", 0, VK, "volitional")
        rule("来よう", "来る", 0, VK, "volitional")
        rule("しよう", "する", 0, VS, "volitional")
        rule("じよう", "ずる", 0, VZ, "volitional")

        // Conditional -ba.
        rule("れば", "る", 0, V1 or V5, "-ba")
        rule("りゃ", "る", 0, V1 or V5, "-ba")
        for (row in GODAN) if (row[0] != "る") rule(row[3] + "ば", row[0], 0, V5, "-ba")
        rule("くれば", "くる", 0, VK, "-ba")
        rule("来れば", "来る", 0, VK, "-ba")
        rule("すれば", "する", 0, VS, "-ba")
        rule("ずれば", "ずる", 0, VZ, "-ba")

        // Auxiliaries attached to the te-form.
        for ((te, de) in listOf("て" to "で")) {
            for (prefix in listOf(te, de)) {
                rule(prefix + "いる", prefix, V1, TE, "progressive")
                rule(prefix + "る", prefix, V1, TE, "progressive")
                rule(prefix + "ある", prefix, V5, TE, "resultative")
                rule(prefix + "おく", prefix, V5, TE, "-te oku")
                rule(prefix + "しまう", prefix, V5, TE, "-te shimau")
                rule(prefix + "みる", prefix, V1, TE, "-te miru")
                rule(prefix + "くる", prefix, VK, TE, "-te kuru")
                rule(prefix + "いく", prefix, V5, TE, "-te iku")
                rule(prefix + "く", prefix, V5, TE, "-te iku")
                rule(prefix + "くれる", prefix, V1, TE, "-te kureru")
                rule(prefix + "もらう", prefix, V5, TE, "-te morau")
                rule(prefix + "あげる", prefix, V1, TE, "-te ageru")
                rule(prefix + "ほしい", prefix, ADJ_I, TE, "-te hoshii")
                rule(prefix + "ください", prefix, 0, TE, "-te kudasai")
                rule(prefix + "下さい", prefix, 0, TE, "-te kudasai")
                rule(prefix + "も", prefix, 0, TE, "-te mo")
                rule(prefix + "は", prefix, 0, TE, "-te wa")
                rule(prefix + "ない", prefix, ADJ_I, TE, "negative progressive")
            }
        }
        rule("とく", "て", V5, TE, "-te oku")
        rule("どく", "で", V5, TE, "-te oku")
        rule("ちゃう", "て", V5, TE, "-te shimau")
        rule("じゃう", "で", V5, TE, "-te shimau")
        rule("ちまう", "て", V5, TE, "-te shimau")
        rule("じまう", "で", V5, TE, "-te shimau")
        rule("ちゃ", "て", 0, TE, "-te wa")
        rule("じゃ", "で", 0, TE, "-te wa")
    }.rules
}
