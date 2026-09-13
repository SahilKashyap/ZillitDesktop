package com.zillit.desktop.feature.maps.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * The map tool's data — cities, typed pinned locations, studio zones and the
 * location types that classify them.
 *
 * Transcribed from the web's `components/map-module` (the module mounted at
 * `/film-tools/map`), which reads every record leniently: the v2 service
 * spells coordinates `{lat, long}`, older rows `lng`/`longitude`. The parsers
 * in `data/MapWire.kt` fold those spellings into the shapes below once, so
 * nothing past the wire has to know.
 */
data class LatLng(val lat: Double, val lng: Double)

/** A rectangle on the map — a city's locality box, a set of pins to fit. */
data class GeoBounds(val north: Double, val south: Double, val east: Double, val west: Double) {
    fun contains(point: LatLng): Boolean =
        point.lat in south..north && point.lng in west..east
}

data class MapCity(
    val id: String,
    val name: String,
    val description: String = "",
    /** `coordinates` — where the city is, and where the map pans to. */
    val coordinates: LatLng? = null,
    /** `center_point` — the boundary's centre; boundary checks prefer it. */
    val centerPoint: LatLng? = null,
    /** `radius`, miles. Zero is not "no boundary": checks read it as 30. */
    val radiusMiles: Double = 0.0,
    val locationCount: Int = 0,
    val hasLocations: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { "City" }

    /**
     * Where the camera goes for this city — the web's `getCityCoords`, which
     * treats a zero on either axis as "no coordinates" (`lat && lng`).
     */
    val panTarget: LatLng? get() = coordinates?.takeIf { it.lat != 0.0 && it.lng != 0.0 }
}

/** A location type — a "header" on the older wire: a name, an icon, its sub-types. */
data class LocationType(
    val id: String,
    val name: String,
    val icon: String = "",
    val subTypes: List<String> = emptyList(),
    val active: Boolean = true,
)

/**
 * A photo on a location, in the attachment shape every client writes.
 *
 * Every field is kept, not just the key: an edit sends the location's whole
 * attachment list back (`useLocationForm.js` — "always send in edit mode"),
 * and a field dropped on the way through would be dropped from the record.
 */
data class MapAttachment(
    /** The object key — `media` on the wire. */
    val media: String,
    val bucket: String = "",
    val region: String = "",
    val thumbnail: String = "",
    val name: String = "",
    val contentType: String = "",
    val contentSubtype: String = "",
    val caption: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val duration: Long = 0,
)

/** The two streets a zone's centre was found from (`intersection_streets`). */
data class IntersectionStreets(val street1: String, val street2: String = "") {
    /** "MG Road & Ring Road", or the one street. */
    val label: String get() = if (street2.isBlank()) street1 else "$street1 & $street2"
}

/** How a zone's centre was chosen — `center_point_type` on the wire. */
enum class CenterPointType(val wire: String) {
    Point("point"),
    Intersection("intersection"),
    ;

    companion object {
        fun of(wire: String?): CenterPointType = entries.firstOrNull { it.wire == wire } ?: Point
    }
}

/**
 * One pin. Studio zones share the collection, told apart by [isStudioZone];
 * a zone is a circle — its [point] is the centre and [miles] the radius.
 */
data class MapLocation(
    val id: String,
    val cityId: String,
    val name: String,
    /** The type's NAME (the wire carries names, not ids). Blank when untyped. */
    val type: String = "",
    val subTypes: List<String> = emptyList(),
    val description: String = "",
    val address: String = "",
    val sceneNumber: String = "",
    val point: LatLng? = null,
    val isStudioZone: Boolean = false,
    /** Raw `miles` (or `radius`); zero when the record has none. */
    val miles: Double = 0.0,
    val centerPointType: CenterPointType = CenterPointType.Point,
    val intersection: IntersectionStreets? = null,
    val attachments: List<MapAttachment> = emptyList(),
) {
    val displayName: String get() = name.ifBlank { if (isStudioZone) "Unnamed Zone" else "Unnamed Location" }

    /** A zone's radius as every web surface reads it: `miles || radius || 30`. */
    val zoneRadiusMiles: Double get() = miles.takeIf { it > 0 } ?: DEFAULT_ZONE_MILES

    /** Whether the record carries a type worth a badge — the web hides "Unknown". */
    val hasType: Boolean get() = type.isNotBlank() && type != UNKNOWN_TYPE
}

/** What Add City sends — the city only; zones are their own flow (client req E). */
data class CityDraft(val name: String, val description: String, val point: LatLng)

/** What a location create or edit sends. */
data class LocationDraft(
    val cityId: String,
    val name: String,
    val type: String,
    val subTypes: List<String> = emptyList(),
    val description: String = "",
    val address: String,
    val sceneNumber: String = "",
    val point: LatLng,
    val attachments: List<MapAttachment> = emptyList(),
)

/** What a studio-zone create or edit sends. */
data class ZoneDraft(
    val cityId: String,
    val name: String,
    val point: LatLng,
    val radiusMiles: Double,
    val centerPointType: CenterPointType = CenterPointType.Point,
    val streets: IntersectionStreets? = null,
)

/** What a location-type create or edit sends. */
data class TypeDraft(val name: String, val icon: String, val subTypes: List<String>)

/** The radius a zone falls back to when none was given — boundary spec §2.2. */
const val DEFAULT_ZONE_MILES = 30.0

/** The web's `parseLocationType` placeholder for an untyped record. */
const val UNKNOWN_TYPE = "Unknown"

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
     * Android's map screen reads the tool's row and nothing else, and the
     * web's map module never mentions `is_admin` either. Owning the
     * production does not put pins on its map.
     */
    val isBlocked: Boolean get() = ready && !canView

    /**
     * Whether a write control acts or asks for rights.
     *
     * Optimistic until the rights have arrived, as the web's `AppContext`
     * is: "not-loaded means ALLOW, so the UI never flashes gated on first
     * paint". The server enforces rights regardless — an optimistic press
     * costs a refused request at worst, a wrongly refused one costs work.
     */
    val mayPost: Boolean get() = !ready || canPost

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
