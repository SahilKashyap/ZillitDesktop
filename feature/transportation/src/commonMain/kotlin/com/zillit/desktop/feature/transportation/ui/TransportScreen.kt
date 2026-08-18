// Screens branch on the section and the viewer's role; one composable per piece.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.transportation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.PermanentTrip
import com.zillit.desktop.feature.transportation.domain.TripRequest
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.ui.pages.TransportDialogs

/** The transport tool: a section nav on the left, the section on the right, dialogs over both. */
@Composable
fun TransportScreen(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(NAV_WIDTH)
                    .fillMaxHeight()
                    .background(colors.surfaceSunken)
                    .padding(ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(text = "Transportation", style = ZillitTheme.typography.titleLarge)
                ZillitText(
                    text = if (state.viewer.isCoordinator) "Coordinator" else if (state.isDriver) "Driver" else "Crew",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
                )
                state.sections.forEach { section ->
                    val active = section == state.section
                    ZillitText(
                        text = section.label,
                        style = ZillitTheme.typography.bodyMedium,
                        color = if (active) colors.accentText else colors.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (active) colors.accentSoft else colors.surfaceSunken,
                                ZillitTheme.shapes.medium)
                            .clickable { onEvent(TransportEvent.SelectSection(section)) }
                            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to the Transportation tool.")
                state.error?.let { message ->
                    ZillitNotice(
                        text = message,
                        tone = StatusTone.Rejected,
                        action = {
                            ZillitButton(
                                text = "Dismiss",
                                onClick = { onEvent(TransportEvent.DismissError) },
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                            )
                        },
                    )
                }
                when (state.section) {
                    TransportSection.Requests -> RequestsSection(state, onEvent)
                    TransportSection.Vehicles -> VehiclesSection(state, onEvent)
                    TransportSection.Permanent -> PermanentSection(state, onEvent)
                    TransportSection.Drivers -> DriversSection(state, onEvent)
                    TransportSection.MyAssignments -> MyAssignmentsSection(state, onEvent)
                }
            }
        }
        TransportDialogs(state, onEvent)
    }
}

// Requests -------------------------------------------------------------------

@Composable
private fun RequestsSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    ZillitPageHeader(
        title = "Pickup requests",
        description = if (state.viewer.isCoordinator) "Every request on the production." else "Requests you raised.",
        actions = {
            ZillitButton(text = "Refresh", onClick = { onEvent(TransportEvent.Refresh) },
                variant = ButtonVariant.Tertiary, loading = state.loading)
            ZillitButton(text = "Raise request", onClick = { onEvent(TransportEvent.NewRequest) },
                leadingIcon = ZillitIcons.Add)
        },
    )
    ZillitTabStrip(
        tabs = TripStatus.entries.map { ZillitTab(it.wire, it.label) },
        activeId = state.tripStatus.wire,
        onSelect = { id -> onEvent(TransportEvent.SelectTripStatus(TripStatus.entries.first { it.wire == id })) },
    )
    TripList(state, state.trips, onEvent)
}

@Composable
private fun TripList(state: TransportUiState, trips: List<TripRequest>, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    if (state.loading && trips.isEmpty()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        return
    }
    if (trips.isEmpty()) {
        ZillitText(text = "No requests here.", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(trips, key = { it.id }) { trip ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, ZillitTheme.shapes.medium)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .clickable { onEvent(TransportEvent.OpenTrip(trip)) }
                    .padding(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically) {
                        ZillitText(
                            text = trip.passengers.joinToString { it.name.ifBlank { state.userName(it.userId) } }
                                .ifBlank { "No passengers" },
                            style = ZillitTheme.typography.titleMedium,
                            color = colors.textPrimary,
                        )
                        ZillitStatusPill(label = trip.priority.ifBlank { "—" }, tone = priorityTone(trip.priority))
                    }
                    ZillitText(
                        text = "Raised by ${state.userName(trip.raisedBy)} · first pickup " +
                            TransportClock.dateTime(trip.passengers.minOfOrNull { it.pickupMs } ?: 0L),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    if (trip.driverId != null || trip.vehicleId != null) {
                        ZillitText(
                            text = "Driver ${state.userName(trip.driverId)} · ${state.vehicleLabel(trip.vehicleId)}",
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
                ZillitStatusPill(label = trip.status.label, tone = statusTone(trip.status))
            }
        }
    }
}

internal fun priorityTone(priority: String): StatusTone = when (priority.lowercase()) {
    "urgent" -> StatusTone.Rejected
    "high" -> StatusTone.Escalated
    "medium" -> StatusTone.Pending
    else -> StatusTone.Neutral
}

internal fun statusTone(status: TripStatus): StatusTone = when (status) {
    TripStatus.Pending -> StatusTone.Pending
    TripStatus.Assigned -> StatusTone.Progress
    TripStatus.InProgress -> StatusTone.Progress
    TripStatus.Completed -> StatusTone.Done
    TripStatus.Cancelled -> StatusTone.Rejected
}

// Vehicles -------------------------------------------------------------------

@Composable
private fun VehiclesSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitPageHeader(
        title = "Vehicles",
        description = "The production's fleet — who drives what, and what is free.",
        actions = {
            ZillitButton(text = "Refresh", onClick = { onEvent(TransportEvent.Refresh) },
                variant = ButtonVariant.Tertiary, loading = state.loading)
            ZillitButton(text = "Add vehicle", onClick = { onEvent(TransportEvent.NewVehicle) },
                leadingIcon = ZillitIcons.Add)
        },
    )
    if (state.vehicles.isEmpty()) {
        ZillitText(text = "No vehicles yet.", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(state.vehicles, key = { it.id }) { vehicle -> VehicleCard(state, vehicle, onEvent) }
    }
}

@Composable
private fun VehicleCard(state: TransportUiState, vehicle: Vehicle, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .background(colors.surface, ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically) {
            ZillitText(text = vehicle.name.ifBlank { vehicle.number }, style = ZillitTheme.typography.titleMedium,
                color = colors.textPrimary)
            ZillitStatusPill(
                label = vehicle.allocation.label,
                tone = if (vehicle.editable) StatusTone.Done else StatusTone.Progress,
            )
            if (vehicle.isPrivate) ZillitStatusPill(label = "Personal", tone = StatusTone.Neutral)
        }
        ZillitText(
            text = listOf(vehicle.number, vehicle.type,
                "${vehicle.seats} seats").filter { it.isNotBlank() }.joinToString(" · "),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        ZillitText(
            text = "Driver: ${state.userName(vehicle.driverId)}",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        if (vehicle.ownerName.isNotBlank()) {
            ZillitText(text = "Owner: ${vehicle.ownerName} ${vehicle.ownerContact}".trim(),
                style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            if (vehicle.editable && !vehicle.isPrivate) {
                ZillitButton(text = "Assign driver", onClick = { onEvent(TransportEvent.AssignDriver(vehicle)) },
                    variant = ButtonVariant.Secondary, size = ButtonSize.Small)
            }
            if (vehicle.editable) {
                ZillitButton(text = "Edit", onClick = { onEvent(TransportEvent.EditVehicle(vehicle)) },
                    variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
            }
            if (vehicle.deletable) {
                ZillitButton(text = "Delete", onClick = { onEvent(TransportEvent.DeleteVehicles(listOf(vehicle.id))) },
                    variant = ButtonVariant.Danger, size = ButtonSize.Small)
            }
        }
    }
}

// Permanent ------------------------------------------------------------------

@Composable
private fun PermanentSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitPageHeader(
        title = "Permanent allocations",
        description = if (state.viewer
            .isCoordinator) "Vehicles and drivers assigned for a run of days." else "Allocations you ride in or drive.",
        actions = {
            ZillitButton(text = "Refresh", onClick = { onEvent(TransportEvent.Refresh) },
                variant = ButtonVariant.Tertiary, loading = state.loading)
            if (state.viewer.isCoordinator) {
                ZillitButton(text = "New allocation", onClick = { onEvent(TransportEvent.NewPermanent) },
                    leadingIcon = ZillitIcons.Add)
            }
        },
    )
    if (state.viewer.isCoordinator) {
        ZillitTabStrip(
            tabs = listOf(
                ZillitTab(PermanentStatus.Permanent.wire, "Assigned"),
                ZillitTab(PermanentStatus.Draft.wire, "Drafts"),
                ZillitTab(PermanentStatus.Completed.wire, "Completed"),
            ),
            activeId = state.permanentTab.wire,
            onSelect = { id -> onEvent(TransportEvent.SelectPermanentTab(PermanentStatus.fromWire(id))) },
        )
    }
    if (state.loading && state.permanent.isEmpty()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        return
    }
    if (state.permanent.isEmpty()) {
        ZillitText(text = "No allocations here.", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(state.permanent, key = { it.id }) { trip -> PermanentRow(state, trip, onEvent) }
    }
}

@Composable
private fun PermanentRow(state: TransportUiState, trip: PermanentTrip, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val coordinator = state.viewer.isCoordinator
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = trip.passengers.joinToString { it.name.ifBlank { state.userName(it.userId) } }
                        .ifBlank { "No passengers" },
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                ZillitStatusPill(
                    label = trip.status.label,
                    tone = when (trip.status) {
                        PermanentStatus.Draft -> StatusTone.Pending
                        PermanentStatus.Permanent -> StatusTone.Progress
                        PermanentStatus.Completed -> StatusTone.Done
                    },
                )
                if (trip.fullDay) ZillitStatusPill(label = "Full day", tone = StatusTone.Neutral)
            }
            val end = if (trip.endMs > 0) TransportClock.date(trip.endMs) else "open"
            ZillitText(
                text = "${TransportClock.date(trip.startMs)} → $end · " +
                    "Driver ${state.userName(trip.driverId)} · ${state.vehicleLabel(trip.vehicleId)}",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        val mine = trip.passengers.any { it.userId == state.viewer.userId }
        if (mine && trip.status != PermanentStatus.Completed) {
            ZillitCheckbox(checked = trip.selfManage, onCheckedChange = { onEvent(TransportEvent.SelfManage(trip,
                it)) }, label = "Self-manage")
        }
        if ((coordinator && trip.status == PermanentStatus.Permanent) || trip.status == PermanentStatus.Draft) {
            ZillitButton(text = "Edit", onClick = { onEvent(TransportEvent.EditPermanent(trip)) },
                variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
        }
        if (coordinator && trip.status == PermanentStatus.Permanent) {
            ZillitButton(text = "Unassign", onClick = { onEvent(TransportEvent.UnassignPermanent(trip)) },
                variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        }
        if (trip.status == PermanentStatus.Draft) {
            ZillitButton(text = "Delete", onClick = { onEvent(TransportEvent.DeletePermanent(trip)) },
                variant = ButtonVariant.Danger, size = ButtonSize.Small)
        }
    }
}

// Drivers --------------------------------------------------------------------

@Composable
private fun DriversSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitPageHeader(
        title = "Drivers",
        description = "Everyone the production can put behind a wheel — by designation, or made a temporary driver.",
        actions = {
            ZillitButton(text = "Refresh", onClick = { onEvent(TransportEvent.Refresh) },
                variant = ButtonVariant.Tertiary, loading = state.loading)
        },
    )
    val drivers = state.drivers
    val others = state.crew.filter { it.isAccepted && !it.isDriver(state.driverDesignations) && it.userId != state
        .viewer.userId }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        item {
            ZillitText(text = "DRIVERS (${drivers.size})", style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted)
        }
        if (drivers.isEmpty()) {
            item { ZillitText(text = "No drivers yet.", style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted) }
        }
        items(drivers, key = { "d:" + it.userId }) { user ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, ZillitTheme.shapes.medium)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .padding(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically) {
                        ZillitText(text = user.fullName, style = ZillitTheme.typography.titleMedium,
                            color = colors.textPrimary)
                        if (user.isTempDriver) ZillitStatusPill(label = "Temporary", tone = StatusTone.Neutral)
                        ZillitStatusPill(
                            label = user.availability,
                            tone = if (user.isTripAssigned || user.permanentTrip) StatusTone.Rejected else StatusTone
                                .Done,
                        )
                    }
                    ZillitText(
                        text = "${user.designation.ifBlank { "—" }} · vehicle ${state.vehicleLabel(user.vehicleId)}",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                if (user.permanentTrip && !user.fullDayTrip) {
                    ZillitButton(
                        text = if (user.isTripAssigned) "Mark available" else "Mark unavailable",
                        onClick = { onEvent(TransportEvent.SetAvailability(user.userId,
                            available = user.isTripAssigned)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
                ZillitButton(
                    text = "Licence reminder",
                    onClick = { onEvent(TransportEvent.DocumentReminder(user.userId, "license")) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                if (user.isTempDriver) {
                    ZillitButton(
                        text = "Remove temp",
                        onClick = { onEvent(TransportEvent.ToggleTempDriver(user.userId, on = false)) },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
        item {
            ZillitText(
                text = "MAKE A TEMPORARY DRIVER",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(top = ZillitTheme.spacing.md),
            )
        }
        items(others, key = { "o:" + it.userId }) { user ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, ZillitTheme.shapes.medium)
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    ZillitText(text = user.fullName, style = ZillitTheme.typography.bodyMedium,
                        color = colors.textPrimary)
                    ZillitText(
                        text = listOf(user.designation, user.department).filter { it.isNotBlank() }.joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                ZillitButton(
                    text = "Make temp driver",
                    onClick = { onEvent(TransportEvent.ToggleTempDriver(user.userId, on = true)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

// My assignments -------------------------------------------------------------

@Composable
private fun MyAssignmentsSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    ZillitPageHeader(
        title = "My assignments",
        description = "Trips you are driving — start them, complete them.",
        actions = {
            ZillitButton(text = "Refresh", onClick = { onEvent(TransportEvent.Refresh) },
                variant = ButtonVariant.Tertiary, loading = state.loading)
        },
    )
    ZillitTabStrip(
        tabs = listOf(ZillitTab(TripStatus.Assigned.wire, "To start"), ZillitTab(TripStatus.Completed.wire,
            "Completed")),
        activeId = state.myTab.wire,
        onSelect = { id -> onEvent(TransportEvent.SelectMyTab(TripStatus.fromWire(id))) },
    )
    TripList(state, state.myTrips, onEvent)
}

private val NAV_WIDTH = 220.dp
private val CARD_MIN_WIDTH = 300.dp
