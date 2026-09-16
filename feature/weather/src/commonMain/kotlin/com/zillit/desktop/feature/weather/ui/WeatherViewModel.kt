package com.zillit.desktop.feature.weather.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.weather.domain.NoWeatherPlaces
import com.zillit.desktop.feature.weather.domain.PlaceSuggestion
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.domain.WeatherPlaces
import com.zillit.desktop.feature.weather.domain.WeatherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The Weather tool — the web's `WeatherMain` flow, on a desktop.
 *
 * The web opens on the browser's geolocation, offers a Places search box, and
 * names whatever it landed on with a reverse geocode. Here the first load is
 * the place remembered for this production (a unit shoots in the same few
 * places for weeks, and the network's idea of "here" is the office), and only
 * failing that the network's location; the search and the naming are the
 * web's, through [WeatherPlaces].
 */
class WeatherViewModel(
    private val repository: WeatherRepository,
    private val places: WeatherPlaces = NoWeatherPlaces,
    private val viewer: () -> ProjectPermissions = { ProjectPermissions(emptyList()) },
    /** The place last looked at, kept across sessions. */
    private val savedPlace: suspend () -> WeatherPlace? = { null },
    private val savePlace: suspend (WeatherPlace) -> Unit = {},
) : ZillitViewModel<WeatherUiState, WeatherEvent, Nothing>(WeatherUiState()) {

    private var suggesting: Job? = null
    private var fetching: Job? = null

    override fun onEvent(event: WeatherEvent) {
        when (event) {
            WeatherEvent.Load -> load()
            is WeatherEvent.SearchChanged -> searchChanged(event.query)
            is WeatherEvent.SuggestionPicked -> pickSuggestion(event.suggestion)
            WeatherEvent.SearchDismissed -> {
                suggesting?.cancel()
                setState { copy(search = search.copy(suggestions = emptyList(), searching = false)) }
            }

            WeatherEvent.UseMyLocation -> locate()
            is WeatherEvent.PlacePicked -> choose(event.place)
            WeatherEvent.Refresh -> currentState.place?.let(::fetch)
            WeatherEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun load() {
        val permissions = viewer()
        val resolved = permissions.tools.isNotEmpty()
        val tool = permissions.tools.firstOrNull { it.identifier == TOOL_IDENTIFIER }
        setState {
            copy(
                rightsResolved = resolved,
                canView = tool?.canView == true,
                configured = repository.isConfigured,
                canSearch = places.canSearch,
            )
        }
        // Nothing to ask for without rights or a key.
        if (!resolved || tool?.canView != true || !repository.isConfigured) return
        if (currentState.place != null) return
        launch {
            val remembered = savedPlace()
            if (remembered != null) {
                setState { copy(place = remembered) }
                fetch(remembered)
            } else {
                locate()
            }
        }
    }

    /** The web's `getCurrentLocation`: where this machine is, then its forecast. */
    private fun locate() {
        if (currentState.locating) return
        setState { copy(locating = true, error = null) }
        launch {
            val here = places.locate()
            setState { copy(locating = false) }
            if (here == null) {
                // The web alerts "please enable location services"; a desktop
                // has no switch to flip, so it says what to do instead.
                setState { copy(error = NOT_LOCATED) }
            } else {
                choose(here)
            }
        }
    }

    private fun searchChanged(query: String) {
        suggesting?.cancel()
        setState { copy(search = search.copy(query = query, searching = query.isNotBlank())) }
        if (query.isBlank()) {
            setState { copy(search = search.copy(suggestions = emptyList())) }
            // `handleSearchText`: an emptied box goes back to "here".
            locate()
            return
        }
        suggesting = launch {
            // A pause, not a call per keystroke — Google bills each one.
            delay(SUGGEST_DEBOUNCE_MS)
            val rows = places.suggest(query)
            // A slower answer to an older query must not land on a newer one.
            if (currentState.search.query != query) return@launch
            setState { copy(search = search.copy(suggestions = rows, searching = false)) }
        }
    }

    private fun pickSuggestion(suggestion: PlaceSuggestion) {
        suggesting?.cancel()
        setState {
            copy(
                search = search.copy(
                    query = suggestion.primary,
                    suggestions = emptyList(),
                    searching = false,
                    resolving = suggestion,
                ),
                error = null,
            )
        }
        launch {
            val place = places.resolve(suggestion)
            setState { copy(search = search.copy(resolving = null)) }
            if (place == null) setState { copy(error = NOT_RESOLVED) } else choose(place)
        }
    }

    /**
     * A place is chosen: it is shown and asked about at once, remembered, and —
     * as the web's `getCityName` does for every reading — renamed to the city
     * it is in once Google says which that is.
     */
    private fun choose(place: WeatherPlace) {
        setState { copy(place = place, error = null) }
        launch { savePlace(place) }
        fetch(place)
        launch {
            val city = places.cityName(place.lat, place.lng) ?: return@launch
            val named = place.copy(name = city)
            // Only if the reader is still looking at the same point.
            if (currentState.place?.samePoint(place) != true || city == place.name) return@launch
            setState { copy(place = named, report = report?.copy(place = named)) }
            savePlace(named)
        }
    }

    private fun fetch(place: WeatherPlace) {
        fetching?.cancel()
        setState { copy(loading = true, error = null) }
        fetching = launch {
            when (val answer = repository.forecast(place)) {
                is ZillitResult.Success -> setState {
                    // Keep the city name a concurrent geocode may already have set.
                    val shown = this.place?.takeIf { it.samePoint(place) } ?: place
                    copy(loading = false, report = answer.data.copy(place = shown))
                }

                is ZillitResult.Failure ->
                    setState { copy(loading = false, error = answer.error.localised()) }
            }
        }
    }

    private fun WeatherPlace.samePoint(other: WeatherPlace): Boolean = lat == other.lat && lng == other.lng

    companion object {
        const val TOOL_IDENTIFIER = "weather_tool"

        /** Google's autocomplete is billed per request; a short pause folds a word into one. */
        const val SUGGEST_DEBOUNCE_MS = 300L

        const val NOT_LOCATED = "Your location could not be found. Search for a city instead."
        const val NOT_RESOLVED = "That place could not be located. Try another."
    }
}
