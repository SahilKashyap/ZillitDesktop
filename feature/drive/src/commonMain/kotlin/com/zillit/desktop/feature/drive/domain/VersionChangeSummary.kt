// One small function per line the panel prints, and one per status it has to
// explain; the alternative is two files that have to be read together.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.drive.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * What a version's changes are *called* — the web's `describeChanges`,
 * `activityItems` / `activityPeople` and the per-cell attribution helpers.
 *
 * Pure, and localised: every line here is what a row or the detail prints, so
 * the three cases the web had to learn the hard way live in one place —
 *
 *  1. a version where somebody only resized two columns is **not** "No cell
 *     changes"; the activity says what was done;
 *  2. the activity's own cell-edit lines are dropped once the comparison lists
 *     those cells, or the same edit is counted twice in one sentence;
 *  3. "No changes" is only said when the comparison *finished* with nothing
 *     **and** there is no activity either. A version with no activity at all
 *     keeps the older, weaker caveat, because formatting is then unknown
 *     rather than absent.
 */

/** Activity types the value comparison already counts. */
private val VALUE_TYPES = setOf("values", "cleared")

/** The activity lines to show, overall. */
fun activityItems(activity: DriveVersionActivity?, skipValues: Boolean = false): List<DriveActivityItem> =
    activity?.items.orEmpty().filter { it.text.isNotBlank() && !(skipValues && it.type in VALUE_TYPES) }

/** Everyone with something to show, in the order the server ranked them. */
fun activityPeople(activity: DriveVersionActivity?, skipValues: Boolean = false): List<DriveActivityPerson> =
    activity?.people.orEmpty()
        .map { person ->
            person.copy(
                items = person.items.filter { it.text.isNotBlank() && !(skipValues && it.type in VALUE_TYPES) },
            )
        }
        .filter { it.items.isNotEmpty() }

/**
 * The activity as one line: "Resized columns F, G · Formatted 12 cells", or
 * with several editors "Priya: Resized column F · Riya: Inserted 2 rows".
 */
fun describeActivity(
    activity: DriveVersionActivity?,
    skipValues: Boolean = false,
    nameOf: (String) -> String = { "" },
): String {
    val people = activityPeople(activity, skipValues)
    if (people.size > 1) {
        return people.joinToString(" · ") { person ->
            val name = person.name.ifBlank { nameOf(person.userId).ifBlank { str(S.history_someone) } }
            "$name: ${firstLines(person.items).joinToString(", ")}"
        }
    }
    return firstLines(activityItems(activity, skipValues)).joinToString(" · ")
}

/**
 * One line for a version row: the value comparison and the activity
 * ("4 cells changed · Resized columns F, G").
 */
fun describeChanges(
    changes: DriveVersionChanges?,
    kind: DriveFileKind = DriveFileKind.Spreadsheet,
    nameOf: (String) -> String = { "" },
): String {
    if (changes == null) return ""
    val values = describeValues(changes, kind)
    val activity = describeActivity(changes.activity, skipValues = changes.hasValues, nameOf = nameOf)
    // While the comparison runs, the activity is the better line: "Working out
    // what changed…" beside things that plainly did change reads as a stall.
    if (values.isNotEmpty() && activity.isNotEmpty() && changes.isWorking) return activity
    val line = listOf(values, activity).filter { it.isNotEmpty() }.joinToString(" · ")
    return when {
        line.isNotEmpty() -> line
        changes.status == DriveChangeStatus.Done -> noChangesLabel(kind)
        else -> ""
    }
}

/** "1 cell changed", "1,200 cells changed", "3 paragraphs changed". */
fun changeCountPhrase(count: Int, kind: DriveFileKind): String = when {
    kind == DriveFileKind.Spreadsheet && count == 1 -> str(S.desktop_drive_one_cell_changed)
    kind == DriveFileKind.Spreadsheet -> str(S.desktop_drive_cells_changed, count.grouped())
    count == 1 -> str(S.desktop_drive_one_paragraph_changed)
    else -> str(S.desktop_drive_paragraphs_changed, count.grouped())
}

/** "3 cells changed across 2 sheets" — the detail's summary, once a group count is worth saying. */
fun acrossGroups(phrase: String, groups: Int, kind: DriveFileKind): String = when (kind) {
    DriveFileKind.Document -> str(S.desktop_drive_changed_across_sections, phrase, groups)
    DriveFileKind.Presentation -> str(S.desktop_drive_changed_across_slides, phrase, groups)
    else -> str(S.desktop_drive_changed_across_sheets, phrase, groups)
}

/** "Added" / "Cleared" / "Changed" / "Formula", or a paragraph's own wording. */
fun changeKindLabel(change: DriveChangeKind, kind: DriveFileKind): String {
    val text = kind != DriveFileKind.Spreadsheet
    return when (change) {
        DriveChangeKind.Added -> str(S.history_added)
        DriveChangeKind.Removed -> if (text) str(S.removed) else str(S.desktop_drive_change_cleared)
        DriveChangeKind.Changed -> if (text) str(S.edited) else str(S.history_changed)
        DriveChangeKind.Formula -> str(S.desktop_drive_change_formula)
    }
}

/** "New sheet" / "Deleted sheet" on a whole sheet that appeared or went. */
fun groupStatusLabel(status: DriveGroupStatus, kind: DriveFileKind): String =
    if (kind != DriveFileKind.Spreadsheet) {
        ""
    } else {
        when (status) {
            DriveGroupStatus.Added -> str(S.desktop_drive_new_sheet)
            DriveGroupStatus.Removed -> str(S.desktop_drive_deleted_sheet)
            DriveGroupStatus.Changed -> ""
        }
    }

/**
 * Why no changes are listed, in the detail — or blank when they are.
 *
 * Separate from [describeChanges]: a row has one line to spend and says
 * "Too large to compare", while the detail has room to say why and from what
 * size.
 */
fun changesEmptyMessage(changes: DriveVersionChanges, kind: DriveFileKind): String = when (changes.status) {
    DriveChangeStatus.None -> str(S.desktop_drive_first_version_nothing_to_compare)
    DriveChangeStatus.Skipped -> skippedDetail(changes.reason, kind)
    DriveChangeStatus.Failed -> str(S.desktop_drive_compare_failed_detail)
    else -> ""
}

/**
 * The detail's line when nothing at all was found.
 *
 * [knowsActivity] is whether the version carries an edit log: with one,
 * "No changes" is the truth; without one, formatting changes are simply not
 * known and saying otherwise is a claim the client cannot make.
 */
fun noChangesMessage(kind: DriveFileKind, knowsActivity: Boolean): String = when {
    knowsActivity -> str(S.desktop_drive_no_changes_in_version)
    kind == DriveFileKind.Spreadsheet -> str(S.desktop_drive_no_cell_values_changed)
    else -> str(S.desktop_drive_no_text_changed)
}

// -- who changed what -----------------------------------------------------

/** The name to show for a change's author: the production's, else the edit's. */
fun changeAuthorName(author: DriveChangeAuthor, nameOf: (String) -> String = { "" }): String {
    val someone = str(S.history_someone)
    val known = author.userId.takeIf { it.isNotBlank() }?.let { nameOf(it) }.orEmpty()
    if (known.isNotBlank() && known != someone) return known
    return author.name.ifBlank { known.ifBlank { someone } }
}

/**
 * Everyone who changed a listed cell, most changes first.
 *
 * A cell counts once per person however many times they wrote it, so the
 * chips add up to cells rather than to keystrokes.
 */
fun changeAuthors(groups: List<DriveChangeGroup>): List<DriveChangeAuthorCount> {
    val people = linkedMapOf<String, DriveChangeAuthorCount>()
    groups.forEach { group ->
        group.changes.forEach { change ->
            change.by.map { it.key }.filter { it.isNotEmpty() }.distinct().forEach { key ->
                val author = change.by.first { it.key == key }
                val row = people[key]
                    ?: DriveChangeAuthorCount(key = key, userId = author.userId, name = author.name)
                people[key] = row.copy(count = row.count + 1)
            }
        }
    }
    return people.values.sortedByDescending { it.count }
}

/** The groups holding only [key]'s changes; groups left empty are dropped. */
fun changesByPerson(groups: List<DriveChangeGroup>, key: String?): List<DriveChangeGroup> {
    if (key.isNullOrEmpty()) return groups
    return groups.mapNotNull { group ->
        val mine = group.changes.filter { change -> change.by.any { it.key == key } }
        if (mine.isEmpty()) null else group.copy(changes = mine, total = mine.size)
    }
}

// -- plumbing -------------------------------------------------------------

/** One line about the values half, or why it is not known. */
private fun describeValues(changes: DriveVersionChanges, kind: DriveFileKind): String = when (changes.status) {
    DriveChangeStatus.Done -> doneLine(changes, kind)
    DriveChangeStatus.Pending, DriveChangeStatus.Running -> str(S.desktop_drive_working_out_changes)
    DriveChangeStatus.Failed -> str(S.desktop_drive_compare_failed)
    DriveChangeStatus.Skipped -> skippedLine(changes.reason, kind)
    else -> ""
}

private fun doneLine(changes: DriveVersionChanges, kind: DriveFileKind): String {
    if (changes.cells <= 0) return ""
    val phrase = changeCountPhrase(changes.cells, kind)
    // A document is one flowing body; counting its "sections" would be
    // counting an implementation detail of the comparison.
    if (changes.sheets <= 1 || kind == DriveFileKind.Document) return phrase
    return if (kind == DriveFileKind.Presentation) {
        str(S.desktop_drive_paragraphs_changed_in_slides, changes.cells.grouped(), changes.sheets.grouped())
    } else {
        str(S.desktop_drive_cells_changed_in_sheets, changes.cells.grouped(), changes.sheets.grouped())
    }
}

private fun skippedLine(reason: String, kind: DriveFileKind): String = when (reason) {
    REASON_TOO_LARGE ->
        if (kind == DriveFileKind.Spreadsheet) {
            str(S.desktop_drive_too_large_cell_by_cell)
        } else {
            str(S.desktop_drive_too_large_to_compare)
        }

    REASON_FORMAT_CHANGED -> str(S.desktop_drive_saved_new_format)
    else -> ""
}

private fun skippedDetail(reason: String, kind: DriveFileKind): String = when (reason) {
    REASON_TOO_LARGE ->
        if (kind == DriveFileKind.Spreadsheet) {
            str(S.desktop_drive_too_large_detail_cells)
        } else {
            str(S.desktop_drive_too_large_detail)
        }

    REASON_FORMAT_CHANGED -> str(S.desktop_drive_new_format_detail)
    else -> str(S.desktop_drive_changes_not_listed_type)
}

private fun noChangesLabel(kind: DriveFileKind): String = if (kind == DriveFileKind.Spreadsheet) {
    str(S.desktop_drive_no_cell_changes)
} else {
    str(S.desktop_drive_no_text_changes)
}

/** At most three lines, then "N more", so one row cannot run to a paragraph. */
private fun firstLines(items: List<DriveActivityItem>): List<String> {
    val texts = items.map { it.text }
    if (texts.size <= MAX_ROW_LINES) return texts
    return texts.take(MAX_ROW_LINES) + str(S.desktop_ce_insights_n_more, texts.size - MAX_ROW_LINES)
}

/** "1,200" — grouped the way the web's `toLocaleString` prints it. */
internal fun Int.grouped(): String {
    val digits = toString()
    if (digits.length <= GROUP_SIZE) return digits
    return digits.reversed().chunked(GROUP_SIZE).joinToString(",").reversed()
}

private const val REASON_TOO_LARGE = "too_large"
private const val REASON_FORMAT_CHANGED = "format_changed"
private const val MAX_ROW_LINES = 3
private const val GROUP_SIZE = 3
