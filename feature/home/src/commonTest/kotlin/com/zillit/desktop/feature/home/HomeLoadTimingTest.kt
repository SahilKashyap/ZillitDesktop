package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolsRepository
import com.zillit.desktop.feature.home.ui.HomeEvent
import com.zillit.desktop.feature.home.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When the tools call is allowed to fire.
 *
 * It carries project and user in its `moduledata`. Fired before a production is
 * open it sends an empty context and the server answers **406** — which is
 * exactly what happened on the first run against QA, from the QR screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLoadTimingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class CountingRepository : ToolsRepository {
        var calls = 0
            private set

        override suspend fun loadPermissions(): ZillitResult<ProjectPermissions> {
            calls++
            return ZillitResult.Success(
                ProjectPermissions(listOf(ToolAccess("email_tool", canView = true))),
            )
        }

        override suspend fun loadGroups() = ZillitResult.Success(emptyList<ToolGroup>())
    }

    @Test
    fun `constructing the view model does not call the server`() = runTest(dispatcher) {
        val repository = CountingRepository()

        HomeViewModel(repository)
        advanceUntilIdle()

        assertEquals(0, repository.calls, "the tools call fired before a production was open")
    }

    @Test
    fun `the host triggers the load once a production is open`() = runTest(dispatcher) {
        val repository = CountingRepository()
        val viewModel = HomeViewModel(repository)

        viewModel.onEvent(HomeEvent.Reload)
        advanceUntilIdle()

        assertEquals(1, repository.calls)
        assertTrue(viewModel.currentState.permissions.canView("email_tool"))
    }
}
