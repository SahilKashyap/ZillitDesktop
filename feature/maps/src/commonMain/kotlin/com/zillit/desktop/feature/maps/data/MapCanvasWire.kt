package com.zillit.desktop.feature.maps.data

import com.zillit.desktop.feature.maps.domain.MapCanvasEvent
import com.zillit.desktop.feature.maps.domain.MapPinMarker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The wire between Kotlin and the map page (`mapengine/map.html`).
 *
 * Pure functions, like the call engine's `EngineBridge`: the CEF half cannot
 * run in a unit test, so everything parseable — event decoding, script
 * construction, string escaping — lives here and the KCEF class stays a thin
 * transport. Event shapes are the contract with `map.html`; change either
 * side only with the other.
 */
object MapCanvasWire {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * True for the page shell's own announcement — the page has loaded but
     * holds no map yet. The transport answers it by pushing the key down;
     * it is not a [MapCanvasEvent].
     */
    fun isPageReady(message: String): Boolean = obj(message)?.str("type") == TYPE_PAGE_READY

    /**
     * One JSON line from the page, as the canvas event it means — or null
     * for [isPageReady], unknown types, and malformed JSON. Never throws.
     */
    fun parse(message: String): MapCanvasEvent? {
        val body = obj(message) ?: return null
        return when (body.str("type")) {
            "map-ready" -> MapCanvasEvent.Ready
            "marker-click" -> MapCanvasEvent.MarkerClicked(body.str("id").orEmpty())
            "map-click" -> MapCanvasEvent.MapClicked(body.dbl("lat"), body.dbl("lng"))
            // The page's `gm_authFailure` hook — Google refused the key (a
            // referrer-restricted key rejecting the file:// origin lands
            // here). Without this the canvas is silently a grey slab.
            "auth-failed" -> MapCanvasEvent.Failed(
                "Google rejected this project's Maps key, so the map cannot load.",
            )
            "error" -> MapCanvasEvent.Failed(body.str("message") ?: "map page error")
            else -> null
        }
    }

    // ── Kotlin → page ───────────────────────────────────────────────────

    /**
     * Hands the decrypted Maps key to the loaded page, which then injects
     * Google's script tag itself — the key never touches the extracted file
     * on disk, only the page runtime. Never log the returned script.
     */
    fun bootScript(key: String): String = "zillitMap.boot(${quote(key)})"

    /**
     * The full marker set as one JSON *string*, parsed inside the page —
     * splicing object literals into a script would make every pin name an
     * injection site, and names come off the wire.
     */
    fun pinsScript(pins: List<MapPinMarker>): String {
        val payload = buildJsonArray {
            pins.forEach { pin ->
                add(
                    buildJsonObject {
                        put("id", pin.id)
                        put("name", pin.name)
                        put("label", pin.label)
                        put("lat", pin.lat)
                        put("lng", pin.lng)
                        put("isZone", pin.isZone)
                        put("radiusMiles", pin.radiusMiles)
                    },
                )
            }
        }
        return "zillitMap.setPins(${quote(payload.toString())})"
    }

    fun centerScript(lat: Double, lng: Double, zoom: Int): String =
        "zillitMap.center($lat, $lng, $zoom)"

    /** A JS string literal that cannot break out of itself. */
    private fun quote(value: String): String =
        json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private fun obj(message: String): JsonObject? =
        runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.dbl(key: String): Double =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0

    private const val TYPE_PAGE_READY = "ready"
}
