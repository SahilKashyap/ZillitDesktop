package com.zillit.desktop.feature.weather.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.weather.domain.PlaceSuggestion
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.domain.WeatherPlaces
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlin.random.Random

/**
 * Google's Places, Geocoding and Geolocation REST endpoints, with the
 * production's own keys — the desktop's stand-in for the web's browser-side
 * Places widget and `navigator.geolocation`.
 *
 * **On the plain client, never the API one.** Google is a third party and the
 * Zillit session headers have no business on its servers — the same rule the
 * forecast, the map tiles and the static maps follow.
 *
 * The keys are read per call rather than captured: remote config lands after
 * the graph is built and can be refreshed, and a gateway that captured a null
 * at construction would stay blind for the session.
 *
 * Search and the reverse geocode use the Places key, as the web's `getCityName`
 * does (`places_secret`); geolocation uses the Maps key, as the Maps tool's
 * locator does. Each falls back to the other so one missing key does not take
 * the whole feature down — and a Places key Google refuses outright (a
 * referrer-restricted key, which a browser satisfies and this app cannot) is
 * retried with the Maps key, which the Maps tool already calls from here.
 */
class GooglePlacesGateway(
    private val httpClient: HttpClient,
    private val placesKey: suspend () -> String?,
    private val mapsKey: suspend () -> String?,
) : WeatherPlaces {

    /**
     * One token per search session, as Google bills autocomplete: every
     * keystroke under the same token, then the details call closes it. Rotated
     * after each resolve.
     */
    private var sessionToken: String = newToken()

    override val canSearch: Boolean get() = true

    override suspend fun suggest(query: String): List<PlaceSuggestion> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val body = fetchWithSearchKey("autocomplete") { key ->
            "$PLACES/autocomplete/json?input=${trimmed.encodeURLParameter()}&sessiontoken=$sessionToken&key=$key"
        }
        return body?.let(PlacesWire::parseSuggestions).orEmpty()
    }

    override suspend fun resolve(suggestion: PlaceSuggestion): WeatherPlace? {
        val body = fetchWithSearchKey("place details") { key ->
            "$PLACES/details/json?place_id=${suggestion.id.encodeURLParameter()}" +
                "&fields=geometry&sessiontoken=$sessionToken&key=$key"
        }
        // The details call ends the session whether or not it answered.
        sessionToken = newToken()
        val point = body?.let(PlacesWire::parseDetailsLocation) ?: return null
        return WeatherPlace(name = suggestion.primary, lat = point.lat, lng = point.lng)
    }

    override suspend fun locate(): WeatherPlace? {
        val key = mapsKey()?.takeIf { it.isNotBlank() } ?: placesKey()?.takeIf { it.isNotBlank() } ?: return null
        val body = runCatching {
            val response = httpClient.post("$GEOLOCATE?key=$key") {
                contentType(ContentType.Application.Json)
                setBody(GEOLOCATE_BODY)
            }
            if (!response.status.isSuccess()) {
                ZillitLog.w(TAG) { "geolocate refused: ${response.status.value}" }
                return null
            }
            response.bodyAsText()
        }.onFailure { ZillitLog.w(TAG) { "geolocate failed: ${it::class.simpleName}" } }.getOrNull()
        val point = body?.let(PlacesWire::parseGeolocation) ?: return null
        return WeatherPlace(
            name = cityName(point.lat, point.lng) ?: CURRENT_LOCATION,
            lat = point.lat,
            lng = point.lng,
        )
    }

    override suspend fun cityName(lat: Double, lng: Double): String? =
        fetchWithSearchKey("reverse geocode") { key -> "$GEOCODE?latlng=$lat,$lng&key=$key" }
            ?.let(PlacesWire::parseCity)

    /**
     * The Places key first, then the Maps key when there is no Places key or
     * Google refused it. Null when neither key exists or both are refused.
     */
    private suspend fun fetchWithSearchKey(what: String, url: (key: String) -> String): String? {
        val places = placesKey()?.takeIf { it.isNotBlank() }
        val maps = mapsKey()?.takeIf { it.isNotBlank() }
        val first = places ?: maps ?: return null
        val body = fetch(url(first), what)
        if (body == null || !PlacesWire.isDenied(body)) return body
        val second = maps?.takeIf { it != first } ?: return null
        ZillitLog.w(TAG) { "$what refused the Places key; retrying with the Maps key" }
        return fetch(url(second), what)
    }

    /** The body of a successful answer, or null — never an exception. The key is never logged. */
    private suspend fun fetch(url: String, what: String): String? = runCatching {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            ZillitLog.w(TAG) { "$what refused: ${response.status.value}" }
            return null
        }
        response.bodyAsText()
    }.onFailure { ZillitLog.w(TAG) { "$what failed: ${it::class.simpleName}" } }.getOrNull()

    private fun newToken(): String = Random.nextLong().toULong().toString(RADIX)

    private companion object {
        const val TAG = "WeatherPlaces"
        const val PLACES = "https://maps.googleapis.com/maps/api/place"
        const val GEOCODE = "https://maps.googleapis.com/maps/api/geocode/json"
        const val GEOLOCATE = "https://www.googleapis.com/geolocation/v1/geolocate"
        const val GEOLOCATE_BODY = "{\"considerIp\":true}"
        val CURRENT_LOCATION: String get() = str(S.desktop_weather_current_location)
        const val RADIX = 36
    }
}
