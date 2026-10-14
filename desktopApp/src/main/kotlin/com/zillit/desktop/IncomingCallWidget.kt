package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.declineReplyTarget
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallViewModel
import com.zillit.desktop.feature.calls.ui.quickReplyPresets
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.delay

/**
 * The floating incoming-call card: a small always-on-top window with Accept
 * and Decline, shown while a call rings this device and Zillit's own window
 * is not in front — minimised, hidden in the tray, or behind another app.
 *
 * When the window *is* in front the ring card inside it is already on screen
 * and a second one would only cover it, so the card waits. It never takes
 * keyboard focus: a call must not pull the cursor out of whatever the user
 * was typing. Closing the card ignores the call for this ring only; the ring
 * card in the window and the banner still stand.
 *
 * The rule for "in front" is re-read while ringing rather than observed,
 * because focus is an AWT property with no Compose state behind it.
 */
@Composable
internal fun ApplicationScope.IncomingCallWidget(
    ready: AppGraph.Ready,
    calls: CallViewModel?,
    preferences: PreferenceStore,
    frame: ComposeWindow?,
    darkTheme: Boolean,
    showMain: () -> Unit,
    /**
     * Whether the main window has the in-window ring card up — true only while
     * the signed-in shell is on screen. False on the production picker and the
     * sign-in pages, where this card is the only one there is.
     */
    shellShowsRing: Boolean,
) {
    calls ?: return
    val enabled by preferences.observe(ZillitPreferences.CallWidget).collectAsState(initial = true)
    val phase by ready.callCoordinator.phase.collectAsState()
    val session by ready.callCoordinator.session.collectAsState()
    val ringing = phase == CallPhase.Incoming
    var mainInFront by remember { mutableStateOf(frame.isInFront()) }
    var dismissed by remember { mutableStateOf(false) }
    // The quick replies, when the Message button has been pressed. The card
    // grows for them rather than opening a second window: this one is already
    // a floating box in the corner, and a sheet over it would be two things to
    // dismiss for one answer.
    var replying by remember { mutableStateOf(false) }
    LaunchedEffect(ringing) {
        dismissed = false
        replying = false
        while (ringing) {
            mainInFront = frame.isInFront()
            delay(FOCUS_POLL_MILLIS)
        }
    }
    val current = session
    // Standing down for the main window is only right when that window is in
    // fact showing the ring. The call surface lives inside the signed-in
    // shell, so on the production picker — where a switch lands, and where a
    // fresh sign-in waits — there is no ring card behind this one and
    // deferring to the frame left an incoming call with nowhere at all to be
    // answered.
    val cardHidden = !enabled || dismissed || (mainInFront && shellShowsRing)
    if (cardHidden || !ringing || current == null) return

    val windowState = rememberWindowState(
        size = DpSize(CARD_WIDTH, CARD_HEIGHT),
        position = WindowPosition.Aligned(Alignment.TopEnd),
    )
    LaunchedEffect(replying) {
        windowState.size = DpSize(CARD_WIDTH, if (replying) REPLY_HEIGHT else CARD_HEIGHT)
    }
    Window(
        onCloseRequest = { dismissed = true },
        state = windowState,
        title = str(S.txt_incoming_call),
        icon = androidx.compose.ui.res.painterResource("icons/zillit-icon.png"),
        alwaysOnTop = true,
        undecorated = true,
        resizable = false,
        focusable = false,
    ) {
        ZillitTheme(darkTheme = darkTheme) {
            AvatarFaces(ready) {
                IncomingCallCard(
                    current = current,
                    onAccept = { calls.onEvent(CallEvent.Accept) },
                    onDecline = { calls.onEvent(CallEvent.Decline) },
                    showMain = showMain,
                    replying = replying,
                    canReply = current.declineReplyTarget() != null,
                    onReply = { replying = true },
                    onCancelReply = { replying = false },
                    onSendReply = { text -> calls.onEvent(CallEvent.DeclineWithMessage(text)) },
                )
            }
        }
    }
}

/**
 * The floating card's face.
 *
 * [replying] swaps the two buttons for Android's ready-made decline lines. The
 * window behind this card takes no keyboard focus — by design, so a ring never
 * steals a keystroke — so there is no box to write your own in: that lives on
 * the ring card inside the main window, which the header opens.
 */
@Composable
@Suppress("LongParameterList") // One card, one ring, one set of answers.
internal fun IncomingCallCard(
    current: CallSession,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    showMain: () -> Unit,
    replying: Boolean = false,
    canReply: Boolean = false,
    onReply: () -> Unit = {},
    onCancelReply: () -> Unit = {},
    onSendReply: (String) -> Unit = {},
) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(CORNER))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, RoundedCornerShape(CORNER))
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.fillMaxWidth().clickable(onClick = showMain)) {
            ZillitText(
                text = current.displayName,
                style = ZillitTheme.typography.label,
                color = colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = describeCall(current.title.orEmpty(), current.hasVideo),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        if (replying) {
            QuickReplyRows(onSend = onSendReply, onCancel = onCancelReply)
            return@Column
        }
        RingButtons(
            onAccept = { onAccept(); showMain() },
            onDecline = onDecline,
            canReply = canReply,
            onReply = onReply,
        )
    }
}

/** Decline, Accept, and Message when there is anyone to send one to. */
@Composable
private fun RingButtons(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    canReply: Boolean,
    onReply: () -> Unit,
) {
    // Three across a 340dp card is tight, so the row drops to the small size
    // when the third one is there — a label that fits beats an icon with no
    // room for the word beside it.
    val buttonSize = if (canReply) ButtonSize.Small else ButtonSize.Medium
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitButton(
            text = str(S.decline),
            onClick = onDecline,
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Danger,
            size = buttonSize,
            leadingIcon = ZillitIcons.PhoneDown,
        )
        ZillitButton(
            text = str(S.accept),
            onClick = onAccept,
            modifier = Modifier.weight(1f),
            size = buttonSize,
            leadingIcon = ZillitIcons.Phone,
        )
        if (canReply) {
            ZillitButton(
                text = str(S.message),
                onClick = onReply,
                modifier = Modifier.weight(1f),
                variant = ButtonVariant.Secondary,
                size = buttonSize,
                leadingIcon = ZillitIcons.Chat,
            )
        }
    }
}

/** The three presets and a way back, sized for a card this small. */
@Composable
private fun QuickReplyRows(onSend: (String) -> Unit, onCancel: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        quickReplyPresets().forEach { preset ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(REPLY_CORNER))
                    .background(colors.surface)
                    .clickable { onSend(preset) }
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = preset,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().clickable(onClick = onCancel).padding(top = ZillitTheme.spacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = str(S.cancel),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** "Video call · Camera unit" — the kind first, the room only when there is one. */
internal fun describeCall(room: String, hasVideo: Boolean): String {
    val kind = if (hasVideo) str(S.txt_video_call_label) else str(S.desktop_voice_call)
    return if (room.isBlank()) kind else "$kind · $room"
}

/** The main window is in front: focused, which a minimised or hidden window never is. */
internal fun ComposeWindow?.isInFront(): Boolean = this?.isFocused == true

private val CARD_WIDTH = 340.dp
private val CARD_HEIGHT = 100.dp

/** Room for the three presets and the way back out of them. */
private val REPLY_HEIGHT = 170.dp
private val REPLY_CORNER = 8.dp
private val CORNER = 12.dp
private const val FOCUS_POLL_MILLIS = 400L
