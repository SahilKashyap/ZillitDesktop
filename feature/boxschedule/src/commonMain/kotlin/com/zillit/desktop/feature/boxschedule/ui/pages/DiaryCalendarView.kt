package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.CalendarMode
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.inDiaryOrder
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DayEvent
import com.zillit.desktop.feature.boxschedule.ui.DayFocus
import com.zillit.desktop.feature.boxschedule.ui.DiaryCommand
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.PageEvent
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent
import kotlinx.datetime.LocalDate

@Composable
internal fun rememberCalendarData(state: BoxScheduleUiState): CalendarData {
    val filter = state.page.filter
    return remember(state.blocks, state.events, filter, state.zone) {
        val blocks = filter.calendarBlocks(state.blocks)
        val entries = filter.calendarEvents(state.events)
        CalendarData(
            schedules = DiaryCalendar.schedulesByDay(blocks, state.zone),
            events = DiaryCalendar.eventsByDay(entries, state.zone),
            notes = DiaryCalendar.notesByDay(entries, state.zone).mapValues { it.value.inDiaryOrder() },
        )
    }
}

/**
 * The Calendar view — `CalendarView.jsx`: Month, Week or Day, with the
 * mode switch, its own default, the arrows, and Today.
 */
@Composable
internal fun DiaryCalendarView(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val data = rememberCalendarData(state)
    Column(Modifier.fillMaxSize()) {
        CalendarHeader(state, onEvent)
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 26.dp, top = 12.dp, bottom = 20.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZillitTheme.colors.surface)
                    .border(1.dp, ZillitTheme.colors.border, RoundedCornerShape(8.dp)),
            ) {
                when (state.page.calendarMode) {
                    CalendarMode.Month -> MonthGrid(state, data, onEvent)
                    CalendarMode.Week -> WeekRow(state, data, onEvent)
                    CalendarMode.Day -> DayFocus(state, data, onEvent)
                }
            }
        }
    }
}

@Composable
private fun CalendarHeader(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val page = state.page
    val title = when (page.calendarMode) {
        CalendarMode.Month -> DiaryFormat.monthTitle(page.month ?: state.today)
        CalendarMode.Week -> page.weekDays().let { days ->
            if (days.isEmpty()) "" else DiaryFormat.weekTitle(days.first(), days.last())
        }
        CalendarMode.Day -> DiaryFormat.dayTitle(page.day ?: state.today)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.canvas)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Segments(
                options = CalendarMode.entries,
                selected = page.calendarMode,
                label = { it.label },
                onSelect = { onEvent(PageEvent.SetCalendarMode(it)) },
            )
            DefaultViewMenu(
                buttonText = str(S.bs_set_default),
                description = str(S.dv_calendar_desc),
                options = CalendarMode.entries,
                current = page.defaultCalendarMode,
                label = { "${it.label} View" },
                hint = { it.hint },
                onChoose = { onEvent(PageEvent.SaveDefaultCalendarMode(it)) },
            )
        }
        NavArrow(ZillitIcons.ChevronLeft, DiaryCommand.Previous.label) { onEvent(PageEvent.Step(forward = false)) }
        ZillitText(
            text = title.uppercase(),
            style = serif(15.sp, FontWeight.ExtraBold, spacing = 2.sp),
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(TITLE_WIDTH),
        )
        NavArrow(ZillitIcons.ChevronRight, DiaryCommand.Next.label) { onEvent(PageEvent.Step(forward = true)) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            ZillitButton(
                text = str(S.today),
                onClick = { onEvent(PageEvent.Today) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun NavArrow(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .border(1.dp, if (hovered) colors.accent else colors.border, RoundedCornerShape(8.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = icon,
            contentDescription = description,
            tint = if (hovered) colors.accentText else colors.textSecondary,
        )
    }
}

// Month --------------------------------------------------------------------

@Composable
private fun MonthGrid(state: BoxScheduleUiState, data: CalendarData, onEvent: (BoxScheduleEvent) -> Unit) {
    val month = state.page.month ?: state.today
    WeekdayHeader()
    DiaryCalendar.monthGrid(month).forEach { week ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            week.forEachIndexed { index, date ->
                MonthCell(
                    date = date,
                    inMonth = DiaryCalendar.sameMonth(date, month),
                    state = state,
                    data = data,
                    onEvent = onEvent,
                    lastInRow = index == week.lastIndex,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth().background(colors.surfaceSunken)) {
        WEEKDAYS.forEach { day ->
            ZillitText(
                text = day.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun MonthCell(
    date: LocalDate,
    inMonth: Boolean,
    state: BoxScheduleUiState,
    data: CalendarData,
    onEvent: (BoxScheduleEvent) -> Unit,
    lastInRow: Boolean,
    modifier: Modifier = Modifier,
) {
    val dayKey = DiaryCalendar.startOf(date, state.zone)
    val schedules = data.schedules[dayKey].orEmpty()
    val hasContent = data.hasContent(dayKey)
    val past = state.isPast(dayKey)
    val selected = state.overlays.day?.dayKey == dayKey
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val clickable = inMonth && (hasContent || (!past && state.mayEdit))
    Box(
        modifier = modifier
            .cellBorders(lastInRow)
            .alpha(cellAlpha(inMonth = inMonth, past = past, hasContent = hasContent))
            .defaultMinSize(minHeight = MONTH_CELL_MIN)
            .background(monthCellGround(date, schedules, selected = selected, hovered = hovered && clickable))
            .hoverable(interaction)
            .clickable(enabled = clickable) { openDay(dayKey, hasContent, past, state, onEvent) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DayNumber(date = date, today = date == state.today)
            if (inMonth || hasContent) {
                CellSchedules(dayKey, date, schedules, past, state, onEvent, max = MAX_SCHEDULES)
                CellEvents(
                    dayKey,
                    date,
                    data.events[dayKey].orEmpty(),
                    data.notes[dayKey].orEmpty().size,
                    state,
                    onEvent,
                    max = MAX_EVENTS,
                )
                CellNotes(dayKey, date, data.notes[dayKey].orEmpty(), onEvent)
            }
        }
    }
}

/** Selected, hovered, the first schedule's tint, a weekend's shade — in that order. */
@Composable
private fun monthCellGround(
    date: LocalDate,
    schedules: List<ScheduleBlock>,
    selected: Boolean,
    hovered: Boolean,
): Color {
    val colors = ZillitTheme.colors
    return when {
        selected -> colors.accentSoft
        hovered -> colors.surfaceHover
        schedules.isNotEmpty() -> swatchColor(schedules.first().color).tint(CELL_TINT)
        DiaryCalendar.isWeekend(date) -> colors.surfaceSunken.copy(alpha = WEEKEND_ALPHA)
        else -> colors.surface
    }
}

/** Another month's days fade; so does an empty day already gone. */
private fun cellAlpha(inMonth: Boolean, past: Boolean, hasContent: Boolean): Float = when {
    !inMonth -> OTHER_MONTH_ALPHA
    past && !hasContent -> PAST_ALPHA
    else -> 1f
}

/** The web's `handleCellClick`: a day with anything on it opens; an empty future day offers to create. */
private fun openDay(
    dayKey: Long,
    hasContent: Boolean,
    past: Boolean,
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    when {
        hasContent -> onEvent(DayEvent.OpenDay(dayKey))
        !past && state.mayEdit -> onEvent(DayEvent.OpenQuickAction(dayKey))
    }
}

/** The grid's hairlines: right of every cell but the last in its row, and under every cell. */
@Composable
private fun Modifier.cellBorders(lastInRow: Boolean): Modifier =
    gridLines(ZillitTheme.colors.border.tint(CELL_LINE_ALPHA), right = !lastInRow)

@Composable
private fun DayNumber(date: LocalDate, today: Boolean, large: Boolean = false) {
    val colors = ZillitTheme.colors
    val size = if (large) 96.dp else 26.dp
    if (today) {
        Box(Modifier.size(size).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            ZillitText(
                text = date.day.toString(),
                style = serif(if (large) 44.sp else 13.sp, FontWeight.ExtraBold),
                color = colors.textOnAccent,
            )
        }
    } else {
        ZillitText(
            text = date.day.toString(),
            style = serif(if (large) 56.sp else 14.sp, FontWeight.SemiBold),
            color = colors.textSecondary,
        )
    }
}

// Week ---------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekRow(state: BoxScheduleUiState, data: CalendarData, onEvent: (BoxScheduleEvent) -> Unit) {
    WeekdayHeader()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        val days = state.page.weekDays()
        days.forEachIndexed { index, date ->
            val dayKey = DiaryCalendar.startOf(date, state.zone)
            val hasContent = data.hasContent(dayKey)
            val past = state.isPast(dayKey)
            val clickable = hasContent || (!past && state.mayEdit)
            val open = state.overlays.day?.dayKey == dayKey
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .cellBorders(lastInRow = index == days.lastIndex)
                    .alpha(if (past && !hasContent) PAST_ALPHA else 1f)
                    .defaultMinSize(minHeight = WEEK_CELL_MIN)
                    .background(if (open) ZillitTheme.colors.accentSoft else ZillitTheme.colors.surface)
                    .clickable(enabled = clickable) { openDay(dayKey, hasContent, past, state, onEvent) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DayNumber(date = date, today = date == state.today)
                    ZillitText(
                        text = DiaryFormat.shortDay(date),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    data.schedules[dayKey].orEmpty().forEach { block ->
                        ScheduleTag(block, dayKey, state) {
                            onEvent(DayEvent.OpenDay(dayKey, DayFocus.Schedule(block.id)))
                        }
                    }
                }
                CellEvents(
                    dayKey,
                    date,
                    data.events[dayKey].orEmpty(),
                    data.notes[dayKey].orEmpty().size,
                    state,
                    onEvent,
                    max = MAX_WEEK_EVENTS,
                )
                CellNotes(dayKey, date, data.notes[dayKey].orEmpty(), onEvent)
            }
        }
    }
}

/** A schedule's tag in the Week and Day views: colour, type, and "Day N" of a multi-day block. */
@Composable
private fun ScheduleTag(
    block: ScheduleBlock,
    dayKey: Long,
    state: BoxScheduleUiState,
    large: Boolean = false,
    onClick: () -> Unit,
) {
    val color = swatchColor(block.color)
    val index = DiaryCalendar.dayIndex(block, dayKey, state.zone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(if (large) 8.dp else 6.dp))
            .background(color.tint(TAG_TINT))
            .border(1.dp, color.tint(TAG_BORDER), RoundedCornerShape(if (large) 8.dp else 6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (large) 14.dp else 10.dp, vertical = if (large) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(color, size = if (large) 12.dp else 8.dp, square = true)
        ZillitText(
            text = block.typeName,
            style = (if (large) ZillitTheme.typography.titleSmall else ZillitTheme.typography.labelSmall)
                .copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        index?.let { (n, total) ->
            ZillitText(
                text = if (large) "DAY $n OF $total" else "DAY $n",
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 0.3.sp,
                ),
                color = color,
            )
        }
        if (large && block.title.isNotBlank()) {
            ZillitText(
                block.title,
                style = serif(11.sp, FontWeight.Normal),
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

// Day ----------------------------------------------------------------------

@Composable
private fun DayFocus(state: BoxScheduleUiState, data: CalendarData, onEvent: (BoxScheduleEvent) -> Unit) {
    val date = state.page.day ?: state.today
    val dayKey = DiaryCalendar.startOf(date, state.zone)
    val past = state.isPast(dayKey)
    val schedules = data.schedules[dayKey].orEmpty()
    val events = data.events[dayKey].orEmpty()
    val notes = data.notes[dayKey].orEmpty()
    val hasAny = data.hasContent(dayKey)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        DayFocusHeader(date = date, today = date == state.today, past = past)
        if (schedules.isNotEmpty()) DayFocusSchedules(schedules, dayKey, state, onEvent)
        if (events.isNotEmpty()) DayFocusEvents(events, dayKey, date, notes.size, state, onEvent)
        if (notes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption(str(S.notes))
                notes.forEach { note -> NoteCard(note) { onEvent(DayEvent.ViewEntry(note.listKey)) } }
            }
        }
        if (!hasAny) NothingOnDay(past)
        Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
        DayFocusActions(dayKey, hasAny = hasAny, canCreate = !past && state.mayEdit, onEvent = onEvent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayFocusSchedules(
    schedules: List<ScheduleBlock>,
    dayKey: Long,
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Caption(str(S.dd_schedules))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            schedules.forEach { block ->
                ScheduleTag(block, dayKey, state, large = true) {
                    onEvent(DayEvent.OpenDay(dayKey, DayFocus.Schedule(block.id)))
                }
            }
        }
    }
}

@Composable
private fun NothingOnDay(past: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitText(
            str(S.dd_empty),
            style = ZillitTheme.typography.titleSmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(
            text = if (past) str(S.dd_past) else str(S.dd_add_prompt),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun DayFocusActions(dayKey: Long, hasAny: Boolean, canCreate: Boolean, onEvent: (BoxScheduleEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasAny) {
            ZillitButton(
                str(S.dd_view_details),
                onClick = { onEvent(DayEvent.OpenDay(dayKey)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        if (canCreate) {
            ZillitButton(
                text = str(S.dd_add_schedule),
                onClick = { onEvent(ScheduleEvent.NewSchedule(dayKey)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Calendar,
            )
            ZillitButton(
                text = str(S.add_event),
                onClick = { onEvent(EntryEvent.NewEntry(DiaryKind.Event, dayKey)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Clock,
            )
        }
    }
}

@Composable
private fun DayFocusHeader(date: LocalDate, today: Boolean, past: Boolean) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        DayNumber(date = date, today = today, large = true)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText(
                DiaryFormat.weekday(date),
                style = serif(22.sp, FontWeight.Bold, spacing = 0.5.sp),
                color = colors.textPrimary,
            )
            ZillitText(
                text = "${DiaryFormat.monthName(date)} ${date.year}".uppercase(),
                style = ZillitTheme.typography.label.copy(letterSpacing = 1.sp),
                color = colors.textMuted,
            )
            when {
                today -> StatusTag(str(S.desktop_bs_today_upper), colors.success)
                past -> StatusTag(str(S.desktop_bs_past_upper), colors.textMuted)
            }
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color.tint(TAG_TINT))
            .border(1.dp, color.tint(TAG_BORDER), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        ZillitText(text, style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = color)
    }
}

@Composable
private fun DayFocusEvents(
    events: List<DiaryEvent>,
    dayKey: Long,
    date: LocalDate,
    noteCount: Int,
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val over = events.size > MAX_DAY_EVENTS + 1
    val shown = if (over) events.take(MAX_DAY_EVENTS) else events
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption(str(S.dd_events))
        shown.forEach { event -> EventCard(event, state) { onEvent(DayEvent.ViewEntry(event.listKey)) } }
        if (over) {
            MoreEventsPopover(
                text = "+ ${events.size - shown.size} more event${if (events.size - shown.size == 1) "" else "s"}",
                date = date,
                events = events,
                noteCount = noteCount,
                state = state,
                onView = { onEvent(DayEvent.ViewEntry(it)) },
                onViewAll = { onEvent(DayEvent.OpenDay(dayKey)) },
            )
        }
    }
}

/** An event as a card with its colour down the left edge — the Day view and the day drawer. */
@Composable
internal fun EventCard(event: DiaryEvent, state: BoxScheduleUiState, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val time = DiaryFormat.timeRange(event, state.zone)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken.tint(CARD_ALPHA))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(hexColor(event.color) ?: EVENT_BLUE))
        Column(
            Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (time.isNotBlank()) {
                ZillitText(
                    time,
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMuted,
                )
            }
            ZillitText(
                event.title,
                style = ZillitTheme.typography.titleSmall,
                color = hexColor(event.textColor) ?: colors.textPrimary,
            )
            if (event.location.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZillitIcon(icon = ZillitIcons.Pin, tint = colors.accentText, size = 11.dp)
                    ZillitText(
                        event.location,
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.accentText,
                        maxLines = 1,
                    )
                }
            }
            if (event.body.isNotBlank()) {
                ZillitText(event.body, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 3)
            }
        }
    }
}

/** A note as a warm card. */
@Composable
internal fun NoteCard(note: DiaryEvent, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.warningSoft.tint(NOTE_ALPHA))
            .border(1.dp, colors.warning.tint(TAG_BORDER), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ZillitText(
            note.title.ifBlank { str(S.desktop_bs_untitled_note) },
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
        )
        if (note.body.isNotBlank()) {
            ZillitText(
                note.body,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 3,
            )
        }
    }
}

private val WEEKDAYS = listOf(str(S.mon), str(S.tue), str(S.wed), str(S.thu), str(S.fri), str(S.sat), str(S.sun))
private val TITLE_WIDTH = 300.dp
private val MONTH_CELL_MIN = 112.dp
private val WEEK_CELL_MIN = 200.dp
internal const val MAX_SCHEDULES = 4
internal const val MAX_EVENTS = 3
private const val MAX_WEEK_EVENTS = 4
private const val MAX_DAY_EVENTS = 6
private const val CELL_TINT = 0.05f
private const val WEEKEND_ALPHA = 0.55f
private const val OTHER_MONTH_ALPHA = 0.3f
private const val PAST_ALPHA = 0.45f
private const val CELL_LINE_ALPHA = 0.8f
private const val TAG_TINT = 0.1f
private const val TAG_BORDER = 0.25f
private const val CARD_ALPHA = 0.6f
private const val NOTE_ALPHA = 0.6f
