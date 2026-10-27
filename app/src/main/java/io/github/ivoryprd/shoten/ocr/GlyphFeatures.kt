package io.github.ivoryprd.shoten.ocr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Describes how a character looks, to tell which characters an image of one looks like: its
 * shape and the directions of its strokes, both blurred, so that different fonts, sizes and
 * stroke widths still compare as alike. Images are [SIZE] pixels square (0 for no ink, 1 for
 * ink), the character's em box (its cell) [EM] pixels square in the middle.
 */
internal object GlyphFeatures {
    const val SIZE = 48
    const val EM = 42

    /** How many values [shape] has. */
    const val SHAPE_SIZE = (SIZE / 4) * (SIZE / 4)

    /** How alike two characters look, from their [of] features: 1 for the same. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** The features of an image of a character. */
    fun of(ink: FloatArray): FloatArray {
        val shape = shape(ink)
        val strokes = normalized(strokes(ink).also { f -> for (i in f.indices) f[i] = sqrt(f[i]) })
        return FloatArray(shape.size + strokes.size) { i -> if (i < shape.size) 0.6f * shape[i] else 0.8f * strokes[i - shape.size] }
    }

    /**
     * The blurred shape of a character alone: much quicker to get than [of], and enough to tell
     * which characters may look alike (compared with [similarity] too).
     */
    fun shape(ink: FloatArray): FloatArray = normalized(centered(blur(pool(ink, SIZE, 4), SIZE / 4)))

    /**
     * The ink of a cell of an image ([pixels], ARGB, [width] × [height]: the cell's em box),
     * placed as [of] expects: whatever stands out from the background, dark or light, as ink, but
     * not faint patterns (screentone, say: under half as strong as the strongest ink).
     */
    fun cellInk(pixels: IntArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(SIZE * SIZE)
        if (width <= 0 || height <= 0 || pixels.size < width * height) return out
        val lum = FloatArray(width * height) { i ->
            val p = pixels[i]
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000f
        }
        // The background: the most common brightness along the edge of the cell.
        val edge = IntArray(64)
        for (x in 0 until width) {
            edge[(lum[x] / 4).toInt().coerceIn(0, 63)]++
            edge[(lum[(height - 1) * width + x] / 4).toInt().coerceIn(0, 63)]++
        }
        for (y in 0 until height) {
            edge[(lum[y * width] / 4).toInt().coerceIn(0, 63)]++
            edge[(lum[y * width + width - 1] / 4).toInt().coerceIn(0, 63)]++
        }
        val background = edge.indices.maxBy { edge[it] } * 4 + 2f
        var strongest = 0f
        for (l in lum) strongest = max(strongest, abs(l - background))
        strongest = max(strongest, 48f)
        val from = 0.4f * strongest
        val scale = 1f / (0.75f * strongest - from)
        // Scaled into the em box in the middle.
        val margin = (SIZE - EM) / 2
        for (y in 0 until EM) for (x in 0 until EM) {
            val sx = ((x + 0.5f) * width / EM - 0.5f).coerceIn(0f, width - 1f)
            val sy = ((y + 0.5f) * height / EM - 0.5f).coerceIn(0f, height - 1f)
            val x0 = sx.toInt()
            val y0 = sy.toInt()
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val fx = sx - x0
            val fy = sy - y0
            fun ink(px: Int, py: Int) = ((abs(lum[py * width + px] - background) - from) * scale).coerceIn(0f, 1f)
            val v = (ink(x0, y0) * (1 - fx) + ink(x1, y0) * fx) * (1 - fy) + (ink(x0, y1) * (1 - fx) + ink(x1, y1) * fx) * fy
            out[(y + margin) * SIZE + x + margin] = v.coerceIn(0f, 1f)
        }
        return out
    }

    /** How much ink runs in each of 6 directions, in 8 × 8 areas of the image. */
    private fun strokes(ink: FloatArray): FloatArray {
        val smooth = blur(blur(ink, SIZE), SIZE)
        val planes = Array(DIRECTIONS) { FloatArray(SIZE * SIZE) }
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val gx = (smooth[y * SIZE + min(x + 1, SIZE - 1)] - smooth[y * SIZE + max(x - 1, 0)]) / 2
            val gy = (smooth[min(y + 1, SIZE - 1) * SIZE + x] - smooth[max(y - 1, 0) * SIZE + x]) / 2
            val magnitude = sqrt(gx * gx + gy * gy)
            if (magnitude == 0f) continue
            var angle = atan2(gy, gx)
            if (angle < 0) angle += Math.PI.toFloat()
            val direction = angle / Math.PI.toFloat() * DIRECTIONS
            for (k in 0 until DIRECTIONS) {
                // Shared between the two nearest directions.
                val d = abs(((direction - k + DIRECTIONS * 1.5f) % DIRECTIONS) - DIRECTIONS / 2f)
                if (d < 1f) planes[k][y * SIZE + x] += (1 - d) * magnitude
            }
        }
        val cells = SIZE / 6
        val out = FloatArray(DIRECTIONS * cells * cells)
        for (k in 0 until DIRECTIONS) blur(pool(planes[k], SIZE, 6), cells).copyInto(out, k * cells * cells)
        return out
    }

    /** Averages [size] × [size] values in blocks of [block] × [block]. */
    private fun pool(values: FloatArray, size: Int, block: Int): FloatArray {
        val n = size / block
        val out = FloatArray(n * n)
        for (y in 0 until n * block) for (x in 0 until n * block) out[(y / block) * n + x / block] += values[y * size + x]
        val area = (block * block).toFloat()
        for (i in out.indices) out[i] /= area
        return out
    }

    /** Blurs [size] × [size] values a little (1, 2, 1 across and down). */
    private fun blur(values: FloatArray, size: Int): FloatArray {
        val across = FloatArray(values.size)
        for (y in 0 until size) for (x in 0 until size) {
            val row = y * size
            across[row + x] = (values[row + max(x - 1, 0)] + 2 * values[row + x] + values[row + min(x + 1, size - 1)]) / 4
        }
        val out = FloatArray(values.size)
        for (y in 0 until size) for (x in 0 until size) {
            out[y * size + x] = (across[max(y - 1, 0) * size + x] + 2 * across[y * size + x] + across[min(y + 1, size - 1) * size + x]) / 4
        }
        return out
    }

    private fun centered(values: FloatArray): FloatArray {
        val mean = values.average().toFloat()
        return FloatArray(values.size) { values[it] - mean }
    }

    private fun normalized(values: FloatArray): FloatArray {
        var sum = 0f
        for (v in values) sum += v * v
        val length = sqrt(sum)
        return if (length == 0f) values else FloatArray(values.size) { values[it] / length }
    }

    private const val DIRECTIONS = 6
}
