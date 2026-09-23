package com.zillit.desktop.feature.maps.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.domain.LocationRules
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.toFixed
import com.zillit.desktop.feature.maps.ui.LocationFormState
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapUiState

/** The location form (`locations/LocationFormPanel.jsx`). */
@Composable
internal fun LocationFormPanel(state: MapUiState, form: LocationFormState, onEvent: (MapEvent) -> Unit) {
    val heroColor = if (form.type.isNotBlank()) hexColor(state.style(form.type).colorHex) else MapColors.Brand
    SidePanelFrame(
        width = RIGHT_PANEL_WIDTH,
        header = {
            HeroHeader(
                accent = heroColor,
                title = if (form.isEdit) str(S.desktop_location_edit_title) else str(S.desktop_map_new_location),
                subtitle = if (form.isEdit) {
                    str(S.desktop_map_edit_location_subtitle)
                } else {
                    str(S.desktop_map_new_location_subtitle)
                },
                onClose = { onEvent(MapEvent.LocationForm.Close) },
            )
        },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ZillitButton(
                    text = str(S.cancel),
                    onClick = { onEvent(MapEvent.LocationForm.Close) },
                    variant = ButtonVariant.Secondary,
                )
                ZillitButton(
                    text = when {
                        form.saving -> str(S.ah_saving)
                        form.isEdit -> str(S.txt_update_location)
                        else -> str(S.desktop_map_save_location)
                    },
                    onClick = { onEvent(MapEvent.LocationForm.Save) },
                    leadingIcon = ZillitIcons.Save,
                    loading = form.saving,
                )
            }
        },
    ) {
        ZillitScrollColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PanelBodyPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicInformation(state, form, onEvent)
            LocationDetails(form, onEvent)
            PhotosSection(form, onEvent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod") // One form section; each field is one line of it.
private fun BasicInformation(state: MapUiState, form: LocationFormState, onEvent: (MapEvent) -> Unit) {
    SectionCard(title = str(S.desktop_map_basic_information), icon = MapIcons.FileText, accent = MapColors.Brand) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(str(S.location_name), MapIcons.TypeGlyph, MapColors.Brand, required = true)
            ZillitTextField(
                value = form.name,
                onValueChange = { onEvent(MapEvent.LocationForm.Name(it)) },
                placeholder = str(S.desktop_map_enter_location_name),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(str(S.dm_cond_work_location), MapIcons.Layers, MapColors.Brand)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ZillitTextField(
                    value = if (form.typeMenuOpen) form.typeSearch else form.type.ifBlank { form.typeSearch },
                    onValueChange = { onEvent(MapEvent.LocationForm.TypeSearch(it)) },
                    placeholder = str(S.desktop_map_search_or_select_type),
                    // The web's `onFocus`: the list opens as soon as the field is entered.
                    modifier = Modifier.weight(1f).onFocusChanged {
                        if (it.isFocused && !form.typeMenuOpen) onEvent(MapEvent.LocationForm.TypeMenu(true))
                    },
                    trailingContent = {
                        CardAction(
                            onClick = { onEvent(MapEvent.LocationForm.TypeMenu(!form.typeMenuOpen)) },
                            icon = if (form.typeMenuOpen) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
                        )
                    },
                )
                ZillitButton(
                    text = str(S.add),
                    onClick = { onEvent(MapEvent.LocationForm.NewType) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Add,
                )
            }
            if (form.typeMenuOpen) TypeMenu(state, form, onEvent)
        }
        val available = state.types.firstOrNull { it.name == form.type }?.subTypes.orEmpty()
        if (available.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(str(S.desktop_map_subtypes), MapIcons.Tag, MapColors.Brand)
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZillitTheme.colors.surfaceSunken)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    available.forEach { sub ->
                        ZillitCheckbox(
                            checked = sub in form.subTypes,
                            onCheckedChange = { onEvent(MapEvent.LocationForm.ToggleSubType(sub)) },
                            label = sub,
                        )
                    }
                }
            }
        }
        if (form.isOtherType) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(str(S.desktop_map_custom_type), MapIcons.TypeGlyph, MapColors.Brand, required = true)
                ZillitTextField(
                    value = form.customType,
                    onValueChange = { onEvent(MapEvent.LocationForm.CustomType(it)) },
                    placeholder = str(S.desktop_map_enter_custom_type),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (form.isShootingType) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(str(S.txt_scene_number), MapIcons.Film, MapColors.Brand)
                ZillitTextField(
                    value = form.sceneNumber,
                    onValueChange = { onEvent(MapEvent.LocationForm.SceneNumber(it)) },
                    placeholder = str(S.desktop_map_scene_number_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(str(S.description), MapIcons.FileText, MapColors.Brand)
            MapTextArea(
                value = form.description,
                onValueChange = { onEvent(MapEvent.LocationForm.Description(it)) },
                placeholder = str(S.desktop_map_add_notes),
            )
        }
    }
}

/** The type list, in the form's flow — searchable, sorted, each type on its colour. */
@Composable
private fun TypeMenu(state: MapUiState, form: LocationFormState, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val query = form.typeSearch.trim().lowercase()
    val matches = state.types
        .filter { query.isEmpty() || it.name.lowercase().contains(query) }
        .sortedBy { it.name.lowercase() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .heightIn(max = 224.dp)
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
    ) {
        if (matches.isEmpty()) {
            ZillitText(
                text = str(S.desktop_map_no_types_found),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        matches.forEach { type ->
            val selected = form.type == type.name
            val (source, hovered) = rememberHover()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            selected -> softOf(MapColors.Brand)
                            hovered -> colors.surfaceHover
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(source)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = source, indication = null) {
                        onEvent(MapEvent.LocationForm.PickType(type.name))
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TypeGlyph(state.style(type.name), size = 24.dp, corner = 6.dp, fontSize = 12)
                ZillitText(
                    text = type.name,
                    style = labelBold(13.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (selected) MapColors.BrandText else colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun LocationDetails(form: LocationFormState, onEvent: (MapEvent) -> Unit) {
    SectionCard(title = str(S.desktop_map_location_details), icon = MapIcons.Crosshair, accent = MapColors.Brand) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(str(S.address), MapIcons.MapPin, MapColors.Brand, required = true)
            ZillitTextField(
                value = form.address,
                onValueChange = { onEvent(MapEvent.LocationForm.AddressText(it)) },
                placeholder = str(S.desktop_map_search_address),
                modifier = Modifier.fillMaxWidth(),
            )
            SuggestionList(form.addressSuggestions, onPick = { onEvent(MapEvent.LocationForm.AddressPick(it)) })
            Hint(str(S.desktop_map_address_hint))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(str(S.desktop_latitude), MapIcons.Globe, MapColors.Brand)
                ReadOnlyBox(
                    form.point?.let { toFixed(it.lat, COORDINATE_DIGITS) }.orEmpty(),
                    str(S.desktop_map_auto_filled),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(str(S.desktop_longitude), MapIcons.Globe, MapColors.Brand)
                ReadOnlyBox(
                    form.point?.let { toFixed(it.lng, COORDINATE_DIGITS) }.orEmpty(),
                    str(S.desktop_map_auto_filled),
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod") // One form section; each field is one line of it.
private fun PhotosSection(form: LocationFormState, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var over by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    SectionCard(title = str(S.desktop_photos), icon = MapIcons.Camera, accent = MapColors.Brand) {
        val dashColor = if (over || hovered) MapColors.Brand else colors.borderStrong
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (over) {
                        softOf(MapColors.Brand)
                    } else if (hovered) {
                        softOf(MapColors.Brand).copy(alpha = 0.05f)
                    } else {
                        Color.Transparent
                    },
                )
                .drawBehind {
                    drawRoundRect(
                        color = dashColor,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                        ),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                }
                .photoDrop(
                    onHover = { over = it },
                    onDrop = { photos, refused -> onEvent(MapEvent.LocationForm.PhotosDropped(photos, refused)) },
                )
                .hoverable(source)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = source, indication = null) {
                    onEvent(MapEvent.LocationForm.BrowsePhotos)
                }
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .background(if (over) MapColors.BrandSoftStrong else softOf(MapColors.Brand)),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = MapIcons.UploadCloud, tint = MapColors.Brand, size = 22.dp)
            }
            ZillitText(
                text = if (over) str(S.desktop_map_drop_images) else str(S.desktop_map_drag_drop_images),
                style = labelBold(13.sp, FontWeight.Medium),
                color = colors.textPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ZillitText(text = str(S.or), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                ZillitText(
                    text = str(S.desktop_map_click_to_browse),
                    style = labelBold(12.sp, FontWeight.Medium),
                    color = MapColors.Brand,
                )
                ZillitText(
                    text = str(S.desktop_map_image_formats),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            ZillitText(
                text = str(S.docusign_field_of, form.mediaCount, LocationRules.MAX_MEDIA),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        if (form.existing.isNotEmpty() || form.added.isNotEmpty()) {
            val tiles: List<Any> = form.existing.indices.toList() + form.added
            PhotoGrid(tiles, columns = 4) { tile ->
                when (tile) {
                    is Int -> {
                        val attachment = form.existing[tile]
                        PhotoTile(onRemove = { onEvent(MapEvent.LocationForm.RemoveExisting(tile)) }) {
                            AsyncPicture(key = attachment.media, modifier = Modifier.fillMaxSize()) {
                                photo(attachment, preview = true)
                            }
                        }
                    }
                    is com.zillit.desktop.feature.maps.ui.NewPhoto -> PhotoTile(
                        isNew = true,
                        onRemove = { onEvent(MapEvent.LocationForm.RemoveAdded(tile.key)) },
                    ) {
                        AsyncPicture(key = tile.key, modifier = Modifier.fillMaxSize()) { decode(tile.photo) }
                    }
                }
            }
        }
    }
}

/** Square tiles in rows of [columns]. */
@Composable
internal fun <T> PhotoGrid(items: List<T>, columns: Int, tile: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item -> Box(Modifier.weight(1f).aspectRatio(1f)) { tile(item) } }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PhotoTile(isNew: Boolean = false, onRemove: (() -> Unit)?, picture: @Composable () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .border(
                if (isNew) 2.dp else 1.dp,
                if (isNew) MapColors.Brand.copy(alpha = 0.4f) else ZillitTheme.colors.border,
                RoundedCornerShape(8.dp),
            )
            .hoverable(source),
    ) {
        picture()
        if (isNew) {
            Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { SmallTag(str(S.mtg_new), MapColors.Brand) }
        }
        if (onRemove != null && hovered) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.Trash, contentDescription = str(S.remove), tint = Color.White,
                    size = 11.dp)
            }
        }
    }
}

/** One location's details (`locations/LocationDetail.jsx`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod") // One panel, read top to bottom; the order is the reading order.
internal fun LocationDetailPanel(state: MapUiState, location: MapLocation, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val style = state.style(location.type)
    val accent = hexColor(style.colorHex)
    SidePanelFrame(
        width = RIGHT_PANEL_WIDTH,
        header = {
            HeroHeader(
                accent = accent,
                title = location.displayName,
                subtitle = null,
                onClose = { onEvent(MapEvent.Locations.CloseDetail) },
                trailing = {
                    HeroPillButton(
                        str(S.edit),
                        onClick = { onEvent(MapEvent.Locations.Edit(location.id)) },
                        icon = ZillitIcons.Edit,
                    )
                },
                eyebrow = if (location.hasType || location.sceneNumber.isNotBlank()) {
                    {
                        if (location.hasType) HeroChip("${style.icon}  ${location.type}")
                        if (location.sceneNumber.isNotBlank()) HeroChip("SC ${location.sceneNumber}", MapIcons.Film)
                    }
                } else {
                    null
                },
                below = if (location.address.isNotBlank()) {
                    {
                        Row(
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ZillitIcon(icon = MapIcons.MapPin, tint = Color.White.copy(alpha = 0.85f), size = 14.dp)
                            ZillitText(
                                text = location.address,
                                style = ZillitTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                } else {
                    null
                },
            )
        },
    ) {
        ZillitScrollColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PanelBodyPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = str(S.desktop_map_location_details), icon = MapIcons.Crosshair,
                accent = MapColors.Brand) {
                if (location.address.isNotBlank()) {
                    InfoRow(MapIcons.MapPin, str(S.address), MapColors.Brand) {
                        ZillitText(
                            text = location.address,
                            style = ZillitTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                        )
                    }
                }
                location.point?.let { point ->
                    val lat = toFixed(point.lat, COORDINATE_DIGITS)
                    val lng = toFixed(point.lng, COORDINATE_DIGITS)
                    InfoRow(MapIcons.Globe, str(S.desktop_map_coordinates), MapColors.Info) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ZillitText(
                                text = "$lat, $lng",
                                style = ZillitTheme.typography.bodyMedium.copy(fontFamily = ZillitTheme.fonts.mono),
                                color = colors.textPrimary,
                            )
                            CardAction(
                                onClick = { onEvent(MapEvent.Locations.CopyCoordinates(location.id)) },
                                icon = MapIcons.Copy,
                                text = str(S.copy),
                                tone = ActionTone.Accent,
                            )
                        }
                    }
                }
                if (location.subTypes.isNotEmpty()) {
                    InfoRow(MapIcons.Tag, str(S.desktop_map_subtypes), MapColors.Brand) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            location.subTypes.forEach { sub ->
                                ZillitText(
                                    text = sub,
                                    style = labelBold(12.sp, FontWeight.Medium),
                                    color = colors.textPrimary,
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                        .background(softOf(MapColors.Brand))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (location.description.isNotBlank()) {
                SectionCard(title = str(S.description), icon = MapIcons.FileText, accent = MapColors.Brand) {
                    ZillitText(
                        text = location.description,
                        style = ZillitTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                    )
                }
            }
            if (location.attachments.isNotEmpty()) {
                SectionCard(
                    title = str(S.desktop_map_photos_count, location.attachments.size),
                    icon = MapIcons.Camera,
                    accent = MapColors.Brand,
                ) {
                    PhotoGrid(location.attachments.withIndex().toList(), columns = 3) { (index, attachment) ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { onEvent(MapEvent.Locations.OpenPhoto(location.id, index)) },
                        ) {
                            AsyncPicture(key = attachment.media, modifier = Modifier.fillMaxSize()) {
                                photo(attachment, preview = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val COORDINATE_DIGITS = 6
