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
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.PermanentState
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.ui.AllocationTab
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState

/**
 * Coordinator → Permanent Allocations.
 *
 * A driver and a vehicle kept for one group of passengers, every shoot day.
 * Three things about the page are not obvious from the list:
 *
 *  - a completed allocation is hidden entirely. It is end-of-life, and
 *    leaving it in would only inflate the All tab and the counts.
 *  - the pill reads the authoring status and the runtime one together — see
 *    [PermanentTrip.state] — because a fresh draft disagrees with itself.
 *  - promoting a draft is a full re-send, so the server's validator runs and
 *    names whichever field is still missing.
 */
@Composable
internal fun AllocationsPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val counts = state.allocationCounts
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = str(S.allocations),
            description = str(
                S.desktop_hub_allocation_count,
                state.visibleAllocations.size.toString(),
            ),
            actions = {
                ZillitButton(
                    text = str(S.desktop_hub_new_allocation),
                    onClick = { onEvent(HubEvent.NewAllocation) },
                    leadingIcon = ZillitIcons.Add,
                )
            },
        )

        ZillitTabStrip(
            tabs = AllocationTab.entries.map { ZillitTab(it.name, it.label(), counts[it] ?: 0) },
            activeId = state.allocationTab.name,
            onSelect = { id -> onEvent(HubEvent.AllocationTabChanged(AllocationTab.valueOf(id))) },
        )

        HubSearch(
            value = state.allocationSearch,
            placeholder = str(S.desktop_hub_allocation_search_hint),
            onValueChange = { onEvent(HubEvent.AllocationSearchChanged(it)) },
        )

        if (state.visibleAllocations.isEmpty()) {
            ZillitEmptyState(
                title = str(S.desktop_hub_no_allocations),
                icon = ZillitIcons.Link,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ZillitLazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                items(state.visibleAllocations, key = { it.id }) { trip ->
                    AllocationCard(
                        trip = trip,
                        busy = state.busyRowId == trip.id,
                        driverName = state.driverName(trip.driverId),
                        vehicleLabel = state.vehicleLabel(trip.vehicleId),
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun AllocationCard(
    trip: PermanentTrip,
    busy: Boolean,
    driverName: String,
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
                    text = trip.title.ifBlank { str(S.desktop_hub_untitled_allocation) },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (trip.isExclusive) {
                    ZillitStatusPill(
                        label = str(S.desktop_hub_exclusive),
                        tone = com.zillit.desktop.core.designsystem.component.StatusTone.Escalated,
                    )
                }
                ZillitStatusPill(label = trip.state.label(), tone = trip.state.tone(), dot = true)
            }
            HubRouteLine(trip.pickup, trip.dropoff)
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(label = str(S.driver), value = driverName, modifier = Modifier.weight(1f))
                HubFact(label = str(S.vehicle), value = vehicleLabel, modifier = Modifier.weight(1f))
                HubFact(
                    label = str(S.txt_passengers),
                    value = trip.passengers.size.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(
                    label = str(S.desktop_hub_active_days),
                    value = trip.activeDays.describe(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_hub_full_day_trip),
                    value = if (trip.isFullDayTrip) str(S.yes) else str(S.no),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitButton(
                    text = str(S.edit),
                    onClick = { onEvent(HubEvent.EditAllocation(trip)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
                ZillitButton(
                    text = trip.state.toggleLabel(),
                    onClick = { onEvent(HubEvent.ToggleAllocationState(trip)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
                // Swapping is for a running allocation: there is nothing to
                // swap out of a draft, and the server logs a swap as a change
                // of hands on a live one.
                if (trip.state != PermanentState.Draft) {
                    ZillitButton(
                        text = str(S.swap_driver),
                        onClick = { onEvent(HubEvent.SwapAllocationDriver(trip)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                    ZillitButton(
                        text = str(S.desktop_hub_swap_vehicle),
                        onClick = { onEvent(HubEvent.SwapAllocationVehicle(trip)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                }
                ZillitButton(
                    text = str(S.delete),
                    onClick = { onEvent(HubEvent.DeleteAllocation(trip)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
            }
            if (trip.swapHistory.isNotEmpty()) {
                ZillitText(
                    text = str(S.desktop_hub_swap_count, trip.swapHistory.size.toString()),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

/** What the one state button does next, which depends on where it is now. */
private fun PermanentState.toggleLabel(): String = when (this) {
    PermanentState.Draft -> str(S.dm_action_activate)
    PermanentState.Paused -> str(S.desktop_resume)
    else -> str(S.desktop_pause)
}

internal fun AllocationTab.label(): String = when (this) {
    AllocationTab.All -> str(S.all)
    AllocationTab.Active -> str(S.active)
    AllocationTab.Drafts -> str(S.cs_drafts)
    AllocationTab.Paused -> str(S.desktop_drive_upload_paused)
}
