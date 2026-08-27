package com.zillit.desktop.feature.weather.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.domain.WeatherRepository

/**
 * The Weather tool.
 *
 * The web reads the browser's own geolocation and offers a search box; a
 * desktop has no geolocation worth trusting for a unit that is somewhere else
 * entirely, so the place is chosen on the map — and remembered, because a
 * production shoots in the same few places for weeks.
 */
class WeatherViewModel(
    private val repository: WeatherRepository,
    private val viewer: () -> ProjectPermissions = { ProjectPermissions(emptyList()) },
    /** The place last looked at, kept across sessions. */
    private val savedPlace: suspend () -> WeatherPlace? = { null },
    private val savePlace: suspend (WeatherPlace) -> Unit = {},
) : ZillitViewModel<WeatherUiState, WeatherEvent, Nothing>(WeatherUiState()) {

    override fun onEvent(event: WeatherEvent) {
        when (event) {
            WeatherEvent.Load -> load()
            is WeatherEvent.PlacePicked -> {
                setState { copy(place = event.place, error = null) }
                launch { savePlace(event.place) }
                fetch(event.place)
            }

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
            )
        }
        // Nothing to ask for without rights, a key, or somewhere to ask about.
        if (!resolved || tool?.canView != true || !repository.isConfigured) return
        launch {
            val place = currentState.place ?: savedPlace() ?: return@launch
            setState { copy(place = place) }
            fetch(place)
        }
    }

    private fun fetch(place: WeatherPlace) {
        setState { copy(loading = true, error = null) }
        launch {
            when (val answer = repository.forecast(place)) {
                is ZillitResult.Success ->
                    setState { copy(loading = false, report = answer.data) }

                is ZillitResult.Failure ->
                    setState { copy(loading = false, error = answer.error.localised()) }
            }
        }
    }

    companion object {
        const val TOOL_IDENTIFIER = "weather_tool"
    }
}
