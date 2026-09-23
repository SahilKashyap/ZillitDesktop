package com.zillit.desktop.core.locationpicker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * What the picker page announces back to Kotlin.
 *
 * The same vocabulary the Maps tool's page speaks (`feature:maps`'s
 * `MapCanvasWire`), minus the pins and plus [Picked] — the picker has one
 * marker and the only thing it has to say is where that marker now is.
 */
sealed interface LocationPickerEvent {

    /** Google's map object exists; anything Kotlin pushed before now can be replayed. */
    data object MapReady : LocationPickerEvent

    /**
     * The pin moved, or a search result was chosen. Sent on *every* change, so
     * Kotlin always holds what "Use this location" would return — the web
     * emits per keystroke and per drag for the same reason
     * (transportationHub/common/LocationPicker.jsx:129-131).
     */
    data class Picked(val location: PickedLocation) : LocationPickerEvent

    /** The map cannot be shown: a refused key, a script that would not load. */
    data class Failed(val message: String) : LocationPickerEvent
}

/**
 * The colours the page paints its own chrome with.
 *
 * Hex strings rather than Compose `Color`s so this file — and its tests — stay
 * free of the UI toolkit; the host converts once, on the way down. See
 * `picker.html` for why the search box is the page's and not Compose's.
 */
data class PickerTheme(
    val background: String,
    val surface: String,
    val text: String,
    val accent: String,
    val border: String,
    val isDark: Boolean,
)

/**
 * The wire between Kotlin and the picker page (`mapengine/picker.html`).
 *
 * Pure functions, for the reason `MapCanvasWire` is: the CEF half cannot run in
 * a unit test, so event decoding, script construction and string escaping live
 * here and the KCEF class stays a transport. Event shapes are the contract with
 * `picker.html`; change either side only with the other.
 *
 * Kept in `core:locationpicker` rather than beside the host so the parser is
 * covered by this module's own test task, and so nothing about the picker's
 * protocol is stranded in an application module that has no tests of its own.
 */
object LocationPickerWire {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * True for the page shell's own announcement — the page has loaded but
     * holds no map yet, because it holds no key yet. The transport answers it
     * by pushing the key down; it is not a [LocationPickerEvent].
     */
    fun isPageReady(message: String): Boolean = obj(message)?.str("type") == TYPE_PAGE_READY

    /**
     * One JSON line from the page, as the event it means — or null for
     * [isPageReady], unknown types, and malformed JSON. Never throws.
     */
    fun parse(message: String): LocationPickerEvent? {
        val body = obj(message) ?: return null
        return when (body.str("type")) {
            "map-ready" -> LocationPickerEvent.MapReady
            "picked" -> body.picked()
            // Google's `gm_authFailure` hook — a referrer-restricted key
            // rejecting the file:// origin lands here. Without it the picker
            // is a silent grey slab with a search box on it.
            "auth-failed" -> LocationPickerEvent.Failed(str(S.desktop_location_picker_maps_key_rejected))
            "error" -> LocationPickerEvent.Failed(body.str("message") ?: str(S.desktop_location_picker_error))
            else -> null
        }
    }

    /**
     * A pick is only a pick with both coordinates: a payload missing either is
     * dropped rather than turned into a pin at (0, 0) in the Gulf of Guinea.
     */
    private fun JsonObject.picked(): LocationPickerEvent? {
        val lat = dbl("lat") ?: return null
        val lng = dbl("lng") ?: return null
        val address = str("address").orEmpty()
        return LocationPickerEvent.Picked(
            PickedLocation(
                // The web's own fallback order: the place's name, else the
                // address (boxScheduleV2/components/PlacePicker.jsx:111).
                name = str("name")?.takeIf(String::isNotBlank) ?: address,
                address = address,
                lat = lat,
                lng = lng,
            ),
        )
    }

    // ── Kotlin → page ───────────────────────────────────────────────────

    /**
     * Hands the decrypted Maps key and the app's colours to the loaded page,
     * which then injects Google's script itself — the key never touches the
     * extracted file on disk, only the page runtime. Never log the returned
     * script.
     */
    fun bootScript(key: String, themeJson: String): String =
        "zillitPicker.boot(${quote(key)}, ${quote(themeJson)})"

    /** The page's chrome colours, as the JSON string [bootScript] carries. */
    fun themeJson(theme: PickerTheme): String = buildJsonObject {
        put("background", theme.background)
        put("surface", theme.surface)
        put("text", theme.text)
        put("accent", theme.accent)
        put("border", theme.border)
        put("dark", theme.isDark)
    }.toString()

    fun centerScript(lat: Double, lng: Double, zoom: Int): String =
        "zillitPicker.center($lat, $lng, $zoom)"

    fun setPinScript(lat: Double, lng: Double): String = "zillitPicker.setPin($lat, $lng)"

    /**
     * Puts a saved address back in the page's own search box on reopen.
     *
     * Quoted for the same reason the key is: an address arrives off the wire,
     * and one with a quote in it would otherwise end the string literal and
     * start being code.
     */
    fun searchTextScript(text: String): String = "zillitPicker.setSearchText(${quote(text)})"

    /**
     * Empties the box and takes the pin off the map.
     *
     * The browser outlives any one opening — it is parked in a hidden window
     * between them — so without this the next place would start inside the
     * last one's search text and pin.
     */
    const val resetScript: String = "zillitPicker.reset()"

    /** A JS string literal that cannot break out of itself. */
    private fun quote(value: String): String =
        json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private fun obj(message: String): JsonObject? =
        runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.dbl(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private const val TYPE_PAGE_READY = "ready"
}
