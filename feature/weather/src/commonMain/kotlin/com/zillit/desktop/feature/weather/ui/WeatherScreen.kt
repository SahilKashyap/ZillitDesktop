package com.zillit.desktop.feature.weather.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.weather.domain.CurrentWeather
import com.zillit.desktop.feature.weather.domain.DailyPoint
import com.zillit.desktop.feature.weather.domain.HourlyPoint
import com.zillit.desktop.feature.weather.domain.PlaceSuggestion
import com.zillit.desktop.feature.weather.domain.WeatherCondition
import com.zillit.desktop.feature.weather.domain.WeatherReport
import kotlin.math.roundToInt

/**
 * The Weather tool — the web's `WeatherMain` page.
 *
 * Top bar with the city search; then the reading for right now on the left
 * (a third of the width, as the web's `.current-weather` is) and the next
 * hours over the week ahead on the right.
 */
@Composable
fun WeatherScreen(
    state: WeatherUiState,
    onEvent: (WeatherEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the map picker; null hides the button (render tests, no picker wired). */
    onPickPlace: (() -> Unit)? = null,
    clock: WeatherClock = WeatherClock(),
) {
    val copy = rememberWeatherCopy()
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.hasNoAccess -> ZillitEmptyState(
                title = str(S.desktop_weather_no_access_title),
                message = str(S.desktop_tool_not_shared_with_you),
                icon = ZillitIcons.Shield,
                modifier = Modifier.align(Alignment.Center),
            )

            !state.configured -> ZillitEmptyState(
                title = str(S.desktop_weather_not_configured_title),
                message = str(S.desktop_weather_not_configured_message),
                icon = ZillitIcons.Warning,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Column(Modifier.fillMaxSize()) {
                TopBar(state, copy, onEvent, onPickPlace)
                Body(state, copy, onEvent, onPickPlace, clock)
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

/** `.weather-main__top`: accent stripe and title on the left, the controls on the right. */
@Composable
private fun TopBar(
    state: WeatherUiState,
    copy: WeatherCopy,
    onEvent: (WeatherEvent) -> Unit,
    onPickPlace: (() -> Unit)?,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = PAGE_GUTTER, vertical = TOP_BAR_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        AccentStripe(height = TITLE_STRIPE)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = copy.title,
                style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 1,
            )
            state.place?.name?.takeIf { it.isNotBlank() }?.let { name ->
                ZillitText(
                    text = name,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TopBarControls(state, copy, onEvent, onPickPlace)
    }
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(colors.border))
}

/** The right cluster: search, "here", the map, refresh. */
@Composable
private fun TopBarControls(
    state: WeatherUiState,
    copy: WeatherCopy,
    onEvent: (WeatherEvent) -> Unit,
    onPickPlace: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (state.canSearch) CitySearch(state.search, copy, onEvent)
        if (state.canSearch) {
            ZillitTooltip(str(S.map_picker_my_location)) {
                ZillitIconButton(
                    icon = ZillitIcons.Pin,
                    contentDescription = str(S.map_picker_my_location),
                    onClick = { onEvent(WeatherEvent.UseMyLocation) },
                    enabled = !state.locating,
                    size = CONTROL,
                )
            }
        }
        if (onPickPlace != null) {
            ZillitTooltip(str(S.av_pick_on_map)) {
                ZillitIconButton(
                    icon = ZillitIcons.Globe,
                    contentDescription = str(S.av_pick_on_map),
                    onClick = onPickPlace,
                    size = CONTROL,
                )
            }
        }
        if (state.place != null) {
            ZillitTooltip(str(S.refresh_text)) {
                Box(Modifier.size(CONTROL), contentAlignment = Alignment.Center) {
                    if (state.loading) {
                        ZillitSpinner(size = SPINNER_SMALL)
                    } else {
                        ZillitIconButton(
                            icon = ZillitIcons.Reload,
                            contentDescription = str(S.refresh_text),
                            onClick = { onEvent(WeatherEvent.Refresh) },
                            size = CONTROL,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The web's `Header.jsx`: a Places search box, its suggestions hanging under
 * it. Return picks the first row; Escape closes the list and keeps the text.
 */
@Composable
private fun CitySearch(search: PlaceSearch, copy: WeatherCopy, onEvent: (WeatherEvent) -> Unit) {
    Box {
        ZillitSearchField(
            value = search.query,
            onValueChange = { onEvent(WeatherEvent.SearchChanged(it)) },
            placeholder = copy.searchCity,
            enabled = search.resolving == null,
            modifier = Modifier
                .width(SEARCH_WIDTH)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            search.suggestions.firstOrNull()?.let { onEvent(WeatherEvent.SuggestionPicked(it)) }
                            true
                        }

                        Key.Escape -> {
                            onEvent(WeatherEvent.SearchDismissed)
                            true
                        }

                        else -> false
                    }
                },
        )
        if (search.open) SuggestionMenu(search, onEvent)
    }
}

/**
 * A `Popup` under the field rather than a `DropdownMenu`: the menu centres on
 * the window in a detached tool window, and a lazy list inside it measures
 * against an unbounded height. Plain rows, fixed width.
 */
@Composable
private fun SuggestionMenu(search: PlaceSearch, onEvent: (WeatherEvent) -> Unit) {
    val colors = ZillitTheme.colors
    // Pixels, not dp: a popup offset is raw, so the drop is scaled by hand.
    val drop = with(LocalDensity.current) { MENU_DROP.roundToPx() }
    Popup(
        offset = IntOffset(0, drop),
        onDismissRequest = { onEvent(WeatherEvent.SearchDismissed) },
    ) {
        Column(
            modifier = Modifier
                .width(SEARCH_WIDTH)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceRaised)
                .border(HAIRLINE, colors.border, ZillitTheme.shapes.large)
                .padding(vertical = ZillitTheme.spacing.xs),
        ) {
            if (search.searching && search.suggestions.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(MENU_WAIT_HEIGHT), contentAlignment = Alignment.Center) {
                    ZillitSpinner(size = SPINNER_SMALL)
                }
            }
            search.suggestions.take(MAX_SUGGESTIONS).forEach { suggestion ->
                SuggestionRow(suggestion) { onEvent(WeatherEvent.SuggestionPicked(suggestion)) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: PlaceSuggestion, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .background(if (hovered) colors.surfaceHover else colors.surfaceRaised)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Pin, tint = colors.textMuted, size = ICON_SMALL)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = suggestion.primary,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (suggestion.secondary.isNotBlank()) {
                ZillitText(
                    text = suggestion.secondary,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Body ──────────────────────────────────────────────────────────────────────

@Composable
private fun Body(
    state: WeatherUiState,
    copy: WeatherCopy,
    onEvent: (WeatherEvent) -> Unit,
    onPickPlace: (() -> Unit)?,
    clock: WeatherClock,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER, vertical = ZillitTheme.spacing.lg)) {
        ErrorBanner(state.error, onEvent)
        val report = state.report
        when {
            report != null -> Crossfade(
                targetState = report,
                animationSpec = tween(FADE_MS),
                label = "weatherReport",
            ) { shown -> Forecast(shown, copy, clock, state.busy) }

            state.busy -> Waiting(
                if (state.locating) str(S.desktop_weather_finding_where_you_are) else "${copy.loading}…",
            )

            state.place == null -> ZillitEmptyState(
                title = str(S.desktop_weather_no_place_chosen),
                message = str(S.desktop_weather_no_place_chosen_message),
                icon = ZillitIcons.Pin,
                modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.xxl),
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        if (state.canSearch) {
                            ZillitButton(
                                text = str(S.map_picker_my_location),
                                onClick = { onEvent(WeatherEvent.UseMyLocation) },
                                leadingIcon = ZillitIcons.Pin,
                            )
                        }
                        if (onPickPlace != null) {
                            ZillitButton(
                                text = str(S.av_pick_on_map),
                                onClick = onPickPlace,
                                variant = ButtonVariant.Secondary,
                                leadingIcon = ZillitIcons.Globe,
                            )
                        }
                    }
                },
            )

            else -> ZillitEmptyState(
                title = str(S.desktop_weather_no_forecast),
                message = str(S.desktop_weather_no_forecast_message),
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.xxl),
                action = {
                    ZillitButton(
                        text = str(S.docusign_token_gateway_retry),
                        onClick = { onEvent(WeatherEvent.Refresh) },
                    )
                },
            )
        }
    }
}

/** A sentence that can be dismissed, sliding in above the content rather than replacing it. */
@Composable
private fun ErrorBanner(message: String?, onEvent: (WeatherEvent) -> Unit) {
    val colors = ZillitTheme.colors
    // The last sentence stays for the exit animation; a null would blank it mid-slide.
    var shown by remember { mutableStateOf(message.orEmpty()) }
    if (message != null) shown = message
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(FADE_MS)) + slideInVertically(tween(FADE_MS)) { -it / 2 },
        exit = fadeOut(tween(FADE_MS)) + slideOutVertically(tween(FADE_MS)) { -it / 2 },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = ZillitTheme.spacing.lg)
                .clip(ZillitTheme.shapes.large)
                .background(colors.dangerSoft)
                .border(HAIRLINE, colors.danger.copy(alpha = BANNER_BORDER_ALPHA), ZillitTheme.shapes.large)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.Warning, tint = colors.danger, size = ICON_SMALL)
            ZillitText(
                text = shown,
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.sync_action_dismiss),
                onClick = { onEvent(WeatherEvent.DismissError) },
                variant = ButtonVariant.Tertiary,
            )
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ZillitSpinner(size = SPINNER_LARGE)
        Spacer(Modifier.height(ZillitTheme.spacing.md))
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** `.weather-main__body`: the reading now on the left, the hours and the week on the right. */
@Composable
private fun Forecast(report: WeatherReport, copy: WeatherCopy, clock: WeatherClock, busy: Boolean) {
    Row(
        modifier = Modifier.fillMaxSize().alpha(if (busy) REFRESHING_ALPHA else 1f),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        CurrentPanel(report, copy, clock, Modifier.fillMaxWidth(CURRENT_PANEL_FRACTION).fillMaxHeight())
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            if (report.hourly.isNotEmpty()) HourlySection(report.hourly.take(HOURS_SHOWN), report.timezone, copy, clock)
            if (report.daily.isNotEmpty()) {
                DailySection(report.daily, report.timezone, copy, clock, Modifier.weight(1f, fill = false))
            }
        }
    }
}

// ── Current conditions ────────────────────────────────────────────────────────

/** `CurrentWeather.jsx`: city, condition, the big number, six readings, and the light. */
@Composable
private fun CurrentPanel(report: WeatherReport, copy: WeatherCopy, clock: WeatherClock, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val current = report.current
    Card(modifier) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            PlaceHeading(report)

            // The big number — `.current-weather__temp`.
            Row(verticalAlignment = Alignment.Top) {
                ZillitText(
                    text = current.temperatureC.asDegrees(),
                    style = ZillitTheme.typography.displayLarge.copy(
                        fontSize = TEMP_SIZE,
                        lineHeight = TEMP_LINE,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = TEMP_TRACKING,
                    ),
                    color = colors.accent,
                )
                ZillitText(
                    text = "C",
                    style = ZillitTheme.typography.titleLarge.copy(
                        fontSize = TEMP_UNIT_SIZE,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.accent,
                    modifier = Modifier.padding(start = ZillitTheme.spacing.xs, top = ZillitTheme.spacing.sm),
                )
            }

            Readings(current, copy)

            SunTile(
                emoji = "🌅",
                label = copy.sunrise,
                time = clock.time(current.sunriseMillis, report.timezone),
            )
            SunTile(
                emoji = "🌇",
                label = copy.sunset,
                time = clock.time(current.sunsetMillis, report.timezone),
            )
        }
    }
}

/** `.current-weather__head`: the city on the left, the condition on the right. */
@Composable
private fun PlaceHeading(report: WeatherReport) {
    val colors = ZillitTheme.colors
    val current = report.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Pin, tint = colors.textMuted, size = ICON_MEDIUM)
        ZillitText(
            text = report.place.name.ifBlank { "—" },
            style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ConditionIcon(current.condition, ICON_CURRENT)
            ZillitText(
                text = current.condition.summary.asSummary(),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/** `.current-weather__stats`: two columns of the six readings, in the web's order. */
@Composable
private fun ColumnScope.Readings(current: CurrentWeather, copy: WeatherCopy) {
    val tiles = listOf(
        copy.uv to "${current.uvIndex.roundToInt()} · ${current.uvIndex.uvBand()}",
        copy.feelsLike to "${current.feelsLikeC.asDegrees()}C",
        copy.humidity to "${current.humidityPercent}%",
        copy.wind to "${current.windKph.roundToInt()} km/h",
        copy.pressure to "${current.pressureHpa} hPa",
        copy.visibility to "${current.visibilityMetres.metresAsMiles()} miles",
    )
    tiles.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            pair.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(TILE_SHAPE)
            .background(colors.surfaceSunken)
            .border(HAIRLINE, colors.border, TILE_SHAPE)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = LABEL_TRACKING,
            ),
            color = colors.accent,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** `.current-weather__sun`: the picture, then the label over the time. */
@Composable
private fun SunTile(emoji: String, label: String, time: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TILE_SHAPE)
            .background(colors.surfaceSunken)
            .border(HAIRLINE, colors.border, TILE_SHAPE)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(text = emoji, style = ZillitTheme.typography.displayLarge.copy(fontSize = SUN_EMOJI))
        Column {
            ZillitText(
                text = label.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = LABEL_TRACKING,
                ),
                color = colors.textMuted,
            )
            ZillitText(
                text = time.ifBlank { "—" },
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.accent,
            )
        }
    }
}

// ── Hourly ────────────────────────────────────────────────────────────────────

/** `HourlyForecast.jsx`: six even slots, time over picture over temperature. */
@Composable
private fun HourlySection(hours: List<HourlyPoint>, zone: String, copy: WeatherCopy, clock: WeatherClock) {
    Section(copy.hourly) {
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(CARD_INSET),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                hours.forEach { hour -> HourPill(hour, clock.time(hour.atMillis, zone), Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HourPill(hour: HourlyPoint, time: String, modifier: Modifier) {
    val colors = ZillitTheme.colors
    Lifting(modifier) { hovered ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PILL_SHAPE)
                .background(colors.surfaceSunken)
                .border(
                    width = HAIRLINE,
                    color = if (hovered) colors.accent.copy(alpha = HOVER_BORDER_ALPHA) else colors.border,
                    shape = PILL_SHAPE,
                )
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = time.ifBlank { "—" },
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 1,
            )
            ConditionIcon(hour.condition, ICON_HOUR)
            ZillitText(
                text = "${hour.temperatureC.asDegrees()}c",
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

// ── Daily ─────────────────────────────────────────────────────────────────────

/** `7DaysForecast.jsx`: a row a day — day, picture, condition, high over low. */
@Composable
private fun DailySection(
    days: List<DailyPoint>,
    zone: String,
    copy: WeatherCopy,
    clock: WeatherClock,
    modifier: Modifier,
) {
    Section(copy.sevenDay, modifier) {
        Card(Modifier.fillMaxWidth()) {
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = CARD_INSET, vertical = ZillitTheme.spacing.xs),
            ) {
                days.forEachIndexed { index, day ->
                    DayRow(day, clock.day(day.atMillis, zone), last = index == days.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun DayRow(day: DailyPoint, label: String, last: Boolean) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interaction)
                .clip(ZillitTheme.shapes.medium)
                .background(if (hovered) colors.surfaceHover else colors.surface)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitText(
                text = label.ifBlank { "—" },
                style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.width(DAY_LABEL),
            )
            ConditionIcon(day.condition, ICON_DAY)
            ZillitText(
                text = day.condition.summary.asSummary(),
                style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                ZillitText(
                    text = day.maxC.asDegrees(),
                    style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
                ZillitText(
                    text = " / ",
                    style = ZillitTheme.typography.bodyLarge,
                    color = colors.textMuted,
                )
                ZillitText(
                    text = day.minC.asDegrees(),
                    style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMuted,
                )
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(HAIRLINE).background(colors.divider))
    }
}

// ── Pieces ────────────────────────────────────────────────────────────────────

/** `h2.text-2xl`: an uppercase tracker with an accent stripe, over its content. */
@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            AccentStripe(height = SECTION_STRIPE)
            ZillitText(
                text = title.uppercase(),
                style = ZillitTheme.typography.label.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = SECTION_TRACKING,
                ),
                color = ZillitTheme.colors.textMuted,
            )
        }
        content()
    }
}

@Composable
private fun AccentStripe(height: Dp) {
    Box(
        Modifier
            .size(width = STRIPE_WIDTH, height = height)
            .clip(ZillitTheme.shapes.small)
            .background(ZillitTheme.colors.accent),
    )
}

/** `.bg-slate-100` / `.bg-slate-200`: a soft surface with a hairline, rounder than the app's usual card. */
@Composable
private fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(CARD_SHAPE)
            .background(colors.surface)
            .border(HAIRLINE, colors.border, CARD_SHAPE),
        content = content,
    )
}

/** The web's `:hover { transform: translateY(-2px) }`, animated. Composed always; only the offset moves. */
@Composable
private fun Lifting(modifier: Modifier, content: @Composable (hovered: Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateDpAsState(if (hovered) HOVER_LIFT else 0.dp, tween(LIFT_MS), label = "lift")
    Box(
        modifier = modifier
            .hoverable(interaction)
            .graphicsLayer { translationY = -lift.toPx() },
    ) {
        content(hovered)
    }
}

/**
 * OpenWeather's picture for a condition, once it arrives; a glyph until then
 * and instead when it never does. The fade covers the swap.
 */
@Composable
private fun ConditionIcon(condition: WeatherCondition, size: Dp) {
    val loader = LocalWeatherIconLoader.current
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = condition.icon, key2 = loader) {
        value = loader?.load(condition.icon)
    }
    val shown by animateFloatAsState(if (image != null) 1f else 0f, tween(FADE_MS), label = "iconFade")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = condition.summary,
                modifier = Modifier.fillMaxSize().alpha(shown),
            )
        } else {
            ZillitText(
                text = condition.glyph(),
                style = ZillitTheme.typography.displayLarge.copy(
                    fontSize = (size.value * GLYPH_RATIO).sp,
                    lineHeight = size.value.sp,
                ),
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/** Six slots, as the web's grid has (`HourlyForecast.jsx:28`). */
private const val HOURS_SHOWN = 6
private const val MAX_SUGGESTIONS = 6
private const val FADE_MS = 220
private const val LIFT_MS = 150
private const val REFRESHING_ALPHA = 0.6f
private const val HOVER_BORDER_ALPHA = 0.5f
private const val BANNER_BORDER_ALPHA = 0.3f
private const val GLYPH_RATIO = 0.62f
private const val CURRENT_PANEL_FRACTION = 0.32f

private val PAGE_GUTTER = 24.dp
private val TOP_BAR_PADDING = 14.dp
private val SEARCH_WIDTH = 280.dp
private val CONTROL = 36.dp
private val MENU_DROP = 40.dp
private val HAIRLINE = 1.dp
private val STRIPE_WIDTH = 4.dp
private val TITLE_STRIPE = 20.dp
private val SECTION_STRIPE = 16.dp
private val CARD_PADDING = 20.dp
private val CARD_INSET = 14.dp
private val CARD_SHAPE = RoundedCornerShape(16.dp)
private val TILE_SHAPE = RoundedCornerShape(10.dp)
private val PILL_SHAPE = RoundedCornerShape(16.dp)
private val ICON_SMALL = 16.dp
private val ICON_MEDIUM = 20.dp
private val ICON_CURRENT = 56.dp
private val ICON_HOUR = 52.dp
private val ICON_DAY = 42.dp
private val DAY_LABEL = 64.dp
private val HOVER_LIFT = 2.dp
private val SPINNER_SMALL = 16.dp
private val SPINNER_LARGE = 28.dp
private val MENU_WAIT_HEIGHT = 48.dp
private val SUN_EMOJI = 26.sp
private val TEMP_SIZE = 64.sp
private val TEMP_LINE = 68.sp
private val TEMP_UNIT_SIZE = 22.sp
private val TEMP_TRACKING = (-1).sp
private val LABEL_TRACKING = 0.4.sp
private val SECTION_TRACKING = 1.4.sp
