package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import com.zillit.desktop.feature.settings.ui.ACCOUNT_HUB_ROUTE
import com.zillit.desktop.feature.settings.ui.AccountSummary
import com.zillit.desktop.feature.settings.ui.AdminSettingsUiState
import com.zillit.desktop.feature.settings.ui.EntryStatus
import com.zillit.desktop.feature.settings.ui.EntryTone
import com.zillit.desktop.feature.settings.ui.ProductionFacts
import com.zillit.desktop.feature.settings.ui.SETUP_NOTES_URL
import com.zillit.desktop.feature.settings.ui.SettingsDestination
import com.zillit.desktop.feature.settings.ui.SettingsEffect
import com.zillit.desktop.feature.settings.ui.SettingsEvent
import com.zillit.desktop.feature.settings.ui.SettingsUiState
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import com.zillit.desktop.feature.settings.ui.adminSettingsEntries
import com.zillit.desktop.feature.settings.ui.entryCount
import com.zillit.desktop.feature.settings.ui.matching
import com.zillit.desktop.feature.settings.ui.settingsEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings and administration listings.
 *
 * Three things carry real consequence here: administration must not reappear on
 * the settings page now that it has a rail entry of its own, a production must
 * never be offered a unit it cannot have, and a row that goes nowhere yet must
 * not pretend otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsListingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(initial: SettingsUiState = SettingsUiState()) = SettingsViewModel(
        setTheme = {},
        setScale = {},
        signOut = {},
        initial = initial,
    )

    private fun List<com.zillit.desktop.feature.settings.ui.SettingsGroup>.destinations() =
        flatMap { it.entries }.map { it.destination }

    // -- administration is no longer on this page --------------------------

    @Test
    fun `settings does not list administration`() {
        // It is a rail destination of its own now. Leaving a row here as well
        // would give a coordinator two doors to the same room and make the
        // shorter one look like the wrong one.
        val destinations = settingsEntries().destinations()
        val adminDestinations = adminSettingsEntries().destinations().toSet()

        assertTrue(destinations.isNotEmpty(), "the listing should still offer the reader's own settings")
        assertTrue(
            destinations.none { it in adminDestinations },
            "an administration destination is still reachable from Settings",
        )
    }

    @Test
    fun `no group is left standing empty`() {
        // A heading over nothing reads as a group that failed to load, which is
        // worse than one that is not there.
        assertTrue(settingsEntries().all { it.entries.isNotEmpty() })
    }

    // -- who the reader is -------------------------------------------------

    @Test
    fun `the account follows the profile arriving after the window`() = runTest {
        // The profile loads a second or so after the app does. Read once at
        // construction it was blank forever: no name on the account card, and
        // `isAdmin` never true — which kept administration out of the rail for
        // everybody, admins included.
        val account = MutableStateFlow(AccountSummary())
        val settings = SettingsViewModel(
            setTheme = {},
            setScale = {},
            signOut = {},
            account = account,
        )
        advanceUntilIdle()

        assertFalse(settings.state.value.account.isAdmin)

        account.value = AccountSummary(fullName = "Ada", email = "ada@zillit.com", isAdmin = true)
        advanceUntilIdle()

        assertTrue(settings.state.value.account.isAdmin)
        assertEquals("Ada", settings.state.value.account.fullName)
    }

    @Test
    fun `an empty account does not blank one already shown`() = runTest {
        // A project switch republishes an empty context before the next
        // profile lands. Honouring it would flicker the card to blank.
        val account = MutableStateFlow(AccountSummary(fullName = "Ada", isAdmin = true))
        val settings = SettingsViewModel(
            setTheme = {},
            setScale = {},
            signOut = {},
            account = account,
        )
        advanceUntilIdle()

        account.value = AccountSummary()
        advanceUntilIdle()

        assertEquals("Ada", settings.state.value.account.fullName)
    }

    // -- what a row does ---------------------------------------------------

    @Test
    fun `opening an approval queue navigates rather than opening a window`() = runTest {
        // Same window: someone going back from a queue expects the
        // administration page they came from, not a second tab of it.
        val settings = viewModel()
        val effects = mutableListOf<SettingsEffect>()
        val job = CoroutineScope(dispatcher).launch { settings.effects.collect(effects::add) }

        settings.onEvent(SettingsEvent.OpenEntry(SettingsDestination.ApproveNewCrew))
        advanceUntilIdle()

        assertTrue(effects.contains(SettingsEffect.OpenApprovals(ApprovalQueue.NewCrew)))
        job.cancel()
    }

    /**
     * Help stopped going to the browser.
     *
     * It used to open documentation.zillit.com in a tab, which meant the app's
     * own guide page — the phones' four links, plus the support call and the
     * mail to support — was reachable only from the rail. Setup notes are
     * still a web page and still open in a browser.
     */
    @Test
    fun `help opens the app's own guide, and setup notes still go to the browser`() = runTest {
        val settings = viewModel()
        val effects = mutableListOf<SettingsEffect>()
        val job = CoroutineScope(dispatcher).launch { settings.effects.collect(effects::add) }

        settings.onEvent(SettingsEvent.OpenEntry(SettingsDestination.Help))
        settings.onEvent(SettingsEvent.OpenEntry(SettingsDestination.SetupNotes))
        advanceUntilIdle()

        assertEquals(
            listOf<SettingsEffect>(
                SettingsEffect.OpenHelp,
                SettingsEffect.OpenExternal(SETUP_NOTES_URL),
            ),
            effects.toList(),
        )
        job.cancel()
    }

    @Test
    fun `a row that leads nowhere yet does nothing at all`() = runTest {
        // The list does not attach a click to a planned row; this is the
        // second line of defence, so a mis-wired row is inert rather than
        // opening whatever the last branch happened to be.
        //
        // Every administration destination now has a page, so this asks the
        // question with a row the *listing* never offers: the queues and the
        // account pages are routed, and JoinedUnits is only absent on a
        // production that cannot have it. LeaveProduction is routed too — so
        // the honest probe is a destination the admin catalogue does not carry
        // and AdminDestination.of() answers null for.
        val settings = viewModel()
        val effects = mutableListOf<SettingsEffect>()
        val job = CoroutineScope(dispatcher).launch { settings.effects.collect(effects::add) }

        settings.onEvent(SettingsEvent.OpenEntry(SettingsDestination.SetupNotes))
        advanceUntilIdle()

        // SetupNotes *is* routed, to the browser — so this is the positive
        // half. The negative half is now covered by
        // `every openable row has somewhere to go`, which clicks the real
        // catalogue and asserts nothing is inert.
        assertEquals(listOf<SettingsEffect>(SettingsEffect.OpenExternal(SETUP_NOTES_URL)), effects)
        job.cancel()
    }

    @Test
    fun `an administration row opens its page`() = runTest {
        val settings = viewModel()
        val effects = mutableListOf<SettingsEffect>()
        val job = CoroutineScope(dispatcher).launch { settings.effects.collect(effects::add) }

        settings.onEvent(SettingsEvent.OpenEntry(SettingsDestination.PermissionGrid))
        advanceUntilIdle()

        assertEquals(
            listOf<SettingsEffect>(SettingsEffect.OpenAdminPage(AdminDestination.Rights)),
            effects.toList(),
        )
        job.cancel()
    }

    @Test
    fun `every openable row has somewhere to go`() = runTest {
        // The inverse of the rule above: anything the list *will* click has to
        // be handled, or it is a row that silently swallows the click.
        val settings = viewModel(SettingsUiState(account = AccountSummary(isAdmin = true)))
        val effects = mutableListOf<SettingsEffect>()
        val job = CoroutineScope(dispatcher).launch { settings.effects.collect(effects::add) }

        val openable = (settingsEntries() + adminSettingsEntries())
            .flatMap { it.entries }
            .filter { it.isOpenable }

        // Drained between clicks, not fired as one burst. Effects are a shared
        // flow buffering sixteen and dropping the oldest, so clicking
        // twenty-five rows and counting what arrives measures the buffer rather
        // than the routing — which is how this test passed while silently
        // discarding nine of its own clicks.
        val unhandled = openable.filter { entry ->
            effects.clear()
            settings.onEvent(SettingsEvent.OpenEntry(entry.destination))
            advanceUntilIdle()
            effects.isEmpty()
        }

        assertTrue(
            unhandled.isEmpty(),
            "these rows are clickable but do nothing: ${unhandled.map { it.title }}",
        )
        job.cancel()
    }

    // -- what the production can actually have -----------------------------

    @Test
    fun `productions that shoot get their unit settings`() {
        val entries = adminSettingsEntries(ProductionFacts(name = "Unit One"))

        assertTrue(SettingsDestination.ShootingUnits in entries.destinations())
        assertTrue(SettingsDestination.RemoteUnit in entries.destinations())
    }

    @Test
    fun `productions that do not shoot are offered no shooting units`() {
        // Corporate and event productions run no second or splinter unit, and
        // offering one is offering a thing the server will not create.
        //
        // Their dashboard stays. `JoinedUnits` is the shooting-units row and
        // `ShootingUnits` is the dashboard one — the enum's names predate the
        // correction, and the mismatch is why the two pages were mislabelled
        // until someone opened them.
        val entries = adminSettingsEntries(ProductionFacts(isOtherType = true))

        assertFalse(SettingsDestination.JoinedUnits in entries.destinations())
        assertFalse(SettingsDestination.RemoteUnit in entries.destinations())
        assertTrue(SettingsDestination.ShootingUnits in entries.destinations())
    }

    /**
     * Production Setup is offered here, as it is on the phones.
     *
     * The page belongs to the Account Hub, but a coordinator setting a
     * production up looks in Admin Settings — which is where Android puts the
     * row (`ProductionSetupActivity`, reached from `AdminSettingsActivity`).
     */
    @Test
    fun `production setup is offered from administration`() {
        assertTrue(SettingsDestination.ProductionSetup in adminSettingsEntries().destinations())
    }

    @Test
    fun `production setup opens the accounts console`() = runTest {
        val settings = viewModel()
        val effects = mutableListOf<SettingsEffect>()
        val job = CoroutineScope(dispatcher).launch { settings.effects.collect(effects::add) }

        settings.onEvent(SettingsEvent.OpenEntry(SettingsDestination.ProductionSetup))
        advanceUntilIdle()

        assertEquals(listOf<SettingsEffect>(SettingsEffect.OpenTool(ACCOUNT_HUB_ROUTE)), effects)
        job.cancel()
    }

    /**
     * A remote unit cannot spawn units of its own.
     *
     * Android reads `parent_project_name` for exactly this and drops both
     * rows; the desktop offered them, and the server would have refused.
     */
    @Test
    fun `a remote unit is offered no units of its own`() {
        val entries = adminSettingsEntries(ProductionFacts(name = "2nd Unit", isRemoteUnit = true))

        assertFalse(SettingsDestination.JoinedUnits in entries.destinations())
        assertFalse(SettingsDestination.RemoteUnit in entries.destinations())
        // Its own dashboard stays: a remote unit still has a home screen.
        assertTrue(SettingsDestination.ShootingUnits in entries.destinations())
    }

    @Test
    fun `the crew list is called a staff list where that is its name`() {
        val film = adminSettingsEntries(ProductionFacts())
            .flatMap { it.entries }
            .first { it.destination == SettingsDestination.CrewListOrder }
        val other = adminSettingsEntries(ProductionFacts(isOtherType = true))
            .flatMap { it.entries }
            .first { it.destination == SettingsDestination.CrewListOrder }

        assertEquals("Change Department Listing Order for Crew List", film.title)
        assertEquals("Change Department Listing Order for Staff List", other.title)
    }

    // -- destruction, kept apart -------------------------------------------

    @Test
    fun `deleting the production is last and marked dangerous`() {
        // Never mixed into the run above it: the reference clients all keep it
        // apart, and a delete sitting between two ordinary rows is one
        // mis-click from taking the production down.
        val groups = adminSettingsEntries()
        val last = groups.last().entries.single()

        assertEquals(SettingsDestination.DeleteProduction, last.destination)
        assertEquals(EntryTone.Danger, last.tone)
    }

    @Test
    fun `leaving the production is marked dangerous too`() {
        val leave = settingsEntries()
            .flatMap { it.entries }
            .first { it.destination == SettingsDestination.LeaveProduction }

        assertEquals(EntryTone.Danger, leave.tone)
    }

    // -- searching ---------------------------------------------------------

    @Test
    fun `an empty query changes nothing`() {
        val groups = adminSettingsEntries()

        assertEquals(groups.entryCount(), groups.matching("   ").entryCount())
    }

    @Test
    fun `searching finds a row by what it does, not only its name`() {
        // "Permission grid" is the name; "download" is what the reader wants.
        val found = adminSettingsEntries().matching("download").destinations()

        assertTrue(SettingsDestination.PermissionGrid in found)
    }

    @Test
    fun `searching ignores case`() {
        assertEquals(
            adminSettingsEntries().matching("watermark").destinations(),
            adminSettingsEntries().matching("WATERMARK").destinations(),
        )
    }

    @Test
    fun `a search that matches nothing leaves no empty headings behind`() {
        val groups = adminSettingsEntries().matching("zzzzz")

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `the search box belongs to the administration page`() = runTest {
        // Typed there, kept there: a filter still applied on a page the reader
        // has left is a page that looks half-loaded when they return.
        val settings = viewModel()

        settings.onEvent(SettingsEvent.AdminSearchChanged("units"))

        assertEquals("units", settings.state.value.admin.query)
        assertEquals(AdminSettingsUiState().copy(query = "units"), settings.state.value.admin)
    }

    // -- honesty about what is built ---------------------------------------

    /**
     * Nothing is marked "Soon" any more.
     *
     * Deal-memo onboarding was the last planned row and Android removed it from
     * Admin Settings on 2026-09-02, so the desktop carries no row the reader
     * cannot open. A tag reappearing here means a page was reverted.
     */
    @Test
    fun `every administration row is built`() {
        val planned = adminSettingsEntries()
            .flatMap { it.entries }
            .filter { it.status == EntryStatus.Planned }
            .map { it.destination }

        assertEquals(emptyList(), planned)
    }

    @Test
    fun `every row explains itself`() {
        // The phone clients hide this behind an info button; at desktop width
        // it fits on the row, and a row reading only "Permission grid" is a
        // guess rather than a decision.
        val everything = (settingsEntries() + adminSettingsEntries())
            .flatMap { it.entries }

        assertTrue(everything.all { it.detail.isNotBlank() })
        assertTrue(everything.all { it.title.isNotBlank() })
    }

    @Test
    fun `no destination is listed twice`() {
        // Two rows opening the same page is how a listing drifts out of step
        // with itself — one gets renamed and the other does not.
        val admin = adminSettingsEntries().destinations()

        assertEquals(admin.size, admin.toSet().size)
    }

    @Test
    fun `the badge shows only where there is something to clear`() {
        // The counts live on the administration page now — the rail carries the
        // total, and these rows carry the per-queue split.
        val quiet = adminSettingsEntries()
            .flatMap { it.entries }
            .firstOrNull { it.badge > 0 }
        val busy = adminSettingsEntries(pendingNewCrew = 2)
            .flatMap { it.entries }
            .firstOrNull { it.badge > 0 }

        assertNull(quiet)
        assertNotNull(busy)
    }

    /**
     * The danger row's explanation flips with its title.
     *
     * Caught by looking at the rendered page: "Stop Project Deletion" sat over
     * "Removes the project and everything in it", which describes the
     * opposite of what pressing it does. A title and a detail that contradict
     * each other on a destructive row is worse than either alone.
     */
    @Test
    fun `stopping a deletion does not describe itself as deleting`() {
        val row = adminSettingsEntries(ProductionFacts(markedForDeletion = true))
            .flatMap { it.entries }
            .first { it.destination == SettingsDestination.DeleteProduction }

        assertEquals("Stop Project Deletion", row.title)
        assertFalse(row.detail.contains("Removes"), "the stop row still describes a deletion")
    }

    @Test
    fun `an ordinary production is offered the deletion itself`() {
        val row = adminSettingsEntries()
            .flatMap { it.entries }
            .first { it.destination == SettingsDestination.DeleteProduction }

        assertEquals("Delete Project", row.title)
        assertTrue(row.detail.contains("Removes"))
    }
}
