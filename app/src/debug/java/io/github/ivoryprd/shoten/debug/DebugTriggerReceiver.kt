package io.github.ivoryprd.shoten.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.assist.ShotenAssistService
import io.github.ivoryprd.shoten.dict.DictionaryInstaller
import io.github.ivoryprd.shoten.dict.importer.ImportProgress
import io.github.ivoryprd.shoten.ocr.OcrScript
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: test hooks for driving the app from adb (the component must be named
 * explicitly, e.g. `am broadcast -n <package>/io.github.ivoryprd.shoten.debug.DebugTriggerReceiver`).
 *  - SHOW_SCREEN_LOOKUP opens the screen lookup without the assist gesture.
 *  - IMPORT_DICTIONARY imports the file at the "path" extra.
 *  - OCR_FILE runs text recognition on the image at the "path" extra and logs the result.
 */
class DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SHOW -> {
                val service = ShotenAssistService.instance
                if (service == null) Log.w(TAG, "Shoten is not the active digital assistant") else service.showScreenLookup()
            }
            ACTION_OCR -> {
                val path = intent.getStringExtra("path") ?: return
                val app = ShotenApp.get(context)
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap == null) {
                    Log.e(TAG, "Cannot decode $path")
                    return
                }
                Log.i(TAG, "Bitmap ${bitmap.width}x${bitmap.height} ${bitmap.config}")
                app.ocr.recognizer(OcrScript.JAPANESE).process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        Log.i(TAG, "ML Kit raw: ${text.textBlocks.size} blocks: ${text.text.replace('\n', '|')}")
                        for (block in text.textBlocks) for (line in block.lines) {
                            Log.i(TAG, "line '${line.text}' box=${line.boundingBox} elements=${line.elements.size} symbols=${line.elements.sumOf { it.symbols.size }}")
                        }
                        CoroutineScope(Dispatchers.Default).launch {
                            val result = app.ocr.recognize(bitmap, OcrScript.JAPANESE)
                            Log.i(TAG, "Converted: empty=${result.isEmpty} text=${result.fullText.replace('\n', '|')}")
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "ML Kit failed", it) }
            }
            ACTION_IMPORT -> {
                val path = intent.getStringExtra("path") ?: return
                val app = ShotenApp.get(context)
                Thread {
                    try {
                        val installed = DictionaryInstaller(app.dictionaries).install(File(path), ImportProgress.None)
                        Log.i(TAG, "Imported ${installed.title} (${installed.terms} terms)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Import failed", e)
                    }
                }.start()
            }
        }
    }

    companion object {
        private const val TAG = "DebugTrigger"
        private const val ACTION_SHOW = "io.github.ivoryprd.shoten.debug.SHOW_SCREEN_LOOKUP"
        private const val ACTION_IMPORT = "io.github.ivoryprd.shoten.debug.IMPORT_DICTIONARY"
        private const val ACTION_OCR = "io.github.ivoryprd.shoten.debug.OCR_FILE"
    }
}
