package com.zillit.desktop.core.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A window's title is stored when it opens, so a language change has to ask
 * the provider again — otherwise "Home" stays "Home" under a French frame.
 */
class RefreshTitlesTest {

    private class LanguageTool(var language: String) : ToolProvider {
        override val path = "/home"
        override val title: String get() = if (language == "fr") "Accueil" else "Home"
        override val icon: ImageVector = ZillitIcons.Home

        @Composable
        override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) = Unit
    }

    @Test
    fun `refresh re-asks every provider for its title`() {
        val tool = LanguageTool("en")
        val viewModel = WorkspaceViewModel(
            registry = ToolRegistry(listOf(tool)),
            sessionStore = InMemoryWorkspaceSessionStore(),
            idGenerator = { "w1" },
        )
        viewModel.onEvent(WorkspaceEvent.Open(WorkspaceRoute.Tool("/home")))
        assertEquals("Home", viewModel.state.value.windows.single().title)

        tool.language = "fr"
        viewModel.onEvent(WorkspaceEvent.RefreshTitles)

        assertEquals("Accueil", viewModel.state.value.windows.single().title)
    }
}
