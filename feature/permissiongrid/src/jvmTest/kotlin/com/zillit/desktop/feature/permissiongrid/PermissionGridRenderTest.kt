package com.zillit.desktop.feature.permissiongrid

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.permissiongrid.domain.GridCell
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSubject
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridScreen
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridUiState
import kotlin.test.Test

/**
 * Composes the real grid. A matrix that throws while laying out, or an access
 * gate that shows the wrong face, is invisible to the model tests.
 */
@OptIn(ExperimentalTestApi::class)
class PermissionGridRenderTest {

    private fun viewer(canPost: Boolean) = PermissionGridViewer.from(
        ProjectPermissions(
            listOf(ToolAccess("permission_grid_tool", canView = true, canPost = canPost)),
        ),
    )

    private val page = GridPage(
        columns = listOf("catering_label", "drive_label"),
        rows = listOf(
            GridRow(
                GridSubject("u1", "Vidya Pixel", department = "Direction", designation = "AD"),
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
    )

    private fun state(canPost: Boolean = true) =
        PermissionGridUiState(viewer = viewer(canPost), grid = page)

    @Test
    fun `the matrix draws its subjects and its tool columns`() {
        runComposeUiTest {
            setContent { ZillitTheme { PermissionGridScreen(state = state(), onEvent = {}) } }

            onNodeWithText("Vidya Pixel").assertExists()
            onNodeWithText("Sahil K").assertExists()
            onNodeWithText("Direction · AD").assertExists()
            // Column heads, humanised from their label keys.
            onNodeWithText("Catering").assertExists()
            onNodeWithText("Drive").assertExists()
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
    fun `a reader is told why the boxes do not move`() {
        runComposeUiTest {
            setContent {
                ZillitTheme { PermissionGridScreen(state = state(canPost = false), onEvent = {}) }
            }

            onNodeWithText(
                "You can see this grid but not change it — posting rights on the " +
                    "permission grid tool are what allow an edit.",
            ).assertExists()
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
}
