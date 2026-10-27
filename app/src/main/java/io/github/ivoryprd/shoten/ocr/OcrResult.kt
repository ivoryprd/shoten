package io.github.ivoryprd.shoten.ocr

import io.github.ivoryprd.shoten.util.Cjk
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** An axis-aligned box in image coordinates. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun contains(x: Float, y: Float, slop: Float = 0f) =
        x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop

    fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    /** This box limited to [other], or this box if they don't overlap. */
    fun clampTo(other: Box): Box {
        val clamped = Box(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom))
        return if (clamped.width > 0f && clamped.height > 0f) clamped else this
    }

    fun distanceTo(x: Float, y: Float): Float {
        val dx = max(0f, max(left - x, x - right))
        val dy = max(0f, max(top - y, y - bottom))
        return hypot(dx, dy)
    }
}

/** One recognized character (usually one code point). [box] is null for inserted spaces. */
class OcrChar(val text: String, val box: Box?)

/** A line of text. [box] is the recognizer's box of the line, or else the union of its characters. */
class OcrLine(val chars: List<OcrChar>, lineBox: Box? = null) {
    val text: String = chars.joinToString("") { it.text }
    val box: Box? = lineBox ?: chars.mapNotNull { it.box }.reduceOrNull { a, b -> a.union(b) }

    /** Whether the characters run top to bottom (vertical Japanese/Chinese text). */
    val vertical: Boolean = run {
        val boxes = chars.mapNotNull { it.box }
        if (boxes.size < 2) {
            val b = boxes.firstOrNull()
            b != null && b.height > b.width * 1.8f && Cjk.codePointCount(text) > 1
        } else {
            abs(boxes.last().centerY - boxes.first().centerY) > abs(boxes.last().centerX - boxes.first().centerX)
        }
    }
}

class OcrBlock(val lines: List<OcrLine>)

data class CharPosition(val block: Int, val line: Int, val char: Int)

/** Text to scan from a tapped character, with the position of every character in it. */
class ScanText(val text: String, private val positions: List<CharPosition>, private val offsets: IntArray) {
    /** Positions of the characters covered by the first [length] UTF-16 units of [text]. */
    fun positionsCovering(length: Int): List<CharPosition> =
        positions.filterIndexed { i, _ -> offsets[i] < length }
}

class OcrResult(val blocks: List<OcrBlock>, val width: Int, val height: Int) {
    val isEmpty: Boolean get() = blocks.all { b -> b.lines.all { it.text.isBlank() } }

    val fullText: String get() = blocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } }

    fun charAt(p: CharPosition): OcrChar? = blocks.getOrNull(p.block)?.lines?.getOrNull(p.line)?.chars?.getOrNull(p.char)

    fun lineAt(p: CharPosition): OcrLine? = blocks.getOrNull(p.block)?.lines?.getOrNull(p.line)

    private fun lineAt(ref: LineRef): OcrLine? = blocks.getOrNull(ref.block)?.lines?.getOrNull(ref.line)

    /** For every line, the line its text continues on when it wraps (see [TextFlow]). */
    private val successors: Map<LineRef, LineRef> by lazy { TextFlow.successors(blocks) }

    /** The line whose text continues [line]'s text (the next line of a wrapped paragraph), or null. */
    fun nextLine(line: LineRef): LineRef? = successors[line]

    /**
     * Finds the character at (x, y) in image coordinates. Characters within [slop] count as hits;
     * inside a line's box the nearest character is returned.
     */
    fun hitTest(x: Float, y: Float, slop: Float): CharPosition? {
        var best: CharPosition? = null
        var bestDistance = Float.MAX_VALUE
        blocks.forEachIndexed { bi, block ->
            block.lines.forEachIndexed { li, line ->
                val lineBox = line.box ?: return@forEachIndexed
                if (!lineBox.contains(x, y, slop)) return@forEachIndexed
                line.chars.forEachIndexed { ci, ch ->
                    val box = ch.box
                    if (box != null && ch.text.isNotBlank()) {
                        val d = box.distanceTo(x, y)
                        if (d < bestDistance) {
                            bestDistance = d
                            best = CharPosition(bi, li, ci)
                        }
                    }
                }
            }
        }
        return if (bestDistance <= slop) best else null
    }

    /**
     * The text from [start] on, continuing on the next lines where the text wraps (see [nextLine]),
     * limited to [maxChars] characters. Lines are joined without a separator, which suits Chinese
     * and Japanese; Latin words get a space between them.
     */
    fun scanText(start: CharPosition, maxChars: Int): ScanText {
        var ref = LineRef(start.block, start.line)
        var line = lineAt(ref) ?: return ScanText("", emptyList(), IntArray(0))
        val sb = StringBuilder()
        val positions = ArrayList<CharPosition>()
        val offsets = ArrayList<Int>()
        val visited = hashSetOf(ref)
        var ci = start.char
        while (positions.size < maxChars) {
            if (ci >= line.chars.size) {
                val next = successors[ref]?.takeIf { visited.add(it) } ?: break
                line = lineAt(next) ?: break
                ref = next
                ci = 0
                val last = sb.lastOrNull()
                val first = line.text.firstOrNull()
                if (last != null && first != null && isSpaced(last) && isSpaced(first)) sb.append(' ')
                continue
            }
            val ch = line.chars[ci]
            offsets.add(sb.length)
            positions.add(CharPosition(ref.block, ref.line, ci))
            sb.append(ch.text)
            ci++
        }
        return ScanText(sb.toString(), positions, offsets.toIntArray())
    }

    companion object {
        /**
         * Builds a line from recognized pieces (words/elements), using per-character boxes
         * ([symbols]) where they are plausible and else splitting a piece's box evenly, merging
         * half-width voicing marks, and inserting spaces between Latin words. [lineBox] is the
         * recognizer's box of the whole line, if known.
         */
        fun buildLine(pieces: List<Pair<String, Box?>>, symbols: List<List<Pair<String, Box?>>>?, lineBox: Box? = null): OcrLine {
            val chars = ArrayList<OcrChar>()
            pieces.forEachIndexed { index, (text, box) ->
                val pieceSymbols = symbols?.getOrNull(index)?.takeIf { it.isNotEmpty() && it.joinToString("") { s -> s.first } == text }
                val pieceChars = box?.takeIf { Cjk.codePoints(text).any(::isFullWidthCjk) }?.let { fitCjkCells(text, it, pieceSymbols) }
                    ?: pieceSymbols?.takeIf { plausible(it, box) }
                        ?.map { (symbol, symbolBox) -> OcrChar(symbol, if (box != null) symbolBox?.clampTo(box) else symbolBox) }
                    ?: splitEvenly(text, box)
                if (chars.isNotEmpty() && pieceChars.isNotEmpty()) {
                    val prev = chars.last().text.lastOrNull()
                    val next = pieceChars.first().text.firstOrNull()
                    if (prev != null && next != null && isSpaced(prev) && isSpaced(next)) chars.add(OcrChar(" ", null))
                }
                chars.addAll(pieceChars)
            }
            return OcrLine(normalize(chars), lineBox)
        }

        private fun isFullWidthCjk(cp: Int) = Cjk.isHan(cp) || cp in 0x3000..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF01..0xFF60

        /**
         * Character boxes for a piece of Chinese or Japanese text. ML Kit's boxes of CJK characters
         * are shifted along the line by up to half a character (while the piece's box is accurate),
         * so the characters are fitted into the piece's box instead: full-width characters take one
         * em and half-width ones half an em, and punctuation with its ink at one side of its cell
         * (「。) reaches past the box. ML Kit's widths tell full-width Latin characters from
         * half-width ones (it reports both as ASCII). Returns null if the text doesn't fit.
         */
        private fun fitCjkCells(text: String, box: Box, symbols: List<Pair<String, Box?>>?): List<OcrChar>? {
            val cps = Cjk.codePoints(text).map { String(Character.toChars(it)) }.toList()
            if (cps.size < 2) return null
            val vertical = box.height > box.width * 1.5f
            val widths = symbols?.takeIf { it.size == cps.size }?.map { (_, b) -> b?.let { if (vertical) it.height else it.width } }
            // ML Kit's first and last boxes are the least accurate; take the typical width from the others.
            val typical = widths?.let { w ->
                val middle = w.indices.filter { it != 0 && it != w.lastIndex && cps[it][0].code >= 0x80 }.mapNotNull { w[it] }.sorted()
                middle.getOrNull(middle.size / 2)
            }
            val judged = BooleanArray(cps.size)
            val weights = FloatArray(cps.size) { i ->
                val cp = cps[i].codePointAt(0)
                val w = widths?.getOrNull(i)
                when {
                    cp in 0xFF61..0xFF9F -> 0.5f
                    cp >= 0x80 -> 1f
                    typical != null && w != null && i != 0 && i != cps.lastIndex -> {
                        judged[i] = true
                        if (w < typical * 0.75f) 0.5f else 1f
                    }
                    Character.isLetterOrDigit(cp) -> 0.5f
                    else -> 1f // Punctuation in CJK text is usually full width.
                }
            }
            // A Latin letter or digit at either end is as wide as its neighbor in the same run.
            for ((end, neighbor) in listOf(0 to 1, cps.lastIndex to cps.lastIndex - 1)) {
                if (isAsciiAlphanumeric(cps[end]) && isAsciiAlphanumeric(cps[neighbor]) && judged[neighbor]) weights[end] = weights[neighbor]
            }
            val lead = weights.first() * leadingBlank(cps.first())
            val trail = weights.last() * trailingBlank(cps.last())
            val units = weights.sum() - lead - trail
            if (units <= 0f) return null
            val em = (if (vertical) box.height else box.width) / units
            var position = (if (vertical) box.top else box.left) - lead * em
            return cps.mapIndexed { i, s ->
                val start = position
                position += weights[i] * em
                val cell = if (vertical) Box(box.left, start, box.right, position) else Box(start, box.top, position, box.bottom)
                OcrChar(s, cell.clampTo(box))
            }
        }

        private fun isAsciiAlphanumeric(s: String) = s.length == 1 && s[0].code < 0x80 && s[0].isLetterOrDigit()

        /** The empty part of a character's cell before its ink, in em. */
        private fun leadingBlank(s: String): Float = if (s in OPENING) 0.6f else 0f

        /** The empty part of a character's cell after its ink, in em. */
        private fun trailingBlank(s: String): Float = when (s) {
            in CLOSING -> 0.65f
            in CENTERED -> 0.3f
            else -> 0f
        }

        private val OPENING = "「『（【〈《〔［｛〘〖([{".map { it.toString() }.toSet()
        private val CLOSING = "。、，．」』）】〉》〕］｝〙〗.,)]}".map { it.toString() }.toSet()
        private val CENTERED = "？！：；?!:;".map { it.toString() }.toSet()

        /** Latin letters, digits and punctuation, which have spaces between words (unlike CJK text). */
        private fun isSpaced(c: Char) = !c.isWhitespace() && !Cjk.isJapanese(c.code) && c.code < 0x2E80

        /**
         * Whether per-character boxes look right: all present and non-empty, centered inside the
         * piece's box and in reading order. Otherwise splitting the piece's box evenly is better.
         */
        private fun plausible(symbols: List<Pair<String, Box?>>, piece: Box?): Boolean {
            val boxes = symbols.map { it.second ?: return false }
            if (boxes.any { it.width <= 0f || it.height <= 0f }) return false
            if (piece == null) return true
            val tolerance = min(piece.width, piece.height) * 0.25f
            if (boxes.any { !piece.contains(it.centerX, it.centerY, tolerance) }) return false
            val vertical = piece.height > piece.width * 1.5f
            return boxes.zipWithNext().all { (a, b) ->
                if (vertical) b.centerY >= a.centerY - tolerance else b.centerX >= a.centerX - tolerance
            }
        }

        private fun splitEvenly(text: String, box: Box?): List<OcrChar> {
            val cps = Cjk.codePoints(text).map { String(Character.toChars(it)) }.toList()
            if (box == null || cps.size <= 1) return cps.map { OcrChar(it, box) }
            val vertical = box.height > box.width * 1.5f
            return cps.mapIndexed { i, s ->
                val f0 = i.toFloat() / cps.size
                val f1 = (i + 1).toFloat() / cps.size
                val b = if (vertical) {
                    Box(box.left, box.top + box.height * f0, box.right, box.top + box.height * f1)
                } else {
                    Box(box.left + box.width * f0, box.top, box.left + box.width * f1, box.bottom)
                }
                OcrChar(s, b)
            }
        }

        /** Converts half-width katakana to full width and merges separate voicing marks. */
        private fun normalize(chars: List<OcrChar>): List<OcrChar> {
            val out = ArrayList<OcrChar>(chars.size)
            for (ch in chars) {
                val text = ch.text
                if (text.length == 1 && Cjk.isVoicingMark(text[0]) && out.isNotEmpty()) {
                    val prev = out.last()
                    val combined = prev.text.singleOrNull()?.let { Cjk.combineVoicing(it, text[0]) }
                    if (combined != null) {
                        val box = if (prev.box != null && ch.box != null) prev.box.union(ch.box) else prev.box ?: ch.box
                        out[out.size - 1] = OcrChar(combined.toString(), box)
                        continue
                    }
                }
                out.add(if (text.length == 1) OcrChar(Cjk.halfWidthKanaToFull(text[0]).toString(), ch.box) else ch)
            }
            return out
        }
    }
}
