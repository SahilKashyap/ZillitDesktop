package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState

/**
 * Adding artistes to the day.
 *
 * A blocked artiste is never offered — blocking exists to keep them off the
 * call, and the server refuses them anyway — and neither is anybody already
 * on it, so ticking twice cannot double-book a day.
 */
@Composable
internal fun AddToDayDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val open = state.addToDay ?: return
    val candidates = state.addable(open.search)

    ZillitDialogShell(
        title = "Add to ${EpochDate.date(state.shootDate).ifEmpty { "the day" }}",
        subtitle = "${open.chosen.size} chosen",
        visible = true,
        onDismiss = { onEvent(AdEvent.CancelAddToDay) },
        icon = ZillitIcons.UserPlus,
    ) {
        ZillitSearchField(
            value = open.search,
            onValueChange = { onEvent(AdEvent.AddSearch(it)) },
            placeholder = "Search the register",
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = open.callTime,
            onValueChange = { onEvent(AdEvent.AddCallTime(it)) },
            label = "Call time (optional)",
            placeholder = "07:00",
        )

        CandidateList(candidates, open.chosen, open.search, onEvent)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AdEvent.CancelAddToDay) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Add",
                onClick = { onEvent(AdEvent.ConfirmAddToDay) },
                loading = open.saving,
                enabled = open.ready && !open.saving,
            )
        }
    }
}

@Composable
private fun CandidateList(
    candidates: List<Artiste>,
    chosen: Set<String>,
    search: String,
    onEvent: (AdEvent) -> Unit,
) {
    if (candidates.isEmpty()) {
        ZillitText(
            text = if (search.isBlank()) {
                "Everyone on the register is already on this day."
            } else {
                "Nobody on the register matches that."
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        candidates.forEach { artiste ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitCheckbox(
                    checked = artiste.id in chosen,
                    onCheckedChange = { onEvent(AdEvent.ToggleArtiste(artiste.id)) },
                )
                ZillitText(text = artiste.name, modifier = Modifier.weight(1f), maxLines = 1)
                ZillitText(
                    text = artiste.category.label,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

/**
 * Blocking an artiste.
 *
 * The reason is optional on the wire but asked for here: it is recorded
 * against the person and read by whoever wonders later why they are not on
 * the call.
 */
@Composable
internal fun BlockDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val open = state.block ?: return

    ZillitDialogShell(
        title = "Block ${open.artiste.name}",
        subtitle = "They stay on the register but cannot be added to a day",
        visible = true,
        onDismiss = { onEvent(AdEvent.CancelBlock) },
        icon = ZillitIcons.Warning,
    ) {
        ZillitTextField(
            value = open.reason,
            onValueChange = { onEvent(AdEvent.BlockReason(it)) },
            label = "Why (optional)",
            placeholder = "Recorded against the artiste",
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AdEvent.CancelBlock) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Block",
                onClick = { onEvent(AdEvent.ConfirmBlock) },
                variant = ButtonVariant.Danger,
                loading = open.saving,
                enabled = !open.saving,
            )
        }
    }
}

/**
 * Sending the day.
 *
 * Confirmed rather than done on a click: submitting locks the day for
 * everyone, and an AD who sends the wrong one has to ask production to
 * reopen it.
 */
@Composable
internal fun SubmitDayDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    if (!state.confirmSubmit) return

    ZillitDialogShell(
        title = "Submit ${EpochDate.date(state.shootDate).ifEmpty { "this day" }}?",
        subtitle = "${state.onToday} on the call · ${state.unsignedToday} not yet signed",
        visible = true,
        onDismiss = { onEvent(AdEvent.CancelSubmitDay) },
        icon = ZillitIcons.Send,
    ) {
        ZillitText(
            text = if (state.unsignedToday > 0) {
                "${state.unsignedToday} artiste(s) have not signed yet. Submitting locks the day " +
                    "and they will have to be chased another way."
            } else {
                "Everyone has signed. Submitting locks the day and sends it on."
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Not yet",
                onClick = { onEvent(AdEvent.CancelSubmitDay) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Submit day",
                onClick = { onEvent(AdEvent.ConfirmSubmitDay) },
                variant = ButtonVariant.Danger,
            )
        }
    }
}

private const val LIST_HEIGHT = 240
