package com.zillit.desktop

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
import com.zillit.desktop.core.designsystem.component.ZillitSpinner

/**
 * The Chromium map page as the Maps tool's canvas slot, when the engine is
 * the real one.
 *
 * Mirrors [callVideoSurface]: a SwingPanel because JCEF renders into a
 * heavyweight AWT component, arriving asynchronously because Chromium takes
 * seconds to come up — a spinner holds the pane until it exists.
 */
internal fun mapCanvasSurface(ready: AppGraph.Ready): (@Composable () -> Unit)? {
    val engine = ready.mapCanvas as? KcefMapEngine ?: return null
    return { MapCanvasPane(engine) }
}

@Composable
private fun MapCanvasPane(engine: KcefMapEngine) {
    // Chromium starts on first use, not at app launch — the Maps tool is not
    // worth seconds of every startup, and the runtime may already be up.
    LaunchedEffect(engine) { engine.open() }
    val component by engine.surface.collectAsState()
    val awtComponent = component
    if (awtComponent == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
    } else {
        // Handed back when the pane leaves the screen: the engine parks the
        // component in a hidden window of its own, because a browser
        // component left with no parent is a browser that will not work the
        // next time the tool opens.
        DisposableEffect(awtComponent) {
            onDispose { engine.releaseSurface() }
        }
        SwingPanel(
            factory = { awtComponent },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
