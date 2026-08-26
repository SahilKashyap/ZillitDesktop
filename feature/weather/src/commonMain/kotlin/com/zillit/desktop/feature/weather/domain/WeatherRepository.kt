package com.zillit.desktop.feature.weather.domain

import com.zillit.desktop.core.common.ZillitResult

/** The forecast service. */
interface WeatherRepository {

    /** Whether a key is configured at all — without one the tool has nothing to show. */
    val isConfigured: Boolean

    /** The forecast for a place, or the reason there is none. */
    suspend fun forecast(place: WeatherPlace): ZillitResult<WeatherReport>
}
