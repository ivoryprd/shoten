package io.github.ivoryprd.shoten.ocr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VerticalSheetTest {
    private val cell = VerticalSheet.CELL.toFloat()

    private fun column(x: Float, top: Float, pitch: Float, count: Int) = VerticalLayout.Column(
        Box(x, top, x + pitch, top + pitch * count),
        List(count) { Box(x, top + it * pitch, x + pitch, top + (it + 1) * pitch) },
        Color.WHITE,
    )

    private fun whole(vararg columns: VerticalLayout.Column) = columns.map { VerticalSheet.Row(it, 0, it.cells.size) }

    @Test
    fun charactersReadOnTheSheetGoBackToTheirCells() {
        val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val right = column(300f, 50f, 40f, 4)
        val left = column(240f, 50f, 40f, 3)
        val sheet = VerticalSheet.create(image, whole(right, left))
        // Rows of cells on the sheet, as the recognizer reads them (boxes in sheet coordinates).
        fun row(index: Int, text: String) = OcrResult.buildLine(
            listOf(text to Box(30f, 30f + index * 2 * cell, 30f + text.length * cell, 30f + index * 2 * cell + cell)),
            null,
        )
        val read = OcrResult(listOf(OcrBlock(listOf(row(0, "今日は良"), row(1, "い天気")))), sheet.bitmap.width, sheet.bitmap.height)
        val mapped = sheet.mapBack(read)
        assertEquals("今日は良", mapped[0].joinToString("") { it.text })
        assertEquals("い天気", mapped[1].joinToString("") { it.text })
        // Each character has the box of its cell in the image.
        assertEquals(right.cells[2], mapped[0][2].box)
        assertEquals(left.cells[0], mapped[1][0].box)
        // The sheet has a row per column and room for the longest one.
        assertEquals(30 * 2 + 4 * VerticalSheet.CELL, sheet.bitmap.width)
    }

    @Test
    fun missingCharactersLeaveTheirCellsOut() {
        val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val col = column(300f, 50f, 40f, 4)
        val sheet = VerticalSheet.create(image, whole(col))
        // Only the first and the last character were read (e.g. faint punctuation in between).
        val first = OcrLine(listOf(OcrChar("今", Box(30f, 30f, 30f + cell, 30f + cell))))
        val last = OcrLine(listOf(OcrChar("。", Box(30f + 3 * cell, 30f, 30f + 4 * cell, 30f + cell))))
        val mapped = sheet.mapBack(OcrResult(listOf(OcrBlock(listOf(first, last))), sheet.bitmap.width, sheet.bitmap.height))[0]
        assertEquals("今。", mapped.joinToString("") { it.text })
        assertEquals(col.cells[3], mapped[1].box)
    }

    @Test
    fun longColumnsTakeSeveralRowsAndSheets() {
        // A short column, then columns longer than a row: 1 + 8 * 2 rows, more than a sheet holds,
        // so the last column starts on the first sheet and ends on the second.
        val pitch = 40f
        val columns = listOf(column(360f, 10f, pitch, 10)) + List(8) { column(320f - it * pitch, 10f, pitch, 40) }
        // Every cell in a color of its own: red for its column, green for its place in the column.
        val image = Bitmap.createBitmap(400, 1700, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            val paint = Paint()
            columns.forEachIndexed { j, c ->
                c.cells.forEachIndexed { i, b ->
                    paint.color = Color.rgb(10 + 20 * j, 10 + 5 * i, 0)
                    drawRect(b.left, b.top, b.right, b.bottom, paint)
                }
            }
        }
        fun charOf(j: Int, i: Int) = String(Character.toChars(0x4E00 + 64 * j + i))

        // Reads the colors in the middle of the cells of each row of a sheet.
        val sheets = ArrayList<Pair<Int, Int>>()
        val read = runBlocking {
            VerticalSheet.read(image, columns) { sheet ->
                sheets += sheet.width to sheet.height
                val lines = ArrayList<OcrLine>()
                var top = 30
                while (top + VerticalSheet.CELL <= sheet.height) {
                    val chars = ArrayList<OcrChar>()
                    var left = 30
                    while (left + VerticalSheet.CELL <= sheet.width) {
                        val p = sheet.getPixel(left + VerticalSheet.CELL / 2, top + VerticalSheet.CELL / 2)
                        if (p != Color.WHITE) {
                            val j = (Color.red(p) - 10 + 10) / 20
                            val i = (Color.green(p) - 10 + 2) / 5
                            chars += OcrChar(charOf(j, i), Box(left.toFloat(), top.toFloat(), left + cell, top + cell))
                        }
                        left += VerticalSheet.CELL
                    }
                    if (chars.isNotEmpty()) lines += OcrLine(chars)
                    top += 2 * VerticalSheet.CELL
                }
                OcrResult(listOf(OcrBlock(lines)), sheet.width, sheet.height)
            }
        }

        // Two sheets, no wider than a row of cells.
        assertEquals(2, sheets.size)
        assertEquals(30 * 2 + VerticalSheet.ROW_CELLS * VerticalSheet.CELL, sheets[0].first)
        columns.forEachIndexed { j, c ->
            val chars = read.getValue(c)
            assertEquals("column $j", c.cells.indices.joinToString("") { charOf(j, it) }, chars.joinToString("") { it.text })
            assertEquals(c.cells, chars.map { it.box })
        }
    }
}
