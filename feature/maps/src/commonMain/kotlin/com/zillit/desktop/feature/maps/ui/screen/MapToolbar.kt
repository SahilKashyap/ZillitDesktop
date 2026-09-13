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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapPanel
import com.zillit.desktop.feature.maps.ui.MapUiState

/**
 * The orange toolbar (`MapView`'s `mm-toolbar`): back, the city and its count,
 * the map's controls as pills, and the guide on the right.
 *
 * The controls only show with a city selected — there is nothing to fit,
 * search or pin without one — but the guide always does: an empty map is
 * exactly when someone does not know what to do first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MapToolbar(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val background = if (colors.isDark) {
        Brush.linearGradient(listOf(colors.surfaceRaised, colors.surfaceRaised))
    } else {
        Brush.linearGradient(listOf(MapColors.Brand, MapColors.Brand.copy(alpha = 0.87f)))
    }
    val city = state.selectedCity
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp)
            .background(background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackButton(onClick = { onEvent(MapEvent.Toolbar.Back) })
        if (city != null) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = 260.dp),
            ) {
                ZillitText(
                    text = city.displayName,
                    style = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ZillitText(
                    text = countLabel(state),
                    style = ZillitTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ControlPills(state, onEvent)
            }
        } else {
            ZillitText(
                text = "Map",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }
        ToolbarPill(
            text = "How To Use Map",
            icon = MapIcons.HelpCircle,
            onClick = { onEvent(MapEvent.Toolbar.ShowGuide) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPills(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    ToolbarPill("Fit All", ZillitIcons.Maximize, onClick = { onEvent(MapEvent.Toolbar.FitAll) })
    ToolbarPill(
        "Search",
        ZillitIcons.Search,
        active = state.search != null,
        onClick = { onEvent(MapEvent.Toolbar.ToggleSearch) },
    )
    ToolbarPill(
        if (state.typeFilters.isEmpty()) "Filter" else "Filter (${state.typeFilters.size})",
        ZillitIcons.Filter,
        active = state.filterOpen || state.typeFilters.isNotEmpty(),
        onClick = { onEvent(MapEvent.Toolbar.ToggleFilter) },
    )
    ToolbarPill(
        if (state.pinMode) "Cancel" else "Pin Location",
        if (state.pinMode) ZillitIcons.Close else ZillitIcons.Add,
        danger = state.pinMode,
        onClick = { onEvent(MapEvent.Toolbar.TogglePinMode) },
    )
    ToolbarPill(
        "List View",
        MapIcons.List,
        active = state.listView != null,
        count = state.locations.size.takeIf { it > 0 },
        onClick = { onEvent(MapEvent.Toolbar.ToggleListView) },
    )
    val zone = state.activeZone
    ToolbarPill(
        if (zone != null) "Zone: ${zone.displayName}" else "Studio Zone",
        if (zone != null) MapIcons.Target else MapIcons.List,
        active = state.topPanel == MapPanel.ZoneList || zone != null,
        count = state.zones.size.takeIf { it > 0 },
        maxTextWidth = 150,
        onClick = { onEvent(MapEvent.Toolbar.ToggleZoneList) },
    )
    // Client req L: "LOC Types" names what it manages.
    ToolbarPill(
        "LOC Types",
        ZillitIcons.Settings,
        active = state.topPanel == MapPanel.Types,
        onClick = { onEvent(MapEvent.Toolbar.ToggleTypes) },
    )
}

/** "12 locations", or "3 / 12 locations" while a filter or search narrows the map. */
private fun countLabel(state: MapUiState): String {
    val total = state.locations.size
    val shown = state.filteredLocations.size
    val noun = if (total == 1) "location" else "locations"
    val narrowed = state.typeFilters.isNotEmpty() || !state.search?.query.isNullOrBlank()
    return if (narrowed) "$shown / $total $noun" else "$shown $noun"
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(38.dp)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(if (hovered) MapColors.BrandSoft else Color.White)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.ArrowLeft, contentDescription = "Back to Film Tools", tint = MapColors.Brand, size = 18.dp)
    }
}

/**
 * One toolbar control. White with orange text at rest; the darker orange
 * when on; red for the one that cancels a mode — the header is orange, so
 * each state has to contrast with it rather than with white.
 */
@Composable
internal fun ToolbarPill(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    active: Boolean = false,
    danger: Boolean = false,
    count: Int? = null,
    maxTextWidth: Int? = null,
) {
    val (source, hovered) = rememberHover()
    val colors = ZillitTheme.colors
    val background = when {
        danger -> if (hovered) MapColors.DangerDeep else MapColors.Danger
        active -> if (hovered) MapColors.BrandActive.copy(alpha = 0.9f) else MapColors.BrandActive
        colors.isDark -> if (hovered) colors.surfaceHover else colors.surface
        else -> if (hovered) MapColors.BrandSoft else Color.White
    }
    val content = if (danger || active) Color.White else MapColors.BrandText
    Row(
        modifier = Modifier
            .height(30.dp)
            .shadow(1.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(background)
            .then(
                if (active || danger) {
                    Modifier.border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(50))
                } else {
                    Modifier
                },
            )
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(icon = icon, tint = content, size = 13.dp)
        ZillitText(
            text = text,
            style = labelBold(12.sp),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (maxTextWidth != null) Modifier.widthIn(max = maxTextWidth.dp) else Modifier,
        )
        if (count != null) CountBadge(count, if (active) BadgeTone.OnAccent else BadgeTone.Primary)
    }
}

/** The strip under the toolbar while pin mode is on. */
@Composable
internal fun PinModeBanner(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    val zone = state.activeZone
    val accent = if (zone != null) MapColors.BrandText else MapColors.Info
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(softOf(accent))
            .border(width = 0.dp, color = Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon = MapIcons.MapPin, tint = accent, size = 15.dp)
        ZillitText(
            text = if (zone != null) {
                "Click inside \"${zone.displayName}\" to place a pin (restricted to zone)"
            } else {
                "Click on the map to place a pin"
            },
            style = labelBold(13.sp, FontWeight.Medium),
            color = accent,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = "Drag a saved pin to move it",
            style = ZillitTheme.typography.bodySmall,
            color = accent.copy(alpha = 0.75f),
        )
        Spacer(Modifier.size(4.dp))
        val (source, hovered) = rememberHover()
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (hovered) accent.copy(alpha = 0.12f) else Color.Transparent)
                .hoverable(source)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = source, indication = null) { onEvent(MapEvent.Bars.ExitPinMode) },
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Close, contentDescription = "Exit pin mode", tint = accent, size = 14.dp)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.25f)))
}

/** Why the map is not drawing, above where it would be. */
@Composable
internal fun CanvasErrorBar(message: String, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(softOf(MapColors.Danger))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitIcon(icon = MapIcons.AlertTriangle, tint = MapColors.DangerDeep, size = 15.dp)
            ZillitText(
                text = message,
                style = labelBold(13.sp, FontWeight.Medium),
                color = MapColors.DangerDeep,
                modifier = Modifier.weight(1f),
            )
            CardAction(onClick = onDismiss, icon = ZillitIcons.Close, tone = ActionTone.Danger)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MapColors.Danger.copy(alpha = 0.25f)))
    }
}
