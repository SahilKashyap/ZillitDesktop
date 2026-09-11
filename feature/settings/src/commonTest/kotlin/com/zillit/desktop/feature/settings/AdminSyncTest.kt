package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.settings.admin.data.ADMIN_SYNC_EVENTS
import com.zillit.desktop.feature.settings.admin.data.ADMIN_SYNC_PAGES
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which administration page each membership event reloads.
 *
 * `CrewListSync` documents that these events "feed the header, badges, and
 * admin-settings pages" rather than the crew list. Nothing on the desktop
 * listened for them at all (audited 2026-09-07), so an admin watching the
 * crew page did not see somebody accepted or removed by a second coordinator.
 *
 * The mapping is the substance: reloading the department tree because
 * somebody's rights moved would spin four pages for one event, and a wrong
 * wire name is silence rather than an error.
 */
class AdminSyncTest {

    private fun pagesFor(event: String) = ADMIN_SYNC_PAGES[SocketEventName(event)].orEmpty()

    @Test
    fun `the department tree reloads for every department change`() {
        // Three pages read the one list, so all three go stale together.
        val tree = setOf(
            AdminDestination.Departments,
            AdminDestination.JobTitles,
            AdminDestination.CrewOrder,
        )
        listOf("department:create", "department:update", "department:delete", "department:reordered")
            .forEach { assertEquals(tree, pagesFor(it), it) }
    }

    @Test
    fun `who is on the production reloads the crew page and the rights grid`() {
        // The grid is indexed by person: a departure leaves a row pointing at
        // nobody, which is worse than a stale name.
        listOf(
            "project:user:accepted", "project:user:removed", "project:user:left",
            "project:user:admin:access", "project:user:profile:update",
            "project:user:profile:created", "project:user:reordered",
            "project:pre-approved:user:joined",
        ).forEach {
            assertEquals(setOf(AdminDestination.Crew, AdminDestination.Rights), pagesFor(it), it)
        }
    }

    @Test
    fun `the queues reload where they are shown`() {
        listOf(
            "project:user:join:request:received",
            "project:user:join:request:accepted",
            "project:user:join:request:rejected",
            "project:user:profile:change:requested",
            "project:user:profile:change:accepted",
            "project:user:profile:change:rejected",
        ).forEach {
            assertEquals(setOf(AdminDestination.PreApproved, AdminDestination.Crew), pagesFor(it), it)
        }
    }

    @Test
    fun `a department change never spins the crew grid, and vice versa`() {
        // The whole point of mapping to pages rather than reloading everything.
        assertTrue(AdminDestination.Crew !in pagesFor("department:create"))
        assertTrue(AdminDestination.Departments !in pagesFor("project:user:removed"))
    }

    @Test
    fun `every mapped event names at least one page`() {
        // A name with no page is a subscription that costs a frame and does
        // nothing — the failure mode this map exists to prevent.
        ADMIN_SYNC_EVENTS.forEach { assertTrue(ADMIN_SYNC_PAGES[it].orEmpty().isNotEmpty(), it.value) }
        assertEquals(ADMIN_SYNC_EVENTS.size, ADMIN_SYNC_EVENTS.distinct().size, "no duplicates")
    }
}
