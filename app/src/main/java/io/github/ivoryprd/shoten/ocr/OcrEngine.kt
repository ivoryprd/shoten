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
import io.github.ivoryprd.shoten.lookup.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /**
     * Recognizes the text in [bitmap]. With [vertical], text is read as vertical text (columns,
     * top to bottom and right to left) as far as it looks like it: see [readVertical].
     */
    suspend fun recognize(bitmap: Bitmap, script: OcrScript, vertical: Boolean = false): OcrResult {
        begin()
        try {
            val input = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            try {
                val start = System.nanoTime()
                val text = process(input, script)
                currentCoroutineContext().ensureActive()
                Log.d(TAG, "$script OCR of ${bitmap.width}x${bitmap.height}: ${text.textBlocks.size} blocks in ${(System.nanoTime() - start) / 1_000_000} ms")
                val result = fitChars(convert(text, bitmap.width, bitmap.height), input)
                if (!vertical) return result
                return try {
                    readVertical(input, result, script)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The text as first read is still worth showing.
                    Log.w(TAG, "Reading vertical text failed", e)
                    result
                }
            } finally {
                if (input !== bitmap) input.recycle()
            }
        } finally {
            end()
        }
    }

    private suspend fun process(image: Bitmap, script: OcrScript): Text = try {
        recognizer(script).process(InputImage.fromBitmap(image, 0)).await()
    } catch (e: MlKitException) {
        if (e.errorCode == MlKitException.UNAVAILABLE) throw OcrModelUnavailableException(e)
        throw e
    }

    /**
     * Reads the vertical text near what [first] found again, column by column: ML Kit reads
     * vertical text, but misreads small characters and misses columns on screentone. The columns
     * and the cells of their characters are found in the image ([VerticalLayout]) and laid out
     * as rows of horizontal text ([VerticalSheet]), which ML Kit reads much more reliably. What
     * this reads replaces the lines of the first pass that the columns cover, unless it reads
     * clearly less; other lines (horizontal text beside the columns, say) are kept.
     */
    private suspend fun readVertical(image: Bitmap, first: OcrResult, script: OcrScript): OcrResult {
        val start = System.nanoTime()
        val firstLines = first.blocks.flatMap { it.lines }
        val seeds = firstLines.mapNotNull { line ->
            line.box?.let { it to line.chars.count { c -> c.box != null && c.text.isNotBlank() } }
        }
        fun columnsIn(region: VerticalLayout.Region): List<VerticalLayout.Column> {
            val pixels = IntArray(region.width * region.height)
            image.getPixels(pixels, 0, region.width, region.left, region.top, region.width, region.height)
            return VerticalLayout.columns(pixels, region)
        }
        val found = ArrayList<List<VerticalLayout.Column>>()
        for (start in VerticalLayout.regions(seeds, image.width, image.height)) {
            var region = start
            var columns = columnsIn(region)
            // Columns cut off at the region's edges, or more beyond: look further.
            currentCoroutineContext().ensureActive()
            for (i in 0 until GROW_REGIONS) {
                region = VerticalLayout.grown(region, columns, image.width, image.height) ?: break
                columns = columnsIn(region)
            }
            // Regions grown into each other find the same columns again.
            val fresh = columns.filter { c -> found.none { columns -> columns.any { overlap(it.box, c.box) } } }
            if (fresh.isNotEmpty()) found += fresh
        }
        if (found.isEmpty()) return first
        currentCoroutineContext().ensureActive()

        val read = VerticalSheet.read(image, found.flatten()) { sheet ->
            convert(process(sheet, script), sheet.width, sheet.height)
        }

        fun coveredBy(columns: List<VerticalLayout.Column>, line: OcrLine) = line.box?.let { VerticalLayout.isCovered(it, columns) } == true
        val blocks = ArrayList<OcrBlock>()
        val replaced = ArrayList<VerticalLayout.Column>()
        var regions = 0
        val judge = Judge(image, if (script == OcrScript.CHINESE) Language.CHINESE else Language.JAPANESE)
        val nativeColumns = firstLines.filter { it.vertical }
        for (columns in found) {
            val lines = columns.sortedByDescending { it.box.right }.mapNotNull { column ->
                val merged = VerticalLayout.merge(column, read[column].orEmpty(), nativeByCell(nativeColumns, column)) { k, candidates ->
                    judge.best(column.cells[k], candidates)
                }
                merged.takeIf { it.isNotEmpty() }?.let { OcrLine(it, column.box, forceVertical = true) }
            }
            val before = firstLines.filter { coveredBy(columns, it) }.sumOf { l -> l.chars.count { it.text.isNotBlank() } }
            if (lines.isNotEmpty() && lines.sumOf { it.chars.size } * 2 >= before) {
                blocks += OcrBlock(lines)
                replaced += columns
                regions++
            }
        }
        for (block in first.blocks) {
            val kept = block.lines.filter { !coveredBy(replaced, it) }
            if (kept.isNotEmpty()) blocks += OcrBlock(kept)
        }
        Log.d(TAG, "Vertical text: ${found.sumOf { it.size }} columns in $regions of ${found.size} regions in ${(System.nanoTime() - start) / 1_000_000} ms")
        return OcrResult(blocks, first.width, first.height)
    }

    /** [result] with its characters' boxes fitted to the ink in [image] ([CharFit]). */
    private fun fitChars(result: OcrResult, image: Bitmap): OcrResult = OcrResult(
        result.blocks.map { block -> OcrBlock(block.lines.map { fitLine(it, image) }) },
        result.width,
        result.height,
    )

    private fun fitLine(line: OcrLine, image: Bitmap): OcrLine {
        val box = line.box ?: return line
        // Room along the line for characters reaching past its box.
        val room = 0.6f * if (line.vertical) box.width else box.height
        val left = (box.left - if (line.vertical) 0f else room).toInt().coerceIn(0, image.width)
        val top = (box.top - if (line.vertical) room else 0f).toInt().coerceIn(0, image.height)
        val right = kotlin.math.ceil(box.right + if (line.vertical) 0f else room).toInt().coerceIn(0, image.width)
        val bottom = kotlin.math.ceil(box.bottom + if (line.vertical) room else 0f).toInt().coerceIn(0, image.height)
        if (right - left < 3 || bottom - top < 3) return line
        return try {
            val pixels = IntArray((right - left) * (bottom - top))
            image.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
            CharFit.fit(line, pixels, right - left, bottom - top, left, top)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Fitting characters failed", e)
            line
        }
    }

    /** The characters of the recognizer's own vertical [lines] in each cell of [column] (by where they are). */
    private fun nativeByCell(lines: List<OcrLine>, column: VerticalLayout.Column): Map<Int, String> {
        val pitch = column.cells.first().height
        val out = HashMap<Int, StringBuilder>()
        for (line in lines) for (c in line.chars) {
            val box = c.box ?: continue
            if (c.text.isBlank() || box.centerX < column.box.left - 0.2f * pitch || box.centerX > column.box.right + 0.2f * pitch) continue
            val k = column.cells.indexOfFirst { box.centerY >= it.top && box.centerY < it.bottom }
            if (k >= 0) out.getOrPut(k) { StringBuilder() }.append(c.text)
        }
        return out.mapValues { it.value.toString() }
    }

    /**
     * Picks, of characters read in a cell of [image], the one that looks most like what is there:
     * the cell is compared with them as the system font draws them (in their vertical forms).
     */
    private inner class Judge(private val image: Bitmap, private val language: Language) {
        private val cells = HashMap<Box, FloatArray?>()

        fun best(cell: Box, candidates: List<String>): String {
            val ink = cells.getOrPut(cell) { CharSuggestions.cellOf(image, cell)?.let(GlyphFeatures::of) } ?: return candidates.first()
            val scores = candidates.map { GlyphFeatures.similarity(ink, glyph(it)) }
            val best = scores.indices.maxBy { scores[it] }
            // The first one unless another looks clearly more like it.
            return if (scores[best] > scores[0] + JUDGE_MARGIN) candidates[best] else candidates[0]
        }

        private fun glyph(text: String): FloatArray = synchronized(glyphs) {
            glyphs.getOrPut("${language.code}:$text") { GlyphFeatures.of(renderer.ink(text, language, vertical = true)) }
        }
    }

    private val renderer by lazy { GlyphRenderer() }
    private val glyphs = object : LinkedHashMap<String, FloatArray>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) = size > 512
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
        val closing = synchronized(this) {
            if (active > 0) return
            recognizers.values.toList().also { recognizers.clear() }
        }
        // Not holding the lock: closing can wait for the recognizer's own threads.
        closing.forEach { runCatching { it.close() } }
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

    private fun overlap(a: Box, b: Box) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    companion object {
        private const val TAG = "OcrEngine"

        /** How many times a region of vertical text grows to take in columns beyond its edges. */
        private const val GROW_REGIONS = 4

        /** How much more alike a character must look than the first one read to be taken instead. */
        private const val JUDGE_MARGIN = 0.02f
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
