package com.zillit.desktop

import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.weather.data.GooglePlacesGateway
import com.zillit.desktop.feature.weather.data.WeatherRepositoryImpl
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.ui.OpenWeatherIconLoader
import com.zillit.desktop.feature.weather.ui.WeatherClock
import com.zillit.desktop.feature.weather.ui.WeatherToolProvider
import com.zillit.desktop.feature.weather.ui.WeatherViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Weather tool's host wiring.
 *
 * Everything here rides the plain HTTP client, never the API one: OpenWeatherMap
 * and Google are third parties and have no business seeing a Zillit session
 * header — the same rule the map tiles and the static maps follow.
 *
 * The Google keys are the production's own, from remote config, read per call
 * so a refresh is seen: the Places key for the search and the city name (the
 * web's `places_secret` in `getCityName`), the Maps key for "where am I" (the
 * Maps tool's locator uses the same endpoint).
 */
internal fun AppGraph.Ready.buildWeather(permissions: () -> ProjectPermissions) = WeatherViewModel(
    repository = WeatherRepositoryImpl(httpClient, config.weatherApiKey),
    places = GooglePlacesGateway(
        httpClient = httpClient,
        placesKey = { remoteConfigRepository.current()?.googlePlacesKey },
        mapsKey = { remoteConfigRepository.current()?.googleMapsKey },
    ),
    viewer = permissions,
    savedPlace = { readWeatherPlace() },
    savePlace = { place -> writeWeatherPlace(place) },
)

internal fun AppGraph.Ready.weatherProvider(viewModel: WeatherViewModel) = WeatherToolProvider(
    viewModel = viewModel,
    clock = WeatherClock(time = ::placeTime, day = ::placeDay),
    icons = OpenWeatherIconLoader(httpClient),
)

/**
 * "6:14 AM" in the place's own zone — the web's
 * `toLocaleTimeString('en-US', { hour12: true, timeZone })` for sunrise and
 * sunset (`CurrentWeather.jsx:38`), with its leading zero dropped as the hourly
 * rail drops it (`HourlyForecast.jsx:21`).
 */
private fun placeTime(millis: Long, zone: String): String =
    if (millis <= 0) "—" else TIME.format(Instant.ofEpochMilli(millis).atZone(zoneOf(zone)))

/** "Mon" — or "Today" when that day is the one the place is in right now. */
private fun placeDay(millis: Long, zone: String): String {
    if (millis <= 0) return "—"
    val zoneId = zoneOf(zone)
    val day = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
    return if (day == LocalDate.now(zoneId)) "Today" else DAY.format(day)
}

/** OpenWeather's IANA name, or this machine's zone for a name Java does not know. */
private fun zoneOf(zone: String): ZoneId =
    runCatching { ZoneId.of(zone) }.getOrElse { ZoneId.systemDefault() }

/**
 * The last place looked at, kept per production.
 *
 * Three lines rather than JSON: a place name can hold any punctuation, but
 * never a newline, which is the same bargain the chat favourites key makes.
 */
private suspend fun AppGraph.Ready.readWeatherPlace(): WeatherPlace? =
    preferences.get(ZillitPreferences.WeatherPlace)
        .takeIf { it.isNotBlank() }
        ?.lines()
        ?.takeIf { it.size >= PLACE_LINES }
        ?.let { lines ->
            val lat = lines[1].toDoubleOrNull()
            val lng = lines[2].toDoubleOrNull()
            if (lat == null || lng == null) null else WeatherPlace(lines[0], lat, lng)
        }

private suspend fun AppGraph.Ready.writeWeatherPlace(place: WeatherPlace) {
    preferences.set(
        ZillitPreferences.WeatherPlace,
        listOf(place.name.replace("\n", " "), place.lat.toString(), place.lng.toString())
            .joinToString("\n"),
    )
}

private const val PLACE_LINES = 3
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
