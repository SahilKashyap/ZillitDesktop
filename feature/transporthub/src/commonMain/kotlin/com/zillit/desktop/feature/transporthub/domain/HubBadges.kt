package com.zillit.desktop.feature.transporthub.domain

/**
 * The hub's badge buckets.
 *
 * The server emits one `unit` per notification, and two of its units cover
 * several hub pages at once: `transportation_trip_label` carries trips,
 * shuttles and templates, and `transportation_fleet_label` carries vehicles,
 * drivers and maintenance. Shown unsplit, the same number appears beside
 * three rail items and the rail's total comes out larger than the Film Tools
 * tile's — so each bucket names the `action` values that are really its own.
 *
 * A bucket with no [actions] owns its whole unit. That is deliberate for
 * [MyTrips]: a driver or a passenger only has one trips page, so every
 * trip-unit notification they receive belongs to it.
 *
 * These sets track the server's notification spec. An action the server adds
 * and this list has not met yet is counted in no bucket — which loses a dot,
 * never a row: the ledger still holds it, and the tile's own total still
 * includes it.
 */
enum class HubBadgeBucket(
    val unit: String,
    val actions: Set<String> = emptySet(),
    /** Actions that must not raise a dot even though they are in the unit. */
    val silentActions: Set<String> = emptySet(),
    /**
     * Further units this bucket owns whole — rows of a page the desktop has
     * not got, given the nearest page that has a rail item, so they count
     * somewhere and clear somewhere.
     */
    val alsoUnits: Set<String> = emptySet(),
    /**
     * Whether opening the bucket's section reads the bucket. False for a unit
     * whose rows clear only by a narrower read — see [ActionVehicles].
     */
    val readOnOpen: Boolean = true,
) {
    /**
     * Ride requests.
     *
     * The whole unit less the quiet actions: a coordinator editing a request,
     * adding a stop or adding a passenger changes a card that is already on
     * screen and refreshed by socket, so it should not also light the rail.
     */
    Rides(
        unit = HubBadgeUnits.RIDE,
        silentActions = setOf(
            "ride_request_updated",
            "ride_request_edited",
            "ride_request_stop_added",
            "ride_request_stop_completed",
            "ride_request_passenger_added",
            "ride_request_passenger_removed",
        ),
    ),

    Trips(
        unit = HubBadgeUnits.TRIP,
        actions = setOf(
            "trip_request_created",
            "trip_request_driver_assigned",
            "trip_request_transferred",
            "trip_request_en_route_pickup",
            "trip_request_at_pickup",
            "trip_request_in_transit",
            "trip_request_at_dropoff",
            "trip_request_completed",
            "trip_request_cancelled",
            "trip_passenger_added",
            "trip_request_pending_reminder",
            "trip_request_updated",
        ),
    ),

    Shuttles(
        unit = HubBadgeUnits.TRIP,
        actions = setOf(
            "shuttle_runs_generated",
            "shuttle_run_assigned",
            "shuttle_run_departed",
        ),
    ),

    Templates(
        unit = HubBadgeUnits.TRIP,
        actions = setOf("day_template_rides_assigned"),
    ),

    /** A driver's or a passenger's single trips page: the whole unit. */
    MyTrips(unit = HubBadgeUnits.TRIP),

    Fleet(
        unit = HubBadgeUnits.FLEET,
        actions = setOf("vehicle_created", "vehicle_updated", "vehicle_deleted"),
    ),

    Drivers(
        unit = HubBadgeUnits.FLEET,
        actions = setOf(
            "driver_license_request",
            "driver_license_approved",
            "driver_license_rejected",
            "driver_details_updated",
        ),
    ),

    Maintenance(
        unit = HubBadgeUnits.FLEET,
        actions = setOf(
            "maintenance_scheduled",
            "maintenance_overdue_alert",
            "maintenance_completed",
        ),
    ),

    /**
     * Permanent allocations, and the talent unit beside them.
     *
     * iOS rolls `transportation_talent_label` into the coordinator's More tab
     * next to Allocations (`ContentView.swift:504-509`) and the web reads it on
     * a Talent Profiles page its sidebar hides (`useHubBadges.js` `talent`).
     * The desktop has neither page, so without a home here those rows counted
     * on the Film Tools tile and never cleared. Allocations is the More item
     * the desktop does have, and talent pick-ups are standing allocations.
     */
    Allocations(unit = HubBadgeUnits.ALLOCATIONS, alsoUnits = setOf(HubBadgeUnits.TALENT)),

    /**
     * Picture vehicles: the whole unit is the rail's count (web `Layout.jsx`
     * "Badge = the whole-unit unread total"; iOS `actionVehicleCount`), but
     * it is never read whole. The unit spans six tabs and carries `action`
     * rows that must survive until the work is done, so iOS refuses a
     * unit-wide read of it (`TransportationBadgeReader.swift:96-99`) and reads
     * per tab, per vehicle and per thread instead ([HubBadgeRead]).
     */
    ActionVehicles(unit = HubBadgeUnits.ACTION_VEHICLE, readOnOpen = false),
    ;

    /** True when this bucket counts a row with [action] under its unit. */
    fun counts(action: String): Boolean = when {
        action in silentActions -> false
        actions.isEmpty() -> true
        else -> action in actions
    }
}

/** The badge units the hub's notifications arrive under. */
object HubBadgeUnits {
    const val RIDE = "transportation_ride_label"
    const val TRIP = "transportation_trip_label"
    const val FLEET = "transportation_fleet_label"
    const val ALLOCATIONS = "transportation_allocations_label"
    const val ACTION_VEHICLE = "transportation_action_vehicle_label"

    /** Talent pick-up profiles — no page of their own here; see [HubBadgeBucket.Allocations]. */
    const val TALENT = "transportation_talent_label"

    /** The module every hub notification belongs to. */
    const val MODULE = "transportation_label"
}

/**
 * The Action Vehicle unit's level vocabulary (iOS `ActionVehicleBadges.swift`
 * `AVBadgeKind`/`AVBadgeTab`, web `useActionVehicleBadges.js`): `level_1` is
 * the recipient's tab, `level_2` the kind of attention, `level_3` the vehicle.
 */
object AvBadgeLevels {
    /** Work owed — cleared by the backend once done, never by a view (but see [clearsActionOnView]). */
    const val ACTION = "action"

    /** Informational — cleared when the tab or the record is seen. */
    const val UPDATE = "update"

    /** A comment, or a request to comment — cleared when the thread is opened. */
    const val COMMENT = "comment"

    const val TAB_REQUESTED = "requested_vehicles"
    const val TAB_IDENTIFY = "identify"
    const val TAB_SENT_FOR_APPROVAL = "sent_for_approval"
    const val TAB_PENDING_APPROVAL = "pending_approval"
    const val TAB_FINALIZED = "finalized"
    const val TAB_COMMENTS = "comments"

    /**
     * ZL-21592: Finalized is the one tab whose `action` rows ("approved — send
     * to set") clear on view; everywhere else the action survives until the
     * work is done (`ActionVehicleBadges.swift:162-167`).
     */
    fun clearsActionOnView(level1: String): Boolean = level1 == TAB_FINALIZED
}

/** One vehicle's unread, by kind — the card's corner count and the thread's dot. */
data class AvVehicleBadge(
    val total: Int = 0,
    val action: Int = 0,
    val update: Int = 0,
    val comment: Int = 0,
)

/** One unread Action Vehicle row's levels, as the ledger holds them. */
data class AvBadgeRow(val level1: String, val level2: String, val level3: String)

/**
 * The Action Vehicle unit split the way its screen draws it — iOS
 * `ActionVehicleBadgeSnapshot` (`ActionVehicleBadges.swift:72-133`): unread
 * per tab (`level_1`) and per vehicle (`level_3`), the latter by kind.
 */
data class AvBadges(
    val tabs: Map<String, Int> = emptyMap(),
    val vehicles: Map<String, AvVehicleBadge> = emptyMap(),
) {
    fun tab(level1: String): Int = tabs[level1] ?: 0

    fun vehicle(id: String): AvVehicleBadge = vehicles[id] ?: AvVehicleBadge()

    companion object {
        val Empty = AvBadges()

        /** The unit's unread rows, bucketed; a row with no tab or no vehicle counts only where it can. */
        fun of(rows: List<AvBadgeRow>): AvBadges {
            val tabs = rows.filter { it.level1.isNotBlank() }.groupingBy { it.level1 }.eachCount()
            val vehicles = rows.filter { it.level3.isNotBlank() }.groupBy { it.level3 }
                .mapValues { (_, own) ->
                    AvVehicleBadge(
                        total = own.size,
                        action = own.count { it.level2 == AvBadgeLevels.ACTION },
                        update = own.count { it.level2 == AvBadgeLevels.UPDATE },
                        comment = own.count { it.level2 == AvBadgeLevels.COMMENT },
                    )
                }
            return AvBadges(tabs, vehicles)
        }
    }
}

/**
 * A read narrower than a rail bucket — one tab, one record, one thread.
 *
 * The host turns each into wire reads: [levelReads] for the Action Vehicle
 * unit, and a per-entity read for [TripRecord].
 */
sealed interface HubBadgeRead {

    /**
     * An Action Vehicles tab reached — by a pick, or by landing on it
     * (`ActionVehiclesView.swift:401-404, 805-824`): its `update` rows, and on
     * Finalized its `action` rows too.
     */
    data class ActionVehicleTab(val level1: String) : HubBadgeRead

    /** One vehicle's detail opened on a tab — the same two kinds, that vehicle only (408-413). */
    data class ActionVehicleRecord(val level1: String, val vehicleId: String) : HubBadgeRead

    /**
     * A vehicle's comment thread opened, or a comment landing while it is open
     * (192-195, 416-419): its `comment` rows on **every** tab — `level_1` is
     * left out so the copies routed to each recipient's tab all clear.
     */
    data class ActionVehicleComments(val vehicleId: String) : HubBadgeRead

    /**
     * One trip's detail opened — iOS `markEntityAsRead(tripId)`
     * (`TripDetailView.swift:220-225`): every hub row naming the trip as its
     * `reference_id` or its `level_3`, whatever the unit.
     */
    data class TripRecord(val tripId: String) : HubBadgeRead

    /** The Action Vehicle level reads this asks for; empty for any other read. */
    fun levelReads(): List<AvLevelRead> = when (this) {
        is ActionVehicleTab -> viewReads(level1, vehicleId = null)
        is ActionVehicleRecord -> viewReads(level1, vehicleId)
        is ActionVehicleComments ->
            if (vehicleId.isBlank()) emptyList() else listOf(AvLevelRead(null, AvBadgeLevels.COMMENT, vehicleId))
        is TripRecord -> emptyList()
    }

    private fun viewReads(level1: String, vehicleId: String?): List<AvLevelRead> {
        if (level1.isBlank() || vehicleId?.isBlank() == true) return emptyList()
        return listOfNotNull(
            AvLevelRead(level1, AvBadgeLevels.UPDATE, vehicleId),
            AvLevelRead(level1, AvBadgeLevels.ACTION, vehicleId)
                .takeIf { AvBadgeLevels.clearsActionOnView(level1) },
        )
    }
}

/** One `notification:level:read` on the Action Vehicle unit; a null level is left off the wire. */
data class AvLevelRead(val level1: String?, val level2: String, val level3: String?)
