package io.github.ivoryprd.shoten.ocr

import android.content.Context
import com.google.mlkit.vision.text.TextRecognizer

/** The "offline" build bundles the OCR models in the APK, so they are always available. */
object OcrModels {
    const val DOWNLOADED_BY_PLAY_SERVICES = false

    fun checkAvailable(context: Context, recognizer: TextRecognizer, callback: (Boolean) -> Unit) {
        callback(true)
    }

    fun install(context: Context, recognizer: TextRecognizer, onProgress: (Int) -> Unit, onDone: (Boolean, String?) -> Unit) {
        onDone(true, null)
    }
}
