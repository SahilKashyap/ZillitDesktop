package com.zillit.desktop.feature.timecard

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.timecard.data.TimecardSyncEnvelope
import com.zillit.desktop.feature.timecard.domain.AllowanceType
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import com.zillit.desktop.feature.timecard.domain.TimecardHistoryEntry
import com.zillit.desktop.feature.timecard.domain.TimecardMetadata
import com.zillit.desktop.feature.timecard.domain.TimecardRepository
import com.zillit.desktop.feature.timecard.domain.TimecardViewer
import com.zillit.desktop.feature.timecard.ui.TimecardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The socket's `timecard:*` announcements land as one debounced reload of the
 * page on screen — the web's `ah:timecard:*` refetch pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimecardSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Monday 18 May 2026, midnight UTC. */
    private val monday = 1_779_062_400_000L

    private val crew = TimecardViewer("u", "department_camera", null)

    @Test
    fun `the envelope keeps only the project id and gates on it`() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString(
            TimecardSyncEnvelope.serializer(),
            """{"project_id":"p1","user_id":"u2","data":{"_id":"tc-4","status":"approved"}}""",
        )
        assertEquals("p1", envelope.projectId)
        assertTrue(envelope.inProject("p1"))
        assertFalse(envelope.inProject("p2"), "another production's frame must drop")
        assertTrue(TimecardSyncEnvelope().inProject("p1"), "an unnamed frame passes rather than starving the screen")
    }

    @Test
    fun `a burst of timecard events is one reload of the open page`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repository = FakeWeeks(events)
        val model = TimecardViewModel(repository, { crew }, { monday }, offline = null)
        model.start()
        runCurrent()
        assertEquals(1, repository.listLoads, "start loads the crew member's own weeks once")

        repeat(3) { events.emit(Unit) }
        runCurrent()
        assertEquals(1, repository.listLoads, "nothing reloads until the debounce window closes")
        advanceTimeBy(TimecardViewModel.SYNC_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(2, repository.listLoads, "three frames collapse into one reload")
    }

    @Suppress("TooManyFunctions") // One override per repository operation.
    private class FakeWeeks(
        override val refreshes: Flow<Unit>,
    ) : TimecardRepository {
        var listLoads = 0

        override suspend fun myTimecards(): ZillitResult<List<Timecard>> = counted()
        override suspend fun approvalQueue(): ZillitResult<List<Timecard>> = counted()
        override suspend fun payrollProcessing(weekStarting: Long): ZillitResult<List<Timecard>> = counted()
        override suspend fun outstanding(): ZillitResult<List<Timecard>> = counted()

        private fun counted(): ZillitResult<List<Timecard>> {
            listLoads++
            return ZillitResult.Success(emptyList())
        }

        override suspend fun metadata(): ZillitResult<TimecardMetadata> = ZillitResult.Success(TimecardMetadata())
        override suspend fun allowanceTypes(): ZillitResult<List<AllowanceType>> = ZillitResult.Success(emptyList())
        override suspend fun timecard(id: String): ZillitResult<Timecard> = unsupported()
        override suspend fun history(id: String): ZillitResult<List<TimecardHistoryEntry>> =
            ZillitResult.Success(emptyList())
        override suspend fun save(draft: TimecardDraft): ZillitResult<String?> = unsupported()
        override suspend fun addNote(id: String, note: String): ZillitResult<Unit> = unsupported()
        override suspend fun submit(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun approve(id: String, note: String?): ZillitResult<Unit> = unsupported()
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
}
