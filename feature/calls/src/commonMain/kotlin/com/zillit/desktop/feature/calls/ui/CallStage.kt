package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.ZillitTheme
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
    val showsVideo = state.stage == CallStageKind.Video && videoAvailable

    // The web's one dark surface (`styles.css:3-19`), whatever the workspace theme.
    Box(modifier = Modifier.fillMaxSize().background(CallPalette.surface)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CallTopBar(state, onEvent, videoAvailable)
            NoticeBanner(state, onEvent)
            // A second ring, in the column with the banners: outside the
            // video rectangle, or it would never be drawn.
            state.secondCall?.let { CallSecondCallBanner(it, onEvent) }
            StageBody(
                state = state,
                onEvent = onEvent,
                loadAvatar = loadAvatar,
                showsVideo = showsVideo,
                onSlot = onSlot,
                modifier = Modifier.weight(1f).padding(horizontal = STAGE_GUTTER),
            )
            /*
             * A row of its own, never a float over the picture.
             *
             * The video is a heavyweight AWT component — Chromium painting
             * straight onto its own native surface — and it sits above every
             * Compose layer regardless of z-order. A control bar floated over
             * it is not dimmed or behind: it is simply not drawn, and the only
             * buttons that survive are the ones hanging off the edge of the
             * video rectangle. That is what a call looked like the moment
             * anybody turned their camera on.
             *
             * So the bar takes real space and the picture gets what is left.
             * It also stays put when somebody enables video mid-call, rather
             * than the whole layout jumping between two arrangements.
             */
            StageControls(state = state, onEvent = onEvent)
        }

        /*
         * Reactions, on the Compose side, for calls with no picture.
         *
         * Same heavyweight rule: nothing composed here survives inside the
         * video rectangle, so on a video call the emoji are drawn by the page
         * itself (see the reaction push in `CallSurface`) and this layer stands
         * down rather than animating something nobody can see.
         */
        if (!showsVideo) {
            CallReactionLayer(
                reactions = state.reactions,
                onExpired = { onEvent(CallEvent.ExpireReaction(it)) },
                modifier = Modifier.fillMaxSize(),
            )
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
        SidePanels(state, onEvent)
    }
}

/**
 * Whatever is open beside the picture. At most one in practice, but they
 * are independent toggles: opening two just splits the width, rather than
 * one of them silently winning.
 */
@Composable
private fun SidePanels(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val panel = Modifier.width(ROSTER_WIDTH).fillMaxSize()
    if (state.rosterOpen) {
        CallRosterPanel(
            tiles = state.tiles,
            modifier = panel,
            state = state,
            onEvent = onEvent,
        )
    }
    if (state.hostControlsOpen && state.isHost) {
        CallHostControlsPanel(
            policy = state.line3.policy,
            onPolicy = { onEvent(CallEvent.SetCallPolicy(it)) },
            onAction = { onEvent(CallEvent.HostAction(it)) },
            onClose = { onEvent(CallEvent.ToggleHostControls) },
            modifier = panel,
        )
    }
    if (state.guestsOpen) {
        CallGuestsPanel(
            guests = state.line3.pendingGuests,
            onAdmit = { onEvent(CallEvent.AdmitGuest(it)) },
            onDecline = { onEvent(CallEvent.DeclineGuest(it)) },
            onClose = { onEvent(CallEvent.ToggleGuests) },
            modifier = panel,
        )
    }
    if (state.addPeopleOpen) {
        CallAddPeoplePanel(
            crew = state.addableCrew,
            onPick = { onEvent(CallEvent.AddPerson(it)) },
            modifier = panel,
        )
    }
    ToolPanels(state, onEvent, panel)
}

/** The device list, the ⋮ rows and the chat — the panels about the call rather than its people. */
@Composable
private fun ToolPanels(state: CallUiState, onEvent: (CallEvent) -> Unit, panel: Modifier) {
    if (state.audioPickerOpen) {
        CallDevicePanel(
            devices = state.devices,
            onChooseMicrophone = { onEvent(CallEvent.ChooseMicrophone(it)) },
            onChooseSpeaker = { onEvent(CallEvent.ChooseSpeaker(it)) },
            modifier = panel,
        )
    }
    if (state.moreOpen) {
        CallMorePanel(
            state = state,
            onEvent = onEvent,
            modifier = panel,
        )
    }
    if (state.chatOpen) {
        CallChatPanel(
            lines = state.chat,
            onSend = { onEvent(CallEvent.SendChat(it)) },
            modifier = panel,
            lockedReason = when {
                state.chatLocked -> "The host has turned chat off"
                state.selfChatBlocked -> "The host blocked you from chat"
                else -> null
            },
        )
    }
}

/**
 * The 54px top bar (`CallRoom.tsx:1349-1503`): the timer, the call's name as
 * a pill, and one pill per standing fact — reconnecting, recording, a hand up,
 * the line's health — then the people button and the window controls.
 *
 * Pills rather than banners: the web keeps every standing fact in this one
 * row so the picture below never moves when a fact appears.
 */
@Composable
private fun CallTopBar(state: CallUiState, onEvent: (CallEvent) -> Unit, videoAvailable: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = STAGE_GUTTER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        CallTimer(state)
        TitlePill(state)
        ConnectionPill(state, videoAvailable)
        HoldPill(state, onEvent)
        RecordingPill(state)
        HandPill(state, onEvent)
        Box(modifier = Modifier.weight(1f))
        NetworkPip(quality = state.media.selfQuality, showLabel = false)
        GuestsPill(state, onEvent)
        PeopleButton(state, onEvent)
        WindowControls(state = state, onEvent = onEvent)
    }
}

/** 17px bold tabular, a 2px rule on its right; "Connecting…" until the first peer (`CallRoom.tsx:259-273`). */
@Composable
private fun CallTimer(state: CallUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = state.timerText.ifBlank { "Connecting…" },
            style = ZillitTheme.typography.numeric
                .copy(fontSize = TIMER_FONT, fontWeight = FontWeight.Bold),
            color = CallPalette.text,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .padding(start = ZillitTheme.spacing.md)
                .width(TIMER_RULE)
                .height(TIMER_RULE_HEIGHT)
                .background(Color.White),
        )
    }
}

/** The room's name, or the person's, in the web's translucent pill (`.projPill`). */
@Composable
private fun TitlePill(state: CallUiState) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(PILL_CORNER))
            .background(CallPalette.pill)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = state.headerTitle,
            style = ZillitTheme.typography.labelSmall,
            color = CallPalette.text,
            maxLines = 1,
        )
        // Which line, as the phones label it: the first question about any
        // call problem is which one it was on.
        if (state.lineLabel.isNotBlank()) {
            ZillitText(
                text = "· ${state.lineLabel}",
                style = ZillitTheme.typography.labelSmall,
                color = CallPalette.muted,
            )
        }
    }
}

/** The people button with its count, top right (`CallRoom.tsx:1473-1482`). */
@Composable
private fun PeopleButton(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val count = state.connected
    ZillitTooltip(if (state.session?.is247Call == true) "Call users" else "Call users · Add users") {
        Box(contentAlignment = Alignment.TopEnd) {
            RoundAction(
                icon = ZillitIcons.Users,
                label = "Call users",
                background = if (state.rosterOpen) CallPalette.accent else CallPalette.control,
                tint = if (state.rosterOpen) CallPalette.onAccent else CallPalette.text,
                size = HEADER_BUTTON,
                onClick = { onEvent(CallEvent.ToggleRoster) },
            )
            if (count > 0) {
                Box(
                    modifier = Modifier.size(COUNT_BADGE).clip(CircleShape).background(CallPalette.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = count.toString(),
                        style = ZillitTheme.typography.labelSmall
                            .copy(fontSize = BADGE_FONT, fontWeight = FontWeight.Bold),
                        color = CallPalette.onAccent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One standing fact, as the web draws it: a dot or icon, a few words, a tinted pill. */
@Composable
private fun StatusPill(text: String, background: Color, foreground: Color, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(PILL_CORNER))
            .background(background)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (icon != null) {
            ZillitIcon(icon = icon, contentDescription = null, tint = foreground, size = PILL_ICON)
        } else {
            Box(modifier = Modifier.size(BANNER_DOT).clip(CircleShape).background(foreground))
        }
        ZillitText(text = text, style = ZillitTheme.typography.labelSmall, color = foreground, maxLines = 1)
    }
}

/**
 * MY hold, with Resume on the pill (`CallRoom.tsx:1394-1403`): the tiles
 * badge everyone else's, and the action that undoes the state belongs where
 * the state is announced, not buried back in the ⋮ menu.
 */
@Composable
private fun HoldPill(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    if (!state.onHold) return
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(PILL_CORNER))
            .background(CallPalette.amberSoft)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Pause, contentDescription = null, tint = CallPalette.onAccent, size = PILL_ICON)
        ZillitText(text = "Call on hold", style = ZillitTheme.typography.labelSmall, color = CallPalette.onAccent)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(PILL_CORNER))
                .background(CallPalette.onAccent)
                .clickable { onEvent(CallEvent.ToggleHold) }
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Play,
                contentDescription = null,
                tint = CallPalette.amberSoft,
                size = RESUME_ICON,
            )
            ZillitText(text = "Resume", style = ZillitTheme.typography.labelSmall, color = CallPalette.amberSoft)
        }
    }
}

/**
 * Guests at the door — a labelled pill with a count, not a bare glyph
 * (`CallRoom.tsx:1447-1470`): "someone outside the project wants in" is a
 * decision, and a tiny icon with a dot was routinely missed.
 */
@Composable
private fun GuestsPill(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val waiting = state.line3.pendingGuests.size
    if (waiting == 0) return
    Box(modifier = Modifier.clickable { onEvent(CallEvent.ToggleGuests) }) {
        StatusPill(
            text = "External request${if (waiting > 1) "s" else ""} · $waiting",
            background = if (state.guestsOpen) CallPalette.accent else CallPalette.control,
            foreground = if (state.guestsOpen) CallPalette.onAccent else CallPalette.text,
            icon = ZillitIcons.UserPlus,
        )
    }
}

/**
 * Recording is the one call fact nobody may miss: red, and named — whoever
 * holds the recorder, everybody on the call is told (`CallRoom.tsx:1414-1418`).
 */
@Composable
private fun RecordingPill(state: CallUiState) {
    val text = when {
        state.recording -> "You are recording"
        state.recordedBy.isNotBlank() -> "${state.recordedBy} is recording"
        else -> return
    }
    StatusPill(text = text, background = CallPalette.danger, foreground = Color.White)
}

/**
 * Who has their hand up, named, amber (`CallRoom.tsx:1419-1439`). Opens the
 * roster: that is where the hand can be seen against the person.
 */
@Composable
private fun HandPill(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val others = state.session?.participants.orEmpty().filter { it.handRaised }.map { it.name }
    val names = (if (state.handRaised) listOf("You") else emptyList()) + others.filter { it.isNotBlank() }
    if (names.isEmpty()) return
    val text = if (names.size == 1) "${names.first()} raised their hand" else "${names.size} users raised their hands"
    Box(modifier = Modifier.clickable { onEvent(CallEvent.ToggleRoster) }) {
        StatusPill(
            text = text,
            background = CallPalette.amber,
            foreground = CallPalette.onAccent,
            icon = ZillitIcons.Hand,
        )
    }
}

/** One pill about the call's health, or nothing (`CallRoom.tsx:1390-1413`). */
@Composable
private fun ConnectionPill(state: CallUiState, videoAvailable: Boolean) {
    val (background, foreground, text) = when {
        state.media.connection == EngineConnection.Reconnecting ->
            Triple(CallPalette.control, CallPalette.text, "Reconnecting…")
        state.media.connection == EngineConnection.Disconnected ||
            state.media.connection == EngineConnection.Failed ->
            Triple(CallPalette.danger, Color.White, "Connection lost — trying again")
        state.mediaDegraded ->
            Triple(CallPalette.amberSoft, CallPalette.onAccent, "No audio on this call")
        state.session?.hasVideo == true && !videoAvailable ->
            Triple(CallPalette.control, CallPalette.text, "Video is unavailable in this build")
        else -> return
    }
    StatusPill(text = text, background = background, foreground = foreground)
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
 * Where this call surface should live next.
 *
 * The same two controls mean different things depending on where the surface
 * is drawn, so they are named for what they do from here rather than for the
 * state they toggle.
 */
@Composable
private fun WindowControls(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    if (state.pipOpen) {
        // Already in its own window: shrink to the always-on-top thumbnail,
        // or hand the call back to the main window.
        RoundAction(
            icon = ZillitIcons.Minimize,
            label = "Shrink to thumbnail",
            background = CallPalette.control,
            tint = CallPalette.text,
            size = HEADER_BUTTON,
            onClick = { onEvent(CallEvent.ToggleCallCompact) },
        )
        RoundAction(
            icon = ZillitIcons.Restore,
            label = "Move back into Zillit",
            background = CallPalette.control,
            tint = CallPalette.text,
            size = HEADER_BUTTON,
            onClick = { onEvent(CallEvent.TogglePip) },
        )
    } else {
        // Drawn inside the main window, which happens when the user put it
        // back: offer the way out again, and the pill.
        RoundAction(
            icon = ZillitIcons.Detach,
            label = "Open in its own window",
            background = CallPalette.control,
            tint = CallPalette.text,
            size = HEADER_BUTTON,
            onClick = { onEvent(CallEvent.TogglePip) },
        )
        RoundAction(
            icon = ZillitIcons.Minimize,
            label = "Minimise call",
            background = CallPalette.control,
            tint = CallPalette.text,
            size = HEADER_BUTTON,
            onClick = { onEvent(CallEvent.ToggleStage) },
        )
    }
}


/**
 * One line about something that did not work, with a way to dismiss it.
 *
 * In the Column with the other banners rather than floating: it has to be
 * outside the video rectangle to be drawn at all.
 */
@Composable
private fun NoticeBanner(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val text = state.notice ?: return
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(BANNER_CORNER))
            .background(CallPalette.menu)
            .clickable { onEvent(CallEvent.DismissNotice) }
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Warning, contentDescription = null, tint = CallPalette.amber)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = CallPalette.text,
        )
        ZillitText(
            text = "Dismiss",
            style = ZillitTheme.typography.labelSmall,
            color = CallPalette.muted,
        )
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

/** The web's bar: 54px, 32px circles, 12px name pill (`styles.css:1246-1262`). */
private val TOP_BAR_HEIGHT = 54.dp
private val HEADER_BUTTON = 32.dp
private val STAGE_GUTTER = 8.dp
private val TIMER_FONT = 17.sp
private val TIMER_RULE = 2.dp
private val TIMER_RULE_HEIGHT = 18.dp
private val PILL_CORNER = 16.dp
private val PILL_ICON = 14.dp
private val COUNT_BADGE = 17.dp
private val BADGE_FONT = 10.sp
private val BANNER_CORNER = 10.dp
private val BANNER_DOT = 8.dp
private val RESUME_ICON = 11.dp

/** How long the picture goes untouched before the chrome steps aside. */
