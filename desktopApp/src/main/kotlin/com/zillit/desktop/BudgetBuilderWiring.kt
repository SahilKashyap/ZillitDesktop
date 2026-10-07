package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderEvent
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderToolProvider
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderViewModel

/**
 * Whether this environment carries both halves of the tool: the service API
 * and the web deployment that serves the page. Without either, the window
 * shows the web's "isn't configured" notice instead of a blank browser.
 */
internal fun AppGraph.Ready.budgetBuilderConfigured(): Boolean =
    config.services.containsKey(ZillitService.BudgetBuilder) &&
        config.services.containsKey(ZillitService.BudgetBuilderWeb)

/**
 * Budget Builder as a workspace tool, with its embedded application.
 *
 * The provider is handed a composable rather than a launcher: the tool *is*
 * the application now, filling the tool's own window as it fills the web's
 * content area — see [BudgetBuilderHost] for why there is no separate frame
 * any more.
 */
internal fun AppGraph.Ready.budgetBuilderProvider(
    viewModel: BudgetBuilderViewModel,
    host: BudgetBuilderHost,
): BudgetBuilderToolProvider = BudgetBuilderToolProvider(
    viewModel = viewModel,
    application = {
        // The application's own "← Film Tools" button travels as an event, so
        // the decision to leave is made in one testable place.
        LaunchedEffect(host, viewModel) {
            host.exits.collect { viewModel.onEvent(BudgetBuilderEvent.ExitRequested) }
        }
        BudgetBuilderPane(host)
    },
)

/**
 * The Chromium surface, or what is standing in for it.
 *
 * Mirrors `MapCanvasPane`: a `SwingPanel` because JCEF renders into a
 * heavyweight AWT component, arriving asynchronously because Chromium takes
 * seconds to come up — a spinner holds the pane until it exists.
 */
@Composable
private fun BudgetBuilderPane(host: BudgetBuilderHost) {
    // Chromium starts on first use, not at app launch: most sessions never
    // open this tool, and the runtime may already be up for calling.
    LaunchedEffect(host) { host.open() }

    val component by host.surface.collectAsState()
    val failure by host.failure.collectAsState()
    val awtComponent = component

    Box(
        modifier = Modifier.fillMaxSize().background(ZillitTheme.colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failure != null -> ZillitText(
                text = failure.orEmpty(),
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.padding(ZillitTheme.spacing.xl),
            )

            awtComponent == null -> ZillitSpinner()

            else -> {
                // Handed back when the pane leaves the screen: the host parks
                // the component in a hidden window of its own, because a
                // browser component left with no parent is a browser that will
                // not work the next time the tool opens. The budget, and any
                // unsaved edit in it, survives the parking untouched.
                DisposableEffect(awtComponent) {
                    val lease = host.hostSurface()
                    onDispose { lease.release() }
                }
                SwingPanel(factory = { awtComponent }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
