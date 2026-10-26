package io.github.ivoryprd.shoten.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import io.github.ivoryprd.shoten.Prefs
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.EnumMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class OcrScript { JAPANESE, CHINESE }

/** Thrown when the OCR model is not available yet (it is being downloaded by Play services). */
class OcrModelUnavailableException(cause: Throwable) : Exception(cause.message, cause)

/**
 * On-device text recognition with ML Kit. Recognizers are kept loaded for a while after use so
 * that repeated lookups are fast, then released to save memory.
 */
class OcrEngine(private val prefs: Prefs) {
    private val recognizers = EnumMap<OcrScript, TextRecognizer>(OcrScript::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var active = 0
    private val releaseRunnable = Runnable { releaseIfIdle() }

    fun recognizer(script: OcrScript): TextRecognizer = synchronized(this) {
        recognizers.getOrPut(script) {
            when (script) {
                OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            }
        }
    }

    suspend fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult {
        begin()
        try {
            val input = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            val start = System.nanoTime()
            val text = try {
                recognizer(script).process(InputImage.fromBitmap(input, 0)).await()
            } catch (e: MlKitException) {
                if (e.errorCode == MlKitException.UNAVAILABLE) throw OcrModelUnavailableException(e)
                throw e
            } finally {
                if (input !== bitmap) input.recycle()
            }
            Log.d(TAG, "$script OCR of ${bitmap.width}x${bitmap.height}: ${text.textBlocks.size} blocks in ${(System.nanoTime() - start) / 1_000_000} ms")
            return convert(text, bitmap.width, bitmap.height)
        } finally {
            end()
        }
    }

    /**
     * Loads the model in the background so the first real recognition is faster. Returns at once:
     * creating a recognizer can take a while (loading Play services' module), which would stall
     * animations on the main thread.
     */
    fun warmUp(script: OcrScript) {
        begin()
        WARM_UP_EXECUTOR.execute {
            val tiny = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                recognizer(script).process(InputImage.fromBitmap(tiny, 0)).addOnCompleteListener {
                    tiny.recycle()
                    end()
                }
            } catch (e: Exception) {
                // Only an optimization; the real recognition reports errors to the user.
                Log.w(TAG, "OCR warm-up failed", e)
                tiny.recycle()
                end()
            }
        }
    }

    /** Releases all models (e.g. when the system is low on memory). */
    fun release() {
        synchronized(this) {
            if (active > 0) return
            recognizers.values.forEach { it.close() }
            recognizers.clear()
        }
    }

    private fun begin() {
        synchronized(this) { active++ }
        handler.removeCallbacks(releaseRunnable)
    }

    private fun end() {
        synchronized(this) { active-- }
        handler.removeCallbacks(releaseRunnable)
        handler.postDelayed(releaseRunnable, prefs.keepOcrMinutes * 60_000L + 1_000L)
    }

    private fun releaseIfIdle() = release()

    private fun convert(text: Text, width: Int, height: Int): OcrResult {
        val blocks = text.textBlocks.map { block ->
            OcrBlock(
                block.lines.map { line ->
                    val elements = line.elements
                    if (elements.isEmpty()) {
                        OcrResult.buildLine(listOf(line.text to line.boundingBox?.toBox()), null, line.boundingBox?.toBox())
                    } else {
                        OcrResult.buildLine(
                            elements.map { it.text to it.boundingBox?.toBox() },
                            elements.map { e -> e.symbols.map { it.text to it.boundingBox?.toBox() } },
                            line.boundingBox?.toBox(),
                        )
                    }
                },
            )
        }
        return OcrResult(blocks, width, height)
    }

    private fun Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        private const val TAG = "OcrEngine"
        private val WARM_UP_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        when {
            e != null -> cont.resumeWithException(e)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
