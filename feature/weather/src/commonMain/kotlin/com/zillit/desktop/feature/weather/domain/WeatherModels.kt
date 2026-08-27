package com.zillit.desktop.feature.weather.domain

/** Where the forecast is for. */
data class WeatherPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
)

/**
 * One weather condition, as OpenWeather names it.
 *
 * [icon] is their code (`10d`, `01n`); the screen maps it to one of ours
 * rather than fetching their artwork, so the tool draws in the app's own hand
 * and works with no network for the picture.
 */
data class WeatherCondition(
    val summary: String,
    val icon: String,
)

/** Right now, where the place is. */
data class CurrentWeather(
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int,
    val pressureHpa: Int,
    val windKph: Double,
    val uvIndex: Double,
    val visibilityMetres: Int,
    val sunriseMillis: Long,
    val sunsetMillis: Long,
    val condition: WeatherCondition,
)

/** One hour ahead. */
data class HourlyPoint(
    val atMillis: Long,
    val temperatureC: Double,
    val condition: WeatherCondition,
)

/** One day ahead: the range, not a single number. */
data class DailyPoint(
    val atMillis: Long,
    val minC: Double,
    val maxC: Double,
    val windKph: Double,
    val condition: WeatherCondition,
)

/** Everything the screen shows, for one place. */
data class WeatherReport(
    val place: WeatherPlace,
    val timezone: String,
    val current: CurrentWeather,
    val hourly: List<HourlyPoint>,
    val daily: List<DailyPoint>,
)
