package com.zillit.desktop.feature.calls.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
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

    Box(modifier = Modifier.fillMaxSize().background(colors.scrim)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            CallStageHeader(state, onEvent)
            ConnectionBanner(state, videoAvailable)
            Row(
                modifier = Modifier.weight(1f),
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
            }
            CallDock(
                state = state,
                onEvent = onEvent,
                modifier = Modifier.align(Alignment.CenterHorizontally),
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
        // A video call can leave the window entirely: the picture goes to a
        // small always-on-top window and the workspace comes back. There is
        // no picture to pop out of an audio call, so no button on one.
        if (state.stage == CallStageKind.Video) {
            RoundAction(
                icon = ZillitIcons.Detach,
                label = "Pop out video",
                background = colors.surfaceHover,
                tint = colors.textPrimary,
                size = HEADER_BUTTON,
                onClick = { onEvent(CallEvent.TogglePip) },
            )
        }
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
