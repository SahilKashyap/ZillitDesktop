package com.zillit.desktop.feature.calls

import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.calls.ui.gridLayout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grid's geometry at the sizes a real window produces.
 *
 * 1248 × 616 is the stage body inside a 1280 × 800 window; 956 wide is the
 * same body with the roster panel open. These are the two shapes anyone will
 * actually see, and a layout that overflows at twelve people is invisible
 * until twelve people are on a call.
 */
class CallGridTest {

    private val bodyWidth = 1248.dp
    private val bodyHeight = 616.dp
    private val withRoster = 956.dp
    private val gap = 8.dp

    @Test
    fun `tiles stay 16 by 9 at every count`() {
        listOf(1, 2, 3, 5, 9, 12).forEach { count ->
            val layout = gridLayout(count, bodyWidth, bodyHeight)
            val ratio = layout.tileWidth.value / layout.tileHeight.value
            assertTrue(abs(ratio - 16f / 9f) < 0.01f, "count $count had ratio $ratio")
        }
    }

    @Test
    fun `the grid never overflows the body it was given`() {
        listOf(1, 2, 3, 5, 9, 12).forEach { count ->
            val layout = gridLayout(count, bodyWidth, bodyHeight)
            val usedWidth = layout.tileWidth * layout.columns + gap * (layout.columns - 1)
            val usedHeight = layout.tileHeight * layout.rows + gap * (layout.rows - 1)
            assertTrue(usedWidth.value <= bodyWidth.value + 0.5f, "count $count overflowed width")
            assertTrue(usedHeight.value <= bodyHeight.value + 0.5f, "count $count overflowed height")
        }
    }

    @Test
    fun `it still fits with the roster panel open`() {
        listOf(2, 5, 12).forEach { count ->
            val layout = gridLayout(count, withRoster, bodyHeight)
            val usedWidth = layout.tileWidth * layout.columns + gap * (layout.columns - 1)
            assertTrue(usedWidth.value <= withRoster.value + 0.5f, "count $count overflowed")
        }
    }

    @Test
    fun `a one-to-one call is two deliberate tiles, not two stretched slabs`() {
        // Without the cap a 1248 dp body gives each of two tiles 620 dp, and a
        // 1:1 call — the common case — reads as a stretched accident.
        val layout = gridLayout(2, bodyWidth, bodyHeight)
        assertEquals(2, layout.columns)
        assertEquals(1, layout.rows)
        assertTrue(layout.tileWidth.value <= 560f)
    }

    @Test
    fun `rows fill before they wrap`() {
        assertEquals(1 to 1, gridLayout(1, bodyWidth, bodyHeight).let { it.columns to it.rows })
        assertEquals(2 to 1, gridLayout(2, bodyWidth, bodyHeight).let { it.columns to it.rows })
        assertEquals(2 to 2, gridLayout(4, bodyWidth, bodyHeight).let { it.columns to it.rows })
        assertEquals(3 to 2, gridLayout(5, bodyWidth, bodyHeight).let { it.columns to it.rows })
        assertEquals(3 to 3, gridLayout(9, bodyWidth, bodyHeight).let { it.columns to it.rows })
        assertEquals(4 to 3, gridLayout(12, bodyWidth, bodyHeight).let { it.columns to it.rows })
    }

    @Test
    fun `the avatar shrinks with the tile but never past legibility`() {
        val two = gridLayout(2, bodyWidth, bodyHeight)
        val twelve = gridLayout(12, bodyWidth, bodyHeight)
        assertTrue(twelve.avatar.value < two.avatar.value)
        assertTrue(twelve.avatar.value >= 40f, "12-up avatar was ${twelve.avatar}")
        assertTrue(two.avatar.value <= 96f)
    }

    @Test
    fun `a cramped body still produces a positive tile`() {
        // The pill's thumb is this small, and a zero-sized tile would divide
        // by zero on the way to the avatar.
        val layout = gridLayout(1, 96.dp, 56.dp)
        assertTrue(layout.tileWidth.value > 0f)
        assertTrue(layout.avatar.value > 0f)
    }
}
