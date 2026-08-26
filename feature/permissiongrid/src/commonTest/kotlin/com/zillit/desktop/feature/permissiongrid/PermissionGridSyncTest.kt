package com.zillit.desktop.feature.permissiongrid

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.permissiongrid.data.RightsSyncDto
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridCell
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSection
import com.zillit.desktop.feature.permissiongrid.domain.GridSubject
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridRepository
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer
import com.zillit.desktop.feature.permissiongrid.domain.RightsSync
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridEvent
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The socket's rights-sync events land in an open grid (ZL-17812): present
 * flags override, absent flags keep the cell, the busy flag clears, and a
 * row the event cannot name is untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionGridSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // -- the merge ---------------------------------------------------------

    private val cell = GridCell(
        unitId = "u-cat",
        unitName = "catering_label",
        canView = true,
        canPost = false,
        canDownload = true,
        busy = true,
    )

    @Test
    fun `present flags land, absent flags keep the cell, busy clears`() {
        val merged = cell.syncedWith(
            RightsSync(userId = "u1", unitName = "catering_label", post = true, postUnlocked = false),
        )

        assertTrue(merged.canPost)
        assertTrue(merged.canView, "absent view_access must keep the cell's value")
        assertTrue(merged.canDownload)
        assertTrue(merged.locked(AccessKind.Post), "postingUpdatable=false locks the right")
        assertFalse(merged.locked(AccessKind.View))
        assertFalse(merged.busy, "the server's word clears the in-flight flag")
    }

    @Test
    fun `a page only changes where the event names a row and a tool`() {
        val page = GridPage(
            columns = listOf("catering_label"),
            rows = listOf(
                GridRow(GridSubject("u1", "Vidya"), mapOf("catering_label" to cell)),
                GridRow(GridSubject("u2", "Moto"), mapOf("catering_label" to cell)),
            ),
            total = 2,
        )

        val other = page.syncedWith(RightsSync(userId = "dept-9", unitName = "catering_label", view = false))
        assertEquals(page, other, "a department id matches no user row, as on the web")

        val synced = page.syncedWith(RightsSync(userId = "u2", unitName = "catering_label", view = false))
        assertTrue(synced.rows[0].cells.getValue("catering_label").canView)
        assertFalse(synced.rows[1].cells.getValue("catering_label").canView)
    }

    // -- the wire ----------------------------------------------------------

    @Test
    fun `the payload decodes with its camelCase gates, and half-named events drop`() {
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString(
            RightsSyncDto.serializer(),
            """{"project_id":"p1","user_id":"u1","unit_name":"drive_label",
               "posting_access":true,"postingUpdatable":false,"extra":1}""",
        )
        val sync = dto.toDomain()

        assertEquals("u1", sync?.userId)
        assertEquals(true, sync?.post)
        assertEquals(false, sync?.postUnlocked)
        assertNull(sync?.view, "absent keys must stay null, never default to false")
        assertNull(RightsSyncDto(userId = "u1").toDomain(), "no unit_name, nothing to apply")
    }

    // -- the view model ----------------------------------------------------

    @Test
    fun `an event lands in the open grid without a reload`() = runTest(dispatcher) {
        val events = MutableSharedFlow<RightsSync>()
        val fixture = GridPage(
            columns = listOf("catering_label"),
            rows = listOf(GridRow(GridSubject("u1", "Vidya"), mapOf("catering_label" to cell))),
            total = 1,
        )
        val model = PermissionGridViewModel(object : PermissionGridRepository {
            override val syncs: Flow<RightsSync> = events
            override suspend fun load(axis: GridAxis, section: GridSection, page: Int, limit: Int) =
                ZillitResult.Success(fixture)
            override suspend fun setAccess(
                axis: GridAxis, section: GridSection, entityId: String,
                unitId: String, kind: AccessKind, enable: Boolean,
            ) = ZillitResult.Success(Unit)
        })
        val viewer = PermissionGridViewer.from(
            ProjectPermissions(listOf(ToolAccess("permission_grid_tool", canView = true, canPost = true))),
        )

        model.onEvent(PermissionGridEvent.Start(viewer))
        runCurrent()
        assertTrue(model.state.value.grid.rows.single().cells.getValue("catering_label").canView)

        events.emit(RightsSync(userId = "u1", unitName = "catering_label", view = false))
        runCurrent()

        assertFalse(model.state.value.grid.rows.single().cells.getValue("catering_label").canView)
    }
}
