package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.PlaceKind
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.SharePayload
import com.zillit.desktop.feature.maps.domain.buildDirectionsShare
import com.zillit.desktop.feature.maps.domain.buildLocationShare
import com.zillit.desktop.feature.maps.domain.toFixed
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Get Directions (`MapView`'s directions panel) and sharing (req N) — the two
 * ways a place leaves the map.
 */
internal class DirectionsController(private val store: MapStore) {

    private var pickupJob: Job? = null
    private var dropJob: Job? = null

    fun onEvent(event: MapEvent.Directions) {
        when (event) {
            MapEvent.Directions.Close -> close()
            is MapEvent.Directions.PickupText -> pickupText(event.text)
            is MapEvent.Directions.PickupPick -> pick(event.prediction, pickup = true)
            MapEvent.Directions.UseCurrentLocation -> useCurrentLocation(reportFailure = true)
            is MapEvent.Directions.DropText -> dropText(event.text)
            is MapEvent.Directions.DropPick -> pick(event.prediction, pickup = false)
            MapEvent.Directions.Share -> shareRoute()
            MapEvent.Directions.CallDriver -> store.effect(MapEffect.OpenTool(TRANSPORTATION_PATH))
        }
    }

    /** "Directions" on a pin's card or a Places result: a route to it from here. */
    fun startTo(point: LatLng?, name: String, address: String) {
        if (point == null) {
            store.notice(str(S.desktop_map_invalid_destination), NoticeTone.Error)
            return
        }
        val destination = name.ifBlank { str(S.drive_pick_dest_title) }
        store.update {
            copy(
                directions = DirectionsState(
                    drop = RouteEnd(point, address.ifBlank { destination }, destination),
                    dropText = address.ifBlank { destination },
                    usingCurrentLocation = true,
                ),
            )
        }
        store.canvas.clearRoute()
        useCurrentLocation(reportFailure = false)
    }

    fun startTo(location: MapLocation) = startTo(location.point, location.name, location.address)

    private fun update(reducer: DirectionsState.() -> DirectionsState) =
        store.update { copy(directions = directions?.reducer()) }

    /**
     * Pickup from this machine's position — the page's fix, else the host's
     * approximate one. Without either the pickup is left for the person to
     * search, as the web does when location access is refused.
     */
    private fun useCurrentLocation(reportFailure: Boolean) {
        store.spawn {
            val here = store.position.current()
            if (here == null) {
                if (reportFailure) {
                    store.notice(str(S.desktop_map_no_current_location), NoticeTone.Error)
                } else {
                    store.notice(str(S.desktop_map_enable_location_access), NoticeTone.Info)
                    update { copy(usingCurrentLocation = false, pickup = null) }
                }
                return@spawn
            }
            update {
                val label = str(S.desktop_map_my_current_location)
                copy(pickup = RouteEnd(here, label), pickupText = label, usingCurrentLocation = true)
            }
            route()
            // The placeholder is replaced by a real address, so a trip booked
            // from it stores somewhere meaningful.
            val address = store.canvas.reverseGeocode(here)?.address?.takeIf { it.isNotBlank() } ?: return@spawn
            update {
                if (pickup?.point == here) copy(pickup = pickup.copy(address = address), pickupText = address) else this
            }
        }
    }

    private fun pickupText(text: String) {
        update { copy(pickupText = text, usingCurrentLocation = false) }
        pickupJob?.cancel()
        pickupJob = suggest(text) { found -> if (pickupText == text) copy(pickupSuggestions = found) else this }
    }

    private fun dropText(text: String) {
        update { copy(dropText = text) }
        dropJob?.cancel()
        dropJob = suggest(text) { found -> if (dropText == text) copy(dropSuggestions = found) else this }
    }

    private fun suggest(text: String, apply: DirectionsState.(List<PlacePrediction>) -> DirectionsState): Job? {
        if (text.isBlank()) {
            update { apply(emptyList()) }
            return null
        }
        return store.spawn {
            delay(SEARCH_DEBOUNCE_MS)
            val found = store.canvas.predictions(text, PlaceKind.Any).orEmpty()
            update { apply(found) }
        }
    }

    private fun pick(prediction: PlacePrediction, pickup: Boolean) {
        update {
            if (pickup) copy(pickupSuggestions = emptyList(), pickupText = prediction.description)
            else copy(dropSuggestions = emptyList(), dropText = prediction.description)
        }
        store.spawn {
            val place = store.canvas.placeDetails(prediction.placeId) ?: return@spawn
            val end = RouteEnd(place.point, place.address.ifBlank { place.name }, place.name)
            update {
                if (pickup) copy(pickup = end, pickupText = end.address, usingCurrentLocation = false)
                else copy(drop = end, dropText = end.address)
            }
            route()
        }
    }

    private suspend fun route() {
        val state = store.state.directions ?: return
        val origin = state.pickup ?: return
        val destination = state.drop ?: return
        update { copy(loading = true, route = null) }
        val found = store.canvas.route(origin.point, destination.point)
        update { copy(loading = false, route = found) }
        if (found == null) store.notice(str(S.desktop_map_no_directions), NoticeTone.Error)
    }

    private fun close() {
        pickupJob?.cancel()
        dropJob?.cancel()
        store.update { copy(directions = null) }
        store.canvas.clearRoute()
        store.state.selectedCity?.panTarget?.let { store.canvas.panTo(it, CITY_ZOOM) }
    }

    // Share ----------------------------------------------------------------------

    fun shareLocation(location: MapLocation?) {
        val payload = location?.let(::buildLocationShare) ?: return
        openShare(payload)
    }

    /** The route, with both units exactly as the panel shows them. */
    private fun shareRoute() {
        val state = store.state.directions ?: return
        val route = state.route ?: return
        val payload = buildDirectionsShare(
            originName = state.pickup?.address,
            origin = state.pickup?.point,
            destinationName = state.drop?.name?.ifBlank { null } ?: state.drop?.address,
            destination = state.drop?.point,
            distance = "${route.distanceText} (${milesText(route.distanceMeters)} mi)",
            eta = route.durationText.takeIf { it.isNotBlank() }?.let { "~$it" },
        ) ?: return
        openShare(payload)
    }

    private fun openShare(payload: SharePayload) {
        val people = store.host.share?.people().orEmpty().sortedBy { it.name.lowercase() }
        store.update {
            copy(
                dialog = MapDialog.Share(
                    ShareState(title = payload.title, text = payload.text, url = payload.url, people = people),
                ),
            )
        }
    }

    fun onShareEvent(event: MapEvent.Dialogs) {
        val share = (store.state.dialog as? MapDialog.Share)?.state ?: return
        when (event) {
            is MapEvent.Dialogs.ShareToggle -> updateShare {
                copy(selected = if (event.userId in selected) selected - event.userId else selected + event.userId)
            }
            is MapEvent.Dialogs.ShareQuery -> updateShare { copy(query = event.text) }
            MapEvent.Dialogs.ShareCopy -> {
                store.effect(MapEffect.Copy(share.text))
                store.notice(str(S.copy_success), NoticeTone.Success)
            }
            MapEvent.Dialogs.ShareOpenMaps -> store.effect(MapEffect.OpenUrl(share.url))
            MapEvent.Dialogs.ShareSend -> send(share)
            else -> Unit
        }
    }

    private fun updateShare(reducer: ShareState.() -> ShareState) = store.update {
        val open = dialog as? MapDialog.Share ?: return@update this
        copy(dialog = MapDialog.Share(open.state.reducer()))
    }

    private fun send(share: ShareState) {
        val host = store.host.share ?: return
        if (share.selected.isEmpty()) return
        updateShare { copy(sending = true) }
        store.spawn {
            when (val result = host.send(share.selected.toList(), share.text)) {
                is ZillitResult.Success -> {
                    store.update { copy(dialog = null) }
                    val count = result.data
                    store.notice(
                        if (count == 1) {
                            str(S.desktop_map_shared_with_one)
                        } else {
                            str(S.drive_shared_with_count_plural, count)
                        },
                        NoticeTone.Success,
                    )
                }
                is ZillitResult.Failure -> {
                    updateShare { copy(sending = false) }
                    store.failed(result.error, str(S.desktop_map_could_not_share))
                }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val CITY_ZOOM = 13

        /** The web's `metersToMiles`. */
        const val MILES_PER_METER = 0.000621371

        /** Where Call a Driver goes — the web opens the Transportation Hub's modal. */
        const val TRANSPORTATION_PATH = "/film-tools/transportation"

        fun milesText(meters: Double): String = toFixed(meters * MILES_PER_METER, 1)
    }
}
