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
            text = "More options",
            style = ZillitTheme.typography.titleSmall,
            color = CallPalette.text,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        )
        // One recording per call is the rule every platform enforces; while
        // somebody else holds it the row says so. Never on a support call.
        if (state.session?.is247Call != true) {
            val recordLabel = when {
                state.recording -> "Stop recording"
                state.recordedBy.isNotBlank() -> "${state.recordedBy} is recording"
                else -> "Start recording"
            }
            MoreRow(ZillitIcons.Record, recordLabel, enabled = state.recording || state.recordedBy.isBlank()) {
                pick(CallEvent.ToggleRecording)
            }
            MoreRow(ZillitIcons.UserPlus, "Add people") { pick(CallEvent.ToggleAddPeople) }
        }
        if (state.pipOpen) {
            MoreRow(ZillitIcons.Minimize, "Picture-in-picture") { pick(CallEvent.ToggleCallCompact) }
            MoreRow(ZillitIcons.Restore, "Move back into Zillit") { pick(CallEvent.TogglePip) }
        } else {
            MoreRow(ZillitIcons.Detach, "Open in its own window") { pick(CallEvent.TogglePip) }
            MoreRow(ZillitIcons.Minimize, "Minimise call") { pick(CallEvent.ToggleStage) }
        }
    }
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
