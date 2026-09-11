@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.maps.data.MapRefresh
import com.zillit.desktop.feature.maps.domain.Geo
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.MapCanvasEvent
import com.zillit.desktop.feature.maps.domain.MapCanvasHost
import com.zillit.desktop.feature.maps.domain.MapPinMarker
import com.zillit.desktop.feature.maps.domain.MapRepository
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate

/**
 * The map tool: cities, typed pins, studio zones — drawn on the host's map
 * canvas when one is provided, and always editable as a typed list.
 */
class MapViewModel(
    private val repository: MapRepository,
    private val resolveViewer: () -> MapViewer,
    /** The map surface, or null on hosts without one (tests, no browser). */
    private val canvas: MapCanvasHost? = null,
) : ZillitViewModel<MapUiState, MapEvent, MapEffect>(MapUiState()) {

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
        listenOnce()
        listenCanvasOnce()
    }

    /**
     * Re-lists the pins when the socket says another client added, edited,
     * or removed one — the web's `map_location_*` handlers splice in place;
     * this client's edit path already re-lists, so the sync does too.
     * Guarded so a second start (the window reopening) does not stack
     * collectors; `conflate()` folds a burst into one re-list.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.conflate().collect { kind ->
                // A city event moves the strip the pins are filtered by, so
                // that one re-reads everything; a pin event re-lists pins.
                if (kind == MapRefresh.Cities) refresh() else loadPins()
            }
        }
    }

    private var listening = false

    /**
     * The canvas's clicks, folded back into the same acts the list performs:
     * a marker opens that pin's editor (the web opens its info card,
     * `GoogleMapComponent.jsx:1049`), an empty-map click proposes a new pin
     * at that point (`GoogleMapComponent.jsx:532`), and a failure becomes a
     * readable error instead of a silently grey map.
     */
    private fun listenCanvasOnce() {
        val host = canvas ?: return
        if (canvasListening) return
        canvasListening = true
        launch {
            host.events.collect { event ->
                when (event) {
                    MapCanvasEvent.Ready -> {
                        pushPins()
                        centerOnCity()
                    }
                    is MapCanvasEvent.MarkerClicked -> openPin(event.id)
                    is MapCanvasEvent.MapClicked -> proposePin(event.lat, event.lng)
                    is MapCanvasEvent.Failed -> setState { copy(canvasError = event.message) }
                }
            }
        }
    }

    private var canvasListening = false

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: MapEvent) {
        when (event) {
            MapEvent.Refresh -> refresh()
            is MapEvent.SelectCity -> {
                setState { copy(selectedCityId = event.cityId) }
                loadPins()
                centerOnCity()
            }
            is MapEvent.FilterType -> {
                setState { copy(typeFilter = event.type) }
                pushPins()
            }
            is MapEvent.NewPin -> setState {
                copy(
                    pinEditor = PinEditor(
                        isZone = event.isZone,
                        cityId = selectedCityId ?: cities.firstOrNull()?.id.orEmpty(),
                        type = types.firstOrNull()?.name.orEmpty(),
                        latText = selectedCity?.lat?.toString().orEmpty(),
                        lngText = selectedCity?.lng?.toString().orEmpty(),
                        name = if (event.isZone) "${selectedCity?.name.orEmpty()} Zone".trim() else "",
                    ),
                )
            }
            is MapEvent.EditPin -> openPin(event.id)
            is MapEvent.PinChanged -> setState {
                copy(
                    pinEditor = pinEditor?.copy(
                        cityId = event.cityId ?: pinEditor.cityId,
                        name = event.name ?: pinEditor.name,
                        type = event.type ?: pinEditor.type,
                        subTypes = event.toggleSubType?.let { sub ->
                            if (sub in pinEditor.subTypes) pinEditor.subTypes - sub else pinEditor.subTypes + sub
                        } ?: pinEditor.subTypes,
                        description = event.description ?: pinEditor.description,
                        address = event.address ?: pinEditor.address,
                        sceneNumber = event.sceneNumber ?: pinEditor.sceneNumber,
                        latText = event.latText ?: pinEditor.latText,
                        lngText = event.lngText ?: pinEditor.lngText,
                        radiusText = event.radiusText ?: pinEditor.radiusText,
                    ),
                )
            }
            MapEvent.SavePin -> savePin()
            MapEvent.ClosePin -> setState { copy(pinEditor = null) }
            is MapEvent.DeletePin -> run({ repository.delete(event.id) }, "Removed")
            is MapEvent.OpenInMaps -> state.value.pins.firstOrNull { it.id == event.id }?.mapsUrl
                ?.let { sendEffect(MapEffect.OpenUrl(it)) }
                ?: sendEffect(MapEffect.Notice("This location has no coordinates"))
            MapEvent.NewCity -> setState { copy(cityEditor = CityEditor()) }
            is MapEvent.CityChanged -> setState {
                copy(
                    cityEditor = cityEditor?.copy(
                        name = event.name ?: cityEditor.name,
                        description = event.description ?: cityEditor.description,
                        latText = event.latText ?: cityEditor.latText,
                        lngText = event.lngText ?: cityEditor.lngText,
                        radiusText = event.radiusText ?: cityEditor.radiusText,
                    ),
                )
            }
            MapEvent.SaveCity -> saveCity()
            MapEvent.CloseCity -> setState { copy(cityEditor = null) }
            is MapEvent.DeleteCity -> run({ repository.deleteCity(event.id) }, "City removed")
            is MapEvent.MoveCity -> moveCity(event.cityId, event.up)
            MapEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun refresh() {
        setState { copy(loading = true) }
        launch {
            coroutineScope {
                val cities = async { repository.cities() }
                val types = async { repository.types() }
                val cityList = cities.await().orError()
                val typeList = types.await().orError()
                setState {
                    copy(
                        cities = cityList ?: this.cities,
                        types = typeList ?: this.types,
                        selectedCityId = selectedCityId ?: cityList?.firstOrNull()?.id,
                    )
                }
            }
            loadPins()
        }
    }

    private fun loadPins() {
        setState { copy(loading = true) }
        launch {
            val pins = repository.locations(state.value.selectedCityId).orError()
            setState { copy(loading = false, pins = pins ?: this.pins) }
            pushPins()
        }
    }

    /**
     * The drawable pins, pushed whole — the canvas clears and redraws, so a
     * removed pin disappears and a burst of refreshes converges. Zones ride
     * along as circles; the type filter applies exactly as it does to the
     * list, so the two views never disagree.
     */
    private fun pushPins() {
        val host = canvas ?: return
        val current = state.value
        val markers = (current.zones + current.locations).mapNotNull { pin ->
            val lat = pin.lat ?: return@mapNotNull null
            val lng = pin.lng ?: return@mapNotNull null
            MapPinMarker(
                id = pin.id,
                name = pin.name,
                label = pin.type,
                lat = lat,
                lng = lng,
                isZone = pin.isStudioZone,
                radiusMiles = pin.radiusMiles,
            )
        }
        host.setPins(markers)
    }

    /** Pans to the selected city — the web's default centre is the city's. */
    private fun centerOnCity() {
        val host = canvas ?: return
        val city = state.value.selectedCity ?: return
        val lat = city.lat ?: return
        val lng = city.lng ?: return
        host.center(lat, lng, CITY_ZOOM)
    }

    /**
     * An empty-map click proposes a pin there: the editor opens prefilled
     * with the clicked coordinates, as the web opens its location form at
     * the click (`GoogleMapComponent.jsx:532-565`). Ignored while an editor
     * is already open (`:536` — click disabled when the form is up) and for
     * viewers who cannot post.
     */
    private fun proposePin(lat: Double, lng: Double) {
        val current = state.value
        if (!current.viewer.mayEdit) return
        if (current.pinEditor != null || current.cityEditor != null) return
        setState {
            copy(
                pinEditor = PinEditor(
                    cityId = selectedCityId ?: cities.firstOrNull()?.id.orEmpty(),
                    type = types.firstOrNull()?.name.orEmpty(),
                    latText = lat.toString(),
                    lngText = lng.toString(),
                ),
            )
        }
    }

    private fun <T> ZillitResult<T>.orError(): T? = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> {
            val message = this.error.localised()
            setState { copy(error = message) }
            null
        }
    }

    /**
     * Moves one city and sends the whole arrangement.
     *
     * The new order shows immediately and is corrected by the reload the
     * service's answer triggers: a list that lurches back on every click
     * would be unusable for arranging anything.
     */
    private fun moveCity(cityId: String, up: Boolean) {
        val current = currentState.cities
        val index = current.indexOfFirst { it.id == cityId }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in current.indices) return

        val reordered = current.toMutableList().apply { add(target, removeAt(index)) }
        setState { copy(cities = reordered) }
        run({ repository.reorderCities(reordered.map { it.id }) }, "Order saved")
    }

    private fun run(block: suspend () -> ZillitResult<Unit>, notice: String) {
        setState { copy(busy = true) }
        launch {
            when (val result = block()) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(MapEffect.Notice(notice))
                    loadPins()
                }
                is ZillitResult.Failure -> setState { copy(busy = false, error = result.error.localised()) }
            }
        }
    }

    private fun openPin(id: String) {
        val pin = state.value.pins.firstOrNull { it.id == id } ?: return
        // Selecting a pin — from the list or its marker — recentres the map
        // on it, as the web pans on a coordinate change
        // (`GoogleMapComponent.jsx:587-594`, zoom 15).
        pin.lat?.let { lat -> pin.lng?.let { lng -> canvas?.center(lat, lng, PIN_ZOOM) } }
        setState {
            copy(
                pinEditor = PinEditor(
                    locationId = pin.id,
                    isZone = pin.isStudioZone,
                    cityId = pin.cityId.ifBlank { selectedCityId.orEmpty() },
                    name = pin.name,
                    type = pin.type,
                    subTypes = pin.subTypes.toSet(),
                    description = pin.description,
                    address = pin.address,
                    sceneNumber = pin.sceneNumber,
                    latText = pin.lat?.toString().orEmpty(),
                    lngText = pin.lng?.toString().orEmpty(),
                    radiusText = pin.radiusMiles.takeIf { it > 0 }?.toString() ?: "30",
                ),
            )
        }
    }

    private fun savePin() {
        val editor = state.value.pinEditor ?: return
        val point = editor.validPoint() ?: run {
            setState { copy(error = "A name, a city, and valid coordinates are needed") }
            return
        }
        outsideCityZone(editor, point)?.let { message ->
            setState { copy(error = message) }
            return
        }
        if (!editor.isZone && editor.address.isBlank()) {
            setState { copy(error = "An address is required") }
            return
        }
        setState { copy(pinEditor = pinEditor?.copy(saving = true)) }
        launch {
            when (val result = writePin(editor, point)) {
                is ZillitResult.Success -> {
                    setState { copy(pinEditor = null) }
                    sendEffect(MapEffect.Notice(if (editor.isZone) "Zone saved" else "Location saved"))
                    loadPins()
                }
                is ZillitResult.Failure -> setState {
                    copy(pinEditor = pinEditor?.copy(saving = false), error = result.error.localised())
                }
            }
        }
    }

    /** Parsed, range-checked coordinates — or null when the form is not saveable. */
    private fun PinEditor.validPoint(): Pair<Double, Double>? {
        val lat = latText.trim().toDoubleOrNull() ?: return null
        val lng = lngText.trim().toDoubleOrNull() ?: return null
        val inRange = lat in -MAX_LAT..MAX_LAT && lng in -MAX_LNG..MAX_LNG
        return if (name.isNotBlank() && cityId.isNotBlank() && inRange) lat to lng else null
    }

    /**
     * The web's containment check: a location must sit inside its city's
     * zone when the city has one. Zones themselves are exempt.
     */
    private fun outsideCityZone(editor: PinEditor, point: Pair<Double, Double>): String? {
        val city = state.value.cities.firstOrNull { it.id == editor.cityId }
        val centreLat = city?.lat
        val centreLng = city?.lng
        val hasZone = city != null && city.radiusMiles > 0
        val hasCentre = centreLat != null && centreLng != null
        val applies = !editor.isZone && hasZone && hasCentre
        if (!applies) return null
        checkNotNull(city)
        val inside = Geo.within(
            point.first, point.second,
            checkNotNull(centreLat), checkNotNull(centreLng),
            city.radiusMiles,
        )
        return if (inside) {
            null
        } else {
            "Those coordinates fall outside ${city.name}'s ${city.radiusMiles.toInt()}-mile zone"
        }
    }

    private suspend fun writePin(editor: PinEditor, point: Pair<Double, Double>): ZillitResult<Unit> {
        val (lat, lng) = point
        return if (editor.isZone) {
            val radius = editor.radiusText.trim().toDoubleOrNull() ?: Geo.ZONE_RADII.first()
            val draft = ZoneDraft(editor.cityId, editor.name, lat, lng, radius)
            if (editor.locationId == null) {
                repository.createZone(draft)
            } else {
                repository.updateZone(editor.locationId, draft)
            }
        } else {
            val draft = LocationDraft(
                cityId = editor.cityId,
                name = editor.name,
                type = editor.type,
                subTypes = editor.subTypes.toList(),
                description = editor.description,
                address = editor.address,
                sceneNumber = editor.sceneNumber,
                lat = lat,
                lng = lng,
            )
            if (editor.locationId == null) {
                repository.createLocation(draft)
            } else {
                repository.updateLocation(editor.locationId, draft)
            }
        }
    }

    private fun saveCity() {
        val editor = state.value.cityEditor ?: return
        val lat = editor.latText.trim().toDoubleOrNull()
        val lng = editor.lngText.trim().toDoubleOrNull()
        if (editor.name.isBlank() || lat == null || lng == null) {
            setState { copy(error = "A city needs a name and coordinates") }
            return
        }
        setState { copy(cityEditor = cityEditor?.copy(saving = true)) }
        launch {
            val radius = editor.radiusText.trim().toDoubleOrNull() ?: 0.0
            when (val result = repository.createCity(editor.name.trim(), editor.description, lat, lng, radius)) {
                is ZillitResult.Success -> {
                    setState { copy(cityEditor = null) }
                    sendEffect(MapEffect.Notice("City added"))
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(cityEditor = cityEditor?.copy(saving = false), error = result.error.localised())
                }
            }
        }
    }

    private companion object {
        const val MAX_LAT = 90.0
        const val MAX_LNG = 180.0

        /** The web's zoom on selecting a point (`GoogleMapComponent.jsx:589`). */
        const val PIN_ZOOM = 15

        /** Wide enough to see a city's pins together; the web starts at the city too. */
        const val CITY_ZOOM = 11
    }
}
