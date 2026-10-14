// A passenger's and a driver's own pages. Each card lays out a dozen fields
// the web's way; splitting them hides the layout. The numbers are the web's
// own colours and sizes, kept verbatim so they diff against hubTheme.css.
@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber")

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDate
import com.zillit.desktop.feature.transporthub.data.hubDateTime
import com.zillit.desktop.feature.transporthub.data.hubTime
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.ui.DriverRideTab
import com.zillit.desktop.feature.transporthub.ui.DriverStep
import com.zillit.desktop.feature.transporthub.ui.DriverTripView
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.kit.HubButton
import com.zillit.desktop.feature.transporthub.ui.kit.HubButtonStyle
import com.zillit.desktop.feature.transporthub.ui.kit.HubCard
import com.zillit.desktop.feature.transporthub.ui.kit.HubDivider
import com.zillit.desktop.feature.transporthub.ui.kit.HubDriverStrip
import com.zillit.desktop.feature.transporthub.ui.kit.HubEmpty
import com.zillit.desktop.feature.transporthub.ui.kit.HubFilterBar
import com.zillit.desktop.feature.transporthub.ui.kit.HubGlyphs
import com.zillit.desktop.feature.transporthub.ui.kit.HubIconTile
import com.zillit.desktop.feature.transporthub.ui.kit.HubMeta
import com.zillit.desktop.feature.transporthub.ui.kit.HubPage
import com.zillit.desktop.feature.transporthub.ui.kit.HubPriorityPill
import com.zillit.desktop.feature.transporthub.ui.kit.HubRouteBlock
import com.zillit.desktop.feature.transporthub.ui.kit.HubSegment
import com.zillit.desktop.feature.transporthub.ui.kit.HubSegmented
import com.zillit.desktop.feature.transporthub.ui.kit.HubStatusPill
import com.zillit.desktop.feature.transporthub.ui.kit.HubTag
import com.zillit.desktop.feature.transporthub.ui.kit.HubText
import com.zillit.desktop.feature.transporthub.ui.kit.HubWhenChip
import com.zillit.desktop.feature.transporthub.ui.kit.hub
import com.zillit.desktop.feature.transporthub.ui.kit.rgb

/* ── Passenger → My Trips ──────────────────────────────────────────────── */

/**
 * Passenger → My Trips — the web's `passenger/History.jsx`.
 *
 * The unified `/trip` feed, which mixes a passenger's own ride requests, the
 * trips a coordinator put them on, and their standing allocations — each
 * drawn its own way. Two cards to a row, as the web lays them out.
 */
@Composable
internal fun PassengerTripsPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val shown = state.passengerTrips
    HubPage(
        title = str(S.desktop_hub_my_trips),
        icon = HubGlyphs.PaperPlane,
        subtitle = tripCount(state.trips.size),
    ) {
        if (state.trips.isNotEmpty()) {
            HubFilterBar(
                filters = state.tripFilters,
                onChange = { onEvent(HubEvent.TripFiltersChanged(it)) },
                statusOptions = TRIP_STATUS_OPTIONS.map { (id, label) -> HubSegment(id, label()) },
                total = state.trips.size,
                shown = shown.size,
                expanded = state.filtersExpanded,
                onToggleExpanded = { onEvent(HubEvent.ToggleFiltersExpanded) },
            )
        }
        when {
            state.trips.isEmpty() -> HubEmpty(str(S.desktop_hub_no_past_rides))
            shown.isEmpty() -> HubEmpty(str(S.desktop_hub_no_rides_match))
            else -> ZillitLazyVerticalGrid(
                columns = GridCells.Adaptive(PASSENGER_CARD_MIN),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.id }) { trip ->
                    if (trip.kind == TripResourceKind.PermanentTrip) {
                        AllocationCard(trip, state, onEvent)
                    } else {
                        PassengerTripCard(trip, state, onEvent)
                    }
                }
            }
        }
    }
}

@Composable
private fun PassengerTripCard(trip: Trip, state: HubUiState, onEvent: (HubEvent) -> Unit) {
    HubCard {
        CardHeader(
            title = str(S.desktop_hub_ride_number, trip.id.takeLast(SHORT_ID)),
            status = trip.status.wire,
            priority = trip.priority.wire,
            whenText = trip.scheduledPickupTimeMs?.hubDateTime(),
        )
        HubRouteBlock(trip.pickup, trip.dropoff, emptyText = str(S.desktop_hub_not_recorded))
        Spacer(Modifier.height(16.dp))
        MetaRow(trip.headCount, trip.department, trip.createdMs)
        val driver = state.driverLabel(trip.driverId, trip.driverName)
        val vehicle = state.vehicleLabelOrId(trip.vehicleId, trip.vehicleLabel)
        if (driver.isNotBlank() || vehicle.isNotBlank()) {
            HubDriverStrip(driver.ifBlank { str(S.desktop_hub_driver_not_assigned) }, vehicle)
            Spacer(Modifier.height(8.dp))
        }
        Notes(trip.notes)
        CardFooter(
            onView = { onEvent(HubEvent.OpenTrip(trip.id)) },
            finished = finishedNote(trip.status.wire, trip.finishedMs),
        )
    }
}

/** A standing allocation in a passenger's feed: read-only, the coordinator owns it. */
@Composable
private fun AllocationCard(trip: Trip, state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val p = hub
    HubCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HubText(
                str(S.desktop_hub_allocation_number, trip.id.takeLast(SHORT_ID)),
                17.sp,
                p.textPrimary,
                weight = FontWeight.Bold,
            )
            HubText(
                trip.status.wire.replace('_', ' ').uppercase(),
                11.sp,
                p.accentDark,
                weight = FontWeight.Bold,
                spacing = 0.4.sp,
                modifier = Modifier.clip(CircleShape).background(p.accentBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            val range = listOf(trip.startDateMs.hubDate(), trip.endDateMs.hubDate())
                .filter { it.isNotBlank() }
                .joinToString(" – ")
            if (range.isNotBlank()) HubWhenChip(range, HubGlyphs.Calendar)
        }
        HubRouteBlock(trip.pickup, trip.dropoff)
        val days = trip.activeDays.describe()
        val hours = listOf(trip.startTime, trip.endTime).filter { it.isNotBlank() }.joinToString(" – ")
        if (days.isNotBlank() || hours.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    .clip(RoundedCornerShape(8.dp)).background(p.surfaceAlt)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (days.isNotBlank()) HubMeta(HubGlyphs.Calendar, days, 12.sp)
                if (hours.isNotBlank()) HubMeta(HubGlyphs.Clock, hours, 12.sp)
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            state.driverLabel(trip.driverId, trip.driverName).takeIf { it.isNotBlank() }
                ?.let { HubMeta(HubGlyphs.User, it, 12.sp) }
            state.vehicleLabelOrId(trip.vehicleId, trip.vehicleLabel).takeIf { it.isNotBlank() }
                ?.let { HubMeta(HubGlyphs.Car, it, 12.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            HubButton(
                text = str(S.view_details),
                onClick = { onEvent(HubEvent.OpenTrip(trip.id)) },
                style = HubButtonStyle.Outline,
            )
        }
    }
}

/* ── Passenger and driver → Ride Requests ──────────────────────────────── */

/**
 * A passenger's Ride Requests, and a driver's own — the web's
 * `passenger/MyRides.jsx`, which serves both.
 *
 * `GET /ride-request?me=true`, and the one place either of them raises a ride
 * for themselves: Call a Driver sends `call_type: self` with the caller as
 * the first passenger.
 */
@Composable
internal fun SelfRidesPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val shown = state.selfRides
    HubPage(
        title = str(S.desktop_hub_my_rides),
        icon = HubGlyphs.Car,
        subtitle = str(S.desktop_hub_active_recent, state.rides.size.toString()),
        actions = {
            HubButton(
                text = str(S.desktop_map_call_a_driver),
                onClick = { onEvent(HubEvent.CallADriver) },
                style = HubButtonStyle.Cta,
                icon = HubGlyphs.Plus,
                height = 36.dp,
                fontSize = 13.sp,
            )
        },
    ) {
        if (state.rides.isNotEmpty()) {
            HubFilterBar(
                filters = state.rideFilters,
                onChange = { onEvent(HubEvent.RideFiltersChanged(it)) },
                statusOptions = RIDE_STATUS_OPTIONS.map { (id, label) -> HubSegment(id, label()) },
                total = state.rides.size,
                shown = shown.size,
                expanded = state.filtersExpanded,
                onToggleExpanded = { onEvent(HubEvent.ToggleFiltersExpanded) },
            )
        }
        when {
            state.rides.isEmpty() -> HubEmpty(
                str(S.desktop_hub_no_rides_yet_call),
                emphasis = str(S.desktop_map_call_a_driver),
            )

            shown.isEmpty() -> HubEmpty(str(S.desktop_hub_no_rides_match))
            else -> ZillitLazyVerticalGrid(
                columns = GridCells.Adaptive(PASSENGER_CARD_MIN),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.id }) { ride -> SelfRideCard(ride, state, onEvent) }
            }
        }
    }
}

@Composable
private fun SelfRideCard(ride: RideRequest, state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val busy = state.busyRowId == ride.id
    HubCard {
        CardHeader(
            title = str(S.desktop_hub_ride_number, ride.id.takeLast(SHORT_ID)),
            status = ride.status.wire,
            priority = ride.priority.wire,
            whenText = ride.scheduledPickupTimeMs?.hubDateTime(),
            waiting = ride.status == RideStatus.Pending,
        )
        HubRouteBlock(ride.pickup, ride.dropoff)
        Spacer(Modifier.height(16.dp))
        MetaRow(ride.passengerCount, ride.department, ride.createdMs)
        val driver = state.driverLabel(ride.assignedDriverId, ride.assignedDriverName)
        val vehicle = state.vehicleLabelOrId(ride.assignedVehicleId, ride.assignedVehicleLabel)
        if (driver.isNotBlank() || vehicle.isNotBlank()) {
            HubDriverStrip(driver.ifBlank { str(S.desktop_hub_driver_not_assigned) }, vehicle)
            Spacer(Modifier.height(8.dp))
        }
        Notes(ride.notes)
        HubDivider()
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            HubButton(
                text = str(S.view_details),
                onClick = { onEvent(HubEvent.OpenRide(ride.id)) },
                style = HubButtonStyle.Outline,
                icon = HubGlyphs.MapTrifold,
            )
            // The server refuses an edit once a coordinator has acted on it.
            if (ride.status == RideStatus.Pending) {
                HubButton(
                    text = str(S.edit),
                    onClick = { onEvent(HubEvent.EditRide(ride)) },
                    style = HubButtonStyle.Outline,
                    icon = HubGlyphs.Pencil,
                )
            }
            // Cancellable any time before the car has actually set off.
            if (ride.status in CANCELLABLE) {
                HubButton(
                    text = str(S.cancel),
                    onClick = { onEvent(HubEvent.CancelRide(ride)) },
                    style = HubButtonStyle.Danger,
                    icon = HubGlyphs.Trash,
                    enabled = !busy,
                )
            }
            if (ride.status in TRACKABLE) {
                HubButton(
                    text = str(S.track_driver),
                    onClick = { onEvent(HubEvent.TrackRideDriver(ride)) },
                    style = HubButtonStyle.Vivid,
                    icon = HubGlyphs.PaperPlane,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/* ── Driver → My Trips ─────────────────────────────────────────────────── */

/**
 * Driver → My Trips — the web's `driver/MyTrips.jsx`, an **action** page.
 *
 * Only what the driver can move is shown, each card carrying its next step;
 * shuttle runs are split off into their own segment and folded one card to a
 * route, because a single route generates dozens a day.
 */
@Composable
internal fun DriverTripsPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val rides = state.driverRides
    val groups = state.driverShuttleGroups
    val subtitle = listOfNotNull(
        rides.size.takeIf { it > 0 }?.let { tripCount(it) },
        groups.size.takeIf { it > 0 }?.let {
            str(S.desktop_hub_n_shuttle_routes, it.toString(), state.driverShuttleRunCount.toString())
        },
    ).joinToString(" · ").ifBlank { str(S.desktop_hub_nothing_active) }
    HubPage(title = str(S.desktop_hub_my_trips), icon = HubGlyphs.SteeringWheel, subtitle = subtitle) {
        SegmentCard {
            HubSegmented(
                options = listOf(
                    HubSegment(DriverTripView.Rides.name, countLabel(str(S.rides), rides.size)),
                    HubSegment(DriverTripView.Routes.name, countLabel(str(S.desktop_hub_shuttle_n), groups.size)),
                ),
                selected = state.driverTripView.name,
                onSelect = { onEvent(HubEvent.DriverTripViewChanged(DriverTripView.valueOf(it))) },
            )
        }
        when (state.driverTripView) {
            DriverTripView.Routes -> if (groups.isEmpty()) {
                HubEmpty(str(S.desktop_hub_no_shuttle_assigned))
            } else {
                DriverGrid {
                    items(groups, key = { it.first.id }) { (head, count) ->
                        DriverCard(
                            trip = head,
                            ride = null,
                            busy = state.busyRowId == head.id,
                            moreRuns = count - 1,
                            onEvent = onEvent,
                        )
                    }
                }
            }

            DriverTripView.Rides -> if (rides.isEmpty()) {
                HubEmpty(
                    if (state.myDriverId == null) {
                        str(S.desktop_hub_no_driver_record)
                    } else {
                        str(S.desktop_hub_no_active_assigned)
                    },
                )
            } else {
                DriverGrid {
                    items(rides, key = { it.id }) { trip ->
                        DriverCard(trip, null, state.busyRowId == trip.id, 0, onEvent)
                    }
                }
            }
        }
    }
}

/* ── Driver → Assigned Rides ───────────────────────────────────────────── */

/**
 * Driver → Assigned Rides — the web's `driver/Rides.jsx`.
 *
 * The rides given to this driver, split Active / Past / Rejected with a count
 * on each, and the same next-step button the driver's trips carry.
 */
@Composable
internal fun DriverRidesPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val counts = state.driverRideCounts
    val active = counts[DriverRideTab.Active] ?: 0
    val past = counts[DriverRideTab.Past] ?: 0
    val shown = state.driverAssignedRides.filter { state.driverRideTab.accepts(it) }
    HubPage(
        title = str(S.desktop_hub_my_rides),
        icon = HubGlyphs.SteeringWheel,
        subtitle = if (past > 0) {
            str(S.desktop_hub_active_past, active.toString(), past.toString())
        } else {
            str(S.desktop_hub_n_active, active.toString())
        },
    ) {
        SegmentCard {
            HubSegmented(
                options = listOf(
                    HubSegment(DriverRideTab.Active.name, str(S.active), active),
                    HubSegment(DriverRideTab.Past.name, str(S.desktop_bs_past), past),
                    HubSegment(DriverRideTab.Rejected.name, str(S.rejected), counts[DriverRideTab.Rejected]),
                ),
                selected = state.driverRideTab.name,
                onSelect = { onEvent(HubEvent.DriverRideTabChanged(DriverRideTab.valueOf(it))) },
            )
        }
        if (shown.isEmpty()) {
            HubEmpty(
                when (state.driverRideTab) {
                    DriverRideTab.Active -> if (state.myDriverId == null) {
                        str(S.desktop_hub_no_driver_record)
                    } else {
                        str(S.desktop_hub_no_active_rides_assigned)
                    }

                    DriverRideTab.Rejected -> str(S.desktop_hub_no_rejected_rides)
                    DriverRideTab.Past -> str(S.desktop_hub_nothing_completed)
                },
            )
        } else {
            DriverGrid {
                items(shown, key = { it.id }) { ride ->
                    DriverCard(null, ride, state.busyRowId == ride.id, 0, onEvent)
                }
            }
        }
    }
}

/**
 * One card of a driver's — a trip, a shuttle run, or an assigned ride.
 *
 * The rider's face and number on the left, the pickup time boxed on the right
 * (the thing a driver reads first), the route, and the next step as a pill.
 */
@Composable
private fun DriverCard(
    trip: Trip?,
    ride: RideRequest?,
    busy: Boolean,
    moreRuns: Int,
    onEvent: (HubEvent) -> Unit,
) {
    val card = trip?.let(DriverCardModel::of) ?: ride?.let(DriverCardModel::of) ?: return
    val p = hub
    HubCard(padding = 16.dp, radius = 12.dp, modifier = Modifier.fillMaxWidth()) {
        DriverCardHeader(card)
        DriverCardTags(card, moreRuns)
        HubRouteBlock(card.pickup, card.dropoff, large = false)
        if (card.notes.isNotBlank()) {
            HubText(
                card.notes,
                12.sp,
                p.textSecondary,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
                    .background(VIVID_TINT).border(1.dp, VIVID_TINT_BORDER, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        HubDivider(dashed = true)
        DriverCardFooter(trip, ride, busy, onEvent)
    }
}

/**
 * What a driver's card shows, resolved once from whichever it was given — a
 * trip (or shuttle run) or an assigned ride — so the card itself never has to
 * ask which.
 */
private data class DriverCardModel(
    val status: String,
    val name: String,
    val heads: Int,
    val phone: String,
    val atMs: Long,
    val priority: String,
    val riderUserId: String?,
    /** Set for a shuttle run: its route type, which picks the tile's tint. */
    val shuttleRouteType: String?,
    val isRideRequest: Boolean,
    val pickup: HubLocation,
    val dropoff: HubLocation,
    val notes: String,
) {
    companion object {
        fun of(trip: Trip) = DriverCardModel(
            status = trip.status.wire,
            name = trip.riderName,
            heads = trip.headCount,
            phone = trip.requesterPhone,
            atMs = trip.scheduledPickupTimeMs ?: trip.createdMs,
            priority = trip.priority.wire,
            riderUserId = trip.requesterUserId,
            shuttleRouteType = trip.takeIf { it.isShuttleRun }?.routeType,
            isRideRequest = trip.kind == TripResourceKind.RideRequest,
            pickup = trip.pickup,
            dropoff = trip.dropoff,
            notes = trip.notes,
        )

        fun of(ride: RideRequest) = DriverCardModel(
            status = ride.status.wire,
            name = ride.passengers.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: ride.requesterName,
            heads = ride.passengerCount,
            phone = ride.requesterPhone,
            atMs = ride.scheduledPickupTimeMs ?: 0L,
            priority = ride.priority.wire,
            riderUserId = ride.requesterUserId,
            shuttleRouteType = null,
            isRideRequest = true,
            pickup = ride.pickup,
            dropoff = ride.dropoff,
            notes = ride.notes,
        )
    }
}

/** The rider's face (or the route's tile), name, head count and number; the pickup time boxed right. */
@Composable
private fun DriverCardHeader(card: DriverCardModel) {
    val p = hub
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                if (card.shuttleRouteType != null) {
                    val tint = routeTint(card.shuttleRouteType)
                    HubIconTile(
                        HubGlyphs.Path,
                        background = tint.copy(alpha = 0.12f),
                        tint = tint,
                        size = 44.dp,
                        radius = 12.dp,
                        iconSize = 22.dp,
                        border = tint.copy(alpha = 0.4f),
                    )
                } else {
                    ZillitAvatar(name = card.name.ifBlank { "?" }, userId = card.riderUserId, size = 44.dp)
                }
                if (card.status == "completed") {
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape)
                            .background(Color(0xFF10B981)).border(2.dp, p.surface, CircleShape),
                        Alignment.Center,
                    ) { ZillitIcon(HubGlyphs.Check, tint = Color.White, size = 12.dp) }
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HubText(card.name.ifBlank { "—" }, 14.sp, p.textPrimary, weight = FontWeight.Bold)
                    if (card.heads > 1) {
                        HubText(
                            "+${card.heads - 1}",
                            10.sp,
                            p.textSecondary,
                            weight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp).clip(CircleShape)
                                .background(p.surfaceAlt).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                if (card.phone.isNotBlank()) HubText(card.phone, 11.sp, p.textMuted)
            }
        }
        if (card.atMs > 0) PickupBox(card.atMs)
    }
}

/** Status, an urgent/high flag, and what kind of job it is. */
@Composable
private fun DriverCardTags(card: DriverCardModel, moreRuns: Int) {
    val p = hub
    Row(
        modifier = Modifier.padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (fg, bg) = antTag(card.status)
        HubTag(card.status.replace('_', ' ').replaceFirstChar { it.uppercase() }, fg, bg)
        if (card.priority == "urgent" || card.priority == "high") {
            val (pfg, pbg) = if (card.priority == "urgent") RED_TAG else ORANGE_TAG
            HubTag(card.priority.uppercase(), pfg, pbg, bold = true)
        }
        if (card.isRideRequest) HubTag(str(S.desktop_hub_ride_request_tag), p.textSecondary, p.surfaceAlt)
        if (card.shuttleRouteType != null) HubTag(str(S.desktop_hub_shuttle_n), p.textSecondary, p.surfaceAlt)
        if (moreRuns > 0) {
            HubTag(str(S.desktop_hub_more_runs, moreRuns.toString()), p.accentDark, p.accentBg, bold = true)
        }
    }
}

/** Details (a ride) or View detail (a trip) on the left; the next step as a pill on the right. */
@Composable
private fun DriverCardFooter(trip: Trip?, ride: RideRequest?, busy: Boolean, onEvent: (HubEvent) -> Unit) {
    val step = trip?.let { DriverStep.forTrip(it) } ?: ride?.let { DriverStep.forRide(it) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (ride != null) {
            HubButton(
                text = str(S.details),
                onClick = { onEvent(HubEvent.OpenRide(ride.id)) },
                style = HubButtonStyle.Outline,
                icon = HubGlyphs.MapTrifold,
                pill = true,
                height = 38.dp,
                fontSize = 13.sp,
            )
        } else if (trip != null) {
            HubButton(
                text = str(S.desktop_hub_view_detail_arrow),
                onClick = { onEvent(HubEvent.OpenTrip(trip.id)) },
                style = HubButtonStyle.Link,
                fontSize = 12.sp,
                height = 38.dp,
            )
        }
        step?.let {
            HubButton(
                text = it.label(),
                onClick = {
                    when {
                        trip != null -> onEvent(HubEvent.AdvanceTrip(trip))
                        ride != null -> onEvent(HubEvent.AdvanceRide(ride))
                    }
                },
                icon = HubGlyphs.ArrowRight,
                pill = true,
                height = 38.dp,
                fontSize = 13.sp,
                loading = busy,
            )
        }
    }
}

/** The boxed pickup time a driver's card wears top-right. */
@Composable
private fun PickupBox(atMs: Long) {
    val p = hub
    Column(
        modifier = Modifier
            .widthIn(min = 62.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(VIVID_TINT)
            .border(1.dp, VIVID_TINT_BORDER, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitIcon(HubGlyphs.Clock, tint = p.accentDark, size = 10.dp)
            HubText(str(S.pickup).uppercase(), 9.sp, p.accentDark, weight = FontWeight.Bold, spacing = 0.5.sp)
        }
        HubText(atMs.hubTime(), 15.sp, p.textPrimary, weight = FontWeight.Bold)
        HubText(atMs.hubDate(), 10.sp, p.textMuted)
    }
}

/* ── Shared pieces ─────────────────────────────────────────────────────── */

/**
 * "Ride #ea128e  [ASSIGNED] [NORMAL]  [🕒 Fri, 19 Jun 2026, 15:51]".
 *
 * It wraps, as the web's `flex-wrap` header does: on a card two to a row the
 * date chip drops to its own line rather than being cut short.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardHeader(
    title: String,
    status: String,
    priority: String,
    whenText: String?,
    waiting: Boolean = false,
) {
    val p = hub
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HubText(
            title,
            17.sp,
            p.textPrimary,
            weight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        Box(Modifier.align(Alignment.CenterVertically)) { HubStatusPill(status) }
        Box(Modifier.align(Alignment.CenterVertically)) { HubPriorityPill(priority) }
        whenText?.takeIf { it.isNotBlank() }?.let { HubWhenChip(it) }
        if (waiting) {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(rgb(0xFEF9ED))
                    .border(1.dp, rgb(0xFCD34D), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(HubGlyphs.Clock, tint = rgb(0xB45309), size = 12.dp)
                HubText(str(S.desktop_hub_waiting_approval), 12.sp, rgb(0xB45309), weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MetaRow(people: Int, department: String, createdMs: Long) {
    Row(
        modifier = Modifier.padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HubMeta(HubGlyphs.Users, peopleCount(people))
        if (department.isNotBlank()) HubMeta(null, department.removeSuffix("_label").replace('_', ' '))
        createdMs.hubDateTime().takeIf { it.isNotBlank() }?.let { HubMeta(HubGlyphs.Calendar, it) }
    }
}

@Composable
private fun Notes(notes: String) {
    if (notes.isBlank()) return
    HubText(
        notes,
        11.sp,
        hub.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(6.dp))
            .background(VIVID_TINT).border(1.dp, VIVID_TINT_BORDER, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun CardFooter(onView: () -> Unit, finished: String?) {
    val p = hub
    HubDivider()
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        HubButton(
            text = str(S.view_details),
            onClick = onView,
            style = HubButtonStyle.Outline,
            icon = HubGlyphs.MapTrifold,
        )
        Spacer(Modifier.weight(1f))
        finished?.let { HubText(it, 12.sp, p.textMuted) }
    }
}

/** The small white card a driver's segment control sits in. */
@Composable
private fun SegmentCard(content: @Composable () -> Unit) {
    val p = hub
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface)
            .border(1.dp, p.border, RoundedCornerShape(12.dp)).padding(12.dp),
    ) { content() }
}

/** The driver's card grid: as many ~400dp columns as fit, as the web's `auto-fill`. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DriverGrid(
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    ZillitLazyVerticalGrid(
        columns = GridCells.Adaptive(DRIVER_CARD_MIN),
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** "Completed · Fri, 19 Jun 2026, 15:38" — only for a ride that has stopped. */
private fun finishedNote(status: String, finishedMs: Long): String? {
    if (finishedMs <= 0) return null
    val label = when (status) {
        "completed" -> str(S.completed)
        "cancelled" -> str(S.cancelled)
        "rejected" -> str(S.rejected)
        else -> return null
    }
    return str(S.docusign_bulk_meta, label, finishedMs.hubDateTime())
}

private fun tripCount(n: Int): String =
    if (n == 1) str(S.desktop_hub_one_trip) else str(S.desktop_hub_trip_count, n.toString())

internal fun peopleCount(n: Int): String =
    if (n == 1) str(S.desktop_tasks_people_count_one) else str(S.desktop_tasks_people_count, n.toString())

private fun countLabel(label: String, n: Int): String = if (n > 0) "$label ($n)" else label

/** antd's borderless `<Tag color=…>` as the driver cards colour a status. */
private fun antTag(status: String): Pair<Color, Color> = when (status) {
    "pending" -> rgb(0xD48806) to rgb(0xFFFBE6)
    "approved" -> rgb(0x1677FF) to rgb(0xE6F4FF)
    "assigned" -> rgb(0x0958D9) to rgb(0xE6F4FF)
    "en_route_pickup", "scheduled" -> rgb(0xD48806) to rgb(0xFFFBE6)
    "at_pickup", "boarding" -> rgb(0xD46B08) to rgb(0xFFF7E6)
    "in_transit" -> rgb(0x1D39C4) to rgb(0xF0F5FF)
    "at_dropoff", "departed" -> rgb(0x531DAB) to rgb(0xF9F0FF)
    "completed" -> rgb(0x389E0D) to rgb(0xF6FFED)
    "cancelled", "rejected" -> rgb(0xCF1322) to rgb(0xFFF1F0)
    else -> rgb(0x595959) to rgb(0xFAFAFA)
}

/** A shuttle route's tint, by its type — the coordinator's Shuttles palette. */
private fun routeTint(type: String): Color = when (type.lowercase()) {
    "crew" -> rgb(0x4F46E5)
    "cast" -> rgb(0xBE185D)
    "vip" -> rgb(0x7C3AED)
    "background" -> rgb(0x0F766E)
    else -> rgb(0xD97706)
}

private val RED_TAG = rgb(0xCF1322) to rgb(0xFFF1F0)
private val ORANGE_TAG = rgb(0xD46B08) to rgb(0xFFF7E6)
private val VIVID_TINT = Color(0x14FC9404)
private val VIVID_TINT_BORDER = Color(0x40FC9404)

private val CANCELLABLE = setOf(RideStatus.Pending, RideStatus.Approved, RideStatus.Assigned)
private val TRACKABLE = setOf(RideStatus.Assigned, RideStatus.InTransit)

/** A passenger's My Trips chips — the unified feed's live and past statuses. */
private val TRIP_STATUS_OPTIONS: List<Pair<String, () -> String>> = listOf(
    "all" to { str(S.all) },
    TripStatus.Pending.wire to { str(S.pending) },
    TripStatus.Approved.wire to { str(S.approved) },
    TripStatus.Assigned.wire to { str(S.assigned) },
    TripStatus.InTransit.wire to { str(S.desktop_card_in_transit) },
    TripStatus.Active.wire to { str(S.desktop_hub_active_permanent) },
    TripStatus.Completed.wire to { str(S.completed) },
    TripStatus.Cancelled.wire to { str(S.cancelled) },
    TripStatus.Rejected.wire to { str(S.rejected) },
)

/** A ride list's chips — no permanent or rejected, as the web's own. */
private val RIDE_STATUS_OPTIONS: List<Pair<String, () -> String>> = listOf(
    "all" to { str(S.all) },
    RideStatus.Pending.wire to { str(S.pending) },
    RideStatus.Approved.wire to { str(S.approved) },
    RideStatus.Assigned.wire to { str(S.assigned) },
    RideStatus.InTransit.wire to { str(S.desktop_card_in_transit) },
    RideStatus.Completed.wire to { str(S.completed) },
    RideStatus.Cancelled.wire to { str(S.cancelled) },
)

private val PASSENGER_CARD_MIN = 520.dp
private val DRIVER_CARD_MIN = 400.dp
private const val SHORT_ID = 6
