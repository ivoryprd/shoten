package io.github.ivoryprd.shoten.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Lines of horizontal text read again on a sheet of their own: each cut out of the image, in gray,
 * scaled so that its characters are [EM] pixels tall, as rows far enough apart to be read as
 * separate lines. The recognizer reads unusual fonts (brush, display, serif) better so than as they
 * are on the page, if not always: of the readings of a line, the one it is surer of is kept, if it
 * has as many characters. Lines it is still unsure of are read once more as outlined text
 * ([Look.OUTLINED]): their letters made out from their outlines, solid black on white, which the
 * recognizer reads far better than heavy outlined fonts on photos.
 */
internal class LineSheet private constructor(val bitmap: Bitmap, private val rows: List<Row>) {
    /** A row of a sheet: [line], cut out of the image at [crop] and drawn at [top], [scale] times as large. */
    class Row(val line: OcrLine, val crop: Rect, val scale: Float, val top: Int)

    /** How lines are drawn on a sheet: as they are (in gray), or as outlined text ([outlined]). */
    enum class Look { GRAY, OUTLINED }

    /** For each row, what was read in it, in the image's coordinates (null: nothing). */
    fun mapBack(read: OcrResult): List<OcrLine?> {
        val found = rows.map { ArrayList<OcrLine>() }
        for (block in read.blocks) for (line in block.lines) {
            val box = line.box ?: continue
            val row = rows.indexOfFirst { box.centerY >= it.top - EM / 2f && box.centerY < it.top + EM * 1.5f }
            if (row >= 0) found[row] += line
        }
        return rows.mapIndexed { i, row ->
            val lines = found[i].sortedBy { it.box?.left ?: 0f }
            if (lines.isEmpty()) return@mapIndexed null
            fun Box.back() = Box(
                row.crop.left + (left - PAD) / row.scale,
                row.crop.top + (top - row.top) / row.scale,
                row.crop.left + (right - PAD) / row.scale,
                row.crop.top + (bottom - row.top) / row.scale,
            )
            val chars = ArrayList<OcrChar>()
            for (line in lines) {
                if (chars.isNotEmpty() && chars.last().text.isNotBlank()) chars += OcrChar(" ", null)
                for (c in line.chars) chars += OcrChar(c.text, c.box?.back(), c.confidence, c.read?.back())
            }
            OcrLine(chars, lines.mapNotNull { it.box }.reduce { a, b -> a.union(b) }.back())
        }
    }

    companion object {
        /** The size of the characters on a sheet, which the recognizer reads unusual fonts best at. */
        const val EM = 48
        private const val PAD = 30
        private const val ROW_PITCH = 2 * EM

        /** Rows per sheet, and the widest a row may be. */
        private const val ROWS = 16
        private const val MAX_WIDTH = 2400

        /** Lines of a recognizer's reading whose characters it is on average less sure of than this are read again. */
        const val UNSURE = 0.6f

        /** How much surer of a line the recognizer must be on the sheet for that reading to be kept. */
        private const val MARGIN = 0.02f

        /** The average confidence of the recognizer in [line]'s characters, or null if it gave none. */
        fun confidence(line: OcrLine): Float? {
            val chars = line.chars.filter { it.text.isNotBlank() }
            if (chars.isEmpty() || chars.all { it.confidence == 1f }) return null
            return chars.sumOf { it.confidence.toDouble() }.toFloat() / chars.size
        }

        /**
         * Reads [lines] of [image] again with [recognize], on as many sheets as they take, in each
         * [Look] (in the next only those it is still [UNSURE] of): for each line, the reading of it
         * to keep (the first one, unless the recognizer is surer of another with as many characters).
         */
        suspend fun reread(image: Bitmap, lines: List<OcrLine>, recognize: suspend (Bitmap) -> OcrResult): List<OcrLine> {
            val out = lines.toMutableList()
            val best = lines.map { confidence(it) }.toMutableList()
            fun count(line: OcrLine) = line.chars.count { it.text.isNotBlank() }
            val candidates = lines.indices.filter { lines[it].box != null && !lines[it].vertical && best[it] != null }
            for (look in Look.entries) for (chunk in candidates.filter { look == Look.GRAY || best[it]!! < UNSURE }.chunked(ROWS)) {
                currentCoroutineContext().ensureActive()
                val sheet = create(image, chunk.map { lines[it] }, look) ?: continue
                val read = try {
                    sheet.mapBack(recognize(sheet.bitmap))
                } finally {
                    sheet.bitmap.recycle()
                }
                chunk.forEachIndexed { k, i ->
                    val again = read[k] ?: return@forEachIndexed
                    val after = confidence(again) ?: return@forEachIndexed
                    // As many characters (one left out or added would move the others), read
                    // with more confidence.
                    if (count(again) == count(lines[i]) && after > best[i]!! + MARGIN) {
                        out[i] = again
                        best[i] = after
                    }
                }
            }
            return out
        }

        /** Lays out [lines] (at most [ROWS]) of [image] on a sheet, or null if there is nothing to lay out. */
        fun create(image: Bitmap, lines: List<OcrLine>, look: Look = Look.GRAY): LineSheet? {
            val rows = ArrayList<Row>()
            var width = 0
            for (line in lines) {
                val box = line.box ?: continue
                val em = box.height
                if (em < 2f) continue
                // The line with some room around it, as the recognizer saw it.
                val crop = Rect(
                    floor(box.left - 0.4f * em).toInt().coerceIn(0, image.width),
                    floor(box.top - 0.25f * em).toInt().coerceIn(0, image.height),
                    ceil(box.right + 0.4f * em).toInt().coerceIn(0, image.width),
                    ceil(box.bottom + 0.25f * em).toInt().coerceIn(0, image.height),
                )
                if (crop.isEmpty) continue
                val scale = min(EM / em, MAX_WIDTH / crop.width().toFloat())
                rows += Row(line, crop, scale, PAD + rows.size * ROW_PITCH)
                width = max(width, ceil(crop.width() * scale).toInt())
            }
            if (rows.isEmpty()) return null
            val bitmap = Bitmap.createBitmap(2 * PAD + width, 2 * PAD + (rows.size - 1) * ROW_PITCH + 2 * EM, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val gray = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            for (row in rows) {
                val dst = RectF(PAD.toFloat(), row.top.toFloat(), PAD + row.crop.width() * row.scale, row.top + row.crop.height() * row.scale)
                when (look) {
                    Look.GRAY -> canvas.drawBitmap(image, row.crop, dst, gray)
                    Look.OUTLINED -> {
                        val w = row.crop.width()
                        val h = row.crop.height()
                        val pixels = IntArray(w * h)
                        image.getPixels(pixels, 0, w, row.crop.left, row.crop.top, w, h)
                        val box = row.line.box!!
                        val band = (box.top - row.crop.top + 0.15f * box.height).toInt()..(box.bottom - row.crop.top - 0.15f * box.height).toInt()
                        val letters = Bitmap.createBitmap(outlined(pixels, w, h, band), w, h, Bitmap.Config.ARGB_8888)
                        canvas.drawBitmap(letters, null, dst, gray)
                        letters.recycle()
                    }
                }
            }
            return LineSheet(bitmap, rows)
        }

        /**
         * [pixels] (ARGB, [width] × [height]: a line of text with some room around it, its middle
         * rows [band]) as outlined text: the outline is the darkest there is in the line, and what
         * it encloses, reached from the edges by crossing it an odd number of times, the letters
         * (solid black; the rest white). Holes inside letters (の, 日) are crossed twice.
         */
        fun outlined(pixels: IntArray, width: Int, height: Int, band: IntRange): IntArray {
            val lum = IntArray(pixels.size) { i ->
                val p = pixels[i]
                (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
            }
            val histogram = IntArray(256)
            var total = 0
            for (y in band.first.coerceAtLeast(0)..band.last.coerceAtMost(height - 1)) for (x in 0 until width) {
                histogram[lum[y * width + x]]++
                total++
            }
            var dark = 0
            var count = 0
            for (l in 0..255) {
                count += histogram[l]
                if (count >= 0.05f * total) {
                    dark = l
                    break
                }
            }
            val wall = BooleanArray(pixels.size) { lum[it] < dark + 60 }
            // Crossings from the edges: 0-1 breadth first (stepping off the outline into a region costs one).
            val depth = IntArray(pixels.size) { Int.MAX_VALUE }
            val queue = ArrayDeque<Int>()
            for (i in pixels.indices) {
                val x = i % width
                val y = i / width
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    depth[i] = 0
                    queue.addLast(i)
                }
            }
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val x = i % width
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < width - 1) i + 1 else -1, i - width, i + width)) {
                    if (j < 0 || j >= pixels.size) continue
                    val step = if (wall[i] && !wall[j]) 1 else 0
                    if (depth[i] + step < depth[j]) {
                        depth[j] = depth[i] + step
                        if (step == 0) queue.addFirst(j) else queue.addLast(j)
                    }
                }
            }
            return IntArray(pixels.size) { i -> if (!wall[i] && depth[i] % 2 == 1) Color.BLACK else Color.WHITE }
        }
    }
}
