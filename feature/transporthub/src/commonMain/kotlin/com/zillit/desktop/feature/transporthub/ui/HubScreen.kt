// The shell branches on the section and the viewer's role; one composable per piece.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.transporthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.SideNavItem
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.SideNavSection
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitSideNav
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.ui.pages.AllocationsPage
import com.zillit.desktop.feature.transporthub.ui.pages.DriversPage
import com.zillit.desktop.feature.transporthub.ui.pages.FleetPage
import com.zillit.desktop.feature.transporthub.ui.pages.HubDialogs
import com.zillit.desktop.feature.transporthub.ui.pages.MyRidesPage
import com.zillit.desktop.feature.transporthub.ui.pages.MyTripsPage
import com.zillit.desktop.feature.transporthub.ui.pages.RidesPage
import com.zillit.desktop.feature.transporthub.ui.pages.TripsPage

/**
 * The Transportation Hub: a section rail on the left, the section on the
 * right, dialogs over both.
 *
 * The rail is the web's sidebar, including the sections phase 1 has not
 * ported — they say so when opened rather than being hidden, so a coordinator
 * who can see Shuttles on the web does not wonder where the data went.
 */
@Composable
fun HubScreen(
    state: HubUiState,
    onEvent: (HubEvent) -> Unit,
    openLink: (String) -> Unit = {},
    /**
     * Opens the older Transportation page.
     *
     * The rail carries a way back to it while the hub is being finished: a
     * driver fills in their own licence details there, and the hub has no
     * page for that — on the web a coordinator does it for them.
     */
    openClassicPage: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    if (!state.viewer.canView) {
        ZillitEmptyState(
            title = str(S.desktop_hub_no_access_title),
            message = str(S.desktop_hub_no_access_body),
            icon = ZillitIcons.Lock,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            ZillitSideNav(
                sections = listOf(
                    SideNavSection(
                        title = null,
                        items = state.rail.map { section ->
                            SideNavItem(
                                id = section.name,
                                label = section.label(),
                                icon = section.icon(),
                                count = state.badge(section),
                            )
                        } + listOfNotNull(
                            openClassicPage?.let {
                                SideNavItem(
                                    id = CLASSIC_PAGE_ID,
                                    label = str(S.desktop_hub_classic_page),
                                    icon = ZillitIcons.ArrowLeft,
                                )
                            },
                        ),
                    ),
                ),
                activeId = state.section.name,
                onSelect = { id ->
                    if (id == CLASSIC_PAGE_ID) {
                        openClassicPage?.invoke()
                    } else {
                        onEvent(HubEvent.SelectSection(HubSection.valueOf(id)))
                    }
                },
                modifier = Modifier.fillMaxHeight().background(colors.surfaceSunken),
                header = {
                    Column(Modifier.padding(bottom = ZillitTheme.spacing.sm)) {
                        ZillitText(
                            text = str(S.desktop_hub_title),
                            style = ZillitTheme.typography.titleLarge,
                        )
                        ZillitText(
                            text = state.roleLabel(),
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
                when {
                    !state.section.isPorted -> NotPortedYet(openClassicPage)
                    state.loading && state.isSectionEmpty -> Box(
                        Modifier.fillMaxSize(),
                        androidx.compose.ui.Alignment.Center,
                    ) { ZillitSpinner() }

                    else -> HubSectionBody(state, onEvent, openLink, openClassicPage)
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
    openClassicPage: (() -> Unit)?,
) {
    when (state.section) {
        HubSection.Trips -> TripsPage(state, onEvent)
        HubSection.RideRequests -> RidesPage(state, onEvent)
        HubSection.Fleet -> FleetPage(state, onEvent)
        HubSection.Drivers -> DriversPage(state, onEvent, openLink)
        HubSection.Allocations -> AllocationsPage(state, onEvent)
        HubSection.MyTrips -> MyTripsPage(state, onEvent)
        HubSection.MyRides -> MyRidesPage(state, onEvent)
        else -> NotPortedYet(openClassicPage)
    }
}

@Composable
private fun NotPortedYet(openClassicPage: (() -> Unit)?) {
    ZillitEmptyState(
        title = str(S.desktop_hub_not_ported_title),
        message = if (openClassicPage == null) {
            str(S.desktop_hub_not_ported_body)
        } else {
            str(S.desktop_hub_not_ported_open_classic)
        },
        icon = ZillitIcons.Info,
        modifier = Modifier.fillMaxWidth(),
        action = openClassicPage?.let {
            {
                ZillitButton(
                    text = str(S.desktop_hub_classic_page),
                    onClick = it,
                    variant = ButtonVariant.Secondary,
                )
            }
        },
    )
}

/** The rail id of the way back to the older page — never a [HubSection]. */
private const val CLASSIC_PAGE_ID = "classic-transportation"

/** The rail's own label for a section. */
internal fun HubSection.label(): String = when (this) {
    HubSection.Trips -> str(S.trips)
    HubSection.RideRequests -> str(S.ride_requests)
    HubSection.Fleet -> str(S.fleet)
    HubSection.Drivers -> str(S.drivers)
    HubSection.Allocations -> str(S.allocations)
    HubSection.MyTrips -> str(S.desktop_hub_my_trips)
    HubSection.MyRides -> str(S.ride_requests)
    HubSection.Dashboard -> str(S.dashboard)
    HubSection.ActionVehicles -> str(S.action_vehicles)
    HubSection.Shuttles -> str(S.shuttles)
    HubSection.LiveTracking -> str(S.desktop_hub_live_tracking)
    HubSection.Maintenance -> str(S.maintenance)
    HubSection.DriverMap -> str(S.desktop_hub_my_map)
}

internal fun HubSection.icon(): ImageVector = when (this) {
    HubSection.Trips, HubSection.MyTrips -> ZillitIcons.Send
    HubSection.RideRequests, HubSection.MyRides -> ZillitIcons.Chat
    HubSection.Fleet -> ZillitIcons.Transport
    HubSection.Drivers -> ZillitIcons.Users
    HubSection.Allocations -> ZillitIcons.Link
    HubSection.Dashboard -> ZillitIcons.Home
    HubSection.ActionVehicles -> ZillitToolIcons.Script
    HubSection.Shuttles -> ZillitToolIcons.Transportation
    HubSection.LiveTracking, HubSection.DriverMap -> ZillitToolIcons.Location
    HubSection.Maintenance -> ZillitIcons.Tools
}

private fun HubUiState.roleLabel(): String = when (role) {
    HubRole.Coordinator -> str(S.ah_step_coordinator)
    HubRole.Driver -> str(S.driver)
    HubRole.Passenger -> str(S.crew)
}

/**
 * True when the open section has nothing to show yet.
 *
 * The spinner is only for a first load: a reload behind a list already on
 * screen must not blank it, or every socket frame would flash the page.
 */
private val HubUiState.isSectionEmpty: Boolean
    get() = when (section) {
        HubSection.Trips, HubSection.MyTrips -> trips.isEmpty()
        HubSection.RideRequests, HubSection.MyRides -> rides.isEmpty()
        HubSection.Fleet -> vehicles.isEmpty()
        HubSection.Drivers -> drivers.isEmpty()
        HubSection.Allocations -> allocations.isEmpty()
        else -> true
    }
