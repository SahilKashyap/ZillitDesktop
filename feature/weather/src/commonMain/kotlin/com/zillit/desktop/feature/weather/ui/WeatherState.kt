package com.zillit.desktop.feature.weather.ui

import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.domain.WeatherReport

data class WeatherUiState(
    /** False until `project/tools` answers — not a denial. */
    val rightsResolved: Boolean = false,
    val canView: Boolean = false,
    /** Whether a key exists at all. Without one the tool says so plainly. */
    val configured: Boolean = true,
    val place: WeatherPlace? = null,
    val report: WeatherReport? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasNoAccess: Boolean get() = rightsResolved && !canView
}

sealed interface WeatherEvent {
    data object Load : WeatherEvent
    /** A place chosen in the map picker. */
    data class PlacePicked(val place: WeatherPlace) : WeatherEvent
    data object Refresh : WeatherEvent
    data object DismissError : WeatherEvent
}
