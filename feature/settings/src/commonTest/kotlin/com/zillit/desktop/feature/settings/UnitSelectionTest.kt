package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.core.units.UnitRepository
import com.zillit.desktop.feature.settings.ui.SettingsEvent
import com.zillit.desktop.feature.settings.ui.SettingsUiState
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import com.zillit.desktop.feature.settings.ui.UnitContext
import com.zillit.desktop.feature.settings.ui.UnitSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Choosing which production unit you are on.
 *
 * Not a cosmetic setting: the unit decides whose call sheets and notices reach
 * you, so being on the wrong one means turning up to the wrong call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnitSelectionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val main = ProductionUnit("u1", "Main Unit")
    private val second = ProductionUnit("u2", "Second Unit")

    private fun viewModel(
        units: FakeUnits = FakeUnits(listOf(main, second)),
        selectedId: String? = "u1",
        onUnitChanged: suspend () -> Unit = {},
        context: Flow<UnitContext> = flowOf(UnitContext(projectId = "p1")),
    ) = SettingsViewModel(
        setTheme = {},
        setScale = {},
        signOut = {},
        unitRepository = units,
        onUnitChanged = onUnitChanged,
        unitContext = context,
        initial = SettingsUiState(unit = UnitSelection(selectedId = selectedId)),
    )

    // -- loading -----------------------------------------------------------

    @Test
    fun `the units are loaded without being asked for`() = runTest(dispatcher) {
        // The screen is opened to change something; making the user click
        // "load units" first is a step that exists only for the programmer.
        val settings = viewModel()

        advanceUntilIdle()

        assertEquals(listOf(main, second), settings.state.value.unit.options)
    }

    @Test
    fun `the current unit is shown as chosen`() = runTest(dispatcher) {
        val settings = viewModel(selectedId = "u2")
        advanceUntilIdle()

        assertEquals(second, settings.state.value.unit.selected)
    }

    @Test
    fun `a failure to load leaves the row absent rather than shouting`() = runTest(dispatcher) {
        // The user came here to do something else. An error about a list they
        // did not ask for is noise.
        val settings = viewModel(units = FakeUnits(emptyList(), failList = true))

        advanceUntilIdle()

        assertTrue(settings.state.value.unit.options.isEmpty())
        assertFalse(settings.state.value.unit.isOfferable)
    }

    @Test
    fun `nothing is asked for before a production is open`() = runTest(dispatcher) {
        // The settings ViewModel is built at startup, long before anyone signs
        // in. Asking a units service that has no idea who is calling produced a
        // rejected request on every launch.
        val units = FakeUnits(listOf(main, second))
        viewModel(units, context = flowOf(UnitContext(projectId = null)))

        advanceUntilIdle()

        assertEquals(0, units.listCalls)
    }

    @Test
    fun `switching production replaces the list`() = runTest(dispatcher) {
        // Units belong to a production. Keeping the previous one's list would
        // offer units the user cannot be on.
        val units = FakeUnits(listOf(main, second))
        val context = MutableStateFlow(UnitContext(projectId = "p1"))
        viewModel(units, context = context)
        advanceUntilIdle()
        assertEquals(1, units.listCalls)

        context.value = UnitContext(projectId = "p2")
        advanceUntilIdle()

        assertEquals(2, units.listCalls)
    }

    @Test
    fun `a profile refresh does not re-fetch the same production's units`() = runTest(dispatcher) {
        val units = FakeUnits(listOf(main, second))
        val context = MutableStateFlow(UnitContext(projectId = "p1", joinUnitId = "u1"))
        viewModel(units, context = context)
        advanceUntilIdle()

        context.value = UnitContext(projectId = "p1", joinUnitId = "u2")
        advanceUntilIdle()

        assertEquals(1, units.listCalls)
    }

    @Test
    fun `the server's answer replaces the optimistic one`() = runTest(dispatcher) {
        val context = MutableStateFlow(UnitContext(projectId = "p1", joinUnitId = "u1"))
        val settings = viewModel(context = context)
        advanceUntilIdle()

        context.value = UnitContext(projectId = "p1", joinUnitId = "u2")
        advanceUntilIdle()

        assertEquals("u2", settings.state.value.unit.selectedId)
    }

    // -- when to offer it at all -------------------------------------------

    @Test
    fun `a production with one unit offers no choice`() {
        // A dropdown with a single option is furniture.
        assertFalse(UnitSelection(options = listOf(main)).isOfferable)
        assertFalse(UnitSelection(options = emptyList()).isOfferable)
        assertTrue(UnitSelection(options = listOf(main, second)).isOfferable)
    }

    // -- changing ----------------------------------------------------------

    @Test
    fun `choosing a unit saves it`() = runTest(dispatcher) {
        val units = FakeUnits(listOf(main, second))
        val settings = viewModel(units)
        advanceUntilIdle()

        settings.onEvent(SettingsEvent.UnitChanged("u2"))
        advanceUntilIdle()

        assertEquals(listOf("u2"), units.saved)
        assertEquals("u2", settings.state.value.unit.selectedId)
    }

    @Test
    fun `the rest of the app is told`() = runTest(dispatcher) {
        // Notices and call sheets are filtered by unit. A change that only the
        // settings screen knows about would leave the user on the old feed.
        var reloads = 0
        val settings = viewModel(onUnitChanged = { reloads++ })
        advanceUntilIdle()

        settings.onEvent(SettingsEvent.UnitChanged("u2"))
        advanceUntilIdle()

        assertEquals(1, reloads)
    }

    @Test
    fun `a failed save puts the old unit back`() = runTest(dispatcher) {
        // The picker must not keep showing a unit the server never accepted —
        // that is the user believing they are on second unit when they are not.
        val units = FakeUnits(listOf(main, second), failSave = true)
        val settings = viewModel(units)
        advanceUntilIdle()

        settings.onEvent(SettingsEvent.UnitChanged("u2"))
        advanceUntilIdle()

        assertEquals("u1", settings.state.value.unit.selectedId)
        assertNotNull(settings.state.value.unit.error)
    }

    @Test
    fun `re-picking the unit you are already on does nothing`() = runTest(dispatcher) {
        val units = FakeUnits(listOf(main, second))
        val settings = viewModel(units)
        advanceUntilIdle()

        settings.onEvent(SettingsEvent.UnitChanged("u1"))
        advanceUntilIdle()

        assertTrue(units.saved.isEmpty())
    }

    // -- fake --------------------------------------------------------------

    private class FakeUnits(
        private val units: List<ProductionUnit>,
        private val failList: Boolean = false,
        private val failSave: Boolean = false,
    ) : UnitRepository {
        val saved = mutableListOf<String>()
        var listCalls = 0
            private set

        override suspend fun joinUnits(projectId: String?): ZillitResult<List<ProductionUnit>> {
            listCalls++
            return if (failList) {
                ZillitResult.Failure(ZillitError.Http(500, "nope"))
            } else {
                ZillitResult.Success(units)
            }
        }

        override suspend fun setJoinUnit(unitId: String): ZillitResult<Unit> {
            if (failSave) return ZillitResult.Failure(ZillitError.Http(500, "nope"))
            saved += unitId
            return ZillitResult.Success(Unit)
        }
    }
}
