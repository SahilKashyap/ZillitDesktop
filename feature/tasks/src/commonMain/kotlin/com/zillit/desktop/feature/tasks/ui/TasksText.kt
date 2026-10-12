@file:Suppress("MaxLineLength") // Wording helpers.

package com.zillit.desktop.feature.tasks.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.DueBucket
import com.zillit.desktop.feature.tasks.domain.DueLabel
import com.zillit.desktop.feature.tasks.domain.HistoryLine
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/** The words the Tasks screens use, kept out of the composables so they can be tested. */

fun statusText(status: TaskStatus): String = when (status) {
    TaskStatus.Todo -> str(S.desktop_tasks_status_todo)
    TaskStatus.Progress -> str(S.desktop_tasks_status_progress)
    TaskStatus.Done -> str(S.done_text)
    TaskStatus.Cancelled -> str(S.cancelled)
}

// The weather tool's "High" / "Low" are the same words; they carry the same translations.
fun priorityText(priority: TaskPriority): String = when (priority) {
    TaskPriority.High -> str(S.desktop_weather_uv_high)
    TaskPriority.Med -> str(S.medium)
    TaskPriority.Low -> str(S.desktop_weather_uv_low)
}

private fun DayOfWeek.short(): String = name.take(SHORT).lowercase().replaceFirstChar { it.uppercase() }

private fun Month.short(): String = name.take(SHORT).lowercase().replaceFirstChar { it.uppercase() }

/** "Oct 6", or "Oct 6, 2027" once the year is not this one. */
fun dayText(date: LocalDate, today: LocalDate): String =
    if (date.year == today.year) "${date.month.short()} ${date.day}" else "${date.month.short()} ${date.day}, ${date.year}"

/** "Tue, Oct 6, 2026" for a date picker's button. */
fun fullDayText(date: LocalDate): String = "${date.dayOfWeek.short()}, ${date.month.short()} ${date.day}, ${date.year}"

/** "Oct 24, 2026" (the web's `MMM D, YYYY`), for the due-date limits; a day that does not parse is shown as it came. */
fun yearDayText(day: String?): String {
    val date = day?.let { runCatching { LocalDate.parse(it.take(DAY)) }.getOrNull() } ?: return day.orEmpty()
    return "${date.month.short()} ${date.day}, ${date.year}"
}

/** "Mon, Oct 12, 2026 · 4:05 PM" (the web's `historyWhen`): when a history entry was made, in full. */
fun historyWhen(millis: Long): String {
    if (millis <= 0) return ""
    val at = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = (at.hour % HALF_DAY).let { if (it == 0) HALF_DAY else it }
    val minute = at.minute.toString().padStart(2, '0')
    return "${fullDayText(at.date)} · $hour:$minute ${if (at.hour < HALF_DAY) "AM" else "PM"}"
}

/** Short label for a card or row: Today / Tomorrow / Yesterday / Tue, Oct 6 / Oct 20. */
fun dueText(label: DueLabel): String = when (label) {
    DueLabel.None -> str(S.ah_template_no_date)
    DueLabel.Today -> str(S.today)
    DueLabel.Tomorrow -> str(S.tomorrow)
    DueLabel.Yesterday -> str(S.yesterday)
    is DueLabel.Soon -> "${label.date.dayOfWeek.short()}, ${label.date.month.short()} ${label.date.day}"
    is DueLabel.On -> "${label.date.month.short()} ${label.date.day}"
}

fun bucketTitle(bucket: DueBucket): String = when (bucket) {
    DueBucket.Overdue -> str(S.desktop_overdue)
    DueBucket.Today -> str(S.today)
    DueBucket.Week -> str(S.desktop_tasks_mine_upcoming)
    DueBucket.Later -> str(S.later)
}

/** "just now / 5m ago / 3h ago / 2d ago", each step rounded to the nearest, as the web's `timeAgo`. */
fun timeAgo(millis: Long, now: Long): String {
    val minutes = ((now - millis).toDouble() / MINUTE_MS).roundToInt()
    if (minutes < 1) return str(S.docusign_template_just_now)
    if (minutes < MINUTES_PER_HOUR) return str(S.desktop_tasks_minutes_ago, minutes)
    val hours = (minutes.toDouble() / MINUTES_PER_HOUR).roundToInt()
    if (hours < HOURS_PER_DAY) return str(S.desktop_tasks_hours_ago, hours)
    return str(S.desktop_tasks_days_ago, (hours.toDouble() / HOURS_PER_DAY).roundToInt())
}

/** The name of a history field, as the panel labels it. */
fun fieldText(field: String, isSubtask: Boolean): String = when (field) {
    "title" -> str(if (isSubtask) S.desktop_tasks_field_subtask_title else S.desktop_tasks_field_title)
    "status" -> str(S.status)
    "assignee_id" -> str(S.desktop_tasks_field_assignee)
    "due_date" -> str(S.ah_run_detail_col_due)
    "priority" -> str(S.priority)
    "department_id" -> str(S.department)
    "scenes" -> str(S.av_scenes)
    else -> field
}

/** One stored history value as text: a status, a person, a department, a day, or the text itself in quotes. */
fun historyValue(
    field: String,
    value: String?,
    crewById: Map<String, TaskPerson>,
    departmentName: (String?) -> String,
    today: LocalDate,
): String {
    val empty = value.isNullOrEmpty()
    return when {
        field == "assignee_id" ->
            if (empty) str(S.unassigned) else crewById[value]?.fullName ?: str(S.desktop_tasks_former_member)
        field == "department_id" ->
            if (empty) str(S.desktop_tasks_no_department) else departmentName(value).ifEmpty { str(S.desktop_tasks_no_department) }
        empty -> str(S.none)
        field == "status" -> statusText(TaskStatus.fromWire(value))
        field == "priority" -> priorityText(TaskPriority.fromWire(value))
        field == "due_date" -> runCatching { dayText(LocalDate.parse(value.orEmpty().take(DAY)), today) }.getOrDefault(value.orEmpty())
        else -> "“$value”"
    }
}

/** What a [HistoryLine] says, as plain text — one sentence, or "Label: from → to". */
fun historyText(
    line: HistoryLine,
    crewById: Map<String, TaskPerson>,
    departmentName: (String?) -> String,
    today: LocalDate,
): String = when (line) {
    is HistoryLine.Created -> str(if (line.isSubtask) S.desktop_tasks_history_created_subtask else S.desktop_tasks_history_created)
    is HistoryLine.CreatedAssignee ->
        "${str(S.desktop_tasks_field_assignee)}: ${historyValue("assignee_id", line.assigneeId, crewById, departmentName, today)}"
    is HistoryLine.SubtaskAdded -> str(S.desktop_tasks_history_subtask_added, line.title)
    is HistoryLine.SubtaskDeleted -> str(S.desktop_tasks_history_subtask_deleted, line.title)
    HistoryLine.DescriptionChanged -> str(S.desktop_tasks_history_description)
    HistoryLine.Changed -> str(S.desktop_tasks_history_changed)
    is HistoryLine.Note -> str(
        if (line.reason == "main_cancelled") S.desktop_tasks_history_reason_main_cancelled else S.desktop_tasks_history_reason_subtask_open,
    )
    is HistoryLine.Field -> {
        val from = historyValue(line.field, line.from, crewById, departmentName, today)
        val to = historyValue(line.field, line.to, crewById, departmentName, today)
        "${fieldText(line.field, line.isSubtask)}: $from → $to"
    }
}

private const val SHORT = 3
private const val DAY = 10
private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val HALF_DAY = 12
