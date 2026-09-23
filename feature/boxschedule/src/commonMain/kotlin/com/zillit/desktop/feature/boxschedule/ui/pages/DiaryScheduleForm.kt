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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DateTab
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.RangeMode
import com.zillit.desktop.feature.boxschedule.domain.ScheduleDates
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.NewTypeDraft
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.ScheduleForm
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * "Add New Schedule" / "Edit Schedule" / "Edit Day" — `CreateScheduleModal`.
 * A type, then the dates one of three ways; a one-day edit instead locks
 * the date and asks how a new type should take it.
 */
@Composable
internal fun ScheduleFormSheet(state: BoxScheduleUiState, form: ScheduleForm, onEvent: (BoxScheduleEvent) -> Unit) {
    DiarySheet(
        title = when {
            form.isSingleDay -> str(S.desktop_bs_edit_day_upper)
            form.isEdit -> str(S.desktop_bs_edit_schedule_upper)
            else -> str(S.desktop_bs_add_new_schedule_upper)
        },
        onDismiss = { onEvent(ScheduleEvent.CloseForm) },
        width = FORM_WIDTH,
        footer = {
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(ScheduleEvent.CloseForm) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = if (form.isEdit) str(S.dm_setup_save) else str(S.txt_save_schedule),
                onClick = { onEvent(ScheduleEvent.Save) },
                enabled = form.canSave,
                loading = form.saving,
            )
        },
    ) {
        TypeField(state, form, onEvent)
        if (form.isSingleDay) {
            SingleDayChoice(state, form, onEvent)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(str(S.cs_how_to_set_dates))
                ZillitTabStrip(
                    tabs = DateTab.entries.map { ZillitTab(it.name, it.label) },
                    activeId = form.tab.name,
                    onSelect = { id ->
                        DateTab.entries.firstOrNull { it.name == id }?.let { onEvent(ScheduleEvent.SetTab(it)) }
                    },
                )
            }
            TabHint(form.tab.description)
            when (form.tab) {
                DateTab.DateRange -> DateRangeTab(state, form, onEvent)
                DateTab.Calendar -> CalendarTab(state, form, onEvent)
                DateTab.DayWise -> DayWiseTab(state, form, onEvent)
            }
            Summary(form)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.details), note = str(S.optional))
            ZillitTextField(
                value = form.title,
                onValueChange = { onEvent(ScheduleEvent.SetTitle(it)) },
                placeholder = str(S.desktop_bs_schedule_details_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TypeField(state: BoxScheduleUiState, form: ScheduleForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(str(S.type), required = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiaryDropdown(
                selected = state.types.firstOrNull { it.id == form.typeId },
                options = state.types,
                onSelect = { type: ScheduleType -> onEvent(ScheduleEvent.SetType(type.id)) },
                label = { it.title },
                placeholder = str(S.select_page_type),
                leading = { Dot(swatchColor(it.color), size = 10.dp, square = true) },
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, if (form.newType != null) colors.accent else colors.border, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIconButton(
                    icon = ZillitIcons.Add,
                    contentDescription = str(S.desktop_bs_add_new_type),
                    onClick = { onEvent(ScheduleEvent.OpenNewType) },
                )
            }
        }
        form.newType?.let { NewTypeCard(it, onEvent) }
    }
}

/** The inline "+" type: a name and a colour, made and selected without leaving the drawer. */
@Composable
private fun NewTypeCard(draft: NewTypeDraft, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FieldLabel(str(S.desktop_bs_new_type_name))
        ZillitTextField(
            value = draft.name,
            onValueChange = { onEvent(ScheduleEvent.SetNewTypeName(it)) },
            placeholder = str(S.desktop_bs_type_name_hint),
            modifier = Modifier.fillMaxWidth(),
        )
        FieldLabel(str(S.color))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(
                current = draft.color,
                options = TYPE_COLORS.take(PALETTE_INLINE),
                onPick = { onEvent(ScheduleEvent.SetNewTypeColor(it)) },
                size = 22.dp,
            )
            ColorPickerButton(
                color = draft.color,
                onPick = { onEvent(ScheduleEvent.SetNewTypeColor(it)) },
                presets = TYPE_COLORS,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(ScheduleEvent.CancelNewType) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_map_add_type),
                onClick = { onEvent(ScheduleEvent.CreateNewType) },
                size = ButtonSize.Small,
                enabled = draft.name.isNotBlank(),
                loading = draft.saving,
            )
        }
    }
}

@Composable
private fun TabHint(text: String) {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        ZillitText(
            text,
            style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = colors.textMuted,
        )
    }
}

@Composable
private fun DateRangeTab(state: BoxScheduleUiState, form: ScheduleForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val notPast: (LocalDate) -> Boolean = { it >= state.today }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RangeModes(form.rangeMode, onEvent)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.start_date))
            DiaryDateField(
                value = form.start,
                onPick = { onEvent(ScheduleEvent.SetStart(it)) },
                today = state.today,
                format = DiaryFormat::fullDate,
                enabled = form.lockedStart == null,
                clearable = form.lockedStart == null,
                selectable = notPast,
                markers = form.existingDays,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (form.rangeMode == RangeMode.ByDays) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(str(S.cs_number_of_days))
                ZillitTextField(
                    value = form.countText,
                    onValueChange = { onEvent(ScheduleEvent.SetCount(it)) },
                    placeholder = "1 – ${ScheduleDates.MAX_DAYS}",
                    errorText = str(S.desktop_bs_between_1_and_n, ScheduleDates.MAX_DAYS).takeIf {
                        form.countText.isNotBlank() && form.count == 0
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(str(S.end_date))
                DiaryDateField(
                    value = form.end,
                    onPick = { onEvent(ScheduleEvent.SetEnd(it)) },
                    today = state.today,
                    format = DiaryFormat::fullDate,
                    clearable = true,
                    selectable = { notPast(it) && (form.start == null || it >= form.start) },
                    markers = form.existingDays,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** By Number of Days or By End Date — two radios. */
@Composable
private fun RangeModes(current: RangeMode, onEvent: (BoxScheduleEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        RangeMode.entries.forEach { mode ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onEvent(ScheduleEvent.SetRangeMode(mode)) }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioMark(current == mode)
                ZillitText(mode.label, style = ZillitTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarTab(state: BoxScheduleUiState, form: ScheduleForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(str(S.desktop_bs_click_dates_to_select))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        ) {
            MonthPicker(
                initialMonth = form.picked.minOrNull() ?: form.lockedStart ?: state.today,
                selected = form.picked,
                today = state.today,
                onPick = { onEvent(ScheduleEvent.TogglePick(it)) },
                selectable = { it >= state.today },
                locked = form.lockedStart,
            )
        }
        ZillitText(
            text = if (form.picked.isEmpty()) {
                str(S.desktop_bs_no_dates_selected)
            } else {
                str(S.desktop_bs_n_dates_selected, form.picked.size)
            },
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        if (form.picked.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                form.picked.sorted().forEach { date ->
                    PickedChip(date, locked = date == form.lockedStart, past = date < state.today, onEvent)
                }
            }
        }
    }
}

@Composable
private fun PickedChip(date: LocalDate, locked: Boolean, past: Boolean, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(4.dp))
            .padding(start = 8.dp, end = if (locked || past) 8.dp else 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ZillitText(
            text = DiaryFormat.shortDay(date) + if (locked) " (fixed)" else "",
            style = ZillitTheme.typography.labelSmall.copy(
                fontWeight = if (locked) FontWeight.Bold else FontWeight.Normal,
            ),
        )
        if (!locked && !past) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.desktop_bs_remove_date),
                onClick = { onEvent(ScheduleEvent.TogglePick(date)) },
                size = 18.dp,
            )
        }
    }
}

@Composable
private fun DayWiseTab(state: BoxScheduleUiState, form: ScheduleForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val available = ScheduleDates.availableWeekdays(form.dayWiseStart, form.dayWiseEnd)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(str(S.desktop_bs_select_date_range))
        DiaryDateField(
            value = form.dayWiseStart,
            onPick = { onEvent(ScheduleEvent.SetDayWiseStart(it)) },
            today = state.today,
            placeholder = str(S.start_date),
            enabled = form.lockedStart == null,
            clearable = form.lockedStart == null,
            selectable = { it >= state.today },
            markers = form.existingDays,
            modifier = Modifier.fillMaxWidth(),
        )
        DiaryDateField(
            value = form.dayWiseEnd,
            onPick = { onEvent(ScheduleEvent.SetDayWiseEnd(it)) },
            today = state.today,
            placeholder = str(S.end_date),
            clearable = true,
            selectable = { it >= state.today && (form.dayWiseStart == null || it >= form.dayWiseStart) },
            markers = form.existingDays,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldLabel(str(S.txt_select_days))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScheduleDates.WEEKDAYS.forEach { day ->
                val enabled = day in available
                val selected = day in form.weekdays
                Box(
                    Modifier
                        .alpha(if (enabled) 1f else DISABLED_ALPHA)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) colors.textPrimary else colors.surface)
                        .border(1.dp, if (selected) colors.textPrimary else colors.border, RoundedCornerShape(6.dp))
                        .clickable(enabled = enabled) { onEvent(ScheduleEvent.ToggleWeekday(day)) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = day.name.take(ABBREVIATION).lowercase().replaceFirstChar { it.uppercase() },
                        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                        color = if (selected) colors.surface else colors.textSecondary,
                    )
                }
            }
        }
        val count = form.days().size
        // Day Wise yields days only once both ends and a weekday are chosen.
        if (count > 0) {
            ZillitText(
                "$count matching day(s) in range",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun Summary(form: ScheduleForm) {
    val days = form.days()
    if (days.isEmpty()) return
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.accentSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Calendar, tint = colors.accentText, size = 14.dp)
        ZillitText(
            text = str(
                S.cs_days_summary,
                days.size,
                DiaryFormat.monthDay(days.first()),
                DiaryFormat.mediumDate(days.last()),
            ),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = colors.accentText,
        )
    }
}

/** "Date cannot be changed when editing a single day" — and, once the type changes, how. */
@Composable
private fun SingleDayChoice(state: BoxScheduleUiState, form: ScheduleForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val date = form.singleDate ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FieldLabel(str(S.date))
        ZillitText(DiaryFormat.longDate(date, state.zone), style = ZillitTheme.typography.titleSmall)
        ZillitText(
            str(S.desktop_bs_date_locked_single_day),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
    }
    if (!form.typeChanged) return
    val day = DiaryFormat.monthDay(date, state.zone)
    val old = form.originalTypeName
    val total = form.originalDays.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(str(S.desktop_bs_how_should_change_apply))
        OptionCard(
            selected = form.singleAction == ConflictAction.Replace,
            title = str(S.replace),
            description = str(S.desktop_bs_replace_description, day, old, total, (total - 1).coerceAtLeast(0)),
            onClick = { onEvent(ScheduleEvent.SetSingleAction(ConflictAction.Replace)) },
        )
        OptionCard(
            selected = form.singleAction == ConflictAction.Extend,
            title = str(S.txt_extent),
            description = str(S.desktop_bs_extend_description, day, old, total),
            extra = extendLine(form, state),
            onClick = { onEvent(ScheduleEvent.SetSingleAction(ConflictAction.Extend)) },
        )
        OptionCard(
            selected = form.singleAction == ConflictAction.Overlap,
            title = str(S.txt_overlap),
            description = str(S.desktop_bs_overlap_description, old, day),
            onClick = { onEvent(ScheduleEvent.SetSingleAction(ConflictAction.Overlap)) },
        )
    }
}

/** "Shoot Day will now end on Sep 19 instead of Sep 18." */
private fun extendLine(form: ScheduleForm, state: BoxScheduleUiState): String? {
    val date = form.singleDate ?: return null
    if (form.originalDays.size <= 1) return null
    val key = DiaryCalendar.dayKey(date, state.zone)
    val remaining = form.originalDays.filter { DiaryCalendar.dayKey(it, state.zone) != key }
    val last = remaining.maxOrNull() ?: return null
    val extended = DiaryCalendar.dateOf(last, state.zone).plus(1, DateTimeUnit.DAY)
    val currentEnd = form.originalDays.max()
    val was = DiaryFormat.monthDay(currentEnd, state.zone)
    return str(S.desktop_bs_will_now_end_on, form.originalTypeName, DiaryFormat.monthDay(extended), was)
}

private val FORM_WIDTH = 440.dp
private const val PALETTE_INLINE = 6
private const val DISABLED_ALPHA = 0.4f
private const val ABBREVIATION = 3
