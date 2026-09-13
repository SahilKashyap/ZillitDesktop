package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.GeocodeOutcome
import com.zillit.desktop.feature.maps.domain.IntersectionStreets
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.PlaceKind
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.SceneCircle
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import com.zillit.desktop.feature.maps.domain.ZoneRules
import com.zillit.desktop.feature.maps.domain.jsNumber
import com.zillit.desktop.feature.maps.domain.zoneSearchBounds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Studio zones: the list (`studio/StudioZoneListPanel.jsx`), the form
 * (`StudioZoneFormPanel.jsx` + `useStudioZoneForm.js`), the details, and the
 * one active zone the map draws (req F).
 */
@Suppress("TooManyFunctions") // One handler per control on three screens.
internal class ZoneController(private val store: MapStore) {

    private var searchJob: Job? = null

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    fun onEvent(event: MapEvent.Zones) {
        when (event) {
            is MapEvent.Zones.Filter -> store.update { copy(zoneFilter = event.text) }
            is MapEvent.Zones.Activate -> activate(event.zoneId, closeList = true)
            MapEvent.Zones.Clear -> clear()
            is MapEvent.Zones.Details -> store.pushPanel(MapPanel.ZoneDetail(event.zoneId))
            is MapEvent.Zones.Edit -> store.withPost { openForm(event.zoneId) }
            is MapEvent.Zones.Delete -> store.withPost { askDelete(event.zoneId) }
            MapEvent.Zones.Add -> store.withPost { openForm(null) }
            MapEvent.Zones.CloseDetail -> store.popPanel { it is MapPanel.ZoneDetail }
            is MapEvent.Zones.Name -> updateForm { copy(name = event.value) }
            is MapEvent.Zones.Mode -> updateForm { copy(mode = event.mode) }
            is MapEvent.Zones.CenterQuery -> centerQuery(event.text)
            is MapEvent.Zones.CenterPick -> centerPick(event.prediction)
            is MapEvent.Zones.Street1 -> street(first = true, text = event.text)
            is MapEvent.Zones.Street1Pick -> streetPick(first = true, prediction = event.prediction)
            is MapEvent.Zones.Street2 -> street(first = false, text = event.text)
            is MapEvent.Zones.Street2Pick -> streetPick(first = false, prediction = event.prediction)
            MapEvent.Zones.FindIntersection -> findIntersection()
            is MapEvent.Zones.Preset -> updateForm { copy(preset = event.miles, useCustom = false, customTouched = false) }
            MapEvent.Zones.Custom -> updateForm { copy(useCustom = true) }
            is MapEvent.Zones.CustomRadius -> updateForm { copy(customRadius = event.text) }
            MapEvent.Zones.CustomRadiusLeft -> updateForm { copy(customTouched = true) }
            MapEvent.Zones.Save -> save()
            MapEvent.Zones.CloseForm -> closeForm()
        }
    }

    fun load(cityId: String) {
        store.update { copy(zonesLoading = true) }
        store.spawn {
            when (val result = store.repository.studioZones(cityId)) {
                is ZillitResult.Success -> {
                    if (store.state.selectedCityId != cityId) return@spawn
                    val before = store.state.activeZone
                    store.update { copy(zones = result.data, zonesLoading = false) }
                    if (before != null && store.state.activeZoneId == before.id) {
                        refit(before, store.state.activeZone)
                    }
                    reconcileActive()
                }
                is ZillitResult.Failure -> {
                    store.update { copy(zonesLoading = false) }
                    store.failed(result.error, "Failed to fetch studio zones")
                }
            }
        }
    }

    /**
     * Req F: a city with zones always draws one. Keeps the active zone in step
     * with the list — the remembered zone, else the first; a deleted active
     * zone falls to the next; none left clears it.
     */
    suspend fun reconcileActive() {
        val state = store.state
        val cityId = state.selectedCityId ?: return
        val current = state.activeZoneId
        if (current != null && state.zones.any { it.id == current }) return
        if (state.zones.isEmpty()) {
            if (current != null) store.update { copy(activeZoneId = null) }
            return
        }
        val remembered = if (current == null) store.host.prefs.activeZoneId(cityId) else null
        val target = state.zones.firstOrNull { it.id == remembered } ?: state.zones.first()
        // A deleted active zone's replacement is remembered, as the web
        // writes it; an automatic first pick is not, so a later visit still
        // prefers what the person chose.
        if (current != null) store.host.prefs.setActiveZoneId(cityId, target.id)
        showOnMap(target)
    }

    /** "Map" on a zone card: draw it, remember it, and close the list. */
    fun activate(zoneId: String, closeList: Boolean) {
        val zone = store.state.zone(zoneId) ?: return
        val cityId = store.state.selectedCityId
        showOnMap(zone)
        if (cityId != null) store.spawn { store.host.prefs.setActiveZoneId(cityId, zone.id) }
        if (closeList) store.popPanel { it == MapPanel.ZoneList }
    }

    private fun showOnMap(zone: MapLocation) {
        store.update { copy(activeZoneId = zone.id) }
        zone.point?.let { store.canvas.fitCircle(SceneCircle(it, zone.zoneRadiusMiles)) }
    }

    /** "Hide" / "Clear Zone": nothing drawn, and the camera goes back to the city. */
    private fun clear() {
        store.update { copy(activeZoneId = null) }
        store.state.selectedCity?.panTarget?.let { store.canvas.panTo(it, CITY_ZOOM) }
    }

    /** The camera follows an edited active zone, as the web's fit effect does. */
    fun refit(before: MapLocation?, after: MapLocation?) {
        if (after == null || after.id != store.state.activeZoneId) return
        val circle = after.point?.let { SceneCircle(it, after.zoneRadiusMiles) } ?: return
        val old = before?.point?.let { SceneCircle(it, before.zoneRadiusMiles) }
        if (circle != old) store.canvas.fitCircle(circle)
    }

    // Form ---------------------------------------------------------------------

    /**
     * Opens the form. A new zone starts on the city — its name and its point
     * (`useStudioZoneForm`'s auto-fill); an edit starts on the zone, its
     * radius a preset when it is one and Custom otherwise, and its streets
     * restored as an already-found intersection.
     */
    fun openForm(zoneId: String?) {
        val state = store.state
        val city = state.selectedCity ?: return
        val zone = zoneId?.let { state.zone(it) }
        if (zoneId != null && zone == null) {
            store.notice("Studio zone not found", NoticeTone.Error)
            return
        }
        val form = if (zone == null) {
            ZoneFormState(cityId = city.id, name = city.name, point = city.coordinates)
        } else {
            val miles = zone.zoneRadiusMiles
            val preset = ZoneRules.PRESETS.firstOrNull { it.toDouble() == miles }
            val streets = zone.intersection
            ZoneFormState(
                editId = zone.id,
                cityId = zone.cityId.ifBlank { city.id },
                name = zone.name,
                point = zone.point,
                preset = preset ?: ZoneRules.PRESETS.first(),
                useCustom = preset == null,
                customRadius = if (preset == null) jsNumber(miles) else "",
                mode = zone.centerPointType,
                street1 = streets?.street1.orEmpty(),
                street1Picked = !streets?.street1.isNullOrBlank(),
                street2 = streets?.street2.orEmpty(),
                street2Picked = !streets?.street2.isNullOrBlank(),
                intersection = streets?.takeIf { zone.centerPointType == CenterPointType.Intersection }?.label,
            )
        }
        store.update { copy(zoneForm = form, panels = panels.filterNot { it == MapPanel.ZoneForm } + MapPanel.ZoneForm) }
    }

    private fun updateForm(reducer: ZoneFormState.() -> ZoneFormState) =
        store.update { copy(zoneForm = zoneForm?.reducer()) }

    private fun closeForm() {
        searchJob?.cancel()
        store.update { copy(zoneForm = null) }
        store.popPanel { it == MapPanel.ZoneForm }
    }

    private fun suggest(text: String, kind: PlaceKind, apply: ZoneFormState.(List<PlacePrediction>) -> ZoneFormState) {
        searchJob?.cancel()
        if (text.isBlank()) {
            updateForm { apply(emptyList()) }
            return
        }
        val centre = store.state.selectedCity?.coordinates
        searchJob = store.spawn {
            delay(SEARCH_DEBOUNCE_MS)
            val found = store.canvas.predictions(
                input = text,
                kind = kind,
                bounds = centre?.let(::zoneSearchBounds),
                strict = centre != null,
            ).orEmpty()
            updateForm { apply(found) }
        }
    }

    private fun centerQuery(text: String) {
        updateForm { copy(centerQuery = text) }
        suggest(text, PlaceKind.Any) { found -> if (centerQuery == text) copy(centerSuggestions = found) else this }
    }

    /** A centre picked: the point, and "{place} Zone" as the name. */
    private fun centerPick(prediction: PlacePrediction) {
        updateForm { copy(centerQuery = prediction.description, centerSuggestions = emptyList()) }
        store.spawn {
            val place = store.canvas.placeDetails(prediction.placeId) ?: return@spawn
            val placeName = place.name.ifBlank { place.address }
            updateForm {
                copy(point = place.point, name = if (placeName.isNotBlank()) "$placeName Zone" else name)
            }
        }
    }

    /** Typing a street invalidates its earlier pick and any found intersection. */
    private fun street(first: Boolean, text: String) {
        updateForm {
            if (first) {
                copy(street1 = text, street1Picked = false, intersection = null)
            } else {
                copy(street2 = text, street2Picked = false, intersection = null)
            }
        }
        suggest(text, PlaceKind.Route) { found ->
            when {
                first && street1 == text -> copy(street1Suggestions = found)
                !first && street2 == text -> copy(street2Suggestions = found)
                else -> this
            }
        }
    }

    private fun streetPick(first: Boolean, prediction: PlacePrediction) {
        val name = prediction.mainText.ifBlank { prediction.description }
        updateForm {
            if (first) {
                copy(street1 = name, street1Picked = true, street1Suggestions = emptyList(), intersection = null)
            } else {
                copy(street2 = name, street2Picked = true, street2Suggestions = emptyList(), intersection = null)
            }
        }
    }

    /** `findIntersection` — geocode "Street 1 & Street 2, City", biased to the city. */
    private fun findIntersection() {
        val form = store.state.zoneForm ?: return
        ZoneRules.intersectionSearchError(form.street1, form.street1Picked, form.street2, form.street2Picked)?.let {
            store.notice(it, NoticeTone.Warning)
            return
        }
        val city = store.state.selectedCity
        updateForm { copy(finding = true, intersection = null) }
        store.spawn {
            val query = ZoneRules.intersectionQuery(form.street1, form.street2, city?.name.orEmpty())
            when (val outcome = store.canvas.geocode(query, city?.coordinates?.let(::zoneSearchBounds))) {
                is GeocodeOutcome.Found -> {
                    updateForm {
                        copy(
                            finding = false,
                            point = outcome.point,
                            intersection = outcome.address.ifBlank { query },
                            name = ZoneRules.intersectionZoneName(form.street1, form.street2),
                        )
                    }
                    store.notice(
                        if (form.street2.isBlank()) "Location found!" else "Intersection found!",
                        NoticeTone.Success,
                    )
                }
                is GeocodeOutcome.NotFound -> {
                    updateForm { copy(finding = false) }
                    store.notice(
                        if (outcome.status == "ZERO_RESULTS") {
                            "No location found for the given street(s). Try a different street name."
                        } else {
                            "Geocoding failed: ${outcome.status}"
                        },
                        NoticeTone.Error,
                    )
                }
            }
        }
    }

    /** The zone's centre dragged on the map while the form is open. */
    fun draftMoved(point: LatLng) = updateForm { copy(point = point) }

    /** `validateAndSubmit`, preceded by the panel's own checks, in the web's order. */
    private fun save() {
        val form = store.state.zoneForm ?: return
        val city = store.state.selectedCity
        // The city must sit inside its own zone. The panel checks this before
        // the hook's own checks, so it wins whenever a centre is already set.
        val cityOutside = ZoneRules.cityOutsideZone(
            cityName = city?.name.orEmpty().ifBlank { "Selected City" },
            city = city?.coordinates,
            centre = form.point,
            radiusMiles = form.effectiveRadius,
        )
        val problem = form.customError?.also { updateForm { copy(customTouched = true) } }
            ?: cityOutside
            ?: intersectionProblem(form)
            ?: coordinateProblem(form)
            ?: "Radius must be greater than 0 miles".takeIf { form.effectiveRadius <= 0 }
        if (problem != null) {
            store.notice(problem, NoticeTone.Warning)
            return
        }
        val point = form.point ?: return
        val draft = ZoneDraft(
            cityId = form.cityId,
            name = form.name.trim().ifBlank { "${city?.name.orEmpty()} Zone".trim() },
            point = point,
            radiusMiles = form.effectiveRadius,
            centerPointType = form.mode,
            streets = IntersectionStreets(form.street1.trim(), form.street2.trim())
                .takeIf { form.mode == CenterPointType.Intersection },
        )
        updateForm { copy(saving = true) }
        store.spawn {
            val result = form.editId?.let { store.repository.updateZone(it, draft) }
                ?: store.repository.createZone(draft)
            when (result) {
                is ZillitResult.Failure -> {
                    updateForm { copy(saving = false) }
                    store.failed(
                        result.error,
                        if (form.isEdit) "Failed to update location" else "Failed to create location",
                    )
                }
                is ZillitResult.Success -> {
                    store.succeeded(
                        result.data,
                        if (form.isEdit) "Location updated successfully" else "Location created successfully",
                    )
                    closeForm()
                    store.hooks.reloadZones()
                    store.hooks.reloadCities()
                }
            }
        }
    }

    private fun intersectionProblem(form: ZoneFormState): String? = when {
        form.mode != CenterPointType.Intersection -> null
        form.street1.isBlank() -> "Please enter street 1"
        form.intersection == null -> "Please select the intersection first by clicking \"Select Intersection\""
        else -> null
    }

    private fun coordinateProblem(form: ZoneFormState): String? = when {
        form.point != null -> null
        form.mode == CenterPointType.Intersection -> "Please select the intersection to set coordinates"
        else -> "Please set a center point location"
    }

    private fun askDelete(zoneId: String) {
        val zone = store.state.zone(zoneId) ?: return
        store.update {
            copy(
                dialog = MapDialog.Confirm(
                    title = "Delete Studio Zone",
                    message = "Are you sure you want to delete \"${zone.name}\"?",
                    confirmLabel = "Delete",
                    danger = true,
                    action = ConfirmAction.DeleteZone(zoneId),
                ),
            )
        }
    }

    fun delete(zoneId: String, done: () -> Unit) {
        store.spawn {
            when (val result = store.repository.deleteLocation(zoneId)) {
                is ZillitResult.Failure -> store.failed(result.error, "Failed to delete location")
                is ZillitResult.Success -> {
                    store.succeeded(result.data, "Location deleted successfully")
                    store.popPanel { it == MapPanel.ZoneDetail(zoneId) }
                    store.hooks.reloadZones()
                    store.hooks.reloadCities()
                }
            }
            done()
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val CITY_ZOOM = 13
    }
}
