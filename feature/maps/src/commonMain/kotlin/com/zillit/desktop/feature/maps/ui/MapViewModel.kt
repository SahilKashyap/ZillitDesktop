@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.maps.domain.Geo
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.MapRepository
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The map tool without a map: cities, typed pins, studio zones — created and
 * edited with typed coordinates, opened in the system's maps app.
 */
class MapViewModel(
    private val repository: MapRepository,
    private val resolveViewer: () -> MapViewer,
) : ZillitViewModel<MapUiState, MapEvent, MapEffect>(MapUiState()) {

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: MapEvent) {
        when (event) {
            MapEvent.Refresh -> refresh()
            is MapEvent.SelectCity -> {
                setState { copy(selectedCityId = event.cityId) }
                loadPins()
            }
            is MapEvent.FilterType -> setState { copy(typeFilter = event.type) }
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
        }
    }

    private fun <T> ZillitResult<T>.orError(): T? = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> {
            val message = this.error.userMessage
            setState { copy(error = message) }
            null
        }
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
                is ZillitResult.Failure -> setState { copy(busy = false, error = result.error.userMessage) }
            }
        }
    }

    private fun openPin(id: String) {
        val pin = state.value.pins.firstOrNull { it.id == id } ?: return
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
                    copy(pinEditor = pinEditor?.copy(saving = false), error = result.error.userMessage)
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
                    copy(cityEditor = cityEditor?.copy(saving = false), error = result.error.userMessage)
                }
            }
        }
    }

    private companion object {
        const val MAX_LAT = 90.0
        const val MAX_LNG = 180.0
    }
}
