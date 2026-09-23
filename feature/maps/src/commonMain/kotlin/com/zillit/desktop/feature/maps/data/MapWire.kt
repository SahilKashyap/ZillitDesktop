@file:Suppress("TooManyFunctions") // One parser or body per wire shape, plus the lenient readers they share.

package com.zillit.desktop.feature.maps.data

import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.CityDraft
import com.zillit.desktop.feature.maps.domain.IntersectionStreets
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.TypeDraft
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import com.zillit.desktop.feature.maps.domain.DEFAULT_TYPE_ICON
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * The map service's wire, both directions — pinned by MapWireTest.
 *
 * Transcription notes, from the web's services and hooks and Android's
 * new_map ApiService, which agree:
 *
 *  - the longitude key is `long`, not `lng` (`location: {lat, long}`); older
 *    rows carry `lng`/`longitude` and are read leniently;
 *  - `location_type` carries the type's NAME, `location_sub_type` sub-type
 *    names — never ids;
 *  - an edit is `PUT` on the collection with the id in the body
 *    (`location_id`, `location_type_id`); there is no single-record read;
 *  - an edit is a FULL replacement, so an edited location always sends its
 *    attachments, even when the list is empty.
 */

// Bodies ---------------------------------------------------------------------

/**
 * Add City. The Center Point & Zone section is hidden on every client (req E),
 * so the centre is the city's own point and both radii are zero.
 */
internal fun cityBody(draft: CityDraft): JsonObject = buildJsonObject {
    put("name", draft.name.trim())
    put("description", draft.description.trim())
    put("coordinates", latLong(draft.point))
    put("center_point", latLong(draft.point))
    put("radius", 0)
    put("zone_radius", 0)
}

internal fun reorderBody(cityIds: List<String>): JsonObject = buildJsonObject {
    put("newOrder", buildJsonArray { cityIds.forEach { add(JsonPrimitive(it)) } })
}

/** `HeaderForm.jsx` — an untouched icon saves as "Z". */
internal fun typeBody(draft: TypeDraft, id: String?): JsonObject = buildJsonObject {
    put("name", draft.name.trim())
    put("icon", draft.icon.ifBlank { DEFAULT_TYPE_ICON })
    put("sub_types", buildJsonArray { draft.subTypes.forEach { add(buildJsonObject { put("name", it) }) } })
    put("is_active", true)
    if (id != null) put("location_type_id", id)
}

/** `useLocationForm.js` `buildAndSubmit`. */
internal fun locationBody(draft: LocationDraft, id: String?): JsonObject = buildJsonObject {
    put("city_id", draft.cityId)
    put("location_name", draft.name.trim())
    put("location_type", draft.type)
    put("description", draft.description.trim())
    put("location", latLong(draft.point))
    put("location_address", draft.address)
    put("scene_number", draft.sceneNumber)
    put("location_sub_type", buildJsonArray { draft.subTypes.forEach { add(JsonPrimitive(it)) } })
    put("is_studio_zone", false)
    put("miles", 0)
    put("center_point_type", CenterPointType.Point.wire)
    put("distribute", false)
    // Always on an edit (the list is replaced whole); on a create only when
    // there is something to send, as the web does.
    if (id != null || draft.attachments.isNotEmpty()) {
        put("attachments", buildJsonArray { draft.attachments.forEach { add(attachmentBody(it)) } })
    }
    if (id != null) put("location_id", id)
}

/** `useStudioZoneForm.js` `validateAndSubmit` — a circle, radius in miles. */
internal fun zoneBody(draft: ZoneDraft, id: String?): JsonObject = buildJsonObject {
    put("city_id", draft.cityId)
    put("location_name", draft.name.trim())
    put("location_type", "")
    put("location", latLong(draft.point))
    put("is_studio_zone", true)
    put("miles", draft.radiusMiles)
    put("center_point_type", draft.centerPointType.wire)
    val streets = draft.streets
    if (draft.centerPointType == CenterPointType.Intersection && streets != null) {
        put(
            "intersection_streets",
            buildJsonObject {
                put("street1", streets.street1.trim())
                put("street2", streets.street2.trim())
            },
        )
    }
    if (id != null) put("location_id", id)
}

/** The attachment shape every client writes — every field, even the empty ones. */
internal fun attachmentBody(attachment: MapAttachment): JsonObject = buildJsonObject {
    put("region", attachment.region)
    put("bucket", attachment.bucket)
    put("thumbnail", attachment.thumbnail)
    put("caption", attachment.caption)
    put("height", attachment.height)
    put("media", attachment.media)
    put("content_subtype", attachment.contentSubtype)
    put("width", attachment.width)
    put("duration", attachment.duration)
    put("name", attachment.name)
    put("content_type", attachment.contentType)
}

/** `{lat, long}` — the wire's spelling. */
private fun latLong(point: LatLng): JsonObject = buildJsonObject {
    put("lat", point.lat)
    put("long", point.lng)
}

// Parsers --------------------------------------------------------------------

internal fun parseCities(data: JsonElement?): List<MapCity> = data.items().mapNotNull { parseCity(it as? JsonObject) }

internal fun parseCity(obj: JsonObject?): MapCity? {
    if (obj == null || obj.isDeleted()) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    return MapCity(
        id = id,
        name = obj.text("name", "cityName"),
        description = obj.text("description"),
        coordinates = latLngOf(obj["coordinates"] as? JsonObject) ?: latLngOf(obj),
        centerPoint = latLngOf((obj.firstOf("center_point", "centerPoint")) as? JsonObject),
        radiusMiles = obj.double("radius") ?: 0.0,
        locationCount = obj.long("location_count", "locationCount", "pinCount")?.toInt() ?: 0,
        hasLocations = obj.bool("has_locations"),
    )
}

internal fun parseTypes(data: JsonElement?): List<LocationType> =
    data.items().mapNotNull { parseType(it as? JsonObject) }

internal fun parseType(obj: JsonObject?): LocationType? {
    if (obj == null || obj.isDeleted()) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    val subs = obj.firstOf("sub_types", "sub_headers", "locationSubtypes", "subtypes", "subHeaders") as? JsonArray
    return LocationType(
        id = id,
        name = obj.text("name", "locationType", "headerName"),
        icon = obj.text("icon"),
        subTypes = subs.names(),
        active = (obj["is_active"] as? JsonPrimitive)?.contentOrNull != "false",
    )
}

internal fun parseLocations(data: JsonElement?): List<MapLocation> =
    data.items().mapNotNull { parseLocation(it as? JsonObject) }

/**
 * Alias-tolerant on purpose — the web is, and older rows spell coordinates
 * three ways. The canonical v2 keys come first.
 */
internal fun parseLocation(obj: JsonObject?): MapLocation? {
    if (obj == null || obj.isDeleted()) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    val subs = obj.firstOf("location_sub_type", "locationSubType", "subtypes", "subHeaders") as? JsonArray
    val attachments = obj.firstOf("attachments", "images", "photos") as? JsonArray
    val streets = obj.firstOf("intersection_streets", "intersectionStreets") as? JsonObject
    return MapLocation(
        id = id,
        cityId = obj.text("city_id", "cityId"),
        name = obj.text("location_name", "name", "locationName"),
        type = obj.text("location_type", "locationType", "type", "headerName"),
        subTypes = subs.names(),
        description = obj.text("description"),
        address = obj.text("location_address", "address", "formattedAddress"),
        sceneNumber = obj.text("scene_number", "sceneNumber", "scene"),
        point = latLngOf(obj["location"] as? JsonObject) ?: latLngOf(obj),
        isStudioZone = obj.bool("is_studio_zone"),
        miles = obj.double("miles", "radius", "radiusMiles") ?: 0.0,
        centerPointType = CenterPointType.of(obj.text("center_point_type", "centerPointType")),
        intersection = streets?.let { IntersectionStreets(it.text("street1"), it.text("street2")) }
            ?.takeIf { it.street1.isNotBlank() },
        attachments = attachments.items().mapNotNull { parseAttachment(it) },
    )
}

internal fun parseAttachment(element: JsonElement?): MapAttachment? {
    val obj = element as? JsonObject ?: return (element as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { MapAttachment(media = it) }
    val media = obj.text("media")
    if (media.isBlank()) return null
    return MapAttachment(
        media = media,
        bucket = obj.text("bucket"),
        region = obj.text("region"),
        thumbnail = obj.text("thumbnail"),
        name = obj.text("name"),
        contentType = obj.text("content_type"),
        contentSubtype = obj.text("content_subtype"),
        caption = obj.text("caption"),
        width = obj.long("width")?.toInt() ?: 0,
        height = obj.long("height")?.toInt() ?: 0,
        duration = obj.long("duration") ?: 0L,
    )
}

/** `{lat, long}` in any of the three spellings; null unless both are numbers. */
internal fun latLngOf(obj: JsonObject?): LatLng? {
    if (obj == null) return null
    val lat = obj.double("lat", "latitude") ?: return null
    val lng = obj.double("long", "lng", "longitude") ?: return null
    return if (lat.isFinite() && lng.isFinite()) LatLng(lat, lng) else null
}

// Lenient readers ------------------------------------------------------------

private fun JsonObject.isDeleted(): Boolean = (long("deleted_on") ?: 0L) > 0L

internal fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonObject.double(vararg names: String): Double? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

internal fun JsonObject.bool(vararg names: String): Boolean =
    (firstOf(*names) as? JsonPrimitive)?.contentOrNull.equals("true", ignoreCase = true)

internal fun JsonElement?.items(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()

/** Sub-types arrive as strings or as `{name}` / `{subHeaderName}` objects. */
private fun JsonArray?.names(): List<String> = items().mapNotNull { sub ->
    (sub as? JsonPrimitive)?.contentOrNull ?: (sub as? JsonObject)?.text("name", "subHeaderName")
}.filter { it.isNotBlank() }
