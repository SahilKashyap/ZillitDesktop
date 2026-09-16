package com.zillit.desktop.feature.weather.ui

import com.zillit.desktop.feature.weather.domain.PlaceSuggestion
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.domain.WeatherReport

data class WeatherUiState(
    /** False until `project/tools` answers — not a denial. */
    val rightsResolved: Boolean = false,
    val canView: Boolean = false,
    /** Whether a key exists at all. Without one the tool says so plainly. */
    val configured: Boolean = true,
    /** Whether the host has a Places key behind the search box. */
    val canSearch: Boolean = false,
    val place: WeatherPlace? = null,
    val report: WeatherReport? = null,
    /** The forecast call is in flight. */
    val loading: Boolean = false,
    /** Working out where this machine is — the web's "Loading…" before its first reading. */
    val locating: Boolean = false,
    val error: String? = null,
    val search: PlaceSearch = PlaceSearch(),
) {
    val hasNoAccess: Boolean get() = rightsResolved && !canView

    /** Something is on its way and there is nothing to show yet. */
    val busy: Boolean get() = loading || locating
}

/** The city search box and what hangs under it. */
data class PlaceSearch(
    val query: String = "",
    val suggestions: List<PlaceSuggestion> = emptyList(),
    /** Google is being asked; the dropdown shows a spinner rather than "nothing found". */
    val searching: Boolean = false,
    /** A suggestion is being turned into coordinates. */
    val resolving: PlaceSuggestion? = null,
) {
    /** The dropdown is worth drawing: there is a query and either rows or a wait. */
    val open: Boolean get() = query.isNotBlank() && (suggestions.isNotEmpty() || searching)
}

sealed interface WeatherEvent {
    data object Load : WeatherEvent

    /** Every keystroke in the search box. Emptying it goes back to this machine's location, as the web does. */
    data class SearchChanged(val query: String) : WeatherEvent

    /** A row of the dropdown chosen. */
    data class SuggestionPicked(val suggestion: PlaceSuggestion) : WeatherEvent

    /** Return or a click away: close the dropdown, keep the text. */
    data object SearchDismissed : WeatherEvent

    /** The web's first load — where this machine is. */
    data object UseMyLocation : WeatherEvent

    /** A place chosen in the map picker. */
    data class PlacePicked(val place: WeatherPlace) : WeatherEvent
    data object Refresh : WeatherEvent
    data object DismissError : WeatherEvent
}
