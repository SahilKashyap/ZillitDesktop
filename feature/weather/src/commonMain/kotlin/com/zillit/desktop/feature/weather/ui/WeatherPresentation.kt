package com.zillit.desktop.feature.weather.ui

import com.zillit.desktop.feature.weather.domain.WeatherCondition
import kotlin.math.roundToInt

/**
 * OpenWeather's icon codes, drawn in our own hand.
 *
 * Their artwork is a PNG per code fetched from their CDN; a glyph costs no
 * request, works offline, and matches the rest of the app. The code's last
 * letter is `d` or `n` — day or night — and the first two digits are the
 * condition (`01` clear … `13` snow, `50` mist).
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

private const val UV_MODERATE = 3.0
private const val UV_HIGH = 6.0
private const val UV_VERY_HIGH = 8.0
private const val UV_EXTREME = 11.0
