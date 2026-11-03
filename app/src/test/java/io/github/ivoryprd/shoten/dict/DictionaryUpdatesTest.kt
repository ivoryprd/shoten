package io.github.ivoryprd.shoten.dict

import io.github.ivoryprd.shoten.lookup.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DictionaryUpdatesTest {
    private fun dictionary(title: String, revision: String, indexUrl: String? = null) = InstalledDictionary(
        id = title, title = title, revision = revision, language = Language.JAPANESE, enabled = true, source = "",
        terms = 1, termMeta = 0, kanji = 0, isUpdatable = indexUrl != null, indexUrl = indexUrl,
        downloadUrl = "https://example.org/old.zip", url = null, attribution = null, description = null, installedAt = 0,
    )

    private fun date(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time

    private val jitendex = dictionary("Jitendex.org [2026-10-03]", "2026.10.03.0", "https://jitendex.org/static/yomitan.json")
    private val cedict = dictionary("CC-CEDICT", "2026-10-30")

    private fun updates(revision: String, modified: Long?) = DictionaryUpdates(
        fetchText = { """{"title":"Jitendex.org","revision":"$revision","downloadUrl":"https://example.org/new.zip"}""" },
        lastModified = { modified },
    )

    @Test
    fun jitendexByItsIndex() {
        val update = updates("2026.11.01.0", null).check(jitendex)!!
        assertEquals("2026.11.01.0", update.revision)
        assertEquals("https://example.org/new.zip", update.downloadUrl)
        assertNull(updates("2026.10.03.0", null).check(jitendex))
        // Never back to an older one.
        assertNull(updates("2026.09.01.0", null).check(jitendex))
    }

    @Test
    fun cedictByTheDateOfTheExport() {
        assertEquals("2026-11-02", updates("", date("2026-11-02 09:16")).check(cedict)!!.revision)
        // Exported the day after its date: the same version.
        assertNull(updates("", date("2026-10-31 09:16")).check(cedict))
        assertNull(updates("", null).check(cedict))
    }

    @Test
    fun onlyDictionariesThatCanBeUpdated() {
        val other = dictionary("Some dictionary", "1")
        val checker = updates("2026.11.01.0", date("2026-11-02 09:16"))
        assertFalse(checker.canUpdate(other))
        assertTrue(checker.canUpdate(jitendex) && checker.canUpdate(cedict))
        assertEquals(listOf(jitendex.title, cedict.title), checker.check(listOf(jitendex, other, cedict)).map { it.dictionary.title })
        // A failed check leaves the dictionary out.
        val offline = DictionaryUpdates({ throw java.io.IOException("offline") }, { throw java.io.IOException("offline") })
        assertTrue(offline.check(listOf(jitendex, cedict)).isEmpty())
    }

    @Test
    fun revisions() {
        assertTrue(DictionaryUpdates.isNewer("2026.11.01.0", "2026.10.03.0"))
        assertTrue(DictionaryUpdates.isNewer("2026.10.03.1", "2026.10.03"))
        assertFalse(DictionaryUpdates.isNewer("2026.10.03", "2026.10.03.0"))
        assertTrue(DictionaryUpdates.isNewer("v2", "v1"))
        assertFalse(DictionaryUpdates.isNewer("v1", "v1"))
    }
}
