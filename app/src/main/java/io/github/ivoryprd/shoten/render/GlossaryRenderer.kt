package io.github.ivoryprd.shoten.render

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LocaleSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import io.github.ivoryprd.shoten.lookup.DictionaryRef
import io.github.ivoryprd.shoten.lookup.Pinyin
import io.github.ivoryprd.shoten.lookup.TermRow
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.ceil

/** Colors and sizes used to render dictionary content. */
class RenderTheme(
    val density: Float,
    /** Base text size in pixels. */
    val textSize: Float,
    val textColor: Int,
    val mutedColor: Int,
    val accentColor: Int,
    val linkColor: Int,
    val chipBackground: Int,
    val chipText: Int,
    val dark: Boolean,
) {
    fun dp(v: Float): Float = v * density
}

/** A rendered piece of a glossary. */
sealed class GlossBlock {
    class Text(val text: CharSequence) : GlossBlock()

    class Table(val rows: List<List<Cell>>) : GlossBlock() {
        class Cell(val text: CharSequence, val header: Boolean, val colSpan: Int)
    }

    class Image(val dictionary: DictionaryRef, val path: String, val widthDp: Float?, val heightDp: Float?, val description: String?) : GlossBlock()
}

/** What happens when links in rendered content are tapped. */
interface LinkHandler {
    fun onLookup(query: String)

    /** Shows the kanji dictionaries' entries for [character]. */
    fun onLookupKanji(character: String) {}

    fun onOpenUrl(url: String)
    fun onShowNote(text: String)
}

/**
 * Renders Yomitan glossaries (plain strings and structured content) into Android text spans.
 * Rendering builds plain objects only, so it can run on a background thread.
 */
class GlossaryRenderer(private val theme: RenderTheme, private val links: LinkHandler) {
    private val styleCache = HashMap<String, StyleSheet>()
    private val markerPaint = TextPaint().apply { textSize = theme.textSize }

    fun render(row: TermRow): List<GlossBlock> {
        val styles = styleCache.getOrPut(row.dictionary.id) { StyleSheet.parse(row.dictionary.styles.orEmpty() + HOUSE_STYLES) }
        val ctx = Context(row.dictionary, styles)
        val items = row.glossary
        val hasStructured = items.any { (it as? Map<*, *>)?.get("type") == "structured-content" }
        val numbered = items.count { it is String || (it as? Map<*, *>)?.get("type") == "text" } > 1
        var number = 0
        for (item in items) {
            when (item) {
                is String -> ctx.textItem(item, if (numbered) ++number else 0)
                is Map<*, *> -> when (item["type"]) {
                    "text" -> ctx.textItem(item["text"] as? String ?: "", if (numbered) ++number else 0)
                    "structured-content" -> {
                        ctx.ensureParagraph()
                        ctx.node(item["content"], 0)
                        ctx.ensureParagraph()
                    }
                    "image" -> ctx.image(item)
                }
                is List<*> -> if (!hasStructured) ctx.redirect(item)
            }
        }
        ctx.flush()
        return ctx.blocks
    }

    private inner class Context(val dictionary: DictionaryRef, val styles: StyleSheet) {
        val blocks = ArrayList<GlossBlock>()
        var sb = SpannableStringBuilder()
        private val pinyin = dictionary.readingFormat == "pinyin-numbered"

        /** Open lists: whether ordered, the inherited list-style-type, and the item counter. */
        private inner class ListState(val ordered: Boolean, val styleType: String?, val content: String?) {
            var counter = 0
        }

        private val lists = ArrayList<ListState>()
        private var inCell = 0

        fun flush() {
            trimTrailingNewlines(sb)
            if (sb.isNotEmpty()) blocks.add(GlossBlock.Text(sb))
            sb = SpannableStringBuilder()
        }

        fun ensureParagraph() {
            if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
        }

        fun textItem(text: String, number: Int) {
            ensureParagraph()
            val start = sb.length
            sb.append(if (pinyin) Pinyin.convertBracketed(text) else text)
            sb.append('\n')
            if (number > 0) {
                nestedSpan(numberMarker(number), start, sb.length, 0)
                if (number > 1) spaceAt(start, theme.dp(SENSE_SPACING).toInt())
            }
        }

        /**
         * Adds vertical space at the paragraph break before [position] (which must follow a line
         * break). Like CSS margins, adjacent spaces collapse into the larger one.
         */
        private fun spaceAt(position: Int, space: Int) {
            if (space <= 0 || position <= 0 || position > sb.length || sb[position - 1] != '\n') return
            val existing = sb.getSpans(position - 1, position, SpaceBelowSpan::class.java).firstOrNull { sb.getSpanEnd(it) == position }
            if (existing != null) {
                existing.space = maxOf(existing.space, space)
            } else {
                sb.setSpan(SpaceBelowSpan(space), position - 1, position, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        /**
         * Sets a span of an element at [depth]. Spans of outer elements come first: paragraph spans
         * indent in that order, and character styles of inner elements are applied last, so they win.
         */
        private fun nestedSpan(span: Any, start: Int, end: Int, depth: Int) {
            val priority = (255 - depth).coerceIn(0, 255) shl Spanned.SPAN_PRIORITY_SHIFT
            sb.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or priority)
        }

        fun redirect(item: List<*>) {
            val term = item.firstOrNull() as? String ?: return
            val reasons = (item.getOrNull(1) as? List<*>)?.filterIsInstance<String>().orEmpty()
            ensureParagraph()
            sb.append("→ ")
            val start = sb.length
            sb.append(term)
            sb.setSpan(ActionSpan(theme.linkColor, false) { links.onLookup(term) }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (reasons.isNotEmpty()) {
                val s = sb.length
                sb.append("  ").append(reasons.joinToString(", "))
                sb.setSpan(ForegroundColorSpan(theme.mutedColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append('\n')
        }

        fun image(item: Map<*, *>) {
            val path = item["path"] as? String ?: return
            if (inCell > 0) return
            flush()
            val units = item["sizeUnits"] as? String
            fun size(key: String): Float? {
                val v = (item[key] as? Number)?.toFloat() ?: return null
                return if (units == "em") v * theme.textSize / theme.density else v
            }
            blocks.add(GlossBlock.Image(dictionary, path, size("width"), size("height"), (item["description"] ?: item["alt"] ?: item["title"]) as? String))
        }

        fun node(node: Any?, depth: Int) {
            if (depth > MAX_DEPTH) return
            when (node) {
                null -> Unit
                is String -> sb.append(node)
                is List<*> -> node.forEach { node(it, depth + 1) }
                is Map<*, *> -> element(node, depth)
                else -> sb.append(node.toString())
            }
        }

        private fun element(e: Map<*, *>, depth: Int) {
            val tag = (e["tag"] as? String)?.lowercase()
            if (tag == null) {
                node(e["content"], depth + 1)
                return
            }
            val attributes = attributesOf(tag, e)
            val style = HashMap(styles.compute(tag, attributes))
            (e["style"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v != null) style[cssName(k)] = v.toString() }
            if (style["display"]?.trim() == "none") return

            when (tag) {
                "br" -> { sb.append('\n'); return }
                "rp" -> return
                "rt" -> return
                "ruby" -> { ruby(e, depth); return }
                "img" -> { image(e); return }
                "table" -> { table(e, depth); return }
            }

            val block = tag in BLOCK_TAGS
            if (block) ensureParagraph()
            // Horizontal margins of inline text become spaces (except for chips, which have their own).
            val inlineMargins = !block && style["background-color"] == null && style["background"] == null
            if (inlineMargins && cssLength(style["margin-left"]) > 0 && sb.isNotEmpty() && !sb[sb.length - 1].isWhitespace()) sb.append(' ')
            val start = sb.length
            val content = attributes["data-sc-content"]
            val isList = tag == "ul" || tag == "ol"
            var marker: String? = null
            var spaceBefore = if (block) cssLength(style["margin-top"]) else 0
            val spaceAfter = if (block) cssLength(style["margin-bottom"]) else 0
            if (tag == "li") {
                val list = lists.lastOrNull()
                marker = listMarker(style)
                // Separate list items that are senses or groups of senses.
                if (list != null && list.counter > 1) {
                    when {
                        list.content == "sense-groups" -> spaceBefore = maxOf(spaceBefore, theme.dp(GROUP_SPACING).toInt())
                        numberOf(marker) != null -> spaceBefore = maxOf(spaceBefore, theme.dp(SENSE_SPACING).toInt())
                    }
                }
            }
            if (isList) lists.add(ListState(tag == "ol", style["list-style-type"]?.trim(), content))
            try {
                if (isList && isInlineList(e, content)) inlineList(e, depth) else node(e["content"], depth + 1)
            } finally {
                if (isList) lists.removeAt(lists.size - 1)
            }
            if (block) ensureParagraph()
            val end = sb.length
            if (end <= start) return

            if (tag == "a") link(e, start, end, style, depth)
            if (tag == "summary") nestedSpan(StyleSpan(Typeface.BOLD), start, end, depth)
            if (marker != null) {
                val number = numberOf(marker)
                when {
                    number != null -> nestedSpan(numberMarker(number), start, end, depth)
                    // Jitendex marks groups of senses with "＊"; the part of speech tags already show them.
                    lists.lastOrNull()?.content == "sense-groups" -> Unit
                    else -> nestedSpan(marker(marker, 0, theme.mutedColor), start, end, depth)
                }
            }
            if (block && tag != "li" && hasBorderBox(style)) {
                val color = CssColors.parse(style["border-color"] ?: style["border-left-color"])?.takeIf { Color.alpha(it) > 0 }
                    ?: withAlpha(theme.mutedColor, 0.45f)
                nestedSpan(BarSpan(color, theme.dp(2.5f).toInt(), theme.dp(10f).toInt()), start, end, depth)
            }
            if (block) {
                spaceAt(start, spaceBefore)
                spaceAt(end, spaceAfter)
            }
            if (block) {
                when (style["text-align"]?.trim()) {
                    "right", "end" -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    "center" -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            (e["lang"] as? String)?.let { lang ->
                runCatching { Locale.forLanguageTag(lang) }.getOrNull()?.let {
                    sb.setSpan(LocaleSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            applyCharacterStyle(style, start, end, block, e["title"] as? String, content, depth)
            if (inlineMargins && cssLength(style["margin-right"]) > 0) sb.append(' ')
        }

        /**
         * Whether a list is shown on one line, with its items separated by semicolons: lists of
         * glosses ("to see; to look; to watch"), and other short lists inside list items.
         */
        private fun isInlineList(e: Map<*, *>, content: String?): Boolean {
            val items = children(e["content"])
            if (items.isEmpty()) return false
            for (item in items) {
                val li = item as? Map<*, *> ?: return false
                if ((li["tag"] as? String)?.lowercase() != "li" || hasBlockContent(li["content"], 0)) return false
            }
            if (content == "glossary") return true
            return lists.isNotEmpty() && items.size > 1 && items.all { plainText((it as Map<*, *>)["content"]).length <= SHORT_ITEM }
        }

        private fun inlineList(e: Map<*, *>, depth: Int) {
            val items = children(e["content"]).filterIsInstance<Map<*, *>>()
            val texts = items.map { plainText(it["content"]) }
            // Japanese and Chinese lists use the ideographic comma.
            val separator = if (texts.all { t -> t.none { it in 'A'..'Z' || it in 'a'..'z' } }) "、" else "; "
            var first = true
            for (li in items) {
                val attributes = attributesOf("li", li)
                val style = HashMap(styles.compute("li", attributes))
                (li["style"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v != null) style[cssName(k)] = v.toString() }
                if (style["display"]?.trim() == "none") continue
                if (!first) {
                    val s = sb.length
                    sb.append(separator)
                    sb.setSpan(ForegroundColorSpan(theme.mutedColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                first = false
                val start = sb.length
                node(li["content"], depth + 2)
                if (sb.length > start) applyCharacterStyle(style, start, sb.length, false, li["title"] as? String, attributes["data-sc-content"], depth + 1)
            }
        }

        private fun hasBlockContent(node: Any?, depth: Int): Boolean {
            if (depth > MAX_DEPTH) return true
            return when (node) {
                is List<*> -> node.any { hasBlockContent(it, depth + 1) }
                is Map<*, *> -> {
                    val tag = (node["tag"] as? String)?.lowercase()
                    tag in BLOCK_TAGS || tag == "table" || tag == "img" || tag == "br" || hasBlockContent(node["content"], depth + 1)
                }
                else -> false
            }
        }

        private fun ruby(e: Map<*, *>, depth: Int) {
            val parts = (e["content"] as? List<*>) ?: listOf(e["content"])
            val base = StringBuilder()
            for (part in parts) {
                val tag = ((part as? Map<*, *>)?.get("tag") as? String)?.lowercase()
                when (tag) {
                    "rt" -> {
                        val reading = plainText((part as Map<*, *>)["content"])
                        if (base.isNotEmpty()) {
                            val start = sb.length
                            sb.append(base)
                            if (reading.isNotBlank()) {
                                sb.setSpan(RubySpan(reading, rubyColor = null), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            base.setLength(0)
                        }
                    }
                    "rp" -> Unit
                    else -> base.append(plainText(part))
                }
            }
            if (base.isNotEmpty()) sb.append(base)
        }

        private fun table(e: Map<*, *>, depth: Int) {
            val rows = ArrayList<List<GlossBlock.Table.Cell>>()
            fun collectRows(n: Any?) {
                when (n) {
                    is List<*> -> n.forEach { collectRows(it) }
                    is Map<*, *> -> when ((n["tag"] as? String)?.lowercase()) {
                        "tr" -> rows.add(cells(n, depth))
                        "thead", "tbody", "tfoot" -> collectRows(n["content"])
                        else -> Unit
                    }
                }
            }
            collectRows(e["content"])
            if (rows.isEmpty()) return
            if (inCell > 0) {
                // Nested table: render rows as lines.
                for (row in rows) {
                    ensureParagraph()
                    row.forEachIndexed { i, cell -> if (i > 0) sb.append("  "); sb.append(cell.text) }
                }
                ensureParagraph()
                return
            }
            flush()
            blocks.add(GlossBlock.Table(rows))
        }

        private fun cells(tr: Map<*, *>, depth: Int): List<GlossBlock.Table.Cell> {
            val out = ArrayList<GlossBlock.Table.Cell>()
            val content = (tr["content"] as? List<*>) ?: listOf(tr["content"])
            for (c in content) {
                val cell = c as? Map<*, *> ?: continue
                val tag = (cell["tag"] as? String)?.lowercase()
                if (tag != "td" && tag != "th") continue
                val saved = sb
                sb = SpannableStringBuilder()
                inCell++
                try {
                    node(cell["content"], depth + 1)
                } finally {
                    inCell--
                }
                trimTrailingNewlines(sb)
                if (sb.isBlank()) {
                    val cls = ((cell["data"] as? Map<*, *>)?.get("class") as? String).orEmpty()
                    FORM_SYMBOLS[cls]?.let { sb.append(it) }
                }
                val text = sb
                sb = saved
                val colSpan = (cell["colSpan"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 1
                out.add(GlossBlock.Table.Cell(text, tag == "th", colSpan))
            }
            return out
        }

        private fun link(e: Map<*, *>, start: Int, end: Int, style: Map<String, String>, depth: Int) {
            val href = e["href"] as? String ?: return
            val color = if (style.containsKey("color")) null else theme.linkColor
            val span = when {
                href.startsWith("?") -> {
                    val query = queryParameter(href.substring(1), "query") ?: return
                    ActionSpan(color, false) { links.onLookup(query) }
                }
                href.startsWith("http://") || href.startsWith("https://") -> ActionSpan(color, false) { links.onOpenUrl(href) }
                else -> return
            }
            nestedSpan(span, start, end, depth)
        }

        private fun applyCharacterStyle(style: Map<String, String>, start: Int, end: Int, block: Boolean, title: String?, content: String?, depth: Int) {
            val background = CssColors.parse(style["background-color"] ?: style["background"])?.takeIf { Color.alpha(it) > 0 }
            // Colors for Jitendex's example sentences, whose own colors depend on Yomitan's theme.
            val foreground = CssColors.parse(style["color"])?.takeIf { Color.alpha(it) > 0 } ?: when (content) {
                "example-keyword" -> theme.accentColor
                "example-sentence-b" -> theme.mutedColor
                else -> null
            }
            val size = fontScale(style["font-size"])
            val bold = isBold(style["font-weight"])
            val italic = style["font-style"]?.trim() == "italic"

            if (!block && background != null && canBeChip(start, end)) {
                val fg = foreground ?: if (isLight(background)) Color.BLACK else Color.WHITE
                nestedSpan(
                    ChipSpan(background, fg, size ?: 0.85f, bold, theme.dp(4f), theme.dp(1.5f), theme.dp(4f), theme.dp(4f)),
                    start, end, depth,
                )
                if (!title.isNullOrBlank()) nestedSpan(ActionSpan(null, false) { links.onShowNote(title) }, start, end, depth)
                return
            }
            if (foreground != null) {
                nestedSpan(ForegroundColorSpan(readable(foreground, background)), start, end, depth)
            }
            if (!block && background != null) nestedSpan(BackgroundColorSpan(background), start, end, depth)
            if (size != null && size != 1f) nestedSpan(RelativeSizeSpan(size), start, end, depth)
            if (bold && italic) {
                nestedSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, depth)
            } else if (bold) {
                nestedSpan(StyleSpan(Typeface.BOLD), start, end, depth)
            } else if (italic) {
                nestedSpan(StyleSpan(Typeface.ITALIC), start, end, depth)
            }
            val decoration = (style["text-decoration-line"] ?: style["text-decoration"]).orEmpty()
            if ("underline" in decoration) nestedSpan(UnderlineSpan(), start, end, depth)
            if ("line-through" in decoration) nestedSpan(StrikethroughSpan(), start, end, depth)
            when (style["vertical-align"]?.trim()) {
                "super" -> {
                    nestedSpan(SuperscriptSpan(), start, end, depth)
                    if (size == null) nestedSpan(RelativeSizeSpan(0.75f), start, end, depth)
                }
                "sub" -> {
                    nestedSpan(SubscriptSpan(), start, end, depth)
                    if (size == null) nestedSpan(RelativeSizeSpan(0.75f), start, end, depth)
                }
            }
            if (!title.isNullOrBlank() && !block && sb.getSpans(start, end, ActionSpan::class.java).isEmpty()) {
                nestedSpan(ActionSpan(null, false) { links.onShowNote(title) }, start, end, depth)
            }
        }

        private fun canBeChip(start: Int, end: Int): Boolean {
            if (end - start > 40) return false
            for (i in start until end) if (sb[i] == '\n') return false
            return sb.getSpans(start, end, ReplacementSpan::class.java).isEmpty()
        }

        private fun listMarker(style: Map<String, String>): String {
            val list = lists.lastOrNull()
            val counter = list?.let { ++it.counter } ?: 1
            val type = style["list-style-type"]?.trim() ?: list?.styleType ?: if (list?.ordered == true) "decimal" else "disc"
            return when {
                type.length >= 2 && (type.startsWith("\"") && type.endsWith("\"") || type.startsWith("'") && type.endsWith("'")) ->
                    type.substring(1, type.length - 1)
                type == "none" -> ""
                type == "circle" -> "◦"
                type == "square" -> "▪"
                type == "disc" -> "•"
                type == "decimal" -> "$counter."
                type == "lower-alpha" || type == "lower-latin" -> if (counter <= 26) "${'a' + counter - 1}." else "$counter."
                type == "upper-alpha" || type == "upper-latin" -> if (counter <= 26) "${'A' + counter - 1}." else "$counter."
                else -> "•"
            }
        }

        private fun marker(marker: String, indent: Int, color: Int?): MarkerSpan {
            val width = if (marker.isEmpty()) 0 else ceil(markerPaint.measureText(marker)).toInt()
            return MarkerSpan(marker, indent, width, if (marker.isEmpty()) theme.dp(4f).toInt() else theme.dp(6f).toInt(), color)
        }

        private fun numberMarker(number: Int) = NumberMarkerSpan(
            number.toString(), theme.accentColor, withAlpha(theme.accentColor, if (theme.dark) 0.24f else 0.14f),
            (theme.textSize * 1.25f).toInt(), theme.dp(8f).toInt(),
        )
    }

    private fun attributesOf(tag: String, e: Map<*, *>): Map<String, String> {
        val out = HashMap<String, String>()
        (e["data"] as? Map<*, *>)?.forEach { (k, v) ->
            if (k is String && v != null) out["data-sc-" + cssName(k)] = v.toString()
        }
        (e["title"] as? String)?.let { out["title"] = it }
        (e["lang"] as? String)?.let { out["lang"] = it }
        (e["href"] as? String)?.let { out["href"] = it }
        return out
    }

    /** A CSS length (margins) in pixels, limited to a sensible range. */
    private fun cssLength(value: String?): Int {
        val v = value?.trim()?.lowercase() ?: return 0
        val px = when {
            v.endsWith("rem") -> v.dropLast(3).toFloatOrNull()?.times(theme.textSize)
            v.endsWith("em") -> v.dropLast(2).toFloatOrNull()?.times(theme.textSize)
            v.endsWith("px") -> v.dropLast(2).toFloatOrNull()?.let { theme.dp(it) }
            else -> v.toFloatOrNull()?.takeIf { it == 0f }
        } ?: return 0
        return px.coerceIn(0f, theme.dp(16f)).toInt()
    }

    private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((Color.alpha(color) * alpha).toInt() shl 24)

    private fun hasBorderBox(style: Map<String, String>): Boolean {
        val borderStyle = style["border-style"] ?: style["border-left-style"] ?: return style.containsKey("border-left")
        return borderStyle.split(' ').any { it == "solid" || it == "dashed" || it == "dotted" || it == "double" }
    }

    private fun fontScale(value: String?): Float? {
        val v = value?.trim()?.lowercase() ?: return null
        val scale = when {
            v.endsWith("rem") -> v.dropLast(3).toFloatOrNull()
            v.endsWith("em") -> v.dropLast(2).toFloatOrNull()
            v.endsWith("%") -> v.dropLast(1).toFloatOrNull()?.div(100f)
            v.endsWith("px") -> v.dropLast(2).toFloatOrNull()?.div(14f)
            v == "xx-small" -> 0.6f
            v == "x-small" -> 0.7f
            v == "small" || v == "smaller" -> 0.85f
            v == "medium" -> 1f
            v == "large" || v == "larger" -> 1.15f
            v == "x-large" -> 1.35f
            v == "xx-large" -> 1.6f
            else -> null
        } ?: return null
        return scale.coerceIn(0.5f, 2f)
    }

    private fun isBold(value: String?): Boolean {
        val v = value?.trim() ?: return false
        return v == "bold" || v == "bolder" || (v.toIntOrNull() ?: 0) >= 600
    }

    private fun isLight(color: Int): Boolean = luminance(color) > 0.6f

    private fun luminance(color: Int): Float =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f

    /** Lightens or darkens text colors that would be unreadable on the current background. */
    private fun readable(color: Int, background: Int?): Int {
        if (background != null) return color
        val l = luminance(color)
        return when {
            theme.dark && l < 0.3f -> blend(color, Color.WHITE, 0.45f)
            !theme.dark && l > 0.85f -> blend(color, Color.BLACK, 0.45f)
            else -> color
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    companion object {
        private const val MAX_DEPTH = 200

        /** Space between senses and between groups of senses, in dp. */
        private const val SENSE_SPACING = 6f
        private const val GROUP_SPACING = 12f

        /** Items of nested lists up to this length are shown on one line. */
        private const val SHORT_ITEM = 40

        /**
         * Adjustments to dictionary styles for a small screen, applied after the dictionary's own
         * styles: Jitendex's example sentences and references are a bit large for a pop-up.
         */
        private const val HOUSE_STYLES = """
            div[data-sc-content="example-sentence-a"] { font-size: 1.1em; }
            div[data-sc-content="example-sentence-b"] { font-size: 0.9em; }
            div[data-sc-content="xref-content"], div[data-sc-content="antonym-content"] { font-size: 1.05em; }
            div[data-sc-content="xref-glossary"], div[data-sc-content="antonym-glossary"] { font-size: 0.9em; }
        """

        /** The number of a numeric list marker ("3.", "③"), or null. */
        fun numberOf(marker: String?): Int? {
            if (marker.isNullOrEmpty()) return null
            if (marker.endsWith('.')) return marker.dropLast(1).toIntOrNull()?.takeIf { it > 0 }
            if (marker.length != 1) return null
            val c = marker[0].code
            return when (c) {
                in 0x2460..0x2473 -> c - 0x2460 + 1 // ① to ⑳
                in 0x3251..0x325F -> c - 0x3251 + 21 // ㉑ to ㉟
                in 0x32B1..0x32BF -> c - 0x32B1 + 36 // ㊱ to ㊿
                in 0x2776..0x277F -> c - 0x2776 + 1 // ❶ to ❿
                in 0x24F5..0x24FE -> c - 0x24F5 + 1 // ⓵ to ⓾
                else -> null
            }
        }

        private fun children(node: Any?): List<Any?> = when (node) {
            null -> emptyList()
            is List<*> -> node.filter { it !is String || it.isNotBlank() }
            else -> listOf(node)
        }

        private val BLOCK_TAGS = setOf(
            "div", "p", "ol", "ul", "li", "details", "summary", "section", "blockquote",
            "h1", "h2", "h3", "h4", "h5", "h6", "dl", "dt", "dd", "header", "footer", "article",
        )

        /** Symbols for Jitendex's empty "forms" table cells (drawn by its CSS in Yomitan). */
        private val FORM_SYMBOLS = mapOf(
            "form-valid" to "◇", "form-pri" to "△", "form-irr" to "✕", "form-out" to "古",
            "form-old" to "旧", "form-rare" to "▽",
        )

        fun cssName(name: String): String = buildString {
            for (c in name) {
                if (c.isUpperCase()) append('-').append(c.lowercaseChar()) else append(c)
            }
        }

        fun plainText(node: Any?): String = when (node) {
            null -> ""
            is String -> node
            is List<*> -> node.joinToString("") { plainText(it) }
            is Map<*, *> -> {
                val tag = (node["tag"] as? String)?.lowercase()
                if (tag == "rt" || tag == "rp") "" else plainText(node["content"])
            }
            else -> node.toString()
        }

        fun queryParameter(query: String, name: String): String? {
            for (part in query.split('&')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                if (part.substring(0, eq) == name) {
                    return runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull()
                }
            }
            return null
        }

        fun trimTrailingNewlines(sb: SpannableStringBuilder) {
            var end = sb.length
            while (end > 0 && sb[end - 1] == '\n') end--
            if (end < sb.length) sb.delete(end, sb.length)
        }
    }
}
