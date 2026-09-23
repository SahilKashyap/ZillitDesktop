package com.zillit.desktop.feature.maps.data

import com.zillit.desktop.feature.maps.domain.CanvasTheme
import com.zillit.desktop.feature.maps.domain.GeoBounds
import com.zillit.desktop.feature.maps.domain.GeocodeOutcome
import com.zillit.desktop.feature.maps.domain.GeocodedPlace
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapCanvasEvent
import com.zillit.desktop.feature.maps.domain.MapCanvasHost
import com.zillit.desktop.feature.maps.domain.MapScene
import com.zillit.desktop.feature.maps.domain.PlaceDetails
import com.zillit.desktop.feature.maps.domain.PlaceKind
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.RouteInfo
import com.zillit.desktop.feature.maps.domain.SceneCircle
import com.zillit.desktop.feature.maps.domain.SearchBias
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Talks to the map page: states the scene, moves the camera, and asks Google's
 * services questions through it.
 *
 * ## Why the page answers questions
 *
 * The desktop has no Places or Directions client, and the page already holds
 * Google's script with the production's key. Address suggestions, geocoding,
 * routes and a city's locality box are all asked of it and answered by a
 * `reply` carrying the request's id — so the forms, the directions panel and
 * the boundary dialogs get Google's answers without a key ever reaching
 * Kotlin.
 *
 * ## Replay
 *
 * The page may load after the view model has already said everything — the
 * tool opens before Chromium is up, and a page can reload. The latest theme,
 * scene and camera move are kept and restated on `map-ready`, so a map never
 * opens empty for what was already known.
 *
 * Every request times out ([REQUEST_TIMEOUT_MS]) and answers null: a page
 * that never booted (no key, no browser) must not hang a form.
 */
@Suppress("TooManyFunctions") // One function per page message; the bridge is the contract.
class MapCanvasClient(
    private val host: MapCanvasHost?,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<MapCanvasEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Page events, replies excluded. */
    val events: Flow<MapCanvasEvent> = _events.asSharedFlow()

    /** Whether there is a map at all — false on hosts without an embedded browser. */
    val available: Boolean get() = host != null

    private var started = false
    private var nextId = 1
    private val pending = mutableMapOf<Int, CompletableDeferred<CanvasReply?>>()

    private var lastScene: MapScene? = null
    private var lastTheme: CanvasTheme? = null
    private var lastCamera: String? = null

    /** Starts listening. Idempotent: the tool's window can open many times. */
    fun start() {
        val source = host ?: return
        if (started) return
        started = true
        scope.launch {
            source.messages.collect { message -> onMessage(message) }
        }
    }

    private fun onMessage(message: String) {
        MapCanvasWire.parseReply(message)?.let { reply ->
            pending.remove(reply.id)?.complete(reply)
            return
        }
        val event = MapCanvasWire.parseEvent(message) ?: return
        if (event is MapCanvasEvent.Ready) replay()
        _events.tryEmit(event)
    }

    private fun replay() {
        lastTheme?.let { run(MapCanvasWire.themeScript(it)) }
        lastScene?.let { run(MapCanvasWire.renderScript(it)) }
        lastCamera?.let { run(it) }
    }

    fun render(scene: MapScene) {
        if (scene == lastScene) return
        lastScene = scene
        run(MapCanvasWire.renderScript(scene))
    }

    fun theme(theme: CanvasTheme) {
        if (theme == lastTheme) return
        lastTheme = theme
        run(MapCanvasWire.themeScript(theme))
    }

    fun panTo(point: LatLng, zoom: Int? = null) = camera(MapCanvasWire.panScript(point, zoom))

    fun fitPoints(points: List<LatLng>) = camera(MapCanvasWire.fitPointsScript(points, SINGLE_POINT_ZOOM))

    fun fitCircle(circle: SceneCircle) = camera(MapCanvasWire.fitCircleScript(circle))

    fun focusMarker(id: String, zoom: Int) = camera(MapCanvasWire.focusScript(id, zoom))

    fun clearPreview() = run(MapCanvasWire.clearScript(preview = true))

    fun clearPlace() = run(MapCanvasWire.clearScript(place = true))

    fun clearRoute() = run(MapCanvasWire.clearScript(route = true))

    /** The address and its parts at [point]; null when nothing answers. */
    suspend fun reverseGeocode(point: LatLng): GeocodedPlace? =
        request("reverseGeocode", pointArgs(point))?.let(MapCanvasWire::geocoded)

    /** Where [query] is, biased to [bounds] when given. */
    suspend fun geocode(query: String, bounds: GeoBounds?): GeocodeOutcome {
        val reply = request(
            "geocode",
            buildJsonObject {
                put("query", query)
                bounds?.let { put("bounds", boundsJson(it)) }
            },
        ) ?: return GeocodeOutcome.NotFound("UNAVAILABLE")
        return MapCanvasWire.geocode(reply)
    }

    /**
     * Suggestions for [input]. [bias] or [bounds] keep them near the city;
     * [strict] refuses anything outside, as the web's `strictBounds` does.
     * Null when the map cannot answer; empty when it found nothing.
     */
    suspend fun predictions(
        input: String,
        kind: PlaceKind,
        bias: SearchBias? = null,
        bounds: GeoBounds? = null,
        strict: Boolean = false,
    ): List<PlacePrediction>? {
        if (input.isBlank()) return emptyList()
        val args = buildJsonObject {
            put("input", input)
            put("types", buildJsonArray { kind.wireTypes.forEach { add(JsonPrimitive(it)) } })
            bias?.let {
                put("biasLat", it.centre.lat)
                put("biasLng", it.centre.lng)
                put("biasRadius", it.radiusMeters)
            }
            bounds?.let { put("bounds", boundsJson(it)) }
            put("strict", strict)
        }
        return request("predictions", args)?.let(MapCanvasWire::predictions)
    }

    suspend fun placeDetails(placeId: String): PlaceDetails? =
        request("details", buildJsonObject { put("placeId", placeId) })?.let(MapCanvasWire::details)

    /** A driving route drawn on the map, and its summary; null when none was found. */
    suspend fun route(origin: LatLng, destination: LatLng): RouteInfo? = request(
        "route",
        buildJsonObject {
            put("origin", pointJson(origin))
            put("destination", pointJson(destination))
        },
    )?.let(MapCanvasWire::route)

    /** The locality box Google files [point] under — the city's real boundary. */
    suspend fun cityBounds(point: LatLng): GeoBounds? =
        request("cityBounds", pointArgs(point))?.let(MapCanvasWire::bounds)

    /** The browser's idea of where this machine is; usually null on a desktop. */
    suspend fun currentPosition(): LatLng? =
        request("currentPosition", buildJsonObject { })?.let(MapCanvasWire::position)

    private suspend fun request(op: String, args: JsonObject): CanvasReply? {
        if (host == null) return null
        val id = nextId++
        val answer = CompletableDeferred<CanvasReply?>()
        pending[id] = answer
        run(MapCanvasWire.requestScript(id, op, args))
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { answer.await() }.also { pending.remove(id) }
    }

    private fun camera(script: String) {
        // Sent now (the page queues it until its map exists) and kept for a
        // page that loads later, which would otherwise open on the fallback.
        lastCamera = script
        run(script)
    }

    private fun run(script: String) {
        host?.execute(script)
    }

    private fun pointArgs(point: LatLng) = buildJsonObject {
        put("lat", point.lat)
        put("lng", point.lng)
    }

    private fun pointJson(point: LatLng) = pointArgs(point)

    private fun boundsJson(bounds: GeoBounds) = buildJsonObject {
        put("north", bounds.north)
        put("south", bounds.south)
        put("east", bounds.east)
        put("west", bounds.west)
    }

    private companion object {
        const val EVENT_BUFFER = 64

        /** Long enough for a slow geocode; short enough that a dead page does not hold a form. */
        const val REQUEST_TIMEOUT_MS = 12_000L

        /** "Fit All" with one pin settles here (`handleFitMarkers`). */
        const val SINGLE_POINT_ZOOM = 15
    }
}
