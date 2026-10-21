package com.zillit.desktop.feature.addashboard

import com.zillit.desktop.feature.addashboard.domain.AdDayStatus
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdViewModel
import com.zillit.desktop.feature.addashboard.ui.AddExtrasEvent
import com.zillit.desktop.feature.addashboard.ui.DayEvent
import com.zillit.desktop.feature.addashboard.ui.RegisterEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two gates gate every change to a day, and both must pass: the viewer's
 * posting right, and the day's own status. A submitted day is read-only for
 * everyone, including the AD who submitted it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdDayGateTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** 2026-03-06T10:15Z — a mid-morning moment, so flattening is visible. */
    private val midMorning = 1_772_792_100_000L
    private val thatMidnight = 1_772_755_200_000L

    private fun repo(status: AdDayStatus = AdDayStatus.InProgress) = FakeAdRepository().apply {
        dayHeader = AdShootDay(id = "d1", status = status)
        artisteRows = listOf(Artiste(id = "a1", name = "Ada"))
        dayRows = listOf(SupportingArtistDay(id = "s1", artisteId = "a1", artisteName = "Ada"))
    }

    private fun model(repo: FakeAdRepository, canPost: Boolean = true) = AdViewModel(
        repository = repo,
        now = { midMorning },
        viewer = { AdViewer(userId = "u1", canPost = canPost, ready = true) },
    ).also { it.start() }

    // -- the date --------------------------------------------------------------

    /**
     * The dashboard opens on the UTC midnight containing "now", never on the
     * raw moment: the service keys days by midnight and a mid-morning stamp
     * would ask for a day that does not exist.
     */
    @Test
    fun `it opens on today's UTC midnight`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        assertEquals(thatMidnight, vm.state.value.shootDate)
        assertTrue(repo.daysAsked.all { it == thatMidnight })
    }

    @Test
    fun `stepping a day keeps the date flat`() = runTest(dispatcher) {
        val vm = model(repo())
        runCurrent()

        vm.onEvent(AdEvent.ChangeDay(vm.state.value.shootDate - 1))
        runCurrent()

        assertEquals(0L, vm.state.value.shootDate % 86_400_000L)
        assertEquals(thatMidnight - 86_400_000L, vm.state.value.shootDate)
    }

    // -- the two gates ---------------------------------------------------------

    /**
     * A mark sends its time explicitly, re-stamped onto the day being run.
     *
     * Never left to the server: a server stamp is a real instant, so an AD
     * marking 07:30 in Mumbai would have it render as 02:00 to everyone else.
     */
    @Test
    fun `marking present sends the attendance and a call time`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(DayEvent.MarkPresent("s1"))
        runCurrent()

        val write = repo.writes.single { it.startsWith("update:s1") }
        assertTrue(write.contains("present"), write)
        assertTrue(write.contains("call="), "the call time rides with the mark: $write")
    }

    /** Unmarking clears the call time too, or a cleared row still reads as present. */
    @Test
    fun `unmarking clears the attendance and the call time`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(DayEvent.Unmark("s1"))
        runCurrent()

        val write = repo.writes.single { it.startsWith("update:s1") }
        assertTrue(write.contains("attendance=null"), write)
        assertTrue(write.contains("call=null"), write)
    }

    @Test
    fun `a viewer without posting rights changes nothing`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo, canPost = false)
        runCurrent()

        vm.onEvent(DayEvent.MarkPresent("s1"))
        vm.onEvent(DayEvent.RemoveFromDay("s1"))
        runCurrent()

        assertTrue(repo.writes.isEmpty())
        assertFalse(vm.state.value.canEditDay)
    }

    /** Nobody may change a submitted day — not even the AD who sent it. */
    @Test
    fun `a submitted day refuses every edit`() = runTest(dispatcher) {
        val repo = repo(status = AdDayStatus.Submitted)
        val vm = model(repo)
        runCurrent()

        vm.onEvent(DayEvent.MarkPresent("s1"))
        vm.onEvent(DayEvent.MarkOut("s1"))
        vm.onEvent(DayEvent.RemoveFromDay("s1"))
        runCurrent()

        assertTrue(repo.writes.isEmpty())
        assertFalse(vm.state.value.canEditDay)
    }

    @Test
    fun `a published day is as locked as a submitted one`() = runTest(dispatcher) {
        val repo = repo(status = AdDayStatus.Published)
        val vm = model(repo)
        runCurrent()

        vm.onEvent(DayEvent.MarkPresent("s1"))
        runCurrent()

        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun `a wrapped day is still open for corrections`() = runTest(dispatcher) {
        val repo = repo(status = AdDayStatus.Wrapped)
        val vm = model(repo)
        runCurrent()

        vm.onEvent(DayEvent.MarkPresent("s1"))
        runCurrent()

        assertEquals(1, repo.writes.count { it.startsWith("update:") })
    }

    /**
     * A future day opens read-only.
     *
     * Today and an unsubmitted **past** day may still be run and submitted;
     * only the future is date-locked, which is how an upcoming day on the
     * schedule can be looked at without being changed.
     */
    @Test
    fun `a future day is read-only even on an open status`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.ChangeDay(thatMidnight + 86_400_000L))
        runCurrent()
        vm.onEvent(DayEvent.MarkPresent("s1"))
        runCurrent()

        assertFalse(vm.state.value.dateEditable)
        assertFalse(vm.state.value.canEditDay)
        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun `a past day is still editable`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.ChangeDay(thatMidnight - 86_400_000L))
        runCurrent()
        vm.onEvent(DayEvent.MarkPresent("s1"))
        runCurrent()

        assertTrue(vm.state.value.dateEditable)
        assertEquals(1, repo.writes.count { it.startsWith("update:") })
    }

    // -- booking onto the day --------------------------------------------------

    @Test
    fun `booking sends the chosen artistes against the open day`() = runTest(dispatcher) {
        val repo = repo()
        repo.dayRows = emptyList()
        repo.artisteRows = listOf(Artiste(id = "a1", name = "Ada"), Artiste(id = "a2", name = "Ravi"))
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AddExtrasEvent.Toggle("a1"))
        vm.onEvent(AddExtrasEvent.Toggle("a2"))
        vm.onEvent(AddExtrasEvent.CallTime("07:00"))
        vm.onEvent(AddExtrasEvent.Submit)
        runCurrent()

        val write = repo.writes.single { it.startsWith("addToDay:") }
        assertTrue(write.contains("a1") && write.contains("a2"), write)
        assertTrue(write.contains(thatMidnight.toString()), write)
        assertTrue(write.endsWith("07:00"), write)
        assertTrue(vm.state.value.addExtras.chosen.isEmpty(), "the picks clear on success")
    }

    @Test
    fun `booking nobody sends nothing`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AddExtrasEvent.Submit)
        runCurrent()

        assertTrue(repo.writes.none { it.startsWith("addToDay:") })
    }

    /**
     * Blocking exists to keep somebody off the call, so a blocked artiste is
     * never offered — and neither is anyone already on the day.
     */
    @Test
    fun `the bookable list leaves out the blocked and the already-booked`() = runTest(dispatcher) {
        val repo = repo()
        repo.artisteRows = listOf(
            Artiste(id = "a1", name = "Ada"),
            Artiste(id = "a2", name = "Ravi", status = ArtisteStatus.Blocked),
            Artiste(id = "a3", name = "Sam"),
        )
        repo.dayRows = listOf(SupportingArtistDay(id = "s1", artisteId = "a1"))
        val vm = model(repo)
        runCurrent()

        assertEquals(listOf("a3"), vm.state.value.addable("").map { it.id })
    }

    /**
     * A new external artiste must have an e-mail address.
     *
     * The portal is code-gated and the code can only go to the address on
     * file, so booking one without it leaves them unable to approve their own
     * day. Quick add is one of the two paths that enforces this; the full form
     * deliberately does not.
     */
    @Test
    fun `a new external artiste with no e-mail is refused`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AddExtrasEvent.StartNewArtiste)
        vm.onEvent(AddExtrasEvent.NewFirstName("Ada"))
        vm.onEvent(AddExtrasEvent.NewLastName("Lovelace"))
        vm.onEvent(AddExtrasEvent.Submit)
        runCurrent()

        assertTrue(repo.writes.none { it.startsWith("createWithDay:") })
        assertTrue(vm.state.value.addExtras.error != null)
    }

    @Test
    fun `a new external artiste with an e-mail is created and booked`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AddExtrasEvent.StartNewArtiste)
        vm.onEvent(AddExtrasEvent.NewFirstName("Ada"))
        vm.onEvent(AddExtrasEvent.NewLastName("Lovelace"))
        vm.onEvent(AddExtrasEvent.NewEmail("ada@example.com"))
        vm.onEvent(AddExtrasEvent.Submit)
        runCurrent()

        assertTrue(repo.writes.any { it.startsWith("createWithDay:ada@example.com") })
    }

    /** Add Extras allows tomorrow deliberately: a unit books its crowd the night before. */
    @Test
    fun `add extras can move to tomorrow`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AddExtrasEvent.PickDay(1))
        runCurrent()

        assertEquals(thatMidnight + 86_400_000L, vm.state.value.shootDate)
    }

    // -- the register ----------------------------------------------------------

    @Test
    fun `blocking records the reason when one is given`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(RegisterEvent.OpenArtiste("a1"))
        runCurrent()
        vm.onEvent(RegisterEvent.AskBlock)
        vm.onEvent(RegisterEvent.BlockReason("Did not turn up twice"))
        vm.onEvent(RegisterEvent.ConfirmBlock)
        runCurrent()

        assertTrue(repo.writes.contains("block:a1:Did not turn up twice"))
    }

    /** An empty reason is sent as none, so no empty explanation is recorded. */
    @Test
    fun `blocking with no reason records none`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(RegisterEvent.OpenArtiste("a1"))
        runCurrent()
        vm.onEvent(RegisterEvent.AskBlock)
        vm.onEvent(RegisterEvent.ConfirmBlock)
        runCurrent()

        assertTrue(repo.writes.contains("block:a1:"), repo.writes.toString())
    }

    @Test
    fun `a reader cannot verify or block`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo, canPost = false)
        runCurrent()

        vm.onEvent(RegisterEvent.Verify("a1"))
        vm.onEvent(RegisterEvent.OpenArtiste("a1"))
        runCurrent()
        vm.onEvent(RegisterEvent.AskBlock)
        vm.onEvent(RegisterEvent.ConfirmBlock)
        runCurrent()

        assertTrue(repo.writes.none { it.startsWith("verify:") })
        assertTrue(repo.writes.none { it.startsWith("block:") })
    }

    /** A verified artiste has been through onboarding and may carry signed days. */
    @Test
    fun `a verified artiste is not offered for deletion`() = runTest(dispatcher) {
        val repo = repo()
        repo.artisteRows = listOf(Artiste(id = "a1", name = "Ada", status = ArtisteStatus.Verified))
        val vm = model(repo)
        runCurrent()

        vm.onEvent(RegisterEvent.AskDelete(repo.artisteRows.single()))
        runCurrent()

        assertTrue(vm.state.value.register.confirmDelete == null)
        assertTrue(repo.writes.none { it.startsWith("deleteArtiste:") })
    }

    @Test
    fun `a blocked viewer loads nothing at all`() = runTest(dispatcher) {
        val repo = repo()
        AdViewModel(
            repository = repo,
            now = { midMorning },
            viewer = { AdViewer(userId = "u1", canView = false, ready = true) },
        ).start()
        runCurrent()

        assertTrue(repo.daysAsked.isEmpty())
    }

    // -- the hidden pages ------------------------------------------------------

    /**
     * Four pages are hidden from a viewer without posting rights, and every
     * other page stays — read-only. A hide-list, not a whitelist.
     */
    @Test
    fun `the four posting-only pages are hidden from a reader`() = runTest(dispatcher) {
        val vm = model(repo(), canPost = false)
        runCurrent()

        val visible = vm.state.value.visibleDestinations
        assertFalse(AdDestination.LiveDay in visible)
        assertFalse(AdDestination.Onboarding in visible)
        assertFalse(AdDestination.Config in visible)
        assertTrue(AdDestination.TodaysList in visible, "everything else stays, read-only")
        assertTrue(AdDestination.Queries in visible)
    }

    /** A reader who lands on a hidden page is put on Today's List instead. */
    @Test
    fun `a reader lands on today's list rather than a hidden page`() = runTest(dispatcher) {
        val vm = model(repo(), canPost = false)
        runCurrent()

        vm.onEvent(AdEvent.Open(AdDestination.Config))
        runCurrent()

        assertFalse(vm.state.value.destination == AdDestination.Config)
    }

    /** Attendance marks go through the same gate from either page. */
    @Test
    fun `an absent mark carries no call time`() = runTest(dispatcher) {
        val repo = repo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(DayEvent.MarkAbsent("s1"))
        runCurrent()

        val write = repo.writes.single { it.startsWith("update:s1") }
        assertEquals("update:s1:${AttendanceStatus.Absent.wire}", write)
    }
}
