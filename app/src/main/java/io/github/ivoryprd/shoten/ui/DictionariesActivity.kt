package io.github.ivoryprd.shoten.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.dict.DictionaryUpdates
import io.github.ivoryprd.shoten.dict.ImportService
import io.github.ivoryprd.shoten.dict.ImportState
import io.github.ivoryprd.shoten.dict.InstalledDictionary
import io.github.ivoryprd.shoten.lookup.Language
import java.text.NumberFormat

/** Lists installed dictionaries and offers downloads and imports. */
class DictionariesActivity : Activity() {
    private val app by lazy { ShotenApp.get(this) }
    private lateinit var list: LinearLayout
    private lateinit var jitendexCard: View
    private lateinit var cedictCard: View
    private lateinit var kanjidicCard: View
    private lateinit var progressCard: LinearLayout
    private lateinit var updatesCard: LinearLayout
    private lateinit var updatesText: TextView
    private lateinit var checkButton: Button
    private lateinit var updateAllButton: Button
    private val updatesListener: () -> Unit = {
        updateUpdatesCard()
        rebuildList()
    }
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cancelButton: Button
    private val density by lazy { resources.displayMetrics.density }

    private val importListener: (ImportState.Status) -> Unit = { updateProgress(it) }
    private val dictionaryListener: () -> Unit = { rebuildList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(content)
        setContentView(scroll)
        scroll.applySystemBarPadding()

        content.addView(header(getString(R.string.dictionaries_title)))

        updatesCard = card()
        updatesText = body("")
        updatesCard.addView(updatesText)
        val updateButtons = LinearLayout(this).apply { gravity = Gravity.END }
        checkButton = textButton(getString(R.string.action_check_updates)) { app.updates.check() }
        updateAllButton = textButton(getString(R.string.action_update_all)) {
            withNotificationPermission { app.updates.available.forEach { KnownDictionaries.applyUpdate(this, it) } }
        }
        updateButtons.addView(checkButton)
        updateButtons.addView(updateAllButton)
        updatesCard.addView(updateButtons)
        content.addView(updatesCard)

        jitendexCard = actionCard(getString(R.string.get_jitendex), getString(R.string.get_jitendex_summary)) {
            withNotificationPermission { KnownDictionaries.downloadJitendex(this) }
        }
        content.addView(jitendexCard)
        cedictCard = actionCard(getString(R.string.get_cedict), getString(R.string.get_cedict_summary)) {
            withNotificationPermission { KnownDictionaries.downloadCedict(this) }
        }
        content.addView(cedictCard)
        kanjidicCard = actionCard(getString(R.string.get_kanjidic), getString(R.string.get_kanjidic_summary)) {
            withNotificationPermission { KnownDictionaries.downloadKanjidic(this) }
        }
        content.addView(kanjidicCard)
        val importCard = actionCard(getString(R.string.import_file), getString(R.string.import_file_summary)) { pickFile() }
        importCard.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.more_dictionaries)
            isAllCaps = false
            setTextColor(getColor(R.color.accent))
            setOnClickListener { openUrl(KnownDictionaries.MORE_DICTIONARIES_URL) }
        })
        content.addView(importCard)

        progressCard = card().apply { visibility = View.GONE }
        progressText = body("")
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        cancelButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.action_cancel_import)
            isAllCaps = false
            setTextColor(getColor(R.color.accent))
            setOnClickListener { ImportService.cancel(this@DictionariesActivity) }
        }
        progressCard.addView(progressText)
        progressCard.addView(progressBar)
        progressCard.addView(cancelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        content.addView(progressCard)

        content.addView(TextView(this).apply {
            text = getString(R.string.dictionaries_installed)
            setTextColor(getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(20), 0, dp(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
    }

    override fun onStart() {
        super.onStart()
        app.imports.addListener(importListener)
        app.dictionaries.addListener(dictionaryListener)
        app.updates.addListener(updatesListener)
        // Jitendex is updated about monthly, CC-CEDICT daily: once a day is often enough.
        app.updates.check(maxAge = 24 * 60 * 60 * 1000L)
        updateUpdatesCard()
        rebuildList()
    }

    override fun onStop() {
        app.imports.removeListener(importListener)
        app.dictionaries.removeListener(dictionaryListener)
        app.updates.removeListener(updatesListener)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        withNotificationPermission { ImportService.import(this, uri) }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/gzip", "application/x-gzip", "text/plain", "application/octet-stream"),
            )
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_FILE)
        } catch (e: android.content.ActivityNotFoundException) {
            progressCard.visibility = View.VISIBLE
            progressText.text = e.message
        }
    }

    private fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        action()
    }

    private fun updateProgress(status: ImportState.Status) {
        val message = when {
            status.running -> status.label + if (status.queued > 1) " · " + resources.getQuantityString(R.plurals.import_queued, status.queued - 1, status.queued - 1) else ""
            else -> status.lastMessage
        }
        progressCard.visibility = if (message == null) View.GONE else View.VISIBLE
        progressText.text = message
        progressBar.visibility = if (status.running) View.VISIBLE else View.GONE
        progressBar.isIndeterminate = status.fraction < 0
        if (status.fraction >= 0) progressBar.progress = (status.fraction * 1000).toInt()
        cancelButton.visibility = if (status.running) View.VISIBLE else View.GONE
    }

    private fun updateUpdatesCard() {
        val updates = app.updates
        updatesCard.visibility = if (updates.hasUpdatable) View.VISIBLE else View.GONE
        val available = updates.available
        updatesText.text = when {
            updates.checking -> getString(R.string.updates_checking)
            available.isNotEmpty() -> resources.getQuantityString(R.plurals.updates_available, available.size, available.size) + "\n" +
                available.joinToString("\n") { "• " + it.dictionary.title + " → " + it.revision }
            updates.lastCheck == 0L -> getString(R.string.updates_unchecked)
            updates.lastCheckFailed -> getString(R.string.updates_failed)
            else -> getString(R.string.updates_none)
        }
        checkButton.isEnabled = !updates.checking
        updateAllButton.visibility = if (available.isNotEmpty() && !updates.checking) View.VISIBLE else View.GONE
    }

    private fun textButton(label: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        setTextColor(getColor(R.color.accent))
        setOnClickListener { onClick() }
    }

    private fun rebuildList() {
        val installed = app.dictionaries.installed
        jitendexCard.visibility = if (installed.any { it.title.startsWith("Jitendex") }) View.GONE else View.VISIBLE
        cedictCard.visibility = if (installed.any { it.title == "CC-CEDICT" }) View.GONE else View.VISIBLE
        kanjidicCard.visibility = if (installed.any { it.kanji > 0 }) View.GONE else View.VISIBLE
        list.removeAllViews()
        if (installed.isEmpty()) {
            list.addView(body(getString(R.string.dictionaries_empty)).apply { setPadding(dp(4), 0, 0, 0) })
            return
        }
        installed.forEachIndexed { index, d -> list.addView(row(d, index, installed.size)) }
    }

    private fun row(d: InstalledDictionary, index: Int, count: Int): View {
        val card = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = d.title
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        val number = NumberFormat.getIntegerInstance()
        val details = when {
            d.terms > 0 -> getString(R.string.dictionary_details, languageName(d.language), d.revision, number.format(d.terms))
            d.kanji > 0 -> getString(R.string.dictionary_details_kanji, languageName(d.language), d.revision, number.format(d.kanji))
            else -> getString(R.string.dictionary_details_meta, languageName(d.language), d.revision, number.format(d.termMeta))
        }
        texts.addView(body(details))
        val update = app.updates.available.firstOrNull { it.dictionary.id == d.id }
        if (update != null) {
            texts.addView(body(getString(R.string.dictionary_update_available, update.revision)).apply { setTextColor(getColor(R.color.accent)) })
        }
        top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        @Suppress("DEPRECATION")
        val toggle = Switch(this).apply {
            isChecked = d.enabled
            contentDescription = d.title
            setOnCheckedChangeListener { _, checked -> app.dictionaries.setEnabled(d.id, checked) }
        }
        top.addView(toggle)
        card.addView(top)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        if (update != null) {
            actions.addView(textButton(getString(R.string.action_update)) { withNotificationPermission { KnownDictionaries.applyUpdate(this, update) } })
        }
        actions.addView(iconButton(R.drawable.ic_expand_less, R.string.action_move_up, index > 0) { app.dictionaries.move(d.id, -1) })
        actions.addView(iconButton(R.drawable.ic_expand_more, R.string.action_move_down, index < count - 1) { app.dictionaries.move(d.id, 1) })
        lateinit var more: ImageView
        more = iconButton(R.drawable.ic_more, R.string.action_more, true) { showMenu(d, more) }
        actions.addView(more)
        card.addView(actions)
        return card
    }

    private fun showMenu(d: InstalledDictionary, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_INFO, 0, R.string.action_info)
        if ((d.isUpdatable && d.indexUrl != null) || d.title == DictionaryUpdates.CEDICT_TITLE) menu.menu.add(0, MENU_UPDATE, 1, R.string.action_check_update)
        val languages = menu.menu.addSubMenu(0, MENU_LANGUAGE, 2, getString(R.string.action_language, languageName(d.language)))
        languages.add(0, MENU_LANG_JA, 0, R.string.language_japanese)
        languages.add(0, MENU_LANG_ZH, 1, R.string.language_chinese)
        languages.add(0, MENU_LANG_OTHER, 2, R.string.language_other)
        menu.menu.add(0, MENU_DELETE, 3, R.string.action_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_INFO -> showInfo(d)
                MENU_UPDATE -> if (d.isUpdatable && d.indexUrl != null) {
                    withNotificationPermission { ImportService.update(this, d.id) }
                } else {
                    app.updates.check()
                }
                MENU_LANG_JA -> app.dictionaries.setLanguage(d.id, Language.JAPANESE)
                MENU_LANG_ZH -> app.dictionaries.setLanguage(d.id, Language.CHINESE)
                MENU_LANG_OTHER -> app.dictionaries.setLanguage(d.id, Language.OTHER)
                MENU_DELETE -> confirmDelete(d)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.show()
    }

    private fun showInfo(d: InstalledDictionary) {
        val text = listOfNotNull(
            d.revision.takeIf { it.isNotBlank() },
            d.description,
            d.attribution,
            d.url,
        ).joinToString("\n\n")
        val message = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(24), dp(12), dp(24), dp(4))
            setTextIsSelectable(true)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }
        AlertDialog.Builder(this)
            .setTitle(d.title)
            .setView(ScrollView(this).apply { addView(message) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete(d: InstalledDictionary) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, d.title))
            .setPositiveButton(R.string.action_delete) { _, _ -> app.dictionaries.delete(d.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun languageName(language: Language) = getString(
        when (language) {
            Language.JAPANESE -> R.string.language_japanese
            Language.CHINESE -> R.string.language_chinese
            Language.OTHER -> R.string.language_other
        },
    )

    // --- View helpers -----------------------------------------------------------------------

    private fun dp(v: Int) = (v * density).toInt()

    private fun header(title: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        row.addView(iconButton(R.drawable.ic_back, R.string.action_back, true) { finish() })
        row.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        })
        return row
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(16), dp(14), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun actionCard(title: String, summary: String, onClick: () -> Unit): LinearLayout {
        val card = card()
        card.addView(body(summary).apply { setPadding(0, 0, dp(4), dp(4)) })
        card.addView(Button(this, null, 0, R.style.Shoten_Button).apply {
            text = title
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun iconButton(icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        contentDescription = getString(label)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.3f
        setColorFilter(getColor(R.color.text_secondary))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        setBackgroundResource(outValue.resourceId)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    companion object {
        private const val REQUEST_FILE = 10
        private const val MENU_INFO = 1
        private const val MENU_UPDATE = 2
        private const val MENU_LANGUAGE = 3
        private const val MENU_DELETE = 4
        private const val MENU_LANG_JA = 5
        private const val MENU_LANG_ZH = 6
        private const val MENU_LANG_OTHER = 7
    }
}
