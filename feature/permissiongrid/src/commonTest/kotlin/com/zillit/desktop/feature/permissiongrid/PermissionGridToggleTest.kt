package com.zillit.desktop.feature.permissiongrid

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridPage
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridRow
import com.zillit.desktop.feature.permissiongrid.domain.DesignationFilter
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridCell
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridQuery
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSection
import com.zillit.desktop.feature.permissiongrid.domain.GridSubject
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridRepository
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridEffect
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridEvent
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The grid's behaviour against the web's `AccessGrid.jsx`: a box moves on
 * the click and goes back if the server refuses, the rights the web keeps
 * read-only stay read-only, and search is the server's, debounced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionGridToggleTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun cell(name: String, locked: Boolean = false, view: Boolean = false, post: Boolean = false) =
        GridCell(
            unitId = "unit_$name",
            unitName = name,
            canView = view,
            canPost = post,
            postLocked = locked,
            viewLocked = locked,
        )

    private fun row(id: String, admin: Boolean = false, vararg cells: GridCell) =
        GridRow(GridSubject(id, "Person $id", isAdmin = admin), cells.associateBy { it.unitName })

    private val page = GridPage(
        columns = listOf("catering_label", "deal_memo_label"),
        rows = listOf(row("u1", false, cell("catering_label"), cell("deal_memo_label"))),
        total = 1,
    )

    private class Fake(
        private val answer: ZillitResult<Unit> = ZillitResult.Success(Unit),
        private val page: GridPage,
        private val defaults: DefaultGridPage = DefaultGridPage.Empty,
    ) : PermissionGridRepository {
        val writes = mutableListOf<Triple<String, AccessKind, Boolean>>()
        val queries = mutableListOf<GridQuery>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun load(query: GridQuery): ZillitResult<GridPage> {
            queries += query
            return ZillitResult.Success(page)
        }

        override suspend fun loadDefaults(axis: GridAxis, section: GridSection) = ZillitResult.Success(defaults)

        override suspend fun setAccess(
            axis: GridAxis,
            section: GridSection,
            entityId: String,
            unitId: String,
            kind: AccessKind,
            enable: Boolean,
        ): ZillitResult<Unit> {
            writes += Triple(unitId, kind, enable)
            gate?.await()
            return answer
        }
    }

    private val editor = PermissionGridViewer.from(
        ProjectPermissions(
            listOf(ToolAccess("permission_grid_tool", canView = true, canPost = true, canDownload = true)),
        ),
    )

    private fun vm(repo: PermissionGridRepository, viewer: PermissionGridViewer = editor) =
        PermissionGridViewModel(repo).also { it.onEvent(PermissionGridEvent.Start(viewer)) }

    private fun PermissionGridViewModel.cell(unit: String, subject: String = "u1") =
        currentState.grid.rows.first { it.subject.id == subject }.cells.getValue(unit)

    @Test
    fun `it opens where the web opens - crew list, home, hired designations`() = runTest(dispatcher) {
        val repo = Fake(page = page)
        vm(repo)
        runCurrent()

        val first = repo.queries.single()
        assertEquals(GridAxis.CrewList, first.axis)
        assertEquals(GridSection.Home, first.section)
        assertEquals(0, first.page)
        assertEquals(20, first.limit)
        assertEquals(DesignationFilter.Used, first.designations)
    }

    @Test
    fun `a click paints at once, holds only that box, and is confirmed`() = runTest(dispatcher) {
        val repo = Fake(page = page).apply { gate = CompletableDeferred() }
        val cut = vm(repo)
        runCurrent()

        cut.onEvent(PermissionGridEvent.Toggle("u1", "catering_label", AccessKind.View, enable = true))
        runCurrent()

        val pending = cut.cell("catering_label")
        assertTrue(pending.canView, "the tick moves on the click")
        assertTrue(pending.busyView)
        assertFalse(pending.busyPost || pending.busyDownload, "only the box being written is held")

        repo.gate?.complete(Unit)
        runCurrent()

        assertTrue(cut.cell("catering_label").canView)
        assertFalse(cut.cell("catering_label").busyView)
        assertEquals(Triple("unit_catering_label", AccessKind.View, true), repo.writes.single())
        assertEquals(true, cut.currentState.toast?.success)
    }

    @Test
    fun `a refusal puts the box back and says why`() = runTest(dispatcher) {
        val refused = Fake(
            answer = ZillitResult.Failure(ZillitError.Http(200, serverMessage = "not_allowed")),
            page = page,
        )
        val cut = vm(refused)
        runCurrent()

        cut.onEvent(PermissionGridEvent.Toggle("u1", "catering_label", AccessKind.View, enable = true))
        runCurrent()

        assertFalse(cut.cell("catering_label").canView, "the server said no, so it went back")
        assertFalse(cut.cell("catering_label").busyView)
        assertEquals(false, cut.currentState.toast?.success)
    }

    @Test
    fun `a locked right is never written`() = runTest(dispatcher) {
        val lockedPage = page.copy(rows = listOf(row("u1", false, cell("catering_label", locked = true))))
        val repo = Fake(page = lockedPage)
        val cut = vm(repo)
        runCurrent()

        cut.onEvent(PermissionGridEvent.Toggle("u1", "catering_label", AccessKind.View, enable = true))
        runCurrent()

        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun `a reader cannot write at all`() = runTest(dispatcher) {
        val repo = Fake(page = page)
        val readOnly = PermissionGridViewer.from(
            ProjectPermissions(listOf(ToolAccess("permission_grid_tool", canView = true))),
        )
        val cut = vm(repo, readOnly)
        runCurrent()

        cut.onEvent(PermissionGridEvent.Toggle("u1", "catering_label", AccessKind.View, enable = true))
        runCurrent()

        assertFalse(cut.currentState.canEdit)
        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun `a deal memo's download is ticked on screen, not written`() = runTest(dispatcher) {
        val repo = Fake(page = page)
        val cut = vm(repo)
        runCurrent()

        cut.onEvent(PermissionGridEvent.Toggle("u1", "deal_memo_label", AccessKind.View, enable = true))
        runCurrent()

        // ZL-16376 — the web patches Download locally; one POST, for View.
        assertEquals(listOf(AccessKind.View), repo.writes.map { it.second })
        assertTrue(cut.cell("deal_memo_label").canDownload)
    }

    @Test
    fun `an admin's boxes are read-only, bar transportation posting`() {
        val admin = row(
            "a1",
            true,
            cell("catering_label").copy(postLocked = false),
            cell("transportation_label").copy(postLocked = false),
        )

        assertFalse(admin.editable("catering_label", AccessKind.View))
        assertFalse(admin.editable("catering_label", AccessKind.Post))
        assertFalse(admin.editable("transportation_label", AccessKind.View))
        assertTrue(admin.editable("transportation_label", AccessKind.Post))
    }

    @Test
    fun `account hub never moves, whatever it is spelled`() {
        val r = row("u1", false, cell("account_hub_label"), cell("account_hub_tool"))
        AccessKind.entries.forEach { kind ->
            assertFalse(r.editable("account_hub_label", kind))
            assertFalse(r.editable("account_hub_tool", kind))
        }
    }

    @Test
    fun `department budget shows the rights main budget grants`() {
        val r = row(
            "u1",
            false,
            cell("main_budget_label", view = true, post = true),
            cell("department_budget_label"),
        )

        assertTrue(r.shownGranted("department_budget_label", AccessKind.View))
        assertTrue(r.shownGranted("department_budget_label", AccessKind.Post))
        assertFalse(r.shownGranted("department_budget_label", AccessKind.Download))
    }

    @Test
    fun `search waits for the typing to settle, then asks the server from page one`() = runTest(dispatcher) {
        val repo = Fake(page = page.copy(total = 100))
        val cut = vm(repo)
        runCurrent()
        cut.onEvent(PermissionGridEvent.GoToPage(3))
        runCurrent()

        cut.onEvent(PermissionGridEvent.Search("Pr"))
        cut.onEvent(PermissionGridEvent.Search("  PriYa "))
        advanceTimeBy(299)
        runCurrent()
        assertEquals(2, repo.queries.size, "nothing goes out mid-typing")

        advanceTimeBy(2)
        runCurrent()
        val searched = repo.queries.last()
        assertEquals("priya", searched.search)
        assertEquals(0, searched.page)
        assertEquals(3, repo.queries.size, "one request per settled query")
    }

    @Test
    fun `switching type clears the search and starts at page one`() = runTest(dispatcher) {
        val cut = vm(Fake(page = page))
        runCurrent()
        cut.onEvent(PermissionGridEvent.Search("ai"))
        advanceTimeBy(400)
        runCurrent()

        cut.onEvent(PermissionGridEvent.SelectAxis(GridAxis.Departments))
        runCurrent()

        assertEquals(GridAxis.Departments, cut.currentState.axis)
        assertEquals(1, cut.currentState.page)
        assertEquals("", cut.currentState.query)
        assertEquals("", cut.currentState.search)
    }

    @Test
    fun `the default grid exports every matching row as a workbook`() = runTest(dispatcher) {
        val defaults = DefaultGridPage(
            first = "department_label",
            columns = listOf("catering_label"),
            rows = listOf(
                DefaultGridRow("d1", "Camera", listOf(cell("catering_label", view = true))),
                DefaultGridRow("d2", "Art", listOf(cell("catering_label"))),
            ),
        )
        val cut = vm(Fake(page = page, defaults = defaults))
        val effects = mutableListOf<PermissionGridEffect>()
        backgroundScope.launch { cut.effects.collect { effects += it } }
        runCurrent()

        cut.onEvent(PermissionGridEvent.Defaults.Open)
        runCurrent()
        assertEquals(2, cut.currentState.defaults.matching.size)

        cut.onEvent(PermissionGridEvent.Defaults.Search("cam"))
        cut.onEvent(PermissionGridEvent.Defaults.DownloadExcel)
        runCurrent()

        val saved = assertIs<PermissionGridEffect.SaveFile>(effects.single())
        assertTrue(saved.fileName.endsWith(".xlsx"))
        // A zip: the workbook's first two bytes are "PK".
        assertEquals('P'.code.toByte(), saved.bytes[0])
        assertEquals('K'.code.toByte(), saved.bytes[1])
    }

    @Test
    fun `without download rights the export asks instead`() = runTest(dispatcher) {
        val viewOnly = PermissionGridViewer.from(
            ProjectPermissions(listOf(ToolAccess("permission_grid_tool", canView = true, canPost = true))),
        )
        val cut = vm(Fake(page = page), viewOnly)
        val effects = mutableListOf<PermissionGridEffect>()
        backgroundScope.launch { cut.effects.collect { effects += it } }
        runCurrent()

        cut.onEvent(PermissionGridEvent.Defaults.DownloadExcel)
        runCurrent()

        assertTrue(effects.isEmpty())
        assertEquals(false, cut.currentState.toast?.success)
    }

    @Test
    fun `an absent grid entry opens the grid, as on the web`() {
        val promoted = PermissionGridViewer.from(
            ProjectPermissions(listOf(ToolAccess("call_sheet_tool", canView = true)), isAdmin = true),
        )
        assertTrue(promoted.ready)
        assertTrue(promoted.canView)
        assertTrue(promoted.canPost)

        val unknown = PermissionGridViewer.from(ProjectPermissions.Empty)
        assertFalse(unknown.ready, "before the tools call answers, nothing is decided")
    }
}
