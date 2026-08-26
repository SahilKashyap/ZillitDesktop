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

    Column(modifier.fillMaxSize()) {
        // All-day events have no position on a time axis, so they sit above the
        // grid rather than being forced into midnight.
        AllDayStrip(state, days)

        Row(Modifier.fillMaxSize().zillitVerticalScroll()) {
            HourGutter()

            days.forEach { date ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(GRID_HEIGHT)
                        .zIndex(if (date == draggingDate) 1f else 0f)
                        .background(colors.canvas)
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

    Box(
        Modifier
            .fillMaxSize()
            .padding(start = HAIRLINE) // Don't draw over the gutter divider
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = GRID_HEIGHT * fractionOfDay)
                .height(2.dp)
                .background(ZillitTheme.colors.danger)
        ) {
            // The dot at the start of the line
            Box(
                Modifier
                    .size(8.dp)
                    .offset(x = (-4).dp, y = (-3).dp)
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.danger)
            )
        }
    }
}

/** One hairline per hour, so events can be read against the clock. */
@Composable
private fun HourLines() {
    Column(Modifier.fillMaxSize()) {
        repeat(HOURS_PER_DAY) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(HOUR_HEIGHT)
                    .border(width = HAIRLINE, color = ZillitTheme.colors.divider.copy(alpha = 0.5f))
            )
        }
    }
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

    Box(Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(ZillitTheme.shapes.small)
            .background(tint.copy(alpha = BLOCK_TINT_ALPHA))
            .border(width = 1.dp, color = tint.copy(alpha = BLOCK_BORDER_ALPHA), shape = ZillitTheme.shapes.small)
            .clickable(onClick = onOpen)
    ) {
        // The vertical stripe, in the event's own colour.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(tint)
        )

        Column(
            modifier = Modifier
                .weight(1f)
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
        }
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

/**
 * All-day events, above the grid.
 *
 * Hidden entirely when there are none — an always-present empty strip costs
 * vertical space the grid wants.
 */
@Composable
private fun AllDayStrip(state: CalendarUiState, days: List<LocalDate>) {
    val perDay = days.map { date -> date to state.eventsOn(date).filter { it.isAllDay } }
    if (perDay.all { it.second.isEmpty() }) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(vertical = ZillitTheme.spacing.xxs),
    ) {
        Box(Modifier.width(TIME_GUTTER_WIDTH)) {
            ZillitText(
                text = "All day",
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
                    ZillitText(
                        text = event.title,
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.accentText,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ZillitTheme.shapes.small)
                            .background(ZillitTheme.colors.accentSoft)
                            .padding(horizontal = ZillitTheme.spacing.xs),
                    )
                }
            }
        }
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
private const val BLOCK_BORDER_ALPHA = 0.45f

private val RESIZE_HANDLE_HEIGHT = 10.dp
private val RESIZE_GRIP_WIDTH = 24.dp
private val RESIZE_GRIP_HEIGHT = 3.dp
private const val GRIP_ALPHA = 0.7f
