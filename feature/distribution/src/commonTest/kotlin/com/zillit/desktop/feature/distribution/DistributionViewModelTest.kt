package com.zillit.desktop.feature.distribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.distribution.domain.DistributionDirectory
import com.zillit.desktop.feature.distribution.domain.DistributionPerson
import com.zillit.desktop.feature.distribution.domain.DistributionRepository
import com.zillit.desktop.feature.distribution.domain.DistributionSection
import com.zillit.desktop.feature.distribution.domain.DistributionUnit
import com.zillit.desktop.feature.distribution.domain.DistributionUser
import com.zillit.desktop.feature.distribution.domain.DistributionViewer
import com.zillit.desktop.feature.distribution.ui.DistributionEffect
import com.zillit.desktop.feature.distribution.ui.DistributionEvent
import com.zillit.desktop.feature.distribution.ui.DistributionViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The toggle's bargain, the filters' resets, and the live refreshes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DistributionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val bulletin = DistributionUnit("h1", "bulletin_label", toEnabled = false, isHome = true)
    private val location = DistributionUnit("t1", "location_tool_label", toEnabled = true, isTool = true)
    private val gaffer = DistributionUser("u1", "Gaffer", status = "accepted", units = listOf(bulletin, location))
    private val vendor = DistributionUser("u2", "Grip Hire", userType = "external", units = listOf(bulletin))

    private class FakeRepository : DistributionRepository {
        var rows: List<DistributionUser> = emptyList()
        var reads = 0
        val writes = mutableListOf<List<Any>>()
        var answer: CompletableDeferred<ZillitResult<String?>>? = null

        override suspend fun allAccess(): ZillitResult<List<DistributionUser>> {
            reads++
            return ZillitResult.Success(rows)
        }

        override suspend fun setAccess(
            userId: String,
            unitId: String,
            enabled: Boolean,
            section: DistributionSection,
            isExternal: Boolean,
        ): ZillitResult<String?> {
            writes += listOf(userId, unitId, enabled, section.wire, isExternal)
            return answer?.await() ?: ZillitResult.Success("distribution_updated")
        }
    }

    private val poster = DistributionViewer(canView = true, canPost = true, ready = true)

    private fun viewModel(
        repository: FakeRepository,
        viewer: DistributionViewer = poster,
        changes: MutableSharedFlow<Unit>? = null,
        reorders: MutableSharedFlow<Unit>? = null,
        directory: DistributionDirectory? = null,
    ) = DistributionViewModel(
        repository = repository,
        resolveViewer = { viewer },
        directory = directory,
        changes = changes,
        reorders = reorders,
        translate = { it },
    )

    @Test
    fun `a toggle is optimistic, posts the section's wire, and toasts the server's message`() = runTest {
        val repository = FakeRepository().apply { rows = listOf(gaffer, vendor) }
        val vm = viewModel(repository)
        val effects = mutableListOf<DistributionEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()

        repository.answer = CompletableDeferred()
        vm.onEvent(DistributionEvent.Toggle("u1", "h1", enabled = true))
        runCurrent()

        // Ticked before the server answers; the row is held while it saves.
        assertTrue(vm.currentState.users[0].cell("h1", DistributionSection.Home)?.toEnabled == true)
        assertTrue(vm.currentState.isBusy("u1", "h1"))
        assertTrue(vm.currentState.isHeld("u1", "t1"))
        assertEquals(listOf("u1", "h1", true, "home", false), repository.writes.single())

        repository.answer?.complete(ZillitResult.Success("distribution_updated"))
        runCurrent()
        assertFalse(vm.currentState.isBusy("u1", "h1"))
        val notice = effects.filterIsInstance<DistributionEffect.Notice>().single()
        assertTrue(notice.success)
    }

    @Test
    fun `a refused toggle rolls the cell back and reports the reason`() = runTest {
        val repository = FakeRepository().apply { rows = listOf(gaffer) }
        val vm = viewModel(repository)
        vm.start()
        runCurrent()

        repository.answer = CompletableDeferred(
            ZillitResult.Failure(ZillitError.Validation("You cannot change this unit.")),
        )
        vm.onEvent(DistributionEvent.Toggle("u1", "h1", enabled = true))
        runCurrent()

        assertFalse(vm.currentState.users[0].cell("h1", DistributionSection.Home)?.toEnabled == true)
        assertEquals("You cannot change this unit.", vm.currentState.error)
        assertTrue(vm.currentState.busy.isEmpty())
    }

    @Test
    fun `an outsider's toggle says so on the wire`() = runTest {
        val repository = FakeRepository().apply { rows = listOf(vendor) }
        val vm = viewModel(repository)
        vm.start()
        runCurrent()

        vm.onEvent(DistributionEvent.Toggle("u2", "h1", enabled = true))
        runCurrent()

        assertEquals(true, repository.writes.single().last())
    }

    @Test
    fun `without posting rights nothing is written and the refusal is said`() = runTest {
        val repository = FakeRepository().apply { rows = listOf(gaffer) }
        val vm = viewModel(repository, viewer = poster.copy(canPost = false))
        val effects = mutableListOf<DistributionEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()

        vm.onEvent(DistributionEvent.Toggle("u1", "h1", enabled = true))
        runCurrent()

        assertTrue(repository.writes.isEmpty())
        assertFalse(vm.currentState.users[0].cell("h1", DistributionSection.Home)?.toEnabled == true)
        val notice = effects.filterIsInstance<DistributionEffect.Notice>().single()
        assertFalse(notice.success)
    }

    @Test
    fun `switching sections clears the unit filter and returns to page one`() = runTest {
        val vm = viewModel(FakeRepository().apply { rows = listOf(gaffer) })
        vm.start()
        runCurrent()

        vm.onEvent(DistributionEvent.UnitFilter(listOf("h1")))
        vm.onEvent(DistributionEvent.GoToPage(3))
        vm.onEvent(DistributionEvent.Section(DistributionSection.Tools))

        assertEquals(DistributionSection.Tools, vm.currentState.section)
        assertTrue(vm.currentState.unitFilter.isEmpty())
        assertEquals(1, vm.currentState.page)
        assertEquals(listOf("t1"), vm.currentState.allColumns { it }.map { it.unitId })
    }

    @Test
    fun `a socket nudge refetches quietly, and the directory names the rows`() = runTest {
        val repository = FakeRepository().apply { rows = listOf(gaffer) }
        val changes = MutableSharedFlow<Unit>()
        val reorders = MutableSharedFlow<Unit>()
        val vm = viewModel(
            repository,
            changes = changes,
            reorders = reorders,
            directory = { listOf(DistributionPerson("u1", fullName = "Aisha Khan", status = "accepted")) },
        )
        vm.start()
        runCurrent()
        assertEquals(1, repository.reads)
        assertEquals("Aisha Khan", vm.currentState.people["u1"]?.fullName)

        changes.emit(Unit)
        runCurrent()
        assertEquals(2, repository.reads)
        assertFalse(vm.currentState.isLoading)

        reorders.emit(Unit)
        runCurrent()
        assertEquals(3, repository.reads)
        assertNull(vm.currentState.error)
    }

    @Test
    fun `the essential notes open the documentation site, the listing order only for admins`() = runTest {
        val vm = viewModel(FakeRepository(), viewer = poster.copy(isAdmin = false))
        val effects = mutableListOf<DistributionEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()

        vm.onEvent(DistributionEvent.OpenEssentialNote(2))
        vm.onEvent(DistributionEvent.OpenListingOrder)
        runCurrent()

        val expected: DistributionEffect =
            DistributionEffect.OpenUrl("https://documentation.zillit.com/#essential-note-2")
        assertEquals(listOf(expected), effects)
    }
}
