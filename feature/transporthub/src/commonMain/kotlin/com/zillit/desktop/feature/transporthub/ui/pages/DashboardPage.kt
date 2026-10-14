@file:Suppress("LongMethod") // A row lays out a dozen fields; splitting it hides the layout.

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
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDate
import com.zillit.desktop.feature.transporthub.data.hubDateTime
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.ui.DashboardFilter
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState

/**
 * Coordinator → Dashboard. The hub's landing page, as it is on the web.
 *
 * Four counters over a Recent Trips card with the web's own five chips and a
 * search. The counters come from `/insights/dashboard` and the trend line
 * under each from its `*_yesterday` key — a deployment that ships neither
 * leaves the number blank and the trend reading "no change", rather than
 * replacing the page with an error, because the trip list below is still
 * worth having.
 */
@Composable
internal fun DashboardPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = str(S.dashboard),
            description = state.nowMs.hubDate(),
            actions = {
                ZillitButton(
                    text = str(S.desktop_hub_view_all_trips),
                    onClick = { onEvent(HubEvent.OpenAllTrips) },
                    leadingIcon = ZillitIcons.Send,
                )
            },
        )
        StatCards(state)
        ZillitSectionCard(
            title = str(S.recent_trips),
            modifier = Modifier.fillMaxWidth().weight(1f),
            action = {
                ZillitButton(
                    text = str(S.desktop_meet_see_all),
                    onClick = { onEvent(HubEvent.OpenAllTrips) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                DashboardFilter.entries.forEach { filter ->
                    ZillitChoiceChip(
                        label = filter.label(),
                        selected = state.dashboardFilter == filter,
                        onClick = { onEvent(HubEvent.DashboardFilterChanged(filter)) },
                    )
                }
            }
            HubSearch(
                value = state.dashboardSearch,
                placeholder = str(S.desktop_hub_trip_search_hint),
                onValueChange = { onEvent(HubEvent.DashboardSearchChanged(it)) },
            )
            RecentTrips(state, onEvent)
        }
    }
}

@Composable
private fun StatCards(state: HubUiState) {
    val insights = state.insights
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatTile(
            label = str(S.active_trips),
            value = insights.activeTrips.toString(),
            // The web spells out what makes up the number, because "active"
            // is four modes at once and the split is the useful part.
            sub = if (insights.activeTrips > 0) {
                str(
                    S.desktop_hub_in_transit_assigned,
                    insights.inTransitTotal.toString(),
                    insights.assignedTotal.toString(),
                )
            } else {
                null
            },
            tone = StatusTone.InTransit,
            icon = ZillitIcons.Send,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_hub_completed_today),
            value = insights.completedTotal.toString(),
            tone = StatusTone.Done,
            icon = ZillitIcons.Check,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_hub_pending_requests),
            value = state.pendingRideCount.toString(),
            sub = insights.rideRequestTotal
                ?.let { str(S.desktop_hub_of_total, it.toString()) },
            tone = StatusTone.Pending,
            icon = ZillitIcons.Clock,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_hub_available_vehicles),
            // The fleet block is the better answer when the server ships it:
            // it counts the whole fleet, not only the page of free cars.
            value = (insights.availableFromFleet ?: state.availableVehicles.size).toString(),
            sub = insights.fleetTotal?.let {
                str(
                    S.desktop_hub_of_fleet_in_use,
                    (insights.fleetInUse ?: 0).toString(),
                    it.toString(),
                )
            },
            tone = StatusTone.Ready,
            icon = ZillitIcons.Transport,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RecentTrips(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val rows = state.recentTrips
    if (rows.isEmpty()) {
        ZillitEmptyState(
            title = if (state.dashboardSearch.isBlank()) {
                str(S.desktop_hub_no_trips)
            } else {
                str(S.desktop_hub_no_matching_trips)
            },
            icon = ZillitIcons.Send,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    ZillitLazyColumn(
        // Not weighted: the card's content Column is itself unweighted inside
        // the card, so a weight here resolves against an intrinsic height and
        // the list measures to nothing. Sizing to the constraint the weighted
        // card hands down is what actually draws the rows.
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        items(rows, key = { it.id }) { trip ->
            DashboardTripRow(
                trip = trip,
                driverName = trip.driverName.ifBlank { state.driverName(trip.driverId) },
                vehicleLabel = trip.vehicleLabel.ifBlank { state.vehicleLabel(trip.vehicleId) },
                onOpen = { onEvent(HubEvent.OpenTrip(trip.id)) },
            )
        }
    }
}

@Composable
private fun DashboardTripRow(
    trip: Trip,
    driverName: String,
    vehicleLabel: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = trip.passengers.firstOrNull()?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: trip.title.ifBlank { str(S.desktop_hub_untitled_trip) },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The web shows the id's last six characters so a coordinator
                // can quote a row without reading out a whole ObjectId.
                ZillitText(
                    text = "#${trip.id.takeLast(SHORT_ID).uppercase()}",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
                if (trip.priority == RidePriority.Urgent || trip.priority == RidePriority.High) {
                    ZillitStatusPill(label = trip.priority.label(), tone = trip.priority.tone())
                }
                if (trip.tripType != "one_time") {
                    ZillitStatusPill(label = trip.tripType.replace('_', ' '), tone = StatusTone.Neutral)
                }
            }
            HubRouteLine(trip.pickup, trip.dropoff)
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                val at = trip.scheduledPickupTimeMs ?: trip.createdMs
                listOfNotNull(
                    at.hubDateTime().takeIf { it.isNotBlank() },
                    trip.passengers.size.takeIf { it > 0 }?.let { count ->
                        // "1 people" reads as a bug; the web says "1 person".
                        if (count == 1) {
                            str(S.desktop_tasks_people_count_one)
                        } else {
                            str(S.desktop_tasks_people_count, count.toString())
                        }
                    },
                    driverName.takeIf { it.isNotBlank() },
                    vehicleLabel.takeIf { it.isNotBlank() },
                ).forEach { fact ->
                    ZillitText(
                        text = fact,
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
        }
        ZillitStatusPill(label = trip.status.label(), tone = trip.status.tone(), dot = true)
        ZillitButton(
            text = str(S.view),
            onClick = onOpen,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
    }
}

internal fun DashboardFilter.label(): String = when (this) {
    DashboardFilter.All -> str(S.all)
    DashboardFilter.Active -> str(S.active)
    DashboardFilter.Pending -> str(S.pending)
    DashboardFilter.Completed -> str(S.completed)
    DashboardFilter.Cancelled -> str(S.cancelled)
}

private const val SHORT_ID = 6
