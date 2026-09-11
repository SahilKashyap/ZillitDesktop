package com.zillit.desktop.core.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A rights change has two spellings, and the desktop only watched one.
 *
 * The grid announces `access-grid:*-rights:update`; a right moved through a
 * tool's own page is announced under that tool. Every tool reads its gate
 * from the tool grid, so the app-wide watcher must hear both or a revoked
 * posting right keeps working until something else reloads the grid.
 */
class ToolRightsEventsTest {

    @Test
    fun `every ported tool that announces its own rights is watched`() {
        val names = ZillitSocketEvents.ToolRights.All.map { it.value }

        assertEquals(
            listOf(
                "home:posting-rights:update",
                "home:viewing-rights:update",
                "info:posting-rights:update",
                "confidential_info:posting-rights:update",
                "account:posting-rights:update",
                "account:viewing-rights:update",
                "script_notes:posting-rights:update",
                "continuity:posting-rights:update",
                "form_signature:posting-rights:update",
                "form_signature:viewing-rights:update",
                "box-schedule:posting-rights:update",
            ),
            names,
        )
    }

    /** The Pre & Production schedule is not ported, so its two stay out. */
    @Test
    fun `the unported schedule tool's rights are not watched`() {
        val names = ZillitSocketEvents.ToolRights.All.map { it.value }

        assertFalse("pre_production:posting-rights:update" in names)
        assertFalse("production:posting-rights:update" in names)
    }

    /** The two families are separate: neither list swallows the other. */
    @Test
    fun `the grid's own spelling stays in its own list`() {
        val tools = ZillitSocketEvents.ToolRights.All
        val grid = ZillitSocketEvents.AccessGrid.All

        assertTrue(tools.none { it in grid })
        assertTrue(grid.none { it.value.contains(':') && it in tools })
        assertTrue(grid.all { it.value.startsWith("access-grid:") })
    }
}
