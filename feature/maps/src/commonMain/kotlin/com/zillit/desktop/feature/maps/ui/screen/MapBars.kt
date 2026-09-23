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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.domain.toFixed
import com.zillit.desktop.feature.maps.ui.DirectionsState
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapUiState

/** A floating card under the toolbar — the web's search, filter and directions panels. */
@Composable
private fun BarCard(content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .shadow(8.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp)),
    ) {
        content()
    }
}

/**
 * Search the city's saved locations by name, address or type. The results
 * list only — the map's pins are never hidden by a search.
 */
@Composable
@Suppress("LongMethod") // One bar, laid out in one place.
internal fun SearchBar(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    val search = state.search ?: return
    val colors = ZillitTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BarCard {
        Column(Modifier.fillMaxWidth()) {
            ZillitTextField(
                value = search.query,
                onValueChange = { onEvent(MapEvent.Bars.SearchQuery(it)) },
                placeholder = str(S.desktop_map_search_locations),
                leadingIcon = ZillitIcons.Search,
                modifier = Modifier.fillMaxWidth().padding(10.dp).focusRequester(focus),
                trailingContent = if (search.query.isNotEmpty()) {
                    {
                        CardAction(onClick = { onEvent(MapEvent.Bars.SearchQuery("")) }, icon = ZillitIcons.Close)
                    }
                } else {
                    null
                },
            )
            if (search.query.isNotBlank()) {
                val results = search.results(state.locations)
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                if (results.isEmpty()) {
                    ZillitText(
                        text = str(S.desktop_map_no_locations_found),
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                } else {
                    results.take(MAX_RESULTS).forEach { location ->
                        val style = state.style(location.type)
                        val (source, hovered) = rememberHover()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (hovered) colors.surfaceHover else Color.Transparent)
                                .hoverable(source)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(interactionSource = source, indication = null) {
                                    onEvent(MapEvent.Bars.SearchPick(location.id))
                                }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TypeGlyph(style, size = 26.dp, corner = 6.dp, fontSize = 12)
                            Column(Modifier.weight(1f)) {
                                ZillitText(
                                    text = location.displayName,
                                    style = labelBold(13.sp, FontWeight.Medium),
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val detail = listOf(location.type, location.address).filter { it.isNotBlank() }
                                if (detail.isNotEmpty()) {
                                    ZillitText(
                                        text = detail.joinToString("  ·  "),
                                        style = ZillitTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (results.size > MAX_RESULTS) {
                        ZillitText(
                            text = "+${results.size - MAX_RESULTS} more results",
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** The type chips: colour dot, glyph, name, count, and a tick when on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterPanel(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    BarCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ZillitText(
                    text = str(S.desktop_map_filter_by_type),
                    style = ZillitTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (state.typeFilters.isNotEmpty()) {
                    ZillitButton(
                        text = str(S.txt_clear_all),
                        onClick = { onEvent(MapEvent.Bars.ClearTypeFilters) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val types = state.presentTypes
            if (types.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_map_no_types_available),
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            } else {
                val counts = state.typeCounts
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    types.forEach { type ->
                        TypeFilterChip(
                            name = type,
                            count = counts[type] ?: 0,
                            active = type in state.typeFilters,
                            state = state,
                            onClick = { onEvent(MapEvent.Bars.ToggleTypeFilter(type)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeFilterChip(name: String, count: Int, active: Boolean, state: MapUiState, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val style = state.style(name)
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) softOf(MapColors.Info) else colors.surface)
            .border(
                1.dp,
                when {
                    active -> MapColors.Info.copy(alpha = 0.5f)
                    hovered -> colors.borderStrong
                    else -> colors.border
                },
                RoundedCornerShape(50),
            )
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(style.colorHex)))
        ZillitText(text = style.icon, style = labelBold(12.sp), color = colors.textSecondary)
        ZillitText(
            text = name,
            style = labelBold(12.sp, if (active) FontWeight.SemiBold else FontWeight.Normal),
            color = if (active) MapColors.Info else colors.textPrimary,
        )
        ZillitText(text = count.toString(), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        if (active) ZillitIcon(icon = MapIcons.CheckCircle, tint = MapColors.Info, size = 13.dp)
    }
}

/**
 * Get Directions: pickup and drop-off with Google's suggestions, the route's
 * distance and time, and the two ways onward — share it, or call a driver.
 */
@Composable
@Suppress("LongMethod") // One panel, read top to bottom; the order is the reading order.
internal fun DirectionsPanel(state: DirectionsState, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    BarCard {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(softOf(MapColors.Info), softOf(hexColor("#6366F1")))))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconChip(icon = MapIcons.Navigation, tint = Color.White, background = MapColors.Info, size = 30.dp)
                ZillitText(
                    text = str(S.desktop_map_get_directions),
                    style = ZillitTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                CardAction(onClick = { onEvent(MapEvent.Directions.Close) }, icon = ZillitIcons.Close)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RouteDots()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ZillitTextField(
                            value = state.pickupText,
                            onValueChange = { onEvent(MapEvent.Directions.PickupText(it)) },
                            placeholder = str(S.txt_pickup_location),
                            modifier = Modifier.weight(1f),
                        )
                        CurrentLocationButton(
                            active = state.usingCurrentLocation,
                            onClick = { onEvent(MapEvent.Directions.UseCurrentLocation) },
                        )
                    }
                    SuggestionList(state.pickupSuggestions, onPick = { onEvent(MapEvent.Directions.PickupPick(it)) })
                    ZillitTextField(
                        value = state.dropText,
                        onValueChange = { onEvent(MapEvent.Directions.DropText(it)) },
                        placeholder = str(S.desktop_map_dropoff_destination),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SuggestionList(state.dropSuggestions, onPick = { onEvent(MapEvent.Directions.DropPick(it)) })
                }
            }
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitSpinner(size = 16.dp)
                    ZillitText(
                        text = str(S.desktop_map_finding_route),
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                }
            }
            val route = state.route
            if (route != null && !state.loading) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(colors.surfaceSunken)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // The figures share a top line; the duration has no second
                    // line, and centring would drop its label below distance's.
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                        RouteFigure(
                            str(S.desktop_map_distance),
                            route.distanceText,
                            str(S.desktop_map_miles_short, toFixed(route.distanceMeters * MILES_PER_METER, 1)),
                        )
                        Box(
                            Modifier.width(1.dp)
                                .height(40.dp)
                                .background(colors.border)
                                .align(Alignment.CenterVertically),
                        )
                        RouteFigure(str(S.desktop_cal_duration), route.durationText, null)
                    }
                    Spacer(Modifier.weight(1f))
                    ZillitButton(
                        text = str(S.share),
                        onClick = { onEvent(MapEvent.Directions.Share) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = MapIcons.Share,
                        enabled = state.pickup != null && state.drop != null,
                    )
                    ZillitButton(
                        text = str(S.desktop_map_call_a_driver),
                        onClick = { onEvent(MapEvent.Directions.CallDriver) },
                        leadingIcon = MapIcons.Truck,
                        enabled = state.pickup != null && state.drop != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteDots() {
    Column(
        modifier = Modifier.padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Dot(MapColors.PickupDot)
        Box(
            Modifier
                .width(2.dp)
                .height(34.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(MapColors.PickupDot.copy(alpha = 0.6f), MapColors.DropDot.copy(alpha = 0.6f)),
                    ),
                ),
        )
        Dot(MapColors.DropDot)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun CurrentLocationButton(active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) MapColors.Info else if (hovered) softOf(MapColors.Info) else colors.surfaceSunken)
            .border(1.dp, if (active) MapColors.Info else colors.border, RoundedCornerShape(10.dp))
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = MapIcons.Target,
            contentDescription = str(S.recce_picker_my_location),
            tint = if (active) Color.White else if (hovered) MapColors.Info else colors.textSecondary,
            size = 16.dp,
        )
    }
}

@Composable
private fun RouteFigure(label: String, value: String, sub: String?) {
    val colors = ZillitTheme.colors
    Column {
        ZillitText(
            text = label,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
            color = colors.textMuted,
        )
        ZillitText(
            text = value,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )
        if (sub != null) ZillitText(text = sub, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
    }
}

private const val MAX_RESULTS = 8

/** The web's `metersToMiles`. */
private const val MILES_PER_METER = 0.000621371
