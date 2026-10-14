package com.zillit.desktop.feature.transporthub.domain

/**
 * Shuttles: the standing routes, and the runs generated from them.
 *
 * Two route prefixes, and mixing them is the mistake this file exists to
 * prevent: a **route** lives under `/shuttle/...` (singular) and a **run**
 * under `/shuttles/...` (plural). The web's own `api.js` keeps both, and the
 * run routes answer 404 under the singular spelling.
 *
 * A route carries its schedule in two shapes depending on the deployment —
 * flat (`frequency`, `start_time`) and nested (`schedule.frequency`) — so the
 * parser reads both and the writer always sends the nested one, which is what
 * the validator takes.
 */

/** What a route is for — the server's `type` enum. */
enum class ShuttleRouteType(val wire: String) {
    Crew("crew"),
    Cast("cast"),
    Vip("vip"),
    Background("background"),
    Other("other"),
    ;

    companion object {
        fun from(value: String?): ShuttleRouteType {
            val raw = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == raw } ?: Other
        }
    }
}

/** One stop on a route. `estimatedTime` is `HH:mm`, or blank when unset. */
data class ShuttleStop(
    val name: String = "",
    val location: HubLocation = HubLocation.Empty,
    val order: Int = 0,
    val estimatedTime: String = "",
)

/** A standing shuttle route. */
data class ShuttleRoute(
    val id: String,
    val name: String = "",
    val type: ShuttleRouteType = ShuttleRouteType.Crew,
    val capacity: Int? = null,
    val notes: String = "",
    /** Minutes between departures. */
    val frequencyMinutes: Int? = null,
    /** `HH:mm`. Blank when the deployment ships no schedule block. */
    val startTime: String = "",
    val endTime: String = "",
    val daysActive: List<HubDay> = emptyList(),
    val stops: List<ShuttleStop> = emptyList(),
    val driverId: String? = null,
    val driverName: String = "",
    val vehicleId: String? = null,
    val vehicleLabel: String = "",
    /**
     * Whether the route is live.
     *
     * The create and update routes spell this `isActive` — camelCase inside an
     * otherwise snake_case body — while the list answers `is_active`. Absent
     * means active: only an explicit false disables a route.
     */
    val isActive: Boolean = true,
) {
    val displayName: String get() = name.ifBlank { "—" }

    /**
     * True when runs can be generated.
     *
     * Both a driver and a vehicle are required: without them the server spawns
     * runs with nobody to drive them, which cannot be acted on afterwards.
     */
    val canGenerateRuns: Boolean
        get() = !driverId.isNullOrBlank() && !vehicleId.isNullOrBlank()

    val timeWindow: String
        get() = when {
            startTime.isNotBlank() && endTime.isNotBlank() -> "$startTime–$endTime"
            else -> startTime.ifBlank { endTime }
        }
}

/** A route being created or edited. */
data class ShuttleRouteDraft(
    val name: String,
    val type: ShuttleRouteType,
    val capacity: Int?,
    val notes: String = "",
    val frequencyMinutes: Int?,
    val startTime: String = "",
    val endTime: String = "",
    val daysActive: List<HubDay> = emptyList(),
    val stops: List<ShuttleStop> = emptyList(),
    val driverId: String? = null,
    val vehicleId: String? = null,
)

/**
 * A generated run's status.
 *
 * [Departed] is the server's own transition between boarding and in-transit —
 * a coordinator never drives it, but a row still has to be able to wear it.
 * Kebab and snake spellings both arrive, so parsing folds them together.
 */
enum class ShuttleRunStatus(val wire: String) {
    Scheduled("scheduled"),
    Boarding("boarding"),
    Departed("departed"),
    InTransit("in_transit"),
    Completed("completed"),
    Cancelled("cancelled"),
    ;

    val isTerminal: Boolean get() = this == Completed || this == Cancelled

    /** The statuses a coordinator may move this one to, in the web's order. */
    val transitions: List<ShuttleRunStatus>
        get() = when (this) {
            Scheduled -> listOf(Boarding, Cancelled)
            Boarding -> listOf(InTransit)
            InTransit -> listOf(Completed)
            Departed, Completed, Cancelled -> emptyList()
        }

    companion object {
        fun from(value: String?): ShuttleRunStatus {
            val raw = value?.trim()?.lowercase()?.replace('-', '_').orEmpty()
            return entries.firstOrNull { it.wire == raw } ?: Scheduled
        }
    }
}

/** One run of a route on one day. */
data class ShuttleRun(
    val id: String,
    val routeId: String? = null,
    val routeName: String = "",
    val routeType: ShuttleRouteType? = null,
    val status: ShuttleRunStatus = ShuttleRunStatus.Scheduled,
    /** Epoch ms. The run's date is as much a part of its identity as its time. */
    val departureMs: Long = 0,
    val driverName: String = "",
    val vehicleLabel: String = "",
    val occupancy: Int = 0,
    val capacity: Int = 0,
) {
    val title: String get() = routeName.ifBlank { "—" }
}
