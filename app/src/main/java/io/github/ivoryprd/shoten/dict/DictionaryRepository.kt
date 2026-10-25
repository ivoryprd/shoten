package io.github.ivoryprd.shoten.dict

import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.ivoryprd.shoten.dict.importer.DictionaryMetadata
import io.github.ivoryprd.shoten.dict.importer.ImportCounts
import io.github.ivoryprd.shoten.lookup.DictionaryRef
import io.github.ivoryprd.shoten.lookup.KanjiRow
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.TermMetaRow
import io.github.ivoryprd.shoten.lookup.TermRow
import io.github.ivoryprd.shoten.lookup.TermSource
import io.github.ivoryprd.shoten.util.Json
import io.github.ivoryprd.shoten.util.asJsonArray
import io.github.ivoryprd.shoten.util.asJsonObject
import io.github.ivoryprd.shoten.util.number
import io.github.ivoryprd.shoten.util.string
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** An installed dictionary, as listed in the registry (in priority order). */
data class InstalledDictionary(
    val id: String,
    val title: String,
    val revision: String,
    val language: Language,
    val enabled: Boolean,
    val source: String,
    val terms: Int,
    val termMeta: Int,
    val kanji: Int,
    val isUpdatable: Boolean,
    val indexUrl: String?,
    val downloadUrl: String?,
    val url: String?,
    val attribution: String?,
    val description: String?,
    val installedAt: Long,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id, "title" to title, "revision" to revision, "language" to language.code, "enabled" to enabled,
        "source" to source, "terms" to terms.toLong(), "termMeta" to termMeta.toLong(), "kanji" to kanji.toLong(),
        "isUpdatable" to isUpdatable, "indexUrl" to indexUrl, "downloadUrl" to downloadUrl, "url" to url,
        "attribution" to attribution, "description" to description, "installedAt" to installedAt,
    )

    companion object {
        fun fromJson(m: Map<String, Any?>): InstalledDictionary? {
            val id = m.string("id") ?: return null
            return InstalledDictionary(
                id = id,
                title = m.string("title") ?: id,
                revision = m.string("revision").orEmpty(),
                language = Language.fromCode(m.string("language")),
                enabled = m["enabled"] != false,
                source = m.string("source") ?: "yomitan",
                terms = m.number("terms")?.toInt() ?: 0,
                termMeta = m.number("termMeta")?.toInt() ?: 0,
                kanji = m.number("kanji")?.toInt() ?: 0,
                isUpdatable = m["isUpdatable"] == true,
                indexUrl = m.string("indexUrl"),
                downloadUrl = m.string("downloadUrl"),
                url = m.string("url"),
                attribution = m.string("attribution"),
                description = m.string("description"),
                installedAt = m.number("installedAt")?.toLong() ?: 0L,
            )
        }
    }
}

/**
 * Keeps track of installed dictionaries and gives the lookup engine access to the enabled ones.
 * Thread safe: lookups run concurrently, changes take an exclusive lock.
 */
class DictionaryRepository(private val dir: File) {
    private val lock = ReentrantReadWriteLock()
    private val registryFile = File(dir, "registry.json")
    private var dictionaries: List<InstalledDictionary> = emptyList()
    private val open = HashMap<String, DictionaryDb>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        dir.mkdirs()
        dictionaries = loadRegistry()
        // Remove leftovers of interrupted imports.
        dir.listFiles()?.forEach { f ->
            val known = dictionaries.any { f.name.startsWith(it.id + ".db") }
            if (!known && f.name != registryFile.name) f.delete()
        }
    }

    val installed: List<InstalledDictionary> get() = lock.read { dictionaries }

    fun hasDictionaries(language: Language): Boolean = lock.read {
        dictionaries.any { it.enabled && (it.language == language || it.language == Language.OTHER) && it.terms > 0 }
    }

    fun findByTitle(title: String): InstalledDictionary? = lock.read { dictionaries.firstOrNull { it.title == title } }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** A path for a new dictionary database; pass it to [install] once it was written. */
    fun newDatabaseFile(): File = File(dir, "import-" + UUID.randomUUID() + ".tmp")

    /**
     * Installs a fully written dictionary database. If [replaceId] is given, that dictionary is
     * replaced in place (keeping its position and enabled state).
     */
    fun install(tempFile: File, metadata: DictionaryMetadata, counts: ImportCounts, replaceId: String? = null): InstalledDictionary {
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.db")
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            SQLiteDatabase.deleteDatabase(tempFile)
        }
        val entry = InstalledDictionary(
            id = id,
            title = metadata.title,
            revision = metadata.revision,
            language = metadata.sourceLanguage,
            enabled = true,
            source = metadata.source,
            terms = counts.terms,
            termMeta = counts.termMeta,
            kanji = counts.kanji,
            isUpdatable = metadata.isUpdatable,
            indexUrl = metadata.indexUrl,
            downloadUrl = metadata.downloadUrl,
            url = metadata.url,
            attribution = metadata.attribution,
            description = metadata.description,
            installedAt = System.currentTimeMillis(),
        )
        lock.write {
            val old = replaceId?.let { rid -> dictionaries.firstOrNull { it.id == rid } }
            dictionaries = if (old != null) {
                dictionaries.map { if (it.id == old.id) entry.copy(enabled = old.enabled, language = old.language) else it }
            } else {
                dictionaries + entry
            }
            if (old != null) removeFiles(old.id)
            saveRegistry()
        }
        notifyChanged()
        return entry
    }

    fun delete(id: String) {
        lock.write {
            dictionaries = dictionaries.filter { it.id != id }
            removeFiles(id)
            saveRegistry()
        }
        notifyChanged()
    }

    fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }

    fun setLanguage(id: String, language: Language) = update(id) { it.copy(language = language) }

    fun move(id: String, delta: Int) {
        lock.write {
            val list = dictionaries.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return
            val target = (index + delta).coerceIn(0, list.size - 1)
            if (target == index) return
            list.add(target, list.removeAt(index))
            dictionaries = list
            saveRegistry()
        }
        notifyChanged()
    }

    private fun update(id: String, change: (InstalledDictionary) -> InstalledDictionary) {
        lock.write {
            dictionaries = dictionaries.map { if (it.id == id) change(it) else it }
            saveRegistry()
        }
        notifyChanged()
    }

    /** Opens (or returns the open) database of an installed dictionary. */
    fun database(id: String): DictionaryDb? = lock.read { openLocked(id) }

    private fun openLocked(id: String): DictionaryDb? {
        val index = dictionaries.indexOfFirst { it.id == id }
        if (index < 0) return null
        val entry = dictionaries[index]
        synchronized(open) {
            val db = open[id] ?: try {
                DictionaryDb(File(dir, "$id.db"), id).also { open[id] = it }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open dictionary ${entry.title}", e)
                return null
            }
            db.priority = index
            db.language = entry.language
            return db
        }
    }

    private fun enabledDatabases(language: Language?): List<DictionaryDb> =
        dictionaries.filter { it.enabled && (language == null || it.language == language || it.language == Language.OTHER) }
            .mapNotNull { openLocked(it.id) }

    /** Lookup access to enabled dictionaries. */
    val termSource: TermSource = object : TermSource {
        override fun findTerms(keys: Collection<String>, language: Language, includeReadings: Boolean): List<TermRow> =
            lock.read { enabledDatabases(language).flatMap { it.findTerms(keys, includeReadings) } }

        override fun findTermsByExpression(dictionary: DictionaryRef, expressions: Collection<String>): List<TermRow> =
            lock.read { openLocked(dictionary.id)?.findTermsByExpression(expressions).orEmpty() }

        override fun findTermMeta(expressions: Collection<String>, language: Language): List<TermMetaRow> =
            lock.read { enabledDatabases(language).flatMap { it.findTermMeta(expressions) } }

        override fun findKanji(characters: Collection<String>): List<KanjiRow> =
            lock.read {
                dictionaries.filter { it.enabled && it.kanji > 0 }.mapNotNull { openLocked(it.id) }
                    .flatMap { it.findKanji(characters) }
            }
    }

    /** Closes open databases (e.g. when memory is low); they are reopened on demand. */
    fun closeAll() {
        lock.write {
            synchronized(open) {
                open.values.forEach { it.close() }
                open.clear()
            }
        }
    }

    private fun removeFiles(id: String) {
        synchronized(open) { open.remove(id)?.close() }
        SQLiteDatabase.deleteDatabase(File(dir, "$id.db"))
    }

    private fun notifyChanged() {
        mainHandler.post { listeners.forEach { it() } }
    }

    private fun loadRegistry(): List<InstalledDictionary> {
        if (!registryFile.exists()) return emptyList()
        return try {
            val root = Json.parse(registryFile.readText()).asJsonObject() ?: return emptyList()
            root["dictionaries"].asJsonArray().orEmpty().mapNotNull { it.asJsonObject()?.let(InstalledDictionary::fromJson) }
                .filter { File(dir, it.id + ".db").exists() }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read the dictionary registry", e)
            emptyList()
        }
    }

    private fun saveRegistry() {
        val json = Json.write(mapOf("version" to 1L, "dictionaries" to dictionaries.map { it.toJson() }))
        val tmp = File(dir, registryFile.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(registryFile)) {
            registryFile.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "DictionaryRepository"
    }
}
