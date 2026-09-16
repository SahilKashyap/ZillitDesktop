package com.zillit.desktop.feature.weather.ui

/**
 * How an instant reads in the place's own day — the host owns the clock and
 * the zone tables. [zone] is OpenWeather's IANA name (`Europe/London`); blank
 * means "this machine's".
 */
data class WeatherClock(
    /** "6:14 AM", as the web's `toLocaleTimeString('en-US', …)` writes it. */
    val time: (millis: Long, zone: String) -> String = { _, _ -> "" },
    /** "Mon", or "Today" for the day the place is in right now. */
    val day: (millis: Long, zone: String) -> String = { _, _ -> "" },
)
