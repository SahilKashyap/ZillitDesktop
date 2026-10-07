package com.zillit.desktop.feature.budgetbuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.workspace.WindowId
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer
import com.zillit.desktop.feature.budgetbuilder.ui.BUDGET_BUILDER_PATH
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderEvent
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderToolProvider
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The way out of a tool that has no chrome of its own.
 *
 * Budget Builder is given the whole window — no page header, no strip above
 * it — on the strength of one thing: the application's own "← Film Tools"
 * button, which posts `zillit:exit`. The web answers that by leaving the
 * route; here the window closes. If this hop is broken the user is left
 * inside a full-bleed application with no visible way back, so it is worth a
 * test of its own.
 *
 * The page→host half (the button posting `zillit:exit`, the injected bridge
 * forwarding it to the gateway) is covered by [BudgetBuilderGatewayTest] and
 * was watched end to end in a real browser. This covers the half after it:
 * the event reaching the window navigator.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetBuilderExitTest {

    /** A window that records what the tool asks of it. */
    private class RecordingNavigator : WindowNavigator {
        var closes = 0
            private set
        val navigations = mutableListOf<WorkspaceRoute>()

        override val windowId = WindowId("budget-builder-window")
        override val canGoBack = false

        override fun navigate(route: WorkspaceRoute) {
            navigations += route
        }

        override fun back() = Unit
        override fun openInNewWindow(route: WorkspaceRoute) = Unit
        override fun setTitle(title: String) = Unit
        override fun setDirty(dirty: Boolean) = Unit

        override fun close() {
            closes++
        }
    }

    private fun viewModel() = BudgetBuilderViewModel(
        resolveViewer = { BudgetBuilderViewer(canView = true, canPost = true, ready = true) },
        configured = true,
    )

    @Test
    fun `the application's exit closes the tool window`() = runComposeUiTest {
        val vm = viewModel()
        val navigator = RecordingNavigator()
        val provider = BudgetBuilderToolProvider(vm) { Box(Modifier.fillMaxSize().testTag(APPLICATION)) }

        setContent {
            ZillitTheme(darkTheme = false) {
                provider.Content(WorkspaceRoute.Tool(BUDGET_BUILDER_PATH), navigator)
            }
        }

        // The application is on screen, and nothing has been closed yet: the
        // effect collector must be subscribed before the event is sent, or a
        // replay-less flow would drop it and this test would pass for the
        // wrong reason.
        onNodeWithTag(APPLICATION).assertExists()
        assertEquals(0, navigator.closes, "nothing closes until the application asks")

        vm.onEvent(BudgetBuilderEvent.ExitRequested)
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) { navigator.closes == 1 }

        assertEquals(1, navigator.closes)
        assertTrue(navigator.navigations.isEmpty(), "leaving is closing the window, not routing it elsewhere")
    }

    /**
     * The tool is the application: the provider hands its whole content area
     * to the injected surface rather than wrapping it in chrome of our own.
     */
    @Test
    fun `an entitled viewer is given the application itself`() = runComposeUiTest {
        val provider = BudgetBuilderToolProvider(viewModel()) {
            Box(Modifier.fillMaxSize().testTag(APPLICATION))
        }

        setContent {
            ZillitTheme(darkTheme = false) {
                provider.Content(WorkspaceRoute.Tool(BUDGET_BUILDER_PATH), RecordingNavigator())
            }
        }

        onNodeWithTag(APPLICATION).assertExists()
    }

    private companion object {
        const val APPLICATION = "budget-builder-application"
        const val TIMEOUT_MILLIS = 5_000L
    }
}
