package com.zillit.desktop.feature.drive.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * How the version history panel words a save, a session and a day — the web's
 * `versionHistoryUtils.js`, minus the parts that are the design system's here
 * (a person's colour and initials come from `ZillitAvatar`, which colours by
 * name app-wide, rather than from the web's own id-keyed palette).
 *
 * Every function takes `now` where it needs one, so "Today" is testable and
 * does not silently change at midnight behind a composition.
 */

/** "Autosaved", "Saved on close", "Restored from version 4"… */
fun saveTypeLabel(version: DriveFileVersion): String {
    val from = version.restoredFromNumber
    if (version.saveType == DriveSaveType.Restore && from != null && from > 0) {
        return str(S.desktop_drive_version_restored_from, from)
    }
    return when (version.saveType) {
        DriveSaveType.Autosave -> str(S.desktop_drive_version_autosaved)
        DriveSaveType.Exit -> str(S.desktop_drive_version_saved_on_close)
        DriveSaveType.Restore -> str(S.desktop_drive_restored)
        DriveSaveType.Upload -> str(S.sides_uploaded)
        DriveSaveType.Created -> str(S.drive_created)
        DriveSaveType.Legacy -> str(S.desktop_drive_version_earlier)
        DriveSaveType.Manual -> str(S.saved)
    }
}

/** "Priya", "Priya and Sam", "Priya, Sam and 2 others" — the way people write them. */
fun joinNames(names: List<String>): String {
    val list = names.filter { it.isNotBlank() }
    return when {
        list.isEmpty() -> ""
        list.size == 1 -> list.first()
        list.size == 2 -> str(S.desktop_docdist_x_and_y, list[0], list[1])
        list.size == NAMES_BEFORE_OTHERS + 1 -> str(S.desktop_drive_names_one_other, list[0], list[1])
        else -> str(S.desktop_drive_names_more_others, list[0], list[1], list.size - NAMES_BEFORE_OTHERS)
    }
}

/**
 * "Today", "Yesterday", "Tuesday, 22 September", "22 September 2026".
 *
 * Compared by calendar day in the machine's own zone, not by elapsed hours:
 * a save at 23:30 is "Yesterday" at 00:30, which "18 hours ago" would call
 * today.
 */
fun dayLabel(millis: Long, now: Long): String {
    val day = millis.localOrNull() ?: return ""
    val today = now.localOrNull()
    val elapsed = if (today == null) -1 else today.toEpochDay() - day.toEpochDay()
    return when {
        elapsed == 0L -> str(S.drive_date_today)
        elapsed == 1L -> str(S.yesterday)
        today != null && day.year == today.year -> "${day.weekdayName()}, ${day.day} ${day.monthName()}"
        else -> "${day.day} ${day.monthName()} ${day.year}"
    }
}

/** `h:mm A` — "2:03 PM". Blank for a stamp the server did not send. */
fun versionTime(millis: Long): String {
    val moment = millis.localOrNull() ?: return ""
    val hour = moment.hour % HALF_DAY
    return "${if (hour == 0) HALF_DAY else hour}:${moment.minute.padded()} ${moment.meridiem()}"
}

/** "Today, 2:03 PM" for the recent days, "22 Sep 2026, 2:03 PM" for the rest. */
fun versionDateTime(millis: Long, now: Long): String {
    if (millis <= 0) return ""
    val label = dayLabel(millis, now)
    val time = versionTime(millis)
    val recent = label == str(S.drive_date_today) || label == str(S.yesterday)
    return if (recent) "$label, $time" else "${shortDate(millis)}, $time"
}

/**
 * "2:03 PM" for one save, "2:03 – 2:41 PM" for a session.
 *
 * The first time drops its AM/PM when both ends share one, which is how a
 * range reads when spoken — but keeps it across noon ("11:50 AM – 12:10 PM").
 */
fun sessionTimeRange(session: DriveVersionSession): String {
    val start = versionTime(session.startedAt)
    val end = versionTime(session.endedAt)
    if (start.isEmpty() || start == end) return end.ifEmpty { start }
    val sameHalf = start.takeLast(MERIDIEM_LENGTH) == end.takeLast(MERIDIEM_LENGTH)
    val from = if (sameHalf) start.dropLast(MERIDIEM_LENGTH + 1) else start
    return "$from – $end"
}

/**
 * The sessions under day headings, in the order they arrived (newest first).
 *
 * With [namedOnly], every named version stands on its own as a session of
 * one: a name is given to a *version*, so collapsing it back under the
 * session that produced it would hide the thing the filter was asked for.
 */
fun groupHistory(
    sessions: List<DriveVersionSession>,
    namedOnly: Boolean = false,
    now: Long = 0,
): List<DriveVersionDay> {
    val list = if (namedOnly) sessions.namedAsSessions() else sessions
    val days = mutableListOf<DriveVersionDay>()
    list.forEach { session ->
        val label = dayLabel(session.endedAt, now)
        val last = days.lastOrNull()
        if (last != null && last.label == label) {
            days[days.lastIndex] = last.copy(sessions = last.sessions + session)
        } else {
            days += DriveVersionDay(label, listOf(session))
        }
    }
    return days
}

private fun List<DriveVersionSession>.namedAsSessions(): List<DriveVersionSession> = flatMap { session ->
    session.versions.filter { it.name.isNotBlank() }.map { version ->
        DriveVersionSession(
            id = "named-${version.id}",
            startedAt = version.savedAt,
            endedAt = version.savedAt,
            editorIds = version.people,
            versions = listOf(version),
        )
    }
}

/** Every type the editor opens; anything else is [DriveFileKind.Other]. */
fun fileKindOf(fileName: String): DriveFileKind {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return DriveFileKind.Other
    return when (extension) {
        in SPREADSHEETS -> DriveFileKind.Spreadsheet
        in DOCUMENTS -> DriveFileKind.Document
        in PRESENTATIONS -> DriveFileKind.Presentation
        else -> DriveFileKind.Other
    }
}

/** The newest version the history lists, which is what the file is now. */
fun findCurrentVersion(sessions: List<DriveVersionSession>): DriveFileVersion? =
    sessions.firstOrNull()?.versions?.firstOrNull()

fun findVersion(sessions: List<DriveVersionSession>, versionId: String?): DriveFileVersion? {
    if (versionId.isNullOrBlank()) return null
    return sessions.firstNotNullOfOrNull { session -> session.versions.firstOrNull { it.id == versionId } }
}

/**
 * Whether [version] is the file as it stands — the server's own flag, or
 * failing that the newest version in the history.
 */
fun isCurrentVersion(version: DriveFileVersion, current: DriveFileVersion?): Boolean =
    version.isCurrent || (current != null && current.id == version.id)

// -- dates ----------------------------------------------------------------

/** "22 Sep 2026" — the stamp beside a time, where the app's own `date()` reads long. */
private fun shortDate(millis: Long): String {
    val moment = millis.localOrNull() ?: return ""
    return "${moment.day} ${moment.monthName().take(MONTH_ABBREVIATION)} ${moment.year}"
}

private fun Long.localOrNull() = takeIf { it > 0 }
    ?.let {
        runCatching {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault())
        }.getOrNull()
    }

private fun LocalDateTime.toEpochDay(): Long = date.toEpochDays().toLong()

private fun LocalDateTime.monthName(): String = str(MONTHS[month.ordinal])

private fun LocalDateTime.weekdayName(): String = str(WEEKDAYS[dayOfWeek.ordinal])

private fun LocalDateTime.meridiem(): String = if (hour >= HALF_DAY) "PM" else "AM"

private fun Int.padded(): String = toString().padStart(2, '0')

private val MONTHS = listOf(
    S.desktop_month_full_january, S.desktop_month_full_february, S.desktop_month_full_march,
    S.desktop_month_full_april, S.desktop_month_short_may, S.desktop_month_full_june,
    S.desktop_month_full_july, S.desktop_month_full_august, S.desktop_month_full_september,
    S.desktop_month_full_october, S.desktop_month_full_november, S.desktop_month_full_december,
)

private val WEEKDAYS = listOf(
    S.day_monday, S.day_tuesday, S.day_wednesday, S.day_thursday,
    S.day_friday, S.day_saturday, S.day_sunday,
)

private val SPREADSHEETS = setOf("xlsx", "xlsm", "xls", "ods", "csv")
private val DOCUMENTS = setOf("docx", "doc", "odt", "rtf", "txt", "md", "markdown")
private val PRESENTATIONS = setOf("pptx", "ppt", "odp")

private const val HALF_DAY = 12
private const val MERIDIEM_LENGTH = 2
private const val MONTH_ABBREVIATION = 3
private const val NAMES_BEFORE_OTHERS = 2
