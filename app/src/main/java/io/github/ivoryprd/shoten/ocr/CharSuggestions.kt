package io.github.ivoryprd.shoten.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.ivoryprd.shoten.lookup.Language
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Suggests what a recognized character really is, for correcting it by hand: the characters it
 * looks like ([SimilarChars]), and, when its image is known, the characters that look most like
 * that image. Misreads can be far from the character that was read (れ read as 花, say), so the
 * image is compared with every common character: first roughly, by their shapes, then closely
 * for the likeliest ones.
 */
class CharSuggestions(
    private val similar: SimilarChars,
    /** The image of a character as [GlyphFeatures] takes it (see [GlyphRenderer]). */
    private val render: (text: String, language: Language) -> FloatArray,
) {
    private class Shapes(val chars: List<String>, val values: ByteArray, val size: Int)

    private val shapes = HashMap<Language, Shapes>()
    private val features = object : LinkedHashMap<String, FloatArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) = size > CACHE
    }

    /**
     * The characters that [char] may really be, the likeliest first: if its image [cell] is known
     * (see [GlyphFeatures.cellInk]), by how much they look like it, else by how much they look
     * like [char]. The first time for a language takes a while (every character is drawn).
     */
    @Synchronized
    fun suggest(char: String, cell: FloatArray?, language: Language, max: Int = 16): List<String> {
        val near = similar.of(char, language)
        if (cell == null) return near.take(max)
        val shape = GlyphFeatures.shape(cell)
        val all = shapesOf(language)
        val rough = FloatArray(all.chars.size) { i ->
            val base = i * all.size
            var dot = 0f
            for (k in 0 until all.size) dot += shape[k] * all.values[base + k]
            dot
        }
        val candidates = LinkedHashSet(near)
        all.chars.indices.sortedByDescending { rough[it] }.take(SHORTLIST).mapTo(candidates) { all.chars[it] }
        candidates -= char
        val image = GlyphFeatures.of(cell)
        // The look-alikes of what was read are likelier than other characters that look as much like the image.
        return candidates
            .map { c -> c to GlyphFeatures.similarity(image, featuresOf(c, language)) + if (c in near) NEAR else 0f }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
    }

    private fun featuresOf(char: String, language: Language): FloatArray =
        features.getOrPut("${language.code}:$char") { GlyphFeatures.of(render(char, language)) }

    /** The shapes of all the characters of [SimilarChars.characters], kept small (a byte a value). */
    private fun shapesOf(language: Language): Shapes = shapes.getOrPut(language) {
        val chars = similar.characters(language)
        val size = GlyphFeatures.SHAPE_SIZE
        val values = ByteArray(chars.size * size)
        chars.forEachIndexed { i, c ->
            GlyphFeatures.shape(render(c, language)).forEachIndexed { k, v ->
                values[i * size + k] = (v * QUANTUM).roundToInt().coerceIn(-127, 127).toByte()
            }
        }
        Shapes(chars, values, size)
    }

    companion object {
        private const val NEAR = 0.05f
        private const val SHORTLIST = 40
        private const val CACHE = 512
        private const val QUANTUM = 300f

        /**
         * The image of the character in [box] (its cell) of [image] as [suggest] takes it, or null
         * if the box is too small. [image] can't be a hardware bitmap: their pixels can't be read.
         */
        fun cellOf(image: Bitmap, box: Box): FloatArray? {
            val left = floor(box.left).toInt().coerceIn(0, image.width)
            val top = floor(box.top).toInt().coerceIn(0, image.height)
            val width = ceil(box.right).toInt().coerceIn(0, image.width) - left
            val height = ceil(box.bottom).toInt().coerceIn(0, image.height) - top
            if (width < 4 || height < 4) return null
            val pixels = IntArray(width * height)
            image.getPixels(pixels, 0, width, left, top, width, height)
            return GlyphFeatures.cellInk(pixels, width, height)
        }
    }
}

/** Draws characters as [GlyphFeatures] takes them, in the font the system uses for the language. */
class GlyphRenderer {
    private val size = GlyphFeatures.SIZE
    private val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
    private val canvas = Canvas(bitmap)
    private val buffer = ByteBuffer.allocate(bitmap.rowBytes * size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = GlyphFeatures.EM.toFloat()
        textAlign = Paint.Align.CENTER
        color = Color.BLACK
    }

    @Synchronized
    fun ink(text: String, language: Language): FloatArray {
        bitmap.eraseColor(Color.TRANSPARENT)
        // Kanji and hanzi differ in their details; the locale picks the forms.
        paint.textLocale = if (language == Language.CHINESE) Locale.CHINESE else Locale.JAPANESE
        // CJK fonts put the em box from 0.88 em above the baseline to 0.12 em below: centered.
        canvas.drawText(text, size / 2f, size / 2f + 0.38f * GlyphFeatures.EM, paint)
        buffer.rewind()
        bitmap.copyPixelsToBuffer(buffer)
        val rowBytes = bitmap.rowBytes
        return FloatArray(size * size) { i -> (buffer.get((i / size) * rowBytes + i % size).toInt() and 0xFF) / 255f }
    }
}
