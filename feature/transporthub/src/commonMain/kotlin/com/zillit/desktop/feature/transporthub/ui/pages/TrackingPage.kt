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
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDateTime
import com.zillit.desktop.feature.transporthub.domain.DriverLocation
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.kit.HubGlyphs
import com.zillit.desktop.feature.transporthub.ui.kit.HubPageHeader
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.TrackingTab

/**
 * Coordinator → Live Tracking.
 *
 * The web draws this on a Google map with a pin per driver and a second tab
 * for the cars in flight. The desktop has no map widget, so the same two
 * lists are drawn as rows and a position is opened in the browser's map —
 * the seam the older Transportation tool already uses for "track driver".
 * What is on screen is the same information; only the picture is missing.
 */
@Composable
internal fun LiveTrackingPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        HubPageHeader(
            icon = HubGlyphs.MapPin,
            title = str(S.desktop_hub_live_tracking),
            description = state.trackingUpdatedMs.takeIf { it > 0 }
                ?.let { str(S.docusign_auto_refreshed_at, it.hubDateTime()) },
            actions = {
                ZillitButton(
                    text = str(S.refresh_text),
                    onClick = { onEvent(HubEvent.RefreshTracking) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Reload,
                )
            },
        )
        ZillitTabStrip(
            tabs = listOf(
                ZillitTab(TrackingTab.Drivers.name, str(S.drivers), state.driverLocations.size),
                ZillitTab(TrackingTab.Rides.name, str(S.ride_requests), state.trackedRides.size),
            ),
            activeId = state.trackingTab.name,
            onSelect = { id -> onEvent(HubEvent.TrackingTabChanged(TrackingTab.valueOf(id))) },
        )
        when (state.trackingTab) {
            TrackingTab.Drivers -> DriverPins(state, onEvent)
            TrackingTab.Rides -> TrackedRides(state, onEvent)
        }
    }
}

/**
 * Driver → My Map.
 *
 * The web's version pings the browser's geolocation every few seconds and
 * draws the driver on a map. A desktop has no position to report, so this
 * shows what the server last heard — from their phone — and says where it
 * came from, rather than pretending to report one.
 */
@Composable
internal fun DriverMapPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val mine = state.driverLocations.firstOrNull { it.driverId == state.myDriverId }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        HubPageHeader(
            icon = HubGlyphs.MapTrifold,
            title = str(S.desktop_hub_my_map),
            description = str(S.desktop_hub_position_from_phone),
            actions = {
                ZillitButton(
                    text = str(S.refresh_text),
                    onClick = { onEvent(HubEvent.RefreshTracking) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Reload,
                )
            },
        )
        if (mine == null) {
            ZillitEmptyState(
                title = str(S.desktop_hub_no_position_yet),
                icon = ZillitIcons.Globe,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        DriverPinCard(pin = mine, onEvent = onEvent)
    }
}

@Composable
private fun DriverPins(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    if (state.driverLocations.isEmpty()) {
        ZillitEmptyState(
            title = str(S.desktop_hub_nobody_reporting),
            icon = ZillitIcons.Globe,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    ZillitLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(state.driverLocations, key = { it.driverId }) { pin ->
            DriverPinCard(
                pin = pin.copy(
                    driverName = pin.driverName.ifBlank { state.driverName(pin.driverId) },
                ),
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun DriverPinCard(pin: DriverLocation, onEvent: (HubEvent) -> Unit) {
    ZillitSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = pin.driverName.ifBlank { "—" },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatusPill(
                    label = if (pin.isOnDuty) str(S.desktop_hub_on_duty) else str(S.desktop_hub_off_duty),
                    tone = if (pin.isOnDuty) StatusTone.Done else StatusTone.Neutral,
                    dot = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(
                    label = str(S.vehicle),
                    value = pin.vehicleLabel,
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_hub_last_reported),
                    value = pin.atMs.hubDateTime(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_dm_position),
                    value = if (pin.hasFix) "${pin.location.lat}, ${pin.location.long}" else "",
                    modifier = Modifier.weight(1f),
                )
            }
            if (pin.location.address.isNotBlank()) {
                HubFact(label = str(S.location), value = pin.location.address)
            }
            ZillitButton(
                text = str(S.desktop_open_in_maps),
                onClick = { onEvent(HubEvent.TrackDriver(pin.driverId)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = pin.hasFix,
            )
        }
    }
}

@Composable
private fun TrackedRides(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val rows = state.trackedRides
    if (rows.isEmpty()) {
        ZillitEmptyState(
            title = str(S.desktop_hub_no_active_cars),
            icon = ZillitIcons.Transport,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    ZillitLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(rows, key = { it.id }) { ride ->
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
                        ZillitStatusPill(
                            label = ride.status.label(),
                            tone = ride.status.tone(),
                            dot = true,
                        )
                    }
                    HubRouteLine(ride.pickup, ride.dropoff)
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                        HubFact(
                            label = str(S.driver),
                            value = ride.assignedDriverName.ifBlank {
                                state.driverName(ride.assignedDriverId)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        HubFact(
                            label = str(S.vehicle),
                            value = ride.assignedVehicleLabel.ifBlank {
                                state.vehicleLabel(ride.assignedVehicleId)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        HubFact(
                            label = str(S.eta),
                            value = ride.etaMinutes?.let { "$it min" }.orEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ride.assignedDriverId?.takeIf { it.isNotBlank() }?.let { driverId ->
                        ZillitButton(
                            text = str(S.desktop_open_in_maps),
                            onClick = { onEvent(HubEvent.TrackDriver(driverId)) },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}
