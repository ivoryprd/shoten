package io.github.ivoryprd.shoten.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds columns of vertical Chinese or Japanese text in an image, and the cells of their
 * characters. Vertical text is set on a grid of square cells, one character per cell, so a column
 * can be cut into its cells; laid out side by side, they form an ordinary horizontal line, which
 * the recognizer reads much more reliably than vertical text (see [OcrEngine]).
 *
 * Columns are found in the ink of the image (pixels that stand out from the background) near
 * text that the recognizer found: runs of ink across the image are columns, and within a column,
 * the pitch of the characters is where the gaps between them repeat. Columns of similar width
 * (main text, furigana) share their pitch, which keeps short columns from being misjudged.
 */
internal object VerticalLayout {
    /**
     * A column of vertical text: its ink, the boxes of its characters' cells (top to bottom), the
     * background color, and the cells (by index) that hold nothing but a mark whose vertical
     * form differs from the horizontal one: 、 and 。 (in the upper right of their cells instead
     * of the lower left) and the long vowel mark ー (turned a quarter, a vertical bar). [inks] are
     * the boxes of the ink in each cell (null for none), which tell small kana from others.
     */
    class Column(
        val box: Box,
        val cells: List<Box>,
        val background: Int,
        val marks: Map<Int, String> = emptyMap(),
        val contrast: Contrast = Contrast.DEFAULT,
        val inks: List<Box?> = emptyList(),
    )

    /**
     * How much the ink of a column stands out from its background in brightness: at least
     * [threshold] (screentone and other faint patterns stay below it), [strong] for its strongest
     * strokes.
     */
    class Contrast(val threshold: Int, val strong: Int) {
        companion object {
            val DEFAULT = Contrast(48, 255)
        }
    }

    /**
     * [pixels] (ARGB) in gray, with what doesn't stand out as ink evened out into the
     * [background]: screentone and textures around text vanish, which the recognizer otherwise
     * takes for parts of characters. Ink keeps its brightness (text outlined in another color
     * stays readable); between faint and strong, it fades into the background.
     */
    fun clean(pixels: IntArray, background: Int, contrast: Contrast): IntArray {
        val bg = luminance(background)
        val from = 0.8f * contrast.threshold
        val to = max(from + 1f, (contrast.threshold + contrast.strong) / 2f)
        return IntArray(pixels.size) { i ->
            val d = luminance(pixels[i]) - bg
            val weight = ((abs(d) - from) / (to - from)).coerceIn(0f, 1f)
            val l = (bg + d * weight).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
    }

    private fun luminance(p: Int) = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000

    /** Where to look for vertical text, and the size of its characters (an em). */
    class Region(val left: Int, val top: Int, val right: Int, val bottom: Int, val em: Float) {
        val width get() = right - left
        val height get() = bottom - top

        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }

    /**
     * Regions around recognized lines ([seeds], each with its number of characters), widened to
     * find columns next to them that the recognizer missed. Overlapping regions are merged; the
     * size of a region's text is the median over its characters, so that furigana counts little.
     */
    fun regions(seeds: List<Pair<Box, Int>>, width: Int, height: Int): List<Region> {
        class Area(var left: Float, var top: Float, var right: Float, var bottom: Float, val sizes: MutableList<Pair<Float, Int>>)
        val areas = seeds.filter { it.first.width > 0f && it.first.height > 0f }.map { (box, chars) ->
            val em = min(box.width, box.height)
            Area(box.left - 2.5f * em, box.top - 0.6f * em, box.right + 2.5f * em, box.bottom + 0.6f * em, mutableListOf(em to max(1, chars)))
        }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in areas.indices) for (j in i + 1 until areas.size) {
                val a = areas[i]
                val b = areas[j]
                if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) {
                    a.left = min(a.left, b.left)
                    a.top = min(a.top, b.top)
                    a.right = max(a.right, b.right)
                    a.bottom = max(a.bottom, b.bottom)
                    a.sizes += b.sizes
                    areas.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
        return areas.mapNotNull { a ->
            val sizes = a.sizes.sortedBy { it.first }
            val total = sizes.sumOf { it.second }
            var count = 0
            val em = sizes.first { count += it.second; count * 2 >= total }.first
            val region = Region(
                a.left.toInt().coerceIn(0, width), a.top.toInt().coerceIn(0, height),
                ceil(a.right).toInt().coerceIn(0, width), ceil(a.bottom).toInt().coerceIn(0, height), em,
            )
            region.takeIf { it.width > 0 && it.height > 0 && em >= 4f }
        }
    }

    /**
     * [region] grown where its [columns] reach its edges, as columns cut off there do (and where
     * there may be more that the recognizer missed): sideways by a few columns, up or down by a
     * few characters, within the image ([width] × [height]). Null if they don't, or it can't.
     */
    fun grown(region: Region, columns: List<Column>, width: Int, height: Int): Region? {
        if (columns.isEmpty()) return null
        val em = region.em
        val near = 0.5f * em
        val left = if (columns.minOf { it.box.left } - region.left < near) (region.left - 2.5f * em).toInt().coerceAtLeast(0) else region.left
        val right = if (region.right - columns.maxOf { it.box.right } < near) ceil(region.right + 2.5f * em).toInt().coerceAtMost(width) else region.right
        val top = if (columns.minOf { it.box.top } - region.top < near) (region.top - 6f * em).toInt().coerceAtLeast(0) else region.top
        val bottom = if (region.bottom - columns.maxOf { it.box.bottom } < near) ceil(region.bottom + 6f * em).toInt().coerceAtMost(height) else region.bottom
        if (left == region.left && top == region.top && right == region.right && bottom == region.bottom) return null
        return Region(left, top, right, bottom, em)
    }

    /**
     * The columns of vertical text in [region], given the region's pixels (ARGB, row by row).
     * Returns nothing if the region doesn't look like vertical text: if no column holds at least
     * two characters.
     */
    fun columns(pixels: IntArray, region: Region): List<Column> {
        val w = region.width
        val h = region.height
        if (w <= 0 || h <= 0 || pixels.size < w * h) return emptyList()
        val em = region.em
        val ink = inkMask(pixels, w, h) ?: return emptyList()

        val profile = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) if (ink.mask[y * w + x]) profile[x]++
        val runs = mergeRuns(runs(w, max(1, (0.004f * h).toInt())) { profile[it] }, gap = 0.15f * em, width = 1.4f * em)

        val raw = ArrayList<RawColumn>()
        for ((x0, x1) in runs) {
            val width = x1 - x0
            val pieces = if (width > 1.3f * em) {
                // Columns that touch: split them where they meet. Anything else this wide isn't a column.
                val n = (width / em).roundToInt()
                if (n < 2 || abs(width / em - n) > 0.3f) continue
                splitAtMinima(x0, x1, n, profile)
            } else {
                listOf(x0 to x1)
            }
            for ((a0, a1) in pieces) {
                val rows = IntArray(h)
                // How far the ink of each row reaches across the column.
                val spans = IntArray(h)
                for (y in 0 until h) {
                    var first = -1
                    var last = -1
                    for (x in a0 until a1) if (ink.mask[y * w + x]) {
                        rows[y]++
                        if (first < 0) first = x
                        last = x
                    }
                    if (first >= 0) spans[y] = last - first + 1
                }
                // Text far apart vertically belongs to different columns (or isn't text).
                for ((y0, y1) in mergeRuns(runs(h, 1) { rows[it] }, gap = 1.0f * em, width = Float.MAX_VALUE)) {
                    if (a1 - a0 < 0.35f * em || y1 - y0 < 0.3f * em) continue
                    val column = RawColumn(a0, a1, y0, rows.copyOfRange(y0, y1), spans.copyOfRange(y0, y1))
                    if (hasStrokesAcross(column)) raw += column
                }
            }
        }

        if (raw.isEmpty()) return emptyList()
        val columns = ArrayList<Column>()
        var mainPitch = 0f
        var mainWidth = 0
        // The main text (the most of it) first: furigana's pitch is searched around half of the main text's.
        val groups = groupByWidth(raw)
        val main = groups.maxBy { group -> group.sumOf { it.rows.size } }
        for (group in listOf(main) + groups.filter { it !== main }) {
            val width = group.first().width
            val furigana = mainPitch > 0f && width <= 0.7f * mainWidth
            val (pitch, phases) = refine(group, if (furigana) pitch(group, 0.42f * mainPitch, 0.62f * mainPitch) else pitch(group))
            if (mainPitch == 0f) {
                mainPitch = pitch
                mainWidth = group.last().width
            }
            group.forEachIndexed { i, c ->
                val tops = if (furigana) furiganaCellTops(c, pitch) else cellTops(c.rows, pitch, phases[i])
                // A single character (but furigana) is left to the recognizer: it could as well
                // be a piece of horizontal text.
                if (tops.isEmpty() || tops.size == 1 && !furigana) return@forEachIndexed
                val marks = HashMap<Int, String>()
                val inks = tops.mapIndexed { k, y ->
                    val left = (c.x0 + c.x1) / 2f - pitch / 2
                    inkBox(ink.mask, w, h, left, c.y0 + y, pitch)?.also { box ->
                        markIn(ink.mask, w, h, left, c.y0 + y, pitch, box)?.let { marks[k] = it }
                    }
                }
                if (!looksLikeText(c, tops, pitch, marks.size)) return@forEachIndexed
                val cx = region.left + (c.x0 + c.x1) / 2f
                val top = region.top + c.y0
                columns += Column(
                    Box(region.left + c.x0.toFloat(), top.toFloat(), region.left + c.x1.toFloat(), (top + c.rows.size).toFloat()),
                    tops.map { y -> Box(cx - pitch / 2, top + y, cx + pitch / 2, top + y + pitch) },
                    ink.background,
                    marks,
                    ink.contrast,
                    inks.map { b -> b?.let { Box(region.left + it.left, region.top + it.top, region.left + it.right, region.top + it.bottom) } },
                )
            }
        }
        // Vertical text has at least one column of several characters, taller than it is wide.
        return if (columns.any { it.cells.size >= 2 && it.box.height >= 1.6f * it.box.width }) columns else emptyList()
    }

    /**
     * Whether [box], a line the recognizer found, lies largely (30%) inside [columns]: its text
     * is (part of) theirs, however it was read.
     */
    fun isCovered(box: Box, columns: List<Column>): Boolean {
        val area = box.width * box.height
        if (area <= 0f) return false
        var inside = 0f
        for (c in columns) {
            val w = min(box.right, c.box.right) - max(box.left, c.box.left)
            val h = min(box.bottom, c.box.bottom) - max(box.top, c.box.top)
            if (w > 0f && h > 0f) inside += w * h
        }
        return inside >= 0.3f * area
    }

    /**
     * Whether some rows of a column have ink across most of it, as characters' horizontal strokes
     * do; a slanted line (the outline of a speech bubble, say) never does.
     */
    private fun hasStrokesAcross(c: RawColumn): Boolean {
        var wide = 0
        var inked = 0
        var widest = 0
        for (span in c.spans) {
            if (span == 0) continue
            inked++
            if (span * 2 >= c.width) wide++
            widest = max(widest, span)
        }
        return widest * 10 >= c.width * 6 && wide * 10 >= inked
    }

    /**
     * Whether a column's ink looks like characters: in at least half its cells the ink reaches
     * across half the column (or the cell holds a mark, one of the [marks] cells), unlike a
     * stroke of a drawing (a speech bubble's outline, say).
     */
    private fun looksLikeText(c: RawColumn, tops: List<Float>, pitch: Float, marks: Int): Boolean {
        val width = c.width
        var wide = 0
        for (top in tops) {
            val from = top.roundToInt().coerceIn(0, c.rows.size)
            val to = (top + pitch).roundToInt().coerceIn(0, c.rows.size)
            var span = 0
            for (y in from until to) span = max(span, c.spans[y])
            if (span >= width / 2) wide++
        }
        return (wide + marks) * 2 >= tops.size
    }

    /**
     * Fixes characters of a column that the recognizer, reading its cells upright in a row,
     * takes for something else because their vertical forms are turned or moved: the long vowel
     * mark (a vertical bar), the ellipsis (a column of dots) and corner brackets (turned a
     * quarter, and read as Latin letters or, at the ends of a column, as a horizontal long vowel
     * mark, which a column can't contain: its long vowel marks are vertical). Characters at
     * the indices in [keep] are left as they are.
     */
    fun fixVerticalForms(chars: List<OcrChar>, keep: Set<Int> = emptySet()): List<OcrChar> = chars.mapIndexed { i, c ->
        if (i in keep) return@mapIndexed c
        val first = i == 0 && isCjkAt(chars, 1)
        val last = i == chars.lastIndex && isCjkAt(chars, i - 1)
        val fixed = when (c.text) {
            in BARS -> if (isCjkAt(chars, i - 1) || isCjkAt(chars, i + 1)) "ー" else c.text
            in DOTS -> "…"
            in OPENING -> if (first) "「" else c.text
            in CLOSING -> if (last) "」" else c.text
            "ー" -> if (first) "「" else if (last) "」" else c.text
            else -> c.text
        }
        if (fixed == c.text) c else OcrChar(fixed, c.box)
    }

    /**
     * The text of [column] from what the recognizer read in its cells ([read], with the boxes of
     * their cells): the marks found in the cells ([Column.marks]) where it read nothing (it
     * misses small marks) or a look-alike ("o" for 。, say), and other vertical forms fixed.
     */
    fun readColumn(column: Column, read: List<OcrChar>): List<OcrChar> {
        val cellOf = column.cells.withIndex().associate { (i, box) -> box to i }
        val byCell = read.groupBy { ch -> ch.box?.let { cellOf[it] } ?: -1 }
        val chars = ArrayList(byCell[-1].orEmpty())
        val marked = HashSet<Int>()
        // The size of the column's characters: the median of their ink's.
        val extents = column.cells.indices.mapNotNull { k -> if (k in column.marks) null else column.inks.getOrNull(k)?.let { extent(it, column.cells[k]) } }.sorted()
        val usual = extents.getOrNull(extents.size / 2)
        for (k in column.cells.indices) {
            val inCell = byCell[k].orEmpty()
            val mark = column.marks[k]
            if (mark != null && inCell.all { it.text in LOOKALIKES.getValue(mark) }) {
                marked += chars.size
                chars += OcrChar(mark, column.cells[k])
            } else {
                // A cell holds one character: punctuation read beside another one (where the ink
                // shows none) was made up, as the recognizer does after the last character of a row.
                val read = if (inCell.size > 1) inCell.filterNot { it.text in STRAY_PUNCTUATION }.ifEmpty { inCell } else inCell
                val ink = column.inks.getOrNull(k)
                chars += if (ink == null || usual == null) read else read.map { sized(it, column.cells[k], ink, usual) }
            }
        }
        return fixVerticalForms(chars, keep = marked)
    }

    /** How far the [ink] in a [cell] reaches, across or down, relative to the cell. */
    private fun extent(ink: Box, cell: Box) = max(ink.width, ink.height) / cell.width

    /**
     * [c] as a small or a full-size kana, as the [ink] in its [cell] tells, compared with the
     * [usual] extent of the column's characters: on a sheet the recognizer often reads small kana
     * as full-size ones (or the other way round). Small kana are about three quarters the size of
     * others (in real columns 0.76 to 0.81 of the usual, full-size kana from 0.85 on) and set right
     * of the middle (in the upper right) of their cells in vertical text.
     */
    private fun sized(c: OcrChar, cell: Box, ink: Box, usual: Float): OcrChar {
        val size = extent(ink, cell) / usual
        val right = (ink.centerX - cell.left) / cell.width
        val fixed = when {
            size <= 0.83f && right >= 0.52f -> SMALL_KANA[c.text]
            size >= 0.9f -> FULL_KANA[c.text]
            else -> null
        }
        return if (fixed == null) c else OcrChar(fixed, c.box)
    }

    private val STRAY_PUNCTUATION = setOf("、", "。", ",", ".", "，", "．", "'", "`")

    private val SMALL_KANA = ("あぁいぃうぅえぇおぉつっやゃゆゅよょわゎアァイィウゥエェオォツッヤャユュヨョワヮカヵケヶ")
        .chunked(2).associate { it.substring(0, 1) to it.substring(1) }
    private val FULL_KANA = SMALL_KANA.entries.associate { (full, small) -> small to full }

    /**
     * What the marks may be read as. Marks read as other marks are left as read: the recognizer
     * knows 、 from 。 better than their shapes tell, and a bar may be a dash (――) after all.
     */
    private val LOOKALIKES = setOf(".", ",", "'", "`", "°", "o", "O", "0", "゜", "・", "･", "｡", "､", "，", "．").let { dots ->
        mapOf("。" to dots, "、" to dots, "ー" to setOf("ー", "一", "-", "−", "‐", "－", "_"))
    }

    private fun isCjkAt(chars: List<OcrChar>, i: Int): Boolean {
        val cp = chars.getOrNull(i)?.text?.codePointAt(0) ?: return false
        return cp >= 0x3000
    }

    private val BARS = setOf("|", "｜", "l", "I", "丨", "︱")
    private val DOTS = setOf(":", "：", "⋮", "︙")
    private val OPENING = setOf("J", "ㄱ", "┐", "¬")
    private val CLOSING = setOf("r", "L", "└")

    private class Ink(val mask: BooleanArray, val background: Int, val contrast: Contrast)

    /** The box of the ink in the cell of [size] at ([left], [top]) of the [mask], or null if it has none. */
    private fun inkBox(mask: BooleanArray, w: Int, h: Int, left: Float, top: Float, size: Float): Box? {
        val x0 = left.roundToInt().coerceIn(0, w)
        val x1 = (left + size).roundToInt().coerceIn(0, w)
        val y0 = top.roundToInt().coerceIn(0, h)
        val y1 = (top + size).roundToInt().coerceIn(0, h)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in y0 until y1) for (x in x0 until x1) if (mask[y * w + x]) {
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        return if (maxX < 0) null else Box(minX.toFloat(), minY.toFloat(), maxX + 1f, maxY + 1f)
    }

    /**
     * The mark in the cell of [size] at ([left], [top]) of a column if that is all the cell holds
     * (see [Column.marks]), given the [box] of its ink: 、 or 。 in the upper right (。 is told
     * from 、 by its hole), or a long vowel mark, an unbroken thin bar down the middle (unlike ！
     * or ︙, which are broken).
     */
    private fun markIn(mask: BooleanArray, w: Int, h: Int, left: Float, top: Float, size: Float, box: Box): String? {
        val minX = box.left.toInt()
        val minY = box.top.toInt()
        val maxX = box.right.toInt() - 1
        val maxY = box.bottom.toInt() - 1
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        // About as wide as high, in the upper right (but clear of the top: ink there is the end of
        // the character above, cut off), and more than a speck.
        if (minX >= left + 0.45f * size && maxY <= top + 0.55f * size && minY >= top + max(2f, 0.03f * size) &&
            max(width, height) >= 0.12f * size && max(width, height) <= 2 * min(width, height) + 1
        ) {
            return if (hasHole(mask, w, h, minX, minY, maxX, maxY)) "。" else "、"
        }
        if (width <= 0.16f * size && height >= 0.45f * size && abs((minX + maxX + 1) / 2f - (left + size / 2)) <= 0.2f * size &&
            (minY..maxY).all { y -> (minX..maxX).any { x -> mask[y * w + x] } }
        ) {
            return "ー"
        }
        return null
    }

    /** Whether the ink within the box from ([x0], [y0]) to ([x1], [y1]) (inclusive) encloses background. */
    private fun hasHole(mask: BooleanArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        // Fill the background from around the box; background left unreached is enclosed.
        val left = max(0, x0 - 1)
        val top = max(0, y0 - 1)
        val bw = min(w - 1, x1 + 1) - left + 1
        val bh = min(h - 1, y1 + 1) - top + 1
        val reached = BooleanArray(bw * bh)
        val queue = ArrayDeque<Int>()
        fun visit(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= bw || y >= bh) return
            val i = y * bw + x
            if (reached[i] || mask[(top + y) * w + left + x]) return
            reached[i] = true
            queue.addLast(i)
        }
        for (x in 0 until bw) {
            visit(x, 0)
            visit(x, bh - 1)
        }
        for (y in 0 until bh) {
            visit(0, y)
            visit(bw - 1, y)
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % bw
            val y = i / bw
            visit(x - 1, y)
            visit(x + 1, y)
            visit(x, y - 1)
            visit(x, y + 1)
        }
        for (y in 0 until bh) for (x in 0 until bw) {
            if (!reached[y * bw + x] && !mask[(top + y) * w + left + x]) return true
        }
        return false
    }

    /**
     * Pixels that stand out from the background: the most common brightness. The threshold is
     * half the strongest contrast in the region, so that faint patterns (screentone, textures)
     * don't count as ink but text in any color does.
     */
    private fun inkMask(pixels: IntArray, w: Int, h: Int): Ink? {
        val n = w * h
        val lum = IntArray(n)
        val histogram = IntArray(64)
        for (i in 0 until n) {
            val p = pixels[i]
            val l = luminance(p)
            lum[i] = l
            histogram[l shr 2]++
        }
        val bin = histogram.indices.maxBy { histogram[it] }
        val background = bin * 4 + 2
        val contrast = IntArray(256)
        for (l in lum) contrast[abs(l - background)]++
        var count = 0
        var strong = 0
        for (d in 255 downTo 0) {
            count += contrast[d]
            if (count >= n * 0.005f) {
                strong = d
                break
            }
        }
        if (strong < 60) return null
        val threshold = max(48, strong / 2)
        val mask = BooleanArray(n) { abs(lum[it] - background) > threshold }
        // The background's actual color, for filling around cut-out cells.
        var r = 0L
        var g = 0L
        var b = 0L
        var k = 0
        for (i in 0 until n) if (lum[i] shr 2 == bin) {
            r += (pixels[i] shr 16) and 0xFF
            g += (pixels[i] shr 8) and 0xFF
            b += pixels[i] and 0xFF
            k++
        }
        val color = if (k == 0) -1 else (0xFF shl 24) or ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (b / k).toInt()
        return Ink(mask, color, Contrast(threshold, strong))
    }

    /** Index ranges [start, end) where [value] is at least [min]. */
    private inline fun runs(size: Int, min: Int, value: (Int) -> Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (i in 0 until size) {
            if (value(i) >= min) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out += start to i
                start = -1
            }
        }
        if (start >= 0) out += start to size
        return out
    }

    /** Joins ranges at most [gap] apart if the result is at most [width] long. */
    private fun mergeRuns(runs: List<Pair<Int, Int>>, gap: Float, width: Float): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (run in runs) {
            val last = out.lastOrNull()
            if (last != null && run.first - last.second <= gap && run.second - last.first <= width) {
                out[out.size - 1] = last.first to run.second
            } else {
                out += run
            }
        }
        return out
    }

    /** Splits [x0, x1) into [n] columns at the lowest ink near where they should meet. */
    private fun splitAtMinima(x0: Int, x1: Int, n: Int, profile: IntArray): List<Pair<Int, Int>> {
        val width = (x1 - x0).toFloat() / n
        val cuts = ArrayList<Int>()
        cuts += x0
        for (k in 1 until n) {
            val center = x0 + k * width
            val from = (center - width / 4).toInt().coerceAtLeast(x0 + 1)
            val to = (center + width / 4).toInt().coerceAtMost(x1 - 1)
            cuts += (from..to).minByOrNull { profile[it] } ?: center.toInt()
        }
        cuts += x1
        return cuts.zipWithNext()
    }

    private class RawColumn(val x0: Int, val x1: Int, val y0: Int, val rows: IntArray, val spans: IntArray) {
        val width get() = x1 - x0

        /**
         * The ink near each row (the least within a row up and down, so that a gap between two
         * characters is found even if a boundary misses it by a row), relative to the most inked row.
         */
        val near: FloatArray = run {
            val peak = max(1, rows.maxOrNull() ?: 0).toFloat()
            FloatArray(rows.size) { i ->
                minOf(rows[i], if (i > 0) rows[i - 1] else rows[i], if (i < rows.size - 1) rows[i + 1] else rows[i]) / peak
            }
        }
    }

    /** Groups columns of similar width: main text and furigana have different pitches. */
    private fun groupByWidth(columns: List<RawColumn>): List<List<RawColumn>> {
        val groups = ArrayList<MutableList<RawColumn>>()
        for (c in columns.sortedBy { it.width }) {
            val group = groups.lastOrNull()
            if (group != null && c.width <= 1.3f * group.first().width) group += c else groups += mutableListOf(c)
        }
        return groups
    }

    /**
     * The pitch of the characters of [columns] (all of a similar size), and for each column the
     * offset of its grid: the grid whose cell boundaries fall on the least ink, that is, between
     * characters. Pitches are tried from [from] to [to], by default 0.9 to 1.55 times the columns'
     * width (characters are about as wide as their cells, kana a bit narrower), preferring about
     * 1.1 times.
     */
    private fun pitch(
        columns: List<RawColumn>,
        from: Float = 0.9f * medianWidth(columns),
        to: Float = 1.55f * medianWidth(columns),
    ): Pair<Float, FloatArray> {
        val width = medianWidth(columns)
        var bestScore = Float.MAX_VALUE
        var bestPitch = width * 1.1f
        var bestPhases = FloatArray(columns.size)
        var pitch = from
        while (pitch <= to) {
            var total = 0f
            var weight = 0
            val phases = FloatArray(columns.size)
            columns.forEachIndexed { i, c ->
                var best = Float.MAX_VALUE
                var bestCount = 0
                var phase = 0f
                while (phase < pitch) {
                    var sum = 0f
                    var count = 0
                    var y = phase
                    while (y < c.rows.size - 0.5f) {
                        if (y > 0f) {
                            sum += c.near[y.roundToInt().coerceAtMost(c.rows.size - 1)]
                            count++
                        }
                        y += pitch
                    }
                    val score = if (count > 0) sum / count else 0f
                    if (score < best) {
                        best = score
                        bestCount = count
                        phases[i] = phase
                    }
                    phase += 0.5f
                }
                total += best * bestCount
                weight += bestCount
            }
            val score = (if (weight > 0) total / weight else 1f) + 0.05f * abs(pitch / width - 1.1f)
            if (score < bestScore) {
                bestScore = score
                bestPitch = pitch
                bestPhases = phases
            }
            pitch += 0.25f
        }
        return bestPitch to bestPhases
    }

    private fun medianWidth(columns: List<RawColumn>) = columns.map { it.width }.sorted()[columns.size / 2].toFloat()

    /**
     * Makes a pitch exact: many pitches near the right one put every boundary into a gap between
     * characters, so the pitch and offsets are fitted to the middles of those gaps (least squares,
     * one pitch for all columns).
     */
    private fun refine(columns: List<RawColumn>, estimate: Pair<Float, FloatArray>): Pair<Float, FloatArray> {
        val (pitch, phases) = estimate
        // For each column, the middles of the gaps near its boundaries, numbered by boundary.
        val gaps = columns.mapIndexed { i, c ->
            val found = ArrayList<Pair<Int, Float>>()
            var k = 0
            var y = phases[i]
            while (y < c.rows.size - 0.5f) {
                if (y > 0f) gapMiddle(c.rows, y, pitch / 4)?.let { found += k to it }
                y += pitch
                k++
            }
            found
        }
        var num = 0.0
        var den = 0.0
        for (g in gaps) {
            if (g.size < 2) continue
            val mk = g.sumOf { it.first.toDouble() } / g.size
            val my = g.sumOf { it.second.toDouble() } / g.size
            for ((k, y) in g) {
                num += (k - mk) * (y - my)
                den += (k - mk) * (k - mk)
            }
        }
        if (den <= 0.0) return estimate
        val fitted = (num / den).toFloat()
        if (abs(fitted - pitch) > pitch * 0.1f) return estimate
        val refined = FloatArray(columns.size) { i ->
            val g = gaps[i]
            if (g.isEmpty()) {
                phases[i]
            } else {
                val offset = g.map { (k, y) -> y - k * fitted }.average().toFloat()
                ((offset % fitted) + fitted) % fitted
            }
        }
        return fitted to refined
    }

    /** The middle of the run of (nearly) empty rows within [reach] of [y], or null. */
    private fun gapMiddle(rows: IntArray, y: Float, reach: Float): Float? {
        val peak = rows.maxOrNull() ?: return null
        val empty = max(0, peak / 20)
        val center = y.roundToInt().coerceIn(0, rows.size - 1)
        var best: Float? = null
        var bestDistance = Float.MAX_VALUE
        var i = (y - reach).roundToInt().coerceAtLeast(0)
        val end = (y + reach).roundToInt().coerceAtMost(rows.size - 1)
        while (i <= end) {
            if (rows[i] <= empty) {
                var j = i
                while (j + 1 < rows.size && rows[j + 1] <= empty) j++
                val middle = (i + j) / 2f
                val distance = abs(middle - center)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = middle
                }
                i = j + 1
            } else {
                i++
            }
        }
        return best
    }

    /**
     * The tops of the cells of a column of furigana. Furigana isn't set on one grid: the reading
     * of each word (or character) is centered on it, which leaves gaps of any size between them.
     * So cells of [pitch] are placed one after another where they fit the ink best: their edges
     * in gaps between characters, their characters in their middles. Empty rows between cells are
     * skipped.
     */
    private fun furiganaCellTops(c: RawColumn, pitch: Float): List<Float> {
        val p = pitch.roundToInt().coerceAtLeast(2)
        // Rows of the column with room around it for cells reaching past its ink.
        val pad = p
        val n = c.rows.size + 2 * pad
        fun ink(y: Int) = if (y - pad in c.rows.indices) c.rows[y - pad] else 0
        fun near(y: Int) = if (y - pad in c.rows.indices) c.near[y - pad] else 0f
        val empty = max(0, (c.rows.maxOrNull() ?: 0) / 20)
        // Running sums of the ink and of the ink times its row, for where the ink of a cell is centered.
        val sum = LongArray(n + 1)
        val moment = LongArray(n + 1)
        for (y in 0 until n) {
            sum[y + 1] = sum[y] + ink(y)
            moment[y + 1] = moment[y] + ink(y).toLong() * y
        }
        // The best cells for the rows up to each row: their cost, and where the last step started.
        val cost = FloatArray(n + 1) { Float.MAX_VALUE }
        val from = IntArray(n + 1) { -1 }
        val cellEnds = BooleanArray(n + 1)
        cost[0] = 0f
        for (y in 0 until n) {
            val base = cost[y]
            if (base == Float.MAX_VALUE) continue
            if (ink(y) <= empty && base < cost[y + 1]) {
                cost[y + 1] = base
                from[y + 1] = y
                cellEnds[y + 1] = false
            }
            val end = y + p
            val inCell = if (end <= n) sum[end] - sum[y] else 0L
            if (inCell > 0) {
                val center = (moment[end] - moment[y]).toFloat() / inCell + 0.5f
                val total = base + near(y) + near(end - 1) + 0.5f * abs(center - (y + p / 2f)) / p
                if (total < cost[end]) {
                    cost[end] = total
                    from[end] = y
                    cellEnds[end] = true
                }
            }
        }
        if (cost[n] == Float.MAX_VALUE) return emptyList()
        val tops = ArrayList<Float>()
        var y = n
        while (y > 0) {
            if (cellEnds[y]) tops += (from[y] - pad).toFloat()
            y = from[y]
        }
        return tops.reversed()
    }

    /** The tops of the cells covering a column's ink, without empty cells at its ends. */
    private fun cellTops(rows: IntArray, pitch: Float, phase: Float): List<Float> {
        val h = rows.size
        val cells = ArrayList<Pair<Float, Int>>()
        var y = if (phase > 0f) phase - pitch else 0f
        while (y < h - 0.5f) {
            val from = y.roundToInt().coerceIn(0, h)
            val to = (y + pitch).roundToInt().coerceIn(0, h)
            var sum = 0
            for (i in from until to) sum += rows[i]
            cells += y to sum
            y += pitch
        }
        if (cells.isEmpty()) return emptyList()
        val median = cells.map { it.second }.sorted()[cells.size / 2]
        // Punctuation has little ink, so only (nearly) empty cells are dropped.
        val minInk = max(1f, median * 0.04f)
        val first = cells.indexOfFirst { it.second >= minInk }
        val last = cells.indexOfLast { it.second >= minInk }
        return if (first < 0) emptyList() else cells.subList(first, last + 1).map { it.first }
    }
}
