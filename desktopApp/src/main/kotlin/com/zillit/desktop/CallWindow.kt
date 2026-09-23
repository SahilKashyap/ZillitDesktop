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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zillit.desktop.feature.calls.ui.CallOverlay
import com.zillit.desktop.feature.calls.ui.CallViewModel
import com.zillit.desktop.feature.calls.ui.headerTitle
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
internal fun ApplicationScope.CallWindow(
    ready: AppGraph.Ready,
    calls: CallViewModel?,
    darkTheme: Boolean,
) {
    calls ?: return
    val state by calls.state.collectAsState()
    // No video gate: an audio call gets a window too. The surface draws
    // avatars when there is no picture, and a call the user cannot see is
    // exactly the thing this window exists to prevent.
    if (!state.pipOpen) return

    val compact = state.pipCompact
    val windowState = rememberCallWindowState(compact)
    Window(
        onCloseRequest = { calls.onEvent(CallEvent.TogglePip) },
        state = windowState,
        title = state.headerTitle.ifBlank { str(S.desktop_zillit_call) },
        // Only the thumbnail floats. A full call window that forced itself
        // over everything would be the one thing nobody could get out of the
        // way while reading the document they joined the call to discuss.
        alwaysOnTop = compact,
        resizable = true,
    ) {
        ZillitTheme(darkTheme = darkTheme) {
            val colors = ZillitTheme.colors
            AvatarFaces(ready) {
                Box(Modifier.fillMaxSize().background(colors.canvas)) {
                    if (compact) {
                        Column(Modifier.fillMaxSize()) {
                            PipStrip(state, calls)
                            // The picture. The same single component the stage
                            // hosts; its factory hands it over and its disposal
                            // parks it again, so the round trip out and back is
                            // two re-parents, not a rebuild — never a dropped call.
                            Box(Modifier.fillMaxSize().background(Color.Black)) {
                                callVideoSurface(ready)?.invoke()
                            }
                        }
                    } else {
                        // The whole calling surface, in its own window: stage,
                        // controls, roster and add-people, exactly as the overlay
                        // drew them inside the app.
                        CallOverlay(
                            state = state,
                            onEvent = calls::onEvent,
                            loadAvatar = crewFaceLoader(ready),
                            videoSurface = callVideoSurface(ready),
                            // This window IS the call: it draws the stage and it
                            // holds the browser component while it is open.
                            ownsCall = true,
                        )
                    }
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
        PipControls(state, calls)
    }
}

/** The thumbnail strip's buttons — the ways back, and the two that end or mute. */
@Composable
private fun PipControls(state: com.zillit.desktop.feature.calls.ui.CallUiState, calls: CallViewModel) {
    val colors = ZillitTheme.colors
    // The way out of the thumbnail, always drawn.
    //
    // It used to appear only when there was unread chat, which made the
    // shrunk window a one-way door for anyone not being messaged: the only
    // other control that looked like a way back is Restore below, and that
    // is a different destination — it re-homes the call into the main
    // window rather than growing this one.
    if (state.pipCompact) {
        ZillitIconButton(
            icon = ZillitIcons.Maximize,
            contentDescription = str(S.desktop_back_to_call_window),
            onClick = { calls.onEvent(CallEvent.ToggleCallCompact) },
            tint = colors.textPrimary,
            size = PIP_BUTTON,
        )
    }
    // Compact hides the chat panel, so this is the only sign a line
    // arrived. Growing the window is how it gets read.
    if (state.chatUnread > 0) {
        ZillitIconButton(
            icon = ZillitIcons.Chat,
            contentDescription = "${state.chatUnread} unread in call chat",
            onClick = {
                // Grows the window *and* opens the panel: one click on an
                // unread badge should end with the message on screen.
                calls.onEvent(CallEvent.ToggleCallCompact)
                if (!state.chatOpen) calls.onEvent(CallEvent.ToggleChat)
            },
            tint = colors.accent,
            size = PIP_BUTTON,
        )
    }
    ZillitIconButton(
        icon = if (state.micMuted) ZillitIcons.MicOff else ZillitIcons.Mic,
        contentDescription = if (state.micMuted) str(S.desktop_unmute) else str(S.desktop_mute),
        onClick = { calls.onEvent(CallEvent.ToggleMic) },
        tint = if (state.micMuted) colors.danger else colors.textPrimary,
        size = PIP_BUTTON,
    )
    ZillitIconButton(
        icon = ZillitIcons.PhoneDown,
        contentDescription = str(S.desktop_end_call),
        onClick = { calls.onEvent(CallEvent.HangUp) },
        tint = colors.danger,
        size = PIP_BUTTON,
    )
    ZillitIconButton(
        icon = ZillitIcons.Restore,
        // Named for where it goes, because the button above it also
        // brings the call back and the two used to read identically.
        contentDescription = str(S.desktop_move_call_into_window),
        onClick = { calls.onEvent(CallEvent.TogglePip) },
        tint = colors.textPrimary,
        size = PIP_BUTTON,
    )
}

/**
 * Bottom-right of the main display, small, and remembered for the session.
 *
 * `rememberWindowState` alone would forget the position each time the window
 * is recreated (it is, whenever PiP toggles); the app-level holder keeps the
 * last size and place across those recreations.
 */
@Composable
private fun rememberCallWindowState(compact: Boolean): WindowState {
    // Built once with the mode it opened in. `rememberWindowState` reads its
    // arguments on first composition only, so a later toggle has to move the
    // window by assigning to the state — passing different arguments changes
    // nothing, which is how "Shrink to thumbnail" restyled the chrome and left
    // a 960x640 window sitting there.
    val state = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = if (compact) {
            pipLastPosition ?: WindowPosition.Aligned(Alignment.BottomEnd)
        } else {
            WindowPosition.Aligned(Alignment.Center)
        },
        size = if (compact) pipLastSize else DpSize(CALL_DEFAULT_WIDTH, CALL_DEFAULT_HEIGHT),
    )

    // Skips the first pass: the state was just built for this mode, and
    // re-assigning would throw away a position the user had already dragged to.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(compact) {
        if (!settled) {
            settled = true
            return@LaunchedEffect
        }
        if (compact) {
            state.size = pipLastSize
            state.position = pipLastPosition ?: WindowPosition.Aligned(Alignment.BottomEnd)
        } else {
            state.size = DpSize(CALL_DEFAULT_WIDTH, CALL_DEFAULT_HEIGHT)
            state.position = WindowPosition.Aligned(Alignment.Center)
        }
    }
    // Written back as it moves so the next popout lands where this one was left.
    LaunchedEffect(state, compact) {
        if (!compact) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { state.position to state.size }
            .collect { (position, size) ->
                if (position is WindowPosition.Absolute) pipLastPosition = position
                pipLastSize = size
            }
    }
    return state
}

/** A call window opens big enough to hold a grid and its controls. */
private val CALL_DEFAULT_WIDTH = 960.dp
private val CALL_DEFAULT_HEIGHT = 640.dp
private val PIP_DEFAULT_WIDTH = 360.dp
private val PIP_DEFAULT_HEIGHT = 240.dp
private val PIP_BAR_HEIGHT = 36.dp
private val PIP_BUTTON = 26.dp

/** Where the popout was last, for the length of the process. */
private var pipLastPosition: WindowPosition? = null
private var pipLastSize: DpSize = DpSize(PIP_DEFAULT_WIDTH, PIP_DEFAULT_HEIGHT)
