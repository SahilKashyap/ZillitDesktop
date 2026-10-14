// The shell branches on the section and the viewer's role; one composable per piece.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.transporthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.HubInsights
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.ui.kit.HubPaddedPage
import com.zillit.desktop.feature.transporthub.ui.kit.LocalHubThemeToggle
import com.zillit.desktop.feature.transporthub.ui.kit.hub
import com.zillit.desktop.feature.transporthub.ui.pages.ActionVehiclesPage
import com.zillit.desktop.feature.transporthub.ui.pages.AllocationsPage
import com.zillit.desktop.feature.transporthub.ui.pages.DashboardPage
import com.zillit.desktop.feature.transporthub.ui.pages.DriverMapPage
import com.zillit.desktop.feature.transporthub.ui.pages.DriversPage
import com.zillit.desktop.feature.transporthub.ui.pages.FleetPage
import com.zillit.desktop.feature.transporthub.ui.pages.HubDialogs
import com.zillit.desktop.feature.transporthub.ui.pages.LiveTrackingPage
import com.zillit.desktop.feature.transporthub.ui.pages.MaintenancePage
import com.zillit.desktop.feature.transporthub.ui.pages.DriverRidesPage
import com.zillit.desktop.feature.transporthub.ui.pages.DriverTripsPage
import com.zillit.desktop.feature.transporthub.ui.pages.PassengerTripsPage
import com.zillit.desktop.feature.transporthub.ui.pages.SelfRidesPage
import com.zillit.desktop.feature.transporthub.ui.pages.RidesPage
import com.zillit.desktop.feature.transporthub.ui.pages.ShuttlesPage
import com.zillit.desktop.feature.transporthub.ui.pages.TripsPage

/**
 * The Transportation Hub: the web's sidebar on the left, the section on the
 * right, dialogs over both.
 *
 * The rail is the role's own, in the web's order — a coordinator's ten
 * destinations, a driver's four, a passenger's two.
 */
@Composable
fun HubScreen(
    state: HubUiState,
    onEvent: (HubEvent) -> Unit,
    openLink: (String) -> Unit = {},
) {
    if (!state.viewer.canView) {
        ZillitEmptyState(
            title = str(S.desktop_hub_no_access_title),
            message = str(S.desktop_hub_no_access_body),
            icon = ZillitIcons.Lock,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    // The hub's own light/dark, when the viewer flipped it in a banner. The
    // whole hub is re-themed, dialogs and fields included — the web swaps its
    // antd theme the same way, so a portal never shows the other skin.
    ZillitTheme(darkTheme = state.hubDark ?: ZillitTheme.colors.isDark, animateThemeChange = false) {
        CompositionLocalProvider(
            LocalHubThemeToggle provides { dark -> onEvent(HubEvent.SetHubTheme(dark)) },
        ) {
            HubShell(state, onEvent, openLink)
        }
    }
}

@Composable
private fun HubShell(
    state: HubUiState,
    onEvent: (HubEvent) -> Unit,
    openLink: (String) -> Unit,
) {
    var collapsed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            HubRail(
                rows = state.rail.map { section ->
                    HubRailRow(
                        id = section.name,
                        label = section.label(),
                        icon = section.icon(),
                        badge = state.badge(section),
                        active = section == state.section,
                    )
                },
                onSelect = { id -> onEvent(HubEvent.SelectSection(HubSection.valueOf(id))) },
                collapsed = collapsed,
                onToggleCollapse = { collapsed = !collapsed },
                isTempDriver = state.viewer.isTempDriver,
            )
            // The hub's grey, edge to edge. Each page draws its own banner
            // across the top and pads its own body, as the web's do.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(hub.bg),
            ) {
                if (state.loading && state.isSectionEmpty) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { ZillitSpinner() }
                } else {
                    HubSectionBody(state, onEvent, openLink)
                }
            }
        }
        HubDialogs(state, onEvent)
        ZillitErrorToast(message = state.error, onDismiss = { onEvent(HubEvent.DismissError) })
    }
}

@Suppress("CyclomaticComplexMethod") // One branch per section, and the role picks a section's page.
@Composable
private fun HubSectionBody(
    state: HubUiState,
    onEvent: (HubEvent) -> Unit,
    openLink: (String) -> Unit,
) {
    when (state.section) {
        HubSection.Dashboard -> DashboardPage(state, onEvent)
        HubSection.Trips -> HubPaddedPage { TripsPage(state, onEvent) }
        HubSection.RideRequests -> HubPaddedPage { RidesPage(state, onEvent) }
        HubSection.Fleet -> HubPaddedPage { FleetPage(state, onEvent) }
        HubSection.Drivers -> HubPaddedPage { DriversPage(state, onEvent, openLink) }
        HubSection.Allocations -> HubPaddedPage { AllocationsPage(state, onEvent) }
        HubSection.Maintenance -> HubPaddedPage { MaintenancePage(state, onEvent) }
        HubSection.Shuttles -> HubPaddedPage { ShuttlesPage(state, onEvent) }
        HubSection.ActionVehicles -> HubPaddedPage { ActionVehiclesPage(state, onEvent) }
        HubSection.LiveTracking -> HubPaddedPage { LiveTrackingPage(state, onEvent) }
        // The web's three role route sets: a driver's My Trips is an action
        // page and a passenger's is a history; a driver's Assigned Rides is
        // what they drive, while their Ride Requests are the passenger's page.
        HubSection.MyTrips -> if (state.role == HubRole.Driver) {
            DriverTripsPage(state, onEvent)
        } else {
            PassengerTripsPage(state, onEvent)
        }

        HubSection.AssignedRides -> DriverRidesPage(state, onEvent)
        HubSection.MyRequests, HubSection.MyRides -> SelfRidesPage(state, onEvent)

        HubSection.DriverMap -> HubPaddedPage { DriverMapPage(state, onEvent) }
    }
}

/** The rail's own label for a section — the web's `NAV_BY_ROLE` labels. */
@Suppress("CyclomaticComplexMethod") // One branch per section; a map would hide the pairing.
internal fun HubSection.label(): String = when (this) {
    HubSection.Dashboard -> str(S.dashboard)
    HubSection.Trips -> str(S.trips)
    HubSection.RideRequests -> str(S.ride_requests)
    HubSection.Fleet -> str(S.fleet)
    HubSection.ActionVehicles -> str(S.action_vehicles)
    HubSection.Drivers -> str(S.drivers)
    HubSection.Allocations -> str(S.allocations)
    HubSection.Shuttles -> str(S.shuttles)
    HubSection.LiveTracking -> str(S.desktop_hub_live_tracking)
    HubSection.Maintenance -> str(S.maintenance)
    HubSection.MyTrips -> str(S.desktop_hub_my_trips)
    HubSection.DriverMap -> str(S.desktop_hub_my_map)
    HubSection.AssignedRides -> str(S.assigned_rides)
    HubSection.MyRequests, HubSection.MyRides -> str(S.ride_requests)
}

internal fun HubSection.icon(): ImageVector = when (this) {
    HubSection.Dashboard -> ZillitIcons.Home
    HubSection.Trips, HubSection.MyTrips -> ZillitIcons.Send
    HubSection.RideRequests -> ZillitIcons.Chat
    HubSection.AssignedRides, HubSection.MyRequests, HubSection.MyRides -> ZillitIcons.Transport
    HubSection.Fleet -> ZillitIcons.Transport
    HubSection.ActionVehicles -> ZillitToolIcons.Script
    HubSection.Drivers -> ZillitIcons.Users
    HubSection.Allocations -> ZillitIcons.Link
    HubSection.Shuttles -> ZillitToolIcons.Transportation
    HubSection.LiveTracking, HubSection.DriverMap -> ZillitToolIcons.Location
    HubSection.Maintenance -> ZillitIcons.Tools
}

/**
 * True when the open section has nothing to show yet.
 *
 * The spinner is only for a first load: a reload behind a list already on
 * screen must not blank it, or every socket frame would flash the page.
 */
private val HubUiState.isSectionEmpty: Boolean
    get() = when (section) {
        HubSection.Dashboard -> trips.isEmpty() && insights == HubInsights.Empty

        HubSection.Trips, HubSection.MyTrips -> trips.isEmpty()
        HubSection.RideRequests, HubSection.MyRides,
        HubSection.AssignedRides, HubSection.MyRequests,
        -> rides.isEmpty()

        HubSection.Fleet -> vehicles.isEmpty()
        HubSection.Drivers -> drivers.isEmpty()
        HubSection.Allocations -> allocations.isEmpty()
        HubSection.Maintenance -> maintenance.isEmpty()
        HubSection.Shuttles -> shuttleRoutes.isEmpty() && shuttleRuns.isEmpty()
        HubSection.ActionVehicles -> actionVehicles.isEmpty()
        HubSection.LiveTracking, HubSection.DriverMap -> driverLocations.isEmpty()
    }
