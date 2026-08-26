package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The production calendar, following the web client's layout.
 *
 * Grid on the left, the selected day's events on the right — the split the web
 * uses and the one desktop width earns. A phone stacks these; a 1440pt window
 * can show both, so clicking a day answers "what is on" without navigating.
 */
@Composable
internal fun CalendarScreen(
    state: CalendarUiState,
    onEvent: (CalendarEvent2Event) -> Unit,
    modifier: Modifier = Modifier,
    loadAvatar: suspend (String) -> ByteArray? = { null },
) {
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        CalendarToolbar(state, onEvent)

        when {
            state.error != null && state.events.isEmpty() -> ErrorState(state.error, onEvent)

            else -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (state.mode) {
                        CalendarViewMode.Month -> MonthView(state, onEvent)
                        CalendarViewMode.Week -> WeekView(state, onEvent)
                        CalendarViewMode.Day -> DayView(state, onEvent)
                    }
                }

                // The detail panel belongs to Month alone. Week and Day are
                // time grids and already show when things are; a list beside
                // them would say the same thing twice and take the width the
                // grid needs.
                if (state.mode == CalendarViewMode.Month) {
                    Box(
                        Modifier
                            .width(DETAIL_WIDTH)
                            .fillMaxHeight()
                            .background(ZillitTheme.colors.surface),
                    ) {
                        DayColumn(state, state.focusedDay)
                    }
                }
            }
        }
    }

        // Shell-backed overlays stay composed and drive the visible flag —
        // an `if` would unmount them before the exit animation could play.
        InvitationsPanel(state, onEvent)

        EventFormDialog(state.form, onEvent, loadAvatar = loadAvatar)

        state.detail?.let { detail -> EventDetailPopover(detail, onEvent) }

        // Always composed so its exit can play; the flag drives visibility.
        DeleteEventDialog(state.detail, onEvent)
        RescheduleDialog(state, onEvent)

        // Last, so it covers whichever surface asked for the decline — the
        // invitations panel or the detail popover.
        DeclineReasonDialog(state.declining, busy = state.invitationsBusy, onEvent)

        // An action error over a loaded board floats, exactly as the home
        // feed's does — it used to vanish silently, which cost a live
        // debugging round to discover.
        if (state.events.isNotEmpty()) {
            ZillitErrorToast(
                message = state.error,
                onDismiss = { onEvent(CalendarEvent2Event.DismissError) },
            )
        }
    }
}

/**
 * Asks before deleting an event.
 *
 * Deleting takes it off everyone's calendar, not just this user's — which is
 * not obvious from a button on your own screen.
 */
@Composable
private fun DeleteEventDialog(detail: EventDetailState?, onEvent: (CalendarEvent2Event) -> Unit) {
    // The event being deleted survives the exit animation — the confirm flag
    // drops on dismiss, but the title must not blank while fading out.
    val shown = remember { mutableStateOf<EventDetailState?>(null) }
    if (detail?.isConfirmingDelete == true) shown.value = detail
    val current = shown.value

    ZillitDialogShell(
        title = "Delete \"${current?.event?.title ?: "this event"}\"?",
        subtitle = "It comes off everyone's calendar, not just yours.",
        visible = detail?.isConfirmingDelete == true,
        onDismiss = { onEvent(CalendarEvent2Event.DismissDeleteEvent) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = "This cannot be undone.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(CalendarEvent2Event.DismissDeleteEvent) },
            )
            ZillitButton(
                text = "Delete",
                variant = ButtonVariant.Danger,
                onClick = { onEvent(CalendarEvent2Event.ConfirmDeleteEvent) },
            )
        }
    }
}

/**
 * The question a drag-drop asks before it moves anything: the event by name,
 * the times it holds now, and the times the drop proposes. Kept composed
 * with `visible` driven, as [ZillitDialogShell] asks; the last pending drop
 * is retained so the words do not blank during the fade-out.
 */
@Composable
private fun RescheduleDialog(state: CalendarUiState, onEvent: (CalendarEvent2Event) -> Unit) {
    val shown = remember { mutableStateOf<PendingReschedule?>(null) }
    state.pendingReschedule?.let { shown.value = it }
    val current = shown.value

    ZillitDialogShell(
        title = "Move \"${current?.event?.title ?: "this event"}\"?",
        subtitle = current?.let { rescheduleLine(it, state.zone) },
        visible = state.pendingReschedule != null,
        onDismiss = { onEvent(CalendarEvent2Event.CancelReschedule) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = "Everyone invited sees the new time.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(CalendarEvent2Event.CancelReschedule) },
            )
            ZillitButton(
                text = "Move event",
                onClick = { onEvent(CalendarEvent2Event.ConfirmReschedule) },
            )
        }
    }
}

/** "Tue 25 Aug, 10:00 – 11:00 → Wed 26 Aug, 10:30 – 11:30" — the whole change in one line. */
private fun rescheduleLine(pending: PendingReschedule, zone: TimeZone): String {
    val from = pending.event.copy()
    val to = pending.event.copy(startMillis = pending.newStart, endMillis = pending.newEnd)
    return "${from.dayAndTimeLabel(zone)}  →  ${to.dayAndTimeLabel(zone)}"
}

/** A dead calendar with a way back — not a message and a shrug. */
@Composable
private fun ErrorState(message: String, onEvent: (CalendarEvent2Event) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(PAGE_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZillitText(
            text = message,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        ZillitButton(text = "Try again", onClick = { onEvent(CalendarEvent2Event.Reload) })
    }
}

@Composable
private fun CalendarToolbar(state: CalendarUiState, onEvent: (CalendarEvent2Event) -> Unit) {
    // Title beside the navigation, actions pinned right — the centred-title
    // layout let a grown action cluster push New event off the window edge.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitButton(
            text = "Today",
            onClick = { onEvent(CalendarEvent2Event.Today) },
            variant = ButtonVariant.Secondary,
            size = com.zillit.desktop.core.designsystem.component.ButtonSize.Small,
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = "Previous",
            onClick = { onEvent(CalendarEvent2Event.Previous) },
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronRight,
            contentDescription = "Next",
            onClick = { onEvent(CalendarEvent2Event.Next) },
        )
        ZillitText(
            text = state.title,
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ToolbarActions(state, onEvent)
    }
}

/** The toolbar's right side: invitations (badged), the view mode, New event. */
@Composable
private fun ToolbarActions(state: CalendarUiState, onEvent: (CalendarEvent2Event) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box {
            ZillitButton(
                text = "Invitations",
                variant = ButtonVariant.Secondary,
                size = com.zillit.desktop.core.designsystem.component.ButtonSize.Small,
                onClick = { onEvent(CalendarEvent2Event.ShowInvitations) },
            )
            ZillitBadge(
                count = state.pendingInvitations,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        ZillitSelect(
            value = state.mode,
            options = CalendarViewMode.entries,
            onSelect = { onEvent(CalendarEvent2Event.SetMode(it)) },
            label = CalendarViewMode::label,
            // Fixed, not fluid: an unconstrained select fills the whole
            // toolbar and shoves New event off the window edge.
            modifier = Modifier.width(MODE_SELECT_WIDTH),
        )
        ZillitButton(
            text = "New event",
            leadingIcon = ZillitIcons.Add,
            onClick = { onEvent(CalendarEvent2Event.OpenForm(null)) },
            size = com.zillit.desktop.core.designsystem.component.ButtonSize.Small,
        )
    }
}

@Composable
private fun MonthView(state: CalendarUiState, onEvent: (CalendarEvent2Event) -> Unit) {
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    val weekCount = state.grid.weeks.size
    // The day whose event is mid-drag: its cell and row ride above the
    // rest, or the dragged chip slides UNDER every later-drawn cell.
    var draggingDate by remember { mutableStateOf<LocalDate?>(null) }

    Column(Modifier.fillMaxSize()) {
        WeekdayHeader(state)

        // The grid's lines are the border colour showing through 1dp gaps —
        // one crisp hairline everywhere instead of doubled cell borders.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.border)
                .onGloballyPositioned { gridSize = it.size },
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        ) {
            state.grid.weeks.forEach { week ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .zIndex(if (week.any { it.date == draggingDate }) 1f else 0f),
                    horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                ) {
                    week.forEach { day ->
                        DayCell(
                            state = state,
                            date = day.date,
                            dimmed = !day.inMonth,
                            onEvent = onEvent,
                            onDragging = { active ->
                                draggingDate = if (active) day.date else null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .zIndex(if (day.date == draggingDate) 1f else 0f),
                            dragGrid = {
                                DragGrid(
                                    dayWidthPx = gridSize.width / DAYS_IN_WEEK.toFloat(),
                                    hourHeightPx = 0f,
                                    daysPerRow = DAYS_IN_WEEK,
                                    rowHeightPx = gridSize.height / weekCount.toFloat(),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Seven days against the clock, matching the web's `timeGridWeek`.
 *
 * The header is offset by the hour gutter so each weekday sits over its own
 * column — without that the labels drift one gutter-width left of their days.
 */
@Composable
private fun WeekView(state: CalendarUiState, onEvent: (CalendarEvent2Event) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TimeGridHeader(state.week, state)
        TimeGrid(
            state = state,
            days = state.week,
            onSelectDay = { onEvent(CalendarEvent2Event.Select(it)) },
            onOpenEvent = { onEvent(CalendarEvent2Event.OpenDetail(it)) },
            onMoveEvent = { event, days, minutes ->
                onEvent(CalendarEvent2Event.MoveEvent(event, days, minutes))
            },
            onResizeEvent = { event, minutes ->
                onEvent(CalendarEvent2Event.ResizeEvent(event, minutes))
            },
        )
    }
}

/** One day against the clock — the web's `timeGridDay`. */
@Composable
private fun DayView(state: CalendarUiState, onEvent: (CalendarEvent2Event) -> Unit) {
    val day = listOf(state.focusedDay)

    Column(Modifier.fillMaxSize()) {
        TimeGridHeader(day, state)
        TimeGrid(
            state = state,
            days = day,
            onSelectDay = { onEvent(CalendarEvent2Event.Select(it)) },
            onOpenEvent = { onEvent(CalendarEvent2Event.OpenDetail(it)) },
            onMoveEvent = { event, days, minutes ->
                onEvent(CalendarEvent2Event.MoveEvent(event, days, minutes))
            },
            onResizeEvent = { event, minutes ->
                onEvent(CalendarEvent2Event.ResizeEvent(event, minutes))
            },
        )
    }
}

/** Weekday and date above each column, aligned past the hour gutter. */
@Composable
private fun TimeGridHeader(days: List<LocalDate>, state: CalendarUiState) {
    val colors = ZillitTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(vertical = ZillitTheme.spacing.xs),
    ) {
        Spacer(Modifier.width(TIME_GUTTER_WIDTH))

        days.forEach { date ->
            val isToday = date == state.today

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZillitText(
                    text = date.dayOfWeek.name.take(WEEKDAY_LETTERS).lowercase()
                        .replaceFirstChar { it.uppercase() },
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
                ZillitText(
                    text = date.day.toString(),
                    style = ZillitTheme.typography.titleSmall,
                    color = if (isToday) colors.accentText else colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun WeekdayHeader(state: CalendarUiState) {
    Row(Modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) {
        state.week.forEach { date ->
            ZillitText(
                text = date.dayOfWeek.name.take(WEEKDAY_LETTERS).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = WEEKDAY_TRACKING,
                ),
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(vertical = ZillitTheme.spacing.sm),
            )
        }
    }
}

@Composable
private fun DayCell(
    state: CalendarUiState,
    date: LocalDate,
    dimmed: Boolean,
    onEvent: (CalendarEvent2Event) -> Unit,
    modifier: Modifier = Modifier,
    dragGrid: () -> DragGrid = { DragGrid(0f, 0f, 0) },
    onDragging: (Boolean) -> Unit = {},
) {
    val colors = ZillitTheme.colors
    val events = state.eventsOn(date)
    val isSelected = date == state.focusedDay
    val isToday = date == state.today

    Column(
        modifier = modifier
            .background(if (dimmed) colors.canvas else colors.surface)
            .then(
                if (isSelected) {
                    Modifier.border(SELECTED_RING, colors.accent)
                } else {
                    Modifier
                },
            )
            .clickable { onEvent(CalendarEvent2Event.Select(date)) }
            .padding(ZillitTheme.spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        DayNumber(date, isToday, dimmed)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            events.take(MAX_CHIPS).forEach { event ->
                EventChip(event, state, dragGrid, onEvent, onDragging)
            }
            if (events.size > MAX_CHIPS) {
                ZillitText(
                    text = "+${events.size - MAX_CHIPS} more",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(start = ZillitTheme.spacing.xxs)
                )
            }
        }
    }
}

/** The cell's date: today wears the accent circle, other months fade. */
@Composable
private fun DayNumber(date: LocalDate, isToday: Boolean, dimmed: Boolean) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        if (isToday) {
            Box(
                modifier = Modifier.size(TODAY_CIRCLE).clip(CircleShape).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = date.day.toString(),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textOnAccent,
                )
            }
        } else {
            ZillitText(
                text = date.day.toString(),
                style = ZillitTheme.typography.labelSmall,
                color = if (dimmed) colors.textDisabled else colors.textPrimary,
                modifier = Modifier.padding(top = ZillitTheme.spacing.xxs),
            )
        }
    }
}

@Composable
private fun EventChip(
    event: CalendarEvent,
    state: CalendarUiState,
    dragGrid: () -> DragGrid,
    onEvent: (CalendarEvent2Event) -> Unit,
    onDragging: (Boolean) -> Unit = {},
) {
    val tint = event.tint()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .draggableEvent(event, dragGrid, onDragging) { days, _ ->
                onEvent(CalendarEvent2Event.MoveEvent(event, days, minuteDelta = 0))
            }
            .clip(ZillitTheme.shapes.small)
            .background(tint.copy(alpha = CHIP_TINT_ALPHA))
            .clickable(onClick = { onEvent(CalendarEvent2Event.OpenDetail(event)) })
            .padding(horizontal = ZillitTheme.spacing.xxs, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Box(Modifier.size(CHIP_DOT).clip(CircleShape).background(tint))
        if (!event.isAllDay) {
            ZillitText(
                text = event.startMillis.chipTime(state.zone),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = event.title,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The selected day, in full. */
@Composable
private fun DayColumn(state: CalendarUiState, date: LocalDate) {
    val events = state.eventsOn(date)

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(PAGE_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = date.day.toString(),
                style = ZillitTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = if (date == state.today) {
                    ZillitTheme.colors.accent
                } else {
                    ZillitTheme.colors.textPrimary
                },
            )
            Column {
                ZillitText(
                    text = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = ZillitTheme.typography.titleSmall,
                )
                ZillitText(
                    text = "${state.eventsOn(date).size} " +
                        if (state.eventsOn(date).size == 1) "event" else "events",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))

        if (events.isEmpty()) {
            Centred("Nothing scheduled.")
            return@Column
        }

        val agendaState = rememberLazyListState()
        ZillitLazyColumn(
            state = agendaState,
            contentPadding = PaddingValues(horizontal = PAGE_PADDING),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            items(events, key = CalendarEvent::id) { event ->
                EventRow(event, state)
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, state: CalendarUiState) {
    val colors = ZillitTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(width = HAIRLINE, color = colors.border, shape = ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical stripe in the event's own colour.
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(32.dp)
                .clip(ZillitTheme.shapes.pill)
                .background(event.tint())
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)
        ) {
            ZillitText(
                text = event.title,
                style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitText(
                text = event.timeLabel(state.zone),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            event.location?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(PAGE_PADDING),
        )
    }
}

private val PAGE_PADDING = 16.dp
private val GRID_GAP = 1.dp
private val TODAY_CIRCLE = 24.dp
private val MODE_SELECT_WIDTH = 120.dp
private const val DAYS_IN_WEEK = 7
private val SELECTED_RING = 2.dp
private val WEEKDAY_TRACKING = androidx.compose.ui.unit.TextUnit(
    1.2f,
    androidx.compose.ui.unit.TextUnitType.Sp,
)
private val DETAIL_WIDTH = 300.dp
private val HAIRLINE = 1.dp
private const val MAX_CHIPS = 3
private const val WEEKDAY_LETTERS = 3
private val CHIP_DOT = 6.dp
private const val CHIP_TINT_ALPHA = 0.16f

/** "9:00" — the compact start time on a month chip. */
private fun Long.chipTime(zone: kotlinx.datetime.TimeZone): String {
    val time = kotlin.time.Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).time
    return "${time.hour}:${time.minute.toString().padStart(2, '0')}"
}

private val DIALOG_WIDTH = 420.dp
