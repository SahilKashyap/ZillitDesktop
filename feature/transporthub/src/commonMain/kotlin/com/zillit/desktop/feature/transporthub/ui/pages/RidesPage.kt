@file:Suppress("LongMethod", "TooManyFunctions") // A card lays out a dozen fields; splitting it hides the layout.

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.RideTab

/**
 * Coordinator → Ride Requests.
 *
 * Every count on the page is taken from the same scoped list as the rows, so
 * the tiles and the tab chips can never disagree with what is underneath
 * them — which they would the moment "my requests only" narrowed one and not
 * the other.
 */
@Composable
internal fun RidesPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val counts = state.rideCounts
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = str(S.ride_requests),
            description = str(S.desktop_hub_ride_count, state.visibleRides.size.toString()),
            actions = {
                ZillitButton(
                    text = str(S.desktop_hub_active_cars),
                    onClick = { onEvent(HubEvent.ShowActiveCars) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Transport,
                )
                ZillitButton(
                    text = str(S.desktop_hub_new_ride),
                    onClick = { onEvent(HubEvent.NewRide) },
                    leadingIcon = ZillitIcons.Add,
                )
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitStatTile(
                label = str(S.pending),
                value = (counts[RideTab.Pending] ?: 0).toString(),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Clock,
                onClick = { onEvent(HubEvent.RideTabChanged(RideTab.Pending)) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_hub_urgent_pending),
                value = state.urgentPendingRides.toString(),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Siren,
                onClick = { onEvent(HubEvent.RideTabChanged(RideTab.Pending)) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.active),
                value = (counts[RideTab.Active] ?: 0).toString(),
                tone = StatusTone.InTransit,
                icon = ZillitIcons.Transport,
                onClick = { onEvent(HubEvent.RideTabChanged(RideTab.Active)) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.completed),
                value = (counts[RideTab.Completed] ?: 0).toString(),
                tone = StatusTone.Done,
                icon = ZillitIcons.Check,
                onClick = { onEvent(HubEvent.RideTabChanged(RideTab.Completed)) },
                modifier = Modifier.weight(1f),
            )
        }

        ZillitTabStrip(
            tabs = RideTab.entries.map { ZillitTab(it.name, it.label(), counts[it] ?: 0) },
            activeId = state.rideTab.name,
            onSelect = { id -> onEvent(HubEvent.RideTabChanged(RideTab.valueOf(id))) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            HubSearch(
                value = state.rideSearch,
                placeholder = str(S.desktop_hub_ride_search_hint),
                onValueChange = { onEvent(HubEvent.RideSearchChanged(it)) },
            )
            ZillitSwitch(
                checked = state.onlyMyRides,
                onCheckedChange = { onEvent(HubEvent.OnlyMyRidesChanged(it)) },
                label = str(S.desktop_hub_only_my_requests),
            )
        }

        RideRows(state, onEvent)
    }
}

/**
 * Driver → Assigned Rides, and passenger → Ride Requests.
 *
 * The same rows; the actions differ by role, and the server has already
 * scoped the list to theirs.
 */
@Composable
internal fun MyRidesPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = if (state.role == HubRole.Driver) {
                str(S.assigned_rides)
            } else {
                str(S.ride_requests)
            },
            description = str(S.desktop_hub_ride_count, state.visibleRides.size.toString()),
            actions = {
                // A passenger raises their own requests; a driver is given
                // them, so the button is a passenger's only.
                if (state.role == HubRole.Passenger) {
                    ZillitButton(
                        text = str(S.desktop_hub_new_ride),
                        onClick = { onEvent(HubEvent.NewRide) },
                        leadingIcon = ZillitIcons.Add,
                    )
                }
            },
        )
        ZillitTabStrip(
            tabs = RideTab.entries.map { ZillitTab(it.name, it.label()) },
            activeId = state.rideTab.name,
            onSelect = { id -> onEvent(HubEvent.RideTabChanged(RideTab.valueOf(id))) },
        )
        RideRows(state, onEvent)
    }
}

@Composable
private fun RideRows(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    if (state.visibleRides.isEmpty()) {
        ZillitEmptyState(
            title = str(S.desktop_hub_no_rides),
            icon = ZillitIcons.Chat,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    ZillitLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(state.visibleRides, key = { it.id }) { ride ->
            RideCard(
                ride = ride,
                busy = state.busyRowId == ride.id,
                role = state.role,
                driverName = ride.assignedDriverName.ifBlank { state.driverName(ride.assignedDriverId) },
                vehicleLabel = ride.assignedVehicleLabel
                    .ifBlank { state.vehicleLabel(ride.assignedVehicleId) },
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun RideCard(
    ride: RideRequest,
    busy: Boolean,
    role: HubRole,
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
                    text = ride.requesterName.ifBlank {
                        ride.passengers.firstOrNull()?.name.orEmpty().ifBlank { "—" }
                    },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatusPill(label = ride.priority.label(), tone = ride.priority.tone())
                ZillitStatusPill(label = ride.status.label(), tone = ride.status.tone(), dot = true)
            }
            HubRouteLine(ride.pickup, ride.dropoff)
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(label = str(S.driver), value = driverName, modifier = Modifier.weight(1f))
                HubFact(label = str(S.vehicle), value = vehicleLabel, modifier = Modifier.weight(1f))
                HubFact(
                    label = str(S.txt_passengers),
                    value = ride.passengerCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            if (ride.notes.isNotBlank()) {
                HubFact(label = str(S.notes), value = ride.notes)
            }
            // Why a request was refused or called off is the first thing
            // anyone asks of a terminal row, so it is on the card, not
            // behind a detail view.
            ride.rejectionReason.takeIf { it.isNotBlank() }?.let {
                HubFact(label = str(S.reason), value = it)
            }
            ride.cancellationReason.takeIf { it.isNotBlank() && ride.rejectionReason.isBlank() }
                ?.let { HubFact(label = str(S.reason), value = it) }

            if (ride.stops.isNotEmpty()) {
                RideStops(ride, busy, role, onEvent)
            }
            RideActions(ride, busy, role, onEvent)
        }
    }
}

@Composable
private fun RideStops(
    ride: RideRequest,
    busy: Boolean,
    role: HubRole,
    onEvent: (HubEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitSectionLabel(text = str(S.recce_label_stops))
        ride.stops.forEachIndexed { index, stop ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = "${index + 1}. ${stop.location.display()}",
                    style = ZillitTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (stop.passengerName.isNotBlank()) {
                    ZillitText(
                        text = stop.passengerName,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
                if (stop.isCompleted) {
                    ZillitStatusPill(label = str(S.completed), tone = StatusTone.Done)
                } else if (role != HubRole.Passenger && ride.status.isActive) {
                    // The server addresses a stop by its position in the
                    // array, not by its id — so the index is what goes out.
                    ZillitButton(
                        text = str(S.dm_action_complete),
                        onClick = { onEvent(HubEvent.CompleteStop(ride.id, index)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/**
 * The buttons a row offers.
 *
 * Which ones depends on the status and the role together, and the rules are
 * the web's: a coordinator approves or declines only what is pending, assigns
 * once it is approved, and may call off anything not yet finished. A
 * passenger edits and cancels only their own pending request, because the
 * server refuses an edit once a trip is moving (`ride_request_not_editable`).
 */
@Composable
private fun RideActions(
    ride: RideRequest,
    busy: Boolean,
    role: HubRole,
    onEvent: (HubEvent) -> Unit,
) {
    val coordinator = role == HubRole.Coordinator
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (coordinator && ride.status == RideStatus.Pending) {
            ZillitButton(
                text = str(S.approve),
                onClick = { onEvent(HubEvent.ApproveRide(ride)) },
                size = ButtonSize.Small,
                enabled = !busy,
            )
            ZillitButton(
                text = str(S.decline),
                onClick = { onEvent(HubEvent.RejectRide(ride)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !busy,
            )
        }
        if (coordinator && (ride.status == RideStatus.Approved || ride.status == RideStatus.Assigned)) {
            ZillitButton(
                text = str(S.assign),
                onClick = { onEvent(HubEvent.AssignRide(ride)) },
                size = ButtonSize.Small,
                enabled = !busy,
            )
        }
        if (role != HubRole.Passenger && ride.status == RideStatus.Assigned) {
            ZillitButton(
                text = str(S.start),
                onClick = { onEvent(HubEvent.StartRide(ride)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !busy,
            )
        }
        if (role != HubRole.Passenger && ride.status.isActive) {
            ZillitButton(
                text = str(S.dm_action_complete),
                onClick = { onEvent(HubEvent.CompleteRide(ride)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !busy,
            )
            ZillitButton(
                text = str(S.recce_add_stop),
                onClick = { onEvent(HubEvent.AddRideStop(ride)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !busy,
            )
            ZillitButton(
                text = str(S.desktop_hub_add_passenger),
                onClick = { onEvent(HubEvent.AddRidePassenger(ride)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !busy,
            )
        }
        // The server accepts an edit while pending, approved or assigned, and
        // refuses it once the trip is in flight or over.
        if (ride.status in EDITABLE_STATUSES) {
            ZillitButton(
                text = str(S.edit),
                onClick = { onEvent(HubEvent.EditRide(ride)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !busy,
            )
        }
        if (!ride.status.isTerminal) {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CancelRide(ride)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !busy,
            )
        }
    }
}

internal fun RideTab.label(): String = when (this) {
    RideTab.Pending -> str(S.pending)
    RideTab.Active -> str(S.active)
    RideTab.Completed -> str(S.completed)
    RideTab.Rejected -> str(S.rejected)
}

private val EDITABLE_STATUSES = setOf(RideStatus.Pending, RideStatus.Approved, RideStatus.Assigned)
