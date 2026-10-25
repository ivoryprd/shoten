package io.github.ivoryprd.shoten.dict.importer

import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.util.Json
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Imports CC-CEDICT (https://cc-cedict.org) from its text format, plain or as the .zip/.gz
 * downloads from MDBG. Lines look like: `傳統 传统 [chuan2 tong3] /tradition/traditional/`.
 */
object CedictImporter {
    private val LINE = Regex("^(\\S+) (\\S+) \\[([^\\]]*)] /(.*)/\\s*$")

    data class Entry(val traditional: String, val simplified: String, val pinyin: String, val definitions: List<String>)

    fun parseLine(line: String): Entry? {
        if (line.isEmpty() || line.startsWith("#")) return null
        val m = LINE.matchEntire(line) ?: return null
        val definitions = m.groupValues[4].split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (definitions.isEmpty()) return null
        return Entry(m.groupValues[1], m.groupValues[2], m.groupValues[3].trim(), definitions)
    }

    /** Whether [file] looks like CC-CEDICT (plain, gzip or zip). */
    fun looksLikeCedict(file: File): Boolean = try {
        openText(file).use { reader ->
            var checked = 0
            var matched = 0
            while (checked < 200) {
                val line = reader.readLine() ?: break
                if (line.isBlank() || line.startsWith("#")) continue
                checked++
                if (parseLine(line) != null) matched++
            }
            checked > 0 && matched * 10 >= checked * 9
        }
    } catch (e: Exception) {
        false
    }

    fun import(file: File, sink: ImportSink, progress: ImportProgress = ImportProgress.None): DictionaryMetadata {
        var date: String? = null
        val total = file.length().toFloat()
        val counting = CountingInputStream(BufferedInputStream(file.inputStream(), 64 * 1024))
        var count = 0L
        openText(counting).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("#!")) {
                    val m = Regex("#!\\s*date=(\\S+)").find(line)
                    if (m != null) date = m.groupValues[1]
                    continue
                }
                val entry = parseLine(line) ?: continue
                count++
                val glossary = Json.write(entry.definitions)
                val sameForms = entry.traditional == entry.simplified
                sink.term(entry.simplified, entry.pinyin, if (sameForms) null else entry.traditional, "", "", 0, glossary, count, "")
                if (!sameForms) {
                    sink.term(entry.traditional, entry.pinyin, entry.simplified, "", "", 0, glossary, count, "")
                }
                if (count % 2000 == 0L) {
                    if (progress.isCancelled) throw ImportCancelledException()
                    progress.onProgress(counting.count / total, "CC-CEDICT")
                }
            }
        }
        if (count == 0L) throw ImportException("No CC-CEDICT entries found")
        val revision = date?.substringBefore('T') ?: "unknown"
        val metadata = DictionaryMetadata(
            title = "CC-CEDICT",
            revision = revision,
            format = 3,
            sourceLanguage = Language.CHINESE,
            targetLanguage = "en",
            url = "https://cc-cedict.org/wiki/",
            description = "Community-maintained Chinese–English dictionary.",
            attribution = "CC-CEDICT, maintained by MDBG (https://www.mdbg.net). " +
                "Licensed under the Creative Commons Attribution-ShareAlike 4.0 International License.",
            readingFormat = "pinyin-numbered",
            source = "cedict",
        )
        sink.finish(metadata)
        return metadata
    }

    private fun openText(file: File): BufferedReader = openText(BufferedInputStream(file.inputStream(), 64 * 1024))

    /** Opens plain, gzip or zip (first .u8/.txt entry) CC-CEDICT data as text. */
    private fun openText(input: InputStream): BufferedReader {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val stream: InputStream = when {
            b0 == 0x1F && b1 == 0x8B -> GZIPInputStream(buffered, 64 * 1024)
            b0 == 'P'.code && b1 == 'K'.code -> {
                val zip = ZipInputStream(buffered)
                while (true) {
                    val entry = zip.nextEntry ?: throw ImportException("No CC-CEDICT text file in the zip")
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory && (name.endsWith(".u8") || name.endsWith(".txt"))) break
                }
                zip
            }
            else -> buffered
        }
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 64 * 1024)
    }
}
