package io.github.ivoryprd.shoten.dict

import io.github.ivoryprd.shoten.dict.importer.CedictImporter
import io.github.ivoryprd.shoten.dict.importer.DictionaryMetadata
import io.github.ivoryprd.shoten.dict.importer.ImportCancelledException
import io.github.ivoryprd.shoten.dict.importer.ImportException
import io.github.ivoryprd.shoten.dict.importer.ImportProgress
import io.github.ivoryprd.shoten.dict.importer.YomitanImporter
import io.github.ivoryprd.shoten.util.string
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/** Detects dictionary file formats, imports them and installs the result. */
class DictionaryInstaller(private val repository: DictionaryRepository) {
    enum class Format { YOMITAN, CEDICT }

    fun detectFormat(file: File): Format? {
        val head = ByteArray(4)
        val read = file.inputStream().use { it.read(head) }
        if (read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4) {
            val yomitan = try {
                ZipFile(file).use { YomitanImporter.isYomitanZip(it) }
            } catch (e: IOException) {
                throw ImportException("The zip file is damaged", e)
            }
            if (yomitan) return Format.YOMITAN
        }
        return if (CedictImporter.looksLikeCedict(file)) Format.CEDICT else null
    }

    /**
     * Imports [file] and installs it. A dictionary with the same title (or the same update URL)
     * is replaced; importing the same revision again is rejected.
     */
    fun install(file: File, progress: ImportProgress, replaceId: String? = null): InstalledDictionary {
        val format = detectFormat(file)
            ?: throw ImportException("Unrecognized file. Choose a Yomitan dictionary (.zip) or a CC-CEDICT file.")
        val (title, revision, indexUrl) = quickMetadata(file, format)
        var target = replaceId
        if (target == null) {
            val existing = repository.installed.firstOrNull { it.title == title }
                ?: indexUrl?.let { url -> repository.installed.firstOrNull { it.indexUrl == url } }
            if (existing != null) {
                if (existing.title == title && existing.revision == revision) {
                    throw ImportException("“$title” is already installed")
                }
                target = existing.id
            }
        }
        val temp = repository.newDatabaseFile()
        val sink = SqliteImportSink(temp)
        try {
            val metadata: DictionaryMetadata = when (format) {
                Format.YOMITAN -> YomitanImporter.import(file, sink, progress)
                Format.CEDICT -> CedictImporter.import(file, sink, progress)
            }
            return repository.install(temp, metadata, sink.importCounts, target)
        } catch (e: Throwable) {
            sink.abort()
            throw e
        }
    }

    private fun quickMetadata(file: File, format: Format): Triple<String?, String?, String?> = when (format) {
        Format.YOMITAN -> ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence()
                .filter { !it.isDirectory && (it.name == "index.json" || it.name.endsWith("/index.json")) }
                .minByOrNull { it.name.count { c -> c == '/' } }
            val index = entry?.let { e ->
                runCatching { YomitanImporter.parseIndex(zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrNull()
            }
            Triple(index?.string("title")?.trim(), index?.string("revision")?.trim(), index?.string("indexUrl"))
        }
        // The revision is only known after reading the whole file.
        Format.CEDICT -> Triple("CC-CEDICT", null, null)
    }

    companion object {
        private const val USER_AGENT = "Shoten/1.0 (Android dictionary app)"

        /** Downloads [url] to [target], following redirects. */
        fun download(url: String, target: File, progress: ImportProgress, label: String) {
            var current = URL(url)
            var redirects = 0
            while (true) {
                val connection = current.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without location")
                        if (++redirects > 8) throw IOException("Too many redirects")
                        current = URL(current, location)
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) throw IOException("Download failed: HTTP $code")
                    val total = connection.contentLengthLong
                    BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReport = 0L
                            while (true) {
                                if (progress.isCancelled) throw ImportCancelledException()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (done - lastReport > 256 * 1024) {
                                    lastReport = done
                                    progress.onProgress(if (total > 0) done.toFloat() / total else -1f, label)
                                }
                            }
                            if (total > 0 && done != total) throw IOException("Download incomplete")
                        }
                    }
                    return
                } finally {
                    connection.disconnect()
                }
            }
        }

        /** Fetches a small text resource, such as a Yomitan index.json used for update checks. */
        fun fetchText(url: String, maxBytes: Int = 1024 * 1024): String {
            var current = URL(url)
            var redirects = 0
            while (true) {
                val connection = current.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without location")
                        if (++redirects > 8) throw IOException("Too many redirects")
                        current = URL(current, location)
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                    val bytes = connection.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (out.size() > maxBytes) throw IOException("Response too large")
                        }
                        out.toByteArray()
                    }
                    return bytes.toString(Charsets.UTF_8)
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}
