package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.ui.ADMIN_SETTINGS_PATH
import com.zillit.desktop.feature.settings.ui.EntryStatus
import com.zillit.desktop.feature.settings.ui.ProductionFacts
import com.zillit.desktop.feature.settings.ui.SettingsDestination
import com.zillit.desktop.feature.settings.ui.adminSettingsEntries
import com.zillit.desktop.feature.settings.ui.path
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import com.zillit.desktop.feature.settings.ui.path as queuePath
import com.zillit.desktop.feature.settings.ui.anyPlanned
import com.zillit.desktop.feature.settings.ui.matching
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The listing and the pages behind it, checked against each other.
 *
 * The failure this guards against is not a crash — it is a row that opens
 * nothing, or a page a production should not have that a stale deep link can
 * still reach. Both are invisible in review and obvious to a coordinator.
 */
class AdminDestinationTest {

    private val film = ProductionFacts(name = "Feature One")
    private val corporate = ProductionFacts(name = "Conference", isOtherType = true)
    private val personal = ProductionFacts(name = "Mine", isPersonal = true)

    @Test
    fun `every openable admin row leads to a page`() {
        val unrouted = adminSettingsEntries(film)
            .flatMap { it.entries }
            .filter { it.status == EntryStatus.Ready }
            .filterNot { it.destination in HANDLED_ELSEWHERE }
            .filter { AdminDestination.of(it.destination) == null }

        assertTrue(
            unrouted.isEmpty(),
            "these rows are clickable but open nothing: ${unrouted.map { it.title }}",
        )
    }

    @Test
    fun `every page has a row that reaches it`() {
        val offered = adminSettingsEntries(film)
            .flatMap { it.entries }
            .mapNotNull { AdminDestination.of(it.destination) }
            .toSet()

        val unreachable = AdminDestination.entries - offered
        assertTrue(unreachable.isEmpty(), "no row opens these pages: $unreachable")
    }

    /**
     * Routes are derived from slugs, so a duplicate slug is two pages on one
     * path — and the second one silently never renders.
     */
    @Test
    fun `slugs are unique`() {
        val slugs = AdminDestination.entries.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size, "duplicate slug in $slugs")
    }

    /** A page whose path is a queue's path would shadow the queue. */
    @Test
    fun `page paths do not collide with the approval queues`() {
        val queues = ApprovalQueue.entries.map { it.queuePath }.toSet()
        AdminDestination.entries.forEach { page ->
            assertTrue(page.path !in queues, "${page.name} shadows an approval queue")
            assertTrue(page.path.startsWith("$ADMIN_SETTINGS_PATH/"), "${page.name} is off the admin tree")
        }
    }

    /**
     * A corporate production shoots nothing, but it still has a dashboard.
     *
     * The distinction only became visible by opening the pages: `home/unit` is
     * the dashboard's own sections — bulletin, calendar, call sheet — and has
     * nothing to do with second units. See [UnitKind].
     */
    @Test
    fun `a corporate production has no shooting units but keeps its dashboard`() {
        listOf(AdminDestination.ShootingUnits, AdminDestination.RemoteUnits).forEach { page ->
            assertFalse(page.availableTo(corporate), "${page.name} should be absent on a corporate project")
        }
        assertTrue(
            AdminDestination.HomeUnits.availableTo(corporate),
            "every project has a dashboard, whatever it is shooting",
        )

        val offered = adminSettingsEntries(corporate).flatMap { it.entries }.map { it.destination }
        // The listing enum's names predate the correction; JoinedUnits is the
        // shooting-units row and ShootingUnits is the dashboard one.
        assertTrue(SettingsDestination.JoinedUnits !in offered)
        assertTrue(SettingsDestination.RemoteUnit !in offered)
        assertTrue(SettingsDestination.ShootingUnits in offered)
    }

    /** It still has departments, tools and a name — only the units go. */
    @Test
    fun `a corporate production keeps everything that is not a unit`() {
        assertTrue(AdminDestination.Departments.availableTo(corporate))
        assertTrue(AdminDestination.ToolGroups.availableTo(corporate))
        assertTrue(AdminDestination.CompanyDetails.availableTo(corporate))
    }

    @Test
    fun `a personal production has no crew pages`() {
        listOf(
            AdminDestination.Crew,
            AdminDestination.CrewOrder,
            AdminDestination.Rights,
            AdminDestination.PreApproved,
            AdminDestination.Sos,
        ).forEach { page ->
            assertTrue(!page.availableTo(personal), "${page.name} should be absent on a personal project")
        }

        val offered = adminSettingsEntries(personal).flatMap { it.entries }.map { it.destination }
        assertTrue(SettingsDestination.CrewAndAdmins !in offered)
        assertTrue(SettingsDestination.PermissionGrid !in offered)
    }

    /**
     * The listing filters on the same rule the page guard uses.
     *
     * Their disagreeing is exactly the web's bug: its hub hides the remote-unit
     * tile on corporate productions and the route behind it still opens.
     */
    @Test
    fun `the listing offers nothing the production does not have`() {
        listOf(film, corporate, personal).forEach { production ->
            adminSettingsEntries(production)
                .flatMap { it.entries }
                .mapNotNull { AdminDestination.of(it.destination) }
                .forEach { page ->
                    assertTrue(
                        page.availableTo(production),
                        "${page.name} is listed on $production but its page refuses to render",
                    )
                }
        }
    }

    /**
     * No row is planned any more.
     *
     * Deal-memo onboarding was the last one, and Android deleted it from Admin
     * Settings on 2026-09-02. The notice explaining the tag must not appear
     * over a page that has nothing tagged.
     */
    @Test
    fun `no admin row is planned, so the Soon notice never shows`() {
        listOf(film, corporate, personal).forEach { production ->
            assertFalse(
                adminSettingsEntries(production).anyPlanned,
                "a planned row appeared on $production",
            )
        }
    }

    @Test
    fun `slugs round-trip`() {
        AdminDestination.entries.forEach { page ->
            assertEquals(page, AdminDestination.fromSlug(page.slug))
        }
        assertNull(AdminDestination.fromSlug("not-a-page"))
    }

    /** The label switches with the production type; the page does not. */
    @Test
    fun `the crew list row is called a staff list on a corporate production`() {
        val row = adminSettingsEntries(corporate)
            .flatMap { it.entries }
            .firstOrNull { it.destination == SettingsDestination.CrewListOrder }

        assertNotNull(row)
        assertEquals("Change Department Listing Order for Staff List", row.title)
    }

    private companion object {
        /**
         * Rows that are not administration pages.
         *
         * The approval queues have their own screens, the documentation links
         * open a browser, and Production Setup is a page of the Account Hub.
         */
        val HANDLED_ELSEWHERE = setOf(
            SettingsDestination.ApproveNewCrew,
            SettingsDestination.ApproveProfileChanges,
            SettingsDestination.Help,
            SettingsDestination.SetupNotes,
            SettingsDestination.ProductionSetup,
        )
    }
}
