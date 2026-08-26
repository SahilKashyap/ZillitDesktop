package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.data.DealSyncEnvelope
import com.zillit.desktop.feature.dealmemo.domain.Agreement
import com.zillit.desktop.feature.dealmemo.domain.BasicRateDetails
import com.zillit.desktop.feature.dealmemo.domain.Deal
import com.zillit.desktop.feature.dealmemo.domain.DealHistoryEntry
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRepository
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.DealViewer
import com.zillit.desktop.feature.dealmemo.domain.NewDeal
import com.zillit.desktop.feature.dealmemo.domain.RateCardEntry
import com.zillit.desktop.feature.dealmemo.domain.Union
import com.zillit.desktop.feature.dealmemo.ui.DealDestination
import com.zillit.desktop.feature.dealmemo.ui.DealEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
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
 * The socket's `deal:*` announcements land as one debounced reload of the page
 * on screen — the web's `ah:deal_memo:*` refetch pattern — and never touch the
 * rate card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealMemoSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the envelope keeps only the project id and gates on it`() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString(
            DealSyncEnvelope.serializer(),
            """{"project_id":"p1","user_id":"u9","data":{"deal_id":"d-3","status":"approved"}}""",
        )
        assertEquals("p1", envelope.projectId)
        assertTrue(envelope.inProject("p1"))
        assertFalse(envelope.inProject("p2"), "another production's frame must drop")
        assertTrue(DealSyncEnvelope().inProject("p1"), "an unnamed frame passes rather than starving the screen")
    }

    private val accountant = DealViewer(
        userId = "user-1",
        departmentIdentifier = "accounts_department_label",
        designationIdentifier = "production accountant",
    )

    @Test
    fun `a burst of deal events is one reload of the open page`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repository = FakeDeals(events)
        val model = DealMemoViewModel(repository) { accountant }
        model.start()
        runCurrent()
        assertEquals(1, repository.dealsLoads, "an accountant opens on All Deals")

        repeat(4) { events.emit(Unit) }
        runCurrent()
        assertEquals(1, repository.dealsLoads, "nothing reloads until the debounce window closes")
        advanceTimeBy(DealMemoViewModel.SYNC_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(2, repository.dealsLoads, "four frames collapse into one reload")
    }

    @Test
    fun `deal events leave the rate card alone`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repository = FakeDeals(events)
        val model = DealMemoViewModel(repository) { accountant }
        model.start()
        runCurrent()
        model.onEvent(DealEvent.Open(DealDestination.RateCard))
        runCurrent()
        assertEquals(1, repository.rateCardLoads)

        events.emit(Unit)
        advanceTimeBy(DealMemoViewModel.SYNC_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(1, repository.rateCardLoads, "no deal event changes the published scale")
        assertEquals(1, repository.dealsLoads, "and the hidden list is not fetched behind it")
    }

    private class FakeDeals(
        override val refreshes: Flow<Unit>,
    ) : DealMemoRepository {
        var dealsLoads = 0
        var rateCardLoads = 0

        override suspend fun deals(status: DealStatus?): ZillitResult<List<Deal>> {
            dealsLoads++
            return ZillitResult.Success(emptyList())
        }

        override suspend fun rateCard(
            unionId: String?,
            departmentIdentifier: String?,
            productionType: String?,
        ): ZillitResult<List<RateCardEntry>> {
            rateCardLoads++
            return ZillitResult.Success(emptyList())
        }

        override suspend fun myDeal(): ZillitResult<Deal?> = ZillitResult.Success(null)
        override suspend fun deal(id: String): ZillitResult<Deal> = unsupported()
        override suspend fun history(id: String): ZillitResult<List<DealHistoryEntry>> =
            ZillitResult.Success(emptyList())
        override suspend fun create(deal: NewDeal, notify: Boolean): ZillitResult<Unit> = unsupported()
        override suspend fun update(id: String, deal: NewDeal, notify: Boolean): ZillitResult<Unit> = unsupported()
        override suspend fun acknowledge(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun unions(): ZillitResult<List<Union>> = ZillitResult.Success(emptyList())
        override suspend fun agreements(unionId: String?): ZillitResult<List<Agreement>> =
            ZillitResult.Success(emptyList())
        override suspend fun resolveRate(
            departmentIdentifier: String,
            designationIdentifier: String,
            productionType: String,
            agreementId: String?,
            unionId: String?,
            budget: Double?,
        ): ZillitResult<RateCardEntry?> = ZillitResult.Success(null)
        override suspend fun basicRateDetails(agreementId: String): ZillitResult<BasicRateDetails?> =
            ZillitResult.Success(null)

        private fun <T> unsupported(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("not in this test"))
    }
}
