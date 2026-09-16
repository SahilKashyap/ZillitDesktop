package com.zillit.desktop.feature.weather.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zillit.desktop.core.localization.LabelDictionary
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.feature.weather.domain.WeatherCondition
import kotlin.math.roundToInt

/**
 * OpenWeather's icon codes, drawn in our own hand.
 *
 * The fallback for when their artwork has not arrived or cannot: a glyph costs
 * no request and works offline. The code's last letter is `d` or `n` — day or
 * night — and the first two digits are the condition (`01` clear … `13` snow,
 * `50` mist).
 */
internal fun WeatherCondition.glyph(): String = when (icon.take(2)) {
    "01" -> if (isNight()) "☽" else "☀"
    "02", "03" -> "⛅"
    "04" -> "☁"
    "09", "10" -> "☔"
    "11" -> "⚡"
    "13" -> "❄"
    "50" -> "▒"
    else -> "☁"
}

internal fun WeatherCondition.isNight(): Boolean = icon.endsWith("n")

/** "21°" — the way every weather app writes it. */
internal fun Double.asDegrees(): String = "${roundToInt()}°"

/** Sentence case for OpenWeather's lowercase descriptions ("light rain"). */
internal fun String.asSummary(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/** Visibility the way the web shows it — miles, from the metres the service sends. */
internal fun Int.metresAsMiles(): Int = (this / METRES_PER_MILE).roundToInt()

/**
 * The UV index in the words a person acts on, as the health scales band it:
 * 0–2 low, 3–5 moderate, 6–7 high, 8–10 very high, 11+ extreme.
 */
internal fun Double.uvBand(): String = when {
    this < UV_MODERATE -> "Low"
    this < UV_HIGH -> "Moderate"
    this < UV_VERY_HIGH -> "High"
    this < UV_EXTREME -> "Very high"
    else -> "Extreme"
}

/**
 * The words on the screen, from the same label keys the web reads
 * (`getLocalStaticData` in the weather components), with the English the web
 * ships as the fallback for a dictionary that has not caught up.
 */
internal class WeatherCopy(private val dictionary: LabelDictionary = LabelDictionary.Empty) {

    fun t(key: String, fallback: String): String =
        dictionary.exact(key)?.takeIf { it.isNotBlank() } ?: fallback

    val title get() = t("weather_tool_label", "Weather")
    val searchCity get() = t("search_city", "Search City")
    val hourly get() = t("hourly_forecast", "Hourly Forecast")
    val sevenDay get() = t("Seven_Day_Forecast", "7-Day Forecast")
    val uv get() = t("UV_text", "UV")
    val feelsLike get() = t("Feels_like_text", "Feels Like")
    val humidity get() = t("humidity_text", "Humidity")
    val wind get() = t("wind_text", "Wind")
    val pressure get() = t("Air_pressure_text", "Air Pressure")
    val visibility get() = t("visibility_text", "Visibility")
    val sunrise get() = t("Sunrise", "Sunrise")
    val sunset get() = t("Sunset", "Sunset")
    val loading get() = t("Loading", "Loading")
}

/** Recomposes when a language lands, so nothing on screen keeps its fallback. */
@Composable
internal fun rememberWeatherCopy(): WeatherCopy {
    val dictionary by Labels.dictionary.collectAsState()
    return remember(dictionary) { WeatherCopy(dictionary) }
}

private const val METRES_PER_MILE = 1609.34
private const val UV_MODERATE = 3.0
private const val UV_HIGH = 6.0
private const val UV_VERY_HIGH = 8.0
private const val UV_EXTREME = 11.0
