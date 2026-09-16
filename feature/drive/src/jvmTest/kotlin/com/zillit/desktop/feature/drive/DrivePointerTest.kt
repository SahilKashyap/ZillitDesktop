package com.zillit.desktop.feature.drive

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveScreen
import com.zillit.desktop.feature.drive.ui.DriveUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The mouse gestures the table answers: select on click, the context menu
 * on a right click, and a drag onto a folder. Right-click regressed once
 * because desktop `awaitFirstDown` answers only the primary button.
 */
@OptIn(ExperimentalTestApi::class)
class DrivePointerTest {
    private val admin = DriveViewer(
        "u1", "Ada", canView = true, canPost = true, canDownload = true, isAdmin = true, ready = true,
    )
    private val folder = DriveItem(
        id = "f", kind = DriveItemKind.Folder, name = "Target", parentFolderId = "p", createdById = "u1",
    )
    private val file = DriveItem(
        id = "x", kind = DriveItemKind.File, name = "note.txt", extension = "txt",
        parentFolderId = "p", createdById = "u1", uploadedById = "u1",
    )
    private val state = DriveUiState(
        viewer = admin,
        section = DriveSection.MyDrive,
        folderId = "p",
        listing = DriveListing(
            folders = listOf(DriveItem("p", DriveItemKind.Folder, "Parent"), folder),
            files = listOf(file),
        ),
    )

    @Test
    fun `a left click on the row selects it`() = runComposeUiTest {
        val events = mutableListOf<DriveEvent>()
        setContent { ZillitTheme { DriveScreen(state = state, onEvent = { events += it }) } }
        onNodeWithText("note.txt").performMouseInput { click(center) }
        assertTrue(events.isNotEmpty(), "got $events")
    }

    @Test
    fun `a right click opens the menu`() = runComposeUiTest {
        val events = mutableListOf<DriveEvent>()
        setContent { ZillitTheme { DriveScreen(state = state, onEvent = { events += it }) } }
        onNodeWithText("note.txt").performMouseInput { rightClick(center) }
        assertTrue(events.any { it is DriveEvent.OpenMenu }, "got $events")
    }

    @Test
    fun `dragging a file by its name onto a folder asks to move it`() = dragCase { it.center }

    @Test
    fun `dragging a file by its empty columns onto a folder asks to move it`() =
        dragCase { Offset(it.right + 200f, it.center.y) }

    private fun dragCase(grabAt: (Rect) -> Offset) = runComposeUiTest {
        val events = mutableListOf<DriveEvent>()
        setContent { ZillitTheme { DriveScreen(state = state, onEvent = { events += it }) } }
        val target = onNodeWithText("Target").fetchSemanticsNode().boundsInRoot.center
        val source = onNodeWithText("note.txt").fetchSemanticsNode().boundsInRoot
        val grab = grabAt(source)
        onRoot().performMouseInput {
            moveTo(grab)
            press()
            repeat(6) { moveBy(Offset(0f, -12f)) }
        }
        // The chip that follows the pointer is a second node carrying the name.
        val chips = onAllNodesWithText("note.txt").fetchSemanticsNodes().size
        onRoot().performMouseInput {
            moveTo(target)
            release()
        }
        assertTrue(chips == 2, "no drag chip while dragging")
        assertTrue(events.any { it is DriveEvent.MoveTo }, "got $events")
    }
}
