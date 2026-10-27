package io.github.ivoryprd.shoten.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Columns of vertical text cut into their cells and laid out as rows of horizontal text: the
 * recognizer reads text like this much more reliably than vertical text (small text, text on
 * screentone, short columns). Every cell is scaled to the same size; the rows are far enough
 * apart to be read as separate lines, and long columns take several rows. [mapBack] puts what
 * was read back into the cells.
 */
internal class VerticalSheet private constructor(val bitmap: Bitmap, private val rows: List<Row>) {
    /** A row of a sheet: the cells of [column] from [from] until [to]. */
    class Row(val column: VerticalLayout.Column, val from: Int, val to: Int) {
        val size get() = to - from
    }

    /** The characters read in each row, with the boxes of their cells in the image. */
    fun mapBack(read: OcrResult): List<List<OcrChar>> {
        val placed = rows.map { ArrayList<Pair<Int, OcrChar>>() }
        for (block in read.blocks) for (line in block.lines) for (ch in line.chars) {
            val box = ch.box ?: continue
            if (ch.text.isBlank()) continue
            val row = floor((box.centerY - PAD + (ROW_PITCH - CELL) / 2f) / ROW_PITCH).toInt()
            if (row !in rows.indices) continue
            val size = rows[row].size
            val cell = floor((box.centerX - PAD) / CELL).toInt()
            // Just outside the cells (a wide glyph's box) still counts; further out it isn't from this row.
            val index = when {
                cell in 0 until size -> cell
                cell == -1 && box.centerX >= PAD - CELL / 2f -> 0
                cell == size && box.centerX < PAD + (size + 0.5f) * CELL -> size - 1
                else -> continue
            }
            placed[row] += index to ch
        }
        return rows.mapIndexed { i, row ->
            placed[i].sortedBy { it.first }.map { (k, ch) -> OcrChar(ch.text, row.column.cells[row.from + k]) }
        }
    }

    companion object {
        /** The size of a cell on a sheet; characters of about this size are read best. */
        const val CELL = 60
        private const val PAD = 30
        private const val ROW_PITCH = 2 * CELL

        /** Rows per sheet and cells per row, which keep sheets to a size the recognizer reads at full resolution. */
        const val ROWS = 16
        const val ROW_CELLS = 32

        /**
         * Reads [columns] of [image] with [recognize], on as many sheets as they take: the
         * characters read in each column, with the boxes of their cells in the image.
         */
        suspend fun read(
            image: Bitmap,
            columns: List<VerticalLayout.Column>,
            recognize: suspend (Bitmap) -> OcrResult,
        ): Map<VerticalLayout.Column, List<OcrChar>> {
            val rows = columns.flatMap { column ->
                (column.cells.indices step ROW_CELLS).map { Row(column, it, min(it + ROW_CELLS, column.cells.size)) }
            }
            val chars = columns.associateWith { ArrayList<OcrChar>() }
            for (chunk in rows.chunked(ROWS)) {
                val sheet = create(image, chunk)
                val read = try {
                    sheet.mapBack(recognize(sheet.bitmap))
                } finally {
                    sheet.bitmap.recycle()
                }
                chunk.forEachIndexed { i, row -> chars.getValue(row.column) += read[i] }
            }
            return chars.mapValues { (column, read) -> VerticalLayout.readColumn(column, read) }
        }

        /** Lays out [rows] (at most [ROWS], of at most [ROW_CELLS] cells) of [image] on a sheet. */
        fun create(image: Bitmap, rows: List<Row>): VerticalSheet {
            val longest = rows.maxOf { it.size }
            val bitmap = Bitmap.createBitmap(2 * PAD + longest * CELL, 2 * PAD + (rows.size - 1) * ROW_PITCH + CELL, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(rows.groupingBy { it.column.background }.eachCount().maxBy { it.value }.key)
            val fill = Paint()
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val src = Rect()
            val dst = RectF()
            rows.forEachIndexed { r, row ->
                val top = PAD + r * ROW_PITCH
                // Each row on the background of its own column.
                fill.color = row.column.background
                canvas.drawRect(0f, top - CELL / 2f, bitmap.width.toFloat(), top + CELL * 1.5f, fill)
                for (k in 0 until row.size) {
                    val cell = row.column.cells[row.from + k]
                    val scale = CELL / max(1f, cell.width)
                    // Marks are drawn as in horizontal text: 、 and 。 from the upper right of the cell to
                    // the lower left, ー turned back a quarter.
                    val mark = row.column.marks[row.from + k]
                    val moved = mark == "。" || mark == "、"
                    val part = if (moved) Box(cell.centerX, cell.top, cell.right, cell.centerY) else cell
                    src.set(
                        part.left.toInt().coerceIn(0, image.width), part.top.toInt().coerceIn(0, image.height),
                        part.right.toInt().coerceIn(0, image.width), part.bottom.toInt().coerceIn(0, image.height),
                    )
                    if (src.isEmpty) continue
                    val left = PAD + k * CELL
                    val y = top + if (moved) CELL / 2f else 0f
                    dst.set(
                        left + (src.left - part.left) * scale, y + (src.top - part.top) * scale,
                        left + (src.right - part.left) * scale, y + (src.bottom - part.top) * scale,
                    )
                    if (mark == "ー") {
                        canvas.save()
                        canvas.rotate(-90f, left + CELL / 2f, top + CELL / 2f)
                        canvas.drawBitmap(image, src, dst, paint)
                        canvas.restore()
                    } else {
                        canvas.drawBitmap(image, src, dst, paint)
                    }
                }
            }
            return VerticalSheet(bitmap, rows)
        }
    }
}
