package com.zillit.desktop.feature.permissiongrid

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridPage
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridCell
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSubject
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer
import com.zillit.desktop.feature.permissiongrid.domain.SubjectColumn
import com.zillit.desktop.feature.permissiongrid.ui.DefaultGridScreen
import com.zillit.desktop.feature.permissiongrid.ui.DefaultGridState
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridScreen
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridUiState
import kotlin.test.Test

/**
 * Composes the real grid. A matrix that throws while laying out, or an access
 * gate that shows the wrong face, is invisible to the model tests.
 */
@OptIn(ExperimentalTestApi::class)
class PermissionGridRenderTest {

    private fun viewer(canPost: Boolean, admin: Boolean = false) = PermissionGridViewer.from(
        ProjectPermissions(
            listOf(ToolAccess("permission_grid_tool", canView = true, canPost = canPost)),
            isAdmin = admin,
        ),
    )

    private val page = GridPage(
        columns = listOf("catering_label", "drive_label"),
        rows = listOf(
            GridRow(
                GridSubject("u1", "Vidya Pixel", department = "Direction", designation = "AD", isAdmin = true),
                mapOf(
                    "catering_label" to GridCell("c1", "catering_label", canView = true),
                    "drive_label" to GridCell("d1", "drive_label"),
                ),
            ),
            GridRow(
                GridSubject("u2", "Sahil K"),
                mapOf("catering_label" to GridCell("c1", "catering_label")),
            ),
        ),
        total = 2,
        subjects = listOf(SubjectColumn.User, SubjectColumn.Department),
    )

    private fun state(canPost: Boolean = true, admin: Boolean = false) =
        PermissionGridUiState(viewer = viewer(canPost, admin), grid = page)

    @Test
    fun `the toolbar carries the web's controls`() {
        runComposeUiTest {
            setContent { ZillitTheme { PermissionGridScreen(state = state(), onEvent = {}) } }

            onNodeWithText("Home").assertExists()
            onNodeWithText("Based on Crew List").assertExists()
            onNodeWithText("View Default Grid").assertExists()
        }
    }

    @Test
    fun `the table draws people, their departments and the tool columns`() {
        runComposeUiTest {
            setContent { ZillitTheme { PermissionGridScreen(state = state(), onEvent = {}) } }

            onNodeWithText("Vidya Pixel").assertExists()
            onNodeWithText("Sahil K").assertExists()
            onNodeWithText("Direction").assertExists()
            onNodeWithText("AD").assertExists()
            onNodeWithText("ADMIN").assertExists()
            // Column heads, humanised from their label keys.
            onNodeWithText("Catering").assertExists()
            onNodeWithText("Drive").assertExists()
            // Three cells carry boxes; each stacks Viewing, Download, Posting.
            onAllNodesWithText("Viewing").assertCountEquals(3)
            onAllNodesWithText("Posting").assertCountEquals(3)
            onNodeWithText("1-2 of 2").assertExists()
        }
    }

    @Test
    fun `a tool the subject cannot be granted shows nothing to click`() {
        runComposeUiTest {
            setContent { ZillitTheme { PermissionGridScreen(state = state(), onEvent = {}) } }

            // Sahil has no drive cell at all — one em dash, not three boxes.
            onAllNodesWithText("—").assertCountEquals(1)
        }
    }

    @Test
    fun `an admin on the crew-list axis is told where the order comes from`() {
        runComposeUiTest {
            setContent {
                ZillitTheme { PermissionGridScreen(state = state(admin = true), onEvent = {}, onOpenListingOrder = {}) }
            }

            onNodeWithText("Click Here").assertExists()
        }
    }

    @Test
    fun `the banner is for the crew-list axis only`() {
        runComposeUiTest {
            val users = state(admin = true).copy(axis = GridAxis.Users)
            setContent {
                ZillitTheme { PermissionGridScreen(state = users, onEvent = {}, onOpenListingOrder = {}) }
            }

            onAllNodesWithText("Click Here").assertCountEquals(0)
        }
    }

    @Test
    fun `no viewing rights is a closed door, not an empty grid`() {
        runComposeUiTest {
            val denied = PermissionGridUiState(
                viewer = PermissionGridViewer.from(
                    ProjectPermissions(listOf(ToolAccess("permission_grid_tool"))),
                ),
            )
            setContent { ZillitTheme { PermissionGridScreen(state = denied, onEvent = {}) } }

            onNodeWithText("No access").assertExists()
        }
    }

    @Test
    fun `the default grid groups each tool's three rights`() {
        runComposeUiTest {
            val defaults = DefaultGridState(
                grid = DefaultGridPage(
                    first = "department_label",
                    columns = listOf("catering_label"),
                    rows = listOf(DefaultGridRow("d1", "Camera", listOf(GridCell("c1", "catering_label")))),
                ),
            )
            val withDefaults = state().copy(defaults = defaults)
            setContent { ZillitTheme { DefaultGridScreen(state = withDefaults, onEvent = {}, onBack = {}) } }

            onNodeWithText("Camera").assertExists()
            onNodeWithText("Catering").assertExists()
            onNodeWithText("Download Excel").assertExists()
            onNodeWithText("Department Permissions").assertExists()
        }
    }
}
