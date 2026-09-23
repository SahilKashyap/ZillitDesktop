package com.zillit.desktop.feature.drive

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.ui.DriveScreen
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.ItemMenuState
import kotlin.test.Test
import kotlin.test.assertTrue

/** The row menu drops from the pointer, and flips above it only when the listing ends beneath. */
@OptIn(ExperimentalTestApi::class)
class DriveMenuPlacementTest {
    private val admin = DriveViewer(
        "u1", "Ada", canView = true, canPost = true, canDownload = true, isAdmin = true, ready = true,
    )
    private val file = DriveItem(
        id = "x", kind = DriveItemKind.File, name = "note.txt", extension = "txt",
        parentFolderId = "p", createdById = "u1", uploadedById = "u1",
    )
    private val state = DriveUiState(
        viewer = admin,
        section = DriveSection.MyDrive,
        folderId = "p",
        listing = DriveListing(folders = listOf(DriveItem("p", DriveItemKind.Folder, "Parent")), files = listOf(file)),
    )

    @Test
    fun `the menu opens at the pointer when there is room beneath`() = runComposeUiTest {
        val opened = state.copy(menu = ItemMenuState(file, 300f, 250f))
        setContent { ZillitTheme { DriveScreen(state = opened, onEvent = {}) } }
        val row = onNodeWithText("Edit Info").fetchSemanticsNode().boundsInWindow
        assertTrue(row.top in 250f..420f && row.left in 300f..360f, "menu row at $row")
    }

    @Test
    fun `the menu opens at the pointer on a retina window too`() = runSkikoComposeUiTest(
        size = Size(2600f, 1560f),
        density = Density(2f),
    ) {
        val opened = state.copy(menu = ItemMenuState(file, 900f, 300f))
        setContent { ZillitTheme { DriveScreen(state = opened, onEvent = {}) } }
        val row = onNodeWithText("Edit Info").fetchSemanticsNode().boundsInWindow
        assertTrue(row.top in 300f..700f && row.left in 900f..1000f, "menu row at $row")
    }

    @Test
    fun `the menu flips above the pointer near the bottom`() = runComposeUiTest {
        val current = mutableStateOf(state)
        setContent { ZillitTheme { DriveScreen(state = current.value, onEvent = {}) } }
        val height = onRoot().fetchSemanticsNode().boundsInWindow.height
        current.value = state.copy(menu = ItemMenuState(file, 300f, height - 40f))
        waitForIdle()
        val row = onNodeWithText("Delete").fetchSemanticsNode().boundsInWindow
        assertTrue(row.bottom <= height - 40f + 1f, "menu row at $row for height $height")
    }
}
