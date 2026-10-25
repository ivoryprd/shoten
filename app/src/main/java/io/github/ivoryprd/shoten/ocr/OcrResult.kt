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

    fun distanceTo(x: Float, y: Float): Float {
        val dx = max(0f, max(left - x, x - right))
        val dy = max(0f, max(top - y, y - bottom))
        return hypot(dx, dy)
    }
}

/** One recognized character (usually one code point). [box] is null for inserted spaces. */
class OcrChar(val text: String, val box: Box?)

class OcrLine(val chars: List<OcrChar>) {
    val text: String = chars.joinToString("") { it.text }
    val box: Box? = chars.mapNotNull { it.box }.reduceOrNull { a, b -> a.union(b) }

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
     * The text from [start] to the end of its block (lines are joined without separators, which
     * suits Chinese and Japanese), limited to [maxChars] characters.
     */
    fun scanText(start: CharPosition, maxChars: Int): ScanText {
        val block = blocks.getOrNull(start.block) ?: return ScanText("", emptyList(), IntArray(0))
        val sb = StringBuilder()
        val positions = ArrayList<CharPosition>()
        val offsets = ArrayList<Int>()
        var li = start.line
        var ci = start.char
        while (li < block.lines.size && positions.size < maxChars) {
            val line = block.lines[li]
            if (ci >= line.chars.size) {
                li++
                ci = 0
                continue
            }
            val ch = line.chars[ci]
            offsets.add(sb.length)
            positions.add(CharPosition(start.block, li, ci))
            sb.append(ch.text)
            ci++
        }
        return ScanText(sb.toString(), positions, offsets.toIntArray())
    }

    companion object {
        /**
         * Builds a line from recognized pieces (words/elements), splitting a piece's box evenly when
         * per-character boxes are not available, merging half-width voicing marks, and inserting
         * spaces between Latin words.
         */
        fun buildLine(pieces: List<Pair<String, Box?>>, symbols: List<List<Pair<String, Box?>>>?): OcrLine {
            val chars = ArrayList<OcrChar>()
            pieces.forEachIndexed { index, (text, box) ->
                val pieceChars = symbols?.getOrNull(index)?.takeIf { it.isNotEmpty() && it.joinToString("") { s -> s.first } == text }
                    ?.map { OcrChar(it.first, it.second ?: box) }
                    ?: splitEvenly(text, box)
                if (chars.isNotEmpty() && pieceChars.isNotEmpty()) {
                    val prev = chars.last().text.lastOrNull()
                    val next = pieceChars.first().text.firstOrNull()
                    if (prev != null && next != null && isWordChar(prev) && isWordChar(next)) chars.add(OcrChar(" ", null))
                }
                chars.addAll(pieceChars)
            }
            return OcrLine(normalize(chars))
        }

        private fun isWordChar(c: Char) = c.isLetterOrDigit() && !Cjk.isJapanese(c.code) && c.code < 0x2E80

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
