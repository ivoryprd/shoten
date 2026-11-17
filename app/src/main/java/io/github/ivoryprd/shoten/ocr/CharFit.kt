package io.github.ivoryprd.shoten.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        if (line.chars.count { it.box != null && it.text.isNotBlank() } < 2 || width <= 2 || height <= 2) return line
        val ink = analyze(pixels, width, height, line, left, top) ?: return line
        val origin = if (line.vertical) top else left
        if (ink.emoji.isEmpty()) return fitTo(line, ink.profile, origin, Excision(ink.profile.size, emptyList()))
        // Emoji aren't read, but their ink is there: they are cut out of the line, and what the
        // recognizer made of them (。 for 💰, say) is left out.
        val excision = Excision(ink.profile.size, ink.emoji)
        if (excision.kept.isEmpty()) return line
        val read = line.chars.filter { c ->
            val b = c.box ?: return@filter true
            val a = (if (line.vertical) b.top else b.left) - origin
            val z = (if (line.vertical) b.bottom else b.right) - origin
            val covered = ink.emoji.sumOf { r -> max(0f, min(z, r.last + 1f) - max(a, r.first.toFloat())).toDouble() }.toFloat()
            // On an emoji: a mark (。 for 💰), or anything the recognizer was unsure of (の for 😂).
            val misread = (covered >= 0.6f * (z - a) && !c.text.any(Character::isLetterOrDigit)) ||
                (covered >= 0.3f * (z - a) && c.confidence < DOUBTFUL)
            !misread
        }
        val kept = if (read.size == line.chars.size) line else OcrLine(read, line.box, forceVertical = line.vertical)
        return fitTo(kept, FloatArray(excision.kept.size) { ink.profile[excision.kept[it]] }, origin, excision)
    }

    /**
     * [line] fitted to the ink [profile] along it, whose positions are those of the image from
     * [origin] on with [excision] cut out.
     */
    private fun fitTo(line: OcrLine, profile: FloatArray, origin: Int, excision: Excision): OcrLine {
        val vertical = line.vertical
        val boxed = line.chars.withIndex().filter { (_, c) -> c.box != null && c.text.isNotBlank() }
        if (boxed.size < 2) return line
        fun start(b: Box) = excision.inward((if (vertical) b.top else b.left) - origin)
        fun end(b: Box) = excision.inward((if (vertical) b.bottom else b.right) - origin)
        val sizes = boxed.map { (_, c) -> end(c.box!!) - start(c.box!!) }.sorted()
        val em = max(2f, sizes[sizes.size / 2])
        val peak = profile.maxOrNull() ?: return line
        if (peak <= 0f) return line
        val empty = peak * 0.03f

        // The ink at the ends of the line.
        val first = start(boxed.first().value.box!!)
        val last = end(boxed.last().value.box!!)
        val cuts = FloatArray(boxed.size + 1)
        cuts[0] = inkStart(profile, first - 0.5f * em, first + 0.5f * em, empty) ?: first
        cuts[boxed.size] = inkEnd(profile, last - 0.5f * em, last + 0.5f * em, empty) ?: last

        // Where the boundaries should be by the characters themselves: full-width ones an em
        // wide, Latin letters and digits about half, a space between words, the blank sides of
        // brackets and punctuation; the em from the extent of the line's ink.
        // Proportional Latin letters (i, m) as wide as their boxes, measured by the full-width
        // characters' (or, in a line without any, taken to be about half of those).
        val fullSizes = boxed.filter { (_, c) -> isFullWidth(c.text) }.map { (_, c) -> end(c.box!!) - start(c.box!!) }.sorted()
        val reference = if (fullSizes.size >= 2) fullSizes[fullSizes.size / 2] else em / HALF
        val units = boxed.map { (_, c) ->
            if (isFullWidth(c.text)) 1f else ((end(c.box!!) - start(c.box!!)) / reference).coerceIn(0.2f, 0.8f)
        }
        val spaces = boxed.indices.map { k ->
            if (k == boxed.lastIndex) 0f else if ((boxed[k].index + 1 until boxed[k + 1].index).any { line.chars[it].text.isBlank() }) SPACE else 0f
        }
        val lead = units.first() * leadingBlank(boxed.first().value.text)
        val trail = units.last() * trailingBlank(boxed.last().value.text)
        val total = units.sum() + spaces.sum() - lead - trail
        val unit = if (total > 0f && cuts[boxed.size] > cuts[0]) (cuts[boxed.size] - cuts[0]) / total else em
        val expected = FloatArray(boxed.size + 1)
        expected[0] = cuts[0] - lead * unit
        for (k in boxed.indices) expected[k + 1] = expected[k] + (units[k] + spaces[k]) * unit
        cuts[0] = expected[0].coerceAtMost(cuts[0] - 0.05f * unit)
        cuts[boxed.size] = (expected[boxed.size] - (spaces.last()) * unit).coerceAtLeast(cuts[boxed.size] + 0.05f * unit)

        // The boundaries between the characters: of the places near each where it can be (near
        // where the characters' sizes or the recognizer put it), those that cost the least together.
        val options = (1 until boxed.size).map { i ->
            val byUnits = expected[i] - spaces[i - 1] * unit / 2
            val byBoxes = (end(boxed[i - 1].value.box!!) + start(boxed[i].value.box!!)) / 2
            (options(profile, byUnits, unit, empty, peak) + options(profile, byBoxes, unit, empty, peak))
                .groupBy { it.first }.map { (position, o) -> position to o.minOf { it.second } }
        }
        if (options.isNotEmpty()) {
            fun widthCost(k: Int, a: Float, b: Float) = abs((b - a) - (units[k] + spaces[k]) * unit) / unit
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
        for (i in 1..boxed.size) cuts[i] = max(cuts[i], cuts[i - 1] + unit / 3)

        val starts = FloatArray(boxed.size) { excision.startOut(cuts[it]) }
        val ends = FloatArray(boxed.size) { excision.endOut(cuts[it + 1]) }
        // A boundary in the blank next to a cut-out emoji: the characters on either side end and
        // start at the emoji, not across it.
        for (i in 1 until boxed.size) for (cut in excision.cuts) {
            val c = cuts[i]
            if (abs(cut.at - c) > 0.5f * unit) continue
            val lo = min(c, cut.at).toInt().coerceIn(0, profile.size)
            val hi = ceil(max(c, cut.at)).toInt().coerceIn(lo, profile.size)
            if ((lo until hi).any { profile[it] > empty }) continue
            ends[i - 1] = min(ends[i - 1], cut.from.toFloat())
            starts[i] = max(starts[i], cut.to.toFloat())
        }
        val chars = line.chars.toMutableList()
        boxed.forEachIndexed { i, (index, c) ->
            val b = c.box!!
            val a = starts[i] + origin
            val z = ends[i] + origin
            chars[index] = OcrChar(c.text, if (vertical) Box(b.left, a, b.right, z) else Box(a, b.top, z, b.bottom), c.confidence)
        }
        return OcrLine(chars, line.box, forceVertical = vertical)
    }

    /** The ink along a line: how much of it there is at each position, and where emoji are. */
    private class Ink(val profile: FloatArray, val emoji: List<IntRange>)

    /**
     * How much ink there is across the line at each position along it (only within the line's
     * box across it, so that neighboring lines don't count), and the emoji among it.
     */
    private fun analyze(pixels: IntArray, w: Int, h: Int, line: OcrLine, left: Int, top: Int): Ink? {
        val vertical = line.vertical
        val box = line.box ?: return null
        val lum = IntArray(w * h) { i -> luminance(pixels[i]) }
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
        return Ink(out, emoji(pixels, lum, w, vertical, a0, a1, length, background, strong))
    }

    /**
     * Where along the line there are emoji (or other color pictures): ink of other colors than
     * the text's that is either solid (text is made of thin strokes) or of several colors
     * (colored text, such as links, is of one). Positions along the line.
     */
    private fun emoji(pixels: IntArray, lum: IntArray, w: Int, vertical: Boolean, a0: Int, a1: Int, length: Int, background: Int, strong: Int): List<IntRange> {
        fun index(p: Int, q: Int) = if (vertical) p * w + q else q * w + p
        // The colors of the background and of the text: the commonest color of the strongest ink.
        var br = 0L
        var bgG = 0L
        var bb = 0L
        var bn = 0
        val bins = HashMap<Int, IntArray>()
        for (p in 0 until length) for (q in a0 until a1) {
            val i = index(p, q)
            val c = pixels[i]
            val d = abs(lum[i] - background)
            if (d <= 3) {
                br += (c shr 16) and 0xFF; bgG += (c shr 8) and 0xFF; bb += c and 0xFF; bn++
            } else if (d >= 0.8f * strong) {
                val key = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)
                val bin = bins.getOrPut(key) { IntArray(4) }
                bin[0]++; bin[1] += (c shr 16) and 0xFF; bin[2] += (c shr 8) and 0xFF; bin[3] += c and 0xFF
            }
        }
        if (bn == 0 || bins.isEmpty()) return emptyList()
        val bg = floatArrayOf(br.toFloat() / bn, bgG.toFloat() / bn, bb.toFloat() / bn)
        val top = bins.values.maxBy { it[0] }
        val fg = floatArrayOf(top[1].toFloat() / top[0], top[2].toFloat() / top[0], top[3].toFloat() / top[0])
        // Ink pixels (anything off the background) and those of other colors than the text's
        // (off the colors from the background to the text's, which edges are drawn in).
        val ink = IntArray(length)
        val foreign = IntArray(length)
        for (p in 0 until length) for (q in a0 until a1) {
            val c = pixels[index(p, q)]
            if (distance(c, bg) <= INK) continue
            ink[p]++
            if (offSegment(c, bg, fg) > FOREIGN) foreign[p]++
        }
        val thickness = a1 - a0
        val candidate = BooleanArray(length) { foreign[it] >= 2 && foreign[it] >= 0.2f * ink[it] }
        val out = ArrayList<IntRange>()
        var p = 0
        while (p < length) {
            if (!candidate[p]) {
                p++
                continue
            }
            // A run of such positions, across small gaps, and the ink adjoining it.
            var start = p
            var end = p
            val bridge = max(2, (0.15f * thickness).toInt())
            while (end + 1 < length && (end + 1 until min(length, end + 1 + bridge)).any { candidate[it] }) end++
            while (start > 0 && ink[start - 1] > 0) start--
            while (end + 1 < length && ink[end + 1] > 0) end++
            p = end + 1
            if (end - start + 1 < 0.25f * thickness) continue
            if (isPicture(pixels, w, vertical, start..end, a0, a1, bg, fg, thickness)) out += start..end
        }
        return out
    }

    /** Whether the ink at [span] along the line is a picture (solid, or of several colors) rather than colored text. */
    private fun isPicture(pixels: IntArray, w: Int, vertical: Boolean, span: IntRange, a0: Int, a1: Int, bg: FloatArray, fg: FloatArray, thickness: Int): Boolean {
        fun pixel(p: Int, q: Int) = pixels[if (vertical) p * w + q else q * w + p]
        fun isInk(p: Int, q: Int) = p in span && q in a0 until a1 && distance(pixel(p, q), bg) > INK
        // The commonest color of the ink of other colors than the text's.
        val bins = HashMap<Int, IntArray>()
        var inkCount = 0
        var solid = 0
        val r = max(2, (0.1f * thickness).roundToInt())
        for (p in span) for (q in a0 until a1) {
            if (!isInk(p, q)) continue
            inkCount++
            val c = pixel(p, q)
            if (offSegment(c, bg, fg) > FOREIGN) {
                val key = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)
                val bin = bins.getOrPut(key) { IntArray(4) }
                bin[0]++; bin[1] += (c shr 16) and 0xFF; bin[2] += (c shr 8) and 0xFF; bin[3] += c and 0xFF
            }
            // Ink all around, as inside a shape (text strokes are thinner).
            if (isInk(p + r, q) && isInk(p - r, q) && isInk(p, q + r) && isInk(p, q - r) &&
                isInk(p + r, q + r) && isInk(p + r, q - r) && isInk(p - r, q + r) && isInk(p - r, q - r)
            ) solid++
        }
        if (inkCount == 0 || bins.isEmpty()) return false
        if (solid >= SOLID * inkCount) return true
        val main = bins.values.maxBy { it[0] }
        val color = floatArrayOf(main[1].toFloat() / main[0], main[2].toFloat() / main[0], main[3].toFloat() / main[0])
        var off = 0
        for (p in span) for (q in a0 until a1) if (isInk(p, q) && offSegment(pixel(p, q), bg, color) > OTHER_COLOR) off++
        return off >= MULTICOLOR * inkCount
    }

    private fun luminance(p: Int) = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000

    private fun distance(c: Int, to: FloatArray): Float {
        val r = ((c shr 16) and 0xFF) - to[0]
        val g = ((c shr 8) and 0xFF) - to[1]
        val b = (c and 0xFF) - to[2]
        return sqrt(r * r + g * g + b * b)
    }

    /** How far the color [c] is from the colors between [a] and [b]. */
    private fun offSegment(c: Int, a: FloatArray, b: FloatArray): Float {
        val p = floatArrayOf(((c shr 16) and 0xFF) - a[0], ((c shr 8) and 0xFF) - a[1], (c and 0xFF) - a[2])
        val d = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val dd = d[0] * d[0] + d[1] * d[1] + d[2] * d[2]
        val t = if (dd == 0f) 0f else ((p[0] * d[0] + p[1] * d[1] + p[2] * d[2]) / dd).coerceIn(0f, 1f)
        val x = p[0] - t * d[0]
        val y = p[1] - t * d[1]
        val z = p[2] - t * d[2]
        return sqrt(x * x + y * y + z * z)
    }

    /**
     * Positions along a line of [length] with [removed] ranges cut out ([kept]: where the
     * remaining ones were), and back.
     */
    private class Excision(length: Int, removed: List<IntRange>) {
        private val n = length
        private val keep = BooleanArray(length) { true }.also { k -> for (r in removed) for (i in r) if (i in 0 until length) k[i] = false }
        val kept: IntArray = (0 until length).filter { keep[it] }.toIntArray()
        /** For each position, how many positions before it are kept. */
        private val before = IntArray(length).also { b -> var c = 0; for (i in 0 until length) { b[i] = c; if (keep[i]) c++ } }

        /** Where a range was cut out: [at] with the ranges cut out, from [from] until [to] in the line. */
        class Cut(val at: Float, val from: Int, val to: Int)

        val cuts: List<Cut> = removed.filter { it.first in 0 until length && !it.isEmpty() }
            .map { Cut(before[it.first].toFloat(), it.first, min(it.last + 1, length)) }

        /** Where [x] is with the removed ranges cut out (a position in one: where it was). */
        fun inward(x: Float): Float {
            if (x < 0f) return x
            if (x >= n) return kept.size + (x - n)
            val i = x.toInt()
            return if (keep[i]) before[i] + (x - i) else before[i].toFloat()
        }

        /** Where a character starting at [c] (with the ranges cut out) starts: after a removed range there. */
        fun startOut(c: Float): Float {
            if (c < 0f) return c
            if (c >= kept.size) return n + (c - kept.size)
            val j = c.toInt()
            return kept[j] + (c - j)
        }

        /** Where a character ending at [c] (with the ranges cut out) ends: before a removed range there. */
        fun endOut(c: Float): Float {
            if (c <= 0f) return c
            if (c > kept.size) return n + (c - kept.size)
            val j = ceil(c).toInt() - 1
            return kept[j] + (c - j)
        }
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

    private fun isFullWidth(s: String): Boolean {
        val cp = s.codePointAt(0)
        return io.github.ivoryprd.shoten.util.Cjk.isHan(cp) || cp in 0x3000..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF01..0xFF60 || cp in 0xAC00..0xD7A3
    }

    /** The empty part of a character's cell before its ink, in its widths. */
    private fun leadingBlank(s: String): Float = if (s in OPENING) 0.6f else 0f

    /** The empty part of a character's cell after its ink, in its widths. */
    private fun trailingBlank(s: String): Float = when (s) {
        in CLOSING -> 0.65f
        in CENTERED -> 0.3f
        else -> 0f
    }

    private const val HALF = 0.55f
    /** How far from the background a color is ink, and from the text's colors of another color. */
    private const val INK = 40f
    private const val FOREIGN = 60f
    /** How much of a picture's ink is inside a shape, or of other colors than its commonest. */
    private const val SOLID = 0.12f
    private const val MULTICOLOR = 0.12f
    private const val OTHER_COLOR = 45f
    /** The recognizer's confidence below which a character on an emoji is taken to be the emoji misread. */
    private const val DOUBTFUL = 0.2f
    private const val SPACE = 0.3f
    private val OPENING = "「『（【〈《〔［｛〘〖".map { it.toString() }.toSet()
    private val CLOSING = "。、，．」』）】〉》〕］｝〙〗".map { it.toString() }.toSet()
    private val CENTERED = "？！：；".map { it.toString() }.toSet()

    private fun inkStart(profile: FloatArray, from: Float, to: Float, empty: Float): Float? {
        for (k in from.roundToInt().coerceAtLeast(0) until to.roundToInt().coerceAtMost(profile.size)) if (profile[k] > empty) return k.toFloat()
        return null
    }

    private fun inkEnd(profile: FloatArray, from: Float, to: Float, empty: Float): Float? {
        for (k in to.roundToInt().coerceAtMost(profile.size) - 1 downTo from.roundToInt().coerceAtLeast(0)) if (profile[k] > empty) return k + 1f
        return null
    }
}
