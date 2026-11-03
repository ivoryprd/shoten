package io.github.ivoryprd.shoten.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.dict.DictionaryUpdates
import io.github.ivoryprd.shoten.dict.ImportService

/** Download locations of the built-in dictionary choices. */
object KnownDictionaries {
    const val JITENDEX_URL = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip"
    const val CEDICT_URL = DictionaryUpdates.CEDICT_URL
    const val MORE_DICTIONARIES_URL = "https://github.com/MarvNC/yomitan-dictionaries"

    fun downloadJitendex(context: Context) = ImportService.download(context, JITENDEX_URL, "Jitendex")

    fun downloadCedict(context: Context) = ImportService.download(context, CEDICT_URL, "CC-CEDICT")

    /** Downloads and installs [update] in place of the dictionary it updates. */
    fun applyUpdate(context: Context, update: DictionaryUpdates.Update) {
        val d = update.dictionary
        if (d.isUpdatable && d.indexUrl != null) {
            ImportService.update(context, d.id)
        } else {
            ImportService.download(context, update.downloadUrl, d.title, replaceId = d.id)
        }
    }
}

/** Pads the view so its content stays clear of the status and navigation bars (edge-to-edge). */
fun View.applySystemBarPadding(includeTop: Boolean = true, includeBottom: Boolean = true) {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val l: Int
        val t: Int
        val r: Int
        val b: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            l = i.left; t = i.top; r = i.right; b = i.bottom
        } else {
            @Suppress("DEPRECATION")
            l = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            t = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            r = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            b = insets.systemWindowInsetBottom
        }
        v.setPadding(left + l, top + if (includeTop) t else 0, right + r, bottom + if (includeBottom) b else 0)
        insets
    }
    requestApplyInsets()
}

/** Makes the activity draw behind the system bars on all supported versions. */
fun Activity.enableEdgeToEdge() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        @Suppress("DEPRECATION") // Edge-to-edge is the default from Android 15; needed before.
        window.setDecorFitsSystemWindows(false)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, url, Toast.LENGTH_LONG).show()
    }
}

/** Opens the system screen where the default digital assistant is chosen. */
fun Context.openAssistantSettings() {
    val intents = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // Try the next one.
        } catch (e: SecurityException) {
            // Try the next one.
        }
    }
    Toast.makeText(this, R.string.settings_assistant, Toast.LENGTH_SHORT).show()
}
