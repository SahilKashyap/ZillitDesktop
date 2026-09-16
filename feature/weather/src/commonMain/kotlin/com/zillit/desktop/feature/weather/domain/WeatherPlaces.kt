package com.zillit.desktop.feature.weather.domain

/** One row of the city search — what Google Places suggests for what was typed. */
data class PlaceSuggestion(
    /** Google's `place_id`; the key that turns the row into coordinates. */
    val id: String,
    /** "Paris" — the bold line. */
    val primary: String,
    /** "France" — the muted line under it. */
    val secondary: String,
)

/**
 * Where a forecast can be for, answered the three ways the web answers it.
 *
 * The web's header is a Google Places search box, its first load is the
 * browser's own geolocation, and the city name over the reading is a reverse
 * geocode of whatever coordinates it ended up with (`WeatherMain.jsx`,
 * `getCityName`). A desktop has no browser geolocation and no Places widget,
 * so those three become three calls on this interface; the host answers them
 * with Google's REST endpoints and the production's own keys.
 */
interface WeatherPlaces {

    /** Whether a Places key exists — without one the search box has nothing behind it. */
    val canSearch: Boolean

    /** Places for what was typed so far; empty for a blank query or a refused key. */
    suspend fun suggest(query: String): List<PlaceSuggestion>

    /** The coordinates behind a suggestion, or null when Google will not say. */
    suspend fun resolve(suggestion: PlaceSuggestion): WeatherPlace?

    /** Where this machine is, named — or null when that cannot be told. */
    suspend fun locate(): WeatherPlace?

    /**
     * The city a point is in, as the web picks it: the first `locality`,
     * `administrative_area_level_1` or `administrative_area_level_2` component
     * Google returns for the point.
     */
    suspend fun cityName(lat: Double, lng: Double): String?
}

/** A host with no Google keys: the search is hidden and the map picker is the only way in. */
object NoWeatherPlaces : WeatherPlaces {
    override val canSearch: Boolean = false
    override suspend fun suggest(query: String): List<PlaceSuggestion> = emptyList()
    override suspend fun resolve(suggestion: PlaceSuggestion): WeatherPlace? = null
    override suspend fun locate(): WeatherPlace? = null
    override suspend fun cityName(lat: Double, lng: Double): String? = null
}
