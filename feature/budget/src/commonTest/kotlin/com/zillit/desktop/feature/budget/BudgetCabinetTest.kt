package com.zillit.desktop.feature.budget

import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.budget.ui.BudgetEvent
import com.zillit.desktop.feature.budget.ui.BudgetMembersDialog
import com.zillit.desktop.feature.budget.ui.BudgetViewModel
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The File Cabinet's copy of the two budget tiles.
 *
 * The web sends no cabinet flag on any budget call, so what these pin is the
 * other half: the cabinet asks for exactly what the live tile asks for, and
 * nothing it does can write, visit or read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetCabinetTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** The same reads as the live full budget, in the same order, and no private thread listed. */
    @Test
    fun `the full budget reads what the live tile reads and lists rooms only`() = runTest(dispatcher) {
        val repository = RecordingBudgetRepository(
            byDepartment = mapOf(
                "dept-me" to listOf(cabinetVersion("v1", updated = 10), cabinetVersion("v2", updated = 30)),
            ),
            chats = listOf(BudgetChatEntry.Person("u2", "Ravi"), BudgetChatEntry.Group("r1", "Camera crew")),
        )
        val model = cabinet(BudgetMode.Main, repository)

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertTrue(
            repository.calls.containsAll(listOf("documentsOf:main/dept-me", "chats:main_budget_tool/dept-me/v2")),
            repository.calls.toString(),
        )
        val state = model.state.value
        assertTrue(state.readOnly)
        assertEquals("v2", state.selected?.id)
        assertEquals(listOf("r1"), state.chats.map { it.key })
    }

    /** The department tile starts on the directory (`budget/list`) and a department's own versions on a click. */
    @Test
    fun `the department budget reads the list, then one department`() = runTest(dispatcher) {
        val repository = RecordingBudgetRepository(
            all = listOf(cabinetVersion("d1", BudgetType.Department, department = "cam", updated = 5)),
            byDepartment = mapOf("cam" to listOf(cabinetVersion("d1", BudgetType.Department, "cam", updated = 5))),
            chats = listOf(BudgetChatEntry.Group("r9", "Budget talk")),
        )
        val model = cabinet(BudgetMode.Department, repository)

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()
        assertEquals(listOf("cam"), model.state.value.directory.map { it.id })

        model.onEvent(BudgetEvent.OpenDepartment("cam"))
        advanceUntilIdle()

        assertEquals("list", repository.calls.first())
        assertTrue("documentsOf:department/cam" in repository.calls)
        assertTrue("chats:department_budget_tool/cam/d1" in repository.calls)
    }

    /** Nothing the cabinet does reaches a write route, the file picker, the badge ledger or last-visited. */
    @Test
    fun `no write, visit or read is ever made`() = runTest(dispatcher) {
        val repository = RecordingBudgetRepository(
            byDepartment = mapOf(
                "dept-me" to listOf(cabinetVersion("v1", updated = 10), cabinetVersion("v2", updated = 30)),
            ),
            chats = listOf(BudgetChatEntry.Group("r1", "Camera crew")),
        )
        val host = CountingHost()
        val badges = CountingBadges()
        val model = cabinet(BudgetMode.Main, repository, host = host, badges = badges)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.SelectVersion("v1"))
        model.onEvent(BudgetEvent.OpenChat(BudgetChatEntry.Group("r1", "Camera crew")))
        model.onEvent(BudgetEvent.UploadRequested())
        model.onEvent(BudgetEvent.ShowMembers(BudgetMembersDialog.Kind.Group))
        model.onEvent(BudgetEvent.GroupNameChanged("Plans"))
        model.onEvent(BudgetEvent.ConfirmMembers)
        model.onEvent(BudgetEvent.UploadConfirm)
        model.onEvent(BudgetEvent.OpenDrawer)
        advanceUntilIdle()

        assertEquals(0, host.picks)
        assertTrue(badges.reads.isEmpty(), badges.reads.toString())
        listOf("post", "createRoom", "delete", "markVisited").forEach { write ->
            assertTrue(repository.calls.none { it.startsWith(write) }, "$write was called: ${repository.calls}")
        }
        val state = model.state.value
        assertNull(state.upload)
        assertNull(state.members)
        assertNull(state.drawer)
        assertFalse(state.canPost)
        assertFalse(state.canChat)
        assertEquals("v1", state.selected?.id, "reading an older version is still allowed")
    }

    /** The cabinet viewer is an admin's: both budgets readable, downloads for an admin, nothing postable. */
    @Test
    fun `the cabinet reader may read both budgets and post to neither`() {
        val admin = BudgetViewer.forCabinet(isAdmin = true)
        assertTrue(admin.canView(BudgetMode.Main) && admin.canView(BudgetMode.Department))
        assertTrue(admin.canDownload(BudgetMode.Main))
        assertFalse(admin.canPost(BudgetMode.Main) || admin.canPost(BudgetMode.Department))
        assertFalse(BudgetViewer.forCabinet(isAdmin = false).canDownload(BudgetMode.Department))
    }

    private fun cabinet(
        mode: BudgetMode,
        repository: RecordingBudgetRepository,
        host: CountingHost = CountingHost(),
        badges: CountingBadges = CountingBadges(),
    ) = BudgetViewModel(
        mode = mode,
        repository = repository,
        viewer = { BudgetViewer.forCabinet(isAdmin = true) },
        host = host,
        badges = badges,
        cabinet = true,
    )
}
