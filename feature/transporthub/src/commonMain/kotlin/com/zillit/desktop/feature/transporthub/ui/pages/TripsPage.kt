@file:Suppress("LongMethod", "TooManyFunctions") // A row lays out a dozen fields; splitting it hides the layout.

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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDate
import com.zillit.desktop.feature.transporthub.data.hubShortDateTime
import com.zillit.desktop.feature.transporthub.data.hubTime
import com.zillit.desktop.feature.transporthub.data.startOfDay
import com.zillit.desktop.feature.transporthub.data.toEpochMillisOrNull
import com.zillit.desktop.feature.transporthub.data.toIsoDate
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.TripTab
import com.zillit.desktop.feature.transporthub.ui.kit.HubButton
import com.zillit.desktop.feature.transporthub.ui.kit.HubButtonStyle
import com.zillit.desktop.feature.transporthub.ui.kit.HubCard
import com.zillit.desktop.feature.transporthub.ui.kit.HubEmpty
import com.zillit.desktop.feature.transporthub.ui.kit.HubGlyphs
import com.zillit.desktop.feature.transporthub.ui.kit.HubPageHeader
import com.zillit.desktop.feature.transporthub.ui.kit.HubSegment
import com.zillit.desktop.feature.transporthub.ui.kit.HubSegmented
import com.zillit.desktop.feature.transporthub.ui.kit.HubText
import com.zillit.desktop.feature.transporthub.ui.kit.TRACK_GREEN
import com.zillit.desktop.feature.transporthub.ui.kit.TRACK_RED
import com.zillit.desktop.feature.transporthub.ui.kit.hub
import com.zillit.desktop.feature.transporthub.ui.kit.rgb

/**
 * Coordinator → Trips — the web's `coordinator/Trips.jsx`.
 *
 * A banner with New Trip, one toolbar card (day, search, status), and a
 * column of row cards. Trips is the one hub list the web lays out as rows
 * rather than a grid, so a coordinator can tell it apart at a glance.
 *
 * The list mixes real trips and ride requests — the server says which in
 * `resource_type` — and the difference matters for Delete, which has to go to
 * the route that owns the row. A ride request already past acting on hides
 * the button entirely rather than offering a call the server will refuse.
 */
@Composable
internal fun TripsPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val shown = state.visibleTrips
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val count = str(S.desktop_hub_trip_count, shown.size.toString())
        HubPageHeader(
            icon = HubGlyphs.Path,
            title = str(S.trips),
            description = if (state.tripDateMs > 0) "$count · ${state.tripDateMs.hubDate()}" else count,
            actions = {
                HubButton(
                    text = str(S.desktop_hub_new_trip),
                    onClick = { onEvent(HubEvent.NewTrip) },
                    icon = HubGlyphs.Plus,
                )
            },
        )
        TripToolbar(state, onEvent)
        if (shown.isEmpty()) {
            HubEmpty(str(S.desktop_hub_no_trips_match))
            return@Column
        }
        ZillitLazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.id }) { trip ->
                TripRow(
                    trip = trip,
                    nowMs = state.nowMs,
                    busy = state.busyRowId == trip.id,
                    coordinator = state.role == HubRole.Coordinator,
                    driverName = trip.driverName.ifBlank { state.driverName(trip.driverId) },
                    vehicleLabel = trip.vehicleLabel.ifBlank { state.vehicleLabel(trip.vehicleId) },
                    onEvent = onEvent,
                )
            }
        }
    }
}

/** The web's toolbar card: a day, a search, and the status buckets. */
@Composable
private fun TripToolbar(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    HubCard(Modifier.fillMaxWidth(), padding = 12.dp, radius = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Clearing the day shows every day again — antd's allowClear.
            ZillitDateField(
                value = state.tripDateMs.toIsoDate(),
                onValueChange = { typed -> onEvent(HubEvent.TripDateChanged(typed.toEpochMillisOrNull() ?: 0)) },
                placeholder = str(S.dd_filter_by_date),
                modifier = Modifier.width(DATE_FILTER_WIDTH),
            )
            ZillitSearchField(
                value = state.tripSearch,
                onValueChange = { onEvent(HubEvent.TripSearchChanged(it)) },
                placeholder = str(S.desktop_hub_trips_search_short),
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            HubSegmented(
                options = TripTab.entries.map { HubSegment(it.name, it.label()) },
                selected = state.tripTab.name,
                onSelect = { onEvent(HubEvent.TripTabChanged(TripTab.valueOf(it))) },
            )
        }
    }
}

@Composable
private fun TripRow(
    trip: Trip,
    nowMs: Long,
    busy: Boolean,
    coordinator: Boolean,
    driverName: String,
    vehicleLabel: String,
    onEvent: (HubEvent) -> Unit,
) {
    val p = hub
    val open = { onEvent(HubEvent.OpenTrip(trip.id)) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface)
            .border(1.dp, if (hovered) VIVID_BORDER else p.border, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = open)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TripRowHeader(trip)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TripRoute(trip.pickup, trip.dropoff, Modifier.weight(ROUTE_WEIGHT).fillMaxHeight())
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.surface)
                    .border(1.dp, p.borderLight, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HubText(
                        str(S.details).uppercase(),
                        9.5.sp,
                        p.textMuted,
                        weight = FontWeight.Bold,
                        spacing = 0.6.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TripStatusTag(trip.status)
                }
                val time = trip.listTime(nowMs)
                DetailLine(HubGlyphs.Calendar, str(S.schedule), time ?: str(S.desktop_hub_no_pickup_time), time == null)
                DetailLine(HubGlyphs.Users, str(S.txt_passengers), peopleCount(trip.passengers.size))
                if (driverName.isNotBlank()) DetailLine(HubGlyphs.SteeringWheel, str(S.driver), driverName)
                if (vehicleLabel.isNotBlank()) DetailLine(HubGlyphs.Truck, str(S.vehicle), vehicleLabel)
                Box(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth().topDashedRule(p.borderLight).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    // Hidden rather than disabled on a row the server cannot
                    // act on: a ride request already completed, cancelled or
                    // refused. The confirm names the passenger, as the web's.
                    if (coordinator && !trip.deleteIsPointless) {
                        HubButton(
                            text = if (trip.kind == TripResourceKind.RideRequest) str(S.cancel) else str(S.delete),
                            onClick = { onEvent(HubEvent.DeleteTrip(trip)) },
                            style = HubButtonStyle.Danger,
                            icon = HubGlyphs.Trash,
                            loading = busy,
                            height = 28.dp,
                            fontSize = 12.sp,
                        )
                    }
                    HubButton(
                        text = str(S.view),
                        onClick = open,
                        icon = HubGlyphs.CaretRight,
                        height = 28.dp,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * The avatar and the row's name: the trip's own title when the coordinator
 * gave it one (the passenger drops to a second line), else the passenger.
 * The chips ride on whichever line names the passenger.
 */
@Composable
private fun TripRowHeader(trip: Trip) {
    val p = hub
    val passenger = trip.passengers.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        ?: trip.requesterName.ifBlank { str(S.desktop_unnamed) }
    val title = trip.title.trim()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(p.accentBg).border(1.dp, p.accentBorder, CircleShape),
            Alignment.Center,
        ) { ZillitIcon(HubGlyphs.CarProfile, tint = p.accentDark, size = 20.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title.isNotEmpty()) {
                HubText(title, 14.sp, p.textPrimary, weight = FontWeight.SemiBold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HubText(passenger, 12.sp, p.textMuted)
                    TripChips(trip)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HubText(passenger, 14.sp, p.textPrimary, weight = FontWeight.SemiBold)
                    TripChips(trip)
                }
            }
        }
    }
}

/** "+2 more", the short id, an urgent/high flag, and permanent/recurring. */
@Composable
private fun TripChips(trip: Trip) {
    val p = hub
    val extra = trip.passengers.size - 1
    if (extra > 0) {
        HubText(
            str(S.desktop_dm_n_more, extra.toString()),
            10.sp,
            p.textSecondary,
            weight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(p.surfaceAlt).padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
    ZillitText(
        text = "#" + trip.id.takeLast(SHORT_ID).uppercase(),
        style = androidx.compose.ui.text.TextStyle(
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.3.sp,
        ),
        color = p.textMuted,
        maxLines = 1,
    )
    // Only the two that need a coordinator's eye are flagged on the row.
    if (trip.priority == RidePriority.Urgent || trip.priority == RidePriority.High) {
        val urgent = trip.priority == RidePriority.Urgent
        val fg = if (urgent) rgb(0xB91C1C) else rgb(0xC2410C)
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (urgent) rgb(0xFEE2E2) else rgb(0xFFEDD5))
                .padding(horizontal = 7.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitIcon(HubGlyphs.Lightning, tint = fg, size = 9.dp)
            HubText(trip.priority.wire.uppercase(), 10.sp, fg, weight = FontWeight.Bold, spacing = 0.4.sp)
        }
    }
    val type = trip.tripType.lowercase()
    if (type == "permanent" || type == "recurring") {
        HubText(
            type.replaceFirstChar { it.uppercase() },
            10.sp,
            p.textSecondary,
            weight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(p.surfaceAlt).padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

/** The row's left half: pickup over drop-off on the grey route card. */
@Composable
private fun TripRoute(pickup: HubLocation, dropoff: HubLocation, modifier: Modifier) {
    val p = hub
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(p.surfaceAlt)
            .border(1.dp, p.borderLight, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.fillMaxHeight().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(TRACK_GREEN))
            Box(Modifier.weight(1f).width(2.dp).padding(vertical = 4.dp).verticalDashes(p.border))
            Box(Modifier.size(8.dp).clip(CircleShape).background(TRACK_RED))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteEnd(str(S.desktop_hub_pickup_upper), pickup, rgb(0x059669), TRACK_GREEN)
            RouteEnd(str(S.desktop_hub_dropoff_upper), dropoff, rgb(0xDC2626), TRACK_RED)
        }
    }
}

@Composable
private fun RouteEnd(label: String, place: HubLocation, labelColor: Color, pin: Color) {
    val p = hub
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(pin.copy(alpha = 0.15f)),
            Alignment.Center,
        ) { ZillitIcon(HubGlyphs.MapPin, tint = pin, size = 14.dp) }
        Column(Modifier.weight(1f)) {
            HubText(label, 9.5.sp, labelColor, weight = FontWeight.Bold, spacing = 0.6.sp)
            HubText(place.address.ifBlank { "—" }, 12.5.sp, p.textPrimary, weight = FontWeight.SemiBold)
        }
    }
}

/** One label/value line of the Details panel, the value right-aligned. */
@Composable
private fun DetailLine(icon: ImageVector, label: String, value: String, muted: Boolean = false) {
    val p = hub
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitIcon(icon, tint = p.textMuted, size = 12.dp)
        HubText(label, 11.sp, p.textMuted, modifier = Modifier.width(72.dp))
        ZillitText(
            text = value,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                fontWeight = if (muted) FontWeight.Medium else FontWeight.SemiBold,
                fontStyle = if (muted) FontStyle.Italic else FontStyle.Normal,
                textAlign = TextAlign.End,
            ),
            color = if (muted) p.textMuted else p.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** antd's borderless `Tag` in the colour the web maps each status to. */
@Composable
private fun TripStatusTag(status: TripStatus) {
    val (fg, bg) = when (status) {
        TripStatus.Assigned -> rgb(0x0958D9) to rgb(0xE6F4FF)
        TripStatus.EnRoutePickup, TripStatus.AtPickup -> rgb(0xD48806) to rgb(0xFFFBE6)
        TripStatus.InTransit, TripStatus.Active, TripStatus.Departed, TripStatus.Boarding ->
            rgb(0x1677FF) to rgb(0xE6F4FF)

        TripStatus.AtDropoff -> rgb(0x1D39C4) to rgb(0xF0F5FF)
        TripStatus.Completed -> rgb(0x389E0D) to rgb(0xF6FFED)
        TripStatus.Cancelled, TripStatus.Rejected -> rgb(0xCF1322) to rgb(0xFFF2F0)
        else -> hub.textSecondary to hub.surfaceAlt
    }
    HubText(
        status.wire.replace('_', ' ').replaceFirstChar { it.uppercase() },
        11.sp,
        fg,
        weight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 1.dp),
    )
}

/**
 * When the row's pickup is — "Today, 15:51", "Tomorrow, …", else the date —
 * or null when there is none to show.
 *
 * The server once stamped `scheduled_pickup_time = Date.now()` on a trip
 * created without one, so a pickup within five seconds of creation is that
 * stamp, not a time anyone chose; the web shows "No pickup time" for it.
 */
internal fun Trip.listTime(nowMs: Long): String? {
    val at = passengers.firstOrNull()?.scheduledPickupTimeMs?.takeIf { it > 0 }
        ?: scheduledPickupTimeMs?.takeIf { it > 0 }
        ?: return null
    if (createdMs > 0 && kotlin.math.abs(at - createdMs) < AUTO_FILL_WINDOW_MS) return null
    val today = nowMs.startOfDay()
    return when (at.startOfDay()) {
        today -> str(S.desktop_hub_today_at, at.hubTime())
        today + DAY_MS -> str(S.desktop_hub_tomorrow_at, at.hubTime())
        else -> at.hubShortDateTime()
    }
}

/** A 1px dashed rule along the top edge — the actions' `border-top: dashed`. */
private fun Modifier.topDashedRule(color: Color): Modifier = drawBehind {
    drawLine(color, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(), pathEffect = dashes())
}

/** The route's dashed track, centred down a thin column. */
private fun Modifier.verticalDashes(color: Color): Modifier = drawBehind {
    val x = size.width / 2
    drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx(), pathEffect = dashes())
}

private fun androidx.compose.ui.unit.Density.dashes(): PathEffect =
    PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))

internal fun TripTab.label(): String = when (this) {
    TripTab.All -> str(S.all)
    TripTab.Pending -> str(S.pending)
    TripTab.Assigned -> str(S.assigned)
    TripTab.InTransit -> str(S.desktop_card_in_transit)
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

private val DATE_FILTER_WIDTH = 170.dp
private val SEARCH_WIDTH = 300.dp
private val VIVID_BORDER = Color(0x66FC9404)
private const val ROUTE_WEIGHT = 1.4f
private const val SHORT_ID = 6
private const val AUTO_FILL_WINDOW_MS = 5_000L
private const val DAY_MS = 24L * 60 * 60 * 1000
