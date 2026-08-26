package com.zillit.desktop.feature.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.weather.domain.CurrentWeather
import com.zillit.desktop.feature.weather.domain.DailyPoint
import com.zillit.desktop.feature.weather.domain.HourlyPoint
import kotlin.math.roundToInt

/**
 * The Weather tool: what it is doing where the unit is shooting.
 *
 * One place at a time — the big reading, the next hours, then the week — which
 * is the shape both other clients use and the shape a call sheet reads from.
 */
@Composable
fun WeatherScreen(
    state: WeatherUiState,
    onEvent: (WeatherEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the map picker; null hides the button (render tests, no picker wired). */
    onPickPlace: (() -> Unit)? = null,
    /** Formats an instant in the place's own day — the host owns the clock. */
    formatTime: (Long) -> String = { "" },
    formatDay: (Long) -> String = { "" },
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.hasNoAccess -> ZillitEmptyState(
                title = "No weather access",
                message = "This tool is not shared with you on this production.",
                icon = ZillitIcons.Shield,
                modifier = Modifier.align(Alignment.Center),
            )

            !state.configured -> ZillitEmptyState(
                title = "Weather is not configured",
                message = "No weather key is set for this environment, so there is nothing to show.",
                icon = ZillitIcons.Warning,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Column(Modifier.fillMaxSize()) {
                Header(state, onEvent, onPickPlace)
                ZillitDivider()
                Body(state, onEvent, onPickPlace, formatTime, formatDay)
            }
        }
    }
}

@Composable
private fun Header(state: WeatherUiState, onEvent: (WeatherEvent) -> Unit, onPickPlace: (() -> Unit)?) {
    ZillitPageHeader(
        title = "Weather",
        eyebrow = "Film tools",
        description = state.place?.name?.takeIf { it.isNotBlank() }
            ?: "Pick where the unit is and the forecast follows.",
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        actions = {
            if (onPickPlace != null) {
                ZillitButton(
                    text = if (state.place == null) "Choose place" else "Change place",
                    onClick = onPickPlace,
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Pin,
                )
            }
            if (state.place != null) {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(WeatherEvent.Refresh) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Reload,
                    loading = state.loading,
                )
            }
        },
    )
}

@Composable
private fun Body(
    state: WeatherUiState,
    onEvent: (WeatherEvent) -> Unit,
    onPickPlace: (() -> Unit)?,
    formatTime: (Long) -> String,
    formatDay: (Long) -> String,
) {
    val report = state.report
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        state.error?.let { message ->
            ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = message,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.danger,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitButton(
                        text = "Dismiss",
                        onClick = { onEvent(WeatherEvent.DismissError) },
                        variant = ButtonVariant.Tertiary,
                    )
                }
            }
        }
        when {
            state.place == null -> ZillitEmptyState(
                title = "No place chosen",
                message = "Choose where the unit is and the forecast appears here.",
                icon = ZillitIcons.Pin,
                action = onPickPlace?.let { pick -> { ZillitButton(text = "Choose place", onClick = pick) } },
            )

            report == null && state.loading -> ZillitText(
                text = "Loading…",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )

            report != null -> {
                Now(report.current, state.place.name, formatTime)
                if (report.hourly.isNotEmpty()) Hours(report.hourly, formatTime)
                if (report.daily.isNotEmpty()) Days(report.daily, formatDay)
            }

            else -> ZillitEmptyState(
                title = "No forecast",
                message = "Nothing came back for this place.",
                icon = ZillitIcons.Warning,
            )
        }
    }
}

/** The big reading, and the numbers a unit actually plans around. */
@Composable
private fun Now(current: CurrentWeather, placeName: String, formatTime: (Long) -> String) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = placeName.ifBlank { "Now" }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitText(
                text = current.condition.glyph(),
                style = ZillitTheme.typography.displayLarge,
                color = ZillitTheme.colors.textPrimary,
            )
            Column {
                ZillitText(
                    text = current.temperatureC.asDegrees(),
                    style = ZillitTheme.typography.displayLarge,
                    color = ZillitTheme.colors.textPrimary,
                )
                ZillitText(
                    text = current.condition.summary.asSummary(),
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitText(
                    text = "Feels like ${current.feelsLikeC.asDegrees()}",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        Readings(current, formatTime)
    }
}

/** The numbers a unit plans around — wind, light, and when the light goes. */
@Composable
private fun ColumnScope.Readings(current: CurrentWeather, formatTime: (Long) -> String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitStatTile(
            label = "Wind",
            value = "${current.windKph.roundToInt()} km/h",
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Humidity",
            value = "${current.humidityPercent}%",
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "UV",
            value = current.uvIndex.roundToInt().toString(),
            sub = current.uvIndex.uvBand(),
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitStatTile(
            label = "Pressure",
            value = "${current.pressureHpa} hPa",
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Sunrise",
            value = formatTime(current.sunriseMillis),
            modifier = Modifier.weight(1f),
        )
        // The one a first AD asks for by name.
        ZillitStatTile(
            label = "Sunset",
            value = formatTime(current.sunsetMillis),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Hours(hours: List<HourlyPoint>, formatTime: (Long) -> String) {
    ZillitSectionLabel(text = "Next hours", modifier = Modifier.fillMaxWidth())
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        contentPadding = PaddingValues(vertical = ZillitTheme.spacing.xxs),
    ) {
        items(hours.size) { index ->
            val hour = hours[index]
            ZillitSectionCard(modifier = Modifier.width(HOUR_TILE)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    ZillitText(
                        text = formatTime(hour.atMillis),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitText(
                        text = hour.condition.glyph(),
                        style = ZillitTheme.typography.titleMedium,
                        color = ZillitTheme.colors.textPrimary,
                    )
                    ZillitText(
                        text = hour.temperatureC.asDegrees(),
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Days(days: List<DailyPoint>, formatDay: (Long) -> String) {
    ZillitSectionLabel(text = "The week ahead", modifier = Modifier.fillMaxWidth())
    days.forEach { day ->
        ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitText(
                    text = formatDay(day.atMillis),
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    modifier = Modifier.width(DAY_LABEL),
                )
                ZillitText(
                    text = day.condition.glyph(),
                    style = ZillitTheme.typography.bodyLarge,
                    color = ZillitTheme.colors.textPrimary,
                )
                ZillitText(
                    text = day.condition.summary.asSummary(),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = "${day.windKph.roundToInt()} km/h",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitText(
                    text = "${day.minC.asDegrees()} / ${day.maxC.asDegrees()}",
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(RANGE_LABEL),
                )
            }
        }
    }
}

private val HOUR_TILE = 84.dp
private val DAY_LABEL = 96.dp
private val RANGE_LABEL = 96.dp
