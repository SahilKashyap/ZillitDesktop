package com.zillit.desktop

import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.weather.data.WeatherRepositoryImpl
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.ui.WeatherToolProvider
import com.zillit.desktop.feature.weather.ui.WeatherViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Weather tool's host wiring.
 *
 * The forecast rides the plain HTTP client, never the API one: OpenWeatherMap
 * is a third party and has no business seeing a Zillit session header — the
 * same rule the map tiles and the static maps follow.
 */
internal fun AppGraph.Ready.buildWeather(permissions: () -> ProjectPermissions) = WeatherViewModel(
    repository = WeatherRepositoryImpl(httpClient, config.weatherApiKey),
    viewer = permissions,
    savedPlace = { readWeatherPlace() },
    savePlace = { place -> writeWeatherPlace(place) },
)

internal fun AppGraph.Ready.weatherProvider(viewModel: WeatherViewModel) = WeatherToolProvider(
    viewModel = viewModel,
    formatTime = ::clockTime,
    formatDay = ::weekDay,
)

/** "06:14" on this machine's clock. */
private fun clockTime(millis: Long): String =
    if (millis <= 0) "\u2014" else TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "Tue 26" — enough to read a week down a column. */
private fun weekDay(millis: Long): String =
    if (millis <= 0) "\u2014" else DAY.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

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
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d")
