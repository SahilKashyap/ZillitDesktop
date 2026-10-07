package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.home.data.ToolInfoDto
import com.zillit.desktop.feature.home.domain.GuideViewer
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolInfoViewer
import com.zillit.desktop.feature.home.domain.ToolsRepository
import com.zillit.desktop.feature.home.domain.toolGuide
import com.zillit.desktop.feature.home.ui.HomeEvent
import com.zillit.desktop.feature.home.ui.HomeUiState
import com.zillit.desktop.feature.home.ui.HomeViewModel
import com.zillit.desktop.feature.home.ui.UNGROUPED_KEY
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/** The Film Tools page against the web's `FilmTools.jsx` rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class FilmToolsParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun tool(id: String, group: String?) =
        ToolAccess(identifier = id, unitName = id, groupIdentifier = group, canView = true)

    // -- grouping ----------------------------------------------------------------

    @Test
    fun `no group key is filed by the web's map, an empty one is Ungrouped`() {
        assertEquals("group_accounts_payroll", ToolInfoDto(identifier = "payroll_tool").toAccess()?.groupIdentifier)
        assertEquals("group_admin", ToolInfoDto(identifier = "brand_new_tool").toAccess()?.groupIdentifier)
        assertNull(ToolInfoDto(identifier = "payroll_tool", groupIdentifier = "").toAccess()?.groupIdentifier)
        val kept = ToolInfoDto(identifier = "payroll_tool", groupIdentifier = "custom_x").toAccess()
        assertEquals("custom_x", kept?.groupIdentifier)
    }

    @Test
    fun `an unknown group files under Admin, a cleared one under a trailing Ungrouped`() {
        val state = HomeUiState(
            permissions = ProjectPermissions(
                listOf(tool("a_tool", "gone_group"), tool("b_tool", null), tool("c_tool", "group_ads")),
            ),
            groups = listOf(ToolGroup("group_ads", "ADs"), ToolGroup("group_admin", "Admin")),
        )
        val sections = state.sections

        assertEquals(listOf("ADs", "Admin", "Ungrouped"), sections.map { it.title })
        assertEquals(listOf("a_tool"), sections[1].tools.map { it.identifier })
        assertTrue(sections.last().ungrouped)
        assertEquals(UNGROUPED_KEY, sections.last().dropKey)
    }

    @Test
    fun `an empty group is dropped, except while organizing`() {
        val state = HomeUiState(
            permissions = ProjectPermissions(listOf(tool("c_tool", "group_ads"))),
            groups = listOf(ToolGroup("group_ads", "ADs"), ToolGroup("custom_x", "Props", id = "g9")),
        )
        assertEquals(listOf("ADs"), state.sections.map { it.title })
        assertEquals(listOf("ADs", "Props"), state.copy(organizing = true).sections.map { it.title })
    }

    // -- organize ----------------------------------------------------------------

    private class Fake(
        var moveAnswer: ZillitResult<Unit> = ZillitResult.Success(Unit),
        var deleteAnswer: ZillitResult<Unit> = ZillitResult.Success(Unit),
    ) : ToolsRepository {
        val moves = mutableListOf<Pair<String, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var groups = listOf(
            ToolGroup("group_ads", "ADs", id = "g1", systemDefined = true),
            ToolGroup("custom_x", "Props", id = "g2"),
        )

        override suspend fun loadPermissions() = ZillitResult.Success(
            ProjectPermissions(
                listOf(
                    ToolAccess(
                        "callsheet_tool",
                        unitName = "Call Sheet",
                        groupIdentifier = "group_ads",
                        canView = true,
                    ),
                ),
            ),
        )

        override suspend fun loadGroups() = ZillitResult.Success(groups)

        override suspend fun moveTool(identifier: String, groupIdentifier: String): ZillitResult<Unit> {
            moves += identifier to groupIdentifier
            gate?.await()
            return moveAnswer
        }

        override suspend fun deleteGroup(toolGroupId: String) = deleteAnswer
    }

    private fun vm(repo: Fake, flag: Boolean = true) =
        HomeViewModel(repo, isAdmin = { true }, organizeSwitch = { flag })

    @Test
    fun `organizing needs an admin and the switch`() = runTest(dispatcher) {
        val off = vm(Fake(), flag = false).also { it.onEvent(HomeEvent.Reload) }
        advanceUntilIdle()
        off.onEvent(HomeEvent.ToggleOrganize)
        assertFalse(off.currentState.canOrganize)
        assertFalse(off.currentState.organizing)

        val on = vm(Fake()).also { it.onEvent(HomeEvent.Reload) }
        advanceUntilIdle()
        on.onEvent(HomeEvent.ToggleOrganize)
        assertTrue(on.currentState.organizing)
    }

    @Test
    fun `a drop paints at once, writes once and names what moved where`() = runTest(dispatcher) {
        val repo = Fake().apply { gate = CompletableDeferred() }
        val cut = vm(repo).also { it.onEvent(HomeEvent.Reload) }
        advanceUntilIdle()
        cut.onEvent(HomeEvent.ToggleOrganize)

        cut.onEvent(HomeEvent.MoveTool("callsheet_tool", "Call Sheet", "custom_x"))
        runCurrent()
        val props = cut.currentState.sections.first { it.identifier == "custom_x" }
        assertEquals(listOf("callsheet_tool"), props.tools.map { it.identifier }, "painted before the answer")

        repo.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("callsheet_tool" to "custom_x"), repo.moves)
        assertEquals("“Call Sheet” moved to Props", cut.currentState.toast?.text)
    }

    @Test
    fun `a refused drop goes back where it was`() = runTest(dispatcher) {
        val repo = Fake(moveAnswer = ZillitResult.Failure(ZillitError.Http(200, serverMessage = "nope")))
        val cut = vm(repo).also { it.onEvent(HomeEvent.Reload) }
        advanceUntilIdle()
        cut.onEvent(HomeEvent.ToggleOrganize)

        cut.onEvent(HomeEvent.MoveTool("callsheet_tool", "Call Sheet", UNGROUPED_KEY))
        advanceUntilIdle()

        assertEquals(listOf("callsheet_tool" to ""), repo.moves, "Ungrouped is the empty group id on the wire")
        assertTrue(cut.currentState.sections.first { it.identifier == "group_ads" }.tools.isNotEmpty())
        assertEquals(false, cut.currentState.toast?.success)
    }

    @Test
    fun `only a custom group can be deleted, and a refusal says why`() = runTest(dispatcher) {
        val repo = Fake(deleteAnswer = ZillitResult.Failure(ZillitError.Http(200, serverMessage = "tool_group_in_use")))
        val cut = vm(repo).also { it.onEvent(HomeEvent.Reload) }
        advanceUntilIdle()

        assertFalse(cut.currentState.isDeletable("group_ads"))
        assertTrue(cut.currentState.isDeletable("custom_x"))
        assertFalse(cut.currentState.isDeletable(UNGROUPED_KEY))

        cut.onEvent(HomeEvent.ToggleOrganize)
        cut.onEvent(HomeEvent.AskDeleteGroup("group_ads"))
        assertNull(cut.currentState.confirmingDelete, "a default group is never offered")

        cut.onEvent(HomeEvent.AskDeleteGroup("custom_x"))
        cut.onEvent(HomeEvent.ConfirmDeleteGroup)
        advanceUntilIdle()
        assertEquals(false, cut.currentState.toast?.success)
    }

    // -- the ⓘ's links ------------------------------------------------------------

    @Test
    fun `More carries who is reading and the production type, ahead of the anchor`() {
        val admin = GuideViewer(ToolInfoViewer(isAdmin = true), projectType = "film")
        val guide = toolGuide("purchase_order_tool", admin)

        assertEquals("https://documentation.zillit.com/?for=admin&project_type=film#purchase-order", guide.tutorialUrl)
        assertTrue(guide.videoUrl.endsWith(".mp4"))

        val crew = toolGuide("purchase_order_tool", GuideViewer(ToolInfoViewer()))
        assertEquals("https://documentation.zillit.com/?project_type=default#purchase-order", crew.tutorialUrl)
    }

    @Test
    fun `a tool the web gives no clip plays its generic one, and no page means no More`() {
        val guide = toolGuide("account_hub_tool", GuideViewer(ToolInfoViewer()))
        assertNull(guide.tutorialUrl)
        assertTrue(guide.videoUrl.contains("static%20video"))
    }
}
