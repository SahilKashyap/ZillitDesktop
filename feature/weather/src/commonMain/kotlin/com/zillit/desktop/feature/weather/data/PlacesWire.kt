package com.zillit.desktop.feature.weather.data

import com.zillit.desktop.feature.weather.domain.PlaceSuggestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What Google's Places, Geocoding and Geolocation endpoints answer, read the
 * way the web reads them.
 *
 * Pure functions over the JSON text so they are covered by this module's own
 * tests: the shapes below are the contract with Google, and a field that moves
 * should fail a test here rather than blank the city name in the app.
 */
internal object PlacesWire {

    private val json = Json { ignoreUnknownKeys = true }

    /** `place/autocomplete/json` → the rows of the dropdown, in Google's order. */
    fun parseSuggestions(body: String): List<PlaceSuggestion> {
        val predictions = obj(body)?.get("predictions") as? JsonArray ?: return emptyList()
        return predictions.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row.str("place_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val structured = row["structured_formatting"] as? JsonObject
            val description = row.str("description").orEmpty()
            PlaceSuggestion(
                id = id,
                primary = structured?.str("main_text")?.takeIf { it.isNotBlank() } ?: description,
                secondary = structured?.str("secondary_text").orEmpty(),
            )
        }
    }

    /** `place/details/json` → the point, or null when the answer has none. */
    fun parseDetailsLocation(body: String): LatLng? {
        val result = obj(body)?.get("result") as? JsonObject ?: return null
        return result.location()
    }

    /**
     * `geocode/json?latlng=` → the city, as `getCityName` picks it: the first
     * address component across every result whose types include one of the
     * three the web accepts (`WeatherMain.jsx:98-104`).
     */
    fun parseCity(body: String): String? {
        val results = obj(body)?.get("results") as? JsonArray ?: return null
        return results.asSequence()
            .mapNotNull { it as? JsonObject }
            .flatMap { (it["address_components"] as? JsonArray)?.asSequence().orEmpty() }
            .mapNotNull { it as? JsonObject }
            .firstOrNull { component ->
                val types = (component["types"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                types.any { it in CITY_TYPES }
            }
            ?.str("long_name")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Google refused the key itself — a referrer-restricted key called from
     * no browser — as opposed to finding nothing. A 200 with this status is
     * the one answer worth retrying with the other key.
     */
    fun isDenied(body: String): Boolean = obj(body)?.str("status") == "REQUEST_DENIED"

    /** `geolocation/v1/geolocate` → where the network says this machine is. */
    fun parseGeolocation(body: String): LatLng? {
        val location = obj(body)?.get("location") as? JsonObject ?: return null
        val lat = location.dbl("lat") ?: return null
        val lng = location.dbl("lng") ?: return null
        return LatLng(lat, lng)
    }

    private fun JsonObject.location(): LatLng? {
        val location = (this["geometry"] as? JsonObject)?.get("location") as? JsonObject
        val lat = location?.dbl("lat")
        val lng = location?.dbl("lng")
        return if (lat != null && lng != null) LatLng(lat, lng) else null
    }

    private fun obj(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    /** The component types the web's `getCityName` accepts, in its order of preference. */
    private val CITY_TYPES = setOf("locality", "administrative_area_level_1", "administrative_area_level_2")
}

/** A point on the map — only ever a pair, so no class of its own beyond this. */
internal data class LatLng(val lat: Double, val lng: Double)
