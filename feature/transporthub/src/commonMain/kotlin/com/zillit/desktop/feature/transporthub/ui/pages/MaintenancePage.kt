@file:Suppress("LongMethod") // A card lays out a dozen fields; splitting it hides the layout.

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.daysFrom
import com.zillit.desktop.feature.transporthub.data.hubDate
import com.zillit.desktop.feature.transporthub.domain.MaintenanceRecord
import com.zillit.desktop.feature.transporthub.domain.MaintenanceStatus
import com.zillit.desktop.feature.transporthub.domain.MaintenanceType
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.kit.HubGlyphs
import com.zillit.desktop.feature.transporthub.ui.kit.HubPageHeader
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.MaintenanceTab

/**
 * Coordinator → Maintenance.
 *
 * The vehicles booked in for work, each with the web's "due soon" chip — the
 * one piece of the card that is worth more than the date itself, because
 * overdue work is what a coordinator is looking for when they open this page.
 */
@Composable
internal fun MaintenancePage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val counts = state.maintenanceCounts
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        HubPageHeader(
            icon = HubGlyphs.Wrench,
            title = str(S.maintenance),
            description = str(
                S.desktop_hub_maintenance_count,
                state.visibleMaintenance.size.toString(),
            ),
            actions = {
                ZillitButton(
                    text = str(S.schedule),
                    onClick = { onEvent(HubEvent.NewMaintenance) },
                    leadingIcon = ZillitIcons.Add,
                )
            },
        )
        ZillitTabStrip(
            tabs = MaintenanceTab.entries.map { ZillitTab(it.name, it.label(), counts[it] ?: 0) },
            activeId = state.maintenanceTab.name,
            onSelect = { id -> onEvent(HubEvent.MaintenanceTabChanged(MaintenanceTab.valueOf(id))) },
        )
        HubSearch(
            value = state.maintenanceSearch,
            placeholder = str(S.desktop_hub_maintenance_search_hint),
            onValueChange = { onEvent(HubEvent.MaintenanceSearchChanged(it)) },
        )
        if (state.visibleMaintenance.isEmpty()) {
            ZillitEmptyState(
                title = str(S.desktop_hub_no_maintenance),
                icon = ZillitIcons.Tools,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        ZillitLazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            items(state.visibleMaintenance, key = { it.id }) { record ->
                MaintenanceCard(
                    record = record,
                    busy = state.busyRowId == record.id,
                    nowMs = state.nowMs,
                    vehicleLabel = record.vehicleLabel.ifBlank {
                        state.vehicleLabel(record.vehicleId)
                    },
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun MaintenanceCard(
    record: MaintenanceRecord,
    busy: Boolean,
    nowMs: Long,
    vehicleLabel: String,
    onEvent: (HubEvent) -> Unit,
) {
    ZillitSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = record.type.label(),
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                record.dueChip(nowMs)?.let { (label, tone) ->
                    ZillitStatusPill(label = label, tone = tone)
                }
                ZillitStatusPill(
                    label = record.status.label(),
                    tone = record.status.tone(),
                    dot = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(
                    label = str(S.vehicle),
                    value = vehicleLabel,
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_csync_when_scheduled),
                    value = record.scheduledDateMs.hubDate(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_hub_estimated_cost),
                    value = record.estimatedCost?.toString().orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_hub_odometer),
                    value = record.odometerReading?.let { "$it km" }.orEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
            if (record.description.isNotBlank()) {
                HubFact(label = str(S.notes), value = record.description)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                if (!record.isDone) {
                    ZillitButton(
                        text = str(S.dm_action_complete),
                        onClick = { onEvent(HubEvent.CompleteMaintenance(record)) },
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                }
                ZillitButton(
                    text = str(S.delete),
                    onClick = { onEvent(HubEvent.DeleteMaintenance(record)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
            }
        }
    }
}

/**
 * How soon the work is due, as the web's chip says it.
 *
 * Nothing for completed work, and nothing for a booking more than a week out:
 * the date beside it already says that, and a chip on every row would stop
 * the overdue ones standing out.
 */
private fun MaintenanceRecord.dueChip(nowMs: Long): Pair<String, StatusTone>? {
    if (isDone) return null
    val days = scheduledDateMs.daysFrom(nowMs) ?: return null
    return when {
        days < 0 -> str(S.desktop_tasks_hint_overdue, (-days).toString()) to StatusTone.Rejected
        days == 0 -> str(S.today) to StatusTone.Pending
        days == 1 -> str(S.tomorrow) to StatusTone.Progress
        days <= DUE_SOON_DAYS -> str(S.desktop_tasks_in_days, days.toString()) to StatusTone.Progress
        else -> null
    }
}

internal fun MaintenanceTab.label(): String = when (this) {
    MaintenanceTab.All -> str(S.all)
    MaintenanceTab.Scheduled -> str(S.desktop_csync_when_scheduled)
    MaintenanceTab.Completed -> str(S.completed)
}

internal fun MaintenanceStatus.label(): String = when (this) {
    MaintenanceStatus.Scheduled -> str(S.desktop_csync_when_scheduled)
    MaintenanceStatus.InProgress -> str(S.in_progress)
    MaintenanceStatus.Completed -> str(S.completed)
}

internal fun MaintenanceStatus.tone(): StatusTone = when (this) {
    MaintenanceStatus.Scheduled -> StatusTone.Ready
    MaintenanceStatus.InProgress -> StatusTone.Pending
    MaintenanceStatus.Completed -> StatusTone.Done
}

internal fun MaintenanceType.label(): String = when (this) {
    MaintenanceType.OilChange -> str(S.desktop_hub_oil_change)
    MaintenanceType.TireRotation -> str(S.desktop_hub_tire_rotation)
    MaintenanceType.BrakeCheck -> str(S.desktop_hub_brake_check)
    MaintenanceType.Inspection -> str(S.desktop_hub_inspection)
    MaintenanceType.Repair -> str(S.desktop_hub_repair)
    MaintenanceType.Cleaning -> str(S.desktop_csync_cleaning)
    MaintenanceType.Other -> str(S.other)
}

/** A booking further out than this gets no chip — the web's own window. */
private const val DUE_SOON_DAYS = 7
