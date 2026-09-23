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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
        title = str(
            S.desktop_ad_add_to_day_title,
            EpochDate.date(state.shootDate).ifEmpty { str(S.desktop_ad_the_day) },
        ),
        subtitle = str(S.desktop_ad_n_chosen, open.chosen.size),
        visible = true,
        onDismiss = { onEvent(AdEvent.CancelAddToDay) },
        icon = ZillitIcons.UserPlus,
    ) {
        ZillitSearchField(
            value = open.search,
            onValueChange = { onEvent(AdEvent.AddSearch(it)) },
            placeholder = str(S.desktop_ad_search_the_register),
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = open.callTime,
            onValueChange = { onEvent(AdEvent.AddCallTime(it)) },
            label = str(S.desktop_ad_call_time_optional),
            placeholder = "07:00",
        )

        CandidateList(candidates, open.chosen, open.search, onEvent)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AdEvent.CancelAddToDay) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.add),
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
                str(S.desktop_ad_everyone_already_on_day)
            } else {
                str(S.desktop_ad_nobody_on_register_matches)
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
        title = str(S.desktop_ad_block_title, open.artiste.name),
        subtitle = str(S.desktop_ad_block_subtitle),
        visible = true,
        onDismiss = { onEvent(AdEvent.CancelBlock) },
        icon = ZillitIcons.Warning,
    ) {
        ZillitTextField(
            value = open.reason,
            onValueChange = { onEvent(AdEvent.BlockReason(it)) },
            label = str(S.desktop_ad_why_optional),
            placeholder = str(S.desktop_ad_recorded_against_artiste),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AdEvent.CancelBlock) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.block),
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
        title = str(
            S.desktop_ad_submit_day_title,
            EpochDate.date(state.shootDate).ifEmpty { str(S.desktop_ad_this_day) },
        ),
        subtitle = str(S.desktop_ad_submit_day_subtitle, state.onToday, state.unsignedToday),
        visible = true,
        onDismiss = { onEvent(AdEvent.CancelSubmitDay) },
        icon = ZillitIcons.Send,
    ) {
        ZillitText(
            text = if (state.unsignedToday > 0) {
                str(S.desktop_ad_submit_unsigned_warning, state.unsignedToday)
            } else {
                str(S.desktop_ad_submit_all_signed)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.desktop_ad_not_yet),
                onClick = { onEvent(AdEvent.CancelSubmitDay) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.desktop_ad_submit_day),
                onClick = { onEvent(AdEvent.ConfirmSubmitDay) },
                variant = ButtonVariant.Danger,
            )
        }
    }
}

private const val LIST_HEIGHT = 240
