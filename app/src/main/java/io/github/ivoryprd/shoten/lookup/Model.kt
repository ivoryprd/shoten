package io.github.ivoryprd.shoten.lookup

/** The language of a dictionary's headwords, which decides how text is scanned. */
enum class Language(val code: String) {
    JAPANESE("ja"),
    CHINESE("zh"),
    OTHER("");

    companion object {
        fun fromCode(code: String?): Language = when {
            code == null -> OTHER
            code.equals("ja", true) || code.startsWith("ja-", true) || code.equals("jpn", true) -> JAPANESE
            code.equals("zh", true) || code.startsWith("zh-", true) || code.equals("yue", true) ||
                code.equals("cmn", true) -> CHINESE
            else -> OTHER
        }
    }
}

data class TagInfo(val name: String, val category: String, val order: Int, val notes: String, val score: Int)

/** An installed dictionary as seen by the lookup engine. */
interface DictionaryRef {
    val id: String
    val title: String
    /** Lower values are shown first. */
    val priority: Int
    val language: Language
    /** "pinyin-numbered" for readings like "zhong1 wen2"; null otherwise. */
    val readingFormat: String?
    /** The dictionary's styles.css, if any. */
    val styles: String?

    fun decodeGlossary(data: ByteArray): List<Any?>
    fun tag(name: String): TagInfo?
}

class TermRow(
    val dictionary: DictionaryRef,
    val rowId: Long,
    val expression: String,
    val reading: String,
    /** Alternative written form (e.g. the traditional form of a simplified Chinese headword). */
    val alt: String?,
    val definitionTags: String,
    val rules: String,
    val score: Int,
    val sequence: Long?,
    val termTags: String,
    private val glossaryData: ByteArray,
) {
    /** Glossary items: strings, structured content objects, or [term, [rules]] redirects. */
    val glossary: List<Any?> by lazy { dictionary.decodeGlossary(glossaryData) }
}

class TermMetaRow(val dictionary: DictionaryRef, val expression: String, val mode: String, val data: Any?)

class KanjiRow(
    val dictionary: DictionaryRef,
    val character: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val meanings: List<String>,
    val stats: Map<String, String>,
)

/** Access to installed dictionaries. Implementations return rows of enabled dictionaries only. */
interface TermSource {
    /** Rows whose expression (or reading, if [includeReadings]) equals one of [keys]. */
    fun findTerms(keys: Collection<String>, language: Language, includeReadings: Boolean): List<TermRow>

    /** Rows of [dictionary] whose expression equals one of [expressions]. */
    fun findTermsByExpression(dictionary: DictionaryRef, expressions: Collection<String>): List<TermRow>

    fun findTermMeta(expressions: Collection<String>, language: Language): List<TermMetaRow>

    fun findKanji(characters: Collection<String>): List<KanjiRow>
}

class TermMatch(
    val row: TermRow,
    /** Length (in UTF-16 units) of the scanned text this match covers. */
    val sourceLength: Int,
    val reasons: List<String>,
    /** Index of the text variant (0 = text as scanned) that matched. */
    val variant: Int,
    val byReading: Boolean,
)

class TermGroup(
    val expression: String,
    val reading: String,
    val alt: String?,
    val sourceLength: Int,
    val reasons: List<String>,
    val entries: List<TermMatch>,
    val meta: List<TermMetaRow>,
)

class LookupResult(
    /** The scanned text (after normalization) the matches refer to. */
    val text: String,
    val language: Language,
    val groups: List<TermGroup>,
    val kanji: List<KanjiRow>,
) {
    val matchLength: Int get() = groups.maxOfOrNull { it.sourceLength } ?: 0
    val isEmpty: Boolean get() = groups.isEmpty() && kanji.isEmpty()

    companion object {
        fun empty(text: String, language: Language) = LookupResult(text, language, emptyList(), emptyList())
    }
}
