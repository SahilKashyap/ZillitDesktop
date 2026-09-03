package com.zillit.desktop.core.badges

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lookups every badge surface leans on.
 *
 * The folding of wire rows into these maps lives with the source (see
 * `tallyRows` in `feature:home`); what belongs here is the contract the rail,
 * tabs, tiles and dock read against.
 */
class BadgeAggregationTest {

    @Test
    fun `tools and units answer their own counts`() {
        val counts = BadgeCounts(
            bySection = mapOf("cnc_label" to 4),
            byTool = mapOf("callsheet_tool" to 2),
            byUnit = mapOf("unit-a" to 3),
        )

        assertEquals(4, counts.section("cnc_label"))
        assertEquals(2, counts["callsheet_tool"])
        assertEquals(3, counts.unit("unit-a"))
    }

    @Test
    fun `unknown keys read as zero rather than throwing`() {
        assertEquals(0, BadgeCounts.Empty["anything"])
        assertEquals(0, BadgeCounts.Empty.section("nowhere_label"))
        assertEquals(0, BadgeCounts.Empty.unit("unit-never-seen"))
    }

    @Test
    fun `the dock total sums sections alone`() {
        // Tools and units are the same unread counted at finer grain —
        // adding the maps would badge the dock two or three times over.
        val counts = BadgeCounts(
            bySection = mapOf("home_label" to 5, "cnc_label" to 1),
            byTool = mapOf("location_tool" to 5),
            byUnit = mapOf("unit-a" to 3, "unit-b" to 2),
        )

        assertEquals(6, counts.total)
    }

    @Test
    fun `emptiness follows the tool map`() {
        assertTrue(BadgeCounts.Empty.isEmpty)
        assertTrue(!BadgeCounts(bySection = mapOf("cnc_label" to 1)).isEmpty)
        assertTrue(!BadgeCounts(byTool = mapOf("location_tool" to 1)).isEmpty)
    }

    @Test
    fun `toString does not dump every tool`() {
        // It reaches log lines on every socket update.
        val counts = BadgeCounts(byTool = mapOf("confidential_info_tool" to 3))

        assertTrue(!counts.toString().contains("confidential_info_tool"))
    }

    /** The store is a StateFlow; only value equality lets it skip a no-op emission. */
    @Test
    fun `counts are equal by value`() {
        val a = BadgeCounts(bySection = mapOf("cnc_label" to 1), byTool = mapOf("email_tool" to 1))
        val b = BadgeCounts(bySection = mapOf("cnc_label" to 1), byTool = mapOf("email_tool" to 1))
        val c = BadgeCounts(bySection = mapOf("cnc_label" to 2), byTool = mapOf("email_tool" to 1))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

}
