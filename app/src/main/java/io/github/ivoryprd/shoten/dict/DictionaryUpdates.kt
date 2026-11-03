package io.github.ivoryprd.shoten.dict

import android.os.Handler
import android.os.Looper
import io.github.ivoryprd.shoten.dict.importer.YomitanImporter
import io.github.ivoryprd.shoten.util.string
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Finds newer versions of installed dictionaries: Yomitan dictionaries that name an index to
 * update from (Jitendex does), and CC-CEDICT, whose export is published anew every day or so.
 */
class DictionaryUpdates(
    /** Fetches a small text file (an index.json). */
    private val fetchText: (url: String) -> String,
    /** When the file at a URL was last changed (milliseconds since the epoch), if known. */
    private val lastModified: (url: String) -> Long?,
) {
    /** A newer [revision] of [dictionary], downloadable from [downloadUrl]. */
    data class Update(val dictionary: InstalledDictionary, val revision: String, val downloadUrl: String)

    /** Whether [d] can be checked for updates. */
    fun canUpdate(d: InstalledDictionary) = (d.isUpdatable && d.indexUrl != null) || d.title == CEDICT_TITLE

    /** The updates of the [installed] dictionaries; those that can't be checked (offline, say) are left out. */
    fun check(installed: List<InstalledDictionary>): List<Update> = installed.filter(::canUpdate).mapNotNull { d ->
        runCatching { check(d) }.getOrNull()
    }

    /** The update of [d], or null if it is up to date. Throws if the check fails. */
    fun check(d: InstalledDictionary): Update? {
        val indexUrl = d.indexUrl
        if (d.isUpdatable && indexUrl != null) {
            val index = YomitanImporter.parseIndex(fetchText(indexUrl))
            val revision = index.string("revision")?.trim() ?: return null
            if (!isNewer(revision, d.revision)) return null
            return Update(d, revision, index.string("downloadUrl") ?: d.downloadUrl ?: return null)
        }
        if (d.title == CEDICT_TITLE) {
            // Its revision is the date in the file. The export is made after that date, so it
            // counts as newer from the day after on.
            val modified = lastModified(CEDICT_URL) ?: return null
            val installed = parseDate(d.revision) ?: return Update(d, formatDate(modified), CEDICT_URL)
            return if (modified >= installed + 2 * DAY) Update(d, formatDate(modified), CEDICT_URL) else null
        }
        return null
    }

    companion object {
        const val CEDICT_TITLE = "CC-CEDICT"
        const val CEDICT_URL = "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.zip"
        private const val DAY = 24 * 60 * 60 * 1000L

        /**
         * Whether [remote] is a newer revision than [installed]: by their numbers where both are
         * numbers (2026.10.03.0 and the like), else if they differ at all (as Yomitan does).
         */
        fun isNewer(remote: String, installed: String): Boolean {
            val a = remote.split('.', '-', '_').map { it.toIntOrNull() }
            val b = installed.split('.', '-', '_').map { it.toIntOrNull() }
            if (a.any { it == null } || b.any { it == null }) return remote != installed
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrNull(i) ?: 0
                val y = b.getOrNull(i) ?: 0
                if (x != y) return x > y
            }
            return false
        }

        private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        private fun parseDate(s: String): Long? = runCatching { dateFormat().parse(s.take(10))?.time }.getOrNull()

        private fun formatDate(time: Long): String = dateFormat().format(Date(time))
    }
}

/**
 * The last check for dictionary updates, shared by the screens that show it; listeners are
 * called on the main thread.
 */
class UpdateState(private val updates: DictionaryUpdates, private val repository: DictionaryRepository) {
    /** The updates found by the last check, of dictionaries still installed at the same revision. */
    var available: List<DictionaryUpdates.Update> = emptyList()
        get() = field.filter { u -> repository.installed.any { it.id == u.dictionary.id && it.revision == u.dictionary.revision } }
        private set

    @Volatile
    var checking = false
        private set

    /** When the last check finished (0 if never), and whether it could reach every server. */
    var lastCheck = 0L
        private set
    var lastCheckFailed = false
        private set

    private val listeners = ArrayList<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Whether any installed dictionary can be updated at all. */
    val hasUpdatable: Boolean get() = repository.installed.any(updates::canUpdate)

    /** Checks for updates in the background, unless a check ran within [maxAge] milliseconds. */
    fun check(maxAge: Long = 0L) {
        if (checking || !hasUpdatable || System.currentTimeMillis() - lastCheck < maxAge) return
        checking = true
        notifyListeners()
        executor.execute {
            val installed = repository.installed.filter(updates::canUpdate)
            var failed = false
            val found = installed.mapNotNull { d -> runCatching { updates.check(d) }.onFailure { failed = true }.getOrNull() }
            handler.post {
                available = found
                lastCheck = System.currentTimeMillis()
                lastCheckFailed = failed
                checking = false
                notifyListeners()
            }
        }
    }

    private fun notifyListeners() {
        handler.post { listeners.toList().forEach { it() } }
    }
}
