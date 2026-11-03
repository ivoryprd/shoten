package io.github.ivoryprd.shoten.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fits the boxes of a line's characters to the image: the recognizer's boxes are only roughly
 * where the characters are (and spread evenly over a piece of text where it gives none), so the
 * boundaries between characters are moved to gaps in the ink near them, and the line's first
 * and last characters to where its ink begins and ends. Characters have gaps inside too (ハ,
 * 川), so the boundaries are chosen together: those that keep every character about as wide
 * as its box was.
 */
internal object CharFit {
    /**
     * [line] with its characters' boxes fitted to [pixels] (ARGB, [width] × [height]: the part of
     * the image at ([left], [top]) around the line), or [line] itself if they can't be.
     */
    fun fit(line: OcrLine, pixels: IntArray, width: Int, height: Int, left: Int, top: Int): OcrLine {
        val vertical = line.vertical
        val boxed = line.chars.withIndex().filter { (_, c) -> c.box != null && c.text.isNotBlank() }
        if (boxed.size < 2 || width <= 2 || height <= 2) return line
        val profile = profile(pixels, width, height, vertical, line, left, top) ?: return line
        val origin = if (vertical) top else left
        fun start(b: Box) = (if (vertical) b.top else b.left) - origin
        fun end(b: Box) = (if (vertical) b.bottom else b.right) - origin
        val sizes = boxed.map { (_, c) -> end(c.box!!) - start(c.box!!) }.sorted()
        val em = max(2f, sizes[sizes.size / 2])
        val peak = profile.maxOrNull() ?: return line
        if (peak <= 0f) return line
        val empty = peak * 0.03f

        // The ink at the ends of the line.
        val first = start(boxed.first().value.box!!)
        val last = end(boxed.last().value.box!!)
        val cuts = FloatArray(boxed.size + 1)
        cuts[0] = inkStart(profile, first - 0.5f * em, first + 0.5f * em, empty)?.let { it - 0.05f * em } ?: first
        cuts[boxed.size] = inkEnd(profile, last - 0.5f * em, last + 0.5f * em, empty)?.let { it + 0.05f * em } ?: last
        // The boundaries between the characters: of the places near each where it can be, those
        // that cost the least together.
        val widths = boxed.map { (_, c) -> end(c.box!!) - start(c.box!!) }
        val options = (1 until boxed.size).map { i ->
            options(profile, (end(boxed[i - 1].value.box!!) + start(boxed[i].value.box!!)) / 2, em, empty, peak)
        }
        if (options.isNotEmpty()) {
            fun widthCost(k: Int, a: Float, b: Float) = abs((b - a) - widths[k]) / em
            // cost[i][o]: the least cost of the characters up to boundary i at its option o.
            val cost = options.map { FloatArray(it.size) }
            val from = options.map { IntArray(it.size) }
            for (i in options.indices) for ((o, option) in options[i].withIndex()) {
                if (i == 0) {
                    cost[0][o] = option.second + widthCost(0, cuts[0], option.first)
                } else {
                    var best = Float.MAX_VALUE
                    for ((p, previous) in options[i - 1].withIndex()) {
                        val c = cost[i - 1][p] + widthCost(i, previous.first, option.first)
                        if (c < best) {
                            best = c
                            from[i][o] = p
                        }
                    }
                    cost[i][o] = best + option.second
                }
            }
            val lastIndex = options.lastIndex
            var o = options[lastIndex].indices.minBy { cost[lastIndex][it] + widthCost(boxed.size - 1, options[lastIndex][it].first, cuts[boxed.size]) }
            for (i in lastIndex downTo 0) {
                cuts[i + 1] = options[i][o].first
                o = from[i][o]
            }
        }
        // Characters keep at least a third of an em, in order.
        for (i in 1..boxed.size) cuts[i] = max(cuts[i], cuts[i - 1] + em / 3)

        val chars = line.chars.toMutableList()
        boxed.forEachIndexed { i, (index, c) ->
            val b = c.box!!
            val a = cuts[i] + origin
            val z = cuts[i + 1] + origin
            chars[index] = OcrChar(c.text, if (vertical) Box(b.left, a, b.right, z) else Box(a, b.top, z, b.bottom))
        }
        return OcrLine(chars, line.box, forceVertical = vertical)
    }

    /**
     * How much ink there is across the line at each position along it (only within the line's
     * box across it, so that neighboring lines don't count).
     */
    private fun profile(pixels: IntArray, w: Int, h: Int, vertical: Boolean, line: OcrLine, left: Int, top: Int): FloatArray? {
        val box = line.box ?: return null
        val lum = IntArray(w * h) { i ->
            val p = pixels[i]
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        val histogram = IntArray(64)
        for (l in lum) histogram[l shr 2]++
        val background = histogram.indices.maxBy { histogram[it] } * 4 + 2
        val contrast = IntArray(256)
        for (l in lum) contrast[abs(l - background)]++
        var strong = 0
        var count = 0
        for (d in 255 downTo 0) {
            count += contrast[d]
            if (count >= lum.size * 0.01f) {
                strong = d
                break
            }
        }
        if (strong < 40) return null
        val from = 0.25f * strong
        val range = 0.35f * strong
        // Across the line: its box, within the pixels.
        val a0 = ((if (vertical) box.left - left else box.top - top)).roundToInt().coerceIn(0, if (vertical) w else h)
        val a1 = ((if (vertical) box.right - left else box.bottom - top)).roundToInt().coerceIn(0, if (vertical) w else h)
        if (a1 <= a0) return null
        val length = if (vertical) h else w
        val out = FloatArray(length)
        for (p in 0 until length) {
            var sum = 0f
            for (q in a0 until a1) {
                val i = if (vertical) p * w + q else q * w + p
                sum += ((abs(lum[i] - background) - from) / range).coerceIn(0f, 1f)
            }
            out[p] = sum
        }
        return out
    }

    /**
     * Where a boundary expected at [expected] can be, each with its cost: the middles of gaps in
     * the ink near it, the further the more costly, and the place with the least ink (for
     * characters that touch), the more ink the more costly.
     */
    private fun options(profile: FloatArray, expected: Float, em: Float, empty: Float, peak: Float): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        val from = (expected - 0.45f * em).roundToInt().coerceIn(0, profile.size - 1)
        val to = (expected + 0.45f * em).roundToInt().coerceIn(0, profile.size - 1)
        var i = from
        while (i <= to) {
            if (profile[i] <= empty) {
                var j = i
                while (j + 1 < profile.size && profile[j + 1] <= empty) j++
                // Gaps reaching outside the window count with their part inside it.
                val middle = (max(i, from) + min(j, to)) / 2f + 0.5f
                out += middle to 0.5f * abs(middle - expected) / em
                i = j + 1
            } else {
                i++
            }
        }
        if (out.isNotEmpty()) return out
        // Characters that touch: where there is least ink, or as expected.
        var least = from
        for (k in from..to) if (profile[k] < profile[least] || (profile[k] == profile[least] && abs(k - expected) < abs(least - expected))) least = k
        out += least + 0.5f to 0.5f * abs(least + 0.5f - expected) / em + 3f * profile[least] / peak
        out += expected to 3f * profile[expected.roundToInt().coerceIn(0, profile.size - 1)] / peak
        return out
    }

    private fun inkStart(profile: FloatArray, from: Float, to: Float, empty: Float): Float? {
        for (k in from.roundToInt().coerceAtLeast(0) until to.roundToInt().coerceAtMost(profile.size)) if (profile[k] > empty) return k.toFloat()
        return null
    }

    private fun inkEnd(profile: FloatArray, from: Float, to: Float, empty: Float): Float? {
        for (k in to.roundToInt().coerceAtMost(profile.size) - 1 downTo from.roundToInt().coerceAtLeast(0)) if (profile[k] > empty) return k + 1f
        return null
    }
}
