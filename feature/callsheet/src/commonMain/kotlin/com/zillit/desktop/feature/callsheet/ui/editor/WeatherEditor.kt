// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod", "TooManyFunctions")

package com.zillit.desktop.feature.callsheet.ui.editor

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
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.feature.callsheet.domain.SheetTime
import com.zillit.desktop.feature.callsheet.domain.SheetWeather
import com.zillit.desktop.feature.callsheet.domain.WeatherValue
import com.zillit.desktop.feature.callsheet.ui.DocumentEvent
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.WeatherPanel
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetInput
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `WeatherWidget`: the stored forecast as a card with the shoot-day strip, or
 * the empty state that fetches one. A desktop has no browser geolocation or
 * Places box, so a location comes from the map picker (which searches) or
 * typed coordinates; free text stays the fallback.
 */
@Composable
internal fun WeatherEditor(
    raw: String,
    address: CellAddress,
    shootDateMs: Long?,
    panel: WeatherPanel?,
    nowMillis: Long,
    onEvent: (SheetEvent) -> Unit,
) {
    val weather = WeatherValue.parse(raw)
    val live = panel?.takeIf { it.row == address.row && it.cell == address.cell }
    val fetch = { lat: Double, lng: Double, name: String ->
        onEvent(DocumentEvent.FetchWeather(address.row, address.cell, lat, lng, name))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (weather != null) {
            WeatherCard(weather, live, shootDateMs, nowMillis, address, onEvent, fetch)
        } else {
            EmptyWeather(live, fetch)
            Column {
                Text(
                    "OR TYPE MANUALLY",
                    style = sheetText(10.sp, FontWeight.Medium).copy(letterSpacing = 0.5.sp),
                    color = SheetTheme.colors.textMuted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                SheetInput(
                    raw,
                    { onEvent(DocumentEvent.SetWeatherText(address.row, address.cell, it)) },
                    Modifier.fillMaxWidth(),
                    placeholder = "e.g. Sunny, 25°C / 77°F, Humidity: 75%",
                )
            }
        }
        live?.error?.let { WeatherError(it) }
    }
}

@Composable
private fun WeatherError(message: String) {
    val colors = SheetTheme.colors
    Text(
        message,
        style = sheetText(12.sp),
        color = colors.red,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.redBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Opens the map picker and fetches the forecast for the place chosen. */
private fun pickPlace(scope: CoroutineScope, picker: LocationPicker, onPicked: (Double, Double, String) -> Unit) {
    scope.launch {
        picker.pick(null, "Weather location")?.let { place ->
            onPicked(place.lat, place.lng, place.address.ifBlank { place.name })
        }
    }
}

private enum class WeatherMode { Idle, Manual }

@Composable
private fun EmptyWeather(live: WeatherPanel?, fetch: (Double, Double, String) -> Unit) {
    val colors = SheetTheme.colors
    val picker = LocalLocationPicker.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(if (picker == null) WeatherMode.Manual else WeatherMode.Idle) }
    val fetching = live?.fetching == true
    val dash = colors.border
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
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(colors.accentLight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SheetIcons.Cloud, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        }
        Text(
            "Add Weather Data",
            style = sheetText(14.sp, FontWeight.SemiBold),
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            "Fetch real-time weather or 8-day forecast for your shoot location",
            style = sheetText(12.sp, lineHeight = 17.sp),
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 240.dp).padding(bottom = 16.dp),
        )
        Column(Modifier.widthIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (picker != null) {
                SheetButton(
                    if (fetching) "Fetching..." else "Search Location",
                    { pickPlace(scope, picker, fetch) },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Accent,
                    icon = ZillitIcons.Search,
                    enabled = !fetching,
                    height = 40.dp,
                    fontSize = 14.sp,
                )
            }
            when (mode) {
                WeatherMode.Manual -> CoordinatesPanel(
                    fetching = fetching,
                    fetchLabel = "Fetch Weather",
                    onClose = if (picker != null) ({ mode = WeatherMode.Idle }) else null,
                    onFetch = fetch,
                    boxed = true,
                )
                WeatherMode.Idle -> SheetButton(
                    "Enter coordinates manually",
                    { mode = WeatherMode.Manual },
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

/** "Manual Coordinates": latitude and longitude, then Fetch. */
@Composable
private fun CoordinatesPanel(
    fetching: Boolean,
    fetchLabel: String,
    onClose: (() -> Unit)?,
    onFetch: (Double, Double, String) -> Unit,
    boxed: Boolean,
) {
    val colors = SheetTheme.colors
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (boxed) colors.surface else colors.elevated)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MANUAL COORDINATES",
                style = sheetText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (onClose != null) {
                ZillitTooltip("Close") {
                    Icon(
                        ZillitIcons.Close,
                        contentDescription = "Close",
                        tint = colors.textMuted,
                        modifier = Modifier.size(10.dp).plainClick(onClick = onClose),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetInput(
                lat,
                {
                    lat = it
                    invalid = false
                },
                Modifier.weight(1f),
                placeholder = "Latitude",
                textStyle = sheetText(14.sp),
            )
            SheetInput(
                lng,
                {
                    lng = it
                    invalid = false
                },
                Modifier.weight(1f),
                placeholder = "Longitude",
                textStyle = sheetText(14.sp),
            )
        }
        if (invalid) Text("Enter valid lat/long.", style = sheetText(12.sp), color = colors.red)
        SheetButton(
            if (fetching) "Fetching..." else fetchLabel,
            {
                val latitude = lat.trim().toDoubleOrNull()
                val longitude = lng.trim().toDoubleOrNull()
                if (latitude == null || longitude == null) {
                    invalid = true
                } else {
                    onFetch(latitude, longitude, "${latitude.round4()}, ${longitude.round4()}")
                }
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
    shootDateMs: Long?,
    nowMillis: Long,
    address: CellAddress,
    onEvent: (SheetEvent) -> Unit,
    fetch: (Double, Double, String) -> Unit,
) {
    val colors = SheetTheme.colors
    val picker = LocalLocationPicker.current
    val scope = rememberCoroutineScope()
    var manual by remember { mutableStateOf(false) }
    val fetching = live?.fetching == true
    val ink = if (colors.isDark) colors.textPrimary else Color(0xFF1D2939)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (colors.isDark) colors.navy else Color(0xFF1D2939))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(ZillitIcons.Pin, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
            Text(
                weather.location.ifBlank { "Unknown" },
                style = sheetText(12.sp, FontWeight.Medium),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (weather.forecastDate != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.accent)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        ZillitIcons.Calendar,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(9.dp),
                    )
                    Text(
                        weather.forecastLabel(),
                        style = sheetText(9.sp, FontWeight.Medium, 12.sp),
                        color = Color.White,
                    )
                }
            }
            Box(Modifier.weight(1f))
            Text(age(weather.fetchedAt, nowMillis), style = sheetText(9.sp), color = Color.White.copy(alpha = 0.4f))
            RefreshMark(spinning = fetching) { onEvent(DocumentEvent.RefreshWeather(address.row, address.cell)) }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    if (colors.isDark) {
                        Brush.linearGradient(listOf(colors.surface, colors.sunken))
                    } else {
                        Brush.linearGradient(listOf(Color(0xFFF8FAFC), Color(0xFFEEF2F7)))
                    },
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (weather.icon.isNotBlank()) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (colors.isDark) colors.elevated else Color.White)
                            .border(1.dp, colors.borderFaint, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(weatherGlyph(weather.icon), style = sheetText(30.sp, lineHeight = 34.sp))
                    }
                }
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${weather.tempC ?: "--"}°", style = sheetText(30.sp, FontWeight.Bold, 32.sp), color = ink)
                        Text(
                            "C",
                            style = sheetText(18.sp, FontWeight.Bold, 24.sp),
                            color = ink,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    Text(
                        buildString {
                            append("${weather.tempF ?: "--"}°F")
                            if (weather.tempHighC != null) {
                                append("  · H: ${weather.tempHighC}° L: ${weather.tempLowC ?: "--"}°")
                            }
                        },
                        style = sheetText(12.sp),
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        weather.description.ifBlank { weather.condition }.replaceFirstChar { it.uppercase() },
                        style = sheetText(14.sp, FontWeight.Medium),
                        color = colors.textSecondary,
                    )
                }
            }
            val stats = listOf(
                Triple("Feels Like", "${weather.feelsLikeC ?: "--"}", "°C"),
                Triple("Humidity", "${weather.humidity ?: "--"}", "%"),
                Triple("Wind", "${weather.windKmh ?: "--"}", " km/h"),
                Triple("UV Index", weather.uvi?.let(::trimNumber) ?: "--", ""),
                Triple("Pressure", "${weather.pressure ?: "--"}", " hPa"),
                if (weather.visibilityMiles != null) {
                    Triple("Visibility", "${weather.visibilityMiles}", " mi")
                } else {
                    // A zero reads as zero here; the web's `||` printed "--" for a 0° high or low.
                    Triple("High/Low", "${weather.tempHighC ?: "--"}/${weather.tempLowC ?: "--"}", "°C")
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stats.chunked(STATS_PER_ROW).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        line.forEach { (label, value, unit) -> StatTile(label, value, unit, Modifier.weight(1f)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SunTile("🌅", "Sunrise", weather.clock(weather.sunrise), Modifier.weight(1f))
                SunTile("🌇", "Sunset", weather.clock(weather.sunset), Modifier.weight(1f))
            }
        }
        live?.response?.let { response -> ForecastStrip(
            response,
            live.selectedDay,
            shootDateMs,
            nowMillis,
            address,
            onEvent,
        ) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetButton(
            "Change Location",
            { if (picker != null) pickPlace(scope, picker, fetch) else manual = !manual },
            Modifier.weight(1f),
            kind = ButtonKind.Navy,
            icon = SheetIcons.Swap,
            enabled = !fetching,
            height = 34.dp,
            fontSize = 12.sp,
        )
        SheetButton(
            "Clear",
            { onEvent(DocumentEvent.ClearWeather(address.row, address.cell)) },
            kind = ButtonKind.DangerOutline,
            icon = ZillitIcons.Trash,
            height = 34.dp,
            fontSize = 12.sp,
        )
    }
    if (picker != null) {
        Text(
            "Enter coordinates instead",
            style = sheetText(11.sp),
            color = colors.textSecondary,
            modifier = Modifier.plainClick { manual = !manual },
        )
    }
    if (manual) {
        CoordinatesPanel(
            fetching = fetching,
            fetchLabel = "Fetch",
            onClose = { manual = false },
            onFetch = { lat, lng, name ->
                manual = false
                fetch(lat, lng, name)
            },
            boxed = false,
        )
    }
}

private const val STATS_PER_ROW = 3

private fun trimNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (colors.isDark) colors.elevated else Color.White)
            .border(1.dp, if (hovered) colors.border else colors.borderFaint, RoundedCornerShape(8.dp))
            .hoverable(source)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label.uppercase(),
            style = sheetText(8.sp, FontWeight.SemiBold, 11.sp).copy(letterSpacing = 0.4.sp),
            color = colors.textMuted,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = sheetText(13.sp, FontWeight.Bold, 16.sp), color = colors.textPrimary, maxLines = 1)
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    style = sheetText(10.sp, lineHeight = 14.sp),
                    color = colors.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SunTile(glyph: String, label: String, time: String, modifier: Modifier) {
    val colors = SheetTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (colors.isDark) colors.elevated else Color.White)
            .border(1.dp, colors.borderFaint, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(glyph, style = sheetText(18.sp, lineHeight = 22.sp))
        Column {
            Text(
                label.uppercase(),
                style = sheetText(8.sp, FontWeight.SemiBold, 11.sp).copy(letterSpacing = 0.4.sp),
                color = colors.textMuted,
            )
            Text(time, style = sheetText(14.sp, FontWeight.Bold), color = colors.textPrimary)
        }
    }
}

@Composable
private fun RefreshMark(spinning: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val turn = rememberInfiniteTransition()
    val angle by turn.animateFloat(
        0f,
        FULL_TURN,
        infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
    )
    ZillitTooltip("Refresh") {
        Icon(
            ZillitIcons.Reload,
            contentDescription = "Refresh",
            tint = Color.White.copy(alpha = if (hovered && !spinning) 1f else 0.6f),
            modifier = Modifier
                .size(14.dp)
                .rotate(if (spinning) angle else 0f)
                .hoverable(source)
                .plainClick(enabled = !spinning, source = source, onClick = onClick),
        )
    }
}

private const val FULL_TURN = 360f
private const val SPIN_MS = 900

/**
 * "Shoot day forecast": the eight days of the forecast in memory. When the
 * window reaches the shoot date only that day can be picked; otherwise any.
 */
@Composable
private fun ForecastStrip(
    response: JsonObject,
    selectedDay: Int?,
    shootDateMs: Long?,
    nowMillis: Long,
    address: CellAddress,
    onEvent: (SheetEvent) -> Unit,
) {
    val colors = SheetTheme.colors
    val days = SheetWeather.forecastDays(response)
    if (days.isEmpty()) return
    val daily = (response["daily"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val shootIndex = SheetWeather.shootDayIndex(response, shootDateMs)
    val today = SheetTime.localDate(nowMillis)
    Column(
        Modifier
            .fillMaxWidth()
            .rules(colors.border, top = true)
            .background(colors.elevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                ZillitIcons.Calendar,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(10.dp),
            )
            Text(
                "SHOOT DAY FORECAST",
                style = sheetText(9.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
                color = colors.textTertiary,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            days.forEachIndexed { index, day ->
                val entry = daily.getOrNull(index)
                DayButton(
                    label = if (day == today) "Today" else day.dayOfWeek.name.take(3),
                    glyph = weatherGlyph(entry?.let { firstIcon(it) }.orEmpty()),
                    high = entry?.let { tempOf(it, "max") },
                    low = entry?.let { tempOf(it, "min") },
                    date = "${day.day} ${WeatherValue.SHORT_MONTHS[day.month.ordinal]}",
                    active = selectedDay == index,
                    enabled = shootIndex == null || shootIndex == index,
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
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    val soft = if (active) Color.White.copy(alpha = 0.7f) else colors.textMuted
    Column(
        Modifier
            .widthIn(min = 58.dp)
            .alpha(if (enabled || active) 1f else 0.5f)
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
        Text(
            label.uppercase(),
            style = sheetText(9.sp, FontWeight.SemiBold, 12.sp),
            color = if (active) Color.White.copy(alpha = 0.8f) else colors.textMuted,
        )
        Text(glyph, style = sheetText(16.sp, lineHeight = 24.sp))
        Row {
            Text(
                "${high ?: "--"}° ",
                style = sheetText(10.sp, FontWeight.Bold, 13.sp),
                color = if (active) Color.White else colors.textSecondary,
            )
            Text("${low ?: "--"}°", style = sheetText(10.sp, lineHeight = 13.sp), color = soft)
        }
        Text(date, style = sheetText(8.sp, lineHeight = 11.sp), color = soft)
    }
}

/** `Now` within the first minute, then whole minutes — computed when drawn, as on the web. */
private fun age(fetchedAt: Long?, nowMillis: Long): String {
    val minutes = fetchedAt?.let { ((nowMillis - it) / MINUTE_MILLIS).coerceAtLeast(0) } ?: return ""
    return if (minutes < 1) "Now" else "${minutes}m"
}
