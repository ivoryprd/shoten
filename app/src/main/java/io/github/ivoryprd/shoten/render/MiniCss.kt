package io.github.ivoryprd.shoten.render

/**
 * A deliberately small CSS engine for Yomitan dictionary styles. It understands rules whose
 * selectors are compound selectors made of an optional tag name and attribute conditions, such as
 * `span[data-sc-content="part-of-speech-info"]`. Rules with combinators, pseudo classes or other
 * unsupported syntax are ignored, as are nested rules and at-rules.
 */
class StyleSheet private constructor(private val rules: List<Rule>) {
    private class Condition(val name: String, val value: String?)
    private class Selector(val tag: String?, val conditions: List<Condition>) {
        val specificity = conditions.size * 10 + if (tag != null) 1 else 0

        fun matches(tag: String, attributes: Map<String, String>): Boolean {
            if (this.tag != null && !this.tag.equals(tag, ignoreCase = true)) return false
            return conditions.all { c ->
                val v = attributes[c.name] ?: return@all false
                c.value == null || c.value == v
            }
        }
    }

    private class Rule(val selectors: List<Selector>, val declarations: Map<String, String>, val order: Int)

    val isEmpty: Boolean get() = rules.isEmpty()

    /** Declarations (property → value) that apply to an element, in cascade order. */
    fun compute(tag: String, attributes: Map<String, String>): Map<String, String> {
        if (rules.isEmpty()) return emptyMap()
        val matched = ArrayList<Pair<Int, Rule>>()
        for (rule in rules) {
            val specificity = rule.selectors.filter { it.matches(tag, attributes) }.maxOfOrNull { it.specificity } ?: continue
            matched.add(specificity to rule)
        }
        if (matched.isEmpty()) return emptyMap()
        matched.sortWith(compareBy({ it.first }, { it.second.order }))
        val out = HashMap<String, String>()
        for ((_, rule) in matched) out.putAll(rule.declarations)
        return out
    }

    companion object {
        val EMPTY = StyleSheet(emptyList())

        private val TAG = Regex("^[a-zA-Z][a-zA-Z0-9-]*")
        private val ATTRIBUTE = Regex("^\\[\\s*([a-zA-Z_][a-zA-Z0-9_-]*)\\s*(?:=\\s*(\"([^\"]*)\"|'([^']*)'|([a-zA-Z0-9_-]+))\\s*)?]")

        fun parse(css: String?): StyleSheet {
            if (css.isNullOrBlank()) return EMPTY
            val text = stripComments(css)
            val rules = ArrayList<Rule>()
            var i = 0
            var order = 0
            while (i < text.length) {
                val open = text.indexOf('{', i)
                if (open < 0) break
                val prelude = text.substring(i, open).trim()
                val close = findBlockEnd(text, open)
                val body = text.substring(open + 1, close)
                i = close + 1
                if (prelude.startsWith("@")) continue
                val selectors = prelude.split(',').mapNotNull { parseSelector(it.trim()) }
                if (selectors.isEmpty()) continue
                val declarations = parseDeclarations(topLevel(body))
                if (declarations.isNotEmpty()) rules.add(Rule(selectors, declarations, order++))
            }
            return StyleSheet(rules)
        }

        private fun stripComments(css: String): String {
            val sb = StringBuilder(css.length)
            var i = 0
            while (i < css.length) {
                if (css.startsWith("/*", i)) {
                    val end = css.indexOf("*/", i + 2)
                    i = if (end < 0) css.length else end + 2
                } else {
                    sb.append(css[i++])
                }
            }
            return sb.toString()
        }

        /** Index of the '}' matching the '{' at [open] (or the end of the text). */
        private fun findBlockEnd(text: String, open: Int): Int {
            var depth = 0
            var quote = 0.toChar()
            for (j in open until text.length) {
                val c = text[j]
                if (quote != 0.toChar()) {
                    if (c == quote) quote = 0.toChar()
                    continue
                }
                when (c) {
                    '"', '\'' -> quote = c
                    '{' -> depth++
                    '}' -> if (--depth == 0) return j
                }
            }
            return text.length - 1
        }

        /** The block body without nested blocks (and the selectors that introduce them). */
        private fun topLevel(body: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < body.length) {
                val open = body.indexOf('{', i)
                if (open < 0) {
                    sb.append(body, i, body.length)
                    break
                }
                // Drop the nested rule's selector: text after the last ';' before the brace.
                val segment = body.substring(i, open)
                val lastSemicolon = segment.lastIndexOf(';')
                if (lastSemicolon >= 0) sb.append(segment, 0, lastSemicolon + 1)
                i = findBlockEnd(body, open) + 1
            }
            return sb.toString()
        }

        private fun parseDeclarations(body: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (part in splitDeclarations(body)) {
                val colon = part.indexOf(':')
                if (colon <= 0) continue
                val name = part.substring(0, colon).trim().lowercase()
                val value = part.substring(colon + 1).trim().removeSuffix("!important").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
            }
            return out
        }

        private fun splitDeclarations(body: String): List<String> {
            val parts = ArrayList<String>()
            val sb = StringBuilder()
            var quote = 0.toChar()
            var parens = 0
            for (c in body) {
                when {
                    quote != 0.toChar() -> { if (c == quote) quote = 0.toChar(); sb.append(c) }
                    c == '"' || c == '\'' -> { quote = c; sb.append(c) }
                    c == '(' -> { parens++; sb.append(c) }
                    c == ')' -> { parens--; sb.append(c) }
                    c == ';' && parens == 0 -> { parts.add(sb.toString()); sb.setLength(0) }
                    else -> sb.append(c)
                }
            }
            if (sb.isNotBlank()) parts.add(sb.toString())
            return parts
        }

        private fun parseSelector(text: String): Selector? {
            if (text.isEmpty()) return null
            var rest = text
            var tag: String? = null
            if (rest.startsWith("*")) {
                rest = rest.substring(1)
            } else {
                TAG.find(rest)?.let { m ->
                    tag = m.value
                    rest = rest.substring(m.value.length)
                }
            }
            val conditions = ArrayList<Condition>()
            while (rest.isNotEmpty()) {
                val m = ATTRIBUTE.find(rest) ?: return null // combinators, classes, pseudo classes...
                val value = m.groups[3]?.value ?: m.groups[4]?.value ?: m.groups[5]?.value
                conditions.add(Condition(m.groupValues[1].lowercase(), value))
                rest = rest.substring(m.value.length)
            }
            if (tag == null && conditions.isEmpty()) return null
            return Selector(tag, conditions)
        }
    }
}

/** Parses CSS color values; returns null for anything unsupported (var(), color-mix(), ...). */
object CssColors {
    private val NAMED = mapOf(
        "black" to 0xFF000000, "white" to 0xFFFFFFFF, "red" to 0xFFFF0000, "green" to 0xFF008000,
        "blue" to 0xFF0000FF, "yellow" to 0xFFFFFF00, "orange" to 0xFFFFA500, "purple" to 0xFF800080,
        "brown" to 0xFFA52A2A, "gray" to 0xFF808080, "grey" to 0xFF808080, "silver" to 0xFFC0C0C0,
        "maroon" to 0xFF800000, "navy" to 0xFF000080, "teal" to 0xFF008080, "olive" to 0xFF808000,
        "lime" to 0xFF00FF00, "aqua" to 0xFF00FFFF, "cyan" to 0xFF00FFFF, "fuchsia" to 0xFFFF00FF,
        "magenta" to 0xFFFF00FF, "pink" to 0xFFFFC0CB, "gold" to 0xFFFFD700, "goldenrod" to 0xFFDAA520,
        "darkgoldenrod" to 0xFFB8860B, "crimson" to 0xFFDC143C, "indigo" to 0xFF4B0082, "violet" to 0xFFEE82EE,
        "tomato" to 0xFFFF6347, "coral" to 0xFFFF7F50, "salmon" to 0xFFFA8072, "khaki" to 0xFFF0E68C,
        "darkgreen" to 0xFF006400, "darkred" to 0xFF8B0000, "darkblue" to 0xFF00008B, "darkorange" to 0xFFFF8C00,
        "darkviolet" to 0xFF9400D3, "darkcyan" to 0xFF008B8B, "darkgray" to 0xFFA9A9A9, "darkgrey" to 0xFFA9A9A9,
        "dimgray" to 0xFF696969, "dimgrey" to 0xFF696969, "lightgray" to 0xFFD3D3D3, "lightgrey" to 0xFFD3D3D3,
        "slategray" to 0xFF708090, "steelblue" to 0xFF4682B4, "royalblue" to 0xFF4169E1, "dodgerblue" to 0xFF1E90FF,
        "seagreen" to 0xFF2E8B57, "forestgreen" to 0xFF228B22, "firebrick" to 0xFFB22222, "chocolate" to 0xFFD2691E,
        "sienna" to 0xFFA0522D, "orchid" to 0xFFDA70D6, "plum" to 0xFFDDA0DD, "tan" to 0xFFD2B48C,
        "deeppink" to 0xFFFF1493, "hotpink" to 0xFFFF69B4, "skyblue" to 0xFF87CEEB, "limegreen" to 0xFF32CD32,
        "olivedrab" to 0xFF6B8E23, "cadetblue" to 0xFF5F9EA0, "midnightblue" to 0xFF191970, "rebeccapurple" to 0xFF663399,
    )

    /** ARGB color, or null if the value is not a plain color. "transparent" returns 0. */
    fun parse(value: String?): Int? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v == "transparent") return 0
        NAMED[v]?.let { return it.toInt() }
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return when (hex.length) {
                3 -> (0xFF shl 24) or expand(hex)
                4 -> (expandNibble(hex[3]) shl 24) or expand(hex.substring(0, 3))
                6 -> (0xFF shl 24) or hex.toInt(16)
                8 -> (hex.substring(6, 8).toInt(16) shl 24) or hex.substring(0, 6).toInt(16)
                else -> null
            }
        }
        val m = Regex("^rgba?\\(([^)]*)\\)$").find(v) ?: return null
        val parts = m.groupValues[1].split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val rgb = parts.take(3).map { channel(it) ?: return null }
        val alpha = parts.getOrNull(3)?.let { a ->
            if (a.endsWith("%")) a.dropLast(1).toFloatOrNull()?.div(100f) else a.toFloatOrNull()
        } ?: 1f
        return ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }

    private fun channel(s: String): Int? =
        if (s.endsWith("%")) s.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).toInt().coerceIn(0, 255) }
        else s.toFloatOrNull()?.toInt()?.coerceIn(0, 255)

    private fun expandNibble(c: Char): Int = c.digitToInt(16) * 17

    private fun expand(rgb: String): Int = (expandNibble(rgb[0]) shl 16) or (expandNibble(rgb[1]) shl 8) or expandNibble(rgb[2])
}
