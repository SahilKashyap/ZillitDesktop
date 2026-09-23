package com.zillit.desktop.feature.maps.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.ui.screen.CitiesPanel
import com.zillit.desktop.feature.maps.ui.screen.CanvasErrorBar
import com.zillit.desktop.feature.maps.ui.screen.DirectionsPanel
import com.zillit.desktop.feature.maps.ui.screen.EmptyBlock
import com.zillit.desktop.feature.maps.ui.screen.FilterPanel
import com.zillit.desktop.feature.maps.ui.screen.LocationDetailPanel
import com.zillit.desktop.feature.maps.ui.screen.LocationFormPanel
import com.zillit.desktop.feature.maps.ui.screen.LocationListView
import com.zillit.desktop.feature.maps.ui.screen.MapColors
import com.zillit.desktop.feature.maps.ui.screen.MapDialogHost
import com.zillit.desktop.feature.maps.ui.screen.MapIcons
import com.zillit.desktop.feature.maps.ui.screen.MapToast
import com.zillit.desktop.feature.maps.ui.screen.MapToastHost
import com.zillit.desktop.feature.maps.ui.screen.MapToolbar
import com.zillit.desktop.feature.maps.ui.screen.PinModeBanner
import com.zillit.desktop.feature.maps.ui.screen.SearchBar
import com.zillit.desktop.feature.maps.ui.screen.TypesPanel
import com.zillit.desktop.feature.maps.ui.screen.ZoneDetailPanel
import com.zillit.desktop.feature.maps.ui.screen.ZoneFormPanel
import com.zillit.desktop.feature.maps.ui.screen.ZoneListPanel

/**
 * The map tool — the web's `MapView`: the orange toolbar, its bars, the live
 * map with the Cities panel on its left and the tool's panels on its right,
 * the full-width locations list, and the dialogs.
 *
 * [canvas] is the map surface, told whether it may show. It is a browser view
 * that paints above every Compose pixel, so it steps aside whenever something
 * must be seen where it is — a dialog, the list. Null (tests, hosts without an
 * embedded browser) leaves a placeholder where the map would be; every panel
 * still works.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    onEvent: (MapEvent) -> Unit,
    canvas: (@Composable (visible: Boolean) -> Unit)? = null,
    toasts: List<MapToast> = emptyList(),
    onDismissToast: (Long) -> Unit = {},
) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            MapToolbar(state, onEvent)
            Bars(state, onEvent)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (state.topPanel == MapPanel.Cities && state.listView == null) {
                    CitiesPanel(state, onEvent)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    MapArea(state, onEvent, canvas)
                }
                if (state.listView == null) RightPanel(state, onEvent)
            }
        }
        state.listView?.let { list ->
            Row(Modifier.fillMaxSize()) {
                LocationListView(state, list, onEvent, Modifier.weight(1f))
                RightPanel(state, onEvent)
            }
        }
        if (state.viewer.isBlocked) {
            Box(Modifier.fillMaxSize().background(colors.canvas), contentAlignment = Alignment.Center) {
                EmptyBlock(
                    icon = MapIcons.Map,
                    accent = MapColors.Brand,
                    title = str(S.desktop_map_no_access),
                    message = str(S.desktop_map_no_access_hint),
                )
            }
        }
        MapDialogHost(state.dialog, onEvent)
        MapToastHost(toasts, onDismissToast)
    }
}

@Composable
private fun ColumnScope.Bars(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    if (state.citiesLoading || state.locationsLoading || state.zonesLoading) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MapColors.Brand,
            trackColor = MapColors.Brand.copy(alpha = 0.15f),
        )
    }
    state.canvasError?.let { message ->
        CanvasErrorBar(message, onDismiss = { onEvent(MapEvent.Bars.DismissCanvasError) })
    }
    AnimatedVisibility(state.pinMode, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        PinModeBanner(state, onEvent)
    }
    AnimatedVisibility(
        state.search != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        SearchBar(state, onEvent)
    }
    AnimatedVisibility(state.filterOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        FilterPanel(state, onEvent)
    }
    state.directions?.let { directions -> DirectionsPanel(directions, onEvent) }
    if (state.search != null || state.filterOpen || state.directions != null) Spacer(Modifier.height(8.dp))
}

/** The map, or what stands in for it. */
@Composable
private fun MapArea(state: MapUiState, onEvent: (MapEvent) -> Unit, canvas: (@Composable (Boolean) -> Unit)?) {
    val covered = state.canvasCovered || state.viewer.isBlocked
    if (canvas != null) {
        canvas(!covered)
        return
    }
    val noCities = state.cities.isEmpty() && state.citiesLoaded
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyBlock(
            icon = MapIcons.Map,
            accent = MapColors.Brand,
            title = if (noCities) str(S.desktop_map_no_cities_yet) else str(S.desktop_map_cannot_show_here),
            message = if (noCities) {
                str(S.desktop_map_add_first_city)
            } else {
                str(S.desktop_map_no_browser)
            },
            action = {
                // The web's floating control reads "Add City" until there is one.
                ZillitButton(
                    text = if (noCities) str(S.desktop_map_add_city) else str(S.cities),
                    onClick = { onEvent(MapEvent.Cities.Open) },
                    leadingIcon = if (noCities) ZillitIcons.Add else MapIcons.Map,
                )
            },
        )
    }
}

/** The panel on the right, whichever is on top of the stack. */
@Composable
private fun RightPanel(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    val panel = state.topPanel ?: return
    key(panel::class) {
        // Starts hidden so the panel slides in when it opens, as a drawer does.
        val appear = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = appear,
            enter = fadeIn() + slideInHorizontally { it / 4 },
        ) {
            when (panel) {
                MapPanel.Cities -> Unit
                MapPanel.ZoneList -> ZoneListPanel(state, onEvent)
                MapPanel.ZoneForm -> state.zoneForm?.let { ZoneFormPanel(state, it, onEvent) }
                is MapPanel.ZoneDetail -> state.zone(panel.zoneId)?.let { ZoneDetailPanel(state, it, onEvent) }
                MapPanel.Types -> TypesPanel(state, onEvent)
                MapPanel.LocationForm -> state.locationForm?.let { LocationFormPanel(state, it, onEvent) }
                is MapPanel.LocationDetail ->
                    state.location(panel.locationId)?.let { LocationDetailPanel(state, it, onEvent) }
            }
        }
    }
}
