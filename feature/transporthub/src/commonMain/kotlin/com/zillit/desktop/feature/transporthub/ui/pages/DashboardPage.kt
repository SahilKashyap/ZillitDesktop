// A row lays out a dozen fields; splitting it hides the layout. The numbers
// are the web's own colours and sizes, kept verbatim.
@file:Suppress("LongMethod", "MagicNumber")

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDate
import com.zillit.desktop.feature.transporthub.data.hubShortDateTime
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.ui.DashboardFilter
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.kit.HubButton
import com.zillit.desktop.feature.transporthub.ui.kit.HubButtonStyle
import com.zillit.desktop.feature.transporthub.ui.kit.HubChip
import com.zillit.desktop.feature.transporthub.ui.kit.HubEmpty
import com.zillit.desktop.feature.transporthub.ui.kit.HubGlyphs
import com.zillit.desktop.feature.transporthub.ui.kit.HubIconTile
import com.zillit.desktop.feature.transporthub.ui.kit.HubText
import com.zillit.desktop.feature.transporthub.ui.kit.hub
import com.zillit.desktop.feature.transporthub.ui.kit.hubType
import com.zillit.desktop.feature.transporthub.ui.kit.rgb

/**
 * Coordinator → Dashboard — the hub's landing page, as the web draws it.
 *
 * Not a `SectionBanner` page: the web gives the Dashboard its own header — a
 * solid amber tile, the date under the title, and "View all trips" — over
 * four white counters and a Recent Trips card with the web's five chips.
 *
 * The counters come from `/insights/dashboard` and the trend line under each
 * from its `*_yesterday` key. A deployment that ships neither leaves the
 * number at zero and the trend reading "No change", rather than replacing the
 * page with an error, because the trip list below is still worth having.
 */
@Composable
internal fun DashboardPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .widthIn(max = MAX_WIDTH),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header(state, onEvent)
        StatCards(state)
        RecentTripsCard(state, onEvent, Modifier.weight(1f))
    }
}

@Composable
private fun Header(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val p = hub
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HubIconTile(HubGlyphs.Path, background = p.accent, tint = Color.White, size = 44.dp, iconSize = 22.dp)
        Column(Modifier.weight(1f)) {
            HubText(str(S.dashboard), 24.sp, p.textPrimary, weight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(HubGlyphs.Calendar, tint = p.textMuted, size = 12.dp)
                HubText(state.nowMs.hubDate(), 12.sp, p.textMuted)
            }
        }
        HubButton(
            text = str(S.desktop_hub_view_all_trips),
            onClick = { onEvent(HubEvent.OpenAllTrips) },
            icon = HubGlyphs.PaperPlane,
            height = 40.dp,
            fontSize = 15.sp,
        )
    }
}

/** One counter's look and numbers. */
private data class Stat(
    val label: String,
    val value: Int,
    val icon: ImageVector,
    val fg: Color,
    val bg: Color,
    val sub: String?,
    /** Today against yesterday; null when the server ships no `*_yesterday`. */
    val delta: Int?,
)

@Composable
private fun StatCards(state: HubUiState) {
    val p = hub
    val i = state.insights
    val available = i.availableFromFleet ?: state.availableVehicles.size
    val stats = listOf(
        Stat(
            label = str(S.active_trips),
            value = i.activeTrips,
            icon = HubGlyphs.Path,
            fg = p.accentDark,
            bg = p.accentBg,
            // "Active" is four modes at once, so the split is the useful part.
            sub = if (i.activeTrips > 0) {
                str(S.desktop_hub_in_transit_assigned, i.inTransitTotal.toString(), i.assignedTotal.toString())
            } else {
                null
            },
            delta = i.yesterday["active_trips_yesterday"]?.let { i.activeTrips - it },
        ),
        Stat(
            label = str(S.desktop_hub_completed_today),
            value = i.completedTotal,
            icon = HubGlyphs.Check,
            fg = p.green,
            bg = p.greenBg,
            sub = null,
            delta = i.yesterday["completed_yesterday"]?.let { i.completedTotal - it },
        ),
        Stat(
            label = str(S.desktop_hub_pending_requests),
            value = state.pendingRideCount,
            icon = HubGlyphs.Clock,
            fg = p.accentDark,
            bg = p.accentBg,
            sub = i.rideRequestTotal?.let { str(S.desktop_hub_of_total, it.toString()) },
            delta = i.yesterday["pending_requests_yesterday"]?.let { state.pendingRideCount - it },
        ),
        Stat(
            label = str(S.desktop_hub_available_vehicles),
            value = available,
            icon = HubGlyphs.Truck,
            fg = p.blue,
            bg = p.blueBg,
            sub = i.fleetTotal?.let {
                str(S.desktop_hub_of_fleet_in_use, (i.fleetInUse ?: 0).toString(), it.toString())
            },
            delta = i.yesterday["available_vehicles_yesterday"]?.let { available - it },
        ),
    )
    // Intrinsic height: the four cards match the tallest of them, as the web's
    // grid row does, without stretching down the page.
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        stats.forEach { stat -> StatCard(stat, Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun StatCard(stat: Stat, modifier: Modifier) {
    val p = hub
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(p.surface)
            .border(1.dp, p.border, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HubIconTile(stat.icon, background = stat.bg, tint = stat.fg, size = 40.dp, iconSize = 20.dp)
        Column(Modifier.weight(1f)) {
            HubText(stat.label, 12.sp, p.textMuted, weight = FontWeight.SemiBold, spacing = 0.2.sp)
            HubText(
                stat.value.toString(),
                28.sp,
                p.textPrimary,
                weight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp),
            )
            stat.sub?.let { HubText(it, 11.sp, p.textMuted, modifier = Modifier.padding(top = 2.dp)) }
            Delta(stat.delta)
        }
    }
}

/** "— No change", or "↑ 3 from yesterday" in green / "↓ 2 from yesterday" in red. */
@Composable
private fun Delta(delta: Int?) {
    val p = hub
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (delta == null || delta == 0) {
            Box(Modifier.width(10.dp).height(1.5.dp).clip(RoundedCornerShape(2.dp)).background(p.textMuted))
            HubText(str(S.desktop_csync_sched_no_change), 11.sp, p.textMuted)
            return@Row
        }
        val up = delta > 0
        val fg = if (up) rgb(0x047857) else rgb(0xB91C1C)
        val bg = if (up) rgb(0xD1FAE5) else rgb(0xFEE2E2)
        Box(Modifier.size(16.dp).clip(CircleShape).background(bg), Alignment.Center) {
            ZillitIcon(if (up) HubGlyphs.ArrowUp else HubGlyphs.ArrowDown, tint = fg, size = 10.dp)
        }
        HubText(
            str(S.desktop_hub_from_yesterday, kotlin.math.abs(delta).toString()),
            11.sp,
            fg,
            weight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RecentTripsCard(state: HubUiState, onEvent: (HubEvent) -> Unit, modifier: Modifier) {
    val p = hub
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.surface)
            .border(1.dp, p.border, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().bottomRule(p.borderLight).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HubText(str(S.recent_trips), 16.sp, p.textPrimary, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            HubButton(
                text = str(S.desktop_hub_see_all_arrow),
                onClick = { onEvent(HubEvent.OpenAllTrips) },
                style = HubButtonStyle.Link,
                height = 24.dp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().bottomRule(p.borderLight).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DashboardFilter.entries.forEach { filter ->
                HubChip(
                    label = filter.label(),
                    active = state.dashboardFilter == filter,
                    onClick = { onEvent(HubEvent.DashboardFilterChanged(filter)) },
                )
            }
            Spacer(Modifier.weight(1f))
            ZillitSearchField(
                value = state.dashboardSearch,
                onValueChange = { onEvent(HubEvent.DashboardSearchChanged(it)) },
                placeholder = str(S.desktop_hub_search_trips),
                modifier = Modifier.width(240.dp),
            )
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp)),
                Alignment.Center,
            ) { ZillitIcon(HubGlyphs.Funnel, tint = p.textPrimary, size = 14.dp) }
        }
        val rows = state.recentTrips
        if (rows.isEmpty()) {
            Box(Modifier.padding(32.dp)) {
                HubEmpty(
                    if (state.dashboardSearch.isBlank()) {
                        str(S.desktop_hub_no_trips_yet)
                    } else {
                        str(S.desktop_hub_no_matching_trips)
                    },
                )
            }
            return@Column
        }
        ZillitLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
}

@Composable
private fun DashboardTripRow(trip: Trip, driverName: String, vehicleLabel: String, onOpen: () -> Unit) {
    val p = hub
    val tone = dashboardTone(trip)
    Row(
        modifier = Modifier.fillMaxWidth().bottomRule(p.borderLight).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(p.accentBg), Alignment.Center) {
            ZillitIcon(HubGlyphs.CarProfile, tint = p.accentDark, size = 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubText(
                    trip.passengers.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                        ?: trip.requesterName.ifBlank { str(S.desktop_hub_unnamed_passenger) },
                    14.sp,
                    p.textPrimary,
                    weight = FontWeight.Bold,
                )
                // The id's last six, so a row can be quoted without reading
                // out a whole ObjectId.
                ZillitText(
                    text = "#${trip.id.takeLast(SHORT_ID).uppercase()}",
                    style = hubType(10.sp, spacing = 0.3.sp).copy(fontFamily = FontFamily.Monospace),
                    color = p.textMuted,
                )
                if (trip.priority == RidePriority.Urgent || trip.priority == RidePriority.High) {
                    val urgent = trip.priority == RidePriority.Urgent
                    Row(
                        modifier = Modifier.clip(CircleShape).background(if (urgent) rgb(0xFEE2E2) else rgb(0xFFEDD5))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val fg = if (urgent) rgb(0xB91C1C) else rgb(0xC2410C)
                        ZillitIcon(HubGlyphs.Lightning, tint = fg, size = 9.dp)
                        HubText(trip.priority.wire.uppercase(), 10.sp, fg, weight = FontWeight.Bold, spacing = 0.4.sp)
                    }
                }
                if (trip.tripType == "permanent" || trip.tripType == "recurring") {
                    HubText(
                        trip.tripType.replaceFirstChar { it.uppercase() },
                        10.sp,
                        p.textSecondary,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).background(p.surfaceAlt)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PinLine(trip.pickup.address, PIN_GREEN)
                PinLine(trip.dropoff.address, PIN_RED)
            }
            val at = trip.scheduledPickupTimeMs ?: trip.createdMs
            val meta = listOfNotNull(
                at.hubShortDateTime().takeIf { it.isNotBlank() }?.let { HubGlyphs.Calendar to it },
                trip.passengers.size.takeIf { it > 0 }?.let { HubGlyphs.Users to peopleLabel(it) },
                driverName.takeIf { it.isNotBlank() }?.let { HubGlyphs.SteeringWheel to it },
                vehicleLabel.takeIf { it.isNotBlank() }?.let { HubGlyphs.Truck to it },
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                meta.forEachIndexed { index, (icon, text) ->
                    if (index > 0) Box(Modifier.size(3.dp).clip(CircleShape).background(p.textMuted))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ZillitIcon(icon, tint = p.textMuted, size = 11.dp)
                        HubText(text, 11.sp, p.textMuted)
                    }
                }
            }
        }
        HubText(
            tone.label,
            11.sp,
            tone.fg,
            weight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(tone.bg).padding(horizontal = 10.dp, vertical = 3.dp),
        )
        HubButton(
            text = str(S.view),
            onClick = onOpen,
            modifier = Modifier.widthIn(min = 116.dp),
            height = 30.dp,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun PinLine(address: String, tint: Color) {
    val p = hub
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitIcon(HubGlyphs.MapPin, tint = tint, size = 11.dp)
        HubText(address.ifBlank { "—" }, 12.sp, p.textSecondary)
    }
}

/** The Dashboard's soft status pill — its own palette, not the ride cards'. */
private data class Tone(val label: String, val fg: Color, val bg: Color)

private fun dashboardTone(trip: Trip): Tone {
    // A permanent allocation reads as one whatever its runtime status says.
    if (trip.tripType == "permanent") return Tone(str(S.desktop_hub_vehicle_permanent), rgb(0x475467), rgb(0xF3F4F6))
    val label = trip.status.label()
    return when (trip.status.wire) {
        "pending" -> Tone(label, rgb(0xB45309), rgb(0xFEF3C7))
        "approved", "assigned" -> Tone(label, rgb(0x1D4ED8), rgb(0xDBEAFE))
        "en_route_pickup", "at_pickup" -> Tone(label, rgb(0xA16207), rgb(0xFEF3C7))
        "in_transit" -> Tone(label, rgb(0x6D28D9), rgb(0xEDE9FE))
        "at_dropoff" -> Tone(label, rgb(0x0E7490), rgb(0xCFFAFE))
        "completed" -> Tone(label, rgb(0x047857), rgb(0xD1FAE5))
        "cancelled", "rejected" -> Tone(label, rgb(0xB91C1C), rgb(0xFEE2E2))
        else -> Tone(label, rgb(0x475467), rgb(0xF3F4F6))
    }
}

private fun peopleLabel(n: Int): String =
    if (n == 1) str(S.desktop_tasks_people_count_one) else str(S.desktop_tasks_people_count, n.toString())

/** A 1dp rule along the bottom — a card section's own divider. */
private fun Modifier.bottomRule(color: Color): Modifier = drawBehind {
    drawRect(color, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = size.copy(height = 1.dp.toPx()))
}

internal fun DashboardFilter.label(): String = when (this) {
    DashboardFilter.All -> str(S.all)
    DashboardFilter.Active -> str(S.active)
    DashboardFilter.Pending -> str(S.pending)
    DashboardFilter.Completed -> str(S.completed)
    DashboardFilter.Cancelled -> str(S.cancelled)
}

private val PIN_GREEN = Color(0xFF10B981)
private val PIN_RED = Color(0xFFEF4444)
private val MAX_WIDTH = 1200.dp
private const val SHORT_ID = 6
