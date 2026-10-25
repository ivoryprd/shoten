package io.github.ivoryprd.shoten.dict.importer

import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.util.Json
import io.github.ivoryprd.shoten.util.JsonException
import io.github.ivoryprd.shoten.util.JsonReader
import io.github.ivoryprd.shoten.util.JsonToken
import io.github.ivoryprd.shoten.util.asJsonObject
import io.github.ivoryprd.shoten.util.number
import io.github.ivoryprd.shoten.util.string
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Imports Yomitan (formerly Yomichan) dictionaries: a zip file with index.json, term banks,
 * term meta banks (frequencies, pitch accents), kanji banks, tag banks, styles.css and media.
 * See https://github.com/yomidevs/yomitan/tree/master/ext/data/schemas for the format.
 */
object YomitanImporter {
    private val BANK = Regex("(term_bank|term_meta_bank|kanji_bank|kanji_meta_bank|tag_bank)_(\\d+)\\.json")
    private val MEDIA_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "svg", "heic", "heif")
    private const val MAX_MEDIA_SIZE = 4L * 1024 * 1024
    private const val MAX_MEDIA_TOTAL = 64L * 1024 * 1024

    fun isYomitanZip(zip: ZipFile): Boolean = findIndex(zip) != null

    fun import(file: File, sink: ImportSink, progress: ImportProgress = ImportProgress.None): DictionaryMetadata {
        ZipFile(file).use { zip ->
            val indexEntry = findIndex(zip) ?: throw ImportException("Not a Yomitan dictionary: index.json is missing")
            val base = indexEntry.name.removeSuffix("index.json")
            val index = readIndex(zip, indexEntry)
            val format = (index.number("format") ?: index.number("version"))?.toInt()
                ?: throw ImportException("index.json has no format version")
            if (format !in 1..3) throw ImportException("Unsupported dictionary format $format")
            val title = index.string("title")?.trim().orEmpty()
            if (title.isEmpty()) throw ImportException("index.json has no title")
            val revision = index.string("revision")?.trim().orEmpty().ifEmpty { "1" }

            // Tag metadata of old (version 1/2) dictionaries lives in index.json.
            index["tagMeta"].asJsonObject()?.forEach { (name, value) ->
                val meta = value.asJsonObject() ?: return@forEach
                sink.tag(
                    name,
                    meta.string("category").orEmpty(),
                    meta.number("order")?.toInt() ?: 0,
                    meta.string("notes").orEmpty(),
                    meta.number("score")?.toInt() ?: 0,
                )
            }

            val banks = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(base) }
                .mapNotNull { entry ->
                    val match = BANK.matchEntire(entry.name.substring(base.length)) ?: return@mapNotNull null
                    Triple(match.groupValues[1], match.groupValues[2].toInt(), entry)
                }
                .sortedWith(compareBy({ bankOrder(it.first) }, { it.second }))
                .toList()
            if (banks.none { it.first == "term_bank" || it.first == "kanji_bank" || it.first == "term_meta_bank" }) {
                throw ImportException("The dictionary contains no terms or kanji")
            }

            val totalBytes = banks.sumOf { maxOf(it.third.size, 1L) }.toFloat()
            var doneBytes = 0L
            val detector = LanguageDetector()
            for ((kind, _, entry) in banks) {
                val counting = CountingInputStream(zip.getInputStream(entry))
                val reader = JsonReader(BufferedReader(InputStreamReader(counting, Charsets.UTF_8), 64 * 1024))
                val report = {
                    if (progress.isCancelled) throw ImportCancelledException()
                    progress.onProgress((doneBytes + counting.count) / totalBytes, title)
                }
                try {
                    reader.use {
                        when (kind) {
                            "term_bank" -> readTermBank(it, format, sink, detector, report)
                            "term_meta_bank" -> readMetaBank(it, report) { e, m, d -> sink.termMeta(e, m, d) }
                            "kanji_bank" -> readKanjiBank(it, format, sink, report)
                            "kanji_meta_bank" -> readMetaBank(it, report) { e, m, d -> sink.kanjiMeta(e, m, d) }
                            "tag_bank" -> readTagBank(it, sink)
                        }
                    }
                } catch (e: JsonException) {
                    throw ImportException("Invalid JSON in ${entry.name}: ${e.message}", e)
                }
                doneBytes += maxOf(entry.size, 1L)
                report()
            }

            zip.getEntry(base + "styles.css")?.let { entry ->
                sink.styles(zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) })
            }
            importMedia(zip, base, sink)

            val declared = Language.fromCode(index.string("sourceLanguage"))
            val metadata = DictionaryMetadata(
                title = title,
                revision = revision,
                format = format,
                sourceLanguage = if (declared != Language.OTHER) declared else detector.result(),
                targetLanguage = index.string("targetLanguage"),
                author = index.string("author"),
                url = index.string("url"),
                description = index.string("description"),
                attribution = index.string("attribution"),
                sequenced = index["sequenced"] == true,
                isUpdatable = index["isUpdatable"] == true,
                indexUrl = index.string("indexUrl"),
                downloadUrl = index.string("downloadUrl"),
                source = "yomitan",
            )
            sink.finish(metadata)
            return metadata
        }
    }

    /** Reads only index.json (used to check for updates). */
    fun parseIndex(text: String): Map<String, Any?> =
        Json.parse(text).asJsonObject() ?: throw ImportException("index.json is not an object")

    private fun findIndex(zip: ZipFile): ZipEntry? =
        zip.entries().asSequence()
            .filter { !it.isDirectory && (it.name == "index.json" || it.name.endsWith("/index.json")) }
            .minByOrNull { it.name.count { c -> c == '/' } }

    private fun readIndex(zip: ZipFile, entry: ZipEntry): Map<String, Any?> {
        val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        return try {
            parseIndex(text)
        } catch (e: JsonException) {
            throw ImportException("index.json is not valid JSON", e)
        }
    }

    private fun bankOrder(kind: String) = when (kind) {
        "tag_bank" -> 0
        "term_bank" -> 1
        "term_meta_bank" -> 2
        "kanji_bank" -> 3
        else -> 4
    }

    private inline fun forEachRow(reader: JsonReader, report: () -> Unit, block: () -> Unit) {
        reader.beginArray()
        var count = 0
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue()
                continue
            }
            reader.beginArray()
            block()
            while (reader.hasNext()) reader.skipValue()
            reader.endArray()
            if (++count % 2000 == 0) report()
        }
        reader.endArray()
    }

    private fun readTermBank(reader: JsonReader, format: Int, sink: ImportSink, detector: LanguageDetector, report: () -> Unit) {
        forEachRow(reader, report) {
            val expression = reader.textOrEmpty()
            val reading = reader.textOrEmpty()
            val definitionTags = reader.textOrEmpty()
            val rules = reader.textOrEmpty()
            val score = reader.numberOrZero().toInt()
            val glossary: List<Any?>
            var sequence: Long? = null
            var termTags = ""
            if (format == 1) {
                val items = ArrayList<Any?>()
                while (reader.hasNext()) items.add(reader.readTree())
                glossary = items
            } else {
                glossary = if (reader.hasNext()) (reader.readTree() as? List<Any?>) ?: emptyList() else emptyList()
                if (reader.hasNext()) {
                    sequence = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.numberOrZero().toLong()
                }
                if (reader.hasNext()) termTags = reader.textOrEmpty()
            }
            if (expression.isNotEmpty()) {
                detector.add(expression)
                sink.term(
                    expression = expression,
                    reading = reading.ifEmpty { expression },
                    alt = null,
                    definitionTags = definitionTags,
                    rules = rules,
                    score = score,
                    glossaryJson = Json.write(glossary),
                    sequence = sequence,
                    termTags = termTags,
                )
            }
        }
    }

    private inline fun readMetaBank(reader: JsonReader, noinline report: () -> Unit, crossinline emit: (String, String, String) -> Unit) {
        forEachRow(reader, report) {
            val key = reader.textOrEmpty()
            val mode = reader.textOrEmpty()
            val data = if (reader.hasNext()) reader.readTree() else null
            if (key.isNotEmpty() && mode.isNotEmpty()) emit(key, mode, Json.write(data))
        }
    }

    private fun readKanjiBank(reader: JsonReader, format: Int, sink: ImportSink, report: () -> Unit) {
        forEachRow(reader, report) {
            val character = reader.textOrEmpty()
            val onyomi = reader.textOrEmpty()
            val kunyomi = reader.textOrEmpty()
            val tags = reader.textOrEmpty()
            val meanings: List<Any?>
            var stats: Any? = emptyMap<String, Any?>()
            if (format == 1) {
                val items = ArrayList<Any?>()
                while (reader.hasNext()) items.add(reader.readTree())
                meanings = items
            } else {
                meanings = if (reader.hasNext()) (reader.readTree() as? List<Any?>) ?: emptyList() else emptyList()
                if (reader.hasNext()) stats = reader.readTree()
            }
            if (character.isNotEmpty()) {
                sink.kanji(character, onyomi, kunyomi, tags, Json.write(meanings), Json.write(stats ?: emptyMap<String, Any?>()))
            }
        }
    }

    private fun readTagBank(reader: JsonReader, sink: ImportSink) {
        forEachRow(reader, {}) {
            val name = reader.textOrEmpty()
            val category = reader.textOrEmpty()
            val order = reader.numberOrZero().toInt()
            val notes = reader.textOrEmpty()
            val score = reader.numberOrZero().toInt()
            if (name.isNotEmpty()) sink.tag(name, category, order, notes, score)
        }
    }

    private fun importMedia(zip: ZipFile, base: String, sink: ImportSink) {
        var total = 0L
        for (entry in zip.entries()) {
            if (entry.isDirectory || !entry.name.startsWith(base)) continue
            val path = entry.name.substring(base.length)
            val extension = path.substringAfterLast('.', "").lowercase()
            if (extension !in MEDIA_EXTENSIONS) continue
            if (entry.size > MAX_MEDIA_SIZE || total + maxOf(entry.size, 0) > MAX_MEDIA_TOTAL) continue
            val data = zip.getInputStream(entry).use { it.readBytes() }
            if (data.size > MAX_MEDIA_SIZE) continue
            total += data.size
            sink.media(path, data)
        }
    }

    /** Reads a string, number (as text), or null (as ""), and skips anything else. */
    private fun JsonReader.textOrEmpty(): String {
        if (!hasNext()) return ""
        return when (peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> nextString()
            JsonToken.NULL -> { nextNull(); "" }
            else -> { skipValue(); "" }
        }
    }

    private fun JsonReader.numberOrZero(): Double {
        if (!hasNext()) return 0.0
        return when (peek()) {
            JsonToken.NUMBER -> nextDouble()
            JsonToken.STRING -> nextString().toDoubleOrNull() ?: 0.0
            else -> { skipValue(); 0.0 }
        }
    }
}
