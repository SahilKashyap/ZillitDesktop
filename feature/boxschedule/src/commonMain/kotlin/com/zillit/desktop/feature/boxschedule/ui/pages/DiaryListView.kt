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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.ListMode
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleDayRow
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DayEvent
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.PageEvent
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent

/** One line of the merged, chronological list — a schedule's day, a whole block, or an entry. */
private sealed interface ListItem {
    val sortKey: Long
    val key: String

    /** Within one day: the schedule first, then entries by their start. */
    val tieBreak: Long get() = Long.MIN_VALUE

    data class Day(val row: ScheduleDayRow, val first: Boolean) : ListItem {
        override val sortKey: Long get() = row.date
        override val key: String get() = "s-${row.key}"
    }

    data class Block(val block: ScheduleBlock) : ListItem {
        override val sortKey: Long get() = block.firstDay
        override val key: String get() = "b-${block.id}"
    }

    data class Entry(val entry: DiaryEvent) : ListItem {
        override val sortKey: Long get() = entry.date.takeIf { it > 0 } ?: entry.startDateTime
        override val key: String get() = "e-${entry.listKey}"
        override val tieBreak: Long get() = entry.startDateTime
    }
}

/**
 * The List view — `ScheduleTable`: By Date, one row per scheduled day that
 * opens to the day's detail, or By Schedule, one card per block. Events and
 * notes fall into the same timeline, as on the phones.
 */
@Composable
internal fun DiaryListView(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val page = state.page
    val byDate = page.listMode == ListMode.ByDate
    val items = remember(state.rows, state.blocks, state.events, page.listMode, page.filter, state.zone) {
        listItems(state)
    }
    val hasSchedules = state.rows.isNotEmpty() || state.blocks.isNotEmpty()

    ZillitLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 34.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (byDate) 0.dp else 12.dp),
    ) {
        when {
            !hasSchedules && state.events.isEmpty() -> item { NothingYet(state.mayEdit) }
            else -> {
                if (!page.selecting && hasSchedules) item(key = "modes") { ListModes(state, onEvent) }
                if (items.isEmpty()) {
                    item(key = "empty") { NothingToShow() }
                } else {
                    if (byDate) item(key = "head") { TableHead(state, onEvent) }
                    items(items, key = { it.key }) { item -> ListRow(item, byDate, state, onEvent) }
                    if (byDate) item(key = "foot") { TableFoot() }
                }
            }
        }
    }
}

/** Scheduled days (By Date) or blocks (By Schedule), with the events and notes, in date order. */
private fun listItems(state: BoxScheduleUiState): List<ListItem> {
    val filter = state.page.filter
    return buildList {
        if (state.page.listMode == ListMode.BySchedule) {
            filter.listBlocks(state.blocks, state.zone).forEach { add(ListItem.Block(it)) }
        } else if (filter.content.showsSchedules) {
            state.filteredRows.forEachIndexed { index, row -> add(ListItem.Day(row, first = index == 0)) }
        }
        filter.listEvents(state.events, state.zone).forEach { add(ListItem.Entry(it)) }
    }.sortedWith(compareBy<ListItem>({ it.sortKey }, { it.tieBreak }))
}

@Composable
private fun ListRow(item: ListItem, byDate: Boolean, state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    when (item) {
        is ListItem.Day -> DayRow(item, state, onEvent)
        is ListItem.Block -> BlockCard(item.block, state, onEvent)
        is ListItem.Entry -> if (byDate) {
            TableCell { EntryLine(item.entry, state, onEvent) }
        } else {
            EntryLine(item.entry, state, onEvent, card = true)
        }
    }
}

@Composable
private fun ListModes(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    Row(
        Modifier.padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Segments(
            options = ListMode.entries,
            selected = state.page.listMode,
            label = { it.label },
            onSelect = { onEvent(PageEvent.SetListMode(it)) },
        )
        DefaultViewMenu(
            buttonText = str(S.bs_set_default),
            description = str(S.dv_list_desc),
            options = ListMode.entries,
            current = state.page.defaultListMode,
            label = { it.label },
            hint = { it.hint },
            onChoose = { onEvent(PageEvent.SaveDefaultListMode(it)) },
        )
    }
}

// By Date ------------------------------------------------------------------

/** The table's rounded top with its column heads; the rows and a foot close the card. */
@Composable
private fun TableHead(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.page.selecting) {
            val rows = state.filteredRows
            val chosen = state.page.selected.size
            Box(Modifier.width(CHECK_COLUMN)) {
                ZillitCheckbox(
                    checked = rows.isNotEmpty() && chosen == rows.size,
                    onCheckedChange = { onEvent(if (it) PageEvent.SelectAll else PageEvent.DeselectAll) },
                )
            }
        }
        HeadText(str(S.bs_day), Modifier.width(DAY_COLUMN), TextAlign.Center)
        HeadText(str(S.date), Modifier.width(DATE_COLUMN))
        HeadText(str(S.type), Modifier.width(TYPE_COLUMN))
        HeadText(str(S.details), Modifier.weight(1f))
        if (!state.page.selecting) Box(Modifier.width(CHEVRON_COLUMN))
    }
}

@Composable
private fun HeadText(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
        color = ZillitTheme.colors.textMuted,
        textAlign = align,
        modifier = modifier,
    )
}

@Composable
private fun TableFoot() {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border, shape),
    )
}

/** A body cell of the table card: its side borders and the hairline under it. */
@Composable
private fun TableCell(content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .gridLines(colors.border, right = false, bottom = true)
            .sideLines(colors.border),
    ) { content() }
}

@Composable
private fun DayRow(item: ListItem.Day, state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val row = item.row
    val selecting = state.page.selecting
    val expanded = !selecting && state.page.expandedRow == row.key
    val past = state.isPast(row.date)
    TableCell {
        if (row.isNewBlock && !item.first) BlockSeparator()
        DayRowLine(row, state, expanded, onEvent)
        if (expanded) {
            Box(Modifier.fillMaxWidth().background(ZillitTheme.colors.canvas)) {
                Column {
                    ScheduleDayDetail(
                        block = row.block,
                        dayKey = row.date,
                        state = state,
                        mode = DetailMode(readOnly = past || !state.mayEdit),
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

/** Day number, date, type and details — a click selects in select mode and opens the day otherwise. */
@Composable
private fun DayRowLine(
    row: ScheduleDayRow,
    state: BoxScheduleUiState,
    expanded: Boolean,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val selecting = state.page.selecting
    val selected = row.key in state.page.selected
    val dayOff = row.block.isDayOff
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ground = when {
        selected -> colors.accentSoft
        expanded -> colors.surfaceSunken
        hovered -> colors.surfaceHover
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(ground)
            .hoverable(interaction)
            .clickable { onEvent(if (selecting) PageEvent.ToggleSelect(row.key) else PageEvent.ToggleRow(row.key)) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Box(Modifier.width(CHECK_COLUMN)) {
                ZillitCheckbox(checked = selected, onCheckedChange = { onEvent(PageEvent.ToggleSelect(row.key)) })
            }
        }
        ZillitText(
            text = if (dayOff) "—" else row.dayNumber.toString(),
            style = serif(15.sp, FontWeight.Bold),
            color = if (dayOff) colors.textDisabled else colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(DAY_COLUMN),
        )
        ZillitText(
            text = DiaryFormat.listDate(row.date, state.zone),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (state.isPast(row.date)) colors.textMuted else colors.textSecondary,
            modifier = Modifier.width(DATE_COLUMN),
        )
        Box(Modifier.width(TYPE_COLUMN)) {
            TypeChip(if (dayOff) str(S.desktop_day_type_day_off) else row.block.typeName, row.block.color, upper = true)
        }
        ZillitText(
            text = row.block.title.ifBlank { "—" },
            style = ZillitTheme.typography.bodySmall.copy(
                fontStyle = if (row.block.title.isBlank()) FontStyle.Italic else FontStyle.Normal,
            ),
            color = colors.textMuted,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        if (!selecting) Chevron(expanded)
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    Box(
        Modifier
            .width(CHEVRON_COLUMN)
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (expanded) ZillitTheme.colors.border else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = if (expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
            tint = ZillitTheme.colors.textMuted,
            size = 14.dp,
        )
    }
}

/** Where one type's run ends and the next begins — the web's fading rule. */
@Composable
private fun BlockSeparator() {
    val line = ZillitTheme.colors.textDisabled
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, line, line, Color.Transparent))),
    )
}

// By Schedule --------------------------------------------------------------

@Composable
private fun BlockCard(block: ScheduleBlock, state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val allPast = block.sortedDays.all { state.isPast(DiaryCalendar.dayKey(it, state.zone)) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BlockCardBody(block, state, allPast)
            if (!allPast && state.mayEdit) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowAction(
                        str(S.edit),
                        ZillitIcons.Edit,
                        onClick = { onEvent(ScheduleEvent.EditSchedule(block.id)) },
                    )
                    RowAction(
                        str(S.bs_delete_script),
                        ZillitIcons.Trash,
                        onClick = { onEvent(ScheduleEvent.AskDeleteBlock(block.id)) },
                        danger = true,
                    )
                }
            }
        }
        val facts = buildList {
            if (block.createdAt > 0) {
                add(str(S.desktop_fs_created_on, DiaryFormat.mediumDate(block.createdAt, state.zone)))
            }
            if (block.version > 1) add(str(S.desktop_bs_version_n, block.version))
        }
        if (facts.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                facts.forEach { ZillitText(it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted) }
            }
        }
    }
}

/** A schedule card's facts: type, length, title, range, and a tag per date. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowScope.BlockCardBody(block: ScheduleBlock, state: BoxScheduleUiState, allPast: Boolean) {
    val colors = ZillitTheme.colors
    val days = block.sortedDays
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeChip(block.typeName.ifBlank { str(S.desktop_unknown) }, block.color)
            ZillitText(
                str(S.desktop_bs_n_days, days.size),
                style = ZillitTheme.typography.label,
                color = colors.textMuted,
            )
            if (allPast) SmallBadge(str(S.desktop_bs_past))
        }
        if (block.title.isNotBlank()) {
            ZillitText(
                block.title,
                style = ZillitTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = colors.textPrimary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitIcon(icon = ZillitIcons.Calendar, tint = colors.textMuted, size = 13.dp)
            ZillitText(
                DiaryFormat.blockRange(block, state.zone),
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            days.take(MAX_DATE_TAGS).forEach { day -> DateTag(day, state, swatchColor(block.color)) }
            if (days.size > MAX_DATE_TAGS) SmallBadge(str(S.desktop_n_more, days.size - MAX_DATE_TAGS))
        }
    }
}

@Composable
private fun DateTag(day: Long, state: BoxScheduleUiState, color: Color) {
    val colors = ZillitTheme.colors
    val key = DiaryCalendar.dayKey(day, state.zone)
    val today = key == state.todayKey
    val past = state.isPast(key)
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    today -> colors.accent
                    past -> colors.surfaceSunken
                    else -> color.tint(DATE_TAG_TINT)
                },
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        ZillitText(
            DiaryFormat.monthDay(day, state.zone),
            style = ZillitTheme.typography.labelSmall,
            color = when {
                today -> colors.textOnAccent
                past -> colors.textMuted
                else -> colors.textPrimary
            },
        )
    }
}

// Entries ------------------------------------------------------------------

/** An event or note in the timeline: what it is, when, and View / Edit / Remove. */
@Composable
private fun EntryLine(
    entry: DiaryEvent,
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
    card: Boolean = false,
) {
    val colors = ZillitTheme.colors
    val accent = hexColor(entry.color) ?: EVENT_BLUE
    val past = state.isPast(DiaryCalendar.dayKey(entry.anchor, state.zone))
    val shape = RoundedCornerShape(10.dp)
    val frame = if (card) Modifier.clip(shape).background(colors.surface).border(
        1.dp,
        colors.border,
        shape,
    ) else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .then(frame)
            .clickable { onEvent(DayEvent.ViewEntry(entry.listKey)) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(4.dp).height(54.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        EntryLineText(entry, state, accent)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RowAction(str(S.view), ZillitIcons.Eye, onClick = { onEvent(DayEvent.ViewEntry(entry.listKey)) })
            if (!past && state.mayEdit) {
                RowAction(str(S.edit), ZillitIcons.Edit, onClick = { onEvent(EntryEvent.EditEntry(entry.listKey)) })
                RowAction(
                    str(S.remove),
                    ZillitIcons.Trash,
                    onClick = { onEvent(EntryEvent.AskDelete(entry.listKey)) },
                    danger = true,
                )
            }
        }
    }
}

@Composable
private fun RowScope.EntryLineText(entry: DiaryEvent, state: BoxScheduleUiState, accent: Color) {
    val colors = ZillitTheme.colors
    val isEvent = entry.kind == DiaryKind.Event
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZillitText(
            text = if (isEvent) str(S.desktop_bs_event_upper) else str(S.dd_label_note),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            ),
            color = accent,
        )
        val time = if (isEvent) DiaryFormat.timeRange(entry, state.zone) else ""
        if (time.isNotBlank()) ZillitText(time, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        ZillitText(
            entry.title.ifBlank { "(untitled)" },
            style = ZillitTheme.typography.titleSmall,
            color = hexColor(entry.textColor) ?: colors.textPrimary,
        )
        if (entry.anchor > 0) {
            ZillitText(
                DiaryFormat.shortDay(entry.anchor, state.zone),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        if (entry.location.isNotBlank()) LocationLink(entry)
        if (entry.body.isNotBlank()) {
            ZillitText(entry.body, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 3)
        }
    }
}

// Empty --------------------------------------------------------------------

@Composable
private fun NothingYet(mayEdit: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitText(
            str(S.no_schedule_title),
            style = ZillitTheme.typography.titleMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(
            if (mayEdit) {
                str(S.desktop_bs_click_create_schedule_hint)
            } else {
                str(S.desktop_bs_no_schedules_created)
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun NothingToShow() {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitText(
            str(S.desktop_nothing_to_show),
            style = ZillitTheme.typography.titleSmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(
            str(S.desktop_bs_adjust_filter_or_add),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** The table card's left and right edges, drawn behind a row. */
private fun Modifier.sideLines(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), stroke)
    drawLine(color, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
}

private val CHECK_COLUMN = 44.dp
private val DAY_COLUMN = 60.dp
private val DATE_COLUMN = 150.dp
private val TYPE_COLUMN = 170.dp
private val CHEVRON_COLUMN = 40.dp
private const val MAX_DATE_TAGS = 14
private const val DATE_TAG_TINT = 0.12f
