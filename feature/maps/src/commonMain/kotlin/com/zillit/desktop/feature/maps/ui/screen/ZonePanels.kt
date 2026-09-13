package com.zillit.desktop.feature.maps.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.ZoneRules
import com.zillit.desktop.feature.maps.domain.jsNumber
import com.zillit.desktop.feature.maps.domain.toFixed
import com.zillit.desktop.feature.maps.domain.zoneLocationCounts
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapUiState
import com.zillit.desktop.feature.maps.ui.ZoneFormState

/** The Studio Zones panel (`studio/StudioZoneListPanel.jsx`). */
@Composable
internal fun ZoneListPanel(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val city = state.selectedCity
    SidePanelFrame(
        width = RIGHT_PANEL_WIDTH,
        header = {
            HeroHeader(
                accent = MapColors.Zone,
                title = "Studio Zones",
                subtitle = "${city?.displayName ?: "All Cities"} · Activate a zone to view it on the map",
                onClose = { onEvent(MapEvent.Toolbar.ToggleZoneList) },
                trailing = {
                    HeroChip("${state.zones.size} ${if (state.zones.size == 1) "Zone" else "Zones"}", MapIcons.Target)
                    HeroPillButton("Add Zone", onClick = { onEvent(MapEvent.Zones.Add) }, icon = ZillitIcons.Add)
                },
            )
        },
    ) {
        if (state.activeZoneId != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(softOf(MapColors.Zone)).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitIcon(icon = MapIcons.Target, tint = MapColors.Zone, size = 14.dp)
                ZillitText(
                    text = "A zone is currently active on the map",
                    style = ZillitTheme.typography.bodySmall,
                    color = MapColors.Zone,
                    modifier = Modifier.weight(1f),
                )
                CardAction(onClick = { onEvent(MapEvent.Zones.Clear) }, icon = ZillitIcons.Close, text = "Clear Zone", tone = ActionTone.Danger)
            }
        }
        if (state.zones.isNotEmpty()) {
            PanelFilter(state.zoneFilter, "Filter by zone name or address...") { onEvent(MapEvent.Zones.Filter(it)) }
        }
        val query = state.zoneFilter.trim().lowercase()
        val shown = state.zones
            .filter { query.isEmpty() || it.name.lowercase().contains(query) || it.address.lowercase().contains(query) }
            .sortedBy { it.name.lowercase() }
        val counts = zoneLocationCounts(state.zones, state.locations)
        ZillitScrollColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.zonesLoading && state.zones.isEmpty() -> LoadingBlock()
                shown.isEmpty() && query.isNotEmpty() -> EmptyBlock(
                    icon = ZillitIcons.Search,
                    accent = colors.textMuted,
                    title = "No zones match \"${state.zoneFilter}\"",
                    message = "Try a different search term",
                )
                state.zones.isEmpty() -> EmptyBlock(
                    icon = MapIcons.Target,
                    accent = MapColors.Zone,
                    title = "No studio zones yet",
                    message = "Create studio zones to group locations within a defined radius and bound pinning to that area.",
                    action = {
                        ZillitButton(text = "Add Studio Zone", onClick = { onEvent(MapEvent.Zones.Add) }, leadingIcon = ZillitIcons.Add)
                    },
                )
                else -> shown.forEach { zone ->
                    ZoneCard(
                        zone = zone,
                        active = zone.id == state.activeZoneId,
                        locationCount = counts[zone.id] ?: 0,
                        canPost = state.viewer.mayPost,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoneCard(zone: MapLocation, active: Boolean, locationCount: Int, canPost: Boolean, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(if (active) 2.dp else 1.dp, if (active) MapColors.Zone.copy(alpha = 0.6f) else colors.border, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconChip(
                icon = MapIcons.Target,
                tint = if (active) Color.White else MapColors.Zone,
                background = if (active) MapColors.Zone else softOf(MapColors.Zone),
                size = 32.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZillitText(
                        text = zone.displayName,
                        style = labelBold(14.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) SmallTag("ACTIVE", MapColors.Zone)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZillitText(text = "${jsNumber(zone.zoneRadiusMiles)} mi radius", style = labelBold(11.sp, FontWeight.Medium), color = MapColors.Zone)
                    if (locationCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            ZillitIcon(icon = MapIcons.MapPin, tint = colors.textMuted, size = 10.dp)
                            ZillitText(text = locationCount.toString(), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                        }
                    }
                    if (zone.address.isNotBlank()) {
                        ZillitText(
                            text = zone.address,
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active) {
                CardAction(onClick = { onEvent(MapEvent.Zones.Clear) }, icon = MapIcons.EyeOff, text = "Hide", tone = ActionTone.ZoneActive)
            } else {
                CardAction(onClick = { onEvent(MapEvent.Zones.Activate(zone.id)) }, icon = MapIcons.Navigation, text = "Map", tone = ActionTone.Zone)
            }
            CardAction(onClick = { onEvent(MapEvent.Zones.Details(zone.id)) }, icon = ZillitIcons.Eye, text = "Details")
            if (canPost) {
                CardAction(onClick = { onEvent(MapEvent.Zones.Edit(zone.id)) }, icon = ZillitIcons.Edit, text = "Edit", tone = ActionTone.Zone)
                Spacer(Modifier.weight(1f))
                CardAction(onClick = { onEvent(MapEvent.Zones.Delete(zone.id)) }, icon = ZillitIcons.Trash, tone = ActionTone.Danger)
            }
        }
    }
}

@Composable
internal fun SmallTag(text: String, color: Color) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        ZillitText(text = text, style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = Color.White)
    }
}

/** The studio-zone form (`studio/StudioZoneFormPanel.jsx`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZoneFormPanel(state: MapUiState, form: ZoneFormState, onEvent: (MapEvent) -> Unit) {
    val city = state.selectedCity
    val cityName = city?.displayName ?: "the city"
    SidePanelFrame(
        width = RIGHT_PANEL_WIDTH,
        header = {
            HeroHeader(
                accent = MapColors.Zone,
                title = if (form.isEdit) "Edit Studio Zone" else "New Studio Zone",
                subtitle = if (form.isEdit) {
                    "Update the zone boundary or radius and save your changes."
                } else {
                    "Define a circular area for filming, parking or crew staging."
                },
                onClose = { onEvent(MapEvent.Zones.CloseForm) },
                trailing = { HeroChip(if (form.isEdit) "Editing" else "New entry", if (form.isEdit) ZillitIcons.Edit else ZillitIcons.Add) },
            )
        },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ZillitButton(text = "Cancel", onClick = { onEvent(MapEvent.Zones.CloseForm) }, variant = ButtonVariant.Secondary)
                ZillitButton(
                    text = when {
                        form.saving -> "Saving..."
                        form.isEdit -> "Update Zone"
                        else -> "Create Zone"
                    },
                    onClick = { onEvent(MapEvent.Zones.Save) },
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
            SectionCard(title = "Center Point & Zone", icon = MapIcons.Target, accent = MapColors.Zone) {
                SoftBanner(accent = MapColors.Zone, icon = MapIcons.MapPin, title = cityName, body = city?.description)
                Hint("Choose how to define the center point within $cityName.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton(
                        text = "Center Point",
                        selected = form.mode == CenterPointType.Point,
                        onClick = { onEvent(MapEvent.Zones.Mode(CenterPointType.Point)) },
                        accent = MapColors.Zone,
                        icon = MapIcons.MapPin,
                    )
                    ChoiceButton(
                        text = "Street Intersection",
                        selected = form.mode == CenterPointType.Intersection,
                        onClick = { onEvent(MapEvent.Zones.Mode(CenterPointType.Intersection)) },
                        accent = MapColors.Zone,
                        icon = MapIcons.Target,
                    )
                }
                if (form.mode == CenterPointType.Point) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Search Center Point", ZillitIcons.Search, MapColors.Zone)
                        ZillitTextField(
                            value = form.centerQuery,
                            onValueChange = { onEvent(MapEvent.Zones.CenterQuery(it)) },
                            placeholder = "Search center point within $cityName...",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SuggestionList(form.centerSuggestions, onPick = { onEvent(MapEvent.Zones.CenterPick(it)) })
                        Hint("Center point must be within zone radius of the city")
                    }
                } else {
                    IntersectionFields(form, cityName, onEvent)
                }
                Hint("Drag the zone's centre on the map to fine-tune the exact location")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Latitude", MapIcons.Globe, MapColors.Zone)
                        ReadOnlyBox(form.point?.let { toFixed(it.lat, 6) }.orEmpty(), "Auto-filled")
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Longitude", MapIcons.Globe, MapColors.Zone)
                        ReadOnlyBox(form.point?.let { toFixed(it.lng, 6) }.orEmpty(), "Auto-filled")
                    }
                }
            }
            SectionCard(title = "Zone Details", icon = MapIcons.TypeGlyph, accent = MapColors.Zone) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel("Zone Name", MapIcons.TypeGlyph, MapColors.Zone, required = true)
                    ZillitTextField(
                        value = form.name,
                        onValueChange = { onEvent(MapEvent.Zones.Name(it)) },
                        placeholder = "e.g., Sector 62 Zone, Dadri Chowk Zone",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Auto-filled from the selected location — you can edit it.")
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Required only under Custom: a preset always has a radius.
                    FieldLabel("Zone Radius", MapIcons.Target, MapColors.Zone, required = form.useCustom)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZoneRules.PRESETS.forEach { miles ->
                            ChoiceButton(
                                text = "$miles mi",
                                selected = !form.useCustom && form.preset == miles,
                                onClick = { onEvent(MapEvent.Zones.Preset(miles)) },
                                accent = MapColors.Zone,
                            )
                        }
                        ChoiceButton(
                            text = "Custom",
                            selected = form.useCustom,
                            onClick = { onEvent(MapEvent.Zones.Custom) },
                            accent = MapColors.Zone,
                        )
                    }
                    if (form.useCustom) {
                        ZillitTextField(
                            value = form.customRadius,
                            onValueChange = { text -> onEvent(MapEvent.Zones.CustomRadius(text.filter { it.isDigit() || it == '.' })) },
                            placeholder = "Enter radius in miles",
                            modifier = Modifier
                                .fillMaxWidth()
                                .onLeave { onEvent(MapEvent.Zones.CustomRadiusLeft) },
                            errorText = if (form.showCustomError) form.customError else null,
                            trailingContent = { ZillitText(text = "mi", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted) },
                        )
                    }
                }
            }
            SectionCard(title = "Zone Preview", icon = MapIcons.Globe, accent = MapColors.Zone) {
                val centre = form.point
                if (centre != null) {
                    AsyncPicture(
                        key = Triple(centre, form.effectiveRadius, "form"),
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)),
                    ) { zonePreview(centre, form.effectiveRadius) }
                }
                if (form.effectiveRadius > 0) {
                    ZillitText(
                        text = "This zone covers a ${jsNumber(form.effectiveRadius)}-mile radius around the center point.",
                        style = ZillitTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        color = ZillitTheme.colors.textSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntersectionFields(form: ZoneFormState, cityName: String, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("Street 1", ZillitIcons.Search, MapColors.Zone, required = true)
        ZillitTextField(
            value = form.street1,
            onValueChange = { onEvent(MapEvent.Zones.Street1(it)) },
            placeholder = "e.g., MG Road in $cityName",
            modifier = Modifier.fillMaxWidth(),
        )
        SuggestionList(form.street1Suggestions, onPick = { onEvent(MapEvent.Zones.Street1Pick(it)) })
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(colors.divider))
            ZillitText(text = "&", style = labelBold(12.sp, FontWeight.Medium), color = colors.textMuted)
            Box(Modifier.weight(1f).height(1.dp).background(colors.divider))
        }
        FieldLabel("Street 2", ZillitIcons.Search, MapColors.Zone)
        ZillitTextField(
            value = form.street2,
            onValueChange = { onEvent(MapEvent.Zones.Street2(it)) },
            placeholder = "e.g., Ring Road in $cityName",
            modifier = Modifier.fillMaxWidth(),
        )
        SuggestionList(form.street2Suggestions, onPick = { onEvent(MapEvent.Zones.Street2Pick(it)) })
        ZillitButton(
            text = if (form.finding) "Selecting..." else "Select Intersection",
            onClick = { onEvent(MapEvent.Zones.FindIntersection) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Search,
            loading = form.finding,
            enabled = !form.finding && form.street1.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        val found = form.intersection
        if (found != null) {
            SoftBanner(accent = MapColors.Success, icon = MapIcons.CheckCircle, title = "Intersection Found", body = found)
        }
        Hint("Enter a primary street/road. Optionally add a second street to pinpoint a chowk or crossing.")
    }
}

/** Details of one zone (`studio/StudioZoneDetail.jsx`). */
@Composable
internal fun ZoneDetailPanel(state: MapUiState, zone: MapLocation, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val intersection = zone.centerPointType == CenterPointType.Intersection
    val streets = zone.intersection
    SidePanelFrame(
        width = RIGHT_PANEL_WIDTH,
        header = {
            HeroHeader(
                accent = MapColors.Zone,
                title = zone.displayName,
                subtitle = null,
                onClose = { onEvent(MapEvent.Zones.CloseDetail) },
                trailing = { HeroPillButton("Edit", onClick = { onEvent(MapEvent.Zones.Edit(zone.id)) }, icon = ZillitIcons.Edit) },
                eyebrow = {
                    HeroChip("Studio Zone", MapIcons.Target)
                    HeroChip("${jsNumber(zone.zoneRadiusMiles)} mi radius")
                },
                below = if (zone.address.isNotBlank()) {
                    {
                        Row(
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ZillitIcon(icon = MapIcons.MapPin, tint = Color.White.copy(alpha = 0.85f), size = 14.dp)
                            ZillitText(text = zone.address, style = ZillitTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
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
            SectionCard(title = "Zone Details", icon = MapIcons.Crosshair, accent = MapColors.Zone) {
                if (zone.address.isNotBlank()) {
                    InfoRow(MapIcons.MapPin, "Address", MapColors.Zone) {
                        ZillitText(text = zone.address, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary)
                    }
                }
                if (intersection && streets != null) {
                    InfoRow(MapIcons.Target, if (streets.street2.isBlank()) "Street" else "Street Intersection", MapColors.Zone) {
                        ZillitText(text = streets.label, style = labelBold(14.sp, FontWeight.Medium), color = colors.textPrimary)
                    }
                }
                InfoRow(MapIcons.Target, "Radius", MapColors.Zone) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(MapColors.Zone.copy(alpha = 0.7f)))
                        ZillitText(text = "${jsNumber(zone.zoneRadiusMiles)} miles", style = labelBold(14.sp), color = colors.textPrimary)
                    }
                }
                zone.point?.let { point ->
                    InfoRow(MapIcons.Globe, if (intersection) "Intersection Coordinates" else "Center Point", MapColors.Zone) {
                        ZillitText(
                            text = "${toFixed(point.lat, 6)}, ${toFixed(point.lng, 6)}",
                            style = ZillitTheme.typography.bodyMedium.copy(fontFamily = ZillitTheme.fonts.mono),
                            color = colors.textPrimary,
                        )
                    }
                }
            }
            val centre = zone.point
            if (centre != null) {
                SectionCard(title = "Zone Preview", icon = MapIcons.Camera, accent = MapColors.Zone) {
                    AsyncPicture(
                        key = Triple(centre, zone.zoneRadiusMiles, "detail"),
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)),
                    ) { zonePreview(centre, zone.zoneRadiusMiles) }
                    ZillitText(
                        text = "This zone covers a ${jsNumber(zone.zoneRadiusMiles)}-mile radius around the center point.",
                        style = ZillitTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        color = colors.textSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Fires once when focus leaves the field — the web's `onBlur` that reveals an error. */
@Composable
internal fun Modifier.onLeave(action: () -> Unit): Modifier {
    val had = androidx.compose.runtime.remember { booleanArrayOf(false) }
    return this.then(
        Modifier.onFocusChanged { focus ->
            if (had[0] && !focus.hasFocus) action()
            had[0] = focus.hasFocus
        },
    )
}

internal val RIGHT_PANEL_WIDTH = 480.dp
