package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallUiState
import com.zillit.desktop.feature.calls.ui.CallViewModel

/**
 * "You're sharing your screen", floating above everything while a share is live.
 *
 * The call's own banner says so too, but it is on the call surface — and the
 * moment somebody shares, they leave that surface for the document, deck or
 * app they meant to show. Without this there was nothing on screen to say a
 * share was running, which is how people ended up presenting their inbox. So
 * it is its own small always-on-top window, like the bar every meeting app
 * puts up, with Stop on it and the way back to the call.
 *
 * Not focusable: it must never take the keyboard from the window being
 * presented. Draggable by its body, since wherever it opens will one day be
 * over the thing being shown.
 */
@Composable
internal fun ApplicationScope.ScreenShareIndicator(
    state: CallUiState,
    calls: CallViewModel,
    darkTheme: Boolean,
    showCall: () -> Unit,
) {
    if (!state.media.selfSharing) return
    val windowState = rememberWindowState(
        size = DpSize(INDICATOR_WIDTH, INDICATOR_HEIGHT),
        position = WindowPosition.Aligned(Alignment.TopCenter),
    )
    Window(
        onCloseRequest = { calls.onEvent(CallEvent.ToggleScreenShare) },
        state = windowState,
        title = str(S.desktop_call_sharing_screen_title),
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
    ) {
        ZillitTheme(darkTheme = darkTheme) {
            WindowDraggableArea {
                SharingCard(
                    onStop = { calls.onEvent(CallEvent.ToggleScreenShare) },
                    onShowCall = showCall,
                )
            }
        }
    }
}

/** The card itself: a live dot, what is happening, and the two things to do about it. */
@Composable
private fun SharingCard(onStop: () -> Unit, onShowCall: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(CARD_MARGIN)
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, RoundedCornerShape(CARD_CORNER))
            .padding(horizontal = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(modifier = Modifier.size(LIVE_DOT).clip(CircleShape).background(colors.danger))
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = str(S.desktop_call_sharing_screen_title),
                style = ZillitTheme.typography.label,
                color = colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = str(S.desktop_call_sharing_screen_body),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitButton(
            text = str(S.desktop_call_back_to_call),
            onClick = onShowCall,
            size = ButtonSize.Small,
            variant = ButtonVariant.Tertiary,
        )
        ZillitButton(
            text = str(S.desktop_call_stop_presenting),
            onClick = onStop,
            size = ButtonSize.Small,
            variant = ButtonVariant.Danger,
        )
    }
}

private val INDICATOR_WIDTH = 560.dp
private val INDICATOR_HEIGHT = 72.dp
private val CARD_MARGIN = 6.dp
private val CARD_CORNER = 14.dp
private val LIVE_DOT = 10.dp
