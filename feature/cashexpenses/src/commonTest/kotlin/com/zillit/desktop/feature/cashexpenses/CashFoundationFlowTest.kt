package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.FloatDetails
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.MyCashOverview
import com.zillit.desktop.feature.cashexpenses.ui.CashBadges
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.TeamMemberDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seams the parity pages build on: the metadata re-pull that reseats the
 * viewer, the per-entity badge reads that replaced the page read, the float
 * detail, and the float order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashFoundationFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val accountant = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private val crew =
        CashViewer(userId = "u1", departmentIdentifier = "department_camera", designationIdentifier = null)

    private fun TestScope.viewModel(
        repository: FakeCash,
        viewer: CashViewer = accountant,
        badges: TabBadgeSource = TabBadgeSource.None,
        socket: FakeSocket? = null,
    ) = CashExpensesViewModel(
        repository = repository,
        viewer = { viewer },
        badges = badges,
        events = socket?.let(::SocketEventBus),
    ).also {
        it.start()
        advanceUntilIdle()
    }

    // -- metadata re-pull ---------------------------------------------------------------

    @Test
    fun `an assignment rule for this module re-pulls the viewer and bounces off a closed page`() =
        runTest(dispatcher) {
            val repository = FakeCash(writesSucceed = true).apply {
                metadata = CashMetadata(isCoordinator = true, codingRequired = true)
            }
            val socket = FakeSocket()
            val vm = viewModel(repository, viewer = crew, socket = socket)
            vm.onEvent(CashEvent.Open(CashDestination.CodingQueue))
            advanceUntilIdle()
            assertEquals(CashDestination.CodingQueue, vm.state.value.destination)
            val reads = repository.metadataReads

            // Another module's rule is not this one's.
            repository.metadata = CashMetadata()
            socket.send("assignment_rule:updated", module = "invoices")
            advanceUntilIdle()
            assertEquals(reads, repository.metadataReads)
            assertEquals(CashDestination.CodingQueue, vm.state.value.destination)

            socket.send("assignment_rule:updated", module = "cash_expenses")
            advanceUntilIdle()
            assertEquals(reads + 1, repository.metadataReads)
            assertEquals(false, vm.state.value.viewer.isCoordinator)
            assertTrue(CashDestination.CodingQueue !in vm.state.value.sharedDestinations)
            val state = vm.state.value
            assertEquals(CashDestination.landing(state.viewer, state.pipeline), state.destination)
        }

    @Test
    fun `an approval chain frame naming no module is every module's`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true)
        val socket = FakeSocket()
        val vm = viewModel(repository, socket = socket)
        val reads = repository.metadataReads

        repository.metadata = CashMetadata(isApprover = true)
        socket.send("approval_tier:updated", module = null)
        advanceUntilIdle()

        assertEquals(reads + 1, repository.metadataReads)
        assertTrue(vm.state.value.viewer.isApprover)
    }

    @Test
    fun `a settings section saved re-pulls the saver's own grants`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply { settingsDoc = CashSettings() }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.Settings))
        advanceUntilIdle()
        val reads = repository.metadataReads

        repository.metadata = CashMetadata(isTeamMember = true, canOverride = true)
        vm.onEvent(CashEvent.EditTeamMember(TeamMemberDraft(userId = "me", isSenior = true)))
        vm.onEvent(CashEvent.SaveTeamMember)
        advanceUntilIdle()

        assertEquals(reads + 1, repository.metadataReads)
        assertTrue(vm.state.value.viewer.metadata.canOverride)
        assertEquals(CashDestination.Settings, vm.state.value.destination, "a page that stays is not left")
    }

    // -- badges ---------------------------------------------------------------------------

    @Test
    fun `opening a page reads nothing, opening a batch reads its receipt rows`() = runTest(dispatcher) {
        val badges = RecordingBadges()
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost))
        }
        val vm = viewModel(repository, badges = badges)
        badges.counts.value = mapOf(CashBadges.PC_POST_LEDGER to 2)
        badges.entities.value = mapOf(CashBadges.PC_POST_LEDGER to mapOf("b1" to 2))

        vm.onEvent(CashEvent.Open(CashDestination.PostLedger))
        advanceUntilIdle()
        assertTrue(badges.tabReads.isEmpty(), "the web never reads a queue tab on arrival")
        assertEquals(2, vm.state.value.unreadFor(CashBadges.PC_POST_LEDGER, "b1"))
        assertEquals(2, vm.state.value.unreadOnPage("b1"))

        vm.onEvent(CashEvent.SelectBatch("b1"))
        advanceUntilIdle()
        assertEquals(listOf("pc_post_ledger|b1|cash_receipt"), badges.entityReads)
    }

    @Test
    fun `the top-ups tab is still read whole, as the web reads it on mount`() = runTest(dispatcher) {
        val badges = RecordingBadges()
        val vm = viewModel(FakeCash(writesSucceed = true), badges = badges)
        badges.counts.value = mapOf(CashBadges.PC_TOPUPS to 1)
        advanceUntilIdle()

        vm.onEvent(CashEvent.Open(CashDestination.TopUps))
        advanceUntilIdle()

        assertEquals(listOf(CashBadges.PC_TOPUPS), badges.tabReads)
    }

    @Test
    fun `a float approval reads its row only once the server agreed`() = runTest(dispatcher) {
        val badges = RecordingBadges()
        val float = float("f1", FloatStatus.AwaitingApproval, createdAt = 1)
        // A chain that makes "me" the first level — with none, nobody may approve.
        val chain = listOf(
            com.zillit.desktop.feature.cashexpenses.domain.ApprovalTierConfig(
                scope = "all",
                departmentId = null,
                tiers = listOf(
                    com.zillit.desktop.feature.cashexpenses.domain.ApprovalTier(
                        listOf(com.zillit.desktop.feature.cashexpenses.domain.TierRule("default", null, listOf("me"))),
                    ),
                ),
            ),
        )
        val failing = FakeCash(writesSucceed = false).apply {
            metadata = CashMetadata(isApprover = true, approvalTierConfigs = chain)
            activeFloatRows = listOf(float)
        }
        val vm = viewModel(failing, badges = badges)
        vm.onEvent(CashEvent.Open(CashDestination.ApprovalQueue))
        advanceUntilIdle()
        vm.onEvent(CashEvent.Ask(CashPrompt.Confirm(ConfirmAction.ApproveFloat, "f1", "", "")))
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertTrue(badges.entityReads.isEmpty(), "a refused approval leaves the chip lit")

        failing.writesSucceed = true
        vm.onEvent(CashEvent.Ask(CashPrompt.Confirm(ConfirmAction.ApproveFloat, "f1", "", "")))
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertEquals(listOf("float_approval|f1|cash_float"), badges.entityReads)
    }

    // -- float detail ---------------------------------------------------------------------

    @Test
    fun `the float detail loads, reads its pc_float row and closes`() = runTest(dispatcher) {
        val badges = RecordingBadges()
        val repository = FakeCash(writesSucceed = true).apply {
            details = FloatDetails(float = float("f1", FloatStatus.Closed, createdAt = 1))
        }
        val vm = viewModel(repository, badges = badges)

        vm.onEvent(CashEvent.OpenFloatDetail("f1"))
        advanceUntilIdle()
        val open = assertNotNull(vm.state.value.floatDetail)
        assertEquals(false, open.loading)
        assertEquals("f1", open.details?.float?.id)
        assertEquals(listOf("pc_float|f1|cash_float"), badges.entityReads)

        vm.onEvent(CashEvent.CloseFloatDetail)
        assertNull(vm.state.value.floatDetail)
    }

    @Test
    fun `a float detail that fails to load says so`() = runTest(dispatcher) {
        val vm = viewModel(FakeCash(writesSucceed = true))
        vm.onEvent(CashEvent.OpenFloatDetail("f9"))
        advanceUntilIdle()
        val open = assertNotNull(vm.state.value.floatDetail)
        assertEquals(false, open.loading)
        assertNull(open.details)
        assertNotNull(open.error)
    }

    // -- float order ------------------------------------------------------------------------

    @Test
    fun `my floats are oldest first, and the overview does not replace them`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            myFloatRows = listOf(
                float("new", FloatStatus.Spending, createdAt = 30),
                float("old", FloatStatus.Spending, createdAt = 10),
                float("mid", FloatStatus.Collected, createdAt = 20),
            )
            myOverviewDoc = MyCashOverview(
                floats = listOf(float("overview-only", FloatStatus.Spending, createdAt = 5)),
            )
        }
        val vm = viewModel(repository, viewer = crew)

        vm.onEvent(CashEvent.Open(CashDestination.FloatRequest))
        advanceUntilIdle()
        assertEquals(listOf("old", "mid", "new"), vm.state.value.myFloats.map { it.id })
        assertEquals("old", vm.state.value.submittableFloat?.id)

        vm.onEvent(CashEvent.Open(CashDestination.MyOverview))
        advanceUntilIdle()
        assertEquals(listOf("old", "mid", "new"), vm.state.value.myFloats.map { it.id })
        assertEquals("overview-only", vm.state.value.myOverview?.floats?.single()?.id)
    }

    // -- fixtures ------------------------------------------------------------------------------

    private fun queuedBatch(status: BatchStatus) =
        FakeCash(writesSucceed = true).queuedBatch(status = status)

    private fun float(id: String, status: FloatStatus, createdAt: Long) = CashFloat(
        id = id, requestNumber = "PC-$id", userId = "u1", holderName = "", departmentId = null, status = status,
        currency = "GBP", requestedAmount = 100.0, issuedAmount = 100.0, balance = 40.0, receiptsAmount = 0.0,
        receiptsCommits = null, returnAmount = 0.0, bsCode = null, companyId = null, duration = null,
        durationType = null, purpose = null, createdAt = createdAt,
    )

    /** Counts the tests set, and every read made, as `key|entity|kind`. */
    private class RecordingBadges : TabBadgeSource {
        override val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val entities = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
        override val entityCounts: Flow<Map<String, Map<String, Int>>> = entities
        val tabReads = mutableListOf<String>()
        val entityReads = mutableListOf<String>()

        override fun read(key: String) {
            tabReads += key
        }

        override fun readEntity(key: String, entityId: String, kind: String?) {
            entityReads += "$key|$entityId|$kind"
        }
    }

    /** A connected socket the test speaks through. */
    private class FakeSocket : SocketClient {
        override val connectionState =
            MutableStateFlow<SocketConnectionState>(SocketConnectionState.Connected("fake"))
        private val inbound = MutableSharedFlow<SocketMessage>(extraBufferCapacity = 8)
        override val messages: Flow<SocketMessage> = inbound

        suspend fun send(event: String, module: String?) {
            val payload = buildJsonObject { put("module", module?.let(::JsonPrimitive) ?: JsonNull) }
            inbound.emit(SocketMessage(SocketEventName(event), payload))
        }

        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Success(Unit)

        override suspend fun emit(event: SocketEventName): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun <T> emitForAck(
            event: SocketEventName,
            payload: T,
            serializer: KSerializer<T>,
        ): ZillitResult<JsonElement> = ZillitResult.Success(JsonNull)
    }
}
