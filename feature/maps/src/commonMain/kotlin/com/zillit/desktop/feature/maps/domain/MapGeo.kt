package com.zillit.desktop.feature.maps.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle arithmetic — the web's `computeDistanceBetween` and
 * `haversineDistance`, without Google. Pure, so every boundary decision can be
 * tested without a map.
 */
object Geo {
    /** The web's conversion everywhere a radius meets a distance. */
    const val METERS_PER_MILE = 1_609.34
    const val KM_PER_MILE = 1.60934
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val HALF_CIRCLE_DEGREES = 180.0

    /** Haversine, in metres (boundary spec §2.2). */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = radians(b.lat - a.lat)
        val dLng = radians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(radians(a.lat)) * cos(radians(b.lat)) * sin(dLng / 2).pow(2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    fun distanceMiles(a: LatLng, b: LatLng): Double = distanceMeters(a, b) / METERS_PER_MILE

    /** Whether [point] sits inside a circle of [radiusMiles] around [centre]. */
    fun within(point: LatLng, centre: LatLng, radiusMiles: Double): Boolean =
        distanceMeters(point, centre) <= radiusMiles * METERS_PER_MILE

    private fun radians(degrees: Double): Double = degrees * PI / HALF_CIRCLE_DEGREES
}

/**
 * How many of [locations] fall inside each zone's radius, by zone id — the
 * pin count the web's studio-zone list shows beside each zone.
 */
fun zoneLocationCounts(zones: List<MapLocation>, locations: List<MapLocation>): Map<String, Int> =
    zones.associate { zone ->
        val centre = zone.point
        zone.id to if (centre == null) {
            0
        } else {
            locations.count { location ->
                location.point?.let { Geo.within(it, centre, zone.zoneRadiusMiles) } == true
            }
        }
    }

/**
 * The location form's area check — `MapView.isWithinSelectedCity`.
 *
 * Deliberately NOT the boundary rule in `Boundary.kt`: the web still runs this
 * older test for the address search inside the form and for the save-time
 * notice, and the two disagree at the edges. Ported as-is so the form behaves
 * exactly as the web's does:
 *
 *  - a city with zones admits a point inside ANY of them, and nothing else;
 *  - otherwise the city's locality box (Google's, when it has been resolved);
 *  - otherwise a generous 100 km around the city, so a lookup miss never blocks.
 */
fun isWithinSelectedCity(
    point: LatLng,
    city: MapCity?,
    zones: List<MapLocation>,
    cityBounds: GeoBounds?,
): Boolean {
    val centre = city?.panTarget ?: return true
    return when {
        zones.isNotEmpty() -> zones.any { zone ->
            zone.point?.let { Geo.within(point, it, zone.zoneRadiusMiles) } == true
        }
        cityBounds != null -> cityBounds.contains(point)
        else -> Geo.distanceMeters(point, centre) <= CITY_FALLBACK_METERS
    }
}

/** The fallback reach of a city whose locality box is not known yet. */
private const val CITY_FALLBACK_METERS = 100_000.0
