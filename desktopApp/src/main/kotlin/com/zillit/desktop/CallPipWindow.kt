package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallViewModel
import com.zillit.desktop.feature.calls.ui.headerTitle

/**
 * The video call, out of the main window: a small always-on-top window the OS
 * owns, so the picture stays in view while Zillit is behind a browser, a
 * script, a spreadsheet — the whole reason someone pops a call out.
 *
 * ## One browser, one home
 *
 * The media engine is a single Chromium component and it can be parented in
 * exactly one place. While this window is up it holds that component; the
 * main window's overlay knows not to mount it (`pipOpen`) and shows the audio
 * pill instead. Closing this window — by its own controls or the OS close
 * button — hands the picture back, and the stage reopens where it was.
 *
 * Draggable by its title strip, resizable by the OS, and it remembers where
 * it was put for the rest of the session — a popout that snaps back to a
 * default corner on every call would be moved again every time.
 */
@Composable
internal fun ApplicationScope.CallPipWindow(
    ready: AppGraph.Ready,
    calls: CallViewModel?,
    darkTheme: Boolean,
) {
    calls ?: return
    val state by calls.state.collectAsState()
    if (!state.pipOpen || !state.videoMounted) return

    val windowState = rememberPipWindowState()
    Window(
        onCloseRequest = { calls.onEvent(CallEvent.TogglePip) },
        state = windowState,
        title = "Zillit call",
        alwaysOnTop = true,
        resizable = true,
    ) {
        ZillitTheme(darkTheme = darkTheme) {
            val colors = ZillitTheme.colors
            Column(Modifier.fillMaxSize().background(colors.canvas)) {
                PipStrip(state, calls)
                // The picture. The same single component the stage hosts;
                // its factory hands it over and its disposal parks it again,
                // so the round trip out and back is two re-parents, not a
                // rebuild — and never a dropped call.
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    callVideoSurface(ready)?.invoke()
                }
            }
        }
    }
}

/** The strip: who, how long, and the way back. Compose-drawn above the browser, never over it. */
@Composable
private fun PipStrip(state: com.zillit.desktop.feature.calls.ui.CallUiState, calls: CallViewModel) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PIP_BAR_HEIGHT)
            .background(colors.surfaceRaised)
            .padding(horizontal = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = state.headerTitle,
            style = ZillitTheme.typography.label,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = state.timerText,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
        )
        ZillitIconButton(
            icon = if (state.micMuted) ZillitIcons.MicOff else ZillitIcons.Mic,
            contentDescription = if (state.micMuted) "Unmute" else "Mute",
            onClick = { calls.onEvent(CallEvent.ToggleMic) },
            tint = if (state.micMuted) colors.danger else colors.textPrimary,
            size = PIP_BUTTON,
        )
        ZillitIconButton(
            icon = ZillitIcons.PhoneDown,
            contentDescription = "End call",
            onClick = { calls.onEvent(CallEvent.HangUp) },
            tint = colors.danger,
            size = PIP_BUTTON,
        )
        ZillitIconButton(
            icon = ZillitIcons.Restore,
            contentDescription = "Bring the call back into Zillit",
            onClick = { calls.onEvent(CallEvent.TogglePip) },
            tint = colors.textPrimary,
            size = PIP_BUTTON,
        )
    }
}

/**
 * Bottom-right of the main display, small, and remembered for the session.
 *
 * `rememberWindowState` alone would forget the position each time the window
 * is recreated (it is, whenever PiP toggles); the app-level holder keeps the
 * last size and place across those recreations.
 */
@Composable
private fun rememberPipWindowState(): WindowState {
    val state = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = pipLastPosition ?: WindowPosition.Aligned(Alignment.BottomEnd),
        size = pipLastSize,
    )
    // Written back as it moves so the next popout lands where this one was left.
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.position to state.size }
            .collect { (position, size) ->
                if (position is WindowPosition.Absolute) pipLastPosition = position
                pipLastSize = size
            }
    }
    return state
}

private val PIP_DEFAULT_WIDTH = 360.dp
private val PIP_DEFAULT_HEIGHT = 240.dp
private val PIP_BAR_HEIGHT = 36.dp
private val PIP_BUTTON = 26.dp

/** Where the popout was last, for the length of the process. */
private var pipLastPosition: WindowPosition? = null
private var pipLastSize: DpSize = DpSize(PIP_DEFAULT_WIDTH, PIP_DEFAULT_HEIGHT)
