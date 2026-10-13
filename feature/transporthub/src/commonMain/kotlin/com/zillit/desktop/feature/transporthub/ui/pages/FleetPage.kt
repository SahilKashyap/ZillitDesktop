@file:Suppress("LongMethod") // A card lays out a dozen fields; splitting it hides the layout.

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.Vehicle
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocation
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocationFilter
import com.zillit.desktop.feature.transporthub.domain.VehicleFilter
import com.zillit.desktop.feature.transporthub.domain.VehicleOwnership
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState

/**
 * Coordinator → Fleet.
 *
 * The filter axes are applied by the **server**, not here: status, type,
 * ownership and the free-text search all go out as query parameters, because
 * the search matches on fields the client does not hold (the owner's details
 * on a hired car). Only `deleted` is filtered locally, in the parser.
 */
@Composable
internal fun FleetPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val filter = state.vehicleFilter
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = str(S.fleet),
            description = str(S.desktop_hub_vehicle_count, state.vehicles.size.toString()),
            actions = {
                ZillitButton(
                    text = str(S.desktop_hub_new_vehicle),
                    onClick = { onEvent(HubEvent.NewVehicle) },
                    leadingIcon = ZillitIcons.Add,
                )
            },
        )

        ZillitTabStrip(
            tabs = VehicleAllocationFilter.entries.map { ZillitTab(it.name, it.label()) },
            activeId = filter.allocation.name,
            onSelect = { id ->
                onEvent(
                    HubEvent.VehicleFilterChanged(
                        filter.copy(allocation = VehicleAllocationFilter.valueOf(id)),
                    ),
                )
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            HubSearch(
                value = filter.search,
                placeholder = str(S.desktop_hub_vehicle_search_hint),
                onValueChange = { onEvent(HubEvent.VehicleSearchChanged(it)) },
            )
            ZillitSelect(
                value = filter.ownership,
                options = VehicleOwnership.entries,
                onSelect = { onEvent(HubEvent.VehicleFilterChanged(filter.copy(ownership = it))) },
                label = { it.label() },
                showInitials = false,
                modifier = Modifier.width(PICKER_WIDTH),
            )
            ZillitSelect(
                value = filter.types.firstOrNull().orEmpty(),
                options = listOf("") + state.vehicleTypes.map { it.label },
                onSelect = { picked ->
                    onEvent(
                        HubEvent.VehicleFilterChanged(
                            filter.copy(types = if (picked.isBlank()) emptyList() else listOf(picked)),
                        ),
                    )
                },
                label = { it.ifBlank { str(S.all) } },
                showInitials = false,
                modifier = Modifier.width(PICKER_WIDTH),
            )
            if (filter.activeAxisCount > 0) {
                ZillitButton(
                    text = str(S.dd_empty_clear_filters),
                    onClick = {
                        onEvent(HubEvent.VehicleFilterChanged(VehicleFilter()))
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }

        if (state.vehicles.isEmpty()) {
            ZillitEmptyState(
                title = str(S.desktop_hub_no_vehicles),
                icon = ZillitIcons.Transport,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ZillitLazyVerticalGrid(
                columns = GridCells.Adaptive(CARD_MIN_WIDTH),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                items(state.vehicles, key = { it.id }) { vehicle ->
                    VehicleCard(
                        vehicle = vehicle,
                        busy = state.busyRowId == vehicle.id,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(vehicle: Vehicle, busy: Boolean, onEvent: (HubEvent) -> Unit) {
    ZillitSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = vehicle.displayName.ifBlank { str(S.desktop_hub_unnamed_vehicle) },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatusPill(
                    label = vehicle.allocation.label(),
                    tone = vehicle.allocation.tone(),
                    dot = true,
                )
            }
            ZillitText(
                text = listOf(vehicle.licensePlate, vehicle.vehicleType)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            if (vehicle.specLine.isNotBlank()) {
                ZillitText(
                    text = vehicle.specLine,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(
                    label = str(S.hint_capacity),
                    value = vehicle.capacity?.toString().orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_hub_ownership),
                    value = if (vehicle.isPrivate) {
                        str(S.desktop_hub_private)
                    } else {
                        vehicle.ownerName.ifBlank { str(S.desktop_hub_owner_managed) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitButton(
                    text = str(S.edit),
                    onClick = { onEvent(HubEvent.EditVehicle(vehicle)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
                // Maintenance is a toggle, not a cycle: the other three
                // allocation states are the server's to set when a car is
                // booked, and a coordinator cannot hand-pick them.
                ZillitButton(
                    text = if (vehicle.allocation == VehicleAllocation.Maintenance) {
                        str(S.desktop_hub_mark_available)
                    } else {
                        str(S.desktop_hub_mark_maintenance)
                    },
                    onClick = {
                        onEvent(
                            HubEvent.SetVehicleAllocation(
                                vehicle.id,
                                if (vehicle.allocation == VehicleAllocation.Maintenance) {
                                    VehicleAllocation.Available
                                } else {
                                    VehicleAllocation.Maintenance
                                },
                            ),
                        )
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
                ZillitButton(
                    text = str(S.delete),
                    onClick = { onEvent(HubEvent.DeleteVehicle(vehicle)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
            }
        }
    }
}

internal fun VehicleAllocationFilter.label(): String = when (this) {
    VehicleAllocationFilter.All -> str(S.all)
    VehicleAllocationFilter.Available -> str(S.available)
    VehicleAllocationFilter.InUse -> str(S.desktop_hub_in_use)
    VehicleAllocationFilter.Maintenance -> str(S.maintenance)
}

internal fun VehicleOwnership.label(): String = when (this) {
    VehicleOwnership.All -> str(S.all)
    VehicleOwnership.Private -> str(S.desktop_hub_private)
    VehicleOwnership.OwnerManaged -> str(S.desktop_hub_owner_managed)
}

private val CARD_MIN_WIDTH = 320.dp
internal val PICKER_WIDTH = 180.dp
