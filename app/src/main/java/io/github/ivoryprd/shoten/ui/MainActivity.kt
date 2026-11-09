package io.github.ivoryprd.shoten.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.assist.ShotenAssistService
import io.github.ivoryprd.shoten.dict.ImportState
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.LookupResult
import io.github.ivoryprd.shoten.ocr.OcrModels
import io.github.ivoryprd.shoten.ocr.OcrScript
import io.github.ivoryprd.shoten.render.ResultsView
import io.github.ivoryprd.shoten.util.Cjk
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** Search screen plus setup status (assistant, dictionaries, OCR models). */
class MainActivity : Activity() {
    private val app by lazy { ShotenApp.get(this) }
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var searchInput: EditText
    private lateinit var languageChip: TextView
    private lateinit var resultsView: ResultsView
    private lateinit var lookups: LookupController
    private lateinit var importStatus: TextView
    private lateinit var importProgress: ProgressBar

    private val importListener: (ImportState.Status) -> Unit = { updateImportStatus(it) }
    private val dictionaryListener: () -> Unit = { updateDictionaryCard() }
    private var pendingSearch: Runnable? = null
    private var ocrDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.scroll).applySystemBarPadding()

        resultsView = ResultsView(this)
        findViewById<FrameLayout>(R.id.results_container).apply {
            clipToOutline = true
            addView(resultsView)
        }
        lookups = LookupController(this, scope, resultsView, object : LookupController.Listener {
            override fun onResult(result: LookupResult, nested: Boolean) = updateBackHandling()
        })
        resultsView.showMessage(getString(R.string.search_empty))

        searchInput = findViewById(R.id.search_input)
        languageChip = findViewById(R.id.search_language)
        updateLanguageChip()
        languageChip.setOnClickListener {
            app.prefs.searchLanguage = if (app.prefs.searchLanguage == Language.CHINESE) Language.JAPANESE else Language.CHINESE
            updateLanguageChip()
            search(immediate = true)
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = search(immediate = false)
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(immediate = true)
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.dictionaries_button).setOnClickListener { startActivity(Intent(this, DictionariesActivity::class.java)) }
        findViewById<View>(R.id.manage_dictionaries).setOnClickListener { startActivity(Intent(this, DictionariesActivity::class.java)) }
        findViewById<View>(R.id.settings_button).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.setup_button).setOnClickListener { openAssistantSettings() }
        findViewById<View>(R.id.get_jitendex).setOnClickListener { startDownload { KnownDictionaries.downloadJitendex(this) } }
        findViewById<View>(R.id.get_cedict).setOnClickListener { startDownload { KnownDictionaries.downloadCedict(this) } }
        importStatus = findViewById(R.id.import_status)
        importProgress = findViewById(R.id.import_progress)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val query = intent?.getStringExtra(EXTRA_QUERY) ?: return
        searchInput.setText(query)
        searchInput.setSelection(query.length)
        search(immediate = true)
    }

    override fun onStart() {
        super.onStart()
        app.imports.addListener(importListener)
        app.dictionaries.addListener(dictionaryListener)
        app.updates.addListener(dictionaryListener)
        app.updates.check(maxAge = 7 * 24 * 60 * 60 * 1000L)
        updateSetupCard()
        updateDictionaryCard()
        updateOcrCard()
    }

    override fun onStop() {
        app.imports.removeListener(importListener)
        app.dictionaries.removeListener(dictionaryListener)
        app.updates.removeListener(dictionaryListener)
        super.onStop()
    }

    override fun onDestroy() {
        pendingSearch?.let { handler.removeCallbacks(it) }
        scope.cancel()
        super.onDestroy()
    }

    // Back returns from links followed inside definitions. Android 13+ uses OnBackInvokedCallback
    // (registered only while there is somewhere to go back to); older versions use onBackPressed.
    private var backCallback: Any? = null

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val wanted = lookups.canGoBack
        val current = backCallback as OnBackInvokedCallback?
        if (wanted && current == null) {
            val callback = OnBackInvokedCallback {
                lookups.back()
                updateBackHandling()
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!wanted && current != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(current)
            backCallback = null
        }
    }

    // Only reached on Android 12 and older; newer versions use the OnBackInvokedCallback above.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!lookups.back()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun search(immediate: Boolean) {
        pendingSearch?.let { handler.removeCallbacks(it) }
        val text = searchInput.text.toString().trim()
        if (text.isEmpty()) {
            lookups.cancel()
            resultsView.showMessage(getString(R.string.search_empty))
            return
        }
        val run = Runnable {
            // Kana means Japanese, whatever the chip says.
            val language = if (Cjk.containsKana(text)) Language.JAPANESE else app.prefs.searchLanguage
            lookups.lookup(text, language)
            updateBackHandling()
        }
        pendingSearch = run
        if (immediate) run.run() else handler.postDelayed(run, 180)
    }

    private fun updateLanguageChip() {
        languageChip.text = getString(
            if (app.prefs.searchLanguage == Language.CHINESE) R.string.language_chinese_short else R.string.language_japanese_short,
        )
    }

    private fun updateSetupCard() {
        val active = ShotenAssistService.isActive(this)
        findViewById<TextView>(R.id.setup_status).setText(if (active) R.string.setup_assistant_done else R.string.setup_assistant_todo)
        findViewById<Button>(R.id.setup_button).visibility = View.VISIBLE
    }

    private fun updateDictionaryCard() {
        val installed = app.dictionaries.installed
        findViewById<TextView>(R.id.dictionary_status).text = if (installed.isEmpty()) {
            getString(R.string.setup_dictionaries_none)
        } else {
            resources.getQuantityString(R.plurals.setup_dictionaries_count, installed.size, installed.size) + "\n" +
                installed.joinToString("\n") { "• " + it.title } +
                if (app.updates.available.isNotEmpty()) "\n\n" + getString(R.string.setup_updates_available) else ""
        }
        val hasJa = installed.any { it.language == Language.JAPANESE && it.terms > 0 }
        val hasZh = installed.any { it.language == Language.CHINESE && it.terms > 0 }
        findViewById<View>(R.id.get_jitendex).visibility = if (hasJa) View.GONE else View.VISIBLE
        findViewById<View>(R.id.get_cedict).visibility = if (hasZh) View.GONE else View.VISIBLE
    }

    private fun updateImportStatus(status: ImportState.Status) {
        val message = when {
            status.running -> status.label + if (status.queued > 1) " · " + resources.getQuantityString(R.plurals.import_queued, status.queued - 1, status.queued - 1) else ""
            status.lastMessage != null -> status.lastMessage
            else -> null
        }
        importStatus.visibility = if (message == null) View.GONE else View.VISIBLE
        importStatus.text = message
        importProgress.visibility = if (status.running) View.VISIBLE else View.GONE
        importProgress.isIndeterminate = status.fraction < 0
        if (status.fraction >= 0) importProgress.progress = (status.fraction * 1000).toInt()
    }

    private fun updateOcrCard() {
        val card = findViewById<View>(R.id.ocr_card)
        if (!OcrModels.DOWNLOADED_BY_PLAY_SERVICES) {
            card.visibility = View.GONE
            return
        }
        val status = findViewById<TextView>(R.id.ocr_status)
        val button = findViewById<Button>(R.id.ocr_download)
        if (ocrDownloading) return
        val scripts = listOf(OcrScript.JAPANESE, OcrScript.CHINESE)
        var remaining = scripts.size
        var allAvailable = true
        try {
            for (script in scripts) {
                OcrModels.checkAvailable(this, app.ocr.recognizer(script)) { available ->
                    if (!available) allAvailable = false
                    if (--remaining == 0 && !isDestroyed) {
                        card.visibility = if (allAvailable) View.GONE else View.VISIBLE
                        status.setText(if (allAvailable) R.string.ocr_models_ready else R.string.ocr_models_missing)
                        button.visibility = if (allAvailable) View.GONE else View.VISIBLE
                    }
                }
            }
        } catch (e: Exception) {
            // The status card is informational; never let ML Kit problems stop the app.
            Log.e(TAG, "Cannot check the OCR models", e)
            card.visibility = View.GONE
            return
        }
        button.setOnClickListener { downloadOcrModels(scripts) }
    }

    private fun downloadOcrModels(scripts: List<OcrScript>) {
        val status = findViewById<TextView>(R.id.ocr_status)
        val button = findViewById<Button>(R.id.ocr_download)
        button.isEnabled = false
        ocrDownloading = true
        val queue = ArrayDeque(scripts)
        fun next() {
            val script = queue.removeFirstOrNull()
            if (script == null) {
                ocrDownloading = false
                button.isEnabled = true
                if (!isDestroyed) updateOcrCard()
                return
            }
            val recognizer = try {
                app.ocr.recognizer(script)
            } catch (e: Exception) {
                ocrDownloading = false
                button.isEnabled = true
                status.text = getString(R.string.ocr_models_failed, e.message ?: e.javaClass.simpleName)
                return
            }
            OcrModels.install(this, recognizer, { percent ->
                if (!isDestroyed) status.text = getString(R.string.ocr_models_downloading, if (percent >= 0) "$percent%" else "")
            }) { ok, error ->
                if (isDestroyed) return@install
                if (ok) {
                    // The recognizer made before the download may not see the model.
                    app.ocr.forget(script)
                    next()
                } else {
                    ocrDownloading = false
                    button.isEnabled = true
                    status.text = getString(R.string.ocr_models_failed, error ?: "")
                }
            }
        }
        next()
    }

    /** Asks for the notification permission (for download progress) and then starts [download]. */
    private fun startDownload(download: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        download()
    }

    companion object {
        const val EXTRA_QUERY = "query"
        private const val TAG = "MainActivity"
    }
}
