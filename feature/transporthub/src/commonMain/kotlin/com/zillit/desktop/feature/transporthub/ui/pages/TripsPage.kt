@file:Suppress("LongMethod", "TooManyFunctions") // A row lays out a dozen fields; splitting it hides the layout.

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
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.TripTab

/**
 * Coordinator → Trips.
 *
 * The list mixes real trips and ride requests — the server says which in
 * `resource_type` — and the difference matters for Delete, which has to go to
 * the route that owns the row. A ride request already past acting on hides
 * the button entirely rather than offering a call the server will refuse.
 */
@Composable
internal fun TripsPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = str(S.trips),
            description = str(S.desktop_hub_trip_count, state.visibleTrips.size.toString()),
            actions = {
                ZillitButton(
                    text = str(S.desktop_hub_new_trip),
                    onClick = { onEvent(HubEvent.NewTrip) },
                    leadingIcon = ZillitIcons.Add,
                )
            },
        )
        TripListBody(state, onEvent)
    }
}

/**
 * Driver and passenger → My Trips.
 *
 * The same list, without the tabs and the create button: their server scope
 * already narrows it to their own trips, so a status filter over three rows
 * would be chrome.
 */
@Composable
internal fun MyTripsPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = str(S.desktop_hub_my_trips),
            description = str(S.desktop_hub_trip_count, state.visibleTrips.size.toString()),
        )
        HubSearch(
            value = state.tripSearch,
            placeholder = str(S.desktop_hub_trip_search_hint),
            onValueChange = { onEvent(HubEvent.TripSearchChanged(it)) },
        )
        TripRows(state, onEvent)
    }
}

@Composable
private fun TripListBody(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    ZillitTabStrip(
        tabs = TripTab.entries.map { ZillitTab(it.name, it.label()) },
        activeId = state.tripTab.name,
        onSelect = { id -> onEvent(HubEvent.TripTabChanged(TripTab.valueOf(id))) },
    )
    HubSearch(
        value = state.tripSearch,
        placeholder = str(S.desktop_hub_trip_search_hint),
        onValueChange = { onEvent(HubEvent.TripSearchChanged(it)) },
    )
    TripRows(state, onEvent)
}

@Composable
private fun TripRows(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    if (state.visibleTrips.isEmpty()) {
        ZillitEmptyState(
            title = str(S.desktop_hub_no_trips),
            icon = ZillitIcons.Send,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    ZillitLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(state.visibleTrips, key = { it.id }) { trip ->
            TripRow(
                trip = trip,
                busy = state.busyRowId == trip.id,
                coordinator = state.role == HubRole.Coordinator,
                driverName = trip.driverName.ifBlank { state.driverName(trip.driverId) },
                vehicleLabel = trip.vehicleLabel.ifBlank { state.vehicleLabel(trip.vehicleId) },
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun TripRow(
    trip: Trip,
    busy: Boolean,
    coordinator: Boolean,
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
                    text = trip.title.ifBlank { str(S.desktop_hub_untitled_trip) },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // A ride request in this list is labelled as one: the two
                // behave differently, and a coordinator should not have to
                // guess why Delete is missing from some rows.
                if (trip.kind == TripResourceKind.RideRequest) {
                    ZillitStatusPill(label = str(S.ride_requests), tone = StatusToneRequest)
                }
                ZillitStatusPill(
                    label = trip.status.label(),
                    tone = trip.status.tone(),
                    dot = true,
                )
            }
            HubRouteLine(trip.pickup, trip.dropoff)
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(
                    label = str(S.driver),
                    value = driverName,
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.vehicle),
                    value = vehicleLabel,
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.txt_passengers),
                    value = trip.passengers.size.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            if (trip.activeDays.isNotEmpty()) {
                HubFact(label = str(S.desktop_hub_active_days), value = trip.activeDays.describe())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitButton(
                    text = str(S.view),
                    onClick = { onEvent(HubEvent.OpenTrip(trip.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                if (coordinator && !trip.status.isTerminal) {
                    ZillitButton(
                        text = str(S.docusign_action_remind),
                        onClick = { onEvent(HubEvent.RemindTrip(trip.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                }
                // Hidden rather than disabled on a row the server cannot act
                // on: a ride request already completed, cancelled or refused.
                if (coordinator && !trip.deleteIsPointless) {
                    ZillitButton(
                        text = if (trip.kind == TripResourceKind.RideRequest) {
                            str(S.cancel)
                        } else {
                            str(S.delete)
                        },
                        onClick = { onEvent(HubEvent.DeleteTrip(trip)) },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

internal fun TripTab.label(): String = when (this) {
    TripTab.All -> str(S.all)
    TripTab.Pending -> str(S.pending)
    TripTab.Assigned -> str(S.assigned)
    TripTab.InTransit -> str(S.in_progress)
    TripTab.Completed -> str(S.completed)
    TripTab.Cancelled -> str(S.cancelled)
    TripTab.Rejected -> str(S.rejected)
}

/** The transitions a coordinator may drive a trip through, in order. */
internal val TRIP_TRANSITIONS: List<TripStatus> = listOf(
    TripStatus.Assigned,
    TripStatus.EnRoutePickup,
    TripStatus.AtPickup,
    TripStatus.InTransit,
    TripStatus.AtDropoff,
    TripStatus.Completed,
    TripStatus.Cancelled,
)

private val StatusToneRequest = com.zillit.desktop.core.designsystem.component.StatusTone.Progress
