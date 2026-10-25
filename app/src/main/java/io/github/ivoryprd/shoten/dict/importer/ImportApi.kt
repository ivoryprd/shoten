package io.github.ivoryprd.shoten.dict.importer

import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.util.Cjk
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class ImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

class ImportCancelledException : IOException("Import cancelled")

/** Metadata of an imported dictionary (mostly the fields of a Yomitan index.json). */
data class DictionaryMetadata(
    val title: String,
    val revision: String,
    val format: Int,
    val sourceLanguage: Language,
    val targetLanguage: String?,
    val author: String? = null,
    val url: String? = null,
    val description: String? = null,
    val attribution: String? = null,
    val sequenced: Boolean = false,
    val isUpdatable: Boolean = false,
    val indexUrl: String? = null,
    val downloadUrl: String? = null,
    /** "pinyin-numbered" for CC-CEDICT style readings. */
    val readingFormat: String? = null,
    /** "yomitan" or "cedict". */
    val source: String = "yomitan",
)

data class ImportCounts(
    val terms: Int = 0,
    val termMeta: Int = 0,
    val kanji: Int = 0,
    val kanjiMeta: Int = 0,
    val tags: Int = 0,
    val media: Int = 0,
)

/** Receives the contents of a dictionary as it is parsed. */
interface ImportSink {
    fun term(
        expression: String,
        reading: String,
        alt: String?,
        definitionTags: String,
        rules: String,
        score: Int,
        glossaryJson: String,
        sequence: Long?,
        termTags: String,
    )

    fun termMeta(expression: String, mode: String, dataJson: String)

    fun kanji(character: String, onyomi: String, kunyomi: String, tags: String, meaningsJson: String, statsJson: String)

    fun kanjiMeta(character: String, mode: String, dataJson: String)

    fun tag(name: String, category: String, order: Int, notes: String, score: Int)

    fun media(path: String, data: ByteArray)

    fun styles(css: String)

    /** Called once after all content was delivered. */
    fun finish(metadata: DictionaryMetadata)
}

interface ImportProgress {
    /** [fraction] is in 0..1 or negative if unknown. */
    fun onProgress(fraction: Float, message: String)

    val isCancelled: Boolean

    object None : ImportProgress {
        override fun onProgress(fraction: Float, message: String) = Unit
        override val isCancelled: Boolean get() = false
    }
}

/** Guesses a dictionary's language from sample headwords. */
class LanguageDetector {
    private var kana = 0
    private var han = 0
    private var samples = 0

    fun add(text: String) {
        if (samples >= 2000) return
        samples++
        if (Cjk.containsKana(text)) kana++ else if (Cjk.containsHan(text)) han++
    }

    fun result(): Language = when {
        kana > 0 && kana * 20 >= samples -> Language.JAPANESE
        han > samples / 4 -> Language.CHINESE
        else -> Language.OTHER
    }
}

/** Counts bytes read, for progress reporting. */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count += skipped
        return skipped
    }
}
