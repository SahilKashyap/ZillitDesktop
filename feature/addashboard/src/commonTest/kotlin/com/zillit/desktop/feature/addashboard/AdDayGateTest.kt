package com.zillit.desktop.feature.addashboard

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.addashboard.domain.AdDayStatus
import com.zillit.desktop.feature.addashboard.domain.AdRepository
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdViewModel
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
import kotlin.test.assertNull
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

    private class FakeRepo(private val dayStatus: AdDayStatus = AdDayStatus.InProgress) : AdRepository {
        val edits = mutableListOf<String>()
        val submitted = mutableListOf<Long>()
        val added = mutableListOf<Triple<List<String>, Long, String?>>()
        val blocked = mutableListOf<Pair<String, String?>>()
        var artisteRows = listOf(Artiste(id = "a1", name = "Ada"))
        var dayRows = listOf(SupportingArtistDay(id = "s1", artisteId = "a1", artisteName = "Ada"))
        var daysAsked = mutableListOf<Long>()

        override suspend fun artistes() = ZillitResult.Success(artisteRows)
        override suspend fun verify(artisteId: String): ZillitResult<Unit> {
            edits += "verify:$artisteId"
            return ZillitResult.Success(Unit)
        }
        override suspend fun block(artisteId: String, reason: String?): ZillitResult<Unit> {
            blocked += artisteId to reason
            return ZillitResult.Success(Unit)
        }
        override suspend fun unblock(artisteId: String) = ZillitResult.Success(Unit)
        override suspend fun deleteArtiste(artisteId: String) = ZillitResult.Success(Unit)
        override suspend fun today(shootDate: Long): ZillitResult<AdShootDay> {
            daysAsked += shootDate
            return ZillitResult.Success(AdShootDay(id = "d1", shootDate = shootDate, status = dayStatus))
        }
        override suspend fun shootDays() = ZillitResult.Success(emptyList<AdShootDay>())
        override suspend fun dayList(shootDate: Long) = ZillitResult.Success(dayRows)
        override suspend fun addToDay(
            artisteIds: List<String>,
            shootDate: Long,
            callTime: String?,
        ): ZillitResult<Unit> {
            added += Triple(artisteIds, shootDate, callTime)
            return ZillitResult.Success(Unit)
        }
        override suspend fun updateDayEntry(
            id: String,
            callTime: String?,
            wrapTime: String?,
            attendance: AttendanceStatus?,
        ): ZillitResult<Unit> {
            edits += "update:$id:${attendance?.wire ?: callTime ?: wrapTime}"
            return ZillitResult.Success(Unit)
        }
        override suspend fun removeFromDay(id: String): ZillitResult<Unit> {
            edits += "remove:$id"
            return ZillitResult.Success(Unit)
        }
        override suspend fun submitDay(shootDate: Long): ZillitResult<Unit> {
            submitted += shootDate
            return ZillitResult.Success(Unit)
        }
    }

    private fun model(repo: FakeRepo, canPost: Boolean = true) = AdViewModel(
        repository = repo,
        viewer = { AdViewer(userId = "u1", canPost = canPost, ready = true) },
        now = { midMorning },
    ).also { it.start() }

    // -- the date --------------------------------------------------------------

    /**
     * The dashboard opens on the UTC midnight containing "now", never on the
     * raw moment: the service keys days by midnight and a mid-morning stamp
     * would ask for a day that does not exist.
     */
    @Test
    fun `it opens on today's UTC midnight`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        assertEquals(thatMidnight, vm.state.value.shootDate)
        assertEquals(listOf(thatMidnight), repo.daysAsked)
    }

    @Test
    fun `stepping a day keeps the date flat`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.ChangeDay(vm.state.value.shootDate - 1))
        runCurrent()

        assertEquals(0L, vm.state.value.shootDate % 86_400_000L)
        assertEquals(thatMidnight - 86_400_000L, vm.state.value.shootDate)
    }

    // -- the two gates ---------------------------------------------------------

    @Test
    fun `an AD with posting rights may mark attendance on an open day`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.SetAttendance("s1", AttendanceStatus.Present))
        runCurrent()

        assertEquals(listOf("update:s1:present"), repo.edits)
    }

    @Test
    fun `a viewer without posting rights changes nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo, canPost = false)
        runCurrent()

        vm.onEvent(AdEvent.SetAttendance("s1", AttendanceStatus.Present))
        vm.onEvent(AdEvent.RemoveFromDay("s1"))
        runCurrent()

        assertTrue(repo.edits.isEmpty())
        assertFalse(vm.state.value.canEditDay)
    }

    /** Nobody may change a submitted day — not even the AD who sent it. */
    @Test
    fun `a submitted day refuses every edit`() = runTest(dispatcher) {
        val repo = FakeRepo(dayStatus = AdDayStatus.Submitted)
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.SetAttendance("s1", AttendanceStatus.Present))
        vm.onEvent(AdEvent.SetCallTime("s1", "07:00"))
        vm.onEvent(AdEvent.RemoveFromDay("s1"))
        runCurrent()

        assertTrue(repo.edits.isEmpty())
        assertFalse(vm.state.value.canEditDay)
    }

    @Test
    fun `a published day is as locked as a submitted one`() = runTest(dispatcher) {
        val repo = FakeRepo(dayStatus = AdDayStatus.Published)
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.SetAttendance("s1", AttendanceStatus.Present))
        runCurrent()

        assertTrue(repo.edits.isEmpty())
    }

    @Test
    fun `a wrapped day is still open for corrections`() = runTest(dispatcher) {
        val repo = FakeRepo(dayStatus = AdDayStatus.Wrapped)
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.SetAttendance("s1", AttendanceStatus.Present))
        runCurrent()

        assertEquals(1, repo.edits.size)
    }

    // -- submitting ------------------------------------------------------------

    /** Submitting locks the day, so it is confirmed rather than done on a click. */
    @Test
    fun `submitting asks first`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.AskSubmitDay)
        runCurrent()

        assertTrue(vm.state.value.confirmSubmit)
        assertTrue(repo.submitted.isEmpty(), "nothing sent until it is confirmed")
    }

    @Test
    fun `confirming submits the open day`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.AskSubmitDay)
        vm.onEvent(AdEvent.ConfirmSubmitDay)
        runCurrent()

        assertEquals(listOf(thatMidnight), repo.submitted)
        assertFalse(vm.state.value.confirmSubmit)
        assertEquals("Day submitted", vm.state.value.notice)
    }

    @Test
    fun `changing one's mind sends nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.AskSubmitDay)
        vm.onEvent(AdEvent.CancelSubmitDay)
        runCurrent()

        assertTrue(repo.submitted.isEmpty())
        assertFalse(vm.state.value.confirmSubmit)
    }

    // -- adding to the day -----------------------------------------------------

    @Test
    fun `adding sends the chosen artistes against the open day`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.dayRows = emptyList()
        repo.artisteRows = listOf(Artiste(id = "a1", name = "Ada"), Artiste(id = "a2", name = "Ravi"))
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.OpenAddToDay)
        vm.onEvent(AdEvent.ToggleArtiste("a1"))
        vm.onEvent(AdEvent.ToggleArtiste("a2"))
        vm.onEvent(AdEvent.AddCallTime("07:00"))
        vm.onEvent(AdEvent.ConfirmAddToDay)
        runCurrent()

        val (ids, date, callTime) = repo.added.single()
        assertEquals(setOf("a1", "a2"), ids.toSet())
        assertEquals(thatMidnight, date)
        assertEquals("07:00", callTime)
        assertNull(vm.state.value.addToDay)
    }

    @Test
    fun `adding nobody sends nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.OpenAddToDay)
        vm.onEvent(AdEvent.ConfirmAddToDay)
        runCurrent()

        assertTrue(repo.added.isEmpty())
    }

    /**
     * Blocking exists to keep somebody off the call, so a blocked artiste is
     * never offered — and neither is anyone already on the day.
     */
    @Test
    fun `the add list leaves out the blocked and the already-booked`() = runTest(dispatcher) {
        val repo = FakeRepo()
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

    // -- the register ----------------------------------------------------------

    @Test
    fun `blocking records the reason when one is given`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.StartBlock(Artiste(id = "a1", name = "Ada")))
        vm.onEvent(AdEvent.BlockReason("Did not turn up twice"))
        vm.onEvent(AdEvent.ConfirmBlock)
        runCurrent()

        assertEquals(1, repo.blocked.size)
        assertEquals("a1", repo.blocked.single().first)
        assertEquals("Did not turn up twice", repo.blocked.single().second)
    }

    /** An empty reason is sent as none, so no empty explanation is recorded. */
    @Test
    fun `blocking with no reason records none`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        runCurrent()

        vm.onEvent(AdEvent.StartBlock(Artiste(id = "a1", name = "Ada")))
        vm.onEvent(AdEvent.ConfirmBlock)
        runCurrent()

        assertEquals(1, repo.blocked.size)
        assertEquals("a1", repo.blocked.single().first)
        assertNull(repo.blocked.single().second, "an empty reason is sent as none")
    }

    @Test
    fun `a reader cannot verify or block`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo, canPost = false)
        runCurrent()

        vm.onEvent(AdEvent.Verify("a1"))
        vm.onEvent(AdEvent.StartBlock(Artiste(id = "a1", name = "Ada")))
        vm.onEvent(AdEvent.ConfirmBlock)
        runCurrent()

        assertTrue(repo.edits.isEmpty())
        assertTrue(repo.blocked.isEmpty())
    }

    @Test
    fun `a blocked viewer loads nothing at all`() = runTest(dispatcher) {
        val repo = FakeRepo()
        AdViewModel(
            repository = repo,
            viewer = { AdViewer(userId = "u1", canView = false, ready = true) },
            now = { midMorning },
        ).start()
        runCurrent()

        assertTrue(repo.daysAsked.isEmpty())
    }
}
