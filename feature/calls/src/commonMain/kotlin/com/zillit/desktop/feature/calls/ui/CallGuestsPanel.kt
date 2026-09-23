package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.data.livekit.LiveKitGuest

/**
 * Link guests waiting to be let in — the web's `guestPanel`
 * (`CallRoom.tsx:1447-1470`): a name and two answers per row. Any in-call
 * member may answer; the server declines the rest when one does.
 */
@Composable
fun CallGuestsPanel(
    guests: List<LiveKitGuest>,
    onAdmit: (String) -> Unit,
    onDecline: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PANEL_CORNER))
            .background(CallPalette.menu)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.desktop_call_guests_requesting_to_join),
                style = ZillitTheme.typography.titleSmall,
                color = CallPalette.text,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.clickable(onClick = onClose)) {
                ZillitIcon(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.close),
                    tint = CallPalette.muted,
                    size = ROW_ICON,
                )
            }
        }
        if (guests.isEmpty()) NobodyWaiting()
        val listState = rememberLazyListState()
        ZillitLazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            items(guests, key = LiveKitGuest::guestId) { guest ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = guest.name.ifBlank { str(S.txt_badge_guest) },
                        style = ZillitTheme.typography.bodyMedium,
                        color = CallPalette.text,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitButton(
                        text = str(S.desktop_admit),
                        size = ButtonSize.Small,
                        onClick = { onAdmit(guest.guestId) },
                    )
                    ZillitButton(
                        text = str(S.decline),
                        size = ButtonSize.Small,
                        variant = ButtonVariant.Tertiary,
                        onClick = { onDecline(guest.guestId) },
                    )
                }
            }
        }
    }
}

private val PANEL_CORNER = 12.dp
private val ROW_ICON = 18.dp

@Composable
private fun NobodyWaiting() {
    ZillitText(
        text = str(S.desktop_call_nobody_is_waiting),
        style = ZillitTheme.typography.bodySmall,
        color = CallPalette.muted,
    )
}
