// The shell branches on the section and the viewer's role; one composable per piece.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.transporthub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.HubInsights
import com.zillit.desktop.feature.transporthub.ui.pages.ActionVehiclesPage
import com.zillit.desktop.feature.transporthub.ui.pages.AllocationsPage
import com.zillit.desktop.feature.transporthub.ui.pages.DashboardPage
import com.zillit.desktop.feature.transporthub.ui.pages.DriverMapPage
import com.zillit.desktop.feature.transporthub.ui.pages.DriversPage
import com.zillit.desktop.feature.transporthub.ui.pages.FleetPage
import com.zillit.desktop.feature.transporthub.ui.pages.HubDialogs
import com.zillit.desktop.feature.transporthub.ui.pages.LiveTrackingPage
import com.zillit.desktop.feature.transporthub.ui.pages.MaintenancePage
import com.zillit.desktop.feature.transporthub.ui.pages.MyRidesPage
import com.zillit.desktop.feature.transporthub.ui.pages.MyTripsPage
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
    /** The way back out of the tool, as the web's sidebar carries. */
    onBackToFilmTools: (() -> Unit)? = null,
    /**
     * Opens the older Transportation page.
     *
     * The rail carries a way back to it while the hub is being finished: a
     * driver fills in their own licence details there, and the hub has no
     * page for that — on the web a coordinator does it for them.
     */
    openClassicPage: (() -> Unit)? = null,
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
                } + listOfNotNull(
                    openClassicPage?.let {
                        HubRailRow(
                            id = CLASSIC_PAGE_ID,
                            label = str(S.desktop_hub_classic_page),
                            icon = ZillitIcons.Expand,
                        )
                    },
                ),
                onSelect = { id ->
                    if (id == CLASSIC_PAGE_ID) {
                        openClassicPage?.invoke()
                    } else {
                        onEvent(HubEvent.SelectSection(HubSection.valueOf(id)))
                    }
                },
                collapsed = collapsed,
                onToggleCollapse = { collapsed = !collapsed },
                isTempDriver = state.viewer.isTempDriver,
                profileName = state.viewer.fullName,
                profileDesignation = state.viewer.designation,
                profileUserId = state.viewer.userId,
                profileIsAdmin = state.viewer.isAdmin,
                onBack = onBackToFilmTools,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
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

@Composable
private fun HubSectionBody(
    state: HubUiState,
    onEvent: (HubEvent) -> Unit,
    openLink: (String) -> Unit,
) {
    when (state.section) {
        HubSection.Dashboard -> DashboardPage(state, onEvent)
        HubSection.Trips -> TripsPage(state, onEvent)
        HubSection.RideRequests -> RidesPage(state, onEvent)
        HubSection.Fleet -> FleetPage(state, onEvent)
        HubSection.Drivers -> DriversPage(state, onEvent, openLink)
        HubSection.Allocations -> AllocationsPage(state, onEvent)
        HubSection.Maintenance -> MaintenancePage(state, onEvent)
        HubSection.Shuttles -> ShuttlesPage(state, onEvent)
        HubSection.ActionVehicles -> ActionVehiclesPage(state, onEvent)
        HubSection.LiveTracking -> LiveTrackingPage(state, onEvent)
        HubSection.MyTrips -> MyTripsPage(state, onEvent)
        // A driver's two ride lists are one page: the rows are the same and
        // only the scope behind them differs, which the load already applied.
        HubSection.AssignedRides, HubSection.MyRequests, HubSection.MyRides ->
            MyRidesPage(state, onEvent)

        HubSection.DriverMap -> DriverMapPage(state, onEvent)
    }
}

/** The rail id of the way back to the older page — never a [HubSection]. */
private const val CLASSIC_PAGE_ID = "classic-transportation"

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
