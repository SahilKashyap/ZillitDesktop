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
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.compositeOver
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
    /**
     * Joins the event's call. Null on a host with no calling, and the button
     * is then not drawn at all.
     */
    onJoinCall: ((CalendarEvent) -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        CalendarToolbar(state, onEvent)
        ZillitDivider()

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
                    ZillitVerticalDivider()
                    Box(
                        Modifier
                            .width(DETAIL_WIDTH)
                            .fillMaxHeight()
                            .background(ZillitTheme.colors.surface),
                    ) {
                        DayColumn(state, state.focusedDay, onEvent)
                    }
                }
            }
        }
    }

        // Shell-backed overlays stay composed and drive the visible flag —
        // an `if` would unmount them before the exit animation could play.
        InvitationsPanel(state, onEvent)

        EventFormDialog(state.form, onEvent, loadAvatar = loadAvatar)

        state.detail?.let { detail -> EventDetailPopover(detail, onEvent, onJoinCall = onJoinCall) }

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
        title = str(S.drive_delete_item_title_format, current?.event?.title ?: str(S.this_event)),
        subtitle = str(S.desktop_cal_delete_event_subtitle),
        visible = detail?.isConfirmingDelete == true,
        onDismiss = { onEvent(CalendarEvent2Event.DismissDeleteEvent) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = str(S.desktop_cannot_be_undone),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(CalendarEvent2Event.DismissDeleteEvent) },
            )
            ZillitButton(
                text = str(S.delete),
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
        title = str(S.desktop_cal_move_event_title, current?.event?.title ?: str(S.this_event)),
        subtitle = current?.let { rescheduleLine(it, state.zone) },
        visible = state.pendingReschedule != null,
        onDismiss = { onEvent(CalendarEvent2Event.CancelReschedule) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = str(S.desktop_cal_everyone_sees_new_time),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(CalendarEvent2Event.CancelReschedule) },
            )
            ZillitButton(
                text = str(S.desktop_cal_move_event),
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
        ZillitButton(text = str(S.try_again), onClick = { onEvent(CalendarEvent2Event.Reload) })
    }
}

@Composable
private fun CalendarToolbar(state: CalendarUiState, onEvent: (CalendarEvent2Event) -> Unit) {
    // Navigation and title on the left, actions pinned right — the
    // centred-title layout let a grown action cluster push New event off
    // the window edge.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        NavigationCluster(onEvent)
        ZillitText(
            text = state.title,
            style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ToolbarActions(state, onEvent)
    }
}

/** ‹ Today › as one bordered control, so the three read as a single stepper. */
@Composable
private fun NavigationCluster(onEvent: (CalendarEvent2Event) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .height(ZillitDimens.controlHeight)
            .clip(ZillitTheme.shapes.medium)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = str(S.docusign_tour_prev),
            onClick = { onEvent(CalendarEvent2Event.Previous) },
            size = ZillitDimens.controlHeight,
        )
        ClusterSeparator()
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable { onEvent(CalendarEvent2Event.Today) }
                .padding(horizontal = ZillitTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = str(S.today),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
        }
        ClusterSeparator()
        ZillitIconButton(
            icon = ZillitIcons.ChevronRight,
            contentDescription = str(S.next),
            onClick = { onEvent(CalendarEvent2Event.Next) },
            size = ZillitDimens.controlHeight,
        )
    }
}

@Composable
private fun ClusterSeparator() {
    Box(Modifier.width(HAIRLINE).fillMaxHeight().background(ZillitTheme.colors.border))
}

/** The toolbar's right side: invitations (counted), the view mode, New event. */
@Composable
private fun ToolbarActions(state: CalendarUiState, onEvent: (CalendarEvent2Event) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        InvitationsButton(state.pendingInvitations) { onEvent(CalendarEvent2Event.ShowInvitations) }
        // A segmented switch rather than a dropdown: three fixed choices are
        // one click each, and the current one is visible at a glance.
        ZillitSegmented(
            options = CalendarViewMode.entries.map { ZillitTab(id = it.name, label = str(it.label)) },
            activeId = state.mode.name,
            onSelect = { id -> onEvent(CalendarEvent2Event.SetMode(CalendarViewMode.valueOf(id))) },
        )
        ZillitButton(
            text = str(S.new_event),
            leadingIcon = ZillitIcons.Add,
            onClick = { onEvent(CalendarEvent2Event.OpenForm(null)) },
        )
    }
}

/**
 * Invitations, with the pending count inside the button — the corner badge
 * it used to wear sat over the label's last letters.
 */
@Composable
private fun InvitationsButton(pending: Int, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .height(ZillitDimens.controlHeight)
            .clip(ZillitTheme.shapes.medium)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Mail,
            size = ZillitDimens.iconSmall,
            tint = if (pending > 0) colors.accent else colors.textSecondary,
        )
        ZillitText(
            text = str(S.desktop_cal_invitations),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
        )
        ZillitBadge(count = pending)
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

        // The grid's lines are the divider colour showing through 1dp gaps —
        // one crisp hairline everywhere instead of doubled cell borders.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.divider)
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

    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm)) {
            Spacer(Modifier.width(TIME_GUTTER_WIDTH))

            days.forEach { date ->
                val isToday = date == state.today

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(
                        text = date.dayOfWeek.name.take(WEEKDAY_LETTERS).uppercase(),
                        style = ZillitTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = WEEKDAY_TRACKING,
                        ),
                        color = if (isToday) colors.accentText else colors.textMuted,
                    )
                    DateDisc(
                        day = date.day,
                        isToday = isToday,
                        isSelected = false,
                        size = HEADER_DISC,
                        style = ZillitTheme.typography.titleMedium,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(colors.divider))
    }
}

@Composable
private fun WeekdayHeader(state: CalendarUiState) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(Modifier.fillMaxWidth()) {
            state.week.forEach { date ->
                val weekend = date.dayOfWeek.isWeekend()
                ZillitText(
                    text = date.dayOfWeek.name.take(WEEKDAY_LETTERS).uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = WEEKDAY_TRACKING,
                    ),
                    color = if (weekend) colors.textDisabled else colors.textMuted,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = CELL_PADDING, vertical = ZillitTheme.spacing.sm),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(colors.divider))
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        isSelected -> colors.accent.copy(alpha = SELECTED_CELL_ALPHA).compositeOver(colors.surface)
        hovered -> colors.surfaceHover
        dimmed -> colors.surfaceSunken
        date.dayOfWeek.isWeekend() -> colors.surface.copy(alpha = WEEKEND_ALPHA).compositeOver(colors.surfaceSunken)
        else -> colors.surface
    }

    Column(
        modifier = modifier
            .background(background)
            .clickable(interactionSource = interaction, indication = null) {
                onEvent(CalendarEvent2Event.ClickDate(date))
            }
            .padding(CELL_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        DayNumber(date, isToday = isToday, isSelected = isSelected, dimmed = dimmed)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CHIP_GAP),
        ) {
            events.take(MAX_CHIPS).forEach { event ->
                EventChip(event, state, dragGrid, onEvent, onDragging)
            }
            if (events.size > MAX_CHIPS) {
                ZillitText(
                    text = str(S.bs_cal_more_items, events.size - MAX_CHIPS),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accentText,
                    modifier = Modifier.padding(start = ZillitTheme.spacing.xs),
                )
            }
        }
    }
}

/**
 * The cell's date: today wears the accent disc, the selected day a soft one,
 * and the first of a month names the month so a five-week grid reads at once.
 */
@Composable
private fun DayNumber(date: LocalDate, isToday: Boolean, isSelected: Boolean, dimmed: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        DateDisc(
            day = date.day,
            isToday = isToday,
            isSelected = isSelected,
            dimmed = dimmed,
            size = TODAY_CIRCLE,
            style = ZillitTheme.typography.label,
        )
        if (date.day == 1) {
            ZillitText(
                text = date.month.name.take(WEEKDAY_LETTERS).lowercase().replaceFirstChar { it.uppercase() },
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                color = if (dimmed) ZillitTheme.colors.textDisabled else ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/** A date number in a disc: filled accent for today, soft accent when selected. */
@Composable
private fun DateDisc(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    style: androidx.compose.ui.text.TextStyle,
    dimmed: Boolean = false,
) {
    val colors = ZillitTheme.colors
    val (fill, ink) = when {
        isToday -> colors.accent to colors.textOnAccent
        isSelected -> colors.accentSoft to colors.accentText
        dimmed -> androidx.compose.ui.graphics.Color.Transparent to colors.textDisabled
        else -> androidx.compose.ui.graphics.Color.Transparent to colors.textPrimary
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = day.toString(),
            style = style.copy(fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium),
            color = ink,
        )
    }
}

/**
 * One event in a month cell. An all-day event is a filled bar — it holds the
 * whole day; a timed one is a dot, its start and its title, so a busy day
 * reads as a list rather than a stack of coloured blocks.
 */
@Composable
private fun EventChip(
    event: CalendarEvent,
    state: CalendarUiState,
    dragGrid: () -> DragGrid,
    onEvent: (CalendarEvent2Event) -> Unit,
    onDragging: (Boolean) -> Unit = {},
) {
    val colors = ZillitTheme.colors
    val tint = event.tint()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = when {
        event.isAllDay -> tint.copy(alpha = if (hovered) ALL_DAY_HOVER_ALPHA else CHIP_TINT_ALPHA)
        hovered -> colors.surfaceHover
        else -> androidx.compose.ui.graphics.Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .draggableEvent(event, dragGrid, onDragging) { days, _ ->
                onEvent(CalendarEvent2Event.MoveEvent(event, days, minuteDelta = 0))
            }
            .clip(ZillitTheme.shapes.small)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null) {
                onEvent(CalendarEvent2Event.OpenDetail(event))
            }
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = CHIP_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (event.isAllDay) {
            Box(Modifier.width(CHIP_STRIPE).height(CHIP_STRIPE_HEIGHT).clip(ZillitTheme.shapes.pill).background(tint))
        } else {
            Box(Modifier.size(CHIP_DOT).clip(CircleShape).background(tint))
            ZillitText(
                text = event.startMillis.chipTime(state.zone),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        ZillitText(
            text = event.title,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The selected day, in full. */
@Composable
private fun DayColumn(state: CalendarUiState, date: LocalDate, onEvent: (CalendarEvent2Event) -> Unit) {
    val colors = ZillitTheme.colors
    val events = state.eventsOn(date)
    val isToday = date == state.today

    Column(Modifier.fillMaxSize()) {
        DayHeader(date, isToday, events.size)
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(colors.divider))

        if (events.isEmpty()) {
            EmptyDay(onEvent)
            return@Column
        }

        val agendaState = rememberLazyListState()
        ZillitLazyColumn(
            state = agendaState,
            contentPadding = PaddingValues(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            items(events, key = CalendarEvent::id) { event ->
                EventRow(event, state) { onEvent(CalendarEvent2Event.OpenDetail(event)) }
            }
        }
    }
}

/** The panel's head: a calendar-leaf date tile, the weekday, the count. */
@Composable
private fun DayHeader(date: LocalDate, isToday: Boolean, count: Int) {
    val colors = ZillitTheme.colors
    val weekday = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(PAGE_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        // A tear-off calendar leaf: the date, big, in its own tile.
        Column(
            modifier = Modifier
                .size(DATE_TILE)
                .clip(ZillitTheme.shapes.large)
                .background(if (isToday) colors.accent else colors.surfaceSunken),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ZillitText(
                text = date.month.name.take(WEEKDAY_LETTERS).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = WEEKDAY_TRACKING,
                ),
                color = if (isToday) colors.textOnAccent else colors.accentText,
            )
            ZillitText(
                text = date.day.toString(),
                style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = if (isToday) colors.textOnAccent else colors.textPrimary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ZillitText(
                text = if (isToday) "${str(S.today)} · $weekday" else weekday,
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            ZillitText(
                text = count.let { n ->
                    if (n == 1) str(S.desktop_cal_one_event) else str(S.desktop_cal_event_count, n)
                },
                style = ZillitTheme.typography.label,
                color = colors.textMuted,
            )
        }
    }
}

/** A free day, said kindly, with the one thing worth doing about it. */
@Composable
private fun EmptyDay(onEvent: (CalendarEvent2Event) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(PAGE_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(EMPTY_ICON_TILE).clip(CircleShape).background(ZillitTheme.colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Calendar, tint = ZillitTheme.colors.textMuted, size = ZillitDimens.iconLarge)
        }
        ZillitText(
            text = str(S.desktop_cal_nothing_scheduled),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        ZillitButton(
            text = str(S.new_event),
            leadingIcon = ZillitIcons.Add,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            onClick = { onEvent(CalendarEvent2Event.OpenForm(null)) },
        )
    }
}

@Composable
private fun EventRow(event: CalendarEvent, state: CalendarUiState, onOpen: () -> Unit) {
    val colors = ZillitTheme.colors
    val tint = event.tint()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(ZillitTheme.shapes.large)
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .border(
                width = HAIRLINE,
                color = if (hovered) tint.copy(alpha = HOVER_BORDER_ALPHA) else colors.divider,
                shape = ZillitTheme.shapes.large,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
    ) {
        // The event's own colour down the leading edge.
        Box(Modifier.width(ROW_STRIPE).fillMaxHeight().background(tint))

        Column(
            modifier = Modifier.weight(1f).padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitIcon(icon = ZillitIcons.Clock, tint = tint, size = ROW_ICON)
                ZillitText(
                    text = event.timeLabel(state.zone),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (event.hasCall) {
                    ZillitIcon(icon = ZillitIcons.Monitor, tint = colors.textMuted, size = ROW_ICON)
                }
            }
            ZillitText(
                text = event.title,
                style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 2,
            )
            event.location?.takeIf { it.isNotBlank() }?.let { EventLocation(it) }
        }
    }
}

@Composable
private fun EventLocation(location: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon = ZillitToolIcons.Location, tint = ZillitTheme.colors.textMuted, size = ROW_ICON)
        ZillitText(
            text = location,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

private fun kotlinx.datetime.DayOfWeek.isWeekend() =
    this == kotlinx.datetime.DayOfWeek.SATURDAY || this == kotlinx.datetime.DayOfWeek.SUNDAY

private val PAGE_PADDING = 16.dp
private val CELL_PADDING = 6.dp
private val GRID_GAP = 1.dp
private val TODAY_CIRCLE = 24.dp
private val HEADER_DISC = 32.dp
private val DATE_TILE = 56.dp
private val EMPTY_ICON_TILE = 48.dp
private const val DAYS_IN_WEEK = 7
private val WEEKDAY_TRACKING = androidx.compose.ui.unit.TextUnit(
    1.2f,
    androidx.compose.ui.unit.TextUnitType.Sp,
)
private val DETAIL_WIDTH = 420.dp
private val HAIRLINE = 1.dp
private const val MAX_CHIPS = 3
private const val WEEKDAY_LETTERS = 3
private val CHIP_DOT = 7.dp
private val CHIP_GAP = 2.dp
private val CHIP_VERTICAL_PADDING = 2.dp
private val CHIP_STRIPE = 3.dp
private val CHIP_STRIPE_HEIGHT = 10.dp
private val ROW_STRIPE = 4.dp
private val ROW_ICON = 13.dp
private const val CHIP_TINT_ALPHA = 0.18f
private const val ALL_DAY_HOVER_ALPHA = 0.28f
private const val SELECTED_CELL_ALPHA = 0.06f
private const val WEEKEND_ALPHA = 0.55f
private const val HOVER_BORDER_ALPHA = 0.6f

/** "9:00" — the compact start time on a month chip. */
private fun Long.chipTime(zone: kotlinx.datetime.TimeZone): String {
    val time = kotlin.time.Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).time
    return "${time.hour}:${time.minute.toString().padStart(2, '0')}"
}

private val DIALOG_WIDTH = 420.dp
