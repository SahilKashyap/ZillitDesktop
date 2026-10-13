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

    Allocations(unit = HubBadgeUnits.ALLOCATIONS),

    ActionVehicles(unit = HubBadgeUnits.ACTION_VEHICLE),
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

    /** The module every hub notification belongs to. */
    const val MODULE = "transportation_label"
}
