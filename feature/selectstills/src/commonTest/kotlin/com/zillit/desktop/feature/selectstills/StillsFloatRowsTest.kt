package com.zillit.desktop.feature.selectstills

import com.zillit.desktop.feature.selectstills.ui.FloatRows
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gallery's rows flowing round the cast list, in the numbers of a 1000-wide
 * tool: 952 inside the page's padding, the list 240 plus a 24 gap, tiles at
 * least 230 and 14 apart.
 */
class StillsFloatRowsTest {

    private fun rows(count: Int, start: Float = 300f, clearAt: Float = 1100f) = FloatRows(
        count = count,
        width = 952f,
        besideX = 264f,
        gap = 14f,
        minCell = 230f,
        start = start,
        clearAt = clearAt,
    )

    @Test
    fun `two tiles beside the list while it lasts, then three across the whole width`() {
        val grid = rows(12)
        // 688 beside the list fits two of 337; 952 fits three of 308.
        assertEquals(337f, grid.cell(0))
        assertEquals(308f, grid.cell(3))
        // Rows start at 300, 651 and 1002 beside the list, which ends (with its gap) at 1100.
        assertEquals(listOf(0..1, 2..3, 4..5, 6..8, 9..11), (0 until grid.rows).map { grid.indices(it) })
        assertEquals(listOf(300f, 651f, 1002f, 1353f, 1675f), (0 until grid.rows).map { grid.top(it) })
        assertEquals(1983f, grid.bottom)
    }

    @Test
    fun `beside the list a row starts in the right column, under it at the page's edge`() {
        val grid = rows(12)
        assertEquals(264f, grid.left(0, 0))
        assertEquals(615f, grid.left(0, 1))
        assertEquals(0f, grid.left(3, 6))
        assertEquals(322f, grid.left(3, 7))
        assertEquals(644f, grid.left(3, 8))
    }

    @Test
    fun `a few photos stay beside the list, the last row short`() {
        val grid = rows(3)
        assertEquals(listOf(0..1, 2..2), (0 until grid.rows).map { grid.indices(it) })
        assertEquals(264f, grid.left(1, 2))
    }

    @Test
    fun `with no list beside them, or one shorter than the filters, every row is whole`() {
        listOf(rows(7, clearAt = 0f), rows(7, start = 500f, clearAt = 400f)).forEach { grid ->
            assertEquals(listOf(0..2, 3..5, 6..6), (0 until grid.rows).map { grid.indices(it) })
            assertEquals(0f, grid.left(0, 0))
        }
    }

    @Test
    fun `a point on the page names the row that starts at or above it`() {
        val grid = rows(12)
        assertEquals(0, grid.rowAt(-5000f))
        assertEquals(0, grid.rowAt(640f))
        assertEquals(1, grid.rowAt(700f))
        assertEquals(2, grid.rowAt(1352f))
        assertEquals(3, grid.rowAt(1400f))
        assertEquals(4, grid.rowAt(99_999f))
        assertEquals(0, rows(0).rowAt(500f))
    }
}
