package io.github.ivoryprd.shoten

import android.content.Context
import io.github.ivoryprd.shoten.lookup.Language

/** Which OCR script to use for the screen lookup. */
enum class OcrMode { AUTO, JAPANESE, CHINESE }

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var ocrMode: OcrMode
        get() = runCatching { OcrMode.valueOf(sp.getString(KEY_OCR_MODE, null) ?: "") }.getOrDefault(OcrMode.AUTO)
        set(value) = sp.edit().putString(KEY_OCR_MODE, value.name).apply()

    /** The language the user last switched to in the screen lookup (used by [OcrMode.AUTO]). */
    var lastScreenLanguage: Language
        get() = Language.fromCode(sp.getString(KEY_LAST_LANGUAGE, "ja")).takeIf { it != Language.OTHER } ?: Language.JAPANESE
        set(value) = sp.edit().putString(KEY_LAST_LANGUAGE, value.code).apply()

    /** Language for the in-app search box. */
    var searchLanguage: Language
        get() = Language.fromCode(sp.getString(KEY_SEARCH_LANGUAGE, "ja")).takeIf { it != Language.OTHER } ?: Language.JAPANESE
        set(value) = sp.edit().putString(KEY_SEARCH_LANGUAGE, value.code).apply()

    /** Read screen text as vertical text (columns, right to left), as in manga and vertical novels. */
    var verticalText: Boolean
        get() = sp.getBoolean(KEY_VERTICAL_TEXT, false)
        set(value) = sp.edit().putBoolean(KEY_VERTICAL_TEXT, value).apply()

    var textScale: Float
        get() = sp.getFloat(KEY_TEXT_SCALE, 1f).coerceIn(0.7f, 1.6f)
        set(value) = sp.edit().putFloat(KEY_TEXT_SCALE, value).apply()

    var showTextBoxes: Boolean
        get() = sp.getBoolean(KEY_SHOW_BOXES, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_BOXES, value).apply()

    var showFrequencies: Boolean
        get() = sp.getBoolean(KEY_SHOW_FREQUENCIES, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_FREQUENCIES, value).apply()

    var showPitch: Boolean
        get() = sp.getBoolean(KEY_SHOW_PITCH, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_PITCH, value).apply()

    var showExamples: Boolean
        get() = sp.getBoolean(KEY_SHOW_EXAMPLES, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_EXAMPLES, value).apply()

    /** Minutes to keep OCR models in memory after a lookup (faster next time, more memory). */
    var keepOcrMinutes: Int
        get() = sp.getInt(KEY_KEEP_OCR, 5).coerceIn(0, 60)
        set(value) = sp.edit().putInt(KEY_KEEP_OCR, value).apply()

    companion object {
        private const val KEY_OCR_MODE = "ocr_mode"
        private const val KEY_LAST_LANGUAGE = "last_screen_language"
        private const val KEY_SEARCH_LANGUAGE = "search_language"
        private const val KEY_VERTICAL_TEXT = "vertical_text"
        private const val KEY_TEXT_SCALE = "text_scale"
        private const val KEY_SHOW_BOXES = "show_text_boxes"
        private const val KEY_SHOW_FREQUENCIES = "show_frequencies"
        private const val KEY_SHOW_PITCH = "show_pitch"
        private const val KEY_SHOW_EXAMPLES = "show_examples"
        private const val KEY_KEEP_OCR = "keep_ocr_minutes"
    }
}
