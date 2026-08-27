package com.zillit.desktop.feature.pagedistribution

import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three tools that share one engine, pinned where they diverge.
 *
 * D.O.D is the awkward one: it rides the *schedule* service and segment, so
 * anything derived from the service alone treats it as a schedule — right
 * for the page-number key, wrong for `schedule_type`, which it must never
 * send. These tests hold that split still.
 */
class DistributionToolTest {

    private val schedule = DistributionTool.ScheduleDistribution
    private val script = DistributionTool.ScriptDistribution
    private val dod = DistributionTool.ScheduleDod

    @Test
    fun `the page-number key follows the service, not the tool`() {
        assertEquals("schedule_page_number", schedule.pageNumberKey)
        assertEquals("script_page_number", script.pageNumberKey)
        assertEquals("schedule_page_number", dod.pageNumberKey, "dod rides the schedule service")
    }

    @Test
    fun `only the schedule tool proper sends schedule_type`() {
        assertTrue(schedule.sendsScheduleType)
        assertFalse(script.sendsScheduleType, "the script service does not read it")
        assertFalse(dod.sendsScheduleType, "nor does dod, despite sharing the service")
    }

    @Test
    fun `dod shares the schedule segment but keeps its own storage path`() {
        assertEquals(schedule.segment, dod.segment, "same REST segment")
        assertEquals(ZillitService.ScheduleDistribution, dod.service)
        assertFalse(dod.storagePath == schedule.storagePath, "but its own S3 prefix")
        assertEquals("/film-tools/dod", dod.storagePath)
    }

    /**
     * The publish destination is a folder name matched by string across
     * Android, iOS and web. Script's is singular — "Script & Page
     * Distribution" — while its title is plural. That is not a typo:
     * correcting it here would stop this client's uploads landing in the
     * same folder as the phones'.
     */
    @Test
    fun `the script publish root stays singular where the title is plural`() {
        assertEquals("Script & Pages Distribution", script.title)
        assertEquals("Script & Page Distribution", script.publishRoot)
    }

    @Test
    fun `each tool publishes into its own root`() {
        val roots = listOf(schedule, script, dod).map { it.publishRoot }

        assertEquals(roots.size, roots.toSet().size, "no two tools share a destination folder")
        assertEquals("Schedule Full & One Line", schedule.publishRoot)
        assertEquals("Schedule D.O.D", dod.publishRoot)
    }

    @Test
    fun `the permission identifiers are the ones the grid spells`() {
        assertEquals("schedule_distribution_tool", schedule.toolIdentifier)
        assertEquals("script_distribution_tool", script.toolIdentifier)
        assertEquals("dod_tool", dod.toolIdentifier, "not schedule_dod_tool")
    }

    @Test
    fun `the schedule tool carries three tabs and the others fewer`() {
        assertEquals(listOf("full_script", "page", "oneline"), schedule.tabs.map { it.key })
        assertEquals(listOf("full_script", "page"), script.tabs.map { it.key })
        assertEquals(listOf("dod"), dod.tabs.map { it.key })
    }

    /** Only the schedule tool's pages tab offers the full/one-line choice. */
    @Test
    fun `the schedule type choice is offered on one tab alone`() {
        val offering = listOf(schedule, script, dod)
            .flatMap { tool -> tool.tabs.map { tool to it } }
            .filter { (_, tab) -> (tab.kind as? TabKind.Folders)?.scheduleTypeChoice == true }

        assertEquals(1, offering.size, "exactly one tab asks which schedule this is")
        assertEquals("page", offering.single().second.key)
        assertEquals(schedule.toolIdentifier, offering.single().first.toolIdentifier)
    }

    @Test
    fun `count types are distinct within a tool`() {
        for (tool in listOf(schedule, script, dod)) {
            val types = tool.tabs.map { it.kind.countType }
            assertEquals(types.size, types.toSet().size, "${tool.title} reuses a count type")
        }
    }

    /** Folders are keyed by scene for pages and by name for D.O.D. */
    @Test
    fun `folder keys differ between pages and dod`() {
        val pageFolder = script.tabs.first { it.key == "page" }.kind as TabKind.Folders
        val dodFolder = dod.tabs.single().kind as TabKind.Folders

        assertFalse(pageFolder.folderKey == dodFolder.folderKey, "scene number vs name")
    }

    @Test
    fun `single tabs replace through a parent pointer`() {
        val single = schedule.tabs.first { it.key == "full_script" }.kind as TabKind.Single

        assertEquals("parent_schedule_id", single.parentKey)
        assertEquals("scheduleId", single.idQuery)
    }
}
