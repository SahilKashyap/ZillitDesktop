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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.TYPE_ICON_OPTIONS
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapUiState
import com.zillit.desktop.feature.maps.ui.TypeFormState

/** The LOC Types panel (`headers/HeaderManagerPanel.jsx`). */
@Composable
internal fun TypesPanel(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    val panel = state.typesPanel
    val colors = ZillitTheme.colors
    SidePanelFrame(
        width = RIGHT_PANEL_WIDTH,
        header = {
            HeroHeader(
                accent = MapColors.Brand,
                title = "Location Types",
                subtitle = "Manage the categories used to classify your map locations.",
                onClose = { onEvent(MapEvent.Toolbar.ToggleTypes) },
                trailing = {
                    HeroChip("${state.types.size} ${if (state.types.size == 1) "Type" else "Types"}", MapIcons.Layers)
                    HeroPillButton("Add Type", onClick = { onEvent(MapEvent.Types.New) }, icon = ZillitIcons.Add)
                },
            )
        },
    ) {
        val form = panel.form
        val editingId = form?.editId
        val query = panel.filter.trim().lowercase()
        val shown = state.types
            .filter { type ->
                query.isEmpty() || type.name.lowercase().contains(query) ||
                    type.subTypes.any { it.lowercase().contains(query) }
            }
            .sortedBy { it.name.lowercase() }
        ZillitScrollColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (form != null) {
                Column(
                    modifier = Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, MapColors.Brand.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconChip(MapIcons.Layers, MapColors.Brand, softOf(MapColors.Brand), size = 28.dp, iconSize = 14.dp)
                        ZillitText(
                            text = if (editingId != null) {
                                "Edit Type — ${state.types.firstOrNull { it.id == editingId }?.name ?: form.name}"
                            } else {
                                "New Type"
                            },
                            style = ZillitTheme.typography.titleSmall,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TypeFormFields(form, onEvent)
                }
            }
            if (state.types.isNotEmpty()) {
                PanelFilter(panel.filter, "Filter by type or subtype...") { onEvent(MapEvent.Types.Filter(it)) }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    shown.isEmpty() && query.isNotEmpty() -> EmptyBlock(
                        icon = ZillitIcons.Search,
                        accent = colors.textMuted,
                        title = "No types match \"${panel.filter}\"",
                        message = "Try a different search term",
                    )
                    state.types.isEmpty() && form == null -> EmptyBlock(
                        icon = MapIcons.Tag,
                        accent = MapColors.Brand,
                        title = "No location types yet",
                        message = "Create types like Hotel, Parking, Shooting to categorize your locations.",
                        action = {
                            ZillitButton(text = "Add First Type", onClick = { onEvent(MapEvent.Types.New) }, leadingIcon = ZillitIcons.Add)
                        },
                    )
                    else -> shown.forEach { type ->
                        TypeRow(state, type, editing = form != null && editingId == type.id, onEvent = onEvent)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeRow(state: MapUiState, type: LocationType, editing: Boolean, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val style = state.style(type.name)
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(
                if (editing) 2.dp else 1.dp,
                when {
                    editing -> MapColors.Brand.copy(alpha = 0.55f)
                    hovered -> MapColors.Brand.copy(alpha = 0.3f)
                    else -> colors.border
                },
                shape,
            )
            .hoverable(source)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TypeGlyph(style, size = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitText(
                    text = type.name,
                    style = labelBold(14.sp),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (editing) SmallTag("EDITING", MapColors.Brand)
            }
            if (type.subTypes.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    type.subTypes.take(MAX_SUBTYPE_CHIPS).forEach { SubTypeChip(it) }
                    if (type.subTypes.size > MAX_SUBTYPE_CHIPS) {
                        ZillitText(
                            text = "+${type.subTypes.size - MAX_SUBTYPE_CHIPS}",
                            style = labelBold(10.sp, FontWeight.Medium),
                            color = colors.textMuted,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(colors.surfaceSunken).padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
        if (!editing) {
            CardAction(onClick = { onEvent(MapEvent.Types.Edit(type.id)) }, icon = ZillitIcons.Edit, tone = ActionTone.Accent)
            CardAction(onClick = { onEvent(MapEvent.Types.Delete(type.id)) }, icon = ZillitIcons.Trash, tone = ActionTone.Danger)
        }
    }
}

@Composable
internal fun SubTypeChip(text: String) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(softOf(MapColors.Brand)).padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ZillitIcon(icon = MapIcons.Tag, tint = MapColors.BrandText, size = 9.dp)
        ZillitText(text = text, style = labelBold(10.sp, FontWeight.Medium), color = MapColors.BrandText, maxLines = 1)
    }
}

/** `headers/HeaderForm.jsx` — name, icon, sub-types — for the panel card and the New Type dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TypeFormFields(form: TypeFormState, onEvent: (MapEvent) -> Unit, showActions: Boolean = true) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitText(text = "Type Name *", style = labelBold(12.sp, FontWeight.Medium), color = colors.textSecondary)
        ZillitTextField(
            value = form.name,
            onValueChange = { onEvent(MapEvent.Types.Name(it)) },
            placeholder = "e.g. Hotel, Parking, Shooting",
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitText(text = "Icon", style = labelBold(12.sp, FontWeight.Medium), color = colors.textSecondary)
        val (source, hovered) = rememberHover()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .border(1.dp, if (hovered) MapColors.Brand else colors.borderStrong, RoundedCornerShape(8.dp))
                .hoverable(source)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = source, indication = null) {
                    onEvent(MapEvent.Types.IconPicker(!form.iconPickerOpen))
                }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (form.icon.isNotBlank()) {
                ZillitText(text = form.icon, style = TextStyle(fontSize = 20.sp))
                ZillitText(text = "Change icon", style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
                CardAction(onClick = { onEvent(MapEvent.Types.Icon("")) }, icon = ZillitIcons.Close, tone = ActionTone.Danger)
            } else {
                ZillitIcon(icon = MapIcons.Smile, tint = colors.textMuted, size = 16.dp)
                ZillitText(text = "Choose an icon...", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
            }
        }
        if (form.iconPickerOpen) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                // Centred, so the grid's spare width splits evenly either side.
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TYPE_ICON_OPTIONS.forEach { option -> EmojiCell(option, selected = option == form.icon) { onEvent(MapEvent.Types.Icon(option)) } }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitText(text = "Subtypes", style = labelBold(12.sp, FontWeight.Medium), color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitTextField(
                value = form.newSubType,
                onValueChange = { onEvent(MapEvent.Types.NewSubType(it)) },
                placeholder = "Add a subtype...",
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        onEvent(MapEvent.Types.AddSubType)
                        true
                    } else {
                        false
                    }
                },
            )
            ZillitButton(
                text = "Add",
                onClick = { onEvent(MapEvent.Types.AddSubType) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Add,
                enabled = form.newSubType.isNotBlank(),
            )
        }
        form.subTypes.forEachIndexed { index, sub ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(text = sub, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                CardAction(onClick = { onEvent(MapEvent.Types.RemoveSubType(index)) }, icon = ZillitIcons.Close, tone = ActionTone.Danger)
            }
        }
    }
    if (showActions) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ZillitButton(text = "Cancel", onClick = { onEvent(MapEvent.Types.Cancel) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
            ZillitButton(
                text = if (form.editId != null) "Update" else "Create",
                onClick = { onEvent(MapEvent.Types.Save) },
                leadingIcon = ZillitIcons.Check,
                size = ButtonSize.Small,
                loading = form.saving,
                enabled = !form.saving && form.name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun EmojiCell(emoji: String, selected: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> MapColors.BrandSoftStrong
                    hovered -> softOf(MapColors.Brand)
                    else -> Color.Transparent
                },
            )
            .then(if (selected) Modifier.border(2.dp, MapColors.Brand, RoundedCornerShape(8.dp)) else Modifier)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(text = emoji, style = TextStyle(fontSize = 18.sp), textAlign = TextAlign.Center)
    }
}

private const val MAX_SUBTYPE_CHIPS = 4
