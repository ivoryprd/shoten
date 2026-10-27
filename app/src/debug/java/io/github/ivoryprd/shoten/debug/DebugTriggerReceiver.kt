package io.github.ivoryprd.shoten.debug

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.assist.ShotenAssistService
import io.github.ivoryprd.shoten.dict.DictionaryInstaller
import io.github.ivoryprd.shoten.dict.importer.ImportProgress
import io.github.ivoryprd.shoten.ocr.OcrScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: test hooks for driving the app from adb (the component must be named
 * explicitly, e.g. `am broadcast -n <package>/io.github.ivoryprd.shoten.debug.DebugTriggerReceiver`).
 *  - SHOW_SCREEN_LOOKUP opens the screen lookup without the assist gesture.
 *  - IMPORT_DICTIONARY imports the file at the "path" extra.
 *  - OCR_FILE runs text recognition on the image at the "path" extra and logs the result (with
 *    the "vertical" extra, as vertical text).
 *  - SNAPSHOT draws the app's windows in software and saves a PNG to the "path" extra (default:
 *    cache/snapshot.png) after the optional "delay" (ms). Useful where screencap returns black
 *    frames, as on emulators without GPU acceleration. Needs hidden API access:
 *    `adb shell settings put global hidden_api_policy 1`.
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
                            Log.i(TAG, "line '${line.text}' box=${line.boundingBox} angle=${line.angle} elements=${line.elements.size} symbols=${line.elements.sumOf { it.symbols.size }}")
                            for (element in line.elements) {
                                Log.i(TAG, "  element '${element.text}' box=${element.boundingBox} symbols=" +
                                    element.symbols.joinToString(" ") { "${it.text}${it.boundingBox?.toShortString()}" })
                            }
                        }
                        CoroutineScope(Dispatchers.Default).launch {
                            val result = app.ocr.recognize(bitmap, OcrScript.JAPANESE, intent.getBooleanExtra("vertical", false))
                            Log.i(TAG, "Converted: empty=${result.isEmpty} text=${result.fullText.replace('\n', '|')}")
                            for (block in result.blocks) for (line in block.lines) Log.i(TAG, "converted '${line.text}' box=${line.box} vertical=${line.vertical}")
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "ML Kit failed", it) }
            }
            ACTION_SNAPSHOT -> {
                val out = File(intent.getStringExtra("path") ?: File(context.cacheDir, "snapshot.png").path)
                val pending = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        snapshot(context, out, intent.getBooleanExtra("lines", false))
                    } catch (e: Throwable) {
                        Log.e(TAG, "Snapshot failed", e)
                    } finally {
                        pending.finish()
                    }
                }, intent.getIntExtra("delay", 0).toLong())
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

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi") // Debug builds only.
    private fun snapshot(context: Context, out: File, dumpLines: Boolean) {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>)
            .filterIsInstance<View>()
            // Windows of higher types (such as the assistant's) are above application windows.
            .sortedBy { (it.layoutParams as? WindowManager.LayoutParams)?.type ?: 0 }
        val size = Point()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(size)
        val bitmap = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(96, 96, 96))
        val location = IntArray(2)
        for (view in views) {
            if (!view.isShown || view.width == 0 || view.height == 0) continue
            view.getLocationOnScreen(location)
            canvas.save()
            canvas.translate(location[0].toFloat(), location[1].toFloat())
            try {
                view.draw(canvas)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot draw $view", e)
            }
            canvas.restore()
        }
        // Written to a temporary file first, so that the snapshot appears only when it is complete.
        val tmp = File(out.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(out)
        Log.i(TAG, "Snapshot of ${views.size} windows saved to $out")
        if (dumpLines) views.forEach { dumpLines(it) }
    }

    /** Logs the line metrics of all text views, for checking spacing. */
    private fun dumpLines(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) dumpLines(view.getChildAt(i))
        } else if (view is TextView && view.isShown) {
            val layout = view.layout ?: return
            for (line in 0 until layout.lineCount) {
                val text = layout.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line)).toString().replace('\n', '|')
                Log.i(TAG, "line ${layout.getLineTop(line)}..${layout.getLineBottom(line)} ascent=${layout.getLineAscent(line)} descent=${layout.getLineDescent(line)} '${text.take(40)}'")
            }
        }
    }

    companion object {
        private const val TAG = "DebugTrigger"
        private const val ACTION_SNAPSHOT = "io.github.ivoryprd.shoten.debug.SNAPSHOT"
        private const val ACTION_SHOW = "io.github.ivoryprd.shoten.debug.SHOW_SCREEN_LOOKUP"
        private const val ACTION_IMPORT = "io.github.ivoryprd.shoten.debug.IMPORT_DICTIONARY"
        private const val ACTION_OCR = "io.github.ivoryprd.shoten.debug.OCR_FILE"
    }
}
