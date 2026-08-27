package com.zillit.desktop.feature.boxschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.boxschedule.domain.DiaryClock
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.ScheduleDayRow
import com.zillit.desktop.feature.boxschedule.ui.pages.BlockEditorDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.DiaryEditorDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.TypesDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.hexColor

/**
 * The production diary: one row per scheduled date, its type badge and
 * running number, and the events and notes on that day.
 */
@Composable
fun BoxScheduleScreen(
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
    mayCall: Boolean = false,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Chrome(state = state, onEvent = onEvent)
            when {
                state.loading && state.rows.isEmpty() ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
                state.rows.isEmpty() && state.orphanEvents.isEmpty() -> ZillitText(
                    text = "No schedule yet — add the first block of days.",
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textMuted,
                )
                else -> DiaryList(state = state, onEvent = onEvent, mayCall = mayCall)
            }
        }
        state.blockEditor?.let { BlockEditorDialog(state = state, editor = it, onEvent = onEvent) }
        state.diaryEditor?.let { DiaryEditorDialog(state = state, editor = it, onEvent = onEvent) }
        if (state.manageTypes) TypesDialog(state = state, onEvent = onEvent)
    }
}

@Composable
private fun Chrome(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitPageHeader(
        title = "Box Schedule",
        description = "The production diary — shoot days, prep, travel and what happens on each.",
        actions = {
            if (state.viewer.mayEdit) {
                ZillitButton(
                    text = "Types",
                    onClick = { onEvent(BoxScheduleEvent.OpenTypes) },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitButton(
                    text = "Add note",
                    onClick = { onEvent(BoxScheduleEvent.NewDiary(DiaryKind.Note, null)) },
                    variant = ButtonVariant.Secondary,
                )
                ZillitButton(
                    text = "Add event",
                    onClick = { onEvent(BoxScheduleEvent.NewDiary(DiaryKind.Event, null)) },
                    variant = ButtonVariant.Secondary,
                )
                ZillitButton(
                    text = "Add schedule",
                    onClick = { onEvent(BoxScheduleEvent.NewBlock) },
                    loading = state.busy,
                )
            }
        },
    )
    if (state.viewer.isBlocked) {
        ZillitNotice(text = "You do not have access to the box schedule.")
    }
    state.error?.let { message ->
        ZillitNotice(
            text = message,
            tone = StatusTone.Rejected,
            action = {
                ZillitButton(
                    text = "Dismiss",
                    onClick = { onEvent(BoxScheduleEvent.DismissError) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            },
        )
    }
}

@Composable
private fun DiaryList(
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
    mayCall: Boolean,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(state.rows, key = { "${it.block.id}:${it.date}" }) { row ->
            DayRow(state = state, row = row, onEvent = onEvent, mayCall = mayCall)
        }
        val orphans = state.orphanEvents
        if (orphans.isNotEmpty()) {
            item {
                ZillitText(
                    text = "OTHER DATES",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.padding(top = ZillitTheme.spacing.md),
                )
            }
            items(orphans, key = { "orphan:${it.listKey}" }) { event ->
                ZillitSectionCard {
                    EventLine(
                        state = state,
                        event = event,
                        onEvent = onEvent,
                        showDate = true,
                        mayCall = mayCall,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod") // One row: date column, events column, action strip.
private fun DayRow(
    state: BoxScheduleUiState,
    row: ScheduleDayRow,
    onEvent: (BoxScheduleEvent) -> Unit,
    mayCall: Boolean,
) {
    val colors = ZillitTheme.colors
    ZillitSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(Modifier.width(DATE_COLUMN)) {
                ZillitText(text = DiaryClock.dayLabel(row.date), style = ZillitTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    Box(
                        Modifier
                            .size(SWATCH)
                            .clip(RoundedCornerShape(3.dp))
                            .background(hexColor(row.block.color) ?: colors.accent),
                    )
                    ZillitText(
                        text = "${row.block.typeName} ${row.dayNumber}",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                if (row.block.title.isNotBlank()) {
                    ZillitText(
                        text = row.block.title,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                val onDay = state.eventsOn(row.date)
                if (onDay.isEmpty()) {
                    ZillitText(
                        text = "Nothing scheduled",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                onDay.forEach { event ->
                    EventLine(
                        state = state,
                        event = event,
                        onEvent = onEvent,
                        showDate = false,
                        mayCall = mayCall,
                    )
                }
            }
            if (state.viewer.mayEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitButton(
                        text = "+",
                        onClick = { onEvent(BoxScheduleEvent.NewDiary(DiaryKind.Event, row.date, row.block.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                    ZillitButton(
                        text = "Edit",
                        onClick = { onEvent(BoxScheduleEvent.EditBlock(row.block.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                    ZillitButton(
                        text = "Remove",
                        onClick = { onEvent(BoxScheduleEvent.DeleteBlockDate(row.block.id, row.date)) },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventLine(
    state: BoxScheduleUiState,
    event: DiaryEvent,
    onEvent: (BoxScheduleEvent) -> Unit,
    showDate: Boolean,
    mayCall: Boolean,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            Modifier
                .size(SWATCH)
                .clip(RoundedCornerShape(SWATCH))
                .background(hexColor(event.color) ?: colors.accent),
        )
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = event.title.ifBlank { if (event.kind == DiaryKind.Note) "Note" else "Event" },
                style = ZillitTheme.typography.bodyMedium,
            )
            val meta = buildList {
                if (showDate) add(DiaryClock.dayLabel(event.date))
                if (event.kind == DiaryKind.Note) {
                    add("note")
                } else if (!event.fullDay) {
                    add("${DiaryClock.hm(event.startDateTime)}–${DiaryClock.hm(event.endDateTime)}")
                } else {
                    add("all day")
                }
                if (event.location.isNotBlank()) add(event.location)
                if (event.isRecurring) add("repeats")
                if (event.calendarSourced) add("from calendar")
            }
            ZillitText(
                text = meta.joinToString("  ·  "),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        EventActions(state, event, mayCall, onEvent)
    }
}

/**
 * The buttons on one event row.
 *
 * Join sits OUTSIDE the edit gate deliberately: read-only crew reach this
 * screen, and joining a call is not editing the schedule.
 */
@Composable
private fun EventActions(
    state: BoxScheduleUiState,
    event: DiaryEvent,
    mayCall: Boolean,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    if (mayCall && event.isCallJoinable && event.hasCallRoom) {
        ZillitButton(
            text = "Join call",
            onClick = { onEvent(BoxScheduleEvent.JoinCall(event.listKey)) },
            variant = ButtonVariant.Primary,
            size = ButtonSize.Small,
        )
    }
    if (state.viewer.mayEdit && !event.calendarSourced) {
        ZillitButton(
            text = "Edit",
            onClick = { onEvent(BoxScheduleEvent.EditDiary(event.listKey)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = "Delete",
            onClick = { onEvent(BoxScheduleEvent.DeleteDiary(event.listKey)) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
        )
    }
}

private val DATE_COLUMN = 180.dp
private val SWATCH = 12.dp
