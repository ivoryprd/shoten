package io.github.ivoryprd.shoten

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import io.github.ivoryprd.shoten.dict.DictionaryInstaller
import io.github.ivoryprd.shoten.dict.DictionaryRepository
import io.github.ivoryprd.shoten.dict.DictionaryUpdates
import io.github.ivoryprd.shoten.dict.UpdateState
import io.github.ivoryprd.shoten.dict.ImportState
import io.github.ivoryprd.shoten.lookup.LookupEngine
import io.github.ivoryprd.shoten.ocr.CharSuggestions
import io.github.ivoryprd.shoten.ocr.GlyphRenderer
import io.github.ivoryprd.shoten.ocr.OcrEngine
import io.github.ivoryprd.shoten.ocr.SimilarChars
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

class ShotenApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var dictionaries: DictionaryRepository
        private set
    lateinit var lookupEngine: LookupEngine
        private set
    lateinit var ocr: OcrEngine
        private set
    val imports = ImportState()

    /** Checks for newer versions of the installed dictionaries. */
    val updates: UpdateState by lazy {
        UpdateState(DictionaryUpdates({ DictionaryInstaller.fetchText(it) }, DictionaryInstaller::lastModified), dictionaries)
    }

    /** Suggestions for correcting recognized characters, loaded when first used. */
    val charSuggestions: CharSuggestions by lazy { CharSuggestions(SimilarChars { assets.open(it) }, GlyphRenderer()::ink) }

    /** Lookups run one at a time on this thread. */
    val lookupDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lookup").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        dictionaries = DictionaryRepository(File(filesDir, "dictionaries"))
        lookupEngine = LookupEngine(dictionaries.termSource)
        ocr = OcrEngine(prefs)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Free the OCR models and database caches once Shoten is in the background; they are
        // loaded again on the next lookup.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            ocr.release()
            dictionaries.closeAll()
        }
    }

    companion object {
        fun get(context: Context): ShotenApp = context.applicationContext as ShotenApp
    }
}
