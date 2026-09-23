package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallViewModel
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
) {
    calls ?: return
    val enabled by preferences.observe(ZillitPreferences.CallWidget).collectAsState(initial = true)
    val phase by ready.callCoordinator.phase.collectAsState()
    val session by ready.callCoordinator.session.collectAsState()
    val ringing = phase == CallPhase.Incoming
    var mainInFront by remember { mutableStateOf(frame.isInFront()) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(ringing) {
        dismissed = false
        while (ringing) {
            mainInFront = frame.isInFront()
            delay(FOCUS_POLL_MILLIS)
        }
    }
    val current = session
    val cardHidden = !enabled || dismissed || mainInFront
    if (cardHidden || !ringing || current == null) return

    val windowState = rememberWindowState(
        size = DpSize(CARD_WIDTH, CARD_HEIGHT),
        position = WindowPosition.Aligned(Alignment.TopEnd),
    )
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
                )
            }
        }
    }
}

@Composable
internal fun IncomingCallCard(
    current: CallSession,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    showMain: () -> Unit,
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = str(S.decline),
                onClick = onDecline,
                modifier = Modifier.weight(1f),
                variant = ButtonVariant.Danger,
                leadingIcon = ZillitIcons.PhoneDown,
            )
            ZillitButton(
                text = str(S.accept),
                onClick = {
                    onAccept()
                    showMain()
                },
                modifier = Modifier.weight(1f),
                leadingIcon = ZillitIcons.Phone,
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
private val CORNER = 12.dp
private const val FOCUS_POLL_MILLIS = 400L
