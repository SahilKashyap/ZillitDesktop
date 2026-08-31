package com.zillit.desktop.feature.maps.domain

import com.zillit.desktop.core.permissions.ProjectPermissions
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The map tool's data — cities, typed pinned locations, and studio zones.
 *
 * The desktop has no map tiles. Everything here is what the web keeps
 * *around* its Google map: the records, their coordinates, and the
 * containment arithmetic; a location opens in the system's maps app.
 */
data class MapCity(
    val id: String,
    val name: String,
    val description: String,
    val lat: Double?,
    val lng: Double?,
    /** Miles; zero when the city has no zone radius. */
    val radiusMiles: Double,
    val locationCount: Int,
)

/** A location type ("header"), with the sub-types it offers. */
data class LocationType(
    val id: String,
    val name: String,
    val icon: String,
    val subTypes: List<String>,
    val active: Boolean,
)

/**
 * One pin. Studio zones share the collection, discriminated by
 * [isStudioZone]; a zone is a CIRCLE — centre plus [radiusMiles].
 */
data class MapLocation(
    val id: String,
    val cityId: String,
    val name: String,
    /** The type NAME, not an id; free text when the type is "Other". */
    val type: String,
    val subTypes: List<String>,
    val description: String,
    val address: String,
    val sceneNumber: String,
    val lat: Double?,
    val lng: Double?,
    val isStudioZone: Boolean,
    val radiusMiles: Double,
    val centerPointType: String,
    val attachmentNames: List<String>,
) {
    /** The universal hand-off — every maps app opens this. */
    val mapsUrl: String? get() = if (lat != null && lng != null) "https://maps.google.com/?q=$lat,$lng" else null
}

/** What a create/edit sends for a location. */
data class LocationDraft(
    val cityId: String,
    val name: String,
    val type: String,
    val subTypes: List<String> = emptyList(),
    val description: String = "",
    val address: String,
    val sceneNumber: String = "",
    val lat: Double,
    val lng: Double,
)

/** What a create/edit sends for a studio zone. */
data class ZoneDraft(
    val cityId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMiles: Double,
)

/** Great-circle helpers — the web's `computeDistanceBetween` calls, without Google. */
object Geo {
    const val METERS_PER_MILE = 1_609.34
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine, in metres. */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** Whether a point sits inside a circle of [radiusMiles] around a centre. */
    fun within(lat: Double, lng: Double, centreLat: Double, centreLng: Double, radiusMiles: Double): Boolean =
        distanceMeters(lat, lng, centreLat, centreLng) <= radiusMiles * METERS_PER_MILE

    /** The web's radius presets. */
    val ZONE_RADII = listOf(30.0, 40.0, 50.0, 60.0)
}

/** The built-in type → glyph table the web falls back to. Pure data. */
val DEFAULT_TYPE_GLYPHS: Map<String, String> = mapOf(
    "Hotel" to "🏨", "Parking" to "🅿️", "Shooting" to "🎬", "Catering" to "🍽️", "Hospital" to "🏥",
    "Base Camp" to "⛺", "Crew Parking" to "🚐", "Equipment" to "🧰", "Extras Holding" to "👥",
    "Dressing Room" to "👗", "Production Office" to "🏢", "Other" to "📍",
)

data class MapViewer(
    val userId: String = "",
    val displayName: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    /**
     * Rights as issued, with no project-admin bypass.
     *
     * Android's map screen reads the tool's row and nothing else —
     * `if (viewAccess == false) finish() else hasPermission = postingAccess`
     * (`ViewPinnedLocationActivity.checkPostingRights`). The web's map module
     * never mentions `is_admin` either. Owning the production does not put
     * pins on its map.
     */
    val isBlocked: Boolean get() = ready && !canView
    val mayEdit: Boolean get() = canPost

    companion object {
        const val TOOL_IDENTIFIER = "map_tool"

        fun from(permissions: ProjectPermissions, userId: String, displayName: String): MapViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return MapViewer(userId = userId, displayName = displayName, ready = false)
            }
            return MapViewer(
                userId = userId,
                displayName = displayName,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
