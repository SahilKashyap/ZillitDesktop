package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState

/**
 * The day the AD is running: who is called, who has turned up, and whether
 * it has been sent.
 *
 * The date bar comes first because an AD correcting yesterday needs to know
 * at a glance that they are not looking at today.
 */
@Composable
internal fun ColumnScope.TodayPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    DayBar(state, onEvent)

    // Said once, at the top: every control below is inert on a sent day, and
    // a screen full of dead buttons with no explanation is the worse failure.
    if (state.today?.editable == false) {
        ZillitNotice(
            text = "This day has been ${state.today.status.label.lowercase()} and can no longer " +
                "be changed. Ask production to reopen it if something is wrong.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatTile(label = "On the call", value = state.onToday.toString())
        ZillitStatTile(
            label = "Turned up",
            value = state.presentToday.toString(),
            tone = if (state.presentToday == state.onToday) StatusTone.Done else StatusTone.Pending,
        )
        ZillitStatTile(
            label = "Not yet signed",
            value = state.unsignedToday.toString(),
            // What stops a day being paid, so it is called out rather than
            // left to be counted off the list.
            tone = if (state.unsignedToday == 0) StatusTone.Done else StatusTone.Pending,
        )
    }

    if (state.dayList.isEmpty()) {
        if (!state.loading) {
            ZillitEmptyState(
                title = "Nobody on this day yet",
                message = "Add artistes from the register to build the call.",
                icon = ZillitIcons.Users,
            )
        }
        return
    }

    state.dayList.forEach { entry -> DayRow(entry, state.dayIsOpen, onEvent) }
}

@Composable
private fun ColumnScope.DayBar(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = "Previous",
            onClick = { onEvent(AdEvent.ChangeDay(state.shootDate - AdDates.MILLIS_PER_DAY)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = EpochDate.date(state.shootDate).ifEmpty { "—" },
                style = ZillitTheme.typography.titleMedium,
            )
            state.today?.let { day ->
                ZillitText(
                    text = listOfNotNull(
                        day.dayNumber?.let { "Day $it" },
                        day.unitName.takeIf { it.isNotBlank() },
                        day.location.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifEmpty { "No day details" },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
        state.today?.let { ZillitStatusPill(label = it.status.label, tone = StatusTone.Neutral) }
        ZillitButton(
            text = "Next",
            onClick = { onEvent(AdEvent.ChangeDay(state.shootDate + AdDates.MILLIS_PER_DAY)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        if (state.dayIsOpen) {
            ZillitButton(
                text = "Add artistes",
                onClick = { onEvent(AdEvent.OpenAddToDay) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.UserPlus,
            )
            ZillitButton(
                text = "Submit day",
                onClick = { onEvent(AdEvent.AskSubmitDay) },
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun DayRow(entry: SupportingArtistDay, editable: Boolean, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = entry.artisteName.ifBlank { "Unnamed" },
                    style = ZillitTheme.typography.titleSmall,
                )
                ZillitText(
                    text = listOf(
                        entry.category.label,
                        listOf(entry.callTime, entry.wrapTime)
                            .filter { it.isNotBlank() }
                            .joinToString(" – "),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }

            entry.signMethod.takeIf { it.isNotBlank() }?.let {
                ZillitStatusPill(label = it, tone = StatusTone.Done)
            }

            if (editable) {
                // Three states, three buttons: an AD marking a call does it
                // dozens of times an hour and a dropdown per row is slower.
                AttendanceStatus.entries
                    .filter { it != AttendanceStatus.Unknown }
                    .forEach { status ->
                        ZillitButton(
                            text = status.label,
                            onClick = { onEvent(AdEvent.SetAttendance(entry.id, status)) },
                            variant = if (entry.attendance == status) {
                                ButtonVariant.Secondary
                            } else {
                                ButtonVariant.Tertiary
                            },
                            size = ButtonSize.Small,
                        )
                    }
                ZillitButton(
                    text = "Remove",
                    onClick = { onEvent(AdEvent.RemoveFromDay(entry.id)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                )
            } else {
                ZillitStatusPill(label = entry.attendance.label, tone = StatusTone.Neutral)
            }
        }
    }
}
