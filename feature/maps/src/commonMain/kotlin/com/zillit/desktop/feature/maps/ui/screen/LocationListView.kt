package com.zillit.desktop.feature.maps.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.toFixed
import com.zillit.desktop.feature.maps.ui.ListViewState
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapUiState

/**
 * Every location in the city, as cards (`locations/LocationList.jsx` in its
 * drawer mode): a hero with the type tabs, and a grid that reflows with the
 * window.
 */
@Composable
internal fun LocationListView(
    state: MapUiState,
    list: ListViewState,
    onEvent: (MapEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val cityName = state.selectedCity?.displayName ?: str(S.desktop_map_all_cities)
    val total = state.locations.size
    val filtered = if (list.filter == ListViewState.ALL_TYPES) {
        state.locations
    } else {
        state.locations.filter { it.type == list.filter }
    }
    val narrowed = list.filter != ListViewState.ALL_TYPES
    Column(modifier.fillMaxSize().background(if (colors.isDark) colors.canvas else colors.surfaceSunken)) {
        HeroHeader(
            accent = MapColors.Brand,
            title = str(S.desktop_map_locations_title),
            subtitle = if (narrowed) {
                str(S.desktop_map_showing_of, filtered.size, total)
            } else {
                str(S.desktop_map_locations_subtitle)
            },
            onClose = { onEvent(MapEvent.Toolbar.ToggleListView) },
            eyebrow = { HeroChip(cityName) },
            trailing = {
                HeroChip(
                    if (total == 1) {
                        str(S.desktop_map_location_count_one, total)
                    } else {
                        str(S.desktop_map_location_count_other, total)
                    },
                    MapIcons.MapPin,
                )
                HeroPillButton(
                    str(S.desktop_map_new_location),
                    onClick = { onEvent(MapEvent.LocationForm.New) },
                    icon = ZillitIcons.Add,
                    solid = true,
                )
            },
            below = {
                FilterTabs(state, list, onEvent)
            },
        )
        ZillitScrollColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(24.dp),
        ) {
            when {
                state.locationsLoading && state.locations.isEmpty() ->
                    LoadingBlock(str(S.desktop_map_loading_locations))
                filtered.isEmpty() -> EmptyCard(narrowed, list.filter, cityName, onEvent)
                else -> LocationGrid(state, filtered, onEvent)
            }
        }
    }
}

/** `FilterTabs.jsx` — All, then each type that has locations, each with its count. */
@Composable
private fun FilterTabs(state: MapUiState, list: ListViewState, onEvent: (MapEvent) -> Unit) {
    val counts = state.typeCounts
    val types = (state.types.map { it.name } + state.locations.map { it.type })
        .filter { it.isNotBlank() && it != "Unknown" && (counts[it] ?: 0) > 0 }
        .distinct()
    Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.1f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tab(
                text = str(S.all),
                icon = null,
                count = state.locations.size,
                active = list.filter == ListViewState.ALL_TYPES,
                activeColor = MapColors.Brand,
                onClick = { onEvent(MapEvent.Locations.Filter(ListViewState.ALL_TYPES)) },
            )
            types.forEach { type ->
                val style = state.style(type)
                Tab(
                    text = type,
                    icon = style.icon,
                    count = counts[type] ?: 0,
                    active = list.filter == type,
                    activeColor = hexColor(style.colorHex),
                    onClick = { onEvent(MapEvent.Locations.Filter(type)) },
                )
            }
        }
    }
}

@Composable
private fun Tab(text: String, icon: String?, count: Int, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    active -> activeColor
                    hovered -> colors.surfaceHover
                    else -> colors.surface
                },
            )
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            ZillitText(
                text = icon,
                style = TextStyle(fontSize = 13.sp),
                color = if (active) Color.White else colors.textSecondary,
            )
        }
        ZillitText(
            text = text,
            style = labelBold(13.sp, FontWeight.Medium),
            color = if (active) Color.White else colors.textSecondary,
            maxLines = 1,
        )
        if (count > 0) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Color.White.copy(alpha = 0.25f) else colors.surfaceSunken)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                ZillitText(
                    text = count.toString(),
                    style = labelBold(11.sp),
                    color = if (active) Color.White else colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun LocationGrid(state: MapUiState, locations: List<MapLocation>, onEvent: (MapEvent) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth < 560.dp -> 1
            maxWidth < 860.dp -> 2
            maxWidth < 1160.dp -> 3
            else -> 4
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            locations.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    row.forEach { location ->
                        LocationCard(state, location, onEvent, Modifier.weight(1f).fillMaxHeight())
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** `LocationCard.jsx`. */
@Composable
@Suppress("LongMethod") // One card, laid out in one place.
private fun LocationCard(state: MapUiState, location: MapLocation, onEvent: (MapEvent) -> Unit, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val style = state.style(location.type)
    val accent = hexColor(style.colorHex)
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .then(if (hovered) Modifier.shadow(10.dp, shape) else Modifier)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border, shape)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null) { onEvent(MapEvent.Locations.View(location.id)) },
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(accent))
        Column(Modifier.weight(1f).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (location.hasType) {
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(accent)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ZillitText(text = style.icon, style = labelBold(12.sp), color = Color.White)
                        ZillitText(
                            text = location.type,
                            style = labelBold(12.sp),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (location.sceneNumber.isNotBlank()) {
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceSunken)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ZillitIcon(icon = MapIcons.Film, tint = colors.textSecondary, size = 10.dp)
                        ZillitText(
                            text = "SC ${location.sceneNumber}",
                            style = labelBold(11.sp),
                            color = colors.textSecondary,
                        )
                    }
                }
            }
            ZillitText(
                text = location.displayName,
                style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (location.address.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconChip(
                        MapIcons.MapPin,
                        MapColors.Brand,
                        softOf(MapColors.Brand),
                        size = 24.dp,
                        iconSize = 11.dp,
                        corner = 6.dp,
                    )
                    ZillitText(
                        text = location.address,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            location.point?.let { point ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconChip(
                        MapIcons.Globe,
                        MapColors.Info,
                        softOf(MapColors.Info),
                        size = 24.dp,
                        iconSize = 11.dp,
                        corner = 6.dp,
                    )
                    ZillitText(
                        text = "${toFixed(point.lat, 6)}, ${toFixed(point.lng, 6)}",
                        style = TextStyle(fontSize = 11.sp, fontFamily = ZillitTheme.fonts.mono),
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
            if (location.subTypes.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    location.subTypes.take(MAX_CARD_SUBTYPES).forEach { SubTypeChip(it) }
                    if (location.subTypes.size > MAX_CARD_SUBTYPES) {
                        ZillitText(
                            text = "+${location.subTypes.size - MAX_CARD_SUBTYPES}",
                            style = labelBold(10.sp, FontWeight.Medium),
                            color = colors.textMuted,
                        )
                    }
                }
            }
            if (location.description.isNotBlank()) {
                ZillitText(
                    text = location.description,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardAction(onClick = { onEvent(MapEvent.Locations.View(location.id)) }, icon = ZillitIcons.Eye,
                    text = str(S.view))
                if (state.viewer.mayPost) {
                    CardAction(onClick = { onEvent(MapEvent.Locations.Edit(location.id)) }, icon = ZillitIcons.Edit,
                        text = str(S.edit), tone = ActionTone.Accent)
                    Spacer(Modifier.weight(1f))
                    CardAction(
                        onClick = { onEvent(MapEvent.Locations.Delete(location.id)) },
                        icon = ZillitIcons.Trash,
                        tone = ActionTone.Danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(narrowed: Boolean, filter: String, cityName: String, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp)),
        ) {
            EmptyBlock(
                icon = MapIcons.MapPin,
                accent = MapColors.Brand,
                title = if (narrowed) {
                    str(S.desktop_map_no_filter_locations, filter)
                } else {
                    str(S.desktop_map_no_locations_yet)
                },
                message = if (narrowed) {
                    str(S.desktop_map_no_filter_locations_msg, filter, cityName)
                } else {
                    str(S.desktop_map_no_locations_msg)
                },
                action = {
                    ZillitButton(
                        text = str(S.add_location),
                        onClick = { onEvent(MapEvent.LocationForm.New) },
                        leadingIcon = ZillitIcons.Add,
                    )
                },
            )
        }
    }
}

private const val MAX_CARD_SUBTYPES = 3
