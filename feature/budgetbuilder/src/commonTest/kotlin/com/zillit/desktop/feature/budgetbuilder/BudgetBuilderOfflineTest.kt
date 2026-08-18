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
 * Budget Builder is a hosted application; with the network gone there is
 * nothing on this computer to open. The launch page says so and the button
 * does nothing, rather than opening a window that cannot load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetBuilderOfflineTest {

    private val dispatcher = StandardTestDispatcher()
    private val online = MutableStateFlow(true)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BudgetBuilderViewModel(
        resolveViewer = { BudgetBuilderViewer() },
        configured = true,
        online = online,
    ).also { it.start() }

    @Test
    fun `offline, the page says so and Open does not launch`() = runTest(dispatcher) {
        val vm = viewModel()
        val launches = mutableListOf<BudgetBuilderEffect>()
        val collecting = launch { vm.effects.collect { launches += it } }
        dispatcher.scheduler.runCurrent()

        online.value = false
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.offline)

        vm.onEvent(BudgetBuilderEvent.Open)
        dispatcher.scheduler.runCurrent()
        assertTrue(launches.isEmpty(), "nothing to open offline")

        online.value = true
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.offline)
        vm.onEvent(BudgetBuilderEvent.Open)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf<BudgetBuilderEffect>(BudgetBuilderEffect.Launch), launches, "back online, it opens")

        collecting.cancel()
    }
}
