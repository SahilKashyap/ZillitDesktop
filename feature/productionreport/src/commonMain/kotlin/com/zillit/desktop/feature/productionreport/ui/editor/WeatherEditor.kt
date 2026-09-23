// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.productionreport.ui.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.ReportTime
import com.zillit.desktop.feature.productionreport.domain.ReportWeather
import com.zillit.desktop.feature.productionreport.domain.WeatherValue
import com.zillit.desktop.feature.productionreport.ui.DocumentEvent
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.WeatherPanel
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportInput
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `WeatherWidget`: the fetched forecast as a card with the shoot-day strip,
 * or the empty state that fetches one — by map pick or coordinates, since a
 * desktop has no browser geolocation — with free text as the fallback.
 */
@Composable
internal fun WeatherEditor(
    raw: String,
    address: CellAddress,
    shootYmd: String,
    panel: WeatherPanel?,
    nowMillis: Long,
    onEvent: (ReportEvent) -> Unit,
) {
    val weather = WeatherValue.parse(raw)
    val live = panel?.takeIf { it.row == address.row && it.cell == address.cell }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (weather != null) {
            WeatherCard(weather, live, shootYmd, nowMillis, address, onEvent)
        } else {
            EmptyWeather(live, address, onEvent)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PaneEyebrow(str(S.desktop_or_type_manually))
                ReportInput(
                    raw,
                    { onEvent(DocumentEvent.SetWeatherText(address.row, address.cell, it)) },
                    Modifier.fillMaxWidth(),
                    placeholder = str(S.desktop_weather_manual_hint),
                )
            }
        }
        live?.error?.let { WeatherError(it) }
    }
}

@Composable
private fun WeatherError(message: String) {
    val colors = ReportTheme.colors
    Text(
        message,
        style = reportText(12.sp),
        color = colors.red,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.redBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyWeather(live: WeatherPanel?, address: CellAddress, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val picker = LocalLocationPicker.current
    val scope = rememberCoroutineScope()
    var manual by remember { mutableStateOf(picker == null) }
    val fetching = live?.fetching == true
    val dash = colors.borderStrong
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elevated)
            .drawBehind {
                drawRoundRect(
                    dash,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                )
            }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(colors.accentLight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ReportIcons.Cloud, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        }
        Text(
            str(S.desktop_add_weather_data),
            style = reportText(14.sp, FontWeight.SemiBold),
            color = colors.textPrimary,
        )
        Text(
            str(S.desktop_weather_fetch_hint),
            style = reportText(12.sp, lineHeight = 17.sp),
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        Column(Modifier.widthIn(max = 300.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (picker != null) {
                ReportButton(
                    if (fetching) str(S.desktop_fetching_dots) else str(S.desktop_pick_location_on_map_title),
                    {
                        scope.launch {
                            picker.pick(null, str(S.desktop_weather_location))?.let { place ->
                                onEvent(
                                    DocumentEvent.FetchWeather(
                                        address.row,
                                        address.cell,
                                        place.lat,
                                        place.lng,
                                        place.name.ifBlank { place.address },
                                    ),
                                )
                            }
                        }
                    },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Accent,
                    icon = ReportIcons.Aim,
                    enabled = !fetching,
                    height = 40.dp,
                    fontSize = 14.sp,
                )
            }
            if (manual) {
                CoordinatesPanel(fetching) { lat, lng ->
                    onEvent(
                        DocumentEvent.FetchWeather(
                            address.row,
                            address.cell,
                            lat,
                            lng,
                            "${lat.round4()}, ${lng.round4()}",
                        ),
                    )
                }
            } else {
                ReportButton(
                    str(S.desktop_enter_coordinates),
                    { manual = true },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Outline,
                    icon = ZillitIcons.Edit,
                    height = 38.dp,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private fun Double.round4(): String = ((this * FOUR_PLACES).roundToInt() / FOUR_PLACES).toString()

private const val FOUR_PLACES = 10_000.0

@Composable
private fun CoordinatesPanel(fetching: Boolean, onFetch: (Double, Double) -> Unit) {
    val colors = ReportTheme.colors
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaneEyebrow(str(S.desktop_manual_coordinates), strong = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportInput(
                lat,
                { lat = it; invalid = false },
                Modifier.weight(1f),
                placeholder = str(S.desktop_latitude),
                textStyle = reportText(13.sp),
            )
            ReportInput(
                lng,
                { lng = it; invalid = false },
                Modifier.weight(1f),
                placeholder = str(S.desktop_longitude),
                textStyle = reportText(13.sp),
            )
        }
        if (invalid) Text(str(S.desktop_enter_valid_lat_long), style = reportText(12.sp), color = colors.red)
        ReportButton(
            if (fetching) str(S.desktop_fetching_dots) else str(S.desktop_fetch_weather),
            {
                val latitude = lat.trim().toDoubleOrNull()
                val longitude = lng.trim().toDoubleOrNull()
                if (latitude == null || longitude == null) invalid = true else onFetch(latitude, longitude)
            },
            Modifier.fillMaxWidth(),
            kind = ButtonKind.Accent,
            enabled = !fetching,
            height = 34.dp,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun WeatherCard(
    weather: WeatherValue,
    live: WeatherPanel?,
    shootYmd: String,
    nowMillis: Long,
    address: CellAddress,
    onEvent: (ReportEvent) -> Unit,
) {
    val colors = ReportTheme.colors
    val picker = LocalLocationPicker.current
    val scope = rememberCoroutineScope()
    val ink = if (colors.isDark) colors.textPrimary else Color(0xFF1D2939)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1D2939)).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("📍", style = reportText(12.sp))
            Text(
                weather.location.ifBlank { str(S.desktop_unknown) },
                style = reportText(12.sp, FontWeight.Medium),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            weather.forecastDate?.let { seconds ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.accent)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        ZillitIcons.Calendar,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(9.dp),
                    )
                    Text(shortDate(seconds), style = reportText(9.sp, FontWeight.SemiBold, 12.sp), color = Color.White)
                }
            }
            Box(Modifier.weight(1f))
            Text(age(weather.fetchedAt, nowMillis), style = reportText(9.sp), color = Color.White.copy(alpha = 0.45f))
            RefreshMark(spinning = live?.fetching == true) {
                onEvent(
                    DocumentEvent.RefreshWeather(address.row, address.cell),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .background(
                    if (colors.isDark) Brush.linearGradient(listOf(
                        colors.surface,
                        colors.surface,
                    )) else Brush.linearGradient(listOf(Color(0xFFF8FAFC), Color(0xFFEEF2F7))),
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (colors.isDark) colors.elevated else Color.White)
                        .border(1.dp, colors.borderFaint, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(weatherGlyph(weather.icon), style = reportText(30.sp, lineHeight = 34.sp))
                }
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${weather.tempC ?: "--"}°",
                            style = reportText(30.sp, FontWeight.Bold, 34.sp),
                            color = ink,
                        )
                        Text("C", style = reportText(18.sp, FontWeight.SemiBold, 26.sp), color = ink)
                    }
                    Text(
                        "${weather.tempF ?: "--"}°F · H: ${weather.tempHighC ?: "--"}° L: ${weather.tempLowC ?: "--"}°",
                        style = reportText(12.sp),
                        color = colors.textTertiary,
                    )
                    Text(
                        weather.description.ifBlank { weather.condition }.replaceFirstChar { it.uppercase() },
                        style = reportText(14.sp, FontWeight.Medium),
                        color = colors.textSecondary,
                    )
                }
            }
            val stats = listOf(
                Triple(str(S.wp_feels_like), "${weather.feelsLikeC ?: "--"}", "°C"),
                Triple(str(S.wp_humidity), "${weather.humidity ?: "--"}", "%"),
                Triple(str(S.wp_wind), "${weather.windKmh ?: "--"}", "km/h"),
                Triple(
                    str(S.wp_uv_index),
                    weather.uvi?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "--",
                    "",
                ),
                Triple(str(S.wp_pressure), "${weather.pressure ?: "--"}", "hPa"),
                if (weather.visibilityMiles != null) {
                    Triple(str(S.wp_visibility), "${weather.visibilityMiles}", "mi")
                } else {
                    Triple(str(S.desktop_high_low), "${weather.tempHighC ?: "--"}/${weather.tempLowC ?: "--"}", "°C")
                },
            )
            stats.chunked(3).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    line.forEach { (label, value, unit) -> StatTile(label, value, unit, Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SunTile("🌅", str(S.wp_sunrise), weather.clock(weather.sunrise), Modifier.weight(1f))
                SunTile("🌇", str(S.wp_sunset), weather.clock(weather.sunset), Modifier.weight(1f))
            }
        }
        live?.response?.let { response -> ForecastStrip(response, weather, shootYmd, address, onEvent) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (picker != null) {
            ReportButton(
                str(S.desktop_change_location),
                {
                    scope.launch {
                        picker.pick(null, str(S.desktop_weather_location))?.let { place ->
                            onEvent(
                                DocumentEvent.FetchWeather(
                                    address.row,
                                    address.cell,
                                    place.lat,
                                    place.lng,
                                    place.name.ifBlank { place.address },
                                ),
                            )
                        }
                    }
                },
                Modifier.weight(1f),
                kind = ButtonKind.Navy,
                icon = ReportIcons.Swap,
                height = 34.dp,
                fontSize = 12.sp,
            )
        }
        ReportButton(
            str(S.txt_clear),
            { onEvent(DocumentEvent.SetWeatherText(address.row, address.cell, "")) },
            kind = ButtonKind.DangerOutline,
            icon = ZillitIcons.Trash,
            height = 34.dp,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (colors.isDark) colors.elevated else Color.White)
            .border(1.dp, if (hovered) colors.border else colors.borderFaint, RoundedCornerShape(8.dp))
            .hoverable(source)
            .padding(8.dp),
    ) {
        Text(
            label.uppercase(),
            style = reportText(8.sp, FontWeight.SemiBold, 11.sp).copy(letterSpacing = 0.4.sp),
            color = colors.textMuted,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = reportText(13.sp, FontWeight.Bold, 17.sp), color = colors.textPrimary)
            if (unit.isNotEmpty()) Text(
                unit,
                style = reportText(10.sp, lineHeight = 15.sp),
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun SunTile(glyph: String, label: String, time: String, modifier: Modifier) {
    val colors = ReportTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (colors.isDark) colors.elevated else Color.White)
            .border(1.dp, colors.borderFaint, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(glyph, style = reportText(18.sp, lineHeight = 22.sp))
        Column {
            Text(label.uppercase(), style = reportText(8.sp, FontWeight.SemiBold, 11.sp), color = colors.textMuted)
            Text(time, style = reportText(14.sp, FontWeight.Bold), color = colors.textPrimary)
        }
    }
}

@Composable
private fun RefreshMark(spinning: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val turn = rememberInfiniteTransition()
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart))
    ZillitTooltip(str(S.refresh_text)) {
        Icon(
            ZillitIcons.Reload,
            contentDescription = str(S.refresh_text),
            tint = Color.White.copy(alpha = if (hovered) 1f else 0.6f),
            modifier = Modifier
                .size(14.dp)
                .rotate(if (spinning) angle else 0f)
                .hoverable(source)
                .plainClick(enabled = !spinning, source = source, onClick = onClick),
        )
    }
}

/**
 * "Shoot day forecast": the eight days in memory. Only the shoot date is
 * pickable when the window reaches it; otherwise every day is.
 */
@Composable
private fun ForecastStrip(
    response: JsonObject,
    weather: WeatherValue,
    shootYmd: String,
    address: CellAddress,
    onEvent: (ReportEvent) -> Unit,
) {
    val colors = ReportTheme.colors
    val days = ReportWeather.forecastDays(response)
    val daily = (response["daily"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val shoot = runCatching { LocalDate.parse(ReportTime.toWireDate(shootYmd)) }.getOrNull()
    val shootInWindow = shoot != null && shoot in days
    val chosenDay = weather.forecastDate?.let { seconds ->
        daily.indexOfFirst { (it["dt"] as? JsonPrimitive)?.content?.toLongOrNull() == seconds }
    }
    Column(
        Modifier.fillMaxWidth().background(colors.elevated).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                ZillitIcons.Calendar,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(10.dp),
            )
            Text(
                str(S.desktop_shoot_day_forecast_upper),
                style = reportText(9.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
                color = colors.textTertiary,
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEachIndexed { index, day ->
                val entry = daily.getOrNull(index)
                val enabled = !shootInWindow || day == shoot
                val active = chosenDay == index || (chosenDay == null && weather.forecastDate == null && index == 0)
                DayButton(
                    label = if (index == 0) str(S.wp_today) else day.dayOfWeek.name.take(3),
                    glyph = weatherGlyph(entry?.let { firstIcon(it) }.orEmpty()),
                    high = entry?.let { tempOf(it, "max") },
                    low = entry?.let { tempOf(it, "min") },
                    date = "${day.dayOfMonth} ${monthShort(day.month.name)}",
                    active = active,
                    enabled = enabled,
                ) { onEvent(DocumentEvent.PickWeatherDay(address.row, address.cell, index)) }
            }
        }
    }
}

private fun firstIcon(day: JsonObject): String =
    ((day["weather"] as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.get("icon")?.let { (it as? JsonPrimitive)?.content }.orEmpty()

private fun tempOf(day: JsonObject, key: String): Int? =
    ((day["temp"] as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull?.let { (it - KELVIN).roundToInt() }

private const val KELVIN = 273.15

/** `SEPTEMBER` → `Sep`. */
private fun monthShort(name: String): String = name.take(MONTH_LETTERS).lowercase().replaceFirstChar { it.uppercase() }

private const val MONTH_LETTERS = 3
private const val SECOND_MILLIS = 1000L
private const val MINUTE_MILLIS = 60_000L

@Composable
private fun DayButton(
    label: String,
    glyph: String,
    high: Int?,
    low: Int?,
    date: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    val fg = if (active) Color.White else colors.textPrimary
    Column(
        Modifier
            .widthIn(min = 58.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.accent else colors.surface)
            .border(
                1.dp,
                when {
                    active -> colors.accent
                    hovered && enabled -> colors.accent.copy(alpha = 0.5f)
                    else -> colors.border
                },
                RoundedCornerShape(8.dp),
            )
            .hoverable(source)
            .plainClick(enabled = enabled, source = source, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label.uppercase(), style = reportText(9.sp, FontWeight.SemiBold, 12.sp), color = fg)
        Text(glyph, style = reportText(16.sp, lineHeight = 22.sp))
        Row {
            Text("${high ?: "--"}°", style = reportText(10.sp, FontWeight.Bold), color = fg)
            Text(
                " ${low ?: "--"}°",
                style = reportText(10.sp, FontWeight.Bold),
                color = if (active) Color.White.copy(alpha = 0.75f) else colors.textMuted,
            )
        }
        Text(
            date,
            style = reportText(8.sp, lineHeight = 11.sp),
            color = if (active) Color.White.copy(alpha = 0.85f) else colors.textTertiary,
        )
    }
}

private fun shortDate(unixSeconds: Long): String {
    val date = kotlinx.datetime.Instant.fromEpochMilliseconds(unixSeconds * SECOND_MILLIS)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    return "${date.dayOfMonth} ${monthShort(date.month.name)}"
}

private fun age(fetchedAt: Long?, nowMillis: Long): String {
    val minutes = fetchedAt?.let { ((nowMillis - it) / MINUTE_MILLIS).coerceAtLeast(0) } ?: return ""
    return if (minutes < 1) str(S.wp_now) else "${minutes}m"
}

