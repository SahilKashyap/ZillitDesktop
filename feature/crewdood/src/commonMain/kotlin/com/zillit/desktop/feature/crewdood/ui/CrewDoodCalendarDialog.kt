package com.zillit.desktop.feature.crewdood.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.crewdood.domain.DAYS_PER_WEEK
import com.zillit.desktop.feature.crewdood.domain.DoodCalc
import com.zillit.desktop.feature.crewdood.domain.DoodCalendar
import com.zillit.desktop.feature.crewdood.domain.DoodHoliday
import com.zillit.desktop.feature.crewdood.domain.DoodHolidayRun
import com.zillit.desktop.feature.crewdood.domain.sundayBasedDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Days off & holidays — one calendar for the whole project: which weekdays
 * nobody works, and which dates are holidays.
 *
 * Built to `design_handoff_days_off_web`: a Monday-first weekday row, a month
 * calendar to pick holidays on (one click starts a range, a second ends it in
 * either direction, hovering previews it), a draft panel with a name, and the
 * holiday list, which groups consecutive days of the same name into one row —
 * renamed and removed as a whole. However a holiday is picked it is saved as
 * one `{ date, name }` per day, because that is the shape the API has.
 *
 * Mounted only while open, so every opening starts from the saved calendar.
 */
@Suppress("LongMethod") // One dialog, drawn top to bottom; its parts are the composables below.
@Composable
internal fun CrewDoodCalendarDialog(
    calendar: DoodCalendar,
    saving: Boolean,
    onSave: (DoodCalendar) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
    val initial = remember(calendar) { CalendarDraft.of(calendar) }
    var draft by remember(calendar) { mutableStateOf(initial) }
    val selection = remember(calendar) { CalendarSelection() }

    val runs = remember(draft) { DoodCalc.groupHolidays(draft.holidays) }
    // A week with no working day would leave every bar on the grid empty.
    val allOff = draft.weeklyOff.size == DAYS_PER_WEEK

    ZillitDialogShell(
        title = str(S.desktop_cdood_calendar_button),
        subtitle = str(S.desktop_cdood_calendar_hint),
        icon = ZillitIcons.Calendar,
        visible = true,
        width = DIALOG_WIDTH,
        onDismiss = { if (!saving) onDismiss() },
        actions = {
            ZillitText(
                text = summaryLine(draft),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitButton(
                text = str(S.cancel),
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !saving,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onSave(draft.toCalendar()) },
                size = ButtonSize.Small,
                enabled = !saving && draft != initial && !allOff,
                loading = saving,
            )
        },
    ) {
        WeeklyDaysOff(
            weeklyOff = draft.weeklyOff,
            allOff = allOff,
            enabled = !saving,
            onToggle = { day -> draft = draft.toggleWeekday(day) },
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            HolidayPicker(
                draft = draft,
                runs = runs,
                selection = selection,
                today = today,
                enabled = !saving,
                onDraft = { draft = it },
                modifier = Modifier.weight(1f),
            )
            HolidayList(
                runs = runs,
                enabled = !saving,
                onRename = { dates, value -> draft = draft.rename(dates, value) },
                onRemove = { dates -> draft = draft.remove(dates) },
                modifier = Modifier.width(LIST_WIDTH),
            )
        }
    }
}

/**
 * The calendar, the draft panel and the key under it.
 *
 * One click starts a range and a second ends it, in either direction, with the
 * days between previewed as the pointer moves — the design's own behaviour,
 * held in [CalendarSelection] so this stays a drawing.
 */
@Composable
private fun HolidayPicker(
    draft: CalendarDraft,
    runs: List<DoodHolidayRun>,
    selection: CalendarSelection,
    today: LocalDate,
    enabled: Boolean,
    onDraft: (CalendarDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val runOf = remember(runs) {
        buildMap { runs.forEach { run -> run.dates.forEach { date -> put(date, run) } } }
    }
    val range = selection.range
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        SectionLabel(str(S.desktop_cdood_add_holidays), str(S.desktop_cdood_add_holidays_hint))
        MonthHeader(
            month = selection.month ?: LocalDate(today.year, today.monthNumber, 1),
            enabled = enabled,
            onShift = { step -> selection.shiftMonth(today, step) },
        )
        MonthGrid(
            month = selection.month ?: LocalDate(today.year, today.monthNumber, 1),
            today = today,
            weeklyOff = draft.weeklyOff,
            runOf = runOf,
            selection = range,
            enabled = enabled,
            onPick = selection::pick,
            onPreview = selection::preview,
        )
        if (range == null) {
            PickerKey()
        } else {
            DraftPanel(
                range = range,
                complete = selection.complete,
                name = selection.name,
                error = selection.error,
                enabled = enabled,
                onName = { selection.name = it },
                onAdd = {
                    when (val outcome = draft.add(range.first, range.second, selection.name)) {
                        is AddOutcome.Added -> {
                            onDraft(outcome.draft)
                            selection.clear()
                        }
                        is AddOutcome.Refused -> selection.error = outcome.reason
                    }
                },
                onClear = selection::clear,
            )
        }
    }
}

@Composable
private fun HolidayList(
    runs: List<DoodHolidayRun>,
    enabled: Boolean,
    onRename: (List<LocalDate>, String) -> Unit,
    onRemove: (List<LocalDate>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        SectionLabel(
            str(S.desktop_cdood_holidays),
            if (runs.isEmpty()) "" else str(S.desktop_cdood_holidays_added, runs.size),
        )
        if (runs.isEmpty()) {
            EmptyHolidays()
        } else {
            runs.forEach { run ->
                HolidayRow(
                    run = run,
                    enabled = enabled,
                    onRename = { value -> onRename(run.dates, value) },
                    onRemove = { onRemove(run.dates) },
                )
            }
        }
    }
}

/**
 * What the calendar has selected: the anchor, the second click, the hovered
 * preview, the name being typed and why an Add was refused.
 *
 * A holder rather than six `remember`s so the picker reads as a drawing and
 * the two-click rule lives in one place.
 */
private class CalendarSelection {
    var month by mutableStateOf<LocalDate?>(null)
        private set
    var name by mutableStateOf("")
    var error by mutableStateOf("")

    private var anchor by mutableStateOf<LocalDate?>(null)
    private var second by mutableStateOf<LocalDate?>(null)
    private var hovered by mutableStateOf<LocalDate?>(null)

    /** The range as it stands — the preview's end until the second click lands. */
    val range: Pair<LocalDate, LocalDate>?
        get() {
            val start = anchor ?: return null
            val other = second ?: hovered ?: start
            return if (other < start) other to start else start to other
        }

    val complete: Boolean get() = second != null

    /** The first click starts a range; a second ends it, in either direction. */
    fun pick(day: LocalDate) {
        error = ""
        hovered = null
        when {
            anchor == null || second != null -> {
                anchor = day
                second = null
            }
            day < anchor!! -> {
                second = anchor
                anchor = day
            }
            else -> second = day
        }
    }

    fun preview(day: LocalDate) {
        if (anchor != null && second == null && hovered != day) hovered = day
    }

    fun clear() {
        anchor = null
        second = null
        hovered = null
        name = ""
        error = ""
    }

    fun shiftMonth(today: LocalDate, step: Int) {
        val from = month ?: LocalDate(today.year, today.monthNumber, 1)
        month = from.plus(step, DateTimeUnit.MONTH)
    }
}

// ── Weekly days off ─────────────────────────────────────────────────────────

@Composable
private fun WeeklyDaysOff(
    weeklyOff: Set<Int>,
    allOff: Boolean,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        SectionLabel(str(S.desktop_cdood_weekly_days_off), weekSummary(weeklyOff))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            DoodWords.WEEK_ORDER.forEach { day ->
                val on = day in weeklyOff
                Box(
                    Modifier
                        .clip(ZillitTheme.shapes.medium)
                        .background(if (on) colors.accentSoft else colors.surfaceSunken)
                        .border(
                            HAIRLINE,
                            if (on) colors.accent else colors.border,
                            ZillitTheme.shapes.medium,
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(enabled = enabled) { onToggle(day) }
                        .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                ) {
                    ZillitText(
                        text = DoodWords.weekdayName(day),
                        style = ZillitTheme.typography.labelSmall.copy(
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (on) colors.accentText else colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        ZillitText(
            text = if (allOff) {
                str(S.desktop_cdood_need_working_day)
            } else {
                str(S.desktop_cdood_weekly_off_help)
            },
            style = ZillitTheme.typography.labelSmall,
            color = if (allOff) colors.danger else colors.textMuted,
            maxLines = 2,
        )
    }
}

// ── The month calendar ──────────────────────────────────────────────────────

@Composable
private fun MonthHeader(month: LocalDate, enabled: Boolean, onShift: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = "${DoodWords.monthLong(month.monthNumber)} ${month.year}",
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = str(S.desktop_previous_month),
            onClick = { onShift(-1) },
            enabled = enabled,
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronRight,
            contentDescription = str(S.desktop_next_month),
            onClick = { onShift(1) },
            enabled = enabled,
        )
    }
}

@Suppress("LongParameterList") // A month cell's whole state; splitting it only hides it.
@Composable
private fun MonthGrid(
    month: LocalDate,
    today: LocalDate,
    weeklyOff: Set<Int>,
    runOf: Map<LocalDate, DoodHolidayRun>,
    selection: Pair<LocalDate, LocalDate>?,
    enabled: Boolean,
    onPick: (LocalDate) -> Unit,
    onPreview: (LocalDate) -> Unit,
) {
    val colors = ZillitTheme.colors
    val lead = (month.sundayBasedDay() + 6) % DAYS_PER_WEEK
    val days = month.daysUntil(month.plus(1, DateTimeUnit.MONTH))
    val cells = List(lead) { null } + List(days) { month.plus(it, DateTimeUnit.DAY) }
    Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            DoodWords.WEEK_ORDER.forEach { day ->
                ZillitText(
                    text = DoodWords.weekdayName(day).take(WEEKDAY_LETTERS),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
        cells.chunked(DAYS_PER_WEEK).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                week.forEach { day ->
                    if (day == null) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        DayCell(
                            day = day,
                            today = today,
                            off = day.sundayBasedDay() in weeklyOff,
                            holiday = runOf[day],
                            selected = selection != null && day >= selection.first && day <= selection.second,
                            enabled = enabled,
                            onPick = onPick,
                            onPreview = onPreview,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(DAYS_PER_WEEK - week.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
            }
        }
    }
}

@Suppress("LongParameterList") // See MonthGrid.
@Composable
private fun DayCell(
    day: LocalDate,
    today: LocalDate,
    off: Boolean,
    holiday: DoodHolidayRun?,
    selected: Boolean,
    enabled: Boolean,
    onPick: (LocalDate) -> Unit,
    onPreview: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val palette = doodPalette()
    val fill = when {
        selected -> colors.accent
        holiday != null -> palette.holiday
        off -> palette.weekend
        else -> Color.Transparent
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(ZillitTheme.shapes.small)
            .background(fill)
            .then(
                if (off && holiday == null && !selected) {
                    Modifier.drawBehind { hatchCell(palette.holiday.copy(alpha = HATCH_ALPHA)) }
                } else {
                    Modifier
                },
            )
            .then(
                if (day == today) {
                    Modifier.border(HAIRLINE_RING, colors.accent, ZillitTheme.shapes.small)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled) { onPick(day) }
            // Hovering a day previews the range it would make, as the design
            // does — through pointer events rather than a hover flag, so a
            // move never re-runs composition for the whole month.
            .pointerInput(day) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Enter || event.type == PointerEventType.Move) {
                            onPreview(day)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = day.day.toString(),
            style = ZillitTheme.typography.labelSmall,
            color = if (selected) colors.textOnAccent else colors.textPrimary,
            maxLines = 1,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.hatchCell(stripe: Color) {
    var offset = -size.height
    while (offset < size.width) {
        drawLine(
            color = stripe,
            start = Offset(offset, size.height),
            end = Offset(offset + size.height, 0f),
            strokeWidth = HATCH_STROKE,
        )
        offset += HATCH_GAP
    }
}

// ── The draft panel and the list ────────────────────────────────────────────

@Suppress("LongParameterList", "LongMethod") // One panel, one state, drawn in one pass.
@Composable
private fun DraftPanel(
    range: Pair<LocalDate, LocalDate>,
    complete: Boolean,
    name: String,
    error: String,
    enabled: Boolean,
    onName: (String) -> Unit,
    onAdd: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val count = range.first.daysUntil(range.second) + 1
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = rangeLabel(range.first, range.second),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            ZillitText(
                text = buildString {
                    append(if (count == 1) str(S.desktop_one_day) else str(S.desktop_payroll_days_count, count))
                    if (!complete) append(" · ${str(S.desktop_cdood_pick_end)}")
                },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitTextField(
                value = name,
                onValueChange = onName,
                placeholder = str(S.desktop_dist_name_optional),
                enabled = enabled,
                maxLength = NAME_MAX,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.add),
                onClick = onAdd,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
                enabled = enabled,
            )
            ZillitButton(
                text = str(S.ah_clear),
                onClick = onClear,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = enabled,
            )
        }
        if (error.isNotBlank()) {
            ZillitText(
                text = error,
                style = ZillitTheme.typography.labelSmall,
                color = colors.danger,
                maxLines = 2,
            )
        }
    }
}

/** The key the panel shows while nothing is selected. */
@Composable
private fun PickerKey() {
    val palette = doodPalette()
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        KeyItem(palette.holiday, str(S.desktop_holiday))
        KeyItem(palette.weekend, str(S.desktop_cdood_key_day_off))
    }
}

@Composable
private fun KeyItem(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Box(Modifier.size(SWATCH).clip(ZillitTheme.shapes.small).background(color))
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyHolidays() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = str(S.desktop_cdood_no_holidays),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 3,
        )
    }
}

@Suppress("LongMethod") // A date tile, an editable name and a delete, in one row.
@Composable
private fun HolidayRow(
    run: DoodHolidayRun,
    enabled: Boolean,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val label = rangeLabel(run.start, run.end)
    val many = run.dates.size > 1
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Column(
            Modifier
                .width(DATE_TILE)
                .clip(ZillitTheme.shapes.small)
                .background(colors.surface)
                .padding(vertical = ZillitTheme.spacing.xxs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZillitText(
                text = DoodWords.month(run.start).uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
            ZillitText(
                text = if (many && run.start.monthNumber == run.end.monthNumber) {
                    "${run.start.day}–${run.end.day}"
                } else {
                    run.start.day.toString()
                },
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitTextField(
                value = run.name,
                onValueChange = onRename,
                placeholder = str(S.desktop_cdood_add_a_name),
                enabled = enabled,
                maxLength = NAME_MAX,
                modifier = Modifier.fillMaxWidth(),
            )
            ZillitText(
                text = "$label · " + if (many) {
                    str(S.desktop_payroll_days_count, run.dates.size)
                } else {
                    str(S.desktop_one_day)
                },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.bs_chip_remove, label),
            onClick = onRemove,
            enabled = enabled,
            tint = colors.danger,
        )
    }
}

@Composable
private fun SectionLabel(title: String, hint: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = title,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        if (hint.isNotBlank()) {
            ZillitText(
                text = hint,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Words ───────────────────────────────────────────────────────────────────

/** "Tue 13 Oct 2026", or "12 – 16 Oct 2026" / "28 Sep – 2 Oct 2026" for a range. */
private fun rangeLabel(start: LocalDate, end: LocalDate): String {
    if (start == end) return "${DoodWords.weekday(start)} ${DoodWords.dayMonthYear(start)}"
    val from = when {
        start.year == end.year && start.monthNumber == end.monthNumber -> start.day.toString()
        start.year == end.year -> DoodWords.dayMonth(start)
        else -> DoodWords.dayMonthYear(start)
    }
    return "$from – ${DoodWords.dayMonthYear(end)}"
}

private fun weekSummary(weeklyOff: Set<Int>): String {
    val names = DoodWords.WEEK_ORDER.filter { it in weeklyOff }.map { DoodWords.weekdayName(it) }
    if (names.isEmpty()) return str(S.desktop_cdood_week_full)
    return str(S.desktop_cdood_week_summary, names.joinToString(", "), DAYS_PER_WEEK - names.size)
}

private fun summaryLine(draft: CalendarDraft): String {
    val weekly = draft.weeklyOff.size
    val holidays = draft.holidays.size
    val weeklyWord = if (weekly == 1) str(S.desktop_cdood_sum_weekly_one) else str(S.desktop_cdood_sum_weekly_many)
    val holidayWord =
        if (holidays == 1) str(S.desktop_cdood_sum_holiday_one) else str(S.desktop_cdood_sum_holiday_many)
    return "$weekly $weeklyWord · $holidays $holidayWord"
}

// ── The draft ───────────────────────────────────────────────────────────────

/**
 * The editor's working copy. Held as a value, so Save is a pure comparison
 * with what was loaded and the grid never sees a half-made calendar.
 */
internal data class CalendarDraft(
    val weeklyOff: Set<Int>,
    val holidays: List<DoodHoliday>,
) {
    fun toggleWeekday(day: Int): CalendarDraft =
        copy(weeklyOff = if (day in weeklyOff) weeklyOff - day else weeklyOff + day)

    /** Days already holidays keep their own name; the rest are added. */
    fun add(from: LocalDate, to: LocalDate, name: String): AddOutcome {
        val days = from.daysUntil(to) + 1
        if (days > DoodCalc.MAX_HOLIDAY_RANGE) {
            return AddOutcome.Refused(str(S.desktop_cdood_range_too_long, DoodCalc.MAX_HOLIDAY_RANGE))
        }
        val have = holidays.map { it.date }.toSet()
        val fresh = DoodCalc.datesBetween(from, to).filterNot { it in have }
        if (fresh.isEmpty()) return AddOutcome.Refused(str(S.desktop_cdood_range_exists))
        val label = name.trim()
        return AddOutcome.Added(
            copy(holidays = (holidays + fresh.map { DoodHoliday(it, label) }).sortedBy { it.date }),
        )
    }

    fun rename(dates: List<LocalDate>, name: String): CalendarDraft =
        copy(holidays = holidays.map { if (it.date in dates) it.copy(name = name) else it })

    fun remove(dates: List<LocalDate>): CalendarDraft =
        copy(holidays = holidays.filterNot { it.date in dates })

    fun toCalendar(): DoodCalendar = DoodCalc.calendarFrom(weeklyOff.sorted(), holidays)

    companion object {
        fun of(calendar: DoodCalendar) = CalendarDraft(
            weeklyOff = calendar.weeklyOff,
            holidays = calendar.holidays.entries.sortedBy { it.key }.map { DoodHoliday(it.key, it.value) },
        )
    }
}

internal sealed interface AddOutcome {
    data class Added(val draft: CalendarDraft) : AddOutcome
    data class Refused(val reason: String) : AddOutcome
}

private val DIALOG_WIDTH = 880.dp
private val LIST_WIDTH = 320.dp
private val DATE_TILE = 44.dp
private val SWATCH = 12.dp
private val CELL_GAP = 4.dp
private val HAIRLINE = 1.dp
private val HAIRLINE_RING = 2.dp
private const val HATCH_GAP = 6f
private const val HATCH_STROKE = 1f
private const val HATCH_ALPHA = 0.5f
private const val WEEKDAY_LETTERS = 2
private const val NAME_MAX = 80
