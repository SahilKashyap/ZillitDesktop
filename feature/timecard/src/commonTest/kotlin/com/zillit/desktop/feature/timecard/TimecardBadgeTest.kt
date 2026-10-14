package com.zillit.desktop.feature.timecard

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.timecard.domain.AllowanceType
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardBadges
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import com.zillit.desktop.feature.timecard.domain.TimecardHistoryEntry
import com.zillit.desktop.feature.timecard.domain.TimecardMetadata
import com.zillit.desktop.feature.timecard.domain.TimecardRepository
import com.zillit.desktop.feature.timecard.domain.TimecardStatus
import com.zillit.desktop.feature.timecard.domain.TimecardUnread
import com.zillit.desktop.feature.timecard.domain.TimecardUnreadRow
import com.zillit.desktop.feature.timecard.domain.TimecardViewer
import com.zillit.desktop.feature.timecard.ui.TimecardConfirmAction
import com.zillit.desktop.feature.timecard.ui.TimecardDestination
import com.zillit.desktop.feature.timecard.ui.TimecardEvent
import com.zillit.desktop.feature.timecard.ui.TimecardPrompt
import com.zillit.desktop.feature.timecard.ui.TimecardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The timecard tool's dots and reads, against iOS `TimecardBadgeViewModel` and
 * its callers: a dot per week and per record, a read per week or record —
 * never a tab read whole as a page comes on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimecardBadgeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a week's dot is its timecard, its week's bucket and its review rows, each row once`() {
        val unread = TimecardUnread(
            listOf(
                row(MY, week = MONDAY_KEY, record = "tc-1"),
                row(MY, record = "tc-1"),
                row(MY, week = MONDAY_KEY, record = "other"),
                row(INBOX, record = "tc-1"),
                row(MY, week = NEXT_KEY, record = "tc-2"),
                row(TimecardBadges.ASSIGNED_TIMECARDS, week = MONDAY_KEY, record = "slot-1"),
            ),
        )
        assertEquals(4, unread.myWeek(week("tc-1", MONDAY)))
        assertEquals(1, unread.myWeek(week("tc-2", MONDAY + WEEK)))
        assertEquals(mapOf(MY to 4, INBOX to 1, TimecardBadges.ASSIGNED_TIMECARDS to 1), unread.byUnit)
        assertEquals(2, unread.week(MY, MONDAY_KEY))
        assertEquals(2, unread.record(MY, "tc-1"))
    }

    @Test
    fun `opening a week reads that week and its timecard, and leaves the next week lit`() = runTest(dispatcher) {
        val badges = Badges(
            listOf(
                row(MY, week = MONDAY_KEY, record = "tc-1"),
                row(MY, record = "tc-1"),
                row(INBOX, record = "tc-1"),
                row(MY, week = NEXT_KEY, record = "tc-2"),
            ),
        )
        val vm = open(Weeks(mine = listOf(week("tc-1", MONDAY), week("tc-2", MONDAY + WEEK))), badges)
        assertTrue(badges.reads.isEmpty(), "every row is on the list, so entering reads none: ${badges.reads}")
        assertEquals(3, vm.state.value.rowUnread(week("tc-1", MONDAY)))
        assertEquals(1, vm.state.value.rowUnread(week("tc-2", MONDAY + WEEK)))
        assertEquals(4, vm.state.value.unread[MY]?.plus(vm.state.value.unread[INBOX] ?: 0))

        vm.onEvent(TimecardEvent.Select("tc-1"))
        runCurrent()
        assertEquals(listOf("week:$MY:$MONDAY_KEY", "record:$MY:tc-1:null", "record:$INBOX:tc-1:null"), badges.reads)
    }

    @Test
    fun `entering a page reads only what its list cannot show, nothing newer than it`() = runTest(dispatcher) {
        val badges = Badges(
            listOf(
                row(MY, week = MONDAY_KEY, record = "tc-1"),
                row(TimecardBadges.ASSIGNED_TIMECARDS, week = MONDAY_KEY, record = "slot-1"),
                row(MY, week = TimecardBadges.DISPUTE_BUCKET, record = "dispute-1"),
                row(TimecardBadges.ASSIGNED_TIMECARDS, week = NEXT_KEY, record = "slot-2", created = NOW),
            ),
        )
        open(Weeks(mine = listOf(week("tc-1", MONDAY))), badges)
        assertEquals(
            listOf(
                "record:${TimecardBadges.ASSIGNED_TIMECARDS}:slot-1:$MONDAY_KEY",
                "record:$MY:dispute-1:${TimecardBadges.DISPUTE_BUCKET}",
            ),
            badges.reads,
            "the slot written while the list was in flight waits for the next entry",
        )
    }

    @Test
    fun `the approval queue reads a week once it is decided, never on opening it`() = runTest(dispatcher) {
        val badges = Badges(listOf(row(QUEUE, record = "tc-1"), row(QUEUE, record = "tc-2")))
        val repository = Weeks(queue = listOf(week("tc-1", MONDAY), week("tc-2", MONDAY)), approver = true)
        val vm = open(repository, badges)
        vm.onEvent(TimecardEvent.Open(TimecardDestination.ApprovalQueue))
        runCurrent()
        assertEquals(1, vm.state.value.rowUnread(week("tc-1", MONDAY)))

        vm.onEvent(TimecardEvent.Select("tc-1"))
        runCurrent()
        assertTrue(badges.reads.isEmpty(), "viewing is not reading (TCApprovalQueuePage.swift:786-789)")

        vm.onEvent(TimecardEvent.Ask(TimecardPrompt.Confirm(TimecardConfirmAction.Approve, "tc-1", "", "")))
        vm.onEvent(TimecardEvent.ConfirmPrompt)
        runCurrent()
        assertEquals(listOf("tc-1"), repository.approved)
        assertEquals(listOf("record:$QUEUE:tc-1:null"), badges.reads, "only the decided week")
    }

    // -- harness ------------------------------------------------------------------------------

    private fun TestScope.open(repository: Weeks, badges: Badges): TimecardViewModel =
        TimecardViewModel(
            repository,
            { TimecardViewer("u", "department_camera", null) },
            { MONDAY },
            offline = null,
            nowMillis = { NOW },
            badges = badges,
        ).also {
            it.start()
            runCurrent()
        }

    private class Badges(rows: List<TimecardUnreadRow>) : TimecardBadges {
        override val rows = MutableStateFlow(rows)
        val reads = mutableListOf<String>()

        override fun readRecord(unit: String, recordId: String, week: String?) {
            reads += "record:$unit:$recordId:$week"
        }

        override fun readWeek(unit: String, week: String) {
            reads += "week:$unit:$week"
        }
    }

    @Suppress("TooManyFunctions") // One override per repository operation.
    private class Weeks(
        private val mine: List<Timecard> = emptyList(),
        private val queue: List<Timecard> = emptyList(),
        private val approver: Boolean = false,
    ) : TimecardRepository {
        val approved = mutableListOf<String>()

        override val refreshes: Flow<Unit> = emptyFlow()
        override suspend fun myTimecards(): ZillitResult<List<Timecard>> = ZillitResult.Success(mine)
        override suspend fun approvalQueue(): ZillitResult<List<Timecard>> = ZillitResult.Success(queue)
        override suspend fun payrollProcessing(weekStarting: Long): ZillitResult<List<Timecard>> =
            ZillitResult.Success(emptyList())
        override suspend fun outstanding(): ZillitResult<List<Timecard>> = ZillitResult.Success(emptyList())
        override suspend fun metadata(): ZillitResult<TimecardMetadata> =
            ZillitResult.Success(TimecardMetadata(isApprover = approver))
        override suspend fun allowanceTypes(): ZillitResult<List<AllowanceType>> = ZillitResult.Success(emptyList())
        override suspend fun timecard(id: String): ZillitResult<Timecard> = unsupported()
        override suspend fun history(id: String): ZillitResult<List<TimecardHistoryEntry>> =
            ZillitResult.Success(emptyList())
        override suspend fun save(draft: TimecardDraft): ZillitResult<String?> = unsupported()
        override suspend fun addNote(id: String, note: String): ZillitResult<Unit> = unsupported()
        override suspend fun submit(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun approve(id: String, note: String?): ZillitResult<Unit> {
            approved += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun reject(id: String, reason: String): ZillitResult<Unit> = unsupported()
        override suspend fun query(id: String, note: String): ZillitResult<Unit> = unsupported()
        override suspend fun finalApprove(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun lock(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun markPaid(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun addDeduction(
            id: String,
            label: String,
            amount: Double,
            nominalCode: String?,
        ): ZillitResult<Unit> = unsupported()
        override suspend fun removeDeduction(id: String, deductionId: String): ZillitResult<Unit> = unsupported()
        override suspend fun approveAll(ids: List<String>): ZillitResult<Unit> = unsupported()
        override suspend fun lockAll(ids: List<String>): ZillitResult<Unit> = unsupported()

        private fun <T> unsupported(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("not in this test"))
    }

    private fun row(unit: String, week: String = "", record: String = "", created: Long = 0L) =
        TimecardUnreadRow(unit = unit, level1 = week, level3 = record, created = created)

    private fun week(id: String, monday: Long) = Timecard(
        id = id, userId = "u", crewName = "Ada", departmentId = null, designation = null,
        weekStarting = monday, weekNumber = 3, status = TimecardStatus.Submitted, currency = "GBP",
        notes = null, queryNote = null, rejectionReason = null,
        lastApprovedBy = null, paidAt = null, updatedAt = null,
    )

    private companion object {
        /** Monday 18 May 2026, midnight UTC. */
        const val MONDAY = 1_779_062_400_000L
        const val WEEK = 7 * 86_400_000L
        const val NOW = MONDAY + 3 * 86_400_000L
        val MONDAY_KEY = MONDAY.toString()
        val NEXT_KEY = (MONDAY + WEEK).toString()
        const val MY = TimecardBadges.MY_TIMECARDS
        const val INBOX = TimecardBadges.RECEIVED_INBOX
        const val QUEUE = TimecardBadges.APPROVAL_QUEUE
    }
}
