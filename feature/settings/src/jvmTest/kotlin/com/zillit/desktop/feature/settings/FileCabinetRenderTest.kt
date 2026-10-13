package com.zillit.desktop.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.cabinet.CabinetViewers
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.settings.admin.domain.CabinetModule
import com.zillit.desktop.feature.settings.admin.domain.DownloadRequest
import com.zillit.desktop.feature.settings.admin.domain.DownloadStatus
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminScreen
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState
import com.zillit.desktop.feature.settings.admin.ui.FileCabinetState
import com.zillit.desktop.feature.settings.ui.ProductionFacts
import kotlin.test.Test

/** The File Cabinet page composed for real: browse and select modes, a viewer window, the unzip steps. */
@OptIn(ExperimentalTestApi::class)
class FileCabinetRenderTest {

    /** A viewer with no network: a row that opens directly, or one that expands into two entries. */
    private class FakeViewer(
        override val identifier: String,
        override val expands: Boolean = false,
    ) : CabinetViewer {
        override suspend fun entries() = listOf(CabinetEntry("a", "Unit A"), CabinetEntry("b", "Unit B"))

        @Composable
        override fun Content(entry: CabinetEntry?, modifier: Modifier) {
            ZillitText(text = "viewing ${entry?.name ?: identifier}", modifier = modifier)
        }
    }

    private val modules = listOf(
        CabinetModule("info", "Info"),
        CabinetModule("home", "Home"),
        CabinetModule("recce", "Recce"),
    )

    private fun state(cabinet: FileCabinetState) = AdminUiState(
        destination = AdminDestination.FileCabinet,
        hasLoaded = true,
        fileCabinet = cabinet,
    )

    private fun render(
        cabinet: FileCabinetState,
        viewers: CabinetViewers = CabinetViewers.None,
        assert: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            ZillitTheme {
                AdminScreen(
                    destination = AdminDestination.FileCabinet,
                    state = state(cabinet),
                    onEvent = {},
                    onBack = {},
                    production = ProductionFacts(name = "Feature One"),
                    cabinetViewers = viewers,
                )
            }
        }
        assert()
    }

    @Test
    fun `browse mode lists the rows and offers Select`() {
        render(FileCabinetState(modules = modules)) {
            onNodeWithText("Info").assertExists()
            onNodeWithText("Home").assertExists()
            onNodeWithText("Select").assertExists()
        }
    }

    @Test
    fun `a row with a viewer opens it in a window`() {
        render(FileCabinetState(modules = modules), CabinetViewers(listOf(FakeViewer("info")))) {
            onNodeWithText("Info").performClick()
            waitForIdle()
            onNodeWithText("viewing info").assertExists()
        }
    }

    @Test
    fun `a row that expands lists its entries, and an entry opens the viewer`() {
        render(FileCabinetState(modules = modules), CabinetViewers(listOf(FakeViewer("home", expands = true)))) {
            onNodeWithText("Home").performClick()
            waitForIdle()
            onNodeWithText("Unit B").assertExists()
            onNodeWithText("Unit B").performClick()
            waitForIdle()
            onNodeWithText("viewing Unit B").assertExists()
        }
    }

    @Test
    fun `select mode shows Select All and the count`() {
        render(FileCabinetState(modules = modules, selecting = true, selected = setOf("info"))) {
            onNodeWithText("Select All").assertExists()
            onNodeWithText("Request Download").assertExists()
            onNodeWithText("1 Selected").assertExists()
        }
    }

    @Test
    fun `a completed request offers the unzip steps and the download`() {
        render(FileCabinetState(modules = modules, request = DownloadRequest("r", DownloadStatus.Completed))) {
            onNodeWithText("Download").assertExists()
            onNodeWithText("Steps to download").performClick()
            waitForIdle()
            onNodeWithText("FOR PC COMPUTERS:").assertExists()
            onNodeWithText("FOR APPLE COMPUTERS:").assertExists()
        }
    }
}
