package com.zillit.desktop.feature.maps.ui

import androidx.lifecycle.viewModelScope
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.feature.maps.data.MapCanvasClient
import com.zillit.desktop.feature.maps.domain.BoundaryChoice
import com.zillit.desktop.feature.maps.domain.CanvasTheme
import com.zillit.desktop.feature.maps.domain.MapCanvasEvent
import com.zillit.desktop.feature.maps.domain.MapCanvasHost
import com.zillit.desktop.feature.maps.domain.MapHost
import com.zillit.desktop.feature.maps.domain.MapList
import com.zillit.desktop.feature.maps.domain.MapRepository
import com.zillit.desktop.feature.maps.domain.MapSyncEvent
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.domain.MarkerActionKind
import com.zillit.desktop.feature.maps.domain.SceneGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The map tool — the web's `/film-tools/map` (`components/map-module`):
 * cities, typed pins and studio zones on a live Google map, with the panels,
 * forms and boundary rules around it.
 *
 * This class wires; the work is in one controller per area of the tool
 * (`CityController`, `LocationController`, `ZoneController`,
 * `TypeController`, `PinController`, `DirectionsController`), which share
 * the state through a [MapStore].
 */
class MapViewModel(
    private val repository: MapRepository,
    private val resolveViewer: () -> MapViewer,
    /** The map surface, or null on hosts without one (tests, no browser). */
    canvas: MapCanvasHost? = null,
    private val host: MapHost = MapHost(),
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<MapUiState, MapEvent, MapEffect>(MapUiState()) {

    private val canvasClient = MapCanvasClient(canvas, viewModelScope)

    private val store: MapStore = object : MapStore {
        override val state: MapUiState get() = currentState
        override val repository: MapRepository get() = this@MapViewModel.repository
        override val canvas: MapCanvasClient get() = canvasClient
        override val host: MapHost get() = this@MapViewModel.host
        override val rights: RightsRequestBus? get() = this@MapViewModel.rights
        override val hooks: MapHooks get() = this@MapViewModel.hooks

        override fun update(reducer: MapUiState.() -> MapUiState) = setState(reducer)
        override fun effect(effect: MapEffect) = sendEffect(effect)
        override fun spawn(block: suspend CoroutineScope.() -> Unit): Job = launch(block)
    }

    private val cities = CityController(store)
    private val locations = LocationController(store)
    private val zones = ZoneController(store)
    private val types = TypeController(store)
    private val pins = PinController(store, cities, locations)
    private val directions = DirectionsController(store)

    private val hooks = object : MapHooks {
        override fun reloadCities() = cities.load()
        override fun reloadLocations() {
            currentState.selectedCityId?.let(locations::load)
        }
        override fun reloadZones() {
            currentState.selectedCityId?.let(zones::load)
        }
        override fun selectCity(cityId: String) = this@MapViewModel.selectCity(cityId)
        override fun closeAllPanels() = setState {
            copy(panels = emptyList(), locationForm = null, zoneForm = null, listView = null)
        }
    }

    private var listening = false

    init {
        cities.pendingPinReady = { pin -> locations.openNew(FormOrigin.Map, point = pin.point, name = pin.name) }
        types.createdForForm = { name -> locations.pickType(name) }
    }

    /** Called each time the tool's window composes; the web refetches on every mount. */
    fun start() {
        setState { copy(viewer = resolveViewer()) }
        canvasClient.start()
        listenOnce()
        cities.load()
        types.reload()
        currentState.selectedCityId?.let { cityId ->
            locations.load(cityId)
            zones.load(cityId)
        }
    }

    /** The app's colours, for the cards the map page draws itself. */
    fun useTheme(theme: CanvasTheme) = canvasClient.theme(theme)

    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            state.map { it.toScene() }.distinctUntilChanged().collect { canvasClient.render(it) }
        }
        launch { canvasClient.events.collect(::onCanvas) }
        launch { repository.sync.collect(::onSync) }
        rights?.let { bus -> launch { bus.showing.collect { open -> setState { copy(frameDialogOpen = open) } } } }
        launch {
            host.badges.cityUnread.collect { unread ->
                setState { copy(cityUnread = unread) }
                // ZL-19984: a badge for the city already on screen is read at once.
                currentState.selectedCityId?.takeIf { (unread[it] ?: 0) > 0 }?.let(host.badges::markCityRead)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per area.
    override fun onEvent(event: MapEvent) {
        when (event) {
            is MapEvent.Toolbar -> onToolbar(event)
            is MapEvent.Bars -> onBars(event)
            is MapEvent.Directions -> directions.onEvent(event)
            is MapEvent.Cities -> cities.onEvent(event)
            is MapEvent.LocationForm -> when (event) {
                MapEvent.LocationForm.NewType -> types.openDialog(currentState.locationForm?.typeSearch.orEmpty())
                else -> locations.onFormEvent(event)
            }
            is MapEvent.Locations -> when (event) {
                is MapEvent.Locations.Share -> directions.shareLocation(currentState.location(event.locationId))
                is MapEvent.Locations.Directions -> currentState.location(event.locationId)?.let(directions::startTo)
                else -> locations.onListEvent(event)
            }
            is MapEvent.Zones -> zones.onEvent(event)
            is MapEvent.Types -> types.onEvent(event)
            is MapEvent.Dialogs -> onDialog(event)
        }
    }

    // Selection ------------------------------------------------------------------

    /**
     * A city chosen — by hand, by the ladder, or after creating one. Mirrors the
     * web's "reset all state when city changes": every mode and panel closes,
     * the city's pins and zones are fetched, its badges read, and the camera
     * goes to it.
     */
    private fun selectCity(cityId: String) {
        val city = currentState.cities.firstOrNull { it.id == cityId } ?: return
        if (currentState.selectedCityId != cityId) {
            setState {
                copy(
                    selectedCityId = cityId,
                    activeZoneId = null,
                    search = null,
                    filterOpen = false,
                    typeFilters = emptySet(),
                    pinMode = false,
                    pendingMove = null,
                    directions = null,
                    panels = emptyList(),
                    locationForm = null,
                    zoneForm = null,
                    zoneFilter = "",
                    locations = emptyList(),
                    zones = emptyList(),
                    cityBounds = null,
                )
            }
            canvasClient.clearPreview()
            canvasClient.clearRoute()
            locations.load(cityId)
            zones.load(cityId)
            val centre = city.panTarget
            if (centre != null) {
                launch {
                    val bounds = canvasClient.cityBounds(centre)
                    setState { if (selectedCityId == cityId) copy(cityBounds = bounds) else this }
                }
            }
        }
        launch { host.prefs.setLastVisitedCityId(cityId) }
        host.badges.markCityRead(cityId)
        city.panTarget?.let { canvasClient.panTo(it, CITY_ZOOM) }
    }

    // Toolbar and bars -------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // One line per toolbar control.
    private fun onToolbar(event: MapEvent.Toolbar) {
        when (event) {
            MapEvent.Toolbar.Back -> sendEffect(MapEffect.Leave)
            MapEvent.Toolbar.FitAll -> fitAll()
            MapEvent.Toolbar.ToggleSearch -> setState {
                if (search != null) copy(search = null) else copy(search = SearchState(), filterOpen = false)
            }
            MapEvent.Toolbar.ToggleFilter -> setState {
                if (filterOpen) copy(filterOpen = false) else copy(filterOpen = true, search = null)
            }
            MapEvent.Toolbar.TogglePinMode -> pins.togglePinMode()
            MapEvent.Toolbar.ToggleListView -> togglePanelLike(
                isOpen = currentState.listView != null,
                open = { setState { copy(listView = ListViewState()) } },
                close = { setState { copy(listView = null, panels = emptyList(), locationForm = null) } },
            )
            MapEvent.Toolbar.ToggleZoneList -> togglePanelLike(
                isOpen = currentState.topPanel == MapPanel.ZoneList,
                open = {
                    store.pushPanel(MapPanel.ZoneList)
                    hooks.reloadZones()
                },
                close = { store.popPanel { it == MapPanel.ZoneList } },
            )
            MapEvent.Toolbar.ToggleTypes -> togglePanelLike(
                isOpen = currentState.topPanel == MapPanel.Types,
                open = { store.pushPanel(MapPanel.Types) },
                close = { store.popPanel { it == MapPanel.Types } },
            )
            // Both flags clear: a guide brought back still collapsed would
            // look like the button did nothing.
            MapEvent.Toolbar.ShowGuide -> setState { copy(guide = SceneGuide(visible = true, collapsed = false)) }
        }
    }

    /** The toolbar's panels are mutually exclusive: opening one closes the rest. */
    private inline fun togglePanelLike(isOpen: Boolean, open: () -> Unit, close: () -> Unit) {
        if (isOpen) {
            close()
        } else {
            hooks.closeAllPanels()
            open()
        }
    }

    /** "Fit All" — every pin the filter shows; one pin settles at street zoom. */
    private fun fitAll() {
        val points = currentState.filteredLocations.mapNotNull { it.point }
        if (points.isEmpty()) {
            store.notice("No locations to zoom to", NoticeTone.Info)
            return
        }
        canvasClient.fitPoints(points)
    }

    private fun onBars(event: MapEvent.Bars) {
        when (event) {
            is MapEvent.Bars.SearchQuery -> setState { copy(search = SearchState(event.query)) }
            is MapEvent.Bars.SearchPick -> pickSearchResult(event.locationId)
            is MapEvent.Bars.ToggleTypeFilter -> setState {
                copy(typeFilters = if (event.type in typeFilters) typeFilters - event.type else typeFilters + event.type)
            }
            MapEvent.Bars.ClearTypeFilters -> setState { copy(typeFilters = emptySet()) }
            MapEvent.Bars.ExitPinMode -> pins.exitPinMode()
            MapEvent.Bars.DismissCanvasError -> setState { copy(canvasError = null) }
        }
    }

    /**
     * A search result: a type filter that would hide it is cleared so the pin
     * is there to see, then the camera goes to it and its card opens.
     */
    private fun pickSearchResult(locationId: String) {
        val location = currentState.location(locationId) ?: return
        if (location.point == null) return
        setState {
            copy(
                search = null,
                typeFilters = if (typeFilters.isNotEmpty() && location.type !in typeFilters) emptySet() else typeFilters,
            )
        }
        canvasClient.focusMarker(locationId, SEARCH_ZOOM)
    }

    // Dialogs ------------------------------------------------------------------------

    private fun onDialog(event: MapEvent.Dialogs) {
        when (event) {
            is MapEvent.Dialogs.Boundary -> pins.answer(event.choice)
            MapEvent.Dialogs.Confirm -> confirm()
            MapEvent.Dialogs.Dismiss -> when (val open = currentState.dialog) {
                is MapDialog.Boundary -> pins.answer(BoundaryChoice.Cancel)
                is MapDialog.AddCity -> cities.onEvent(MapEvent.Cities.AddCancel)
                // A confirmed action runs to its end; the dialog closes with it.
                is MapDialog.Confirm -> if (!open.busy) closeDialog()
                else -> closeDialog()
            }
            else -> directions.onShareEvent(event)
        }
    }

    private fun closeDialog() = setState { copy(dialog = null) }

    private fun confirm() {
        val dialog = currentState.dialog as? MapDialog.Confirm ?: return
        if (dialog.busy) return
        setState { copy(dialog = dialog.copy(busy = true)) }
        val done = { setState { if (this.dialog is MapDialog.Confirm) copy(dialog = null) else this } }
        when (val action = dialog.action) {
            is ConfirmAction.DeleteCity -> cities.delete(action.id, done)
            is ConfirmAction.DeleteZone -> zones.delete(action.id, done)
            is ConfirmAction.DeleteLocation -> locations.delete(action.id, done)
            is ConfirmAction.DeleteType -> types.delete(action.id, done)
            is ConfirmAction.UpdateType -> types.update(action.id, action.form, done)
        }
    }

    // The map page ---------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // One line per page event.
    private fun onCanvas(event: MapCanvasEvent) {
        when (event) {
            MapCanvasEvent.Ready -> setState { copy(canvasError = null) }
            is MapCanvasEvent.Failed -> setState { copy(canvasError = event.message) }
            is MapCanvasEvent.MarkerAction -> onMarkerAction(event)
            is MapCanvasEvent.ZoneEdit -> store.withPost {
                hooks.closeAllPanels()
                zones.openForm(event.id)
            }
            is MapCanvasEvent.PreviewAdd -> pins.addAt(event.point, event.name, fromPlace = false)
            is MapCanvasEvent.PlacePin -> pins.addAt(event.point, event.name, fromPlace = true)
            is MapCanvasEvent.PlaceDirections -> directions.startTo(event.point, event.name, event.address)
            is MapCanvasEvent.MarkerDragged -> pins.moved(event.id, event.point)
            is MapCanvasEvent.DraftZoneMoved -> zones.draftMoved(event.point)
            is MapCanvasEvent.DraftPinMoved -> locations.draftMoved(event.point)
            MapCanvasEvent.CitiesOpen -> cities.onEvent(MapEvent.Cities.Open)
            is MapCanvasEvent.Guide -> setState {
                copy(guide = SceneGuide(visible = !event.dismissed, collapsed = event.collapsed))
            }
        }
    }

    private fun onMarkerAction(event: MapCanvasEvent.MarkerAction) {
        val location = currentState.location(event.id) ?: return
        when (event.action) {
            MarkerActionKind.Edit -> store.withPost {
                hooks.closeAllPanels()
                locations.openEdit(location.id)
            }
            MarkerActionKind.Share -> directions.shareLocation(location)
            // "View" opens the full details — beside the list of every
            // location, which is where the web's View takes you.
            MarkerActionKind.View -> {
                hooks.closeAllPanels()
                setState { copy(listView = ListViewState()) }
                store.pushPanel(MapPanel.LocationDetail(location.id))
            }
            MarkerActionKind.Directions -> directions.startTo(location)
        }
    }

    // Other clients ----------------------------------------------------------------------

    private fun onSync(event: MapSyncEvent) {
        when (event) {
            MapSyncEvent.CitiesReordered -> cities.load()
            is MapSyncEvent.Refetch -> when (event.what) {
                MapList.Cities -> cities.load()
                MapList.Types -> types.reload()
                MapList.Locations -> {
                    hooks.reloadLocations()
                    hooks.reloadZones()
                }
            }
            is MapSyncEvent.CityDeleted -> {
                val wasSelected = currentState.selectedCityId == event.id
                setState { applySync(event) }
                // The city on screen is gone: the ladder picks another.
                if (wasSelected) {
                    setState { copy(selectedCityId = null, locations = emptyList(), zones = emptyList(), activeZoneId = null) }
                    cities.load()
                }
            }
            is MapSyncEvent.LocationUpserted, is MapSyncEvent.LocationDeleted -> {
                val before = currentState.activeZone
                setState { applySync(event) }
                launch {
                    zones.reconcileActive()
                    zones.refit(before, currentState.activeZone)
                }
            }
            else -> setState { applySync(event) }
        }
    }

    private companion object {
        /** The web's zoom on selecting a city. */
        const val CITY_ZOOM = 13

        /** The web's zoom on a search result. */
        const val SEARCH_ZOOM = 16
    }
}
