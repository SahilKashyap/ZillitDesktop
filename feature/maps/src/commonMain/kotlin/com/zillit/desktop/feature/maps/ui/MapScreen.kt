@file:Suppress("LongMethod") // Screens and dialogs are linear layouts; splitting hides the form.

package com.zillit.desktop.feature.maps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.zillit.desktop.feature.maps.domain.DEFAULT_TYPE_GLYPHS
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation

/** The map tool: a city picker, the city's pins and zones, and the editors. */
@Composable
fun MapScreen(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                CityPicker(state = state, onEvent = onEvent)
                if (state.types.isNotEmpty()) {
                    ZillitSelect(
                        value = state.typeFilter,
                        options = listOf<String?>(null) + state.types.map { it.name },
                        onSelect = { onEvent(MapEvent.FilterType(it)) },
                        label = { it ?: "All types" },
                        modifier = Modifier.width(SELECT_WIDTH),
                    )
                }
            }
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
        state.pinEditor?.let { PinEditorDialog(state = state, editor = it, onEvent = onEvent) }
        state.cityEditor?.let { CityEditorDialog(editor = it, onEvent = onEvent) }
    }
}

@Composable
private fun CityPicker(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    if (state.cities.isEmpty()) return
    ZillitSelect(
        value = state.selectedCity,
        options = state.cities,
        onSelect = { it?.let { city -> onEvent(MapEvent.SelectCity(city.id)) } },
        label = { city: MapCity? -> city?.let { "${it.name} (${it.locationCount})" } ?: "Pick a city" },
        modifier = Modifier.width(SELECT_WIDTH),
    )
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
                ZillitTextField(
                    value = editor.address,
                    onValueChange = { onEvent(MapEvent.PinChanged(address = it)) },
                    label = "Address",
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

private val SELECT_WIDTH = 240.dp
