package com.zillit.desktop.feature.home

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
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the grid shows in the seconds a project switch takes.
 *
 * The desktop used to hide the whole shell behind a filled box until every
 * view model had been told about the switch — the blank screen on switching
 * production. With the shell drawn straight away, the grid is what the person
 * is looking at while the tools call is in flight, so what it draws then is
 * now a user-visible question with exactly one right answer: *this*
 * production's tools, from the copy kept the last time it was open. Not the
 * previous production's, which is what "keep what you have until the answer
 * comes" meant once the shell stopped being hidden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectSwitchGridTest {

    private val dispatcher = StandardTestDispatcher()
    private val drafts = InMemoryDraftStore()
    private var openProject = "p1"
    private var now = 5_000_000L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** One store, one scope that moves — the host's own `currentScope`. */
    private fun TestScope.support(): OfflineSupport {
        val online = MutableStateFlow(true)
        val engine = SyncEngine(
            store = InMemoryOutboxStore(),
            handlers = SyncHandlerRegistry(),
            online = online,
            currentScope = { SyncScope("u1", openProject) },
            scope = backgroundScope,
            nowMillis = { now },
            newId = { "op" },
        )
        return OfflineSupport(engine, drafts, online) { SyncScope("u1", openProject) }
    }

    private class Answering(private val answer: ZillitResult<ProjectPermissions>) : ToolsRepository {
        override suspend fun loadPermissions(): ZillitResult<ProjectPermissions> = answer
        override suspend fun loadGroups() = ZillitResult.Success(listOf(ToolGroup("finance", "Finance")))
    }

    /** A production whose tools call never comes back — the switch, mid-flight. */
    private class NeverAnswering : ToolsRepository {
        private val never = CompletableDeferred<ZillitResult<ProjectPermissions>>()
        override suspend fun loadPermissions(): ZillitResult<ProjectPermissions> = never.await()
        override suspend fun loadGroups() = ZillitResult.Success(listOf(ToolGroup("finance", "Finance")))
    }

    private fun grid(tool: String) = ProjectPermissions(
        listOf(ToolAccess(tool, groupIdentifier = "finance", canView = true, canPost = true)),
    )

    private fun TestScope.openAndCache(support: OfflineSupport, project: String, tool: String) {
        openProject = project
        HomeViewModel(Answering(ZillitResult.Success(grid(tool))), support, nowMillis = { now }).also {
            it.onEvent(HomeEvent.Reload)
            runCurrent()
        }
    }

    @Test
    fun `a switch draws the production just opened, not the one just left`() = runTest(dispatcher) {
        val support = support()
        openAndCache(support, "p1", "purchase_order_tool")
        openAndCache(support, "p2", "payroll_tool")

        // The session's one grid owner, on p2, switched to p1 — and p1's tools
        // call has not answered yet.
        openProject = "p2"
        val cut = HomeViewModel(NeverAnswering(), support, nowMillis = { now })
        cut.onEvent(HomeEvent.Reload)
        runCurrent()
        assertTrue(cut.currentState.permissions.canView("payroll_tool"), "p2 opened from its own saved grid")

        openProject = "p1"
        cut.onEvent(HomeEvent.Reload)
        runCurrent()

        assertTrue(cut.currentState.permissions.canView("purchase_order_tool"), "p1's own saved grid stands in")
        assertFalse(
            cut.currentState.permissions.canView("payroll_tool"),
            "the production just left was still on screen under the new production's name",
        )
    }

    @Test
    fun `a production never opened here gets nothing to look at rather than the last one's tools`() =
        runTest(dispatcher) {
            val support = support()
            openAndCache(support, "p1", "purchase_order_tool")

            openProject = "p1"
            val cut = HomeViewModel(NeverAnswering(), support, nowMillis = { now })
            cut.onEvent(HomeEvent.Reload)
            runCurrent()
            assertTrue(cut.currentState.permissions.canView("purchase_order_tool"))

            // A production this computer has never been in: there is no copy to
            // put up, and p1's rights must not answer for it.
            openProject = "brand-new"
            cut.onEvent(HomeEvent.Reload)
            runCurrent()

            assertFalse(cut.currentState.permissions.canView("purchase_order_tool"))
            assertTrue(cut.currentState.isBusy, "and it says it is still loading, so the screen can skeleton")
        }
}
