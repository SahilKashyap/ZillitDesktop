package com.zillit.desktop.feature.maps.domain

/**
 * Where a pin lands relative to the city and the active studio zone —
 * `utils/boundaryGeometry.js`, MAP_PIN_BOUNDARY_CONFIRMATION_SPEC §2.
 *
 * Pure and self-contained, because the same point must produce the same
 * verdict from every entry point (map tap, drag, info-window "Add Location",
 * place search) — the spec is explicit that a check which differs by route is
 * what makes the feature feel inconsistent.
 */
enum class BoundaryStatus { Inside, OutsideZone, OutsideCity }

data class BoundaryVerdict(
    val status: BoundaryStatus,
    val zoneName: String? = null,
    val cityName: String? = null,
)

/**
 * The verdict for [point].
 *
 * Three rules the spec insists on, all of which the older check got wrong:
 *
 *  1. the city is a RADIUS around its centre (`center_point`, else
 *     `coordinates`), not a geocoded box — and a radius of 0 means 30 miles;
 *  2. only the ACTIVE zone counts, not every zone;
 *  3. inside the active zone is Inside, full stop — even beyond the city
 *     radius, which happens whenever a zone straddles a city edge.
 *
 * Returns Inside whenever there is nothing to enforce: no point, no city, or a
 * city with no usable coordinates (spec §9). A boundary that cannot be
 * computed must never block a pin.
 */
fun evaluatePinBoundary(point: LatLng?, city: MapCity?, activeZone: MapLocation?): BoundaryVerdict {
    if (point == null || city == null) return BoundaryVerdict(BoundaryStatus.Inside)
    val cityName = city.name.ifBlank { "the city" }
    val zoneName = activeZone?.name?.takeIf { it.isNotBlank() } ?: "the studio zone"

    // Zone first, and it short-circuits: a zone drawn at a city's edge
    // legitimately covers ground the city circle does not.
    val zoneCentre = activeZone?.point
    if (activeZone != null && zoneCentre != null &&
        Geo.within(point, zoneCentre, radiusOrDefault(activeZone.miles))
    ) {
        return BoundaryVerdict(BoundaryStatus.Inside)
    }

    val centre = city.centerPoint ?: city.coordinates ?: return BoundaryVerdict(BoundaryStatus.Inside)
    val insideCity = Geo.within(point, centre, radiusOrDefault(city.radiusMiles))
    return when {
        // Only a zone that is actually active can be "outside the zone".
        insideCity && activeZone != null && zoneCentre != null ->
            BoundaryVerdict(BoundaryStatus.OutsideZone, zoneName = zoneName, cityName = cityName)
        insideCity -> BoundaryVerdict(BoundaryStatus.Inside)
        else -> BoundaryVerdict(BoundaryStatus.OutsideCity, cityName = cityName)
    }
}

/** 0 / missing means 30 miles, NOT "no boundary" (spec §2.2). */
private fun radiusOrDefault(miles: Double): Double = miles.takeIf { it > 0 } ?: DEFAULT_ZONE_MILES

/** Adding a new pin, or moving an existing one — the two ask differently. */
enum class BoundaryMode { Add, Move }

/** What the person chose. Dismissing the dialog is [Cancel] (spec §3.J). */
enum class BoundaryChoice { Confirm, CreateCity, Cancel }

/** How a choice is drawn: the expected one, the third way out, or abort. */
enum class BoundaryActionKind { Primary, Neutral, Cancel }

data class BoundaryAction(val choice: BoundaryChoice, val label: String, val kind: BoundaryActionKind)

/**
 * The question to put, and the buttons to put it with.
 *
 * [address] is the point of the dialog: "outside the zone" means nothing
 * without saying where the pin actually landed.
 */
data class BoundaryPrompt(
    val title: String,
    val message: String,
    val address: String?,
    val actions: List<BoundaryAction>,
) {
    /**
     * Three long labels stack one per line — they name outcomes, and a
     * truncated outcome defeats the reason for the wording.
     */
    val stacked: Boolean get() = actions.size > 2
}

/**
 * `utils/boundaryPrompt.js` — what to ask when a pin lands outside, or null
 * when nothing should be asked.
 *
 * The copy is exact-match across Android, iOS and the web (spec §3.J); do not
 * reword it here. The outside-city labels are the client email's wording,
 * which the web ships and Android does not.
 */
fun buildBoundaryPrompt(
    status: BoundaryStatus,
    mode: BoundaryMode,
    cityName: String? = null,
    zoneName: String? = null,
    address: String? = null,
    name: String? = null,
): BoundaryPrompt? {
    val city = cityName?.takeIf { it.isNotBlank() } ?: "the selected city"
    val zone = zoneName?.takeIf { it.isNotBlank() } ?: "studio zone"
    val verb = if (mode == BoundaryMode.Move) "move" else "pin"
    val where = address?.takeIf { it.isNotBlank() }
    val cancel = BoundaryAction(BoundaryChoice.Cancel, "Cancel", BoundaryActionKind.Cancel)
    val move = BoundaryAction(BoundaryChoice.Confirm, "Move", BoundaryActionKind.Primary)

    return when (status) {
        // Adding inside every boundary is silent; a move always confirms,
        // because a drag can be an accident.
        BoundaryStatus.Inside -> if (mode == BoundaryMode.Move) {
            BoundaryPrompt(
                title = "Move Location",
                message = "Move '${name?.takeIf { it.isNotBlank() } ?: "this location"}' to the new position?",
                address = where,
                actions = listOf(cancel, move),
            )
        } else {
            null
        }

        BoundaryStatus.OutsideZone -> BoundaryPrompt(
            title = "Outside Studio Zone",
            message = "Location is outside the $zone. Do you want to $verb the location?",
            address = where,
            actions = listOf(
                BoundaryAction(BoundaryChoice.Cancel, "No", BoundaryActionKind.Cancel),
                BoundaryAction(BoundaryChoice.Confirm, "Yes", BoundaryActionKind.Primary),
            ),
        )

        // Spec §5.5: a moved pin keeps its city, so no "Create Another City".
        BoundaryStatus.OutsideCity -> if (mode == BoundaryMode.Move) {
            BoundaryPrompt(
                title = "Outside $city",
                message = "The new position is outside $city and any studio zone. Move the pin anyway?",
                address = where,
                actions = listOf(cancel, move),
            )
        } else {
            BoundaryPrompt(
                title = "Outside $city",
                message = "This location is outside the selected city and studio zone. What would you like to do?",
                address = where,
                actions = listOf(
                    BoundaryAction(
                        BoundaryChoice.CreateCity,
                        "Create Another City & Pin Location",
                        BoundaryActionKind.Primary,
                    ),
                    BoundaryAction(BoundaryChoice.Confirm, "Pin Location in the Same City", BoundaryActionKind.Neutral),
                    cancel,
                ),
            )
        }
    }
}
