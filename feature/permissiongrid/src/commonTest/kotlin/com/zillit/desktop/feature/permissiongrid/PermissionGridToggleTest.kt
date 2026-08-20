package com.zillit.desktop.feature.permissiongrid

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridCell
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSection
import com.zillit.desktop.feature.permissiongrid.domain.GridSubject
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridRepository
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridEvent
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Writing one cell: the box moves when the server agrees, and only then. */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionGridToggleTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun cell(name: String, locked: Boolean = false) = GridCell(
        unitId = "unit_$name",
        unitName = name,
        canView = false,
        postLocked = locked,
        viewLocked = locked,
    )

    private val page = GridPage(
        columns = listOf("catering_label", "deal_memo_label"),
        rows = listOf(
            GridRow(
                subject = GridSubject("u1", "Vidya Pixel"),
                cells = mapOf(
                    "catering_label" to cell("catering_label"),
                    "deal_memo_label" to cell("deal_memo_label"),
                ),
            ),
        ),
        total = 1,
    )

    private class Fake(
        private val answer: ZillitResult<Unit> = ZillitResult.Success(Unit),
        private val page: GridPage,
    ) : PermissionGridRepository {
        val writes = mutableListOf<Triple<String, AccessKind, Boolean>>()
        override suspend fun load(axis: GridAxis, section: GridSection, page: Int, limit: Int) =
            ZillitResult.Success(this.page)
        override suspend fun setAccess(
            axis: GridAxis,
            section: GridSection,
            entityId: String,
            unitId: String,
            kind: AccessKind,
            enable: Boolean,
        ): ZillitResult<Unit> {
            writes += Triple(unitId, kind, enable)
            return answer
        }
    }

    private val editor = PermissionGridViewer.from(
        ProjectPermissions(
            listOf(ToolAccess("permission_grid_tool", canView = true, canPost = true)),
        ),
    )

    private fun vm(repo: PermissionGridRepository) = PermissionGridViewModel(repo).also {
        it.onEvent(PermissionGridEvent.Start(editor))
    }

    @Test
    fun `a granted right sticks once the server agrees`() = runTest(dispatcher) {
        val repo = Fake(page = page)
        val cut = vm(repo)
        runCurrent()

        cut.onEvent(
            PermissionGridEvent.Toggle("u1", "catering_label", AccessKind.View, enable = true),
        )
        runCurrent()

        val after = cut.currentState.grid.rows.single().cells.getValue("catering_label")
        assertTrue(after.canView)
        assertFalse(after.busy, "the cell is released when the write lands")
        assertEquals(Triple("unit_catering_label", AccessKind.View, true), repo.writes.first())
    }

    @Test
    fun `a refusal leaves the box where it was and says why`() = runTest(dispatcher) {
        val refused = Fake(
            answer = ZillitResult.Failure(ZillitError.Http(200, serverMessage = "not_allowed")),
            page = page,
        )
        val cut = vm(refused)
        runCurrent()

        cut.onEvent(
            PermissionGridEvent.Toggle("u1", "catering_label", AccessKind.View, enable = true),
        )
        runCurrent()

        val after = cut.currentState.grid.rows.single().cells.getValue("catering_label")
        assertFalse(after.canView, "the server said no, so nothing moved")
        assertFalse(after.busy)
        assertNotNull(cut.currentState.notice)
    }

    @Test
    fun `a locked right is never written`() = runTest(dispatcher) {
        val lockedPage = page.copy(
            rows = listOf(
                page.rows.single().let { row ->
                    row.copy(cells = mapOf("catering_label" to cell("catering_label", locked = true)))
                },
            ),
        )
        val repo = Fake(page = lockedPage)
        val cut = vm(repo)
        runCurrent()

        cut.onEvent(
            PermissionGridEvent.Toggle("u1", "catering_label", AccessKind.View, enable = true),
        )
        runCurrent()

        // The backend cascades these itself and rejects a client that writes
        // them — the flash-checked-then-revert the web used to show.
        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun `a reader cannot write at all`() = runTest(dispatcher) {
        val repo = Fake(page = page)
        val readOnly = PermissionGridViewer.from(
            ProjectPermissions(listOf(ToolAccess("permission_grid_tool", canView = true))),
        )
        val cut = PermissionGridViewModel(repo)
        cut.onEvent(PermissionGridEvent.Start(readOnly))
        runCurrent()

        cut.onEvent(
            PermissionGridEvent.Toggle("u1", "catering_label", AccessKind.View, enable = true),
        )
        runCurrent()

        assertFalse(cut.currentState.canEdit)
        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun `a deal memo you may see is one you may take away`() = runTest(dispatcher) {
        val repo = Fake(page = page)
        val cut = vm(repo)
        runCurrent()

        cut.onEvent(
            PermissionGridEvent.Toggle("u1", "deal_memo_label", AccessKind.View, enable = true),
        )
        runCurrent()

        // ZL-16376 — view implies download on this one tool, and the backend
        // does not do it for us.
        assertEquals(
            listOf(AccessKind.View, AccessKind.Download),
            repo.writes.map { it.second },
        )
        assertTrue(cut.currentState.grid.rows.single().cells.getValue("deal_memo_label").canDownload)
    }

    @Test
    fun `switching axis starts at page one with an empty grid`() = runTest(dispatcher) {
        val cut = vm(Fake(page = page))
        runCurrent()
        cut.onEvent(PermissionGridEvent.GoToPage(1))

        cut.onEvent(PermissionGridEvent.SelectAxis(GridAxis.Departments))
        runCurrent()

        assertEquals(GridAxis.Departments, cut.currentState.axis)
        assertEquals(1, cut.currentState.page)
        assertNull(cut.currentState.error)
    }
}
