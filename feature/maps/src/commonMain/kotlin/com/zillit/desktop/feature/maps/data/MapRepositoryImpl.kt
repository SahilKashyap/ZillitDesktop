package com.zillit.desktop.feature.maps.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapRepository
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The map service (`mapapi`, `/api/v2`).
 *
 * Transcription notes, verified against the web client and the dev host:
 *
 *  - HTTP 200 + `status:0` is a failure — checked on every call;
 *  - the longitude key is **`long`**, not `lng` (`location: {lat, long}`);
 *    older rows carry `lng`/`longitude` and are read leniently;
 *  - `location_type` carries the type's NAME, `location_sub_type` the
 *    sub-type names — never ids;
 *  - `GET /map` returns locations and studio zones mixed, discriminated by
 *    `is_studio_zone`; a zone is a circle, `miles` is its radius;
 *  - update is `PUT /map` with `location_id` in the body — there is no
 *    `/map/{id}` read route, so edit re-lists;
 *  - `distribute:false` is hard-coded on every write, as the web does.
 */
@Suppress("TooManyFunctions") // One function per REST route.
class MapRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : MapRepository {

    private val base = config.apiV2(ZillitService.Map).trimEnd('/')

    /**
     * See [MapRepository.refreshes]. Another production's frame is dropped
     * when both sides can name a project, as every web handler does.
     */
    override val refreshes: Flow<MapRefresh> =
        bus?.onAny(MAP_SYNC_EVENTS)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.map { message -> if (message.event in MAP_CITY_EVENTS) MapRefresh.Cities else MapRefresh.Pins }
            ?: emptyFlow()

    override suspend fun cities(): ZillitResult<List<MapCity>> =
        get("$base/city").mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseCity(it as? JsonObject) }
        }

    override suspend fun createCity(
        name: String,
        description: String,
        lat: Double,
        lng: Double,
        radiusMiles: Double,
    ): ZillitResult<Unit> = write(
        HttpVerb.Post,
        "$base/city",
        buildJsonObject {
            put("name", name)
            put("description", description)
            put("coordinates", latLong(lat, lng))
            put("center_point", latLong(lat, lng))
            put("radius", radiusMiles)
            put("zone_radius", radiusMiles)
        },
    )

    override suspend fun deleteCity(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/city/$id", null)

    /** `PUT /v2/city/reorder-cities` — the whole arrangement, not a move. */
    override suspend fun reorderCities(cityIds: List<String>): ZillitResult<Unit> = write(
        HttpVerb.Put,
        "$base/city/reorder-cities",
        buildJsonObject {
            put("newOrder", buildJsonArray { cityIds.forEach { add(JsonPrimitive(it)) } })
        },
    )

    override suspend fun types(): ZillitResult<List<LocationType>> =
        get("$base/location-type").mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseType(it as? JsonObject) }
        }

    override suspend fun createType(name: String, icon: String, subTypes: List<String>): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            "$base/location-type",
            buildJsonObject {
                put("name", name)
                put("icon", icon.ifBlank { "Z" })
                put(
                    "sub_types",
                    buildJsonArray { subTypes.forEach { add(buildJsonObject { put("name", it) }) } },
                )
                put("is_active", true)
            },
        )

    override suspend fun deleteType(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/location-type/$id", null)

    override suspend fun locations(cityId: String?): ZillitResult<List<MapLocation>> =
        get("$base/map", cityId?.let { mapOf("cityId" to it) }.orEmpty()).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseLocation(it as? JsonObject) }
        }

    override suspend fun createLocation(draft: LocationDraft): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/map", locationWire(draft, id = null))

    override suspend fun updateLocation(id: String, draft: LocationDraft): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/map", locationWire(draft, id = id))

    override suspend fun createZone(draft: ZoneDraft): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/map", zoneWire(draft, id = null))

    override suspend fun updateZone(id: String, draft: ZoneDraft): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/map", zoneWire(draft, id = id))

    override suspend fun delete(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/map/$id", null)

    // Transport ------------------------------------------------------------

    private suspend fun get(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<ApiEnvelope> =
        apiClient.envelope(HttpVerb.Get, url, RequestModule.ProjectUser, queryParameters = query)

    private suspend fun write(verb: HttpVerb, url: String, body: JsonObject?): ZillitResult<Unit> =
        when (val envelope = apiClient.envelope(verb, url, RequestModule.ProjectUser, body)) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success ->
                if (envelope.data.status == 0) {
                    ZillitResult.Failure(rejected(envelope.data))
                } else {
                    ZillitResult.Success(Unit)
                }
        }

    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> =
        when (this) {
            is ZillitResult.Failure -> this
            is ZillitResult.Success ->
                if (data.status == 0) {
                    ZillitResult.Failure(rejected(data))
                } else {
                    ZillitResult.Success(transform(data.data))
                }
        }

    private fun rejected(envelope: ApiEnvelope): ZillitError =
        ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message ?: "something_went_wrong")

    private companion object {
        const val HTTP_OK = 200
    }
}

/** The location body — the exact contract, pinned by test. */
internal fun locationWire(draft: LocationDraft, id: String?): JsonObject = buildJsonObject {
    put("city_id", draft.cityId)
    put("location_name", draft.name.trim())
    put("location_type", draft.type)
    put("description", draft.description)
    put("location", latLong(draft.lat, draft.lng))
    put("location_address", draft.address.trim())
    put("scene_number", draft.sceneNumber)
    put("location_sub_type", buildJsonArray { draft.subTypes.forEach { add(JsonPrimitive(it)) } })
    put("is_studio_zone", false)
    put("miles", 0)
    put("center_point_type", "point")
    put("distribute", false)
    if (id != null) {
        put("location_id", id)
        // The web always sends the array on edit, even empty.
        put("attachments", buildJsonArray { })
    }
}

/** The studio-zone body: a circle, radius in miles. */
internal fun zoneWire(draft: ZoneDraft, id: String?): JsonObject = buildJsonObject {
    put("city_id", draft.cityId)
    put("location_name", draft.name.trim())
    put("location_type", "")
    put("location", latLong(draft.lat, draft.lng))
    put("is_studio_zone", true)
    put("miles", draft.radiusMiles)
    put("center_point_type", "point")
    if (id != null) put("location_id", id)
}

/** `{lat, long}` — the wire's spelling; `lng` is what older rows carry. */
private fun latLong(lat: Double, lng: Double): JsonObject = buildJsonObject {
    put("lat", lat)
    put("long", lng)
}

// Parsers ------------------------------------------------------------------

private fun parseCity(obj: JsonObject?): MapCity? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    val coords = obj["coordinates"] as? JsonObject
    return MapCity(
        id = id,
        name = obj.text("name", "cityName"),
        description = obj.text("description"),
        lat = coords?.double("lat") ?: obj.double("lat", "latitude"),
        lng = coords?.double("long", "lng") ?: obj.double("lng", "long", "longitude"),
        radiusMiles = obj.double("zone_radius", "radius") ?: 0.0,
        locationCount = obj.long("locationCount", "pinCount")?.toInt() ?: 0,
    )
}

private fun parseType(obj: JsonObject?): LocationType? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    val subs = obj.firstOf("sub_types", "sub_headers", "locationSubtypes", "subtypes", "subHeaders") as? JsonArray
    return LocationType(
        id = id,
        name = obj.text("name"),
        icon = obj.text("icon"),
        subTypes = subs.items().mapNotNull { sub ->
            (sub as? JsonObject)?.text("name", "subHeaderName") ?: (sub as? JsonPrimitive)?.content
        }.filter { it.isNotBlank() },
        active = obj.firstOf("is_active")?.let { (it as? JsonPrimitive)?.content != "false" } ?: true,
    )
}

/**
 * The read side is alias-tolerant on purpose — the web is, and older rows
 * spell coordinates three ways. The canonical v2 keys come first.
 */
private fun parseLocation(obj: JsonObject?): MapLocation? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    if ((obj.long("deleted_on") ?: 0L) > 0L) return null
    val point = obj["location"] as? JsonObject
    val subs = obj.firstOf("location_sub_type", "locationSubType", "subtypes", "subHeaders") as? JsonArray
    val attachments = obj.firstOf("attachments", "images", "photos") as? JsonArray
    return MapLocation(
        id = id,
        cityId = obj.text("city_id"),
        name = obj.text("location_name", "name", "locationName"),
        type = obj.text("location_type", "locationType", "type", "headerName"),
        subTypes = subs.items().mapNotNull { sub ->
            (sub as? JsonPrimitive)?.content ?: (sub as? JsonObject)?.text("name", "subHeaderName")
        },
        description = obj.text("description"),
        address = obj.text("location_address", "address", "formattedAddress"),
        sceneNumber = obj.text("scene_number", "sceneNumber", "scene"),
        lat = point?.double("lat") ?: obj.double("lat", "latitude"),
        lng = point?.double("long", "lng") ?: obj.double("lng", "long", "longitude"),
        isStudioZone = obj.bool("is_studio_zone"),
        radiusMiles = obj.double("miles", "radius", "radiusMiles") ?: 0.0,
        centerPointType = obj.text("center_point_type", "centerPointType"),
        attachmentNames = attachments.items()
            .mapNotNull { (it as? JsonObject)?.text("name") }
            .filter { it.isNotBlank() },
    )
}

// Lenient readers ----------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

private fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

private fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.longOrNull

private fun JsonObject.double(vararg names: String): Double? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

private fun JsonObject.bool(vararg names: String): Boolean =
    (firstOf(*names) as? JsonPrimitive)?.content.equals("true", ignoreCase = true)

private fun JsonArray?.items(): List<JsonElement> = this?.toList() ?: emptyList()
