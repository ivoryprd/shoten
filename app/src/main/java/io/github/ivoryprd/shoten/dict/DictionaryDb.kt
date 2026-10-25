package io.github.ivoryprd.shoten.dict

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.ivoryprd.shoten.lookup.DictionaryRef
import io.github.ivoryprd.shoten.lookup.KanjiRow
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.TagInfo
import io.github.ivoryprd.shoten.lookup.TermMetaRow
import io.github.ivoryprd.shoten.lookup.TermRow
import io.github.ivoryprd.shoten.util.Json
import io.github.ivoryprd.shoten.util.asJsonArray
import io.github.ivoryprd.shoten.util.asJsonObject
import java.io.File

/** Read-only access to one installed dictionary database. */
class DictionaryDb(val file: File, override val id: String) : DictionaryRef {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )
    private val info: Map<String, String> = HashMap<String, String>().also { map ->
        db.rawQuery("SELECT key, value FROM info", null).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getString(1) ?: ""
        }
    }
    private val codec = GlossaryCodec(
        db.rawQuery("SELECT dictionary FROM codec WHERE id = 1", null).use { c -> if (c.moveToFirst()) c.getBlob(0) else null },
    )
    private val tags: Map<String, TagInfo> = HashMap<String, TagInfo>().also { map ->
        db.rawQuery("SELECT name, category, sort_order, notes, score FROM tags", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                map[normalizeTagName(name)] = TagInfo(name, c.getString(1), c.getInt(2), c.getString(3), c.getInt(4))
            }
        }
    }

    override val title: String = info["title"] ?: id
    override val readingFormat: String? = info["readingFormat"]
    override val styles: String? = info["styles"]

    @Volatile
    override var priority: Int = 0

    @Volatile
    override var language: Language = Language.fromCode(info["sourceLanguage"])

    override fun decodeGlossary(data: ByteArray): List<Any?> = Json.parse(codec.decode(data)).asJsonArray() ?: emptyList()

    override fun tag(name: String): TagInfo? = tags[normalizeTagName(name)]

    fun findTerms(keys: Collection<String>, includeReadings: Boolean): List<TermRow> {
        val out = ArrayList<TermRow>()
        for (chunk in keys.chunked(MAX_KEYS_PER_QUERY)) {
            val placeholders = placeholders(chunk.size)
            val sql = buildString {
                append(TERM_COLUMNS).append(" WHERE expression IN (").append(placeholders).append(')')
                if (includeReadings) append(" OR reading IN (").append(placeholders).append(')')
            }
            val args = if (includeReadings) (chunk + chunk).toTypedArray() else chunk.toTypedArray()
            db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(readTerm(c)) }
        }
        return out
    }

    fun findTermsByExpression(expressions: Collection<String>): List<TermRow> = findTerms(expressions, false)

    fun findTermMeta(expressions: Collection<String>): List<TermMetaRow> {
        val out = ArrayList<TermMetaRow>()
        for (chunk in expressions.chunked(MAX_KEYS_PER_QUERY)) {
            db.rawQuery(
                "SELECT expression, mode, data FROM term_meta WHERE expression IN (${placeholders(chunk.size)})",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val data = runCatching { Json.parse(c.getString(2)) }.getOrNull()
                    out.add(TermMetaRow(this, c.getString(0), c.getString(1), data))
                }
            }
        }
        return out
    }

    fun findKanji(characters: Collection<String>): List<KanjiRow> {
        val out = ArrayList<KanjiRow>()
        for (chunk in characters.chunked(MAX_KEYS_PER_QUERY)) {
            db.rawQuery(
                "SELECT character, onyomi, kunyomi, tags, meanings, stats FROM kanji WHERE character IN (${placeholders(chunk.size)})",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val meanings = runCatching { Json.parse(c.getString(4)).asJsonArray() }.getOrNull().orEmpty()
                        .mapNotNull { it?.toString() }
                    val stats = runCatching { Json.parse(c.getString(5)).asJsonObject() }.getOrNull().orEmpty()
                        .mapValues { (_, v) -> if (v is Double) Json.write(v) else v.toString() }
                    out.add(KanjiRow(this, c.getString(0), c.getString(1), c.getString(2), c.getString(3), meanings, stats))
                }
            }
        }
        return out
    }

    fun media(path: String): ByteArray? =
        db.rawQuery("SELECT data FROM media WHERE path = ?", arrayOf(path)).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

    fun info(key: String): String? = info[key]

    fun close() {
        db.close()
    }

    private fun readTerm(c: Cursor) = TermRow(
        dictionary = this,
        rowId = c.getLong(0),
        expression = c.getString(1),
        reading = c.getString(2),
        alt = if (c.isNull(3)) null else c.getString(3),
        definitionTags = c.getString(4),
        rules = c.getString(5),
        score = c.getInt(6),
        sequence = if (c.isNull(8)) null else c.getLong(8),
        termTags = c.getString(9),
        glossaryData = c.getBlob(7),
    )

    companion object {
        private const val MAX_KEYS_PER_QUERY = 200
        private const val TERM_COLUMNS =
            "SELECT id, expression, reading, alt, def_tags, rules, score, glossary, sequence, term_tags FROM terms"

        private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")

        /** Tag names may use non-breaking spaces (Jitendex does). */
        fun normalizeTagName(name: String) = name.replace(160.toChar(), ' ').trim()
    }
}
