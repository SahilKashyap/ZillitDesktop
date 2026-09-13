package com.zillit.desktop.feature.maps.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.ui.CurrentPlace
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapUiState

/**
 * The Cities panel (`common/Sidebar.jsx`): search, the production's cities in
 * its own order — dragged by the handle to rearrange — and the way to add one.
 */
@Composable
internal fun CitiesPanel(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    val panel = state.citiesPanel
    SidePanelFrame(
        width = CITIES_PANEL_WIDTH,
        header = {
            HeroHeader(
                accent = MapColors.Brand,
                title = "Cities",
                subtitle = "Switch between cities or add a new one to the map.",
                onClose = { onEvent(MapEvent.Cities.Close) },
                trailing = {
                    HeroChip(
                        text = "${state.cities.size} ${if (state.cities.size == 1) "City" else "Cities"}",
                        icon = MapIcons.Map,
                    )
                    // The header's add is for those who can post; the empty
                    // state's add asks for rights instead (the web's split).
                    if (state.viewer.mayPost) {
                        HeroPillButton(
                            text = "Add Another City",
                            icon = ZillitIcons.Add,
                            onClick = { onEvent(MapEvent.Cities.Add) },
                        )
                    }
                },
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().background(ZillitTheme.colors.surface).padding(horizontal = 20.dp, vertical = 12.dp)) {
            com.zillit.desktop.core.designsystem.component.ZillitTextField(
                value = panel.search,
                onValueChange = { onEvent(MapEvent.Cities.Search(it)) },
                placeholder = "Search cities...",
                leadingIcon = ZillitIcons.Search,
                modifier = Modifier.fillMaxWidth(),
            )
            if (panel.suggestions.isNotEmpty()) {
                SuggestionList(
                    predictions = panel.suggestions,
                    onPick = { onEvent(MapEvent.Cities.SearchPick(it)) },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        val filtered = state.cities.filter { it.name.contains(panel.search.trim(), ignoreCase = true) }
        // Offered only while no city of that name exists (`suggestedPlace`).
        val suggested = panel.currentPlace?.takeIf { place ->
            state.cities.none { it.name.trim().equals(place.name.trim(), ignoreCase = true) }
        }
        ZillitScrollColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PanelBodyPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (suggested != null) {
                CurrentPlaceCard(suggested, onAdd = { onEvent(MapEvent.Cities.AddCurrentPlace) })
            }
            when {
                state.citiesLoading && state.cities.isEmpty() -> LoadingBlock()
                filtered.isEmpty() -> EmptyBlock(
                    icon = MapIcons.MapPin,
                    accent = MapColors.Brand,
                    title = if (panel.search.isBlank()) "No cities added yet" else "No cities match your search",
                    action = if (panel.search.isBlank()) {
                        {
                            ZillitButton(
                                text = "Add Another City",
                                onClick = { onEvent(MapEvent.Cities.Add) },
                                leadingIcon = ZillitIcons.Add,
                            )
                        }
                    } else {
                        null
                    },
                )
                else -> ReorderableCities(state, filtered, onEvent)
            }
        }
    }
}

/**
 * The Current Location card: where this machine is, as a city one tap away.
 * The other half of the empty state (req C) — and above the list otherwise.
 */
@Composable
private fun CurrentPlaceCard(place: CurrentPlace, onAdd: () -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(softOf(MapColors.Info), colors.surface)),
            )
            .border(1.dp, MapColors.Info.copy(alpha = 0.35f), shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconChip(icon = MapIcons.Navigation, tint = Color.White, background = MapColors.Info, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            ZillitText(
                text = "CURRENT LOCATION",
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                color = MapColors.Info,
            )
            ZillitText(
                text = place.name,
                style = labelBold(14.sp),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (place.description.isNotBlank() && place.description != place.name) {
                ZillitText(
                    text = place.description,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ZillitButton(text = "Add", onClick = onAdd, leadingIcon = ZillitIcons.Add, size = ButtonSize.Small)
    }
}

/**
 * The city rows, rearranged by dragging a handle. The new order shows while
 * dragging and is sent once, on release — the service takes the whole
 * arrangement (`reorder-cities`), and a request per row crossed would race.
 */
@Composable
private fun ReorderableCities(state: MapUiState, shown: List<MapCity>, onEvent: (MapEvent) -> Unit) {
    var order by remember(shown) { mutableStateOf(shown) }
    // The gesture outlives recompositions; it must read today's lists, not
    // the ones it started with.
    val currentShown by rememberUpdatedState(shown)
    val allCities by rememberUpdatedState(state.cities)
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        order.forEach { city ->
            val isDragging = dragging == city.id
            CityRow(
                city = city,
                selected = city.id == state.selectedCityId,
                unread = state.cityUnread[city.id] ?: 0,
                dragging = isDragging,
                onClick = { onEvent(MapEvent.Cities.Select(city.id)) },
                onDelete = { onEvent(MapEvent.Cities.Delete(city.id)) },
                modifier = Modifier
                    .onSizeChanged { heights[city.id] = it.height }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) offset else 0f },
                handle = Modifier.pointerInput(city.id) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = city.id
                            offset = 0f
                        },
                        onDragEnd = {
                            val moved = order.map { it.id } != currentShown.map { it.id }
                            dragging = null
                            offset = 0f
                            if (moved) onEvent(MapEvent.Cities.Reorder(fullOrder(allCities, currentShown, order, city.id)))
                        },
                        onDragCancel = {
                            dragging = null
                            offset = 0f
                            order = currentShown
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                            val index = order.indexOfFirst { it.id == city.id }
                            val gap = 10.dp.toPx()
                            val next = order.getOrNull(index + 1)
                            val previous = order.getOrNull(index - 1)
                            val nextHeight = next?.let { (heights[it.id] ?: 0) + gap } ?: Float.MAX_VALUE
                            val previousHeight = previous?.let { (heights[it.id] ?: 0) + gap } ?: Float.MAX_VALUE
                            if (next != null && offset > nextHeight / 2) {
                                order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                                offset -= nextHeight
                            } else if (previous != null && -offset > previousHeight / 2) {
                                order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                                offset += previousHeight
                            }
                        },
                    )
                },
            )
        }
    }
}

/**
 * The full list's new order for a drag made in a (possibly filtered) view —
 * dnd-kit's `arrayMove` from the dragged city's place to the place of the city
 * it landed on.
 */
internal fun fullOrder(all: List<MapCity>, shown: List<MapCity>, reordered: List<MapCity>, movedId: String): List<String> {
    val target = reordered.indexOfFirst { it.id == movedId }
    val overId = shown.getOrNull(target)?.id ?: return all.map { it.id }
    val ids = all.map { it.id }.toMutableList()
    val from = ids.indexOf(movedId)
    val to = ids.indexOf(overId)
    if (from < 0 || to < 0) return all.map { it.id }
    ids.add(to, ids.removeAt(from))
    return ids
}

@Composable
private fun CityRow(
    city: MapCity,
    selected: Boolean,
    unread: Int,
    dragging: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
    handle: Modifier,
) {
    val colors = ZillitTheme.colors
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (dragging || selected) Modifier.shadow(if (dragging) 10.dp else 3.dp, shape) else Modifier)
            .clip(shape)
            .background(colors.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = when {
                    selected -> MapColors.Brand.copy(alpha = 0.55f)
                    hovered -> MapColors.Brand.copy(alpha = 0.35f)
                    else -> colors.border
                },
                shape = shape,
            )
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(
            icon = MapIcons.DragHandle,
            contentDescription = "Drag to reorder",
            tint = colors.textMuted,
            size = 14.dp,
            modifier = handle.pointerHoverIcon(PointerIcon.Crosshair),
        )
        IconChip(
            icon = MapIcons.MapPin,
            tint = if (selected) Color.White else MapColors.Brand,
            background = if (selected) MapColors.Brand else softOf(MapColors.Brand),
            size = 36.dp,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitText(
                    text = city.displayName,
                    style = labelBold(14.sp),
                    color = if (selected) MapColors.BrandText else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (city.locationCount > 0) CountBadge(city.locationCount, BadgeTone.Secondary)
                if (unread > 0) CountBadge(unread, BadgeTone.Danger)
            }
            if (city.description.isNotBlank()) {
                ZillitText(
                    text = city.description,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CardAction(onClick = onDelete, icon = ZillitIcons.Trash, tone = ActionTone.Danger)
    }
}

internal val CITIES_PANEL_WIDTH = 360.dp
