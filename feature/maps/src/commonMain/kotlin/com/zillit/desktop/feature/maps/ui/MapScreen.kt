@file:Suppress("LongMethod") // Screens and dialogs are linear layouts; splitting hides the form.

package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.ZillitLocationField
import com.zillit.desktop.feature.maps.domain.DEFAULT_TYPE_GLYPHS
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation

/**
 * The map tool: a city picker, the city's pins and zones, and the editors —
 * beside a live map canvas when the host provides one.
 *
 * [canvas] is the map surface slot. Null (tests, hosts without an embedded
 * browser) keeps the list-only layout; non-null takes the web's split — the
 * map as the main pane with the pin list beside it.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    onEvent: (MapEvent) -> Unit,
    canvas: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            MapHeader(state = state, onEvent = onEvent)
            if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to the map tool.")
            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(
                            text = "Dismiss",
                            onClick = { onEvent(MapEvent.DismissError) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    },
                )
            }
            if (canvas == null) {
                ListOnlyBody(state = state, onEvent = onEvent)
            } else {
                SplitBody(state = state, onEvent = onEvent, canvas = canvas)
            }
        }
        state.pinEditor?.let { PinEditorDialog(state = state, editor = it, onEvent = onEvent) }
        state.cityEditor?.let { CityEditorDialog(editor = it, onEvent = onEvent) }
    }
}

@Composable
private fun MapHeader(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    ZillitPageHeader(
        title = "Map",
        description = "Locations and studio zones by city — open any pin in your maps app.",
        actions = {
            if (state.viewer.mayEdit) {
                ZillitButton(
                    text = "Add city",
                    onClick = { onEvent(MapEvent.NewCity) },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitButton(
                    text = "Add zone",
                    onClick = { onEvent(MapEvent.NewPin(isZone = true)) },
                    variant = ButtonVariant.Secondary,
                    enabled = state.selectedCityId != null,
                )
                ZillitButton(
                    text = "Add location",
                    onClick = { onEvent(MapEvent.NewPin(isZone = false)) },
                    enabled = state.selectedCityId != null,
                    loading = state.busy,
                )
            }
        },
    )
}

/** The original canvas-less layout: pickers in a row, the list below. */
@Composable
private fun ListOnlyBody(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        CityPicker(state = state, onEvent = onEvent)
        TypeFilter(state = state, onEvent = onEvent)
    }
    ListContent(state = state, onEvent = onEvent)
}

/**
 * The web's split (`map_components/GoogleMapComponent.jsx:922-1068`): the map
 * owns the pane, the pin list rides beside it. The canvas keeps drawing under
 * an auth failure notice rather than being swapped out, so a key rejection
 * reads as a message, not a vanished tool.
 */
@Composable
private fun ColumnScope.SplitBody(
    state: MapUiState,
    onEvent: (MapEvent) -> Unit,
    canvas: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // The canvas is a heavyweight browser surface, which paints above
            // every Compose pixel in the window — the editor dialogs included.
            // It steps aside while one is open (the engine keeps the browser
            // alive off-screen, so it returns instantly) rather than burying
            // the dialog it just asked for.
            val editorOpen = state.pinEditor != null || state.cityEditor != null
            if (!editorOpen) canvas()
            state.canvasError?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(ZillitTheme.spacing.md),
                )
            }
        }
        Column(
            modifier = Modifier.width(SIDE_PANE_WIDTH).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            CityPicker(state = state, onEvent = onEvent)
            TypeFilter(state = state, onEvent = onEvent)
            ListContent(state = state, onEvent = onEvent)
        }
    }
}

@Composable
private fun ListContent(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    when {
        state.loading && state.pins.isEmpty() ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        state.cities.isEmpty() -> ZillitText(
            text = "No cities yet — add the first one to start pinning locations.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
        else -> PinList(state = state, onEvent = onEvent)
    }
}

@Composable
private fun TypeFilter(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    if (state.types.isEmpty()) return
    ZillitSelect(
        value = state.typeFilter,
        options = listOf<String?>(null) + state.types.map { it.name },
        onSelect = { onEvent(MapEvent.FilterType(it)) },
        label = { it ?: "All types" },
        modifier = Modifier.width(SELECT_WIDTH),
    )
}

@Composable
private fun CityPicker(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    if (state.cities.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSelect(
            value = state.selectedCity,
            options = state.cities,
            onSelect = { it?.let { city -> onEvent(MapEvent.SelectCity(city.id)) } },
            label = { city: MapCity? -> city?.let { "${it.name} (${it.locationCount})" } ?: "Pick a city" },
            modifier = Modifier.width(SELECT_WIDTH),
        )
        // The order of this list is the production's own — the city it works
        // in most belongs at the top. Arranged here rather than by dragging
        // a dropdown open, which no pointer enjoys.
        val index = state.cities.indexOfFirst { it.id == state.selectedCityId }
        if (index >= 0 && state.cities.size > 1) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronUp,
                contentDescription = "Move city up",
                onClick = { onEvent(MapEvent.MoveCity(state.cities[index].id, up = true)) },
                enabled = index > 0,
            )
            ZillitIconButton(
                icon = ZillitIcons.ChevronDown,
                contentDescription = "Move city down",
                onClick = { onEvent(MapEvent.MoveCity(state.cities[index].id, up = false)) },
                enabled = index < state.cities.lastIndex,
            )
        }
    }
}

@Composable
private fun PinList(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        if (state.zones.isNotEmpty()) {
            item {
                ZillitText(
                    text = "STUDIO ZONES",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            items(state.zones, key = { "z:${it.id}" }) { zone -> PinRow(state, zone, onEvent) }
        }
        item {
            ZillitText(
                text = "LOCATIONS",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            )
        }
        if (state.locations.isEmpty()) {
            item {
                ZillitText(
                    text = "No locations in this city yet.",
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        items(state.locations, key = { "l:${it.id}" }) { pin -> PinRow(state, pin, onEvent) }
    }
}

@Composable
private fun PinRow(state: MapUiState, pin: MapLocation, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = if (pin.isStudioZone) "◎" else DEFAULT_TYPE_GLYPHS[pin.type] ?: "📍",
                style = ZillitTheme.typography.titleSmall,
            )
            Column(Modifier.weight(1f)) {
                ZillitText(text = pin.name.ifBlank { "Untitled" }, style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    text = buildList {
                        if (pin.isStudioZone) add("${pin.radiusMiles.toInt()} mi radius") else add(pin.type)
                        pin.subTypes.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
                        if (pin.sceneNumber.isNotBlank()) add("Sc. ${pin.sceneNumber}")
                        if (pin.address.isNotBlank()) add(pin.address)
                        pin.lat?.let { lat -> pin.lng?.let { lng -> add("$lat, $lng") } }
                    }.joinToString("  ·  "),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(
                text = "Open in Maps",
                onClick = { onEvent(MapEvent.OpenInMaps(pin.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = pin.mapsUrl != null,
            )
            if (state.viewer.mayEdit) {
                ZillitButton(
                    text = "Edit",
                    onClick = { onEvent(MapEvent.EditPin(pin.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = "Delete",
                    onClick = { onEvent(MapEvent.DeletePin(pin.id)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun PinEditorDialog(state: MapUiState, editor: PinEditor, onEvent: (MapEvent) -> Unit) {
    val title = when {
        editor.locationId == null && editor.isZone -> "Add studio zone"
        editor.locationId == null -> "Add location"
        editor.isZone -> "Edit studio zone"
        else -> "Edit location"
    }
    ZillitDialogShell(
        title = title,
        onDismiss = { onEvent(MapEvent.ClosePin) },
        visible = true,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(MapEvent.ClosePin) }, variant = ButtonVariant.Tertiary)
            ZillitButton(text = "Save", onClick = { onEvent(MapEvent.SavePin) }, loading = editor.saving)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = "City",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitSelect(
                value = state.cities.firstOrNull { it.id == editor.cityId },
                options = state.cities,
                onSelect = { it?.let { city -> onEvent(MapEvent.PinChanged(cityId = city.id)) } },
                label = { it?.name ?: "Pick a city" },
            )
            ZillitTextField(
                value = editor.name,
                onValueChange = { onEvent(MapEvent.PinChanged(name = it)) },
                label = "Name",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = editor.latText,
                    onValueChange = { onEvent(MapEvent.PinChanged(latText = it)) },
                    label = "Latitude",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.lngText,
                    onValueChange = { onEvent(MapEvent.PinChanged(lngText = it)) },
                    label = "Longitude",
                    modifier = Modifier.weight(1f),
                )
            }
            if (editor.isZone) {
                ZillitTextField(
                    value = editor.radiusText,
                    onValueChange = { onEvent(MapEvent.PinChanged(radiusText = it)) },
                    label = "Radius (miles)",
                    helperText = "Presets: 30, 40, 50, 60",
                )
            } else {
                // The canvas's click-to-place already answers "where is this?"
                // — but only for a host that has a canvas, and only by panning
                // to a spot already on screen. Searching a place by name is
                // the other half, and the web's pin form has both: an address
                // autocomplete that fills address, lat and lng together
                // (`map-module/hooks/useLocationForm.js:96-98`) beside the map.
                // The pin wire carries all three (`location_address`,
                // `location.lat`, `location.long` — MapRepositoryImpl.kt:210),
                // so a pick persists. The pin's own name is left alone, as the
                // web's autocomplete leaves it.
                ZillitLocationField(
                    text = editor.address,
                    onTextChange = { onEvent(MapEvent.PinChanged(address = it)) },
                    onPicked = {
                        onEvent(
                            MapEvent.PinChanged(
                                address = it.address.ifBlank { it.name },
                                latText = it.lat.toString(),
                                lngText = it.lng.toString(),
                            ),
                        )
                    },
                    label = "Address",
                    initial = editor.pickedAt(),
                )
                if (state.types.isNotEmpty()) {
                    ZillitText(
                        text = "Type",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitSelect(
                        value = editor.type,
                        options = state.types.map { it.name },
                        onSelect = { onEvent(MapEvent.PinChanged(type = it)) },
                        label = { it },
                    )
                    val subs = state.types.firstOrNull { it.name == editor.type }?.subTypes.orEmpty()
                    subs.forEach { sub ->
                        ZillitCheckbox(
                            checked = sub in editor.subTypes,
                            onCheckedChange = { onEvent(MapEvent.PinChanged(toggleSubType = sub)) },
                            label = sub,
                        )
                    }
                }
                ZillitTextField(
                    value = editor.sceneNumber,
                    onValueChange = { onEvent(MapEvent.PinChanged(sceneNumber = it)) },
                    label = "Scene number (optional)",
                )
                ZillitTextField(
                    value = editor.description,
                    onValueChange = { onEvent(MapEvent.PinChanged(description = it)) },
                    label = "Notes",
                    singleLine = false,
                )
            }
        }
    }
}

@Composable
private fun CityEditorDialog(editor: CityEditor, onEvent: (MapEvent) -> Unit) {
    ZillitDialogShell(
        title = "Add city",
        onDismiss = { onEvent(MapEvent.CloseCity) },
        visible = true,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(MapEvent.CloseCity) }, variant = ButtonVariant.Tertiary)
            ZillitButton(text = "Save", onClick = { onEvent(MapEvent.SaveCity) }, loading = editor.saving)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = editor.name,
                onValueChange = { onEvent(MapEvent.CityChanged(name = it)) },
                label = "Name",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = editor.latText,
                    onValueChange = { onEvent(MapEvent.CityChanged(latText = it)) },
                    label = "Latitude",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.lngText,
                    onValueChange = { onEvent(MapEvent.CityChanged(lngText = it)) },
                    label = "Longitude",
                    modifier = Modifier.weight(1f),
                )
            }
            ZillitTextField(
                value = editor.radiusText,
                onValueChange = { onEvent(MapEvent.CityChanged(radiusText = it)) },
                label = "Zone radius in miles (0 = none)",
            )
            ZillitTextField(
                value = editor.description,
                onValueChange = { onEvent(MapEvent.CityChanged(description = it)) },
                label = "Description",
                singleLine = false,
            )
        }
    }
}

/** Where the picker's map should open: where the pin already stands. */
private fun PinEditor.pickedAt(): PickedLocation? {
    val at = latText.trim().toDoubleOrNull() ?: return null
    val to = lngText.trim().toDoubleOrNull() ?: return null
    return PickedLocation(name = name, address = address, lat = at, lng = to)
}

private val SELECT_WIDTH = 240.dp

/** The pin list beside the map — wide enough for a row's three buttons. */
private val SIDE_PANE_WIDTH = 430.dp
