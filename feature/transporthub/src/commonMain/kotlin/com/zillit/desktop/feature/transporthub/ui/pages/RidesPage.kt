@file:Suppress("LongMethod", "TooManyFunctions") // A card lays out a dozen fields; splitting it hides the layout.

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubTime
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.StopAction
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.RideTab
import com.zillit.desktop.feature.transporthub.ui.kit.HubButton
import com.zillit.desktop.feature.transporthub.ui.kit.HubButtonStyle
import com.zillit.desktop.feature.transporthub.ui.kit.HubCard
import com.zillit.desktop.feature.transporthub.ui.kit.HubGlyphs
import com.zillit.desktop.feature.transporthub.ui.kit.HubPageHeader
import com.zillit.desktop.feature.transporthub.ui.kit.HubPriorityPill
import com.zillit.desktop.feature.transporthub.ui.kit.HubSegment
import com.zillit.desktop.feature.transporthub.ui.kit.HubSegmented
import com.zillit.desktop.feature.transporthub.ui.kit.HubStatusPill
import com.zillit.desktop.feature.transporthub.ui.kit.HubText
import com.zillit.desktop.feature.transporthub.ui.kit.TRACK_GREEN
import com.zillit.desktop.feature.transporthub.ui.kit.TRACK_RED
import com.zillit.desktop.feature.transporthub.ui.kit.VIVID
import com.zillit.desktop.feature.transporthub.ui.kit.dashedBorder
import com.zillit.desktop.feature.transporthub.ui.kit.hub
import com.zillit.desktop.feature.transporthub.ui.kit.rgb

/**
 * Coordinator → Ride Requests — the web's `coordinator/RideRequests.jsx`.
 *
 * Four stat cards that double as tab shortcuts, a toolbar card with the tabs
 * and the "My requests" switch, and the requests as a two-column grid of
 * cards on the hub's grey.
 *
 * Every count on the page is taken from the same scoped list as the cards, so
 * the tiles and the tab badges can never disagree with what is underneath
 * them — which they would the moment "My requests" narrowed one and not the
 * other.
 */
@Composable
internal fun RidesPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val counts = state.rideCounts
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HubPageHeader(
            icon = HubGlyphs.Chat,
            title = str(S.ride_requests),
            // The web counts every request here, not the tab's.
            description = str(S.desktop_hub_ride_count, state.rides.size.toString()),
            actions = {
                HubButton(
                    text = str(S.desktop_hub_view_active_cars),
                    onClick = { onEvent(HubEvent.ShowActiveCars) },
                    style = HubButtonStyle.Outline,
                    icon = AhIcons.Eye,
                )
                HubButton(
                    text = str(S.desktop_hub_new_ride),
                    onClick = { onEvent(HubEvent.NewRide) },
                    icon = HubGlyphs.Plus,
                )
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val tab = { t: RideTab -> { onEvent(HubEvent.RideTabChanged(t)) } }
            RideStat(
                HubGlyphs.Warning,
                str(S.pending),
                counts[RideTab.Pending] ?: 0,
                rgb(0xFFFBEB),
                rgb(0xD97706),
                tab(RideTab.Pending),
                Modifier.weight(1f),
            )
            RideStat(
                HubGlyphs.Car,
                str(S.active),
                counts[RideTab.Active] ?: 0,
                rgb(0xEEF2FF),
                rgb(0x4338CA),
                tab(RideTab.Active),
                Modifier.weight(1f),
            )
            RideStat(
                HubGlyphs.Clock,
                str(S.ah_topup_filter_urgent),
                state.urgentPendingRides,
                rgb(0xFEF2F2),
                rgb(0xDC2626),
                tab(RideTab.Pending),
                Modifier.weight(1f),
            )
            RideStat(
                HubGlyphs.Check,
                str(S.desktop_hub_completed_today),
                counts[RideTab.Completed] ?: 0,
                rgb(0xECFDF5),
                rgb(0x059669),
                tab(RideTab.Completed),
                Modifier.weight(1f),
            )
        }
        HubCard(Modifier.fillMaxWidth(), padding = 12.dp, radius = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HubSegmented(
                    options = RideTab.entries.map { HubSegment(it.name, it.label(), counts[it] ?: 0) },
                    selected = state.rideTab.name,
                    onSelect = { onEvent(HubEvent.RideTabChanged(RideTab.valueOf(it))) },
                )
                Box(Modifier.weight(1f))
                // Off until the profile — and so the viewer's id — is known.
                ZillitSwitch(
                    checked = state.onlyMyRides,
                    onCheckedChange = { onEvent(HubEvent.OnlyMyRidesChanged(it)) },
                    enabled = !state.viewer.userId.isNullOrBlank(),
                    label = str(S.desktop_hub_my_requests),
                )
            }
        }
        RideGrid(state, onEvent, Modifier.weight(1f))
    }
}

@Composable
private fun RideGrid(state: HubUiState, onEvent: (HubEvent) -> Unit, modifier: Modifier) {
    val p = hub
    val rides = state.visibleRides
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.bg)
            .border(1.dp, p.border, RoundedCornerShape(12.dp)),
    ) {
        if (rides.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ZillitIcon(HubGlyphs.Chat, tint = p.textMuted, size = 40.dp)
                HubText(
                    str(S.desktop_hub_no_tab_requests, state.rideTab.label().lowercase()),
                    14.sp,
                    p.textPrimary,
                    weight = FontWeight.SemiBold,
                )
                HubText(
                    if (state.rideTab == RideTab.Pending) {
                        str(S.desktop_hub_all_caught_up)
                    } else {
                        str(S.desktop_hub_nothing_in_view)
                    },
                    12.sp,
                    p.textMuted,
                )
            }
            return@Box
        }
        // Two to a row, as the web's `lg:grid-cols-2`; each pair shares a
        // height so the footers line up.
        ZillitLazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rides.chunked(2), key = { pair -> pair.first().id }) { pair ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { ride ->
                        RequestCard(state, ride, onEvent, Modifier.weight(1f).fillMaxHeight())
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The web's `StatCardAnt`: a tinted glyph, the figure, a small caps label. */
@Composable
private fun RideStat(
    icon: ImageVector,
    label: String,
    value: Int,
    bg: Color,
    fg: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val p = hub
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(p.surface)
            .border(1.dp, if (hovered) p.borderStrong else p.border, RoundedCornerShape(8.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(bg), Alignment.Center) {
            ZillitIcon(icon, tint = fg, size = 16.dp)
        }
        Column {
            HubText(value.toString(), 20.sp, p.textPrimary, weight = FontWeight.Bold)
            HubText(label.uppercase(), 10.sp, p.textMuted, weight = FontWeight.SemiBold, spacing = 0.4.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestCard(state: HubUiState, ride: RideRequest, onEvent: (HubEvent) -> Unit, modifier: Modifier) {
    val p = hub
    val busy = state.busyRowId == ride.id
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val who = ride.passengers.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        ?: ride.requesterName.ifBlank { str(S.desktop_unnamed) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(p.surface)
            .border(1.dp, if (hovered) HOVER_BORDER else p.border, RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .padding(14.dp),
    ) {
        RequestCardHeader(ride, who)
        Box(Modifier.height(12.dp))
        CompactRoute(ride.pickup, ride.dropoff)
        ride.etaMinutes?.let { eta ->
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ZillitIcon(HubGlyphs.Clock, tint = p.textSecondary, size = 12.dp)
                HubText(str(S.eta).uppercase(), 9.sp, p.textMuted, weight = FontWeight.SemiBold, spacing = 0.4.sp)
                HubText(
                    str(S.desktop_pc_amount_min, eta.toString()),
                    11.sp,
                    p.textPrimary,
                    weight = FontWeight.SemiBold,
                )
            }
        }
        Box(Modifier.height(8.dp))
        NoteStrip(ride.notes)
        // Not on the web's card, which leaves a coordinator opening the
        // ride to learn why it was refused or called off; here it stays.
        ride.rejectionReason.ifBlank { ride.cancellationReason }.takeIf { it.isNotBlank() && ride.status.isTerminal }
            ?.let { reason ->
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HubText(str(S.reason).uppercase(), 9.sp, rgb(0xB42318), weight = FontWeight.Bold, spacing = 0.4.sp)
                    HubText(reason, 11.sp, p.textSecondary, maxLines = 2)
                }
            }
        Box(Modifier.height(8.dp))
        DriverStrip(
            driver = ride.assignedDriverName.ifBlank { state.driverName(ride.assignedDriverId) }
                .takeIf { ride.assignedDriverId != null || it.isNotBlank() }.orEmpty(),
            vehicle = ride.assignedVehicleLabel.ifBlank { state.vehicleLabel(ride.assignedVehicleId) },
        )
        Box(Modifier.height(8.dp))
        var openList by remember(ride.id) { mutableStateOf<String?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryChip(
                text = if (ride.stops.isEmpty()) {
                    str(S.desktop_hub_no_stops)
                } else {
                    str(S.desktop_hub_n_stops, ride.stops.size.toString())
                },
                icon = HubGlyphs.MapPin,
                empty = ride.stops.isEmpty(),
                onClick = { openList = if (openList == LIST_STOPS) null else LIST_STOPS },
            )
            val pax = ride.passengerCount
            SummaryChip(
                text = if (pax == 0) {
                    str(S.desktop_transport_no_passengers)
                } else {
                    str(S.desktop_hub_n_passengers, pax.toString())
                },
                icon = HubGlyphs.Users,
                empty = pax == 0,
                onClick = { openList = if (openList == LIST_PASSENGERS) null else LIST_PASSENGERS },
            )
        }
        // The web shows these in a hover popover; here a click on the chip
        // opens them under it, which also keeps a stop's Complete reachable.
        when (openList) {
            LIST_STOPS -> StopList(ride, busy, onEvent)
            LIST_PASSENGERS -> PassengerList(ride)
        }
        Box(Modifier.weight(1f).height(12.dp))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .dashedTop(p.borderLight)
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { RideActions(ride, busy, onEvent) }
    }
}

/** Who, their department, and an urgent/high flag; the status and pickup time on the right. */
@Composable
private fun RequestCardHeader(ride: RideRequest, who: String) {
    val p = hub
    // Header: who, their department, then status and the pickup time.
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ZillitAvatar(
            name = who,
            userId = ride.requesterUserId ?: ride.passengers.firstOrNull()?.userId,
            size = 48.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HubText(
                    who,
                    15.sp,
                    p.textPrimary,
                    weight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (ride.priority == RidePriority.Urgent || ride.priority == RidePriority.High) {
                    HubPriorityPill(ride.priority.wire)
                }
            }
            if (ride.department.isNotBlank()) {
                HubText(ride.department, 11.5.sp, p.textMuted, weight = FontWeight.Medium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubStatusPill(ride.status.wire)
            val whenMs = ride.scheduledPickupTimeMs?.takeIf { it > 0 } ?: ride.createdMs.takeIf { it > 0 }
            whenMs?.let { PickupBadge(it.hubTime()) }
        }
    }
}

/** The amber "PICKUP 15:51" box in a card's top-right corner. */
@Composable
private fun PickupBadge(time: String) {
    val p = hub
    Column(
        modifier = Modifier
            .widthIn(min = 62.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(VIVID.copy(alpha = 0.1f))
            .border(1.dp, VIVID.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitIcon(HubGlyphs.Clock, tint = p.accentDark, size = 10.dp)
            HubText(str(S.pickup).uppercase(), 9.sp, p.accentDark, weight = FontWeight.Bold, spacing = 0.5.sp)
        }
        HubText(time, 15.sp, p.textPrimary, weight = FontWeight.Bold)
    }
}

/** Pickup over drop-off on one grey strip, a dotted joint between the dots. */
@Composable
private fun CompactRoute(pickup: HubLocation, dropoff: HubLocation) {
    val p = hub
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.surfaceAlt)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        RouteLine(str(S.pickup), pickup, TRACK_GREEN)
        Box(Modifier.padding(start = 3.dp, top = 4.dp, bottom = 4.dp).width(2.dp).height(8.dp).verticalDots(p.border))
        RouteLine(str(S.desktop_hub_dropoff_upper), dropoff, TRACK_RED)
    }
}

@Composable
private fun RouteLine(label: String, place: HubLocation, dot: Color) {
    val p = hub
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        HubText(
            label.uppercase(),
            9.sp,
            p.textMuted,
            weight = FontWeight.Bold,
            spacing = 0.5.sp,
            modifier = Modifier.width(52.dp),
        )
        if (place.address.isBlank()) {
            HubText(str(S.dm_gpr_not_set), 12.5.sp, p.textMuted, modifier = Modifier.weight(1f))
        } else {
            HubText(place.address, 12.5.sp, p.textPrimary, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    }
}

/** The note, quoted on amber — or a dashed "NOTE — Not available", so every card is the same height. */
@Composable
private fun NoteStrip(notes: String) {
    val p = hub
    val has = notes.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (has) VIVID.copy(alpha = 0.08f) else p.surfaceAlt)
            .then(
                if (has) {
                    Modifier.border(1.dp, VIVID.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                } else {
                    Modifier.dashedOutline(p.border, 6.dp)
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (has) {
            HubText("“$notes”", 11.sp, p.textSecondary)
        } else {
            HubText(str(S.note).uppercase(), 9.sp, p.textMuted, weight = FontWeight.Bold, spacing = 0.4.sp)
            HubText(str(S.desktop_hub_note_not_available), 11.sp, p.textMuted)
        }
    }
}

/** Who drives, green once someone does; "Not assigned" on dashes until then. */
@Composable
private fun DriverStrip(driver: String, vehicle: String) {
    val p = hub
    val has = driver.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (has) TRACK_GREEN.copy(alpha = 0.1f) else p.surfaceAlt)
            .then(
                if (has) {
                    Modifier.border(1.dp, TRACK_GREEN.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                } else {
                    Modifier.dashedOutline(p.border, 6.dp)
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(HubGlyphs.User, tint = if (has) TRACK_GREEN else p.textMuted, size = 12.dp)
        HubText(str(S.driver).uppercase(), 9.sp, p.textMuted, weight = FontWeight.Bold, spacing = 0.4.sp)
        if (has) {
            HubText(driver, 11.5.sp, p.textPrimary, weight = FontWeight.SemiBold)
            if (vehicle.isNotBlank()) HubText("· $vehicle", 11.sp, p.textMuted)
        } else {
            HubText(str(S.not_assigned), 11.5.sp, p.textMuted)
        }
    }
}

@Composable
private fun SummaryChip(text: String, icon: ImageVector, empty: Boolean, onClick: () -> Unit) {
    val p = hub
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (empty) p.surfaceAlt else p.accentBg)
            .then(
                if (empty) {
                    Modifier.dashedOutline(p.border, 999.dp)
                } else {
                    Modifier.border(1.dp, p.accentBorder, CircleShape).clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(icon, tint = if (empty) p.textMuted else p.accent, size = 12.dp)
        HubText(text, 11.sp, if (empty) p.textMuted else p.accent, weight = FontWeight.SemiBold)
    }
}

/** The web's Route Stops popover, in place. */
@Composable
private fun StopList(ride: RideRequest, busy: Boolean, onEvent: (HubEvent) -> Unit) {
    val p = hub
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, p.borderLight, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        HubText(
            str(S.desktop_hub_route_stops),
            12.sp,
            p.textPrimary,
            weight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        ride.stops.forEachIndexed { index, stop ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(if (stop.isCompleted) TRACK_GREEN.copy(alpha = 0.18f) else p.surfaceAlt),
                    Alignment.Center,
                ) {
                    HubText(
                        (index + 1).toString(),
                        10.sp,
                        if (stop.isCompleted) p.green else p.textMuted,
                        weight = FontWeight.Bold,
                    )
                }
                HubText(stop.location.display(), 12.sp, p.textPrimary, modifier = Modifier.weight(1f))
                val (fg, bg) = when (stop.action) {
                    StopAction.Pickup -> rgb(0x389E0D) to rgb(0xF6FFED)
                    StopAction.Dropoff -> rgb(0xCF1322) to rgb(0xFFF2F0)
                    StopAction.Waypoint -> rgb(0xD48806) to rgb(0xFFFBE6)
                }
                HubText(
                    stop.action.wire.uppercase(),
                    9.sp,
                    fg,
                    weight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp),
                )
                // The server addresses a stop by its position in the array,
                // not by its id — so the index is what goes out.
                if (!stop.isCompleted && ride.status.isActive) {
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !busy) { onEvent(HubEvent.CompleteStop(ride.id, index)) },
                        Alignment.Center,
                    ) { ZillitIcon(HubGlyphs.Check, tint = p.green, size = 12.dp) }
                }
            }
        }
    }
}

@Composable
private fun PassengerList(ride: RideRequest) {
    val p = hub
    // Everyone on the ride: its own passengers plus those picked up at a
    // stop, once each — by account, else by name, as the web dedupes.
    val seenIds = HashSet<String>()
    val seenNames = HashSet<String>()
    val all = (ride.passengers + ride.stops.flatMap { it.passengers }).filter { pax ->
        val id = pax.userId
        if (!id.isNullOrBlank()) return@filter seenIds.add(id)
        val name = pax.name.trim().lowercase()
        name.isEmpty() || seenNames.add(name)
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, p.borderLight, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        all.forEach { pax ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitAvatar(name = pax.name, userId = pax.userId, size = 24.dp)
                HubText(
                    pax.name.ifBlank { "—" },
                    12.sp,
                    p.textPrimary,
                    weight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (pax.contactNumber.isNotBlank()) HubText(pax.contactNumber, 11.sp, p.textMuted)
            }
        }
    }
}

/**
 * The footer chips — the web's rules, status by status: a pending request
 * is rejected, approved, or approved and assigned in one go; an approved one
 * waits for a driver; an assigned one starts; a moving one completes. Any
 * request a car is owed can be called off, gain a stop or a passenger, and
 * the server takes an edit until the ride is under way.
 */
@Composable
private fun RideActions(ride: RideRequest, busy: Boolean, onEvent: (HubEvent) -> Unit) {
    val s = ride.status
    if (s == RideStatus.Pending) {
        Chip(str(S.reject), ChipStyle.Danger, busy) { onEvent(HubEvent.RejectRide(ride)) }
        Chip(str(S.approve), ChipStyle.Outline, busy) { onEvent(HubEvent.ApproveRide(ride)) }
        Chip(str(S.desktop_hub_approve_assign), ChipStyle.Primary, busy, HubGlyphs.ArrowRight) {
            onEvent(HubEvent.AssignRide(ride))
        }
    }
    if (s == RideStatus.Approved) {
        Chip(
            str(S.txt_assign_driver),
            ChipStyle.Primary,
            busy,
            HubGlyphs.ArrowRight,
        ) { onEvent(HubEvent.AssignRide(ride)) }
    }
    if (s == RideStatus.Assigned) {
        Chip(
            str(S.desktop_hub_start_ride),
            ChipStyle.Primary,
            busy,
            HubGlyphs.ArrowRight,
        ) { onEvent(HubEvent.StartRide(ride)) }
    }
    if (s == RideStatus.InTransit) {
        Chip(
            str(S.dm_action_complete),
            ChipStyle.Primary,
            busy,
            HubGlyphs.Check,
        ) { onEvent(HubEvent.CompleteRide(ride)) }
    }
    if (s == RideStatus.Approved || s.isActive) {
        Chip(str(S.txt_cance_trip_title), ChipStyle.Danger, busy) { onEvent(HubEvent.CancelRide(ride)) }
    }
    if (s.isActive) {
        Chip(str(S.recce_add_stop), ChipStyle.Accent, busy, HubGlyphs.MapPin) { onEvent(HubEvent.AddRideStop(ride)) }
        Chip(str(S.desktop_hub_add_passenger), ChipStyle.Accent, busy, AhIcons.UserPlus) {
            onEvent(HubEvent.AddRidePassenger(ride))
        }
    }
    if (s in EDITABLE_STATUSES) {
        Chip(str(S.edit), ChipStyle.Outline, busy, HubGlyphs.Pencil) { onEvent(HubEvent.EditRide(ride)) }
    }
}

private enum class ChipStyle { Primary, Outline, Danger, Accent }

/** The web's `rqCardBtn.chip`: a 32dp pill, 12sp bold. */
@Composable
private fun Chip(text: String, style: ChipStyle, busy: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val p = hub
    val (bg, fg, border) = when (style) {
        ChipStyle.Primary -> Triple(VIVID, Color.White, VIVID)
        ChipStyle.Outline -> Triple(p.surface, p.textPrimary, p.border)
        ChipStyle.Danger -> Triple(p.surface, rgb(0xB42318), rgb(0xFECDCA))
        ChipStyle.Accent -> Triple(p.surface, p.accent, p.accent)
    }
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { ZillitIcon(it, tint = fg.copy(alpha = if (busy) 0.5f else 1f), size = 13.dp) }
        HubText(text, 12.sp, fg.copy(alpha = if (busy) 0.5f else 1f), weight = FontWeight.Bold, spacing = 0.2.sp)
    }
}

private fun Modifier.dashedTop(color: Color): Modifier = drawBehind {
    drawLine(
        color,
        Offset.Zero,
        Offset(size.width, 0f),
        1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
    )
}

private fun Modifier.verticalDots(color: Color): Modifier = drawBehind {
    drawLine(
        color,
        Offset(size.width / 2, 0f),
        Offset(size.width / 2, size.height),
        2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
    )
}

private fun Modifier.dashedOutline(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier =
    dashedBorder(color, radius)

internal fun RideTab.label(): String = when (this) {
    RideTab.Pending -> str(S.pending)
    RideTab.Active -> str(S.active)
    RideTab.Completed -> str(S.completed)
    RideTab.Rejected -> str(S.rejected)
}

private val EDITABLE_STATUSES = setOf(RideStatus.Pending, RideStatus.Approved, RideStatus.Assigned)
private val HOVER_BORDER = Color(0x66FC9404)
private const val LIST_STOPS = "stops"
private const val LIST_PASSENGERS = "passengers"
