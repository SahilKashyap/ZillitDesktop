package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.LocationRules
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.PickedPhoto
import com.zillit.desktop.feature.maps.domain.PlaceKind
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.SearchBias
import com.zillit.desktop.feature.maps.domain.isWithinSelectedCity
import com.zillit.desktop.feature.maps.domain.toFixed
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Locations: the city's list, the List View and details
 * (`locations/LocationList.jsx`, `LocationDetail.jsx`), and the location form
 * (`LocationFormPanel.jsx` + `useLocationForm.js`).
 */
@Suppress("TooManyFunctions") // One handler per control on three screens.
internal class LocationController(private val store: MapStore) {

    private var addressJob: Job? = null
    private var photoKeys = 0

    fun load(cityId: String) {
        store.update { copy(locationsLoading = true) }
        store.spawn {
            when (val result = store.repository.locations(cityId)) {
                is ZillitResult.Success -> {
                    if (store.state.selectedCityId != cityId) return@spawn
                    store.update { copy(locations = result.data, locationsLoading = false) }
                }
                is ZillitResult.Failure -> {
                    store.update { copy(locationsLoading = false) }
                    store.failed(result.error, str(S.desktop_map_failed_fetch_locations))
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    fun onListEvent(event: MapEvent.Locations) {
        when (event) {
            is MapEvent.Locations.Filter -> store.update { copy(listView = listView?.copy(filter = event.type)) }
            is MapEvent.Locations.View -> view(event.locationId)
            is MapEvent.Locations.Edit -> store.withPost { openEdit(event.locationId) }
            is MapEvent.Locations.Delete -> store.withPost { askDelete(event.locationId) }
            is MapEvent.Locations.Share -> Unit // Routed by the view model to the share flow.
            is MapEvent.Locations.Directions -> Unit // Routed by the view model to directions.
            MapEvent.Locations.CloseDetail -> store.popPanel { it is MapPanel.LocationDetail }
            is MapEvent.Locations.CopyCoordinates -> copyCoordinates(event.locationId)
            is MapEvent.Locations.OpenPhoto -> store.state.location(event.locationId)?.attachments
                ?.getOrNull(event.index)
                ?.let { store.update { copy(dialog = MapDialog.Photo(attachment = it, added = null)) } }
        }
    }

    private fun view(locationId: String) {
        if (store.state.location(locationId) == null) return
        store.popPanel { it is MapPanel.LocationDetail }
        store.pushPanel(MapPanel.LocationDetail(locationId))
    }

    /** "Copy" beside the coordinates — six decimals, as shown. */
    private fun copyCoordinates(locationId: String) {
        val point = store.state.location(locationId)?.point ?: return
        store.effect(
            MapEffect.Copy("${toFixed(point.lat, COORDINATE_DIGITS)}, ${toFixed(point.lng, COORDINATE_DIGITS)}"),
        )
        store.notice(str(S.desktop_map_coordinates_copied), NoticeTone.Success)
    }

    // Form ---------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per field.
    fun onFormEvent(event: MapEvent.LocationForm) {
        when (event) {
            MapEvent.LocationForm.New -> store.withPost { openNew(origin = FormOrigin.List) }
            is MapEvent.LocationForm.Name -> updateForm { copy(name = event.value) }
            is MapEvent.LocationForm.TypeSearch -> updateForm {
                copy(typeSearch = event.value, typeMenuOpen = true).let {
                    if (event.value.isEmpty()) it.copy(type = "", subTypes = emptyList()) else it
                }
            }
            is MapEvent.LocationForm.TypeMenu -> updateForm { copy(typeMenuOpen = event.open, typeSearch = "") }
            is MapEvent.LocationForm.PickType -> pickType(event.name)
            is MapEvent.LocationForm.CustomType -> updateForm { copy(customType = event.value) }
            is MapEvent.LocationForm.ToggleSubType -> updateForm {
                copy(subTypes = if (event.name in subTypes) subTypes - event.name else subTypes + event.name)
            }
            is MapEvent.LocationForm.SceneNumber -> updateForm { copy(sceneNumber = event.value) }
            is MapEvent.LocationForm.Description -> updateForm { copy(description = event.value) }
            is MapEvent.LocationForm.AddressText -> addressText(event.value)
            is MapEvent.LocationForm.AddressPick -> addressPick(event.prediction)
            MapEvent.LocationForm.UseAddressAnyway -> useAddressAnyway()
            MapEvent.LocationForm.BrowsePhotos -> browsePhotos()
            is MapEvent.LocationForm.PhotosDropped -> addPhotos(event.photos, event.refused)
            is MapEvent.LocationForm.RemoveExisting -> updateForm {
                copy(existing = existing.filterIndexed { index, _ -> index != event.index })
            }
            is MapEvent.LocationForm.RemoveAdded -> updateForm { copy(added = added.filterNot { it.key == event.key }) }
            MapEvent.LocationForm.NewType -> Unit // Routed by the view model to the types controller.
            MapEvent.LocationForm.Save -> save()
            MapEvent.LocationForm.Close -> close()
        }
    }

    fun pickType(name: String) = updateForm {
        copy(type = name, subTypes = emptyList(), typeSearch = "", typeMenuOpen = false)
    }

    /**
     * Opens a blank form. From the map it carries the tapped point and its
     * name; the address is filled from the point once the map answers.
     */
    fun openNew(origin: FormOrigin, point: LatLng? = null, name: String = "") {
        val state = store.state
        val cityId = state.selectedCityId ?: return
        val form = LocationFormState(
            origin = origin,
            cityId = cityId,
            name = name,
            point = point,
            anchor = point ?: state.selectedCity?.panTarget,
        )
        show(form)
        if (point != null) fillAddressFrom(point)
    }

    fun openEdit(locationId: String) {
        val location = store.state.location(locationId) ?: run {
            store.notice(str(S.location_not_found), NoticeTone.Error)
            return
        }
        show(
            LocationFormState(
                editId = location.id,
                origin = if (store.state.listView != null) FormOrigin.List else FormOrigin.Map,
                cityId = location.cityId.ifBlank { store.state.selectedCityId.orEmpty() },
                name = location.name,
                type = location.type,
                subTypes = location.subTypes,
                sceneNumber = location.sceneNumber,
                description = location.description,
                address = location.address,
                point = location.point,
                existing = location.attachments,
                anchor = location.point ?: store.state.selectedCity?.panTarget,
            ),
        )
    }

    private fun show(form: LocationFormState) {
        addressJob?.cancel()
        store.update {
            copy(
                locationForm = form,
                panels = panels.filterNot { it == MapPanel.LocationForm || it is MapPanel.LocationDetail } +
                    MapPanel.LocationForm,
            )
        }
    }

    private fun updateForm(reducer: LocationFormState.() -> LocationFormState) =
        store.update { copy(locationForm = locationForm?.reducer()) }

    fun close() {
        addressJob?.cancel()
        store.update { copy(locationForm = null) }
        store.popPanel { it == MapPanel.LocationForm }
    }

    /** `useLocationForm`'s reverse geocode: a pinned point with no address gets one. */
    private fun fillAddressFrom(point: LatLng) {
        store.spawn {
            val place = store.canvas.reverseGeocode(point) ?: return@spawn
            updateForm { if (address.isBlank() && this.point == point) copy(address = place.address) else this }
        }
    }

    /** The draft pin dragged on the map: new coordinates, and the address they have. */
    fun draftMoved(point: LatLng) {
        updateForm { copy(point = point) }
        store.spawn {
            val place = store.canvas.reverseGeocode(point) ?: return@spawn
            updateForm { if (this.point == point) copy(address = place.address) else this }
        }
    }

    private fun addressText(value: String) {
        updateForm {
            copy(address = value, addressSuggestions = if (value.isBlank()) emptyList() else addressSuggestions)
        }
        addressJob?.cancel()
        if (value.isBlank()) return
        val anchor = store.state.locationForm?.anchor
        addressJob = store.spawn {
            delay(SEARCH_DEBOUNCE_MS)
            val found = store.canvas.predictions(
                input = value,
                kind = PlaceKind.Any,
                bias = anchor?.let { SearchBias(it, SearchBias.ADDRESS_RADIUS_METERS) },
                strict = anchor != null,
            ).orEmpty()
            updateForm { if (address == value) copy(addressSuggestions = found) else this }
        }
    }

    /**
     * A place chosen for the address. Outside the city it asks first — two
     * options, not three: deep inside the form, sending someone off to create
     * a city would break the flow (boundary spec §5.6).
     */
    private fun addressPick(prediction: PlacePrediction) {
        addressJob?.cancel()
        updateForm { copy(addressSuggestions = emptyList()) }
        store.spawn {
            val place = store.canvas.placeDetails(prediction.placeId) ?: return@spawn
            val pick = AddressPick(
                name = place.name,
                address = place.address.ifBlank { place.name },
                point = place.point,
            )
            val state = store.state
            if (!isWithinSelectedCity(pick.point, state.selectedCity, state.zones, state.cityBounds)) {
                val label = state.selectedCity?.name?.ifBlank { null } ?: str(S.desktop_map_the_selected_city)
                store.update { copy(dialog = MapDialog.AddressOutside(label, pick)) }
            } else {
                applyAddress(pick)
            }
        }
    }

    private fun useAddressAnyway() {
        val dialog = store.state.dialog as? MapDialog.AddressOutside ?: return
        store.update { copy(dialog = null) }
        applyAddress(dialog.pick)
    }

    /** The name only fills an empty field; the address and point always move. */
    private fun applyAddress(pick: AddressPick) = updateForm {
        copy(name = name.ifBlank { pick.name }, address = pick.address, point = pick.point)
    }

    // Photos -------------------------------------------------------------------

    private fun browsePhotos() {
        val photos = store.host.photos ?: return
        store.spawn {
            val refused = mutableListOf<String>()
            val picked = photos.pick { refused += it }
            addPhotos(picked, refused)
        }
    }

    /** `addFiles`: at most [LocationRules.MAX_MEDIA] photos, existing ones included. */
    private fun addPhotos(photos: List<PickedPhoto>, refused: List<String>) {
        refused.forEach { store.notice(str(S.desktop_map_unsupported_image, it), NoticeTone.Warning) }
        val form = store.state.locationForm ?: return
        if (photos.isEmpty()) return
        val remaining = LocationRules.MAX_MEDIA - form.mediaCount
        if (remaining <= 0) {
            store.notice(str(S.desktop_map_max_media, LocationRules.MAX_MEDIA), NoticeTone.Warning)
            return
        }
        val batch = photos.take(remaining)
        if (batch.size < photos.size) {
            store.notice(
                str(S.desktop_map_only_n_files_added, batch.size, photos.size, LocationRules.MAX_MEDIA),
                NoticeTone.Warning,
            )
        }
        updateForm { copy(added = added + batch.map { NewPhoto("new-${photoKeys++}", it) }) }
    }

    // Save ---------------------------------------------------------------------

    private fun save() {
        val form = store.state.locationForm ?: return
        LocationRules.saveError(form.name, form.address, form.type, form.customType, form.point)?.let {
            store.notice(it, NoticeTone.Warning)
            return
        }
        val point = form.point ?: return
        val state = store.state
        // The boundary was asked when the pin was placed; an address search
        // can move it after that, and being told is still useful. It no
        // longer blocks (req I reversed the old refusal).
        if (!isWithinSelectedCity(point, state.selectedCity, state.zones, state.cityBounds)) {
            store.notice(
                str(S.desktop_map_saved_outside, state.selectedCity?.name ?: str(S.desktop_map_the_selected_city)),
                NoticeTone.Info,
            )
        }
        updateForm { copy(saving = true) }
        store.spawn {
            val uploaded = upload(form.added) ?: run {
                updateForm { copy(saving = false) }
                return@spawn
            }
            val draft = LocationDraft(
                cityId = form.cityId,
                name = form.name,
                type = LocationRules.savedType(form.type, form.customType),
                subTypes = form.subTypes,
                description = form.description,
                address = form.address,
                sceneNumber = form.sceneNumber,
                point = point,
                attachments = (if (form.isEdit) form.existing else emptyList()) + uploaded,
            )
            val result = form.editId?.let { store.repository.updateLocation(it, draft) }
                ?: store.repository.createLocation(draft)
            finishSave(form, point, result)
        }
    }

    /** The save's answer: the toast, and — on success — the reloads and the pan. */
    private fun finishSave(form: LocationFormState, point: LatLng, result: ZillitResult<String?>) {
        when (result) {
            is ZillitResult.Failure -> {
                updateForm { copy(saving = false) }
                store.failed(
                    result.error,
                    if (form.isEdit) {
                        str(S.desktop_map_failed_update_location)
                    } else {
                        str(S.desktop_map_failed_create_location)
                    },
                )
            }
            is ZillitResult.Success -> {
                store.succeeded(
                    result.data,
                    if (form.isEdit) str(S.desktop_map_location_updated) else str(S.desktop_map_location_created),
                )
                close()
                store.canvas.clearPreview()
                store.canvas.clearPlace()
                store.hooks.reloadLocations()
                store.hooks.reloadCities()
                // Req P: centre on the saved pin, never re-zoom.
                if (form.origin == FormOrigin.Map) store.canvas.panTo(point, zoom = null)
            }
        }
    }

    /** Stores every new photo; null (after saying so) when any upload fails. */
    private suspend fun upload(added: List<NewPhoto>): List<MapAttachment>? {
        if (added.isEmpty()) return emptyList()
        val photos = store.host.photos ?: run {
            store.notice(str(S.desktop_map_upload_config_unavailable), NoticeTone.Error)
            return null
        }
        val stored = mutableListOf<MapAttachment>()
        for (photo in added) {
            when (val result = photos.upload(photo.photo)) {
                is ZillitResult.Success -> stored += result.data
                is ZillitResult.Failure -> {
                    store.notice(str(S.desktop_map_failed_upload_files), NoticeTone.Error)
                    return null
                }
            }
        }
        return stored
    }

    private fun askDelete(locationId: String) {
        val location = store.state.location(locationId) ?: return
        store.update {
            copy(
                dialog = MapDialog.Confirm(
                    title = str(S.desktop_map_delete_location_title),
                    message = str(S.desktop_map_delete_confirm_named, location.name),
                    confirmLabel = str(S.delete),
                    danger = true,
                    action = ConfirmAction.DeleteLocation(locationId),
                ),
            )
        }
    }

    fun delete(locationId: String, done: () -> Unit) {
        store.spawn {
            when (val result = store.repository.deleteLocation(locationId)) {
                is ZillitResult.Failure -> store.failed(result.error, str(S.desktop_map_failed_delete_location))
                is ZillitResult.Success -> {
                    store.succeeded(result.data, str(S.desktop_map_location_deleted))
                    store.update { copy(locations = locations.filterNot { it.id == locationId }) }
                    store.popPanel { it == MapPanel.LocationDetail(locationId) }
                    store.hooks.reloadLocations()
                    store.hooks.reloadCities()
                }
            }
            done()
        }
    }

    /** A saved pin with no usable point cannot be shared — its menu stays inert. */
    fun shareable(location: MapLocation?): Boolean = location?.point != null

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val COORDINATE_DIGITS = 6
    }
}
