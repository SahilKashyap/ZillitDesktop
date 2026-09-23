package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.domain.BoundaryChoice
import com.zillit.desktop.feature.maps.domain.BoundaryMode
import com.zillit.desktop.feature.maps.domain.BoundaryStatus
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.ScenePin
import com.zillit.desktop.feature.maps.domain.buildBoundaryPrompt
import com.zillit.desktop.feature.maps.domain.evaluatePinBoundary
import kotlinx.coroutines.CompletableDeferred

/**
 * Pinning: Pin Location mode, the boundary questions (reqs I + J), and moving
 * a saved pin by dragging it (req O).
 *
 * The six routes into a new pin — map tap, the preview's drag, its "Add
 * Location", a Places result's "Pin Location" — all pass one gate,
 * [askBoundary], so the same point gets the same question whichever way it
 * arrived.
 */
internal class PinController(
    private val store: MapStore,
    private val cities: CityController,
    private val locations: LocationController,
) {
    private var answer: CompletableDeferred<BoundaryChoice>? = null

    fun togglePinMode() {
        if (!store.state.viewer.mayPost) {
            store.denyPost()
            return
        }
        if (store.state.pinMode) {
            exitPinMode()
            return
        }
        if (store.state.selectedCityId == null) {
            store.notice(str(S.desktop_map_select_city_first), NoticeTone.Warning)
            return
        }
        store.update { copy(pinMode = true) }
    }

    fun exitPinMode() {
        store.update { copy(pinMode = false) }
        store.canvas.clearPreview()
    }

    /**
     * Asks the out-of-bounds question and waits for the answer. Answers
     * [BoundaryChoice.Confirm] at once when nothing needs asking, so call
     * sites read as a straight line.
     *
     * The address is fetched BEFORE the dialog opens — "outside the zone"
     * tells nobody anything without saying where the pin landed — and only
     * when a question will actually be put.
     */
    suspend fun askBoundary(point: LatLng, mode: BoundaryMode, name: String? = null): BoundaryChoice {
        val state = store.state
        val verdict = evaluatePinBoundary(point, state.selectedCity, state.activeZone)
        if (verdict.status == BoundaryStatus.Inside && mode == BoundaryMode.Add) return BoundaryChoice.Confirm
        val address = store.canvas.reverseGeocode(point)?.address
        val prompt = buildBoundaryPrompt(
            status = verdict.status,
            mode = mode,
            cityName = verdict.cityName,
            zoneName = verdict.zoneName,
            address = address,
            name = name,
        ) ?: return BoundaryChoice.Confirm
        val pending = CompletableDeferred<BoundaryChoice>()
        answer?.complete(BoundaryChoice.Cancel)
        answer = pending
        store.update { copy(dialog = MapDialog.Boundary(prompt)) }
        return pending.await()
    }

    /** The dialog's answer; dismissing it is Cancel (spec §3.J). */
    fun answer(choice: BoundaryChoice) {
        store.update { if (dialog is MapDialog.Boundary) copy(dialog = null) else this }
        answer?.complete(choice)
        answer = null
    }

    /** "Add Location" on the preview card, or "Pin Location" on a Places result. */
    fun addAt(point: LatLng, name: String, fromPlace: Boolean) {
        if (!store.state.viewer.mayPost) {
            store.denyPost()
            return
        }
        if (store.state.selectedCityId == null) {
            store.notice(str(S.desktop_map_select_city_first), NoticeTone.Warning)
            return
        }
        store.spawn {
            when (askBoundary(point, BoundaryMode.Add)) {
                BoundaryChoice.Cancel -> Unit
                BoundaryChoice.CreateCity -> createCityFor(point)
                BoundaryChoice.Confirm -> {
                    store.hooks.closeAllPanels()
                    locations.openNew(FormOrigin.Map, point = point, name = name)
                    // The preview has done its job; pin mode stays on for the next one.
                    if (!fromPlace) store.canvas.clearPreview()
                }
            }
        }
    }

    /**
     * J.2 "Create Another City & Pin Location": Add City filled in from the
     * point, and the pin opened there once the city exists.
     */
    private suspend fun createCityFor(point: LatLng) {
        val place = store.canvas.reverseGeocode(point)
        cities.openAddForPoint(point, name = place?.suggestedCityName.orEmpty(), address = place?.address.orEmpty())
    }

    /**
     * Req O: a saved pin dropped somewhere new.
     *
     * The pin stays where it was dropped for the whole question and save, so
     * the dialog describes what is on screen. The PUT is a FULL replacement —
     * every field is carried over and only the point and address change; a
     * partial body would blank the type, scene and photos.
     */
    fun moved(locationId: String, point: LatLng) {
        val state = store.state
        val location = state.location(locationId) ?: return
        if (state.movingId != null) {
            // One save at a time: two overlapping PUTs on one record race,
            // and the loser silently wins on the server.
            store.notice(str(S.desktop_map_wait_previous_move), NoticeTone.Info)
            snapBack()
            return
        }
        store.update { copy(pendingMove = ScenePin(locationId, point)) }
        store.spawn {
            if (askBoundary(point, BoundaryMode.Move, name = location.name) != BoundaryChoice.Confirm) {
                snapBack()
                return@spawn
            }
            store.update { copy(movingId = locationId) }
            val address = store.canvas.reverseGeocode(point)?.address
            val draft = LocationDraft(
                cityId = location.cityId.ifBlank { state.selectedCityId.orEmpty() },
                name = location.name,
                type = location.type,
                subTypes = location.subTypes,
                description = location.description,
                address = address?.takeIf { it.isNotBlank() } ?: location.address,
                sceneNumber = location.sceneNumber,
                point = point,
                attachments = location.attachments,
            )
            when (val result = store.repository.updateLocation(locationId, draft)) {
                is ZillitResult.Success -> {
                    store.notice(
                        str(S.desktop_map_location_moved, location.name.ifBlank { str(S.location) }),
                        NoticeTone.Success,
                    )
                    // Held until the list carries the new point, so the pin
                    // never flickers back through its old position.
                    store.update {
                        copy(
                            locations = locations.map {
                                if (it.id == locationId) it.copy(point = point, address = draft.address) else it
                            },
                            pendingMove = null,
                            movingId = null,
                        )
                    }
                    store.hooks.reloadLocations()
                }
                is ZillitResult.Failure -> {
                    store.failed(result.error, str(S.desktop_map_could_not_move))
                    store.update { copy(movingId = null) }
                    snapBack()
                }
            }
        }
    }

    /**
     * Returns a dropped pin to where it is stored. The page moves the marker
     * back itself when the scene no longer holds it elsewhere.
     */
    private fun snapBack() = store.update { copy(pendingMove = null, snapNonce = snapNonce + 1) }
}
