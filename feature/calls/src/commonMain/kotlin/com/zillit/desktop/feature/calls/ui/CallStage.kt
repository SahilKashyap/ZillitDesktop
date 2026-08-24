package com.zillit.desktop.feature.calls.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.EngineConnection

/**
 * The in-call surface.
 *
 * A dedicated stage rather than a bar along the bottom: once a call has more
 * than two people in it, the things a participant needs — who is here, who is
 * talking, whether their line is holding up — do not fit in a strip, and
 * hiding them behind a menu is how you end up talking over someone for a
 * minute before noticing.
 *
 * [onSlot] reports the video rectangle to the host. The stage never draws
 * inside it; a heavyweight browser surface owns every pixel it covers, so the
 * rectangle is reserved as a real layout child and everything else lays out
 * around it.
 */
@Composable
fun CallStage(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    videoAvailable: Boolean,
    onSlot: (LayoutCoordinates) -> Unit,
) {
    val colors = ZillitTheme.colors
    val showsVideo = state.stage == CallStageKind.Video && videoAvailable

    // Bumped by any pointer event, which is what wakes the controls back up.
    var wakeTick by remember { mutableStateOf(0) }
    val controlsVisible = rememberControlsVisible(wakeTick, showsVideo)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        wakeTick++
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                CallStageHeader(state, onEvent)
            }
            ConnectionBanner(state, videoAvailable)
            HandRaisedBanner(state)
            StageBody(
                state = state,
                onEvent = onEvent,
                loadAvatar = loadAvatar,
                showsVideo = showsVideo,
                onSlot = onSlot,
                modifier = Modifier.weight(1f),
            )
        }

        /*
         * Above everything, including the controls.
         *
         * Reactions rise from the dock and past it, so this has to be the last
         * thing composed in the Box. It takes no pointer input — it is drawn
         * over the End call button for part of its flight, and an emoji must
         * never be what a click lands on.
         */
        CallReactionLayer(
            reactions = state.reactions,
            onExpired = { onEvent(CallEvent.ExpireReaction(it)) },
            modifier = Modifier.fillMaxSize(),
        )

        // Overlaid rather than stacked: a control bar that reserves a row
        // shrinks the picture it belongs to, and every call app of the last
        // decade floats it. Removed from composition when hidden, so an
        // invisible bar cannot swallow a click meant for the video.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ZillitTheme.spacing.lg),
        ) {
            StageControls(state = state, onEvent = onEvent)
        }
    }
}

/**
 * The picture, and whatever panel is open beside it.
 *
 * The picture's Box is reported through [onSlot] whether or not video is up:
 * the browser surface is positioned against those bounds by the host, and a
 * slot that only exists once video arrives would leave the first frame with
 * nowhere to land.
 */
@Composable
private fun StageBody(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    showsVideo: Boolean,
    onSlot: (LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .onGloballyPositioned(onSlot),
        ) {
            if (!showsVideo) {
                AvatarGrid(
                    tiles = state.tiles,
                    modifier = Modifier.fillMaxSize(),
                    loadAvatar = loadAvatar,
                )
                if (state.tiles.size <= 1) WaitingForOthers()
            }
        }
        // At most one is open at a time in practice, but they are independent
        // toggles: opening two just splits the width, rather than one of them
        // silently winning.
        if (state.rosterOpen) {
            CallRosterPanel(
                tiles = state.tiles,
                modifier = Modifier.width(ROSTER_WIDTH).fillMaxSize(),
            )
        }
        if (state.addPeopleOpen) {
            CallAddPeoplePanel(
                crew = state.addableCrew,
                onPick = { onEvent(CallEvent.AddPerson(it)) },
                modifier = Modifier.width(ROSTER_WIDTH).fillMaxSize(),
            )
        }
        if (state.chatOpen) {
            CallChatPanel(
                lines = state.chat,
                onSend = { onEvent(CallEvent.SendChat(it)) },
                modifier = Modifier.width(ROSTER_WIDTH).fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CallStageHeader(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .clip(RoundedCornerShape(PANEL_CORNER))
            .background(colors.surfaceRaised)
            .border(HAIRLINE, colors.border, RoundedCornerShape(PANEL_CORNER))
            .padding(horizontal = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = state.headerTitle, size = HEADER_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = state.headerTitle,
                style = ZillitTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = state.headerSubtitle,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        // Always present, unlike the tile pips: a reading the user can glance
        // at is the point of asking for a network indicator, and one that
        // appears only when things break cannot be trusted when it is absent.
        NetworkPip(quality = state.media.selfQuality, showLabel = true)
        RoundAction(
            icon = ZillitIcons.Users,
            label = "Participants",
            background = if (state.rosterOpen) colors.surfaceSelected else colors.surfaceHover,
            tint = colors.textPrimary,
            size = HEADER_BUTTON,
            onClick = { onEvent(CallEvent.ToggleRoster) },
        )
        WindowControls(state = state, onEvent = onEvent)
    }
}

/** The dock, with the emoji bar stacked above it when it is open. */
@Composable
private fun StageControls(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        // Above the dock rather than inside it: the bar is wider than any
        // control, and opening it should not resize the dock.
        if (state.reactionBarOpen) {
            CallReactionBar(onPick = { onEvent(CallEvent.SendReaction(it)) })
        }
        CallDock(state = state, onEvent = onEvent)
    }
}

/**
 * Whether the control bar is currently up.
 *
 * Re-arms on every [wakeTick], so any pointer event brings the bar back — the
 * behaviour every video app has trained people to expect. On an audio call it
 * never hides: there is nothing behind it to reveal, and a bar that vanished
 * over a static avatar would be hiding the whole interface.
 */
@Composable
private fun rememberControlsVisible(wakeTick: Int, showsVideo: Boolean): Boolean {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(wakeTick, showsVideo) {
        visible = true
        if (!showsVideo) return@LaunchedEffect
        delay(CONTROLS_IDLE_MILLIS)
        visible = false
    }
    return visible
}

/**
 * Where this call surface should live next.
 *
 * The same two controls mean different things depending on where the surface
 * is drawn, so they are named for what they do from here rather than for the
 * state they toggle.
 */
@Composable
private fun WindowControls(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val colors = ZillitTheme.colors
    if (state.pipOpen) {
        // Already in its own window: shrink to the always-on-top thumbnail,
        // or hand the call back to the main window.
        RoundAction(
            icon = ZillitIcons.Minimize,
            label = "Shrink to thumbnail",
            background = colors.surfaceHover,
            tint = colors.textPrimary,
            size = HEADER_BUTTON,
            onClick = { onEvent(CallEvent.ToggleCallCompact) },
        )
        RoundAction(
            icon = ZillitIcons.Restore,
            label = "Move back into Zillit",
            background = colors.surfaceHover,
            tint = colors.textPrimary,
            size = HEADER_BUTTON,
            onClick = { onEvent(CallEvent.TogglePip) },
        )
    } else {
        // Drawn inside the main window, which happens when the user put it
        // back: offer the way out again, and the pill.
        RoundAction(
            icon = ZillitIcons.Detach,
            label = "Open in its own window",
            background = colors.surfaceHover,
            tint = colors.textPrimary,
            size = HEADER_BUTTON,
            onClick = { onEvent(CallEvent.TogglePip) },
        )
        RoundAction(
            icon = ZillitIcons.Minimize,
            label = "Minimise call",
            background = colors.surfaceHover,
            tint = colors.textPrimary,
            size = HEADER_BUTTON,
            onClick = { onEvent(CallEvent.ToggleStage) },
        )
    }
}

/**
 * One line about the call's health, or nothing.
 *
 * A `Column` child rather than an overlay: anything floating over the stage
 * body would be swallowed by the video surface exactly when a video call is
 * the thing going wrong.
 */
/**
 * Who has their hand up, named.
 *
 * A badge on a tile is easy to miss on a busy grid and invisible on an audio
 * call, where there are no tiles worth scanning — so the names are said out
 * loud, the way the phones say them. This user's own hand is included: the
 * dock button already shows it, but a hand raised five minutes ago is exactly
 * the thing people forget they are still holding up.
 */
@Composable
private fun HandRaisedBanner(state: CallUiState) {
    val colors = ZillitTheme.colors
    val others = state.session?.participants.orEmpty().filter { it.handRaised }.map { it.name }
    val names = (if (state.handRaised) listOf("You") else emptyList()) + others.filter { it.isNotBlank() }
    if (names.isEmpty()) return

    val text = when {
        names.size == 1 -> "${names.first()} raised a hand"
        names.size == 2 -> "${names[0]} and ${names[1]} raised their hands"
        else -> "${names[0]} and ${names.size - 1} others raised their hands"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(BANNER_CORNER))
            .background(colors.warningSoft)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Hand, contentDescription = null, tint = colors.warning)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = colors.warning,
        )
    }
}

@Composable
private fun ConnectionBanner(state: CallUiState, videoAvailable: Boolean) {
    val colors = ZillitTheme.colors
    val (background, foreground, text) = when {
        state.media.connection == EngineConnection.Reconnecting ->
            Triple(colors.warningSoft, colors.warning, "Reconnecting…")
        state.media.connection == EngineConnection.Disconnected ||
            state.media.connection == EngineConnection.Failed ->
            Triple(colors.dangerSoft, colors.danger, "Connection lost — trying again")
        state.mediaDegraded ->
            Triple(colors.warningSoft, colors.warning, "No audio on this call")
        state.session?.hasVideo == true && !videoAvailable ->
            Triple(colors.infoSoft, colors.info, "Video is unavailable in this build")
        else -> return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BANNER_HEIGHT)
            .clip(RoundedCornerShape(BANNER_CORNER))
            .background(background)
            .padding(horizontal = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(modifier = Modifier.size(BANNER_DOT).clip(CircleShape).background(foreground))
        ZillitText(text = text, style = ZillitTheme.typography.labelSmall, color = foreground)
    }
}

/** 1:1 calls are titled by the person; a group by the room. */
val CallUiState.headerTitle: String
    get() {
        val session = session ?: return "Call"
        return if (session.mode == CallMode.Group) {
            session.title.ifBlank { "Group call" }
        } else {
            session.displayName.ifBlank { session.title.ifBlank { "Call" } }
        }
    }

internal val CallUiState.headerSubtitle: String
    get() {
        val timer = timerText
        if (timer.isBlank()) return "Connecting…"
        return "$timer · $connected in call"
    }

private val HEADER_HEIGHT = 56.dp
private val HEADER_AVATAR = 32.dp
private val HEADER_BUTTON = 44.dp
private val BANNER_HEIGHT = 32.dp
private val BANNER_CORNER = 10.dp
private val BANNER_DOT = 12.dp
private val PANEL_CORNER = 16.dp
private val HAIRLINE = 1.dp

/** How long the picture goes untouched before the chrome steps aside. */
private const val CONTROLS_IDLE_MILLIS = 3_500L
