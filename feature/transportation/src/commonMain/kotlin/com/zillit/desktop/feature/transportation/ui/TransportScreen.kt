// Screens branch on the section and the viewer's role; one composable per piece.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.transportation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.SideNavItem
import com.zillit.desktop.core.designsystem.component.SideNavSection
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitSideNav
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.PermanentTrip
import com.zillit.desktop.feature.transportation.domain.TripRequest
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.ui.pages.DriverDetailsBody
import com.zillit.desktop.feature.transportation.ui.pages.DriverRow
import com.zillit.desktop.feature.transportation.ui.pages.Face
import com.zillit.desktop.feature.transportation.ui.pages.TransportDialogs
import com.zillit.desktop.feature.transportation.ui.pages.VehiclePicture
import com.zillit.desktop.feature.transportation.ui.pages.allocationTone
import com.zillit.desktop.feature.transportation.ui.pages.priorityTone
import com.zillit.desktop.feature.transportation.ui.pages.statusTone

/** The transport tool: a section nav on the left, the section on the right, dialogs over both. */
@Composable
fun TransportScreen(state: TransportUiState, onEvent: (TransportEvent) -> Unit, openLink: (String) -> Unit = {}) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            ZillitSideNav(
                sections = listOf(
                    SideNavSection(
                        title = null,
                        items = state.sections.map { section ->
                            SideNavItem(
                                id = section.name,
                                label = section.label,
                                icon = section.icon(),
                                count = state.sectionBadge(section),
                            )
                        },
                    ),
                ),
                activeId = state.section.name,
                onSelect = { id -> onEvent(TransportEvent.SelectSection(TransportSection.valueOf(id))) },
                modifier = Modifier.fillMaxHeight().background(colors.surfaceSunken),
                header = {
                    Column(Modifier.padding(bottom = ZillitTheme.spacing.sm)) {
                        ZillitText(text = "Transportation", style = ZillitTheme.typography.titleLarge)
                        ZillitText(
                            text = when {
                                state.viewer.isCoordinator -> "Coordinator"
                                state.isDriver -> "Driver"
                                else -> "Crew"
                            },
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                },
            )
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
                            ZillitButton(text = "Dismiss", onClick = { onEvent(TransportEvent.DismissError) },
                                variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                        },
                    )
                }
                when (state.section) {
                    TransportSection.Requests -> RequestsSection(state, onEvent)
                    TransportSection.Vehicles -> VehiclesSection(state, onEvent)
                    TransportSection.Permanent -> PermanentSection(state, onEvent)
                    TransportSection.Drivers -> DriversSection(state, onEvent)
                    TransportSection.MyAssignments -> MyAssignmentsSection(state, onEvent)
                    TransportSection.MyDetails -> MyDetailsSection(state, onEvent)
                }
            }
        }
        TransportDialogs(state, onEvent, openLink)
    }
}

private fun TransportSection.icon() = when (this) {
    TransportSection.Requests -> ZillitIcons.Pin
    TransportSection.Vehicles -> ZillitIcons.Transport
    TransportSection.Drivers -> ZillitIcons.Users
    TransportSection.Permanent -> ZillitIcons.Calendar
    TransportSection.MyAssignments -> ZillitIcons.Tick
    TransportSection.MyDetails -> ZillitIcons.User
}

/** A card the pointer lifts — the web's `hover:shadow-md` tiles. */
@Composable
private fun HoverCard(modifier: Modifier = Modifier, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lifted = hovered && onClick != null
    Box(
        modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .background(if (lifted) colors.surfaceHover else colors.surface, ZillitTheme.shapes.large)
            .border(1.dp, if (lifted) colors.borderStrong else colors.border, ZillitTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(ZillitTheme.spacing.md),
    ) { content() }
}

// Requests -------------------------------------------------------------------

@Composable
private fun RequestsSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    ZillitPageHeader(
        title = "Pickup requests",
        description = if (state.viewer.isCoordinator) "Every request on the production — approve, assign, track." else
            "Requests you raised, and the ones you ride in.",
        actions = {
            ZillitButton(text = "Refresh", onClick = { onEvent(TransportEvent.Refresh) },
                variant = ButtonVariant.Tertiary, loading = state.loading)
            ZillitButton(text = "Raise pickup request", onClick = { onEvent(TransportEvent.NewRequest) },
                leadingIcon = ZillitIcons.Add)
        },
    )
    ZillitTabStrip(
        tabs = TripStatus.entries.map {
            ZillitTab(it.wire, it.label, count = state.badge(TransportUiState.tripStatusBadge(it)))
        },
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
        ZillitEmptyState(title = "No requests here", message = "Requests in this state will appear as they arrive.")
        return
    }
    ZillitLazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(trips, key = { it.id }) { trip ->
            val raiser = state.user(trip.raisedBy)
            HoverCard(onClick = { onEvent(TransportEvent.OpenTrip(trip)) }) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    // The web's coloured left edge: the priority at a glance.
                    Box(Modifier.width(EDGE).height(EDGE_HEIGHT).background(priorityColor(trip.priority),
                        ZillitTheme.shapes.pill))
                    Face(raiser, state.userName(trip.raisedBy))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically) {
                            ZillitText(
                                text = trip.passengers.joinToString { it.name.ifBlank { state.userName(it.userId) } }
                                    .ifBlank { "No passengers" },
                                style = ZillitTheme.typography.titleMedium,
                                color = colors.textPrimary,
                            )
                            ZillitStatusPill(label = trip.priority.uppercase().ifBlank { "—" },
                                tone = priorityTone(trip.priority))
                        }
                        ZillitText(
                            text = "Raised by ${state.userName(trip.raisedBy)} · pickup " +
                                TransportClock.dateTime(trip.firstPickupMs),
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        if (trip.driverId != null || trip.vehicleId != null) {
                            ZillitText(
                                text = "Driver ${state.userName(trip.driverId)} · " +
                                    state.vehicleLabel(trip.vehicleId),
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
}

@Composable
private fun priorityColor(priority: String) = when (priority.lowercase()) {
    "urgent" -> ZillitTheme.colors.danger
    "high" -> ZillitTheme.colors.accent
    "medium" -> ZillitTheme.colors.info
    else -> ZillitTheme.colors.success
}

// Vehicles -------------------------------------------------------------------

@Composable
private fun VehiclesSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val selecting = state.vehicleSelection != null
    ZillitPageHeader(
        title = "Vehicle list",
        description = "The production's fleet — who drives what, and what is free.",
        actions = {
            ZillitButton(text = "Refresh", onClick = { onEvent(TransportEvent.Refresh) },
                variant = ButtonVariant.Tertiary, loading = state.loading)
            ZillitButton(text = "Add vehicle", onClick = { onEvent(TransportEvent.NewVehicle) },
                leadingIcon = ZillitIcons.Add)
        },
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(value = state.vehicleQuery, onValueChange = { onEvent(TransportEvent.VehicleQuery(it)) },
            placeholder = "Search by name or number", modifier = Modifier.width(SEARCH), enabled = !selecting)
        if (state.visibleVehicles.isNotEmpty() && state.vehicleQuery.isBlank()) {
            ZillitButton(text = if (selecting) "Cancel" else "Select",
                onClick = { onEvent(TransportEvent.ToggleVehicleSelection) }, variant = ButtonVariant.Secondary)
        }
        val chosen = state.vehicleSelection.orEmpty()
        if (selecting && chosen.isNotEmpty()) {
            ZillitButton(text = "Delete (${chosen.size})", onClick = { onEvent(TransportEvent.DeleteSelectedVehicles) },
                variant = ButtonVariant.Danger, leadingIcon = ZillitIcons.Trash, loading = state.busy)
        }
    }
    val vehicles = state.visibleVehicles
    if (vehicles.isEmpty()) {
        ZillitEmptyState(title = if (state.vehicleQuery.isBlank()) "No vehicles yet" else "No vehicles match",
            message = if (state.vehicleQuery.isBlank()) "Add the production's first vehicle." else null)
        return
    }
    ZillitLazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(vehicles, key = { it.id }) { vehicle -> VehicleCard(state, vehicle, onEvent) }
    }
}

@Composable
private fun VehicleCard(state: TransportUiState, vehicle: Vehicle, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val selecting = state.vehicleSelection != null
    val driver = state.user(vehicle.driverId)
    HoverCard(onClick = if (selecting) null else { { onEvent(TransportEvent.OpenVehicle(vehicle)) } }) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically) {
                VehiclePicture(state, vehicle)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically) {
                        ZillitText(text = vehicle.name.ifBlank { vehicle.number },
                            style = ZillitTheme.typography.titleMedium, color = colors.textPrimary,
                            modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                        if (vehicle.isPrivate) ZillitStatusPill(label = "Private", tone = StatusTone.Neutral)
                        if (selecting && vehicle.deletable) {
                            ZillitCheckbox(checked = vehicle.id in state.vehicleSelection.orEmpty(),
                                onCheckedChange = { onEvent(TransportEvent.ToggleVehicleSelected(vehicle.id)) })
                        }
                    }
                    ZillitText(text = vehicle.number, style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted)
                    ZillitStatusPill(label = vehicle.allocation.label, tone = allocationTone(vehicle.allocation))
                    if (vehicle.seats > 0) {
                        ZillitText(text = "Seating capacity: ${vehicle.seats}",
                            style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                    ZillitText(
                        text = "Driver — ${driver?.fullName ?: "Not assigned"}",
                        style = ZillitTheme.typography.bodySmall,
                        color = if (driver == null) colors.danger else colors.textSecondary,
                    )
                }
            }
            if (!selecting && vehicle.editable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs,
                    Alignment.End)) {
                    if (vehicle.deletable) {
                        ZillitButton(text = "Delete", onClick = { onEvent(TransportEvent.DeleteVehicle(vehicle)) },
                            variant = ButtonVariant.Danger, size = ButtonSize.Small, leadingIcon = ZillitIcons.Trash)
                    }
                    ZillitButton(text = "Edit", onClick = { onEvent(TransportEvent.EditVehicle(vehicle)) },
                        variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Edit)
                }
            }
        }
    }
}

// Permanent ------------------------------------------------------------------

@Composable
private fun PermanentSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val coordinator = state.viewer.isCoordinator
    ZillitPageHeader(
        title = "Permanent allocations",
        description = if (coordinator) "Vehicles and drivers assigned to people for a run of days." else
            "Allocations you ride in" + if (state.isDriver) " or drive." else ".",
        actions = {
            ZillitButton(text = "Refresh", onClick = { onEvent(TransportEvent.Refresh) },
                variant = ButtonVariant.Tertiary, loading = state.loading)
            if (coordinator) {
                ZillitButton(text = "Assign vehicle", onClick = { onEvent(TransportEvent.NewPermanent) },
                    leadingIcon = ZillitIcons.Add)
            }
        },
    )
    if (coordinator) {
        ZillitTabStrip(
            tabs = listOf(
                ZillitTab(PermanentStatus.Permanent.wire, "Assigned vehicles", count = state.badge(TransportUiState
                    .PERMANENT_BADGE)),
                ZillitTab(PermanentStatus.Draft.wire, "Drafts"),
                ZillitTab(PermanentStatus.Completed.wire, "Completed"),
            ),
            activeId = state.permanentTab.wire,
            onSelect = { id -> onEvent(TransportEvent.SelectPermanentTab(PermanentStatus.fromWire(id))) },
        )
    } else if (state.isDriver) {
        ZillitTabStrip(
            tabs = listOf(
                ZillitTab("passenger", "Assigned as passenger",
                    count = state.badge(TransportUiState.PERMANENT_AS_PASSENGER_BADGE)),
                ZillitTab("driver", "Assigned as driver",
                    count = state.badge(TransportUiState.PERMANENT_AS_DRIVER_BADGE)),
            ),
            activeId = if (state.permanentAsDriver) "driver" else "passenger",
            onSelect = { id -> onEvent(TransportEvent.SelectPermanentRole(id == "driver")) },
        )
    }
    if (state.loading && state.permanent.isEmpty()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        return
    }
    if (state.permanent.isEmpty()) {
        ZillitEmptyState(title = "No allocations here")
        return
    }
    ZillitLazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(state.permanent, key = { it.id }) { trip -> PermanentRow(state, trip, onEvent) }
    }
}

/** The web's `PermanentRequestItem`, its kebab menu spelled out as buttons. */
@Composable
private fun PermanentRow(state: TransportUiState, trip: PermanentTrip, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val coordinator = state.viewer.isCoordinator
    val vehicle = state.vehicle(trip.vehicleId)
    HoverCard(onClick = { onEvent(TransportEvent.EditPermanent(trip, viewOnly = true)) }) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (vehicle != null) VehiclePicture(state, vehicle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically) {
                    ZillitText(text = vehicle?.number ?: "No vehicle", style = ZillitTheme.typography.titleMedium,
                        color = colors.textPrimary)
                    ZillitStatusPill(
                        label = trip.status.label,
                        tone = when (trip.status) {
                            PermanentStatus.Draft -> StatusTone.Pending
                            PermanentStatus.Permanent -> StatusTone.Progress
                            PermanentStatus.Completed -> StatusTone.Done
                        },
                    )
                }
                ZillitText(text = "Trip type: ${if (trip.fullDay) "Full day" else "Pickup and drop-off"}",
                    style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
                val first = trip.passengers.firstOrNull()
                ZillitText(
                    text = "Passengers: " + when {
                        first == null -> "N/A"
                        trip.passengers.size == 1 -> first.name.ifBlank { state.userName(first.userId) }
                        else -> first.name.ifBlank { state.userName(first.userId) } +
                            " +${trip.passengers.size - 1} more"
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                ZillitText(text = "Driver: ${state.userName(trip.driverId)}", style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary)
                val end = if (trip.endMs > 0) TransportClock.date(trip.endMs) else "open"
                ZillitText(text = "${TransportClock.date(trip.startMs)} → $end",
                    style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                if ((coordinator && trip.status == PermanentStatus.Permanent) || trip.status == PermanentStatus.Draft) {
                    ZillitButton(text = "Edit", onClick = { onEvent(TransportEvent.EditPermanent(trip)) },
                        variant = ButtonVariant.Secondary, size = ButtonSize.Small)
                }
                if (coordinator && trip.status == PermanentStatus.Permanent) {
                    ZillitButton(text = "Unassign", onClick = { onEvent(TransportEvent.UnassignPermanent(trip)) },
                        variant = ButtonVariant.Danger, size = ButtonSize.Small)
                }
                if (trip.status == PermanentStatus.Draft) {
                    ZillitButton(text = "Delete", onClick = { onEvent(TransportEvent.DeletePermanent(trip)) },
                        variant = ButtonVariant.Danger, size = ButtonSize.Small)
                }
            }
        }
    }
}

// Drivers --------------------------------------------------------------------

/**
 * Drivers waiting for a licence change to be approved.
 *
 * The desktop listened for the socket event and reloaded the crew, but had no
 * way to answer: the decision could only be made from the web. It sits above
 * the driver list because it is the one thing here that is blocking someone.
 */
@Composable
private fun LicenceRequests(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        title = "Licence changes waiting",
        meta = "${state.licenceRequests.size} to answer",
    ) {
        state.licenceRequests.forEach { request ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(text = state.userName(request.userId), style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                ZillitButton(text = "Approve",
                    onClick = { onEvent(TransportEvent.DecideLicence(request.id, approved = true)) },
                    variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = !state.busy)
                ZillitButton(text = "Reject",
                    onClick = { onEvent(TransportEvent.DecideLicence(request.id, approved = false)) },
                    variant = ButtonVariant.Danger, size = ButtonSize.Small, enabled = !state.busy)
            }
        }
    }
}

@Composable
private fun DriversSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    ZillitPageHeader(
        title = "Drivers",
        description = "Everyone the production can put behind a wheel — by designation, or made a temporary driver.",
        actions = {
            ZillitButton(text = "Refresh", onClick = { onEvent(TransportEvent.Refresh) },
                variant = ButtonVariant.Tertiary, loading = state.loading)
            ZillitButton(text = "Temporary drivers", onClick = { onEvent(TransportEvent.OpenTempDrivers) },
                variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.UserPlus)
        },
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSegmented(
            options = DriverFilter.entries.map { ZillitTab(it.name, it.label) },
            activeId = state.driverFilter.name,
            onSelect = { onEvent(TransportEvent.SelectDriverFilter(DriverFilter.valueOf(it))) },
        )
        ZillitSearchField(value = state.driverQuery, onValueChange = { onEvent(TransportEvent.DriverQuery(it)) },
            placeholder = "Search driver by name", modifier = Modifier.width(SEARCH))
    }
    if (state.driverFilter == DriverFilter.Allocated) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            AllocationFilter.entries.forEach { filter ->
                ZillitChoiceChip(label = filter.label, selected = state.allocationFilter == filter,
                    onClick = { onEvent(TransportEvent.SelectAllocationFilter(filter)) })
            }
        }
    }
    val drivers = state.filteredDrivers()
    ZillitLazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (state.licenceRequests.isNotEmpty()) {
            item { LicenceRequests(state, onEvent) }
        }
        if (drivers.isEmpty()) {
            item {
                ZillitEmptyState(
                    title = "No drivers here",
                    message = if (state.driverFilter == DriverFilter.All) {
                        "Drivers come from the transport department's designations, or are made temporary drivers."
                    } else {
                        null
                    },
                )
            }
        }
        items(drivers, key = { it.userId }) { user ->
            HoverCard(onClick = { onEvent(TransportEvent.OpenDriver(user)) }) {
                DriverRow(state, user, onEvent, trailing = {
                    ZillitText(text = "›", style = ZillitTheme.typography.titleLarge,
                        color = ZillitTheme.colors.textMuted)
                })
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
        tabs = listOf(
            ZillitTab(TripStatus.Assigned.wire, "Start trip",
                count = state.badge(TransportUiState.DRIVER_ASSIGNMENT_BADGE)),
            ZillitTab(TripStatus.Completed.wire, "Completed trips"),
        ),
        activeId = state.myTab.wire,
        onSelect = { id -> onEvent(TransportEvent.SelectMyTab(TripStatus.fromWire(id))) },
    )
    TripList(state, state.myTrips, onEvent)
}

// Fill in details ------------------------------------------------------------

/** The driver's own record — the web's Fill in details tile, as a page rather than a modal. */
@Composable
private fun MyDetailsSection(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val editor = state.driverDetails?.takeIf { it.self }
    ZillitPageHeader(
        title = "Fill in details",
        description = "Your phone, address, licence and documents — what the transport office needs before it " +
            "assigns you trips.",
        actions = {
            if (state.badge(TransportUiState.DRIVER_REMINDER_BADGE) > 0) {
                ZillitBadge(count = state.badge(TransportUiState.DRIVER_REMINDER_BADGE))
            }
            ZillitButton(text = "Update", onClick = { onEvent(TransportEvent.SaveDriverDetails) },
                loading = editor?.saving == true, enabled = editor != null && !editor.uploading)
        },
    )
    if (editor == null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        return
    }
    ZillitScrollColumn(modifier = Modifier.widthIn(max = DETAILS_WIDTH)) {
        DriverDetailsBody(state, editor, onEvent)
    }
}

private val CARD_MIN_WIDTH = 320.dp
private val SEARCH = 300.dp
private val EDGE = 4.dp
private val EDGE_HEIGHT = 44.dp
private val DETAILS_WIDTH = 760.dp
