package io.github.ivoryprd.shoten.testing

import io.github.ivoryprd.shoten.dict.importer.DictionaryMetadata
import io.github.ivoryprd.shoten.dict.importer.ImportSink
import io.github.ivoryprd.shoten.lookup.DictionaryRef
import io.github.ivoryprd.shoten.lookup.KanjiRow
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.TagInfo
import io.github.ivoryprd.shoten.lookup.TermMetaRow
import io.github.ivoryprd.shoten.lookup.TermRow
import io.github.ivoryprd.shoten.lookup.TermSource
import io.github.ivoryprd.shoten.util.Json
import io.github.ivoryprd.shoten.util.asJsonArray

/** An in-memory dictionary filled through [ImportSink], for tests. */
class MemoryDictionary(
    override val id: String,
    override val priority: Int = 0,
) : DictionaryRef, ImportSink {
    override var title: String = id
    override var language: Language = Language.OTHER
    override var readingFormat: String? = null
    override var styles: String? = null
    var metadata: DictionaryMetadata? = null

    val terms = ArrayList<TermRow>()
    val termMeta = ArrayList<TermMetaRow>()
    val kanji = ArrayList<KanjiRow>()
    val tags = HashMap<String, TagInfo>()
    val media = HashMap<String, ByteArray>()

    override fun decodeGlossary(data: ByteArray): List<Any?> = Json.parse(String(data, Charsets.UTF_8)).asJsonArray() ?: emptyList()

    override fun tag(name: String): TagInfo? = tags[name]

    override fun term(
        expression: String, reading: String, alt: String?, definitionTags: String, rules: String,
        score: Int, glossaryJson: String, sequence: Long?, termTags: String,
    ) {
        terms.add(TermRow(this, terms.size.toLong() + 1, expression, reading, alt, definitionTags, rules, score, sequence, termTags, glossaryJson.toByteArray()))
    }

    override fun termMeta(expression: String, mode: String, dataJson: String) {
        termMeta.add(TermMetaRow(this, expression, mode, Json.parse(dataJson)))
    }

    @Suppress("UNCHECKED_CAST")
    override fun kanji(character: String, onyomi: String, kunyomi: String, tags: String, meaningsJson: String, statsJson: String) {
        val meanings = (Json.parse(meaningsJson) as List<Any?>).map { it.toString() }
        val stats = (Json.parse(statsJson) as Map<String, Any?>).mapValues { it.value.toString() }
        kanji.add(KanjiRow(this, character, onyomi, kunyomi, tags, meanings, stats))
    }

    override fun kanjiMeta(character: String, mode: String, dataJson: String) = Unit

    override fun tag(name: String, category: String, order: Int, notes: String, score: Int) {
        tags[name] = TagInfo(name, category, order, notes, score)
    }

    override fun media(path: String, data: ByteArray) {
        media[path] = data
    }

    override fun styles(css: String) {
        styles = css
    }

    override fun finish(metadata: DictionaryMetadata) {
        this.metadata = metadata
        title = metadata.title
        language = metadata.sourceLanguage
        readingFormat = metadata.readingFormat
    }

    private val byExpression by lazy { terms.groupBy { it.expression } }
    private val byReading by lazy { terms.groupBy { it.reading } }

    fun find(keys: Collection<String>, includeReadings: Boolean): List<TermRow> {
        val out = LinkedHashSet<TermRow>()
        for (k in keys) {
            byExpression[k]?.let { out.addAll(it) }
            if (includeReadings) byReading[k]?.let { out.addAll(it) }
        }
        return out.toList()
    }

    fun findByExpression(keys: Collection<String>): List<TermRow> = keys.flatMap { byExpression[it].orEmpty() }
}

class MemoryTermSource(private val dictionaries: List<MemoryDictionary>) : TermSource {
    val queriedKeyCounts = ArrayList<Int>()

    override fun findTerms(keys: Collection<String>, language: Language, includeReadings: Boolean): List<TermRow> {
        queriedKeyCounts.add(keys.size)
        return dictionaries.filter { it.language == language || it.language == Language.OTHER }
            .flatMap { it.find(keys, includeReadings) }
    }

    override fun findTermsByExpression(dictionary: DictionaryRef, expressions: Collection<String>): List<TermRow> =
        dictionaries.filter { it.id == dictionary.id }.flatMap { it.findByExpression(expressions) }

    override fun findTermMeta(expressions: Collection<String>, language: Language): List<TermMetaRow> {
        val set = expressions.toSet()
        return dictionaries.filter { it.language == language || it.language == Language.OTHER }
            .flatMap { d -> d.termMeta.filter { it.expression in set } }
    }

    override fun findKanji(characters: Collection<String>): List<KanjiRow> {
        val set = characters.toSet()
        return dictionaries.flatMap { d -> d.kanji.filter { it.character in set } }
    }
}
