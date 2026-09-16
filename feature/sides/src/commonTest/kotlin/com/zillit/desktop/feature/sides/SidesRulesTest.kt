package com.zillit.desktop.feature.sides

import com.zillit.desktop.feature.sides.domain.ManualPlan
import com.zillit.desktop.feature.sides.domain.SceneInfo
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.domain.VersionScenes
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules the web's forms encode inline. */
class SidesRulesTest {

    @Test
    fun `scene name strips the number the chip already shows`() {
        assertEquals("INT. KITCHEN - DAY", SidesRules.sceneName("12 INT. KITCHEN - DAY", "12"))
        assertEquals("INT. KITCHEN", SidesRules.sceneName("12A. INT. KITCHEN", "12a"))
        assertEquals("EXT. LOT", SidesRules.sceneName("EXT. LOT", "3"))
        assertEquals("12", SidesRules.sceneName("12", "12"), "a heading that is only the number stays")
        assertEquals("Scene 7", SidesRules.sceneTip(SceneInfo("7")))
        assertEquals("7  EXT. LOT", SidesRules.sceneTip(SceneInfo("7", "7 EXT. LOT")))
    }

    @Test
    fun `order sync keeps the custom order, appends new picks and drops deselected`() {
        assertEquals(listOf("3", "1", "4"), SidesRules.syncOrder(listOf("3", "1", "2"), listOf("1", "3", "4")))
        assertEquals(emptyList(), SidesRules.syncOrder(listOf("1"), emptyList()))
    }

    @Test
    fun `a custom order regroups picks by the first version that picked each scene`() {
        val plan = ManualPlan(
            scriptId = "s",
            versionScenes = listOf(VersionScenes("v1", listOf("1", "2")), VersionScenes("v2", listOf("2", "3"))),
            sceneOrder = listOf("3", "2", "1", "9"),
        )
        val groups = plan.wireVersionScenes
        assertEquals(listOf("v2", "v1"), groups.map { it.versionId })
        assertEquals(listOf("3"), groups[0].sceneNumbers)
        assertEquals(listOf("2", "1"), groups[1].sceneNumbers, "scene 2 belongs to v1, which picked it first")
        assertEquals(
            plan.versionScenes,
            plan.copy(sceneOrder = listOf("nope")).wireVersionScenes,
            "an unknown order keeps the picks",
        )
    }

    @Test
    fun `bytes and names format as the web does`() {
        assertNull(SidesRules.formatBytes(0))
        assertEquals("512 B", SidesRules.formatBytes(512))
        assertEquals("2 KB", SidesRules.formatBytes(2048))
        assertEquals("1.5 MB", SidesRules.formatBytes(1_572_864))
        assertEquals("Day_5_sides.pdf", SidesRules.downloadName("Day 5 sides"))
        assertEquals("sides.pdf", SidesRules.downloadName(""))
        assertEquals("Ep 1", SidesRules.titleFromFileName("Ep 1.FDX"))
        assertEquals("fdx", SidesRules.contentSubtype("a.FDX"))
        assertTrue(SidesRules.isPdfOrFdx("x.fdx") && SidesRules.isPdf("x.PDF") && !SidesRules.isPdf("x.fdx"))
        assertTrue(SidesRules.isPdfBytes("%PDF-1.7".encodeToByteArray()))
        assertFalse(SidesRules.isPdfBytes("<?xml".encodeToByteArray()))
    }

    @Test
    fun `the autogenerate disabled reason follows the web's ladder`() {
        assertTrue(SidesRules.autoDisabledReason(false, false, 0, false).startsWith("Select a call sheet"))
        assertTrue(SidesRules.autoDisabledReason(true, true, 0, false).startsWith("Loading"))
        assertTrue(SidesRules.autoDisabledReason(true, false, 0, true).startsWith("Add at least one"))
        assertTrue(SidesRules.autoDisabledReason(true, false, 0, false).startsWith("This call sheet has no"))
        assertEquals("", SidesRules.autoDisabledReason(true, false, 3, false))
    }

    @Test
    fun `dates print in the web's format`() {
        val utc = TimeZone.UTC
        assertEquals("Sep 15, 2026 · 3:05 PM", SidesRules.formatDateTime("2026-09-15T15:05:00Z", utc))
        assertEquals("Jan 2, 2026 · 12:00 AM", SidesRules.formatDateTime("2026-01-02T00:00:00Z", utc))
        assertEquals("Sep 15, 2026", SidesRules.formatDate("2026-09-15T15:05:00Z", utc))
        assertEquals("—", SidesRules.formatDate("not a date"))
    }
}
