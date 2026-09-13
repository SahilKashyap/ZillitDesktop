package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.maps.domain.CityDraft
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.PlaceKind
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.pickAutoCity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Cities: the list and its order, the auto-selection ladder, the Cities panel
 * (`common/Sidebar.jsx`) and the Add City dialog (`cities/AddCityModal.jsx`).
 */
internal class CityController(private val store: MapStore) {

    private var searchJob: Job? = null
    private var addSearchJob: Job? = null

    /**
     * A pin waiting on the city being created for it — boundary spec §6.1, the
     * "Create Another City & Pin Location" round trip. Consumed when the city
     * is saved, cleared when the dialog is left: a stale pending pin would
     * fire on the next city created from any path.
     */
    var pendingPin: PendingPin? = null
        private set

    data class PendingPin(val point: LatLng, val name: String)

    fun onEvent(event: MapEvent.Cities) {
        when (event) {
            MapEvent.Cities.Open -> {
                store.hooks.closeAllPanels()
                store.pushPanel(MapPanel.Cities)
            }
            MapEvent.Cities.Close -> close()
            is MapEvent.Cities.Search -> search(event.text)
            is MapEvent.Cities.SearchPick -> searchPick(event.prediction)
            is MapEvent.Cities.Select -> {
                store.hooks.selectCity(event.cityId)
                close()
            }
            MapEvent.Cities.Add -> store.withPost { openAdd(AddCityState()) }
            is MapEvent.Cities.Delete -> store.withPost { askDelete(event.cityId) }
            is MapEvent.Cities.Reorder -> reorder(event.cityIds)
            is MapEvent.Cities.AddQuery -> addQuery(event.text)
            is MapEvent.Cities.AddPick -> addPick(event.prediction)
            MapEvent.Cities.AddSave -> save()
            MapEvent.Cities.AddCancel -> cancelAdd()
        }
    }

    fun load(onLoaded: (List<MapCity>) -> Unit = {}) {
        store.update { copy(citiesLoading = true) }
        store.spawn {
            when (val result = store.repository.cities()) {
                is ZillitResult.Success -> {
                    store.update { copy(cities = result.data, citiesLoading = false, citiesLoaded = true) }
                    afterLoad(result.data)
                    onLoaded(result.data)
                }
                is ZillitResult.Failure -> {
                    store.update { copy(citiesLoading = false, citiesLoaded = true) }
                    store.failed(result.error, "Failed to fetch cities")
                }
            }
        }
    }

    /**
     * Reqs G + H: re-open the city the person was last on, by the ladder in
     * `pickAutoCity`. A city already selected this session is left alone —
     * returning from a panel must not yank anyone somewhere else — unless it
     * has disappeared from the list.
     */
    private suspend fun afterLoad(cities: List<MapCity>) {
        val current = store.state.selectedCityId
        if (current != null && cities.any { it.id == current }) return
        if (current != null) store.update { copy(selectedCityId = null, locations = emptyList(), zones = emptyList()) }
        val target = pickAutoCity(cities, store.host.prefs.lastVisitedCityId()) ?: return
        store.hooks.selectCity(target.id)
    }

    private fun close() = store.popPanel { it == MapPanel.Cities }

    private fun search(text: String) {
        store.update { copy(citiesPanel = citiesPanel.copy(search = text, suggestions = if (text.isBlank()) emptyList() else citiesPanel.suggestions)) }
        searchJob?.cancel()
        if (text.isBlank()) return
        searchJob = store.spawn {
            delay(SEARCH_DEBOUNCE_MS)
            val found = store.canvas.predictions(text, PlaceKind.Cities).orEmpty()
            store.update { if (citiesPanel.search == text) copy(citiesPanel = citiesPanel.copy(suggestions = found)) else this }
        }
    }

    /**
     * A place picked in the Cities search: an existing city of that name is
     * selected; a new one opens Add City already filled in.
     */
    private fun searchPick(prediction: PlacePrediction) {
        store.update { copy(citiesPanel = CitiesPanelState()) }
        store.spawn {
            val place = store.canvas.placeDetails(prediction.placeId) ?: return@spawn
            val name = place.name.ifBlank { place.address }
            val existing = store.state.cities.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
            when {
                existing != null -> {
                    store.hooks.selectCity(existing.id)
                    close()
                }
                !store.state.viewer.mayPost -> store.denyPost()
                else -> openAdd(
                    AddCityState(name = name, description = place.address, point = place.point, prefilled = true),
                )
            }
        }
    }

    fun openAdd(state: AddCityState) = store.update { copy(dialog = MapDialog.AddCity(state)) }

    /** J.2: Add City filled in from a tapped point, which is pinned once the city exists. */
    fun openAddForPoint(point: LatLng, name: String, address: String) {
        pendingPin = PendingPin(point, name)
        openAdd(AddCityState(name = name, description = address, point = point, prefilled = true))
    }

    private fun addState(): AddCityState? = (store.state.dialog as? MapDialog.AddCity)?.state

    private fun updateAdd(reducer: AddCityState.() -> AddCityState) = store.update {
        val dialog = dialog as? MapDialog.AddCity ?: return@update this
        copy(dialog = MapDialog.AddCity(dialog.state.reducer()))
    }

    private fun addQuery(text: String) {
        updateAdd { copy(query = text, suggestions = if (text.isBlank()) emptyList() else suggestions) }
        addSearchJob?.cancel()
        if (text.isBlank()) return
        addSearchJob = store.spawn {
            delay(SEARCH_DEBOUNCE_MS)
            val found = store.canvas.predictions(text, PlaceKind.CityOrPlace).orEmpty()
            updateAdd { if (query == text) copy(suggestions = found) else this }
        }
    }

    private fun addPick(prediction: PlacePrediction) {
        updateAdd { copy(query = prediction.description, suggestions = emptyList()) }
        store.spawn {
            val place = store.canvas.placeDetails(prediction.placeId) ?: return@spawn
            updateAdd {
                copy(
                    name = place.name.ifBlank { place.address },
                    description = place.address.ifBlank { description },
                    point = place.point,
                )
            }
        }
    }

    private fun cancelAdd() {
        pendingPin = null
        store.update { copy(dialog = null) }
    }

    /** `AddCityModal.handleSubmit` — the checks, in the web's order and words. */
    private fun save() {
        val add = addState() ?: return
        val name = add.name.trim()
        val point = add.point
        val problem = when {
            name.isBlank() -> "Please enter a city name"
            point == null -> "Please search and select a city location"
            store.state.cities.any { it.name.trim().equals(name, ignoreCase = true) } ->
                "\"$name\" already exists. Please use a different city name."
            else -> null
        }
        if (problem != null) {
            store.notice(problem, NoticeTone.Warning)
            return
        }
        val at = point ?: return
        updateAdd { copy(saving = true) }
        store.spawn {
            when (val result = store.repository.createCity(CityDraft(name, add.description, at))) {
                is ZillitResult.Failure -> {
                    updateAdd { copy(saving = false) }
                    store.failed(result.error, "Failed to add city")
                }
                is ZillitResult.Success -> {
                    store.notice("City added successfully", NoticeTone.Success)
                    store.update { copy(dialog = null) }
                    val createdId = result.data.city?.id
                    load { cities -> afterCreate(createdId ?: cities.firstOrNull { it.name.equals(name, true) }?.id) }
                }
            }
        }
    }

    /**
     * After a city is created: select it (the Cities panel's
     * `handleCityCreated`), and when a pin was waiting on it, open the
     * location form at that point — no second boundary check, the person
     * chose this city for this pin (spec §6.4).
     */
    private fun afterCreate(cityId: String?) {
        val waiting = pendingPin.also { pendingPin = null }
        if (cityId == null) {
            if (waiting != null) store.notice("City created. Open it and place the pin again.", NoticeTone.Info)
            return
        }
        // Select first: choosing a city resets every panel, and the form the
        // waiting pin opens must come after that reset, not be undone by it.
        store.hooks.selectCity(cityId)
        close()
        if (waiting != null) pendingPinReady?.invoke(waiting)
    }

    /** Set by the view model: opens the location form for a pin whose city now exists. */
    var pendingPinReady: ((PendingPin) -> Unit)? = null

    private fun askDelete(cityId: String) {
        val city = store.state.cities.firstOrNull { it.id == cityId } ?: return
        store.update {
            copy(
                dialog = MapDialog.Confirm(
                    title = "Delete City",
                    message = "Are you sure you want to delete \"${city.name}\"? " +
                        "This will also delete all associated locations.",
                    confirmLabel = "Delete",
                    danger = true,
                    action = ConfirmAction.DeleteCity(cityId),
                ),
            )
        }
    }

    fun delete(cityId: String, done: () -> Unit) {
        store.spawn {
            when (val result = store.repository.deleteCity(cityId)) {
                is ZillitResult.Failure -> store.failed(result.error, "Failed to delete city")
                is ZillitResult.Success -> {
                    store.notice("City deleted successfully", NoticeTone.Success)
                    if (store.state.selectedCityId == cityId) {
                        store.update { copy(selectedCityId = null, locations = emptyList(), zones = emptyList(), activeZoneId = null) }
                    }
                    load()
                }
            }
            done()
        }
    }

    /**
     * A drag in the Cities panel. The new order shows at once and is sent
     * whole; a refusal refetches, so the list never lies about the server.
     */
    private fun reorder(cityIds: List<String>) {
        val byId = store.state.cities.associateBy { it.id }
        val reordered = cityIds.mapNotNull { byId[it] }
        if (reordered.size != store.state.cities.size || reordered == store.state.cities) return
        store.update { copy(cities = reordered) }
        store.spawn {
            val result = store.repository.reorderCities(cityIds)
            if (result is ZillitResult.Failure) {
                store.failed(result.error, "Failed to reorder cities")
                load()
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
