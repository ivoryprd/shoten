package io.github.ivoryprd.shoten.ocr

import io.github.ivoryprd.shoten.util.Cjk
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A line of an [OcrResult]: its block and its index in the block. */
data class LineRef(val block: Int, val line: Int)

/**
 * Works out how text flows from line to line, so that text wrapped onto the next line can be read
 * (and looked up) as one: for every line, the line its text continues on, below it or, for
 * vertical text, the column to its left.
 *
 * ML Kit's blocks are not enough for this: it often puts each line of a paragraph in a block of
 * its own, for example when the lines are far apart or furigana sits between them. So besides
 * lines that follow each other in a block, lines of different blocks are joined when they look
 * like a wrapped paragraph: text of the same size and script, close together, aligned at the
 * start (or centered), the first line reaching about as far as the next one and not ending a
 * sentence. Furigana (small text right above a line or beside a column) is never joined.
 */
internal object TextFlow {
    fun successors(blocks: List<OcrBlock>): Map<LineRef, LineRef> {
        val all = blocks.flatMapIndexed { b, block ->
            block.lines.mapIndexedNotNull { l, line ->
                val box = line.box
                if (box == null || line.text.isBlank()) null else Item(LineRef(b, l), line, box)
            }
        }
        val lines = all.filter { r -> all.none { m -> isRuby(r, m) } }
        val byRef = lines.associateBy { it.ref }
        val proposals = lines.mapNotNull { a ->
            val nextInBlock = byRef[LineRef(a.ref.block, a.ref.line + 1)]
            if (nextInBlock != null && continuesInBlock(a, nextInBlock)) {
                Link(a, nextInBlock, sameBlock = true)
            } else {
                nearestFollowing(a, lines)?.takeIf { wraps(a, it) }?.let { Link(a, it, sameBlock = false) }
            }
        }
        // A line continues on one line at most, and is continued from one line at most: ML Kit's
        // own blocks win, then the closest line.
        return proposals.groupBy { it.to.ref }.values
            .map { links -> links.minWith(compareBy<Link>({ !it.sameBlock }, { it.gap })) }
            .associate { it.from.ref to it.to.ref }
    }

    private class Item(val ref: LineRef, val line: OcrLine, val box: Box) {
        val vertical = line.vertical

        /**
         * The size of the text (an em): the size of its Chinese or Japanese characters' cells, or
         * else the height of a horizontal line or the width of a vertical one. (The ink of a line
         * is less reliable: kana are smaller than kanji.)
         */
        val size: Float = line.chars
            .filter { it.box != null && it.text.isNotEmpty() && isFullWidthCjk(it.text.codePointAt(0)) }
            .map { if (vertical) it.box!!.height else it.box!!.width }
            .sorted()
            .let { cells -> if (cells.isEmpty()) (if (vertical) box.width else box.height) else cells[cells.size / 2] }

        /** Where the line begins and ends across the text's flow (lines follow in that direction). */
        val crossStart = if (vertical) -box.right else box.top
        val crossEnd = if (vertical) -box.left else box.bottom

        /** Where the line begins and ends along its text. */
        val start = if (vertical) box.top else box.left
        val end = if (vertical) box.bottom else box.right

        val cjk = Cjk.codePoints(line.text).any { Cjk.isJapanese(it) }
    }

    private class Link(val from: Item, val to: Item, val sameBlock: Boolean) {
        val gap = to.crossStart - from.crossEnd
    }

    private fun isFullWidthCjk(cp: Int) = Cjk.isHan(cp) || cp in 0x3040..0x30FF

    /** Whether [r] is furigana of [m]: much smaller text right above it (or right of a column). */
    private fun isRuby(r: Item, m: Item): Boolean {
        if (r === m) return false
        val size = m.size
        // Readings over separate kanji have gaps between them, which make their cells look wide.
        if (min(r.size, if (m.vertical) r.box.width else r.box.height) > size * 0.8f) return false
        return if (m.vertical) {
            val gap = r.box.left - m.box.right
            gap >= -0.3f * size && gap <= 0.6f * size && r.box.top >= m.box.top - size && r.box.bottom <= m.box.bottom + size
        } else {
            val gap = m.box.top - r.box.bottom
            gap >= -0.3f * size && gap <= 0.6f * size && r.box.left >= m.box.left - size && r.box.right <= m.box.right + size
        }
    }

    private fun overlaps(a: Item, b: Item) = b.start < a.end && a.start < b.end

    /** Lines that follow each other in one of ML Kit's blocks: [b] has to come after [a] and be near. */
    private fun continuesInBlock(a: Item, b: Item): Boolean =
        a.vertical == b.vertical && b.size in a.size * 0.6f..a.size * 1.67f &&
            b.crossStart > a.crossStart + 0.3f * a.size && b.crossStart - a.crossEnd <= 2.5f * max(a.size, b.size) &&
            overlaps(a, b)

    /** The closest line after [a] (below it, or left of a column) that overlaps it along the text. */
    private fun nearestFollowing(a: Item, lines: List<Item>): Item? = lines
        .filter { it !== a && it.vertical == a.vertical && it.crossStart > a.crossStart + 0.5f * a.size && overlaps(a, it) }
        .minByOrNull { it.crossStart }

    /** Whether [a]'s text looks like it wraps onto [b]. */
    private fun wraps(a: Item, b: Item): Boolean {
        val size = max(a.size, b.size)
        val gap = b.crossStart - a.crossEnd
        val aligned = abs(b.start - a.start) <= 1.5f * size || abs((b.start + b.end) - (a.start + a.end)) / 2 <= size
        return b.size in a.size * 0.7f..a.size * 1.43f &&
            gap >= -0.3f * size && gap <= 1.8f * size && // Games often space lines widely.
            a.cjk == b.cjk &&
            aligned &&
            a.end >= b.end - 1.5f * size &&
            a.line.text.trimEnd().lastOrNull()?.let { it in SENTENCE_ENDS } != true
    }

    /** Characters that end a sentence or a quote; text doesn't wrap into a new paragraph after them. */
    private const val SENTENCE_ENDS = "。！？．.!?」』"
}
