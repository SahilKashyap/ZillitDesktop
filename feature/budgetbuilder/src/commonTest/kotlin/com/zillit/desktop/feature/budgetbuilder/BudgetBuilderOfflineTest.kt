package com.zillit.desktop.feature.budgetbuilder

import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderEffect
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderEvent
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the tool's window shows, and what the application's own exit does.
 *
 * Budget Builder is a hosted application: with the network gone there is
 * nothing on this computer to render, so the window says so rather than
 * embedding a browser that cannot load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetBuilderOfflineTest {

    private val dispatcher = StandardTestDispatcher()
    private val online = MutableStateFlow(true)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        viewer: BudgetBuilderViewer = BudgetBuilderViewer(),
        configured: Boolean = true,
    ) = BudgetBuilderViewModel(
        resolveViewer = { viewer },
        configured = configured,
        online = online,
    ).also { it.start() }

    @Test
    fun `offline, the window says so instead of embedding the application`() = runTest(dispatcher) {
        val vm = viewModel()
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.showsApplication, "online, the application is the window")

        online.value = false
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.showsApplication, "nothing to embed offline")

        online.value = true
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.offline)
        assertTrue(vm.state.value.showsApplication, "back online, it embeds again")
    }

    /** The two other reasons the application is not what gets drawn. */
    @Test
    fun `an unconfigured install and a blocked viewer both withhold the application`() = runTest(dispatcher) {
        assertFalse(viewModel(configured = false).state.value.showsApplication)
        val blocked = BudgetBuilderViewer(canView = false, canPost = false, ready = true)
        assertFalse(viewModel(viewer = blocked).state.value.showsApplication)
    }

    /**
     * The application's own "← Film Tools" button posts `zillit:exit`; the web
     * answers by leaving the route, and here the window closes. It is the only
     * way out the tool offers besides the window's own close button, because
     * the page is given the whole window with no strip of ours above it.
     */
    @Test
    fun `the application's exit asks the window to close`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = mutableListOf<BudgetBuilderEffect>()
        val collecting = launch { vm.effects.collect { effects += it } }
        dispatcher.scheduler.runCurrent()

        vm.onEvent(BudgetBuilderEvent.ExitRequested)
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf<BudgetBuilderEffect>(BudgetBuilderEffect.Exit), effects)
        collecting.cancel()
    }
}
