package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.CsyncBadges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which page shows and clears which unread (the web's `badges.test.js`). */
class BadgesTest {

    @Test
    fun `uploads badge the pages that show them`() {
        // Script / schedule on Scene Breakdown — under either of its addresses.
        assertEquals(CsyncBadges.SCENE_BREAKDOWN, CsyncBadges.unitForPath("breakdown"))
        assertEquals(CsyncBadges.SCENE_BREAKDOWN, CsyncBadges.unitForPath("/breakdown"))
        assertEquals(CsyncBadges.SCENE_BREAKDOWN, CsyncBadges.unitForPath("scenes"))
        // The call sheet on Continuity ▸ On set.
        assertEquals(CsyncBadges.CONTINUITY, CsyncBadges.unitForPath("continuity"))
        // A budget sheet imported — on the Budget tab, finance only.
        assertEquals(CsyncBadges.BUDGET, CsyncBadges.unitForPath("budget"))
    }

    @Test
    fun `the continuity book never clears the call sheet`() {
        assertNull(CsyncBadges.unitForPath("continuity/book"))
        assertNull(CsyncBadges.unitForPath("/continuity/book"))
    }

    @Test
    fun `a record's own page keeps its list's unit, and an unknown page has none`() {
        assertEquals(CsyncBadges.CLEANING, CsyncBadges.unitForPath("cleaning/123"))
        assertEquals(CsyncBadges.SCENE_BREAKDOWN, CsyncBadges.unitForPath("breakdown/scene/9"))
        assertNull(CsyncBadges.unitForPath("gallery"))
        assertNull(CsyncBadges.unitForPath(""))
    }

    @Test
    fun `every unit but general has a page`() {
        val pages = CsyncBadges.UNIT_BY_PAGE.values.toSet()
        listOf(
            CsyncBadges.CLEANING, CsyncBadges.ALTERATION, CsyncBadges.DAMAGE, CsyncBadges.MISSING,
            CsyncBadges.FITTING, CsyncBadges.RENTAL, CsyncBadges.READINESS,
            CsyncBadges.SCENE_BREAKDOWN, CsyncBadges.CONTINUITY, CsyncBadges.BUDGET,
        ).forEach { unit -> assertTrue(unit in pages, "$unit has no page") }
        // Record chat and "production created" are read by the record and by
        // the module opening, never by a page.
        assertTrue(CsyncBadges.GENERAL !in pages)
    }

    @Test
    fun `a row is the tool's by any of its tool spellings or by its unit`() {
        // iOS counts by either key (CSSBadges.swift:33-37): a row filed under
        // another spelling still lands on a tab, so a tab can clear it.
        listOf("costume_set_sync_tool_label", "costume_set_sync_tool", "costume_set_sync_label").forEach { tool ->
            assertTrue(CsyncBadges.owns(tool, ""), tool)
        }
        assertTrue(CsyncBadges.owns("some_other_label", CsyncBadges.CONTINUITY))
        assertFalse(CsyncBadges.owns("location_tool_label", "location_folder_label"))
    }
}
