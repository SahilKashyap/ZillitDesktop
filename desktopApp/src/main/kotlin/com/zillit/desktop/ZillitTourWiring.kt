package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.feature.settings.ui.ZILLIT_TOUR_URL
import com.zillit.desktop.feature.settings.ui.ZillitTourToolProvider
import com.zillit.desktop.feature.settings.ui.ZillitTourUnavailable

/**
 * The Zillit Tour as a workspace tool, with the hosted tour inside it.
 *
 * The provider is handed a composable rather than a URL: the browser is the
 * host's to own, as Budget Builder's is, and the settings module has no way
 * to build an AWT surface.
 */
internal fun zillitTourProvider(
    host: ZillitTourHost,
    onOpenExternal: (String) -> Unit,
): ZillitTourToolProvider = ZillitTourToolProvider(
    tour = { ZillitTourPane(host, onOpenExternal) },
)

/**
 * The Chromium surface, or what is standing in for it.
 *
 * Mirrors `BudgetBuilderPane`: a `SwingPanel` because JCEF renders into a
 * heavyweight AWT component, arriving asynchronously because Chromium takes
 * seconds to come up — a spinner holds the pane until it exists, and a build
 * with no embedded browser gets the page's public address instead.
 */
@Composable
private fun ZillitTourPane(host: ZillitTourHost, onOpenExternal: (String) -> Unit) {
    // Chromium starts on first use, not at app launch: most sessions never
    // open the tour, and the runtime may already be up for calling.
    LaunchedEffect(host) { host.open() }

    val component by host.surface.collectAsState()
    val failure by host.failure.collectAsState()
    val awtComponent = component

    Box(
        modifier = Modifier.fillMaxSize().background(ZillitTheme.colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failure != null -> ZillitTourUnavailable(
                reason = failure.orEmpty(),
                onOpenInBrowser = { onOpenExternal(ZILLIT_TOUR_URL) },
            )

            awtComponent == null -> ZillitSpinner()

            else -> {
                // Handed back when the pane leaves the screen: the host parks
                // the component in a hidden window of its own, because a
                // browser component left with no parent is a browser that will
                // not work the next time the tour opens.
                DisposableEffect(awtComponent) {
                    val lease = host.hostSurface()
                    onDispose { lease.release() }
                }
                SwingPanel(factory = { awtComponent }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
