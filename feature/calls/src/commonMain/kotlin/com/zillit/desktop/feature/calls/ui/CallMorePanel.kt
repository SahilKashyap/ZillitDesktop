package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallProvider

/**
 * The ⋮ menu's rows — the web's settings menu (`CallRoom.tsx:1726-1826`),
 * as a panel beside the picture like the roster and the device list.
 *
 * A panel and not a popup for the reason every other call panel is one: a
 * heavyweight video surface paints over anything Compose floats above it,
 * so a menu opened upward from the dock was drawn under the video and read
 * as "the options do nothing".
 */
@Composable
fun CallMorePanel(state: CallUiState, onEvent: (CallEvent) -> Unit, modifier: Modifier = Modifier) {
    val pick: (CallEvent) -> Unit = { event ->
        onEvent(CallEvent.ToggleMore)
        onEvent(event)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PANEL_CORNER))
            .background(CallPalette.menu)
            .padding(vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ZillitText(
            text = str(S.dd_cd_more),
            style = ZillitTheme.typography.titleSmall,
            color = CallPalette.text,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        )
        val line3 = state.session?.provider == CallProvider.LiveKit
        // Hold first, and always offered on Line 3: the one row here you may
        // need in a hurry, and never host-restricted (`CallRoom.tsx:1762-1770`).
        if (line3) {
            MoreRow(
                if (state.onHold) ZillitIcons.Play else ZillitIcons.Pause,
                if (state.onHold) str(S.desktop_call_resume_call) else str(S.desktop_call_hold_call),
            ) { pick(CallEvent.ToggleHold) }
        }
        // One recording per call is the rule every platform enforces; while
        // somebody else holds it the row says so. Never on a support call.
        if (state.session?.is247Call != true) {
            RecordingRow(state, pick)
            MoreRow(ZillitIcons.UserPlus, str(S.desktop_call_add_people)) { pick(CallEvent.ToggleAddPeople) }
        }
        if (state.inviteLinkOffered) {
            MoreRow(ZillitIcons.Link, str(S.desktop_call_copy_invite_link)) { pick(CallEvent.CopyInviteLink) }
        }
        if (line3 && state.isHost) {
            MoreRow(
                ZillitIcons.Shield,
                if (state.line3.policy.on) str(S.desktop_call_host_controls_on) else str(S.desktop_call_host_controls),
            ) {
                pick(CallEvent.ToggleHostControls)
            }
        }
        if (state.pipOpen) {
            MoreRow(ZillitIcons.Minimize, str(S.desktop_call_picture_in_picture)) { pick(CallEvent.ToggleCallCompact) }
            MoreRow(ZillitIcons.Restore, str(S.desktop_call_move_back_into_zillit)) { pick(CallEvent.TogglePip) }
        } else {
            MoreRow(ZillitIcons.Detach, str(S.desktop_call_open_in_its_own_window)) { pick(CallEvent.TogglePip) }
            MoreRow(ZillitIcons.Minimize, str(S.desktop_call_minimise_call)) { pick(CallEvent.ToggleStage) }
        }
    }
}

/** Record, stop, or who holds it — and "off" under a host policy, where pressing explains. */
@Composable
private fun RecordingRow(state: CallUiState, pick: (CallEvent) -> Unit) {
    val label = when {
        state.recordingLocked -> str(S.desktop_call_recording_is_off)
        state.recording -> str(S.txt_record_stop)
        state.recordedBy.isNotBlank() -> str(S.desktop_call_name_is_recording, state.recordedBy)
        else -> str(S.desktop_call_start_recording)
    }
    MoreRow(
        ZillitIcons.Record,
        label,
        enabled = !state.recordingLocked && (state.recording || state.recordedBy.isBlank()),
    ) { pick(CallEvent.ToggleRecording) }
}

@Composable
private fun MoreRow(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else INERT_ALPHA)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = icon, contentDescription = null, tint = CallPalette.text, size = ROW_ICON)
        ZillitText(text = label, style = ZillitTheme.typography.bodyMedium, color = CallPalette.text, maxLines = 1)
    }
}

private val PANEL_CORNER = 12.dp
private val ROW_ICON = 18.dp
private const val INERT_ALPHA = 0.4f
