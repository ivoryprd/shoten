package io.github.ivoryprd.shoten.dict

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import io.github.ivoryprd.shoten.dict.importer.DictionaryMetadata
import io.github.ivoryprd.shoten.dict.importer.ImportCounts
import io.github.ivoryprd.shoten.dict.importer.ImportSink
import java.io.File

/** Schema of a dictionary database. Each installed dictionary lives in its own SQLite file. */
object DictionarySchema {
    const val VERSION = 1

    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE info (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE codec (id INTEGER PRIMARY KEY, dictionary BLOB)")
        db.execSQL(
            "CREATE TABLE terms (id INTEGER PRIMARY KEY, expression TEXT NOT NULL, reading TEXT NOT NULL, " +
                "alt TEXT, def_tags TEXT NOT NULL, rules TEXT NOT NULL, score INTEGER NOT NULL, " +
                "glossary BLOB NOT NULL, sequence INTEGER, term_tags TEXT NOT NULL)",
        )
        db.execSQL("CREATE TABLE term_meta (id INTEGER PRIMARY KEY, expression TEXT NOT NULL, mode TEXT NOT NULL, data TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE kanji (id INTEGER PRIMARY KEY, character TEXT NOT NULL, onyomi TEXT NOT NULL, " +
                "kunyomi TEXT NOT NULL, tags TEXT NOT NULL, meanings TEXT NOT NULL, stats TEXT NOT NULL)",
        )
        db.execSQL("CREATE TABLE kanji_meta (id INTEGER PRIMARY KEY, character TEXT NOT NULL, mode TEXT NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE tags (name TEXT NOT NULL, category TEXT NOT NULL, sort_order INTEGER NOT NULL, notes TEXT NOT NULL, score INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE media (path TEXT PRIMARY KEY, data BLOB NOT NULL)")
    }

    /** Indexes are created after the bulk insert, which is much faster than maintaining them. */
    fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX terms_expression ON terms(expression)")
        db.execSQL("CREATE INDEX terms_reading ON terms(reading)")
        db.execSQL("CREATE INDEX term_meta_expression ON term_meta(expression)")
        db.execSQL("CREATE INDEX kanji_character ON kanji(character)")
        db.execSQL("CREATE INDEX kanji_meta_character ON kanji_meta(character)")
    }
}

/**
 * Writes an imported dictionary into a new SQLite file. Glossaries are compressed with a preset
 * dictionary trained on the first entries, so those are buffered until enough have been seen.
 */
class SqliteImportSink(private val file: File) : ImportSink {
    private class PendingTerm(
        val expression: String, val reading: String, val alt: String?, val definitionTags: String,
        val rules: String, val score: Int, val glossaryJson: String, val sequence: Long?, val termTags: String,
    )

    private val db: SQLiteDatabase
    private val insertTerm: SQLiteStatement
    private val insertTermMeta: SQLiteStatement
    private val insertKanji: SQLiteStatement
    private val insertKanjiMeta: SQLiteStatement
    private val insertTag: SQLiteStatement
    private val insertMedia: SQLiteStatement

    private val pending = ArrayList<PendingTerm>()
    private var encoder: GlossaryCodec.Encoder? = null
    private var counts = ImportCounts()
    private var stylesCss: String? = null
    private var closed = false

    init {
        file.delete()
        db = SQLiteDatabase.openDatabase(
            file.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        db.disableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=OFF")
        db.version = DictionarySchema.VERSION
        DictionarySchema.create(db)
        db.beginTransaction()
        insertTerm = db.compileStatement(
            "INSERT INTO terms (expression, reading, alt, def_tags, rules, score, glossary, sequence, term_tags) VALUES (?,?,?,?,?,?,?,?,?)",
        )
        insertTermMeta = db.compileStatement("INSERT INTO term_meta (expression, mode, data) VALUES (?,?,?)")
        insertKanji = db.compileStatement(
            "INSERT INTO kanji (character, onyomi, kunyomi, tags, meanings, stats) VALUES (?,?,?,?,?,?)",
        )
        insertKanjiMeta = db.compileStatement("INSERT INTO kanji_meta (character, mode, data) VALUES (?,?,?)")
        insertTag = db.compileStatement("INSERT INTO tags (name, category, sort_order, notes, score) VALUES (?,?,?,?,?)")
        insertMedia = db.compileStatement("INSERT OR REPLACE INTO media (path, data) VALUES (?,?)")
    }

    val importCounts: ImportCounts get() = counts

    override fun term(
        expression: String, reading: String, alt: String?, definitionTags: String, rules: String,
        score: Int, glossaryJson: String, sequence: Long?, termTags: String,
    ) {
        val term = PendingTerm(expression, reading, alt, definitionTags, rules, score, glossaryJson, sequence, termTags)
        if (encoder == null) {
            pending.add(term)
            if (pending.size >= TRAINING_SAMPLES) startEncoding()
        } else {
            insert(term)
        }
    }

    private fun startEncoding() {
        val codec = GlossaryCodec(GlossaryCodec.trainDictionary(pending.map { it.glossaryJson }))
        codec.presetDictionary?.let { dict ->
            db.compileStatement("INSERT INTO codec (id, dictionary) VALUES (1, ?)").use {
                it.bindBlob(1, dict)
                it.executeInsert()
            }
        }
        encoder = codec.Encoder()
        for (term in pending) insert(term)
        pending.clear()
    }

    private fun insert(t: PendingTerm) {
        val s = insertTerm
        s.clearBindings()
        s.bindString(1, t.expression)
        s.bindString(2, t.reading)
        if (t.alt != null) s.bindString(3, t.alt) else s.bindNull(3)
        s.bindString(4, t.definitionTags)
        s.bindString(5, t.rules)
        s.bindLong(6, t.score.toLong())
        s.bindBlob(7, encoder!!.encode(t.glossaryJson))
        if (t.sequence != null) s.bindLong(8, t.sequence) else s.bindNull(8)
        s.bindString(9, t.termTags)
        s.executeInsert()
        counts = counts.copy(terms = counts.terms + 1)
    }

    override fun termMeta(expression: String, mode: String, dataJson: String) {
        insertTermMeta.clearBindings()
        insertTermMeta.bindString(1, expression)
        insertTermMeta.bindString(2, mode)
        insertTermMeta.bindString(3, dataJson)
        insertTermMeta.executeInsert()
        counts = counts.copy(termMeta = counts.termMeta + 1)
    }

    override fun kanji(character: String, onyomi: String, kunyomi: String, tags: String, meaningsJson: String, statsJson: String) {
        val s = insertKanji
        s.clearBindings()
        s.bindString(1, character)
        s.bindString(2, onyomi)
        s.bindString(3, kunyomi)
        s.bindString(4, tags)
        s.bindString(5, meaningsJson)
        s.bindString(6, statsJson)
        s.executeInsert()
        counts = counts.copy(kanji = counts.kanji + 1)
    }

    override fun kanjiMeta(character: String, mode: String, dataJson: String) {
        insertKanjiMeta.clearBindings()
        insertKanjiMeta.bindString(1, character)
        insertKanjiMeta.bindString(2, mode)
        insertKanjiMeta.bindString(3, dataJson)
        insertKanjiMeta.executeInsert()
        counts = counts.copy(kanjiMeta = counts.kanjiMeta + 1)
    }

    override fun tag(name: String, category: String, order: Int, notes: String, score: Int) {
        val s = insertTag
        s.clearBindings()
        s.bindString(1, name)
        s.bindString(2, category)
        s.bindLong(3, order.toLong())
        s.bindString(4, notes)
        s.bindLong(5, score.toLong())
        s.executeInsert()
        counts = counts.copy(tags = counts.tags + 1)
    }

    override fun media(path: String, data: ByteArray) {
        insertMedia.clearBindings()
        insertMedia.bindString(1, path)
        insertMedia.bindBlob(2, data)
        insertMedia.executeInsert()
        counts = counts.copy(media = counts.media + 1)
    }

    override fun styles(css: String) {
        stylesCss = css
    }

    override fun finish(metadata: DictionaryMetadata) {
        if (encoder == null) startEncoding()
        val info = linkedMapOf(
            "title" to metadata.title,
            "revision" to metadata.revision,
            "format" to metadata.format.toString(),
            "sourceLanguage" to metadata.sourceLanguage.code,
            "targetLanguage" to metadata.targetLanguage,
            "author" to metadata.author,
            "url" to metadata.url,
            "description" to metadata.description,
            "attribution" to metadata.attribution,
            "sequenced" to metadata.sequenced.toString(),
            "isUpdatable" to metadata.isUpdatable.toString(),
            "indexUrl" to metadata.indexUrl,
            "downloadUrl" to metadata.downloadUrl,
            "readingFormat" to metadata.readingFormat,
            "source" to metadata.source,
            "styles" to stylesCss,
        )
        db.compileStatement("INSERT INTO info (key, value) VALUES (?, ?)").use { s ->
            for ((key, value) in info) {
                if (value == null) continue
                s.clearBindings()
                s.bindString(1, key)
                s.bindString(2, value)
                s.executeInsert()
            }
        }
        DictionarySchema.createIndexes(db)
        db.setTransactionSuccessful()
        db.endTransaction()
        db.execSQL("ANALYZE")
        close()
    }

    /** Discards the partially written database. */
    fun abort() {
        if (!closed) {
            if (db.inTransaction()) db.endTransaction()
            close()
        }
        SQLiteDatabase.deleteDatabase(file)
    }

    private fun close() {
        if (closed) return
        closed = true
        encoder?.close()
        listOf(insertTerm, insertTermMeta, insertKanji, insertKanjiMeta, insertTag, insertMedia).forEach { it.close() }
        db.close()
    }

    companion object {
        private const val TRAINING_SAMPLES = 2000
    }
}
