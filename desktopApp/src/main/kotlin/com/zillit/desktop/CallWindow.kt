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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import java.awt.Dimension
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
    showMain: () -> Unit = {},
) {
    calls ?: return
    val state by calls.state.collectAsState()
    // Whenever a share is live, wherever the call is drawn.
    ScreenShareIndicator(state = state, calls = calls, darkTheme = darkTheme) {
        // Back to the call: its own window when it has one (raised, full
        // size), otherwise the main window with the stage expanded.
        if (state.pipOpen) {
            calls.onEvent(CallEvent.ToggleStage)
        } else {
            if (!state.expanded) calls.onEvent(CallEvent.ToggleStage)
            showMain()
        }
    }
    // No video gate: an audio call gets a window too. The surface draws
    // avatars when there is no picture, and a call the user cannot see is
    // exactly the thing this window exists to prevent.
    if (!callWindowAlive(state.pipOpen)) return

    val compact = state.pipCompact
    val windowState = rememberCallWindowState(compact)
    Window(
        onCloseRequest = { calls.onEvent(CallEvent.TogglePip) },
        state = windowState,
        // Hidden for the one frame between the contents going and the window
        // following, so the empty shell is never seen.
        visible = state.pipOpen,
        title = state.headerTitle.ifBlank { str(S.desktop_zillit_call) },
        // Only the thumbnail floats. A full call window that forced itself
        // over everything would be the one thing nobody could get out of the
        // way while reading the document they joined the call to discuss.
        alwaysOnTop = compact,
        resizable = true,
    ) {
        // Everything below — the stage, the strip and the browser the crash
        // above is about — goes as soon as the call does, a frame before the
        // window that holds it.
        if (!state.pipOpen) return@Window
        // The pill's expand button, pressed in the main window while the call
        // lives here: this window is the call, so it comes forward — out of
        // the Dock if it was minimised there, and full size (the view model
        // has already dropped the thumbnail). Zero is "never asked", so a
        // window opening for the first time does not jump the queue.
        LaunchedEffect(state.windowRaise) {
            if (state.windowRaise == 0) return@LaunchedEffect
            windowState.isMinimized = false
            window.toFront()
            window.requestFocus()
            com.zillit.desktop.core.common.ZillitLog.i("CallWindowing") { "raised the call window" }
        }
        CallWindowBehaviour(windowState, window, compact, calls)
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
                            loadGroupPicture = groupPictureLoader(ready),
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

/**
 * True while the call window should exist: for as long as the call is up, and
 * one frame longer.
 *
 * Dropping the window and its contents together — what `if (!state.pipOpen)
 * return` did — killed the UI on Windows every time a call ended:
 *
 *     IllegalStateException: SkiaLayer is disposed
 *       at SwingInteropViewGroup.invalidate
 *       at CefBrowserWr$3.removeCanvas … removeNotify
 *       at ComposeWindowPanel.removeNotify
 *
 * Compose disposes the scene and its `SkiaLayer`, then AWT walks the hierarchy
 * calling `removeNotify`; JCEF's canvas removal invalidates its parent chain,
 * that chain still reaches the dead scene, and the throw escapes onto the
 * event thread. The call was still connected with no way back to it, and the
 * process went on holding the single-instance lock — so Zillit could not be
 * reopened either. macOS never hit it: JCEF only takes this
 * heavyweight-canvas path on Windows.
 *
 * `callVideoSurface`'s `onDispose` exists to unparent the browser first, and
 * it was losing the race because the window's AWT teardown had already begun.
 * Holding the window one frame past its contents gives that disposal the
 * ordering it was written to assume: the browser leaves the hierarchy while
 * the scene is still alive, and by the time the window goes there is no
 * interop view left in it.
 *
 * The same family as the `getPreferredSize` crash the holder in
 * `callVideoSurface` fixes — Compose touching Swing interop during window
 * disposal. That one was measurement; this is invalidation.
 */
@Composable
private fun callWindowAlive(pipOpen: Boolean): Boolean {
    var alive by remember { mutableStateOf(false) }
    LaunchedEffect(pipOpen) {
        if (pipOpen) {
            alive = true
        } else {
            // One frame: long enough for the composition that drops the
            // contents to have been applied, and `onDispose` to have run.
            withFrameNanos { }
            alive = false
        }
    }
    return alive
}

/**
 * What the OS may do to this window, and what the call needs back.
 *
 * **A floor under the size.** The dock is a fixed row — two pills with carets,
 * five round buttons and the hang-up — about 540dp of controls that cannot
 * reflow, and the stage above it needs room for a tile and the mini-tile that
 * floats over it. Dragged below that the controls run off the right edge and
 * the mini tile lands on top of the big one, which is what "the call UI is
 * distracted in a small window" looks like. The OS enforces the floor, so the
 * layout is never asked to do something it has no way to do. The thumbnail has
 * its own, much smaller floor: it is one tile and a four-button strip.
 *
 * **Zoomed, it stops being a thumbnail.** Compact draws the PiP strip and asks
 * the page for its compact stage, where the CSS hides every tile but the first
 * (`body.compact .tile:not(:first-child)`) — so a maximised thumbnail was a
 * full screen of one face with everybody else switched off. Leaving compact
 * hands the same window to the real surface, at the size just asked for.
 */
@Composable
private fun CallWindowBehaviour(
    windowState: WindowState,
    window: java.awt.Window,
    compact: Boolean,
    calls: CallViewModel,
) {
    LaunchedEffect(compact) {
        window.minimumSize = if (compact) {
            Dimension(PIP_MIN_WIDTH, PIP_MIN_HEIGHT)
        } else {
            Dimension(CALL_MIN_WIDTH, CALL_MIN_HEIGHT)
        }
    }
    LaunchedEffect(windowState.placement, compact) {
        if (!compact || windowState.placement == WindowPlacement.Floating) return@LaunchedEffect
        com.zillit.desktop.core.common.ZillitLog.i("CallWindowing") {
            "thumbnail ${windowState.placement}; leaving compact"
        }
        calls.onEvent(CallEvent.ToggleCallCompact)
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
        // A window the OS is sizing — zoomed or full-screen — keeps the size
        // and place the OS gave it. This runs when leaving compact, and a
        // thumbnail leaves compact precisely BECAUSE it was just maximised:
        // centring it at 960x640 would undo the click that got us here.
        if (state.placement != WindowPlacement.Floating) return@LaunchedEffect
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

/**
 * The smallest the OS will let either window become — see [CallWindowBehaviour].
 * AWT sizes a window in the same logical points Compose measures `Dp` in here,
 * so these are the dp figures they read as.
 */
private const val CALL_MIN_WIDTH = 680
private const val CALL_MIN_HEIGHT = 480
private const val PIP_MIN_WIDTH = 260
private const val PIP_MIN_HEIGHT = 180
private val PIP_DEFAULT_WIDTH = 360.dp
private val PIP_DEFAULT_HEIGHT = 240.dp
private val PIP_BAR_HEIGHT = 36.dp
private val PIP_BUTTON = 26.dp

/** Where the popout was last, for the length of the process. */
private var pipLastPosition: WindowPosition? = null
private var pipLastSize: DpSize = DpSize(PIP_DEFAULT_WIDTH, PIP_DEFAULT_HEIGHT)
