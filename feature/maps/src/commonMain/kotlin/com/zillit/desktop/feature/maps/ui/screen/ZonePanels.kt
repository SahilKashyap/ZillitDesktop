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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
@Suppress("CyclomaticComplexMethod", "LongMethod") // One panel, read top to bottom.
internal fun ZoneListPanel(state: MapUiState, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val city = state.selectedCity
    SidePanelFrame(
        width = RIGHT_PANEL_WIDTH,
        header = {
            HeroHeader(
                accent = MapColors.Zone,
                title = str(S.desktop_map_studio_zones),
                subtitle = str(S.desktop_map_zones_subtitle, city?.displayName ?: str(S.desktop_map_all_cities)),
                onClose = { onEvent(MapEvent.Toolbar.ToggleZoneList) },
                trailing = {
                    HeroChip(
                        if (state.zones.size == 1) {
                            str(S.desktop_map_zone_count_one, state.zones.size)
                        } else {
                            str(S.desktop_map_zone_count_other, state.zones.size)
                        },
                        MapIcons.Target,
                    )
                    HeroPillButton(str(S.desktop_map_add_zone), onClick = { onEvent(MapEvent.Zones.Add) },
                        icon = ZillitIcons.Add)
                },
            )
        },
    ) {
        if (state.activeZoneId != null) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(softOf(MapColors.Zone))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitIcon(icon = MapIcons.Target, tint = MapColors.Zone, size = 14.dp)
                ZillitText(
                    text = str(S.desktop_map_zone_active_banner),
                    style = ZillitTheme.typography.bodySmall,
                    color = MapColors.Zone,
                    modifier = Modifier.weight(1f),
                )
                CardAction(
                    onClick = { onEvent(MapEvent.Zones.Clear) },
                    icon = ZillitIcons.Close,
                    text = str(S.desktop_map_clear_zone),
                    tone = ActionTone.Danger,
                )
            }
        }
        if (state.zones.isNotEmpty()) {
            PanelFilter(state.zoneFilter, str(S.desktop_map_filter_zones)) { onEvent(MapEvent.Zones.Filter(it)) }
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
                    title = str(S.desktop_map_no_zones_match, state.zoneFilter),
                    message = str(S.desktop_map_try_different_search),
                )
                state.zones.isEmpty() -> EmptyBlock(
                    icon = MapIcons.Target,
                    accent = MapColors.Zone,
                    title = str(S.desktop_map_no_zones_yet),
                    message = str(S.desktop_map_no_zones_msg),
                    action = {
                        ZillitButton(
                            text = str(S.desktop_map_add_studio_zone),
                            onClick = { onEvent(MapEvent.Zones.Add) },
                            leadingIcon = ZillitIcons.Add,
                        )
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
@Suppress("LongMethod") // One card, laid out in one place.
private fun ZoneCard(
    zone: MapLocation,
    active: Boolean,
    locationCount: Int,
    canPost: Boolean,
    onEvent: (MapEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(
                if (active) 2.dp else 1.dp,
                if (active) MapColors.Zone.copy(alpha = 0.6f) else colors.border,
                shape,
            ),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitText(
                        text = zone.displayName,
                        style = labelBold(14.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) SmallTag(str(S.active), MapColors.Zone)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        text = str(S.desktop_map_mi_radius, jsNumber(zone.zoneRadiusMiles)),
                        style = labelBold(11.sp, FontWeight.Medium),
                        color = MapColors.Zone,
                    )
                    if (locationCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            ZillitIcon(icon = MapIcons.MapPin, tint = colors.textMuted, size = 10.dp)
                            ZillitText(
                                text = locationCount.toString(),
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
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
            modifier = Modifier.fillMaxWidth()
                .background(colors.surfaceSunken.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active) {
                CardAction(
                    onClick = { onEvent(MapEvent.Zones.Clear) },
                    icon = MapIcons.EyeOff,
                    text = str(S.hide),
                    tone = ActionTone.ZoneActive,
                )
            } else {
                CardAction(
                    onClick = { onEvent(MapEvent.Zones.Activate(zone.id)) },
                    icon = MapIcons.Navigation,
                    text = str(S.map),
                    tone = ActionTone.Zone,
                )
            }
            CardAction(onClick = { onEvent(MapEvent.Zones.Details(zone.id)) }, icon = ZillitIcons.Eye,
                text = str(S.details))
            if (canPost) {
                CardAction(
                    onClick = { onEvent(MapEvent.Zones.Edit(zone.id)) },
                    icon = ZillitIcons.Edit,
                    text = str(S.edit),
                    tone = ActionTone.Zone,
                )
                Spacer(Modifier.weight(1f))
                CardAction(
                    onClick = { onEvent(MapEvent.Zones.Delete(zone.id)) },
                    icon = ZillitIcons.Trash,
                    tone = ActionTone.Danger,
                )
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
@Suppress("CyclomaticComplexMethod", "LongMethod") // A form; each field is one line of it.
internal fun ZoneFormPanel(state: MapUiState, form: ZoneFormState, onEvent: (MapEvent) -> Unit) {
    val city = state.selectedCity
    val cityName = city?.displayName ?: str(S.desktop_map_the_city)
    SidePanelFrame(
        width = RIGHT_PANEL_WIDTH,
        header = {
            HeroHeader(
                accent = MapColors.Zone,
                title = if (form.isEdit) str(S.desktop_map_edit_studio_zone) else str(S.desktop_map_new_studio_zone),
                subtitle = if (form.isEdit) {
                    str(S.desktop_map_edit_zone_subtitle)
                } else {
                    str(S.desktop_map_new_zone_subtitle)
                },
                onClose = { onEvent(MapEvent.Zones.CloseForm) },
                trailing = {
                    HeroChip(
                        if (form.isEdit) str(S.desktop_editing) else str(S.desktop_map_new_entry),
                        if (form.isEdit) ZillitIcons.Edit else ZillitIcons.Add,
                    )
                },
            )
        },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ZillitButton(
                    text = str(S.cancel),
                    onClick = { onEvent(MapEvent.Zones.CloseForm) },
                    variant = ButtonVariant.Secondary,
                )
                ZillitButton(
                    text = when {
                        form.saving -> str(S.ah_saving)
                        form.isEdit -> str(S.desktop_map_update_zone)
                        else -> str(S.desktop_map_create_zone)
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
            SectionCard(title = str(S.desktop_map_center_point_and_zone), icon = MapIcons.Target,
                accent = MapColors.Zone) {
                SoftBanner(accent = MapColors.Zone, icon = MapIcons.MapPin, title = cityName, body = city?.description)
                Hint(str(S.desktop_map_choose_center_point, cityName))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton(
                        text = str(S.desktop_map_center_point),
                        selected = form.mode == CenterPointType.Point,
                        onClick = { onEvent(MapEvent.Zones.Mode(CenterPointType.Point)) },
                        accent = MapColors.Zone,
                        icon = MapIcons.MapPin,
                    )
                    ChoiceButton(
                        text = str(S.desktop_map_street_intersection),
                        selected = form.mode == CenterPointType.Intersection,
                        onClick = { onEvent(MapEvent.Zones.Mode(CenterPointType.Intersection)) },
                        accent = MapColors.Zone,
                        icon = MapIcons.Target,
                    )
                }
                if (form.mode == CenterPointType.Point) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel(str(S.desktop_map_search_center_point), ZillitIcons.Search, MapColors.Zone)
                        ZillitTextField(
                            value = form.centerQuery,
                            onValueChange = { onEvent(MapEvent.Zones.CenterQuery(it)) },
                            placeholder = str(S.desktop_map_search_center_in_city, cityName),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SuggestionList(form.centerSuggestions, onPick = { onEvent(MapEvent.Zones.CenterPick(it)) })
                        Hint(str(S.desktop_map_center_within_radius))
                    }
                } else {
                    IntersectionFields(form, cityName, onEvent)
                }
                Hint(str(S.desktop_map_drag_zone_centre))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel(str(S.desktop_latitude), MapIcons.Globe, MapColors.Zone)
                        ReadOnlyBox(form.point?.let { toFixed(it.lat, 6) }.orEmpty(), str(S.desktop_map_auto_filled))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel(str(S.desktop_longitude), MapIcons.Globe, MapColors.Zone)
                        ReadOnlyBox(form.point?.let { toFixed(it.lng, 6) }.orEmpty(), str(S.desktop_map_auto_filled))
                    }
                }
            }
            SectionCard(title = str(S.desktop_map_zone_details), icon = MapIcons.TypeGlyph, accent = MapColors.Zone) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel(str(S.desktop_map_zone_name), MapIcons.TypeGlyph, MapColors.Zone, required = true)
                    ZillitTextField(
                        value = form.name,
                        onValueChange = { onEvent(MapEvent.Zones.Name(it)) },
                        placeholder = str(S.desktop_map_zone_name_hint),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(str(S.desktop_map_zone_name_autofill))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Required only under Custom: a preset always has a radius.
                    FieldLabel(str(S.desktop_map_zone_radius), MapIcons.Target, MapColors.Zone,
                        required = form.useCustom)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ZoneRules.PRESETS.forEach { miles ->
                            ChoiceButton(
                                text = str(S.desktop_map_miles_choice, miles),
                                selected = !form.useCustom && form.preset == miles,
                                onClick = { onEvent(MapEvent.Zones.Preset(miles)) },
                                accent = MapColors.Zone,
                            )
                        }
                        ChoiceButton(
                            text = str(S.custom),
                            selected = form.useCustom,
                            onClick = { onEvent(MapEvent.Zones.Custom) },
                            accent = MapColors.Zone,
                        )
                    }
                    if (form.useCustom) {
                        ZillitTextField(
                            value = form.customRadius,
                            onValueChange = { text ->
                                onEvent(MapEvent.Zones.CustomRadius(text.filter { it.isDigit() || it == '.' }))
                            },
                            placeholder = str(S.desktop_map_enter_radius_miles),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onLeave { onEvent(MapEvent.Zones.CustomRadiusLeft) },
                            errorText = if (form.showCustomError) form.customError else null,
                            trailingContent = {
                                ZillitText(
                                    text = str(S.desktop_map_mi),
                                    style = ZillitTheme.typography.bodySmall,
                                    color = ZillitTheme.colors.textMuted,
                                )
                            },
                        )
                    }
                }
            }
            SectionCard(title = str(S.desktop_map_zone_preview), icon = MapIcons.Globe, accent = MapColors.Zone) {
                val centre = form.point
                if (centre != null) {
                    AsyncPicture(
                        key = Triple(centre, form.effectiveRadius, "form"),
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)),
                    ) { zonePreview(centre, form.effectiveRadius) }
                }
                if (form.effectiveRadius > 0) {
                    ZillitText(
                        text = str(S.desktop_map_zone_covers, jsNumber(form.effectiveRadius)),
                        style = ZillitTheme.typography.bodySmall
                            .copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
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
        FieldLabel(str(S.desktop_map_street_1), ZillitIcons.Search, MapColors.Zone, required = true)
        ZillitTextField(
            value = form.street1,
            onValueChange = { onEvent(MapEvent.Zones.Street1(it)) },
            placeholder = str(S.desktop_map_street_1_hint, cityName),
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
        FieldLabel(str(S.desktop_map_street_2), ZillitIcons.Search, MapColors.Zone)
        ZillitTextField(
            value = form.street2,
            onValueChange = { onEvent(MapEvent.Zones.Street2(it)) },
            placeholder = str(S.desktop_map_street_2_hint, cityName),
            modifier = Modifier.fillMaxWidth(),
        )
        SuggestionList(form.street2Suggestions, onPick = { onEvent(MapEvent.Zones.Street2Pick(it)) })
        ZillitButton(
            text = if (form.finding) str(S.desktop_map_selecting) else str(S.desktop_map_select_intersection),
            onClick = { onEvent(MapEvent.Zones.FindIntersection) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Search,
            loading = form.finding,
            enabled = !form.finding && form.street1.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        val found = form.intersection
        if (found != null) {
            SoftBanner(
                accent = MapColors.Success,
                icon = MapIcons.CheckCircle,
                title = str(S.desktop_map_intersection_found_title),
                body = found,
            )
        }
        Hint(str(S.desktop_map_intersection_hint))
    }
}

/** Details of one zone (`studio/StudioZoneDetail.jsx`). */
@Composable
@Suppress("LongMethod", "UnusedParameter") // One panel; `state` keeps the panels' shared shape.
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
                trailing = {
                    HeroPillButton(
                        str(S.edit),
                        onClick = { onEvent(MapEvent.Zones.Edit(zone.id)) },
                        icon = ZillitIcons.Edit,
                    )
                },
                eyebrow = {
                    HeroChip(str(S.studio_zone_txt), MapIcons.Target)
                    HeroChip(str(S.desktop_map_mi_radius, jsNumber(zone.zoneRadiusMiles)))
                },
                below = if (zone.address.isNotBlank()) {
                    {
                        Row(
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ZillitIcon(icon = MapIcons.MapPin, tint = Color.White.copy(alpha = 0.85f), size = 14.dp)
                            ZillitText(
                                text = zone.address,
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
            SectionCard(title = str(S.desktop_map_zone_details), icon = MapIcons.Crosshair, accent = MapColors.Zone) {
                if (zone.address.isNotBlank()) {
                    InfoRow(MapIcons.MapPin, str(S.address), MapColors.Zone) {
                        ZillitText(
                            text = zone.address,
                            style = ZillitTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                        )
                    }
                }
                if (intersection && streets != null) {
                    InfoRow(
                        MapIcons.Target,
                        if (streets.street2.isBlank()) {
                            str(S.desktop_map_street)
                        } else {
                            str(S.desktop_map_street_intersection)
                        },
                        MapColors.Zone,
                    ) {
                        ZillitText(
                            text = streets.label,
                            style = labelBold(14.sp, FontWeight.Medium),
                            color = colors.textPrimary,
                        )
                    }
                }
                InfoRow(MapIcons.Target, str(S.radius_txt), MapColors.Zone) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(MapColors.Zone.copy(alpha = 0.7f)))
                        ZillitText(
                            text = str(S.desktop_map_miles_value, jsNumber(zone.zoneRadiusMiles)),
                            style = labelBold(14.sp),
                            color = colors.textPrimary,
                        )
                    }
                }
                zone.point?.let { point ->
                    InfoRow(
                        MapIcons.Globe,
                        if (intersection) {
                            str(S.desktop_map_intersection_coordinates)
                        } else {
                            str(S.desktop_map_center_point)
                        },
                        MapColors.Zone,
                    ) {
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
                SectionCard(title = str(S.desktop_map_zone_preview), icon = MapIcons.Camera, accent = MapColors.Zone) {
                    AsyncPicture(
                        key = Triple(centre, zone.zoneRadiusMiles, "detail"),
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)),
                    ) { zonePreview(centre, zone.zoneRadiusMiles) }
                    ZillitText(
                        text = str(S.desktop_map_zone_covers, jsNumber(zone.zoneRadiusMiles)),
                        style = ZillitTheme.typography.bodySmall
                            .copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
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
