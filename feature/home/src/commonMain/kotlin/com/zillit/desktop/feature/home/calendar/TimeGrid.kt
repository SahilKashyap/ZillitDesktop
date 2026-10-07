package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.component.ZillitText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * A day, or a week of days, laid out against the clock.
 *
 * ## Why this is not a list
 *
 * The web uses FullCalendar's `timeGrid` for its Week and Day views, and the
 * reason is worth restating: a shooting day is about *when* things are. A list
 * says a unit call and a production meeting both exist; a grid says they
 * overlap by twenty minutes, which is the thing someone actually needs to see.
 *
 * Positions come from [layOutDay], which is where the overlap rule lives and
 * where it is tested.
 */
@Composable
internal fun TimeGrid(
    state: CalendarUiState,
    days: List<LocalDate>,
    onSelectDay: (LocalDate) -> Unit,
    onOpenEvent: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
    onMoveEvent: (CalendarEvent, Int, Int) -> Unit = { _, _, _ -> },
    onResizeEvent: (CalendarEvent, Int) -> Unit = { _, _ -> },
) {
    val colors = ZillitTheme.colors
    // The day whose block is mid-drag rides above its neighbours; without
    // the lift a cross-column drag slides UNDER every later-drawn column.
    var draggingDate by remember { mutableStateOf<LocalDate?>(null) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // Open on the working day, not on an empty midnight: the earlier of the
    // first event's hour and 7 am, an hour's margin above it.
    val firstHour = remember(days, state.events) {
        val earliest = days.flatMap { state.eventsOn(it) }
            .filterNot { it.isAllDay }
            .minOfOrNull { kotlin.time.Instant.fromEpochMilliseconds(it.startMillis).toLocalDateTime(state.zone).hour }
        minOf(earliest ?: DEFAULT_FIRST_HOUR, DEFAULT_FIRST_HOUR)
    }
    LaunchedEffect(days.firstOrNull()) {
        // The scroll range is only known once the grid has been measured.
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        // A little above the hour line, so its label is not cut in half.
        val target = with(density) {
            (HOUR_HEIGHT * (firstHour - 1).coerceAtLeast(0) - SCROLL_HEADROOM).roundToPx().coerceAtLeast(0)
        }
        scroll.scrollTo(target.coerceAtMost(scroll.maxValue))
    }

    Column(modifier.fillMaxSize()) {
        // All-day events have no position on a time axis, so they sit above the
        // grid rather than being forced into midnight.
        AllDayStrip(state, days, onOpenEvent)

        Row(Modifier.fillMaxSize().background(colors.surface).zillitVerticalScroll(scroll)) {
            HourGutter()

            days.forEach { date ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(GRID_HEIGHT)
                        .zIndex(if (date == draggingDate) 1f else 0f)
                        .background(
                            if (date == state.today) colors.accent.copy(alpha = TODAY_COLUMN_ALPHA) else colors.surface,
                        )
                        .clickable { onSelectDay(date) },
                ) {
                    HourLines()
                    DayEvents(
                        state, date, onOpenEvent, onMoveEvent, onResizeEvent,
                        onDragging = { active -> draggingDate = if (active) date else null },
                    )
                    if (date == state.today) {
                        CurrentTimeLine(state.zone)
                    }
                }
            }
        }
    }
}

/** The hour labels down the left. */
@Composable
private fun HourGutter() {
    Column(Modifier.width(TIME_GUTTER_WIDTH).height(GRID_HEIGHT)) {
        for (hour in 0 until HOURS_PER_DAY) {
            Box(Modifier.fillMaxWidth().height(HOUR_HEIGHT)) {
                ZillitText(
                    // Skipped at midnight: a label at the very top has no line
                    // to sit against and reads as belonging to the row above.
                    text = if (hour == 0) "" else hour.hourLabel(),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = ZillitTheme.spacing.xs)
                        .offset(y = LABEL_LIFT),
                )
            }
        }
    }
}

@Composable
private fun CurrentTimeLine(zone: TimeZone) {
    val now = remember {
        kotlin.time.Clock.System.now().toLocalDateTime(zone)
    }
    val minutesSinceMidnight = now.hour * 60 + now.minute
    val fractionOfDay = minutesSinceMidnight.toFloat() / (24 * 60)
    val red = ZillitTheme.colors.danger

    // Drawn, not boxed: the dot used to be a 10dp box inside the 2dp line,
    // and was clipped to a dash.
    Box(
        Modifier.fillMaxSize().drawBehind {
            val y = size.height * fractionOfDay
            drawLine(red, Offset(0f, y), Offset(size.width, y), NOW_LINE.toPx())
            drawCircle(red, radius = NOW_DOT_RADIUS.toPx(), center = Offset(NOW_DOT_RADIUS.toPx(), y))
        },
    )
}

/**
 * One hairline per hour and a fainter one at the half, plus the column's
 * leading edge. Drawn as lines, not bordered boxes: four-sided borders met
 * their neighbours' and doubled every line in the grid.
 */
@Composable
private fun HourLines() {
    val line = ZillitTheme.colors.divider
    val half = line.copy(alpha = HALF_HOUR_ALPHA)
    Box(
        Modifier.fillMaxSize().drawBehind {
            val hour = HOUR_HEIGHT.toPx()
            val stroke = HAIRLINE.toPx()
            drawLine(line, Offset(0f, 0f), Offset(0f, size.height), stroke)
            for (h in 0 until HOURS_PER_DAY) {
                val y = h * hour
                if (h > 0) drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
                drawLine(half, Offset(0f, y + hour / 2), Offset(size.width, y + hour / 2), stroke)
            }
        },
    )
}

/**
 * The events themselves, positioned within the day column.
 *
 * `BoxWithConstraints` because the layout is in fractions: the maths does not
 * know how wide a column is, which is what keeps it testable.
 */
@Composable
@Suppress("LongParameterList") // One drag seam per gesture, one each.
private fun DayEvents(
    state: CalendarUiState,
    date: LocalDate,
    onOpenEvent: (CalendarEvent) -> Unit,
    onMoveEvent: (CalendarEvent, Int, Int) -> Unit,
    onResizeEvent: (CalendarEvent, Int) -> Unit,
    onDragging: (Boolean) -> Unit = {},
) {
    val dayStart = date.startOfDayMillis(state.zone)
    val dayEnd = date.plusDays(1).startOfDayMillis(state.zone)
    val placed = layOutDay(state.eventsOn(date), dayStart, dayEnd)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val density = LocalDensity.current
        val geometry = {
            with(density) {
                DragGrid(
                    dayWidthPx = width.toPx(),
                    hourHeightPx = HOUR_HEIGHT.toPx(),
                    daysPerRow = 0,
                )
            }
        }

        var draggingId by remember { mutableStateOf<String?>(null) }
        placed.forEach { positioned ->
            Box(
                Modifier
                    .offset(
                        x = width * positioned.leftFraction,
                        y = height * positioned.top,
                    )
                    .width(width * positioned.widthFraction)
                    .height(height * positioned.height)
                    // Above its overlapping neighbours too, not just other columns.
                    .zIndex(if (positioned.event.id == draggingId) 1f else 0f)
                    .draggableEvent(
                        positioned.event,
                        geometry,
                        onDragging = { active ->
                            draggingId = if (active) positioned.event.id else null
                            onDragging(active)
                        },
                    ) { days, minutes ->
                        onMoveEvent(positioned.event, days, minutes)
                    }
                    // Inset so touching blocks read as two, not one.
                    .padding(EVENT_INSET),
            ) {
                TimeGridEvent(
                    event = positioned.event,
                    zone = state.zone,
                    geometry = geometry,
                    onResize = { minutes -> onResizeEvent(positioned.event, minutes) },
                    onOpen = { onOpenEvent(positioned.event) },
                )
            }
        }
    }
}

@Composable
private fun TimeGridEvent(
    event: CalendarEvent,
    zone: TimeZone,
    geometry: () -> DragGrid,
    onResize: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val tint = event.tint()

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(ZillitTheme.shapes.medium)
            // Opaque under the tint, so the hour lines do not show through a block.
            .background(colors.surface)
            .background(tint.copy(alpha = if (hovered) BLOCK_HOVER_ALPHA else BLOCK_TINT_ALPHA))
            .border(width = 1.dp, color = tint.copy(alpha = BLOCK_BORDER_ALPHA), shape = ZillitTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
    ) {
        // The vertical stripe, in the event's own colour.
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(tint)
        )

        BlockLabels(event, zone, Modifier.weight(1f))
    }

    // The resize grip: a short bar on the bottom edge. Its own pointer input,
    // so grabbing it stretches the end rather than moving the whole block.
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(RESIZE_HANDLE_HEIGHT)
            .resizableEventEdge(event, geometry, onResize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(RESIZE_GRIP_WIDTH)
                .height(RESIZE_GRIP_HEIGHT)
                .clip(ZillitTheme.shapes.pill)
                .background(tint.copy(alpha = GRIP_ALPHA)),
        )
    }
    }
}

/** A block's words: title, times, and the place when there is room. */
@Composable
private fun BlockLabels(event: CalendarEvent, zone: TimeZone, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = EVENT_INSET),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        ZillitText(
            text = event.title,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 2,
        )
        ZillitText(
            text = event.timeLabel(zone),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1,
        )
        event.location?.takeIf { it.isNotBlank() }?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * All-day events, above the grid.
 *
 * Hidden entirely when there are none — an always-present empty strip costs
 * vertical space the grid wants.
 */
@Composable
private fun AllDayStrip(state: CalendarUiState, days: List<LocalDate>, onOpenEvent: (CalendarEvent) -> Unit) {
    val perDay = days.map { date -> date to state.eventsOn(date).filter { it.isAllDay } }
    if (perDay.all { it.second.isEmpty() }) return

    Column(Modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ZillitTheme.spacing.xs),
    ) {
        Box(Modifier.width(TIME_GUTTER_WIDTH)) {
            ZillitText(
                text = str(S.all_day),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = ZillitTheme.spacing.xs),
            )
        }

        perDay.forEach { (_, events) ->
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = EVENT_INSET),
                verticalArrangement = Arrangement.spacedBy(EVENT_INSET),
            ) {
                events.forEach { event ->
                    val tint = event.tint()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ZillitTheme.shapes.small)
                            .background(tint.copy(alpha = BLOCK_TINT_ALPHA))
                            .clickable { onOpenEvent(event) }
                            .padding(horizontal = ZillitTheme.spacing.xs, vertical = EVENT_INSET),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        Box(Modifier.width(3.dp).height(10.dp).clip(ZillitTheme.shapes.pill).background(tint))
                        ZillitText(
                            text = event.title,
                            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = ZillitTheme.colors.textPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.divider))
    }
}

/** "9 am", "12 pm" — the web's format, and the one people read a call sheet in. */
private fun Int.hourLabel(): String = when {
    this == 0 -> "12 am"
    this < NOON -> "$this am"
    this == NOON -> "12 pm"
    else -> "${this - NOON} pm"
}

private const val HOURS_PER_DAY = 24
private const val NOON = 12

private val HOUR_HEIGHT = 48.dp
private val GRID_HEIGHT = HOUR_HEIGHT * HOURS_PER_DAY
/** Shared with the header, so weekday labels sit over their own columns. */
internal val TIME_GUTTER_WIDTH = 56.dp
private val EVENT_INSET = 2.dp
private val HAIRLINE = 1.dp

/** Lifts an hour label so it straddles its line rather than sitting under it. */
private val LABEL_LIFT = (-6).dp

private const val BLOCK_TINT_ALPHA = 0.18f
private const val BLOCK_HOVER_ALPHA = 0.28f
private const val BLOCK_BORDER_ALPHA = 0.35f
private const val HALF_HOUR_ALPHA = 0.4f
private const val TODAY_COLUMN_ALPHA = 0.04f
/** Where a time grid opens when nothing earlier is booked. */
private const val DEFAULT_FIRST_HOUR = 7
private val SCROLL_HEADROOM = 12.dp
private val NOW_LINE = 2.dp
private val NOW_DOT_RADIUS = 5.dp

private val RESIZE_HANDLE_HEIGHT = 10.dp
private val RESIZE_GRIP_WIDTH = 24.dp
private val RESIZE_GRIP_HEIGHT = 3.dp
private const val GRIP_ALPHA = 0.7f
