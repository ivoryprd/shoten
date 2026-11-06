package io.github.ivoryprd.shoten.render

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import io.github.ivoryprd.shoten.Prefs
import io.github.ivoryprd.shoten.dict.DictionaryDb
import io.github.ivoryprd.shoten.lookup.Furigana
import io.github.ivoryprd.shoten.lookup.KanjiRow
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.LookupResult
import io.github.ivoryprd.shoten.lookup.Pinyin
import io.github.ivoryprd.shoten.lookup.TermGroup
import io.github.ivoryprd.shoten.lookup.TermMetaRow
import io.github.ivoryprd.shoten.util.Cjk
import java.util.Locale

class RenderedEntry(val dictionaryTitle: String, val tags: CharSequence?, val blocks: List<GlossBlock>)

class RenderedGroup(
    val header: CharSequence,
    /** Pinyin line for Chinese entries. */
    val subtitle: CharSequence?,
    val copyText: String,
    val reasons: CharSequence?,
    val badges: CharSequence?,
    val entries: List<RenderedEntry>,
    val locale: Locale,
)

class RenderedKanji(val character: String, val body: CharSequence, val dictionaryTitle: String)

class RenderedResult(val result: LookupResult, val groups: List<RenderedGroup>, val kanji: List<RenderedKanji>)

/** Prepares lookup results for display (safe to call off the main thread). */
class ResultPresenter(private val theme: RenderTheme, private val prefs: Prefs, private val links: LinkHandler) {
    private val glossary = GlossaryRenderer(theme, links)

    fun present(result: LookupResult, maxGroups: Int = Int.MAX_VALUE): RenderedResult {
        // Kanji in headwords open their entries if there is a kanji dictionary.
        val kanjiLinks = result.kanji.isNotEmpty()
        val groups = result.groups.take(maxGroups).map { presentGroup(it, result.language, kanjiLinks) }
        val kanji = result.kanji.map { presentKanji(it) }
        return RenderedResult(result, groups, kanji)
    }

    private fun presentGroup(g: TermGroup, language: Language, kanjiLinks: Boolean): RenderedGroup {
        val chinese = language == Language.CHINESE || g.entries.first().row.dictionary.language == Language.CHINESE
        val locale = when {
            !chinese -> Locale.JAPANESE
            g.alt != null && looksTraditional(g.expression, g.alt) -> Locale.TRADITIONAL_CHINESE
            else -> Locale.SIMPLIFIED_CHINESE
        }
        val header = SpannableStringBuilder()
        var subtitle: CharSequence? = null
        if (chinese) {
            header.append(g.expression)
            if (g.alt != null && g.alt != g.expression) {
                val s = header.length
                header.append("  ").append(g.alt)
                header.setSpan(ForegroundColorSpan(theme.mutedColor), s, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                header.setSpan(RelativeSizeSpan(0.8f), s, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            subtitle = pinyin(g.reading, g.entries.first().row.dictionary.readingFormat == "pinyin-numbered")
        } else {
            for (segment in Furigana.distribute(g.expression, g.reading)) {
                val s = header.length
                header.append(segment.text)
                if (segment.ruby != null) {
                    header.setSpan(RubySpan(segment.ruby, 0.5f, theme.mutedColor), s, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (kanjiLinks) {
                var i = 0
                while (i < header.length) {
                    val cp = Character.codePointAt(header, i)
                    val n = Character.charCount(cp)
                    if (Cjk.isHan(cp)) {
                        val character = String(Character.toChars(cp))
                        header.setSpan(ActionSpan(null, false) { links.onLookupKanji(character) }, i, i + n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    i += n
                }
            }
        }

        val reasons = if (g.reasons.isEmpty()) null else SpannableStringBuilder(g.reasons.joinToString(" › ")).also {
            it.setSpan(ForegroundColorSpan(theme.mutedColor), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            it.setSpan(StyleSpan(Typeface.ITALIC), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val entries = g.entries.map { match ->
            val row = match.row
            val tags = SpannableStringBuilder()
            val tagNames = (row.definitionTags.split(' ') + row.termTags.split(' ')).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            for (name in tagNames) {
                val info = row.dictionary.tag(name)
                chip(tags, DictionaryDb.normalizeTagName(name), tagColor(info?.category))
            }
            RenderedEntry(displayTitle(row.dictionary.title), if (tags.isEmpty()) null else tags, glossary.render(row))
        }

        return RenderedGroup(header, subtitle, g.expression, reasons, badges(g), entries, locale)
    }

    private fun badges(g: TermGroup): CharSequence? {
        if (g.meta.isEmpty()) return null
        val sb = SpannableStringBuilder()
        for (meta in g.meta) {
            when (meta.mode) {
                "freq" -> if (prefs.showFrequencies) frequency(meta)?.let { chip(sb, "${shortTitle(meta)} $it", theme.chipBackground) }
                "pitch" -> if (prefs.showPitch) pitches(meta, g.reading).forEach { chip(sb, it, theme.chipBackground) }
            }
        }
        return if (sb.isEmpty()) null else sb
    }

    /** The title without a trailing revision, e.g. "Jitendex.org [2025-10-01]" becomes "Jitendex.org". */
    private fun displayTitle(title: String): String = title.replace(REVISION_SUFFIX, "").ifBlank { title }

    private fun shortTitle(meta: TermMetaRow): String {
        val title = meta.dictionary.title
        return if (title.length > 14) title.take(13) + "…" else title
    }

    private fun frequency(meta: TermMetaRow): String? {
        fun value(v: Any?): String? = when (v) {
            is Number -> v.toLong().toString()
            is String -> v
            is Map<*, *> -> (v["displayValue"] as? String) ?: (v["value"] as? Number)?.toLong()?.toString()
            else -> null
        }
        val data = meta.data
        return if (data is Map<*, *> && data.containsKey("frequency")) value(data["frequency"]) else value(data)
    }

    private fun pitches(meta: TermMetaRow, reading: String): List<String> {
        val data = meta.data as? Map<*, *> ?: return emptyList()
        val r = data["reading"] as? String ?: reading
        val list = data["pitches"] as? List<*> ?: return emptyList()
        return list.mapNotNull { p ->
            when (val position = (p as? Map<*, *>)?.get("position")) {
                is Number -> downstep(r, position.toInt())
                is String -> "$r $position"
                else -> null
            }
        }.distinct()
    }

    /** Marks the accent position, e.g. はし with downstep 1 becomes "はꜜし [1]". */
    private fun downstep(reading: String, position: Int): String {
        val morae = morae(reading)
        if (position <= 0 || position > morae.size) return "${morae.joinToString("")} [$position]"
        return morae.take(position).joinToString("") + DOWNSTEP + morae.drop(position).joinToString("") + " [$position]"
    }

    private fun morae(reading: String): List<String> {
        val out = ArrayList<String>()
        for (ch in reading) {
            if (ch in SMALL_KANA && out.isNotEmpty()) out[out.size - 1] = out.last() + ch else out.add(ch.toString())
        }
        return out
    }

    private fun presentKanji(k: KanjiRow): RenderedKanji {
        val sb = SpannableStringBuilder()
        fun muted(text: String, size: Float = 1f) {
            val s = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(theme.mutedColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (size != 1f) sb.setSpan(RelativeSizeSpan(size), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun newLine() {
            if (sb.isNotEmpty()) sb.append('\n')
        }
        if (k.meanings.isNotEmpty()) {
            val s = sb.length
            sb.append(k.meanings.joinToString("; "))
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val on = k.onyomi.split(' ').filter { it.isNotEmpty() }
        if (on.isNotEmpty()) {
            newLine()
            muted("On  ")
            sb.append(on.joinToString("、"))
        }
        val kun = k.kunyomi.split(' ').filter { it.isNotEmpty() }
        if (kun.isNotEmpty()) {
            newLine()
            muted("Kun  ")
            kun.forEachIndexed { i, reading ->
                if (i > 0) sb.append("、")
                // The okurigana (after the dot) in muted color: つよ.い → つよい.
                val dot = reading.indexOf('.')
                if (dot < 0) {
                    sb.append(reading)
                } else {
                    sb.append(reading, 0, dot)
                    muted(reading.substring(dot + 1))
                }
            }
        }
        val facts = kanjiFacts(k)
        if (facts.isNotEmpty()) {
            newLine()
            muted(facts.joinToString("  ·  "), 0.85f)
        }
        return RenderedKanji(k.character, sb, k.dictionary.title)
    }

    /**
     * The stats of [k] worth showing: strokes, school grade, JLPT level, frequency rank, and other
     * general ones; not the indices of other dictionaries and the codes of classifications
     * (KANJIDIC has dozens).
     */
    private fun kanjiFacts(k: KanjiRow): List<String> {
        val out = ArrayList<String>()
        val stats = k.stats
        stats["strokes"]?.let { out += "$it strokes" }
        val grade = stats["grade"]?.toIntOrNull()
        val tags = k.tags.split(' ')
        when {
            grade != null && grade in 1..6 -> out += "Grade $grade"
            grade == 8 || "jouyou" in tags -> out += "Jōyō"
            grade == 9 || grade == 10 || "jinmeiyou" in tags -> out += "Jinmeiyō"
        }
        // KANJIDIC still has the levels of the old JLPT (1–4).
        stats["jlpt"]?.let { out += "JLPT $it (old)" }
        stats["freq"]?.let { out += "Frequency #$it" }
        for ((key, value) in stats) {
            if (key in KANJI_FACTS) continue
            val tag = k.dictionary.tag(key)
            if (tag != null && tag.category != "misc") continue
            out += "${tag?.notes?.takeIf { it.isNotBlank() } ?: key}: $value"
        }
        return out
    }

    private fun pinyin(reading: String, numbered: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        val syllables = if (numbered || Pinyin.looksNumbered(reading)) {
            Pinyin.syllables(reading)
        } else {
            reading.split(' ').filter { it.isNotEmpty() }.map { Pinyin.Syllable(it, toneOfMarked(it)) }
        }
        syllables.forEachIndexed { i, syllable ->
            if (i > 0) sb.append(' ')
            val s = sb.length
            sb.append(syllable.text)
            TONE_COLORS.getOrNull(syllable.tone)?.let { color ->
                sb.setSpan(ForegroundColorSpan(adjustTone(color)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun adjustTone(color: Int): Int = if (theme.dark) {
        Color.rgb((Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2)
    } else {
        color
    }

    private fun toneOfMarked(syllable: String): Int {
        for (c in syllable) {
            val tone = MARKED_TONES[c]
            if (tone != null) return tone
        }
        return 5
    }

    private fun chip(sb: SpannableStringBuilder, text: String, background: Int) {
        val start = sb.length
        sb.append(text)
        val fg = if ((0.2126f * Color.red(background) + 0.7152f * Color.green(background) + 0.0722f * Color.blue(background)) / 255f > 0.6f) {
            Color.BLACK
        } else {
            Color.WHITE
        }
        sb.setSpan(
            ChipSpan(background, if (background == theme.chipBackground) theme.chipText else fg, 0.8f, false, theme.dp(5f), theme.dp(2f), theme.dp(5f), theme.dp(5f)),
            start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        // Zero-width separator so that chips can wrap between each other.
        sb.append(8203.toChar())
    }

    private fun tagColor(category: String?): Int = when (category) {
        "popular" -> theme.accentColor
        "frequent" -> 0xFF2E7D32.toInt()
        "archaism" -> 0xFF8D6E63.toInt()
        "partOfSpeech" -> 0xFF565656.toInt()
        "name" -> 0xFF6A1B9A.toInt()
        "expression" -> 0xFF00838F.toInt()
        else -> theme.chipBackground
    }

    /**
     * Whether a Chinese headword is in traditional characters, used only to pick matching glyph
     * shapes (the font locale). Checks for common characters that exist only in traditional Chinese.
     */
    private fun looksTraditional(expression: String, alt: String): Boolean =
        expression != alt && expression.any { it in TRADITIONAL_HINTS } && alt.none { it in TRADITIONAL_HINTS }

    companion object {
        private val DOWNSTEP = 0xA71C.toChar().toString()
        private val REVISION_SUFFIX = Regex("\\s*\\[[^\\]]*]\\s*$")
        private val KANJI_FACTS = setOf("strokes", "grade", "jlpt", "freq")
        private val SMALL_KANA = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ".toSet()
        private val TONE_COLORS = listOf(null, 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF757575.toInt())
        private val MARKED_TONES: Map<Char, Int> = buildMap {
            listOf("āēīōūǖĀĒĪŌŪǕ", "áéíóúǘÁÉÍÓÚǗ", "ǎěǐǒǔǚǍĚǏǑǓǙ", "àèìòùǜÀÈÌÒÙǛ").forEachIndexed { i, chars ->
                chars.forEach { put(it, i + 1) }
            }
        }

        /** Common characters that only exist in traditional Chinese. */
        private const val TRADITIONAL_HINTS = "們個來這說時國會學對還過麼沒裡開關與為從當經長門問間現發見電話車書東無點萬實寫歡麗體聽讀機業應樣邊頭題總給親樂員氣變龍語覺進運動區處議認識續傳統圖館"
    }
}
