package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.drive.domain.DiffRunType
import com.zillit.desktop.feature.drive.domain.DriveCellChange
import com.zillit.desktop.feature.drive.domain.DriveChangeAuthor
import com.zillit.desktop.feature.drive.domain.DriveChangeAuthorCount
import com.zillit.desktop.feature.drive.domain.DriveChangeGroup
import com.zillit.desktop.feature.drive.domain.DriveChangeKind
import com.zillit.desktop.feature.drive.domain.DriveFileKind
import com.zillit.desktop.feature.drive.domain.DriveVersionActivity
import com.zillit.desktop.feature.drive.domain.DriveVersionChanges
import com.zillit.desktop.feature.drive.domain.acrossGroups
import com.zillit.desktop.feature.drive.domain.activityItems
import com.zillit.desktop.feature.drive.domain.activityPeople
import com.zillit.desktop.feature.drive.domain.changeAuthorName
import com.zillit.desktop.feature.drive.domain.changeCountPhrase
import com.zillit.desktop.feature.drive.domain.changeKindLabel
import com.zillit.desktop.feature.drive.domain.changesEmptyMessage
import com.zillit.desktop.feature.drive.domain.groupStatusLabel
import com.zillit.desktop.feature.drive.domain.joinNames
import com.zillit.desktop.feature.drive.domain.noChangesMessage
import com.zillit.desktop.feature.drive.domain.wordDiff
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.VersionHistoryState

/**
 * "What changed" in one version — the web's `versionHistory/VersionChanges`.
 *
 * Two parts, in this order: what the editors *did* (the edit log, which is
 * the only thing that knows about a formatting-only save), then the value
 * comparison sheet by sheet, each change as cell · kind · before → after with
 * the person who wrote it. A document or a deck shows paragraphs instead,
 * with the words that changed marked in place.
 */
@Composable
internal fun ColumnScope.VersionChangesView(
    panel: VersionHistoryState,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    val version = panel.detail
    val changes = panel.changes
    when {
        version == null -> Unit
        // A synthetic row stands for the file as it is now: there is no stored
        // version behind it to compare with anything.
        version.synthetic -> Note(str(S.desktop_drive_file_as_it_is_now))
        panel.changesLoading && changes == null -> PanelSpinner()
        panel.changesFailed -> ChangesFailed(onEvent)
        changes != null -> ChangesBody(panel, changes, nameOf, onEvent)
        else -> Unit
    }
}

@Composable
private fun ColumnScope.ChangesBody(
    panel: VersionHistoryState,
    changes: DriveVersionChanges,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    val kind = panel.fileKind
    // The activity goes above the comparison, and leaves the cell edits to it
    // once there are any to list.
    ActivitySummary(changes.activity, skipValues = changes.total > 0, nameOf = nameOf)
    val message = changesEmptyMessage(changes, kind)
    when {
        changes.isWorking -> Note(str(S.desktop_drive_working_out_changes))
        message.isNotEmpty() || changes.total == 0 -> EmptyChanges(changes, kind, message)
        else -> ChangeList(panel, changes, kind, nameOf, onEvent)
    }
}

/** The spinner both halves of the panel show while they wait. */
@Composable
internal fun PanelSpinner() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.Center,
    ) {
        ZillitSpinner()
    }
}

@Composable
private fun ColumnScope.ChangesFailed(onEvent: (DriveEvent) -> Unit) {
    Note(str(S.desktop_drive_changes_load_failed))
    ZillitButton(
        text = str(S.try_again),
        onClick = { onEvent(DriveEvent.ReloadVersionChanges) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Reload,
    )
}

/** The empty case, which says *why* rather than just "nothing". */
@Composable
private fun ColumnScope.EmptyChanges(changes: DriveVersionChanges, kind: DriveFileKind, message: String) {
    val shows = activityItems(changes.activity).isNotEmpty()
    when {
        message.isNotEmpty() -> Note(message)
        !shows -> Note(noChangesMessage(kind, changes.activity != null))
        else -> Unit
    }
}

@Composable
private fun ColumnScope.ChangeList(
    panel: VersionHistoryState,
    changes: DriveVersionChanges,
    kind: DriveFileKind,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    val authors = panel.authors
    val picked = panel.activePersonKey?.let { key -> authors.firstOrNull { it.key == key } }
    if (authors.size > 1) PersonFilter(authors, panel.activePersonKey, nameOf, onEvent)
    val groups = panel.visibleGroups
    // A document's body is one group; wrapping it in a heading would be a
    // disclosure over the only thing there is.
    val collapsible = kind != DriveFileKind.Document || groups.size > 1
    Summary(picked, changes, groups.size, kind, collapsible, nameOf)
    if (changes.truncated) {
        Hint(str(S.desktop_drive_showing_first_changes, groups.sumOf { it.changes.size }))
    }
    groups.forEachIndexed { index, group ->
        GroupChanges(
            group = group,
            kind = kind,
            collapsible = collapsible,
            // The first few are open, and everything opens once a person is
            // picked: the point of picking one is to read their changes. A
            // press flips whichever way that group started.
            open = (index < GROUPS_OPEN_BY_DEFAULT || picked != null) != (group.name in panel.toggledGroups),
            showAll = group.name in panel.fullGroups,
            nameOf = nameOf,
            onEvent = onEvent,
        )
    }
    if (kind != DriveFileKind.Spreadsheet && changes.activity == null) {
        Hint(str(S.desktop_drive_only_text_listed))
    }
}

/** "3 cells changed by Priya across 2 sheets". */
@Composable
private fun Summary(
    picked: DriveChangeAuthorCount?,
    changes: DriveVersionChanges,
    groups: Int,
    kind: DriveFileKind,
    collapsible: Boolean,
    nameOf: (String) -> String,
) {
    val count = picked?.count ?: changes.total
    var line = changeCountPhrase(count, kind)
    if (picked != null) line = str(S.desktop_drive_changed_by_person, line, picked.displayName(nameOf))
    if (collapsible && groups > 1) line = acrossGroups(line, groups, kind)
    ZillitText(text = line, style = ZillitTheme.typography.label)
}

/** "All · Riya · Aviral" — show only the cells one person changed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonFilter(
    people: List<DriveChangeAuthorCount>,
    selected: String?,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitChoiceChip(
            label = str(S.all),
            selected = selected == null,
            onClick = { onEvent(DriveEvent.FilterVersionChangesBy(null)) },
        )
        people.forEach { person ->
            val name = person.displayName(nameOf)
            val on = selected == person.key
            ZillitTooltip(text = str(S.desktop_drive_person_cells_changed, name, person.count)) {
                ZillitChoiceChip(
                    label = name,
                    selected = on,
                    onClick = {
                        onEvent(DriveEvent.FilterVersionChangesBy(if (on) null else person.key))
                    },
                )
            }
        }
    }
}

/**
 * One sheet or slide, foldable, with its changes.
 *
 * Only the first [CHANGES_PER_GROUP] are drawn until "Show N more": a sheet
 * with four thousand changed cells is a list nobody scrolls, and drawing it
 * costs the panel its smoothness for rows past the first screen.
 */
@Composable
private fun ColumnScope.GroupChanges(
    group: DriveChangeGroup,
    kind: DriveFileKind,
    collapsible: Boolean,
    open: Boolean,
    showAll: Boolean,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    if (collapsible) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.small)
                .clickable { onEvent(DriveEvent.ToggleVersionChangeGroup(group.name)) }
                .padding(vertical = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(
                icon = if (open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                tint = ZillitTheme.colors.textSecondary,
                size = DISCLOSURE,
            )
            ZillitText(
                text = group.name,
                style = ZillitTheme.typography.label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            val status = groupStatusLabel(group.status, kind)
            if (status.isNotEmpty()) {
                ZillitText(
                    text = status,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            ZillitText(
                text = group.total.toString(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
    if (collapsible && !open) return
    val rows = if (showAll) group.changes else group.changes.take(CHANGES_PER_GROUP)
    rows.forEach { change ->
        if (kind == DriveFileKind.Spreadsheet) {
            CellChangeRow(change, kind, nameOf)
        } else {
            TextChangeRow(change, kind, nameOf)
        }
    }
    val hidden = group.changes.size - rows.size
    if (hidden > 0) {
        ZillitButton(
            text = str(S.desktop_drive_show_n_more, hidden),
            onClick = { onEvent(DriveEvent.ShowAllVersionChanges(group.name)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}

@Composable
private fun CellChangeRow(change: DriveCellChange, kind: DriveFileKind, nameOf: (String) -> String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(CELL_LABEL)
                .clip(ZillitTheme.shapes.small)
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.xs, vertical = 2.dp),
        ) {
            ZillitText(text = change.cell, style = ZillitTheme.typography.labelSmall, maxLines = 1)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = changeKindLabel(change.kind, kind),
                    style = ZillitTheme.typography.labelSmall,
                    color = change.kind.tint(),
                )
                ChangeAuthor(change, nameOf)
            }
            ZillitText(text = change.beforeAfter(), style = ZillitTheme.typography.bodySmall)
        }
    }
}

/** An edited paragraph shows its words struck out and added in place. */
@Composable
private fun TextChangeRow(change: DriveCellChange, kind: DriveFileKind, nameOf: (String) -> String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = changeKindLabel(change.kind, kind),
                style = ZillitTheme.typography.labelSmall,
                color = change.kind.tint(),
            )
            ChangeAuthor(change, nameOf)
        }
        ZillitText(text = change.paragraph(), style = ZillitTheme.typography.bodySmall)
    }
}

/** Who wrote the cell: the last person to, with "+1" when somebody wrote it before them. */
@Composable
private fun ChangeAuthor(change: DriveCellChange, nameOf: (String) -> String) {
    val last = change.by.firstOrNull() ?: return
    val names = change.by.map { changeAuthorName(it, nameOf) }
    val tooltip = if (names.size > 1) {
        str(S.desktop_drive_also_edited_by, names.first(), joinNames(names.drop(1)))
    } else {
        names.first()
    }
    ZillitTooltip(text = tooltip) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitAvatar(name = names.first(), userId = last.userId, size = AUTHOR_AVATAR)
            ZillitText(
                text = names.first(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
            )
            if (names.size > 1) {
                ZillitText(
                    text = "+${names.size - 1}",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

/**
 * What the editors did, from the edit log: "Resized columns F, G",
 * "Inserted 2 rows"… and the same per person once more than one edited.
 *
 * [skipValues] drops the lines about cell values when the comparison below
 * lists those cells, so one edit is not counted twice on one screen.
 */
@Composable
private fun ColumnScope.ActivitySummary(
    activity: DriveVersionActivity?,
    skipValues: Boolean,
    nameOf: (String) -> String,
) {
    val items = activityItems(activity, skipValues)
    if (items.isEmpty()) return
    val people = activityPeople(activity, skipValues)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            ZillitText(text = "• ${item.text}", style = ZillitTheme.typography.bodySmall)
        }
        if (people.size > 1) {
            people.forEach { person ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(PERSON_DOT)
                            .clip(ZillitTheme.shapes.pill)
                            .background(avatarHue(person.name)),
                    )
                    ZillitText(
                        text = person.name.ifBlank { nameOf(person.userId) },
                        style = ZillitTheme.typography.labelSmall,
                    )
                    ZillitText(
                        text = person.items.joinToString(" · ") { it.text },
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (activity?.truncated == true) {
            Hint(str(S.desktop_drive_activity_summarised, activity.ops))
        }
    }
}

@Composable
private fun Note(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
}

@Composable
private fun Hint(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
    )
}

/** "1" → "2" as one line, with the old value struck through. */
@Composable
private fun DriveCellChange.beforeAfter(): AnnotatedString = buildAnnotatedString {
    val colors = ZillitTheme.colors
    if (before.isNotEmpty()) {
        withStyle(SpanStyle(color = colors.textMuted, textDecoration = TextDecoration.LineThrough)) { append(before) }
    }
    if (before.isNotEmpty() && after.isNotEmpty()) append(" → ")
    if (after.isNotEmpty()) {
        withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.Medium)) { append(after) }
    }
}

/** The paragraph with its changed words marked, or whole when it was added or removed. */
@Composable
private fun DriveCellChange.paragraph(): AnnotatedString {
    val colors = ZillitTheme.colors
    val removed = SpanStyle(color = colors.danger, textDecoration = TextDecoration.LineThrough)
    val added = SpanStyle(color = colors.success)
    return buildAnnotatedString {
        when (kind) {
            DriveChangeKind.Removed -> withStyle(removed) { append(before) }
            DriveChangeKind.Added -> withStyle(added) { append(after) }
            else -> wordDiff(before, after).forEach { run ->
                when (run.type) {
                    DiffRunType.Removed -> withStyle(removed) { append(run.text) }
                    DiffRunType.Added -> withStyle(added) { append(run.text) }
                    DiffRunType.Same -> append(run.text)
                }
            }
        }
    }
}

/** The name a chip or a summary shows for one author. */
private fun DriveChangeAuthorCount.displayName(nameOf: (String) -> String): String =
    changeAuthorName(DriveChangeAuthor(userId = userId, name = name), nameOf)

@Composable
private fun DriveChangeKind.tint(): Color = when (this) {
    DriveChangeKind.Added -> ZillitTheme.colors.success
    DriveChangeKind.Removed -> ZillitTheme.colors.danger
    DriveChangeKind.Changed -> ZillitTheme.colors.info
    DriveChangeKind.Formula -> ZillitTheme.colors.accent
}

private val CELL_LABEL = 56.dp
private val AUTHOR_AVATAR = 16.dp
private val PERSON_DOT = 8.dp
private val DISCLOSURE = 14.dp
private const val CHANGES_PER_GROUP = 40
private const val GROUPS_OPEN_BY_DEFAULT = 3
