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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.isPersonalNote
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DayEvent
import com.zillit.desktop.feature.boxschedule.ui.DayFocus
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent
import kotlinx.datetime.LocalDate

/**
 * A month cell's schedule pills — four at most; past that, three and a
 * "+N more" that lists them all. A pill opens the day drawer on that
 * schedule; its bin, shown on hover, removes this date from it.
 */
@Composable
internal fun CellSchedules(
    dayKey: Long,
    date: LocalDate,
    schedules: List<ScheduleBlock>,
    past: Boolean,
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
    max: Int,
) {
    if (schedules.isEmpty()) return
    val over = schedules.size > max
    val visible = if (over) schedules.take(max - 1) else schedules
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.forEach { block ->
            SchedulePill(
                block = block,
                dayKey = dayKey,
                state = state,
                deletable = state.mayEdit && !past,
                onOpen = { onEvent(DayEvent.OpenDay(dayKey, DayFocus.Schedule(block.id))) },
                onDelete = { onEvent(ScheduleEvent.AskDeleteDay(block.id, dayKey)) },
            )
        }
        if (over) MoreSchedules(dayKey, date, schedules, hidden = schedules.size - visible.size, state, onEvent)
    }
}

/** "+N more" — every schedule on the day in a popover, each opening the drawer on itself. */
@Composable
private fun MoreSchedules(
    dayKey: Long,
    date: LocalDate,
    schedules: List<ScheduleBlock>,
    hidden: Int,
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        MoreChip("+ $hidden more", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val heading = str(S.desktop_bs_n_schedules, schedules.size)
            PopoverFrame(date, heading, onViewAll = {
                open = false
                onEvent(DayEvent.OpenDay(dayKey))
            }) {
                schedules.forEach { block ->
                    PopoverRow(onClick = {
                        open = false
                        onEvent(DayEvent.OpenDay(dayKey, DayFocus.Schedule(block.id)))
                    }) {
                        Dot(swatchColor(block.color), size = 10.dp, square = true)
                        PopoverSchedule(block, DiaryCalendar.dayIndex(block, dayKey, state.zone)?.first)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.PopoverSchedule(block: ScheduleBlock, dayNumber: Int?) {
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitText(
                block.typeName.ifBlank { str(S.schedule) },
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            )
            dayNumber?.let { DayBadge(it, block.color) }
        }
        if (block.title.isNotBlank()) {
            ZillitText(
                block.title,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SchedulePill(
    block: ScheduleBlock,
    dayKey: Long,
    state: BoxScheduleUiState,
    deletable: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val color = swatchColor(block.color)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(color.tint(if (hovered) PILL_HOVER else PILL))
            .border(1.dp, color.tint(if (hovered) PILL_BORDER_HOVER else PILL_BORDER), RoundedCornerShape(7.dp))
            .hoverable(interaction)
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(color, size = 8.dp)
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ZillitText(
                text = block.typeName,
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            DiaryCalendar.dayIndex(block, dayKey, state.zone)?.let { (n, _) -> DayBadge(n, block.color) }
        }
        if (deletable) {
            // Always composed, revealed by alpha: a control added on hover never receives the press.
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .alpha(if (hovered) 1f else 0f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ZillitTheme.colors.surface)
                    .border(1.dp, ZillitTheme.colors.border, RoundedCornerShape(4.dp))
                    .clickable(enabled = hovered, onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.desktop_bs_delete_day_from_schedule),
                    tint = ZillitTheme.colors.danger,
                    size = 10.dp,
                )
            }
        }
    }
}

@Composable
private fun DayBadge(n: Int, hex: String) {
    ZillitText(
        text = str(S.desktop_ad_day_number, n),
        style = ZillitTheme.typography.labelSmall.copy(
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
        ),
        color = swatchColor(hex),
        maxLines = 1,
    )
}

/**
 * A cell's events, in one bordered group so they read apart from the notes.
 * A single overflowing event is shown rather than hidden behind a chip that
 * would take the same row.
 */
@Composable
internal fun CellEvents(
    dayKey: Long,
    date: LocalDate,
    events: List<DiaryEvent>,
    noteCount: Int,
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
    max: Int,
) {
    if (events.isEmpty()) return
    val over = events.size > max + 1
    val visible = if (over) events.take(max) else events
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, ZillitTheme.colors.border, RoundedCornerShape(7.dp))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        visible.forEach { event ->
            CellRow(
                onClick = { onEvent(DayEvent.ViewEntry(event.listKey)) },
                hoverColor = hexColor(event.color) ?: EVENT_BLUE,
            ) {
                Dot(hexColor(event.color) ?: EVENT_BLUE, size = 6.dp)
                ZillitText(
                    text = event.title,
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                )
            }
        }
        if (over) {
            MoreEventsPopover(
                text = str(S.desktop_bs_plus_n_more, events.size - visible.size),
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

/**
 * A cell's notes themselves, not a count — three, then "+N more notes" with
 * every note of the day, Personal Notes first.
 */
@Composable
internal fun CellNotes(dayKey: Long, date: LocalDate, notes: List<DiaryEvent>, onEvent: (BoxScheduleEvent) -> Unit) {
    if (notes.isEmpty()) return
    val colors = ZillitTheme.colors
    val visible = notes.take(MAX_CELL_NOTES)
    val hidden = notes.size - visible.size
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, colors.warning.tint(NOTE_BORDER), RoundedCornerShape(7.dp))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        visible.forEach { note ->
            CellRow(onClick = { onEvent(DayEvent.ViewEntry(note.listKey)) }, hoverColor = colors.warning) {
                ZillitIcon(icon = ZillitIcons.Edit, tint = colors.warning, size = 9.dp)
                ZillitText(
                    text = noteLabel(note),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
        }
        if (hidden > 0) {
            var open by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.warningSoft)
                        .border(1.dp, colors.warning.tint(NOTE_BORDER), RoundedCornerShape(8.dp))
                        .clickable { open = true }
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitIcon(icon = ZillitIcons.Edit, tint = colors.warning, size = 10.dp)
                    ZillitText(
                        text = "+$hidden more ${if (hidden > 1) "notes" else "note"}",
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.warning,
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    MoreNotes(date, notes, onView = {
                        open = false
                        onEvent(DayEvent.ViewEntry(it))
                    }, onViewAll = {
                        open = false
                        onEvent(DayEvent.OpenDay(dayKey, DayFocus.Notes))
                    })
                }
            }
        }
    }
}

@Composable
private fun MoreNotes(
    date: LocalDate,
    notes: List<DiaryEvent>,
    onView: (String) -> Unit,
    onViewAll: () -> Unit,
) {
    val (personal, general) = notes.partition { it.isPersonalNote }
    PopoverFrame(date, str(S.desktop_bs_n_notes, notes.size), onViewAll = onViewAll) {
        if (personal.isNotEmpty()) GroupHeading(str(S.bs_pdf_notes_label))
        personal.forEach { NoteRow(it, onView) }
        if (general.isNotEmpty()) GroupHeading(str(S.ce_note_type_general))
        general.forEach { NoteRow(it, onView) }
    }
}

@Composable
private fun NoteRow(note: DiaryEvent, onView: (String) -> Unit) {
    PopoverRow(onClick = { onView(note.listKey) }) {
        ZillitIcon(icon = ZillitIcons.Edit, tint = ZillitTheme.colors.warning, size = 11.dp)
        Column(Modifier.weight(1f)) {
            ZillitText(
                note.title.ifBlank { str(S.desktop_bs_untitled_note) },
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (note.body.isNotBlank()) {
                ZillitText(
                    note.body,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 2,
                )
            }
        }
    }
}

/** One event in the "+N more" popover: its colour stripe, time, title and place. */
@Composable
private fun PopoverEventRow(event: DiaryEvent, state: BoxScheduleUiState, onClick: () -> Unit) {
    PopoverRow(onClick = onClick) {
        Box(Modifier.width(3.dp).heightIn(min = 30.dp).background(hexColor(event.color) ?: EVENT_BLUE))
        Column(Modifier.weight(1f)) {
            val time = if (event.fullDay) {
                str(S.desktop_bs_full_day_upper)
            } else {
                DiaryFormat.timeRange(event, state.zone)
            }
            if (time.isNotBlank()) {
                ZillitText(
                    time,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            ZillitText(
                event.title.ifBlank { str(S.desktop_cal_untitled_event) },
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (event.location.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZillitIcon(
                        icon = ZillitIcons.Pin,
                        tint = ZillitTheme.colors.textMuted,
                        size = 10.dp,
                    )
                    ZillitText(
                        event.location,
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "+N more" on a day's events: every event of the day with its time and place. */
@Composable
internal fun MoreEventsPopover(
    text: String,
    date: LocalDate,
    events: List<DiaryEvent>,
    noteCount: Int,
    state: BoxScheduleUiState,
    onView: (String) -> Unit,
    onViewAll: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        MoreChip(text, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val count = str(S.desktop_bs_n_events, events.size) +
                if (noteCount > 0) " · " + str(S.desktop_bs_n_notes, noteCount) else ""
            PopoverFrame(date, count, onViewAll = {
                open = false
                onViewAll()
            }) {
                events.forEach { event ->
                    PopoverEventRow(event, state) {
                        open = false
                        onView(event.listKey)
                    }
                }
            }
        }
    }
}

/** A popover's frame: the day, a count, the rows, and a way to the full day. */
@Composable
private fun PopoverFrame(date: LocalDate, count: String, onViewAll: () -> Unit, rows: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.width(POPOVER_WIDTH)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Caption(DiaryFormat.weekday(date))
            ZillitText(DiaryFormat.fullDate(date), style = serif(15.sp, FontWeight.Bold), color = colors.textPrimary)
            ZillitText(count, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        Box(Modifier.fillMaxWidth().background(colors.border).heightIn(min = 1.dp, max = 1.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = POPOVER_LIST)
                .zillitVerticalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            rows()
        }
        Box(Modifier.fillMaxWidth().background(colors.border).heightIn(min = 1.dp, max = 1.dp))
        Box(Modifier.fillMaxWidth().padding(10.dp)) {
            ZillitButton(
                text = str(S.desktop_bs_view_full_day_details),
                onClick = onViewAll,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PopoverRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun GroupHeading(text: String) {
    Caption(text, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp))
}

/** One clickable line in a cell, its border lit on hover so the target is plain. */
@Composable
private fun CellRow(onClick: () -> Unit, hoverColor: Color, content: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .border(1.dp, if (hovered) hoverColor else Color.Transparent, RoundedCornerShape(5.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** The web's `noteCellLabel`: the title, trimmed, or "Untitled note"; a hard cap keeps a runaway title out. */
internal fun noteLabel(note: DiaryEvent): String {
    val title = note.title.trim().ifEmpty { str(S.desktop_bs_untitled_note) }
    return if (title.length > NOTE_CHAR_LIMIT) title.take(NOTE_CHAR_LIMIT).trimEnd() + "…" else title
}

private val POPOVER_WIDTH = 300.dp
private val POPOVER_LIST = 280.dp
private const val MAX_CELL_NOTES = 3
private const val NOTE_CHAR_LIMIT = 60
private const val PILL = 0.08f
private const val PILL_HOVER = 0.14f
private const val PILL_BORDER = 0.19f
private const val PILL_BORDER_HOVER = 0.33f
private const val NOTE_BORDER = 0.45f
