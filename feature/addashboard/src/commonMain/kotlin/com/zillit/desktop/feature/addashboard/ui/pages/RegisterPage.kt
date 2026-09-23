package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState

/** The production's artiste roster, searchable and filterable. */
@Composable
internal fun ColumnScope.RegisterPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.registerSearch,
            onValueChange = { onEvent(AdEvent.RegisterSearch(it)) },
            placeholder = str(S.desktop_ad_search_register_placeholder),
            modifier = Modifier.width(SEARCH_WIDTH.dp),
        )
        ZillitText(
            text = str(S.docusign_field_of, state.register.size, state.artistes.size),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Chip(str(S.all), state.statusFilter == null) { onEvent(AdEvent.FilterStatus(null)) }
        ArtisteStatus.entries.forEach { status ->
            Chip(status.label, state.statusFilter == status) { onEvent(AdEvent.FilterStatus(status)) }
        }
    }

    if (state.register.isEmpty()) {
        if (!state.loading) {
            ZillitEmptyState(
                title = if (state.artistes.isEmpty()) {
                    str(S.desktop_ad_no_artistes_yet)
                } else {
                    str(S.desktop_nobody_matches)
                },
                message = if (state.artistes.isEmpty()) {
                    str(S.desktop_ad_no_artistes_yet_message)
                } else {
                    str(S.desktop_try_a_different_search_or_filter)
                },
                icon = ZillitIcons.Users,
            )
        }
        return
    }

    state.register.forEach { artiste -> ArtisteRow(artiste, onEvent) }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    ZillitButton(
        text = label,
        onClick = onClick,
        variant = if (selected) ButtonVariant.Secondary else ButtonVariant.Tertiary,
        size = ButtonSize.Small,
    )
}

@Composable
private fun ArtisteRow(artiste: Artiste, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = artiste.name, style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    text = listOf(
                        artiste.refNumber,
                        artiste.category.label,
                        artiste.engagement.label,
                        artiste.union,
                        // An agency artiste's agency is who to ring; a direct
                        // one has none, so it is only shown when there is one.
                        artiste.agencyName,
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
            ZillitStatusPill(label = artiste.status.label, tone = artiste.status.tone())

            // Every one of these lands in AdViewModel, which answers a press
            // without posting rights by offering to ask an administrator.
            when (artiste.status) {
                ArtisteStatus.Blocked -> ZillitButton(
                    text = str(S.desktop_unblock),
                    onClick = { onEvent(AdEvent.Unblock(artiste.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )

                ArtisteStatus.Verified -> ZillitButton(
                    text = str(S.block),
                    onClick = { onEvent(AdEvent.StartBlock(artiste)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )

                else -> {
                    ZillitButton(
                        text = str(S.txt_verify),
                        onClick = { onEvent(AdEvent.Verify(artiste.id)) },
                        size = ButtonSize.Small,
                    )
                    ZillitButton(
                        text = str(S.block),
                        onClick = { onEvent(AdEvent.StartBlock(artiste)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

/** Every shoot day the production has run, newest first. */
@Composable
internal fun ColumnScope.ShootDaysPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    if (state.shootDays.isEmpty()) {
        if (!state.loading) {
            ZillitEmptyState(
                title = str(S.desktop_ad_no_shoot_days),
                message = str(S.desktop_ad_no_shoot_days_message),
                icon = ZillitIcons.Calendar,
            )
        }
        return
    }

    state.shootDays
        .sortedByDescending { it.shootDate ?: 0L }
        .forEach { day -> ShootDayRow(day, onEvent) }
}

@Composable
private fun ShootDayRow(day: AdShootDay, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = EpochDate.date(day.shootDate).ifEmpty { "—" },
                    style = ZillitTheme.typography.titleSmall,
                )
                ZillitText(
                    text = listOfNotNull(
                        day.dayNumber?.let { str(S.desktop_ad_day_number, it) },
                        day.unitName.takeIf { it.isNotBlank() },
                        day.location.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifEmpty { str(S.desktop_no_details) },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitStatusPill(label = day.status.label, tone = StatusTone.Neutral)
            day.shootDate?.let { date ->
                ZillitButton(
                    text = str(S.recce_open),
                    onClick = { onEvent(AdEvent.ChangeDay(date)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

/** Verified reads as done, blocked as a refusal, the rest as still in progress. */
private fun ArtisteStatus.tone(): StatusTone = when (this) {
    ArtisteStatus.Verified -> StatusTone.Done
    ArtisteStatus.Blocked -> StatusTone.Rejected
    ArtisteStatus.Pending -> StatusTone.Pending
    ArtisteStatus.Draft -> StatusTone.Neutral
}

private const val SEARCH_WIDTH = 320
