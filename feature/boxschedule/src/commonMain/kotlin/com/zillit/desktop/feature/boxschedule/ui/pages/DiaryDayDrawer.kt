package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.filedOn
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DayDrawer
import com.zillit.desktop.feature.boxschedule.ui.DayEvent
import com.zillit.desktop.feature.boxschedule.ui.DayFocus
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent

/**
 * The calendar's day drawer — scoped to what was clicked. A schedule pill
 * shows that schedule alone; the notes group shows the notes; a date shows
 * the whole day, with "Add to this day" at the top of it.
 */
@Composable
internal fun DayDrawerSheet(
    state: BoxScheduleUiState,
    drawer: DayDrawer,
    onEvent: (BoxScheduleEvent) -> Unit,
    mayCall: Boolean,
) {
    val data = rememberCalendarData(state)
    val dayKey = drawer.dayKey
    val content = drawerContent(state, data, drawer)
    val readOnly = state.isPast(dayKey) || !state.mayEdit

    DiarySheet(
        title = DiaryFormat.longDate(dayKey, state.zone),
        onDismiss = { onEvent(DayEvent.CloseDay) },
        width = DRAWER_WIDTH,
        padded = false,
        subtitle = if (content.shown.isEmpty()) null else { { TypeChips(content.shown) } },
    ) {
        if (content.focusActive) FocusBar(focusedName = content.focused?.typeName, onEvent = onEvent)
        if (!readOnly) AddToDayBar(dayKey, content, onEvent)
        content.shown.forEach { block ->
            ScheduleDayDetail(
                block = block,
                dayKey = dayKey,
                state = state,
                mode = DetailMode(readOnly = readOnly, hideInlineCreate = true, hideEvents = content.notesFocus),
                onEvent = onEvent,
            )
        }
        LooseEntries(state, content, readOnly, mayCall, onEvent)
        if (content.isEmpty) EmptyDay(DiaryFormat.fullDate(dayKey, state.zone))
    }
}

/** What a drawer shows, worked out once from the calendar's buckets and the drawer's focus. */
private class DrawerContent(
    val onDay: List<ScheduleBlock>,
    val focused: ScheduleBlock?,
    val notesFocus: Boolean,
    val shown: List<ScheduleBlock>,
    /** Events and notes on the day that no schedule on it owns. */
    val looseEvents: List<DiaryEvent>,
    val looseNotes: List<DiaryEvent>,
) {
    val focusActive: Boolean get() = focused != null || notesFocus
    val isEmpty: Boolean get() = onDay.isEmpty() && looseEvents.isEmpty() && looseNotes.isEmpty()
}

private fun drawerContent(state: BoxScheduleUiState, data: CalendarData, drawer: DayDrawer): DrawerContent {
    val dayKey = drawer.dayKey
    val onDay = data.schedules[dayKey].orEmpty()
    val focused = (drawer.focus as? DayFocus.Schedule)?.let { focus -> onDay.firstOrNull { it.id == focus.blockId } }
    val notesFocus = drawer.focus == DayFocus.Notes
    val ownIds = onDay.map { it.id }.toSet()
    val shown = when {
        focused != null -> listOf(focused)
        // The notes group lists only the schedules that carry a note filed on this day.
        notesFocus -> onDay.filter { block ->
            state.events.any {
                it.kind == DiaryKind.Note && it.scheduleDayId == block.id && it.filedOn(state.zone) == dayKey
            }
        }
        else -> onDay
    }
    return DrawerContent(
        onDay = onDay,
        focused = focused,
        notesFocus = notesFocus,
        shown = shown,
        looseEvents = data.events[dayKey].orEmpty().filter { it.scheduleDayId !in ownIds },
        looseNotes = data.notes[dayKey].orEmpty().filter { it.scheduleDayId !in ownIds },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(blocks: List<ScheduleBlock>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { TypeChip(it.typeName, it.color) }
    }
}

/** The standalone events and the day's own notes — hidden while the drawer is focused elsewhere. */
@Composable
private fun LooseEntries(
    state: BoxScheduleUiState,
    content: DrawerContent,
    readOnly: Boolean,
    mayCall: Boolean,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    if (!content.focusActive && content.looseEvents.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            DetailSection("Standalone Events", ZillitIcons.Clock, content.looseEvents.size, null, {}, "") {
                content.looseEvents.forEach {
                    EntryRow(it, state, readOnly = readOnly, onEvent = onEvent, mayCall = mayCall)
                }
            }
        }
    }
    if (content.focused == null && content.looseNotes.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            DetailSection("Notes", ZillitIcons.Edit, content.looseNotes.size, null, {}, "") {
                NoteGroups(content.looseNotes) {
                    EntryRow(it, state, readOnly = readOnly, onEvent = onEvent, mayCall = false)
                }
            }
        }
    }
}

@Composable
private fun EmptyDay(date: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitText(
            "No schedule on this day",
            style = ZillitTheme.typography.titleSmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(date, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

@Composable
private fun FocusBar(focusedName: String?, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitText(
            text = focusedName?.let { "Showing only $it on this day" } ?: "Showing only notes on this day",
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            "Show full day",
            onClick = { onEvent(DayEvent.ShowFullDay) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/**
 * "Add to this day" — every create for this date. A focused drawer keeps
 * the one that matches it; more than one schedule offers to delete them all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddToDayBar(dayKey: Long, content: DrawerContent, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val focusActive = content.focusActive
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("Add to this day", modifier = Modifier.weight(1f))
            if (!focusActive && content.onDay.size > 1) {
                ZillitButton(
                    text = "Delete all schedules",
                    onClick = { onEvent(ScheduleEvent.AskDeleteAllOn(dayKey)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!focusActive || content.focused != null) {
                CreateButton("Create Schedule", ZillitIcons.Calendar) { onEvent(ScheduleEvent.NewSchedule(dayKey)) }
            }
            if (!focusActive) {
                CreateButton("Create Event", ZillitIcons.Clock) {
                    onEvent(EntryEvent.NewEntry(DiaryKind.Event, dayKey))
                }
            }
            if (!focusActive || content.notesFocus) {
                CreateButton("Create Note", ZillitIcons.Edit) { onEvent(EntryEvent.NewEntry(DiaryKind.Note, dayKey)) }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** The warm outlined create button the drawer and details use. */
@Composable
internal fun CreateButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.accent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon = icon, tint = colors.accentText, size = 14.dp)
        ZillitText(
            text,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = colors.accentText,
        )
    }
}

/** An empty future date: "This date has no schedule or events. What would you like to create?" */
@Composable
internal fun QuickActionDialog(state: BoxScheduleUiState, dayKey: Long, onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitDialogShell(
        title = DiaryFormat.longDate(dayKey, state.zone),
        onDismiss = { onEvent(DayEvent.CloseQuickAction) },
        visible = true,
        width = 400.dp,
    ) {
        ZillitText(
            "This date has no schedule or events. What would you like to create?",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
        QuickChoice(ZillitIcons.Calendar, "Create Schedule", "Add a Prep, Shoot, Wrap, Day Off, or Travel day") {
            onEvent(ScheduleEvent.NewSchedule(dayKey))
        }
        QuickChoice(ZillitIcons.Clock, "Create Event", "Add a meeting, call, or activity with time and location") {
            onEvent(EntryEvent.NewEntry(DiaryKind.Event, dayKey))
        }
        QuickChoice(ZillitIcons.Edit, "Create Note", "Add a quick note or reminder for this day") {
            onEvent(EntryEvent.NewEntry(DiaryKind.Note, dayKey))
        }
    }
}

@Composable
private fun QuickChoice(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitIcon(icon = icon, tint = colors.accentText, size = 16.dp)
        Column {
            ZillitText(title, style = ZillitTheme.typography.titleSmall)
            ZillitText(hint, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

private val DRAWER_WIDTH = 640.dp
