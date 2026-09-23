package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.MediaDevice

/**
 * Microphone and speaker, chosen from a panel beside the picture.
 *
 * A panel and not a menu, for the same reason the control bar is a row rather
 * than a float: the picture is a heavyweight browser surface and it paints over
 * every Compose layer regardless of z-order. A dropdown anchored to the control
 * bar opens upward INTO that rectangle, so most of it is simply not drawn —
 * what the user sees is a sliver of one device name hanging below the video and
 * nothing else. Out here, beside the picture, there is nothing to be painted
 * over by.
 */
@Composable
fun CallDevicePanel(
    devices: com.zillit.desktop.feature.calls.domain.CallDevices,
    onChooseMicrophone: (String) -> Unit,
    onChooseSpeaker: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PANEL_CORNER))
            .background(colors.surfaceRaised)
            .border(PANEL_BORDER, colors.border, RoundedCornerShape(PANEL_CORNER))
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.audio),
            style = ZillitTheme.typography.titleSmall,
            color = colors.textPrimary,
        )
        val listState = rememberLazyListState()
        ZillitLazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            item { DeviceHeading(str(S.txt_microphone)) }
            item {
                DeviceChoice(str(S.theme_system_default), devices.microphoneId.isBlank()) { onChooseMicrophone("") }
            }
            items(devices.microphones, key = { "mic-" + it.id }) { device ->
                DeviceChoice(device.displayName, device.id == devices.microphoneId) {
                    onChooseMicrophone(device.id)
                }
            }
            item { DeviceHeading(str(S.txt_speaker)) }
            item {
                DeviceChoice(str(S.theme_system_default), devices.speakerId.isBlank()) { onChooseSpeaker("") }
            }
            items(devices.speakers, key = { "spk-" + it.id }) { device ->
                DeviceChoice(device.displayName, device.id == devices.speakerId) {
                    onChooseSpeaker(device.id)
                }
            }
        }
    }
}

@Composable
private fun DeviceHeading(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textSecondary,
        modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
    )
}

/**
 * One device. The dot rather than a tick because the row is narrow and a
 * leading marker keeps the names left-aligned with each other.
 */
@Composable
private fun DeviceChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ROW_CORNER))
            .background(if (selected) colors.surfaceSelected else colors.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier
                .size(MARKER)
                .clip(CircleShape)
                .background(if (selected) colors.accent else colors.border),
        ) {}
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = if (selected) colors.accentText else colors.textPrimary,
            maxLines = 1,
        )
    }
}

private val PANEL_CORNER = 12.dp
private val PANEL_BORDER = 1.dp
private val ROW_CORNER = 8.dp
private val MARKER = 8.dp
