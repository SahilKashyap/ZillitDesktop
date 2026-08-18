package com.zillit.desktop.feature.draft

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.draft.domain.DraftHost
import com.zillit.desktop.feature.draft.domain.DraftStore
import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.ImportedFile
import com.zillit.desktop.feature.draft.domain.ScreenplayRenderer
import com.zillit.desktop.feature.draft.domain.StoredScript
import com.zillit.desktop.feature.draft.ui.DraftEvent
import com.zillit.desktop.feature.draft.ui.DraftViewModel
import com.zillit.desktop.feature.draft.ui.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The editor's typing rhythm, autosave, import and export, on a fake store and host. */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class MemoryStore : DraftStore {
        val saved = linkedMapOf<String, StoredScript>()
        override suspend fun list(projectId: String) = saved.values.filter { it.projectId == projectId }
        override suspend fun load(id: String) = saved[id]
        override suspend fun save(script: StoredScript) {
            saved[script.id] = script
        }
        override suspend fun delete(id: String) {
            saved.remove(id)
        }
    }

    private class FakeHost(var importFile: ImportedFile? = null) : DraftHost {
        val exported = mutableListOf<String>()
        override suspend fun pickImport() = importFile
        override suspend fun export(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
            exported += fileName
            return ZillitResult.Success(Unit)
        }
        override suspend fun sendToDrive(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
    }

    private var ids = 0
    private var clock = 1_000L

    private fun viewModel(store: MemoryStore = MemoryStore(), host: FakeHost = FakeHost()) = DraftViewModel(
        store = store,
        host = host,
        renderer = ScreenplayRenderer { ByteArray(4) },
        projectId = { "p1" },
        newId = { "id${ids++}" },
        nowMillis = { clock },
    ).also { it.start() }

    private fun DraftViewModel.types() = currentState.open!!.elements.map { it.type }
    private fun DraftViewModel.texts() = currentState.open!!.elements.map { it.text }
    private fun DraftViewModel.focused() = currentState.open!!.focused!!

    @Test
    fun `a new script opens on a scene heading and Enter walks the flow`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(DraftEvent.StartNew)
        vm.onEvent(DraftEvent.NewTitleChanged("The Weekend"))
        vm.onEvent(DraftEvent.CreateNew)
        advanceUntilIdle()

        assertEquals(listOf(ElementType.SceneHeading), vm.types())
        val heading = vm.focused()
        vm.onEvent(DraftEvent.TextChanged(heading.id, "int. kitchen - day"))
        assertEquals("INT. KITCHEN - DAY", vm.focused().text, "headings are typed in caps")

        vm.onEvent(DraftEvent.Split(heading.id, heading.text.length + 20))
        assertEquals(listOf(ElementType.SceneHeading, ElementType.Action), vm.types())
        val action = vm.focused()
        vm.onEvent(DraftEvent.TextChanged(action.id, "Sam waits."))
        vm.onEvent(DraftEvent.Tab(action.id))
        assertEquals(ElementType.Character, vm.focused().type, "Tab on action starts a cue")
        val cue = vm.focused()
        vm.onEvent(DraftEvent.TextChanged(cue.id, "Sam"))
        vm.onEvent(DraftEvent.Split(cue.id, 3))
        assertEquals(ElementType.Dialogue, vm.focused().type, "Enter after a cue is the line")
        vm.onEvent(DraftEvent.TextChanged(vm.focused().id, "Finally."))
        vm.onEvent(DraftEvent.Split(vm.focused().id, 8))
        assertEquals(ElementType.Character, vm.focused().type, "Enter after a line is the next speaker")
        assertEquals(listOf("SAM"), vm.characters())
    }

    @Test
    fun `smart type offers the known name and Tab accepts it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(DraftEvent.StartNew)
        vm.onEvent(DraftEvent.CreateNew)
        advanceUntilIdle()
        val first = vm.focused()
        vm.onEvent(DraftEvent.SetType(first.id, ElementType.Character))
        vm.onEvent(DraftEvent.TextChanged(first.id, "MARGARET"))
        vm.onEvent(DraftEvent.Split(first.id, 8))
        vm.onEvent(DraftEvent.TextChanged(vm.focused().id, "Hello."))
        vm.onEvent(DraftEvent.Split(vm.focused().id, 6))
        val cue = vm.focused()
        assertEquals(ElementType.Character, cue.type)
        vm.onEvent(DraftEvent.TextChanged(cue.id, "ma"))
        assertEquals(listOf("MARGARET"), vm.currentState.open!!.suggestions)
        vm.onEvent(DraftEvent.Tab(cue.id))
        assertEquals("MARGARET", vm.focused().text)
    }

    @Test
    fun `backspace at the start joins upwards and empty elements can be removed`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(DraftEvent.StartNew)
        vm.onEvent(DraftEvent.CreateNew)
        advanceUntilIdle()
        val heading = vm.focused()
        vm.onEvent(DraftEvent.TextChanged(heading.id, "INT. HALL - DAY"))
        vm.onEvent(DraftEvent.Split(heading.id, 15))
        val action = vm.focused()
        vm.onEvent(DraftEvent.TextChanged(action.id, "Quiet."))
        vm.onEvent(DraftEvent.MergeUp(action.id))
        assertEquals(listOf("INT. HALL - DAY QUIET."), vm.texts())
        vm.onEvent(DraftEvent.Split(vm.focused().id, vm.focused().text.length))
        assertEquals("", vm.focused().text)
        vm.onEvent(DraftEvent.RemoveEmpty(vm.focused().id))
        assertEquals(1, vm.currentState.open!!.elements.size)
    }

    @Test
    fun `edits autosave into the store and export names the file after the script`() = runTest(dispatcher) {
        val store = MemoryStore()
        val host = FakeHost()
        val vm = viewModel(store, host)
        vm.onEvent(DraftEvent.StartNew)
        vm.onEvent(DraftEvent.NewTitleChanged("Night Shift"))
        vm.onEvent(DraftEvent.CreateNew)
        advanceUntilIdle()
        vm.onEvent(DraftEvent.TextChanged(vm.focused().id, "EXT. YARD - NIGHT"))
        assertTrue(vm.currentState.open!!.dirty)
        clock = 5_000L
        advanceTimeBy(1_000L)
        advanceUntilIdle()
        val stored = store.saved.values.single()
        assertTrue(stored.body.contains("EXT. YARD - NIGHT"))
        assertEquals(5_000L, stored.updatedAtMillis)
        assertTrue(!vm.currentState.open!!.dirty)

        vm.onEvent(DraftEvent.Export(ExportFormat.Fountain))
        advanceUntilIdle()
        assertEquals(listOf("Night_Shift.fountain"), host.exported)
    }

    @Test
    fun `import reads a Fountain file into a new script on the production`() = runTest(dispatcher) {
        val store = MemoryStore()
        val host = FakeHost(
            ImportedFile(
                "pilot.fountain",
                "Title: Pilot\nAuthor: Jo\n\nINT. BAR - NIGHT\n\nA quiet room.\n\nJO\nHi.\n",
            ),
        )
        val vm = viewModel(store, host)
        advanceUntilIdle()
        vm.onEvent(DraftEvent.Import)
        advanceUntilIdle()
        val open = assertNotNull(vm.currentState.open)
        assertEquals("Pilot", open.screenplay.title)
        assertEquals(
            listOf(ElementType.SceneHeading, ElementType.Action, ElementType.Character, ElementType.Dialogue),
            open.elements.map { it.type },
        )
        assertEquals(1, store.saved.size)
        assertEquals(1, vm.currentState.scripts.size)
    }
}
