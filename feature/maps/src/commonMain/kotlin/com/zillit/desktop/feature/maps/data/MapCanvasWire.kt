@file:Suppress("TooManyFunctions") // One builder or decoder per message on the page's wire.

package com.zillit.desktop.feature.maps.data

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.domain.CanvasTheme
import com.zillit.desktop.feature.maps.domain.GeoBounds
import com.zillit.desktop.feature.maps.domain.GeoComponent
import com.zillit.desktop.feature.maps.domain.GeocodeOutcome
import com.zillit.desktop.feature.maps.domain.GeocodedPlace
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapCanvasEvent
import com.zillit.desktop.feature.maps.domain.MapScene
import com.zillit.desktop.feature.maps.domain.MarkerActionKind
import com.zillit.desktop.feature.maps.domain.PlaceDetails
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.RouteInfo
import com.zillit.desktop.feature.maps.domain.SceneCircle
import com.zillit.desktop.feature.maps.domain.SceneCities
import com.zillit.desktop.feature.maps.domain.SceneMarker
import com.zillit.desktop.feature.maps.domain.SceneZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The wire between Kotlin and the map page (`mapengine/map.html`).
 *
 * Pure functions, like the call engine's `EngineBridge`: the CEF half cannot
 * run in a unit test, so everything parseable — script construction, event
 * decoding, string escaping — lives here and the browser class stays a thin
 * transport. The shapes are the contract with `map.html`; change either side
 * only with the other.
 *
 * Every payload crosses as one JSON *string* parsed inside the page. Splicing
 * object literals into a script would make every pin name an injection site,
 * and names come off the wire.
 */
object MapCanvasWire {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * True for the page shell's own announcement — loaded, keyless. The host
     * answers it with [bootScript]; it is not an event.
     */
    fun isPageReady(message: String): Boolean = obj(message)?.str("type") == TYPE_PAGE_READY

    /**
     * Hands the decrypted Maps key to the loaded page, which injects Google's
     * script itself — the key never touches the extracted file on disk. Never
     * log the returned script.
     */
    fun bootScript(key: String): String = "zillitMap.boot(${quote(key)})"

    fun renderScript(scene: MapScene): String = call("render", sceneJson(scene))

    fun themeScript(theme: CanvasTheme): String = call(
        "setTheme",
        buildJsonObject {
            put("surface", theme.surface)
            put("text", theme.text)
            put("textSecondary", theme.textSecondary)
            put("textMuted", theme.textMuted)
            put("border", theme.border)
            put("accent", theme.accent)
            put("dark", theme.isDark)
        },
    )

    fun panScript(point: LatLng, zoom: Int?): String = call(
        "camera",
        buildJsonObject {
            put("op", "pan")
            putPoint(point)
            zoom?.let { put("zoom", it) }
        },
    )

    /** Fit every point, padded 50px; a single point settles at [singleZoom]. */
    fun fitPointsScript(points: List<LatLng>, singleZoom: Int): String = call(
        "camera",
        buildJsonObject {
            put("op", "fit")
            put("points", buildJsonArray { points.forEach { add(buildJsonObject { putPoint(it) }) } })
            put("singleZoom", singleZoom)
        },
    )

    fun fitCircleScript(circle: SceneCircle): String = call(
        "camera",
        buildJsonObject {
            put("op", "fitCircle")
            putPoint(circle.centre)
            put("radiusMiles", circle.radiusMiles)
        },
    )

    /** Pans and zooms to a saved pin and opens its card — a search result chosen. */
    fun focusScript(markerId: String, zoom: Int): String = call(
        "camera",
        buildJsonObject {
            put("op", "focus")
            put("id", markerId)
            put("zoom", zoom)
        },
    )

    fun clearScript(preview: Boolean = false, place: Boolean = false, route: Boolean = false): String = call(
        "clear",
        buildJsonObject {
            put("preview", preview)
            put("place", place)
            put("route", route)
        },
    )

    /** A question for Google's services on the page; answered by a `reply` with [id]. */
    fun requestScript(id: Int, op: String, args: JsonObject): String = call(
        "request",
        buildJsonObject {
            put("id", id)
            put("op", op)
            args.forEach { (key, value) -> put(key, value) }
        },
    )

    /**
     * One JSON line from the page, as the event it means — or null for
     * [isPageReady], replies, unknown types and malformed JSON. Never throws.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per message type.
    fun parseEvent(message: String): MapCanvasEvent? {
        val body = obj(message) ?: return null
        return when (body.str("type")) {
            "map-ready" -> MapCanvasEvent.Ready
            // The page's `gm_authFailure` hook — Google refused the key.
            // Without it the canvas is silently a grey slab.
            "auth-failed" -> MapCanvasEvent.Failed(str(S.desktop_map_auth_failed))
            "error" -> MapCanvasEvent.Failed(body.str("message") ?: str(S.desktop_map_page_error))
            "marker-action" -> markerAction(body)
            "zone-action" -> body.str("id")?.let { MapCanvasEvent.ZoneEdit(it) }
            "preview-action" -> body.point()?.let {
                MapCanvasEvent.PreviewAdd(it, body.str("name").orEmpty(), body.str("address").orEmpty())
            }
            "place-action" -> placeAction(body)
            "marker-dragged" -> body.point()?.let { point ->
                body.str("id")?.let { MapCanvasEvent.MarkerDragged(it, point) }
            }
            "draft-zone-moved" -> body.point()?.let { MapCanvasEvent.DraftZoneMoved(it) }
            "draft-pin-moved" -> body.point()?.let { MapCanvasEvent.DraftPinMoved(it) }
            "cities-open" -> MapCanvasEvent.CitiesOpen
            "guide" -> MapCanvasEvent.Guide(
                collapsed = body.bool("collapsed"),
                dismissed = body.bool("dismissed"),
            )
            else -> null
        }
    }

    /** A `reply` to [requestScript]: its id, whether it succeeded, and its body. */
    fun parseReply(message: String): CanvasReply? {
        val body = obj(message) ?: return null
        if (body.str("type") != TYPE_REPLY) return null
        val id = (body["id"] as? JsonPrimitive)?.intOrNull ?: return null
        return CanvasReply(id = id, ok = body.bool("ok"), body = body)
    }

    // Reply decoders -----------------------------------------------------------

    fun geocoded(reply: CanvasReply): GeocodedPlace? {
        if (!reply.ok) return null
        val components = (reply.body["components"] as? JsonArray).orEmpty().mapNotNull { element ->
            val component = element as? JsonObject ?: return@mapNotNull null
            GeoComponent(
                longName = component.str("long_name").orEmpty(),
                types = (component["types"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }
        return GeocodedPlace(address = reply.body.str("address").orEmpty(), components = components)
    }

    fun predictions(reply: CanvasReply): List<PlacePrediction>? {
        if (!reply.ok) return null
        return (reply.body["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val placeId = item.str("placeId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlacePrediction(
                placeId = placeId,
                description = item.str("description").orEmpty(),
                mainText = item.str("main").orEmpty().ifBlank { item.str("description").orEmpty() },
                secondaryText = item.str("secondary").orEmpty(),
            )
        }
    }

    fun details(reply: CanvasReply): PlaceDetails? {
        if (!reply.ok) return null
        val point = reply.body.point() ?: return null
        return PlaceDetails(
            name = reply.body.str("name").orEmpty(),
            address = reply.body.str("address").orEmpty(),
            point = point,
        )
    }

    fun route(reply: CanvasReply): RouteInfo? {
        if (!reply.ok) return null
        return RouteInfo(
            distanceText = reply.body.str("distanceText").orEmpty(),
            distanceMeters = reply.body.dbl("distanceMeters") ?: 0.0,
            durationText = reply.body.str("durationText").orEmpty(),
            startAddress = reply.body.str("startAddress").orEmpty(),
            endAddress = reply.body.str("endAddress").orEmpty(),
        )
    }

    fun geocode(reply: CanvasReply): GeocodeOutcome {
        val point = reply.body.point()
        return if (reply.ok && point != null) {
            GeocodeOutcome.Found(point, reply.body.str("address").orEmpty())
        } else {
            GeocodeOutcome.NotFound(reply.body.str("status") ?: "UNKNOWN_ERROR")
        }
    }

    @Suppress("ReturnCount") // One guard per required edge; a partial box is no box.
    fun bounds(reply: CanvasReply): GeoBounds? {
        if (!reply.ok) return null
        val body = reply.body
        return GeoBounds(
            north = body.dbl("north") ?: return null,
            south = body.dbl("south") ?: return null,
            east = body.dbl("east") ?: return null,
            west = body.dbl("west") ?: return null,
        )
    }

    fun position(reply: CanvasReply): LatLng? = if (reply.ok) reply.body.point() else null

    // Scene --------------------------------------------------------------------

    internal fun sceneJson(scene: MapScene): JsonObject = buildJsonObject {
        put("markers", markersJson(scene.markers))
        put("pinMode", scene.pinMode)
        put("draggable", scene.draggable)
        put("lockedId", scene.lockedId)
        put(
            "pendingMove",
            scene.pendingMove?.let { pin -> buildJsonObject { put("id", pin.id); putPoint(pin.point) } } ?: JsonNull,
        )
        put("zone", scene.zone?.let(::zoneJson) ?: JsonNull)
        put(
            "draftZone",
            scene.draftZone?.let { circle ->
                buildJsonObject {
                    putPoint(circle.centre)
                    put("radiusMiles", circle.radiusMiles)
                }
            } ?: JsonNull,
        )
        put("draftPin", scene.draftPin?.let { point -> buildJsonObject { putPoint(point) } } ?: JsonNull)
        put("cities", scene.cities?.let(::citiesJson) ?: JsonNull)
        put(
            "guide",
            buildJsonObject {
                put("visible", scene.guide.visible)
                put("collapsed", scene.guide.collapsed)
            },
        )
        put("canPost", scene.canPost)
        put("nonce", scene.nonce)
    }

    private fun markersJson(markers: List<SceneMarker>): JsonArray = buildJsonArray {
        markers.forEach { marker ->
            add(
                buildJsonObject {
                    put("id", marker.id)
                    put("name", marker.name)
                    put("type", marker.type)
                    put("icon", marker.icon)
                    put("color", marker.color)
                    putPoint(marker.point)
                    put("address", marker.address)
                    put("subTypes", buildJsonArray { marker.subTypes.forEach { add(JsonPrimitive(it)) } })
                    put("sceneNumber", marker.sceneNumber)
                    put("description", marker.description)
                },
            )
        }
    }

    private fun zoneJson(zone: SceneZone): JsonObject = buildJsonObject {
        put("id", zone.id)
        put("name", zone.name)
        putPoint(zone.circle.centre)
        put("radiusMiles", zone.circle.radiusMiles)
        put("address", zone.address)
        put("type", zone.type)
        put("streets", zone.streets)
    }

    private fun citiesJson(cities: SceneCities): JsonObject = buildJsonObject {
        put("count", cities.count)
        put("unread", cities.unread)
        put("selectedName", cities.selectedName)
        put("selectedCount", cities.selectedCount)
    }

    // Internals ----------------------------------------------------------------

    private fun markerAction(body: JsonObject): MapCanvasEvent? {
        val id = body.str("id") ?: return null
        val action = MarkerActionKind.entries.firstOrNull { it.wire == body.str("action") } ?: return null
        return MapCanvasEvent.MarkerAction(action, id)
    }

    private fun placeAction(body: JsonObject): MapCanvasEvent? {
        val point = body.point() ?: return null
        val name = body.str("name").orEmpty()
        val address = body.str("address").orEmpty()
        return when (body.str("action")) {
            "pin" -> MapCanvasEvent.PlacePin(point, name, address)
            "directions" -> MapCanvasEvent.PlaceDirections(point, name, address)
            else -> null
        }
    }

    private fun call(function: String, payload: JsonObject): String =
        "zillitMap.$function(${quote(payload.toString())})"

    /** A JS string literal that cannot break out of itself. */
    private fun quote(value: String): String =
        json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private fun obj(message: String): JsonObject? =
        runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.dbl(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun JsonObject.bool(key: String): Boolean = str(key).equals("true", ignoreCase = true)

    private fun JsonObject.point(): LatLng? {
        val lat = dbl("lat") ?: return null
        val lng = dbl("lng") ?: return null
        return LatLng(lat, lng)
    }

    private fun JsonObjectBuilder.putPoint(point: LatLng) {
        put("lat", point.lat)
        put("lng", point.lng)
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList() ?: emptyList()

    private const val TYPE_PAGE_READY = "ready"
    private const val TYPE_REPLY = "reply"
}

/** The page's answer to one request. */
data class CanvasReply(val id: Int, val ok: Boolean, val body: JsonObject)
