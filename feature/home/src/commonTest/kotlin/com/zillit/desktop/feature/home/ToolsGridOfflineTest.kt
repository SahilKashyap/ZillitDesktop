package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.core.sync.InMemoryDraftStore
import com.zillit.desktop.core.sync.InMemoryOutboxStore
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.core.sync.SyncEngine
import com.zillit.desktop.core.sync.SyncHandlerRegistry
import com.zillit.desktop.core.sync.SyncScope
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolsRepository
import com.zillit.desktop.feature.home.ui.HomeEvent
import com.zillit.desktop.feature.home.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tools grid is the way into every tool, so it must draw from its last
 * answer when the network is gone — and only then. A refusal is not offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolsGridOfflineTest {

    private val dispatcher = StandardTestDispatcher()
    private val drafts = InMemoryDraftStore()
    private var now = 5_000_000L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.support(): OfflineSupport {
        val online = MutableStateFlow(true)
        val engine = SyncEngine(
            store = InMemoryOutboxStore(),
            handlers = SyncHandlerRegistry(),
            online = online,
            currentScope = { SyncScope("u1", "p1") },
            scope = backgroundScope,
            nowMillis = { now },
            newId = { "op" },
        )
        return OfflineSupport(engine, drafts, online) { SyncScope("u1", "p1") }
    }

    private class Answering(private val answer: ZillitResult<ProjectPermissions>) : ToolsRepository {
        override suspend fun loadPermissions(): ZillitResult<ProjectPermissions> = answer
        override suspend fun loadGroups() = ZillitResult.Success(listOf(ToolGroup("finance", "Finance")))
    }

    private val issued = ProjectPermissions(
        listOf(ToolAccess("purchase_order_tool", groupIdentifier = "finance", canView = true, canPost = true)),
    )

    @Test
    fun `the grid is drawn from its saved copy when the network is gone, dated`() = runTest(dispatcher) {
        val support = support()
        val online = HomeViewModel(Answering(ZillitResult.Success(issued)), support, nowMillis = { now })
        online.onEvent(HomeEvent.Reload)
        runCurrent()
        assertTrue(online.currentState.permissions.canView("purchase_order_tool"))
        val fetchedAt = now

        now += 90_000
        val unreachable = Answering(ZillitResult.Failure(ZillitError.NoConnection()))
        val cut = HomeViewModel(unreachable, support, nowMillis = { now })
        cut.onEvent(HomeEvent.Reload)
        runCurrent()

        assertTrue(cut.currentState.permissions.canView("purchase_order_tool"), "the saved grid stands in")
        assertTrue(cut.currentState.permissions.canPost("purchase_order_tool"))
        assertFalse(cut.currentState.permissions.canView("payroll_tool"), "absent still means denied")
        assertEquals(fetchedAt, cut.currentState.staleSince)
        assertNull(cut.currentState.error)
        assertEquals(listOf("finance"), cut.currentState.groups.map { it.identifier })
    }

    @Test
    fun `a server refusal is not offline and shows no saved copy`() = runTest(dispatcher) {
        val support = support()
        HomeViewModel(Answering(ZillitResult.Success(issued)), support, nowMillis = { now }).also {
            it.onEvent(HomeEvent.Reload)
            runCurrent()
        }

        val forbidden = Answering(ZillitResult.Failure(ZillitError.Forbidden()))
        val refused = HomeViewModel(forbidden, support, nowMillis = { now })
        refused.onEvent(HomeEvent.Reload)
        runCurrent()

        assertFalse(refused.currentState.permissions.canView("purchase_order_tool"))
        assertNull(refused.currentState.staleSince)
        assertNotNull(refused.currentState.error)
    }

    @Test
    fun `without offline support the grid stays network-only`() = runTest(dispatcher) {
        val cut = HomeViewModel(Answering(ZillitResult.Failure(ZillitError.NoConnection())))
        cut.onEvent(HomeEvent.Reload)
        runCurrent()
        assertNotNull(cut.currentState.error)
        assertNull(cut.currentState.staleSince)
    }
}
