package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.drive.domain.DrivePerson
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.FileAccessLevel
import com.zillit.desktop.feature.drive.ui.AccessDraft
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The permissions picker every drawer shares — `FilePermissionsPanel`,
 * `CreateFolderDrawer`'s access section, `ShareDrawer`'s people list.
 *
 * Two modes: **Project users** applies one grant to everyone eligible;
 * **Specific users** picks people one by one, each with their own grant.
 *
 * In specific mode the grant is three nested boxes, drawn the same way for
 * both kinds: Viewer / Editor / Owner on a folder, View / Download / Edit on
 * a file. A box takes the ones below it with it, unticking drops one level,
 * and unticking the lowest stops sharing — [AccessDraft.clicked] is the whole
 * rule. A person a parent folder already covers ([AccessDraft.inherited]) has
 * those boxes and their own checkbox locked: they can be given more here,
 * never less.
 *
 * [locked] people are left out of the list entirely — the item's creator, and
 * the viewer themself, for whom sharing means nothing.
 */
@Composable
@Suppress("LongParameterList") // One seam per thing the three callers differ on.
internal fun AccessPicker(
    draft: AccessDraft,
    onChange: (AccessDraft) -> Unit,
    people: List<DrivePerson>,
    forFolder: Boolean,
    modifier: Modifier = Modifier,
    locked: Set<String> = emptySet(),
    privateHint: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        if (privateHint != null) {
            ZillitText(
                text = privateHint,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitChoiceChip(
                label = str(S.drive_permissions_mode_project),
                selected = draft.projectWide,
                onClick = {
                    // The mode is the only thing a switch keeps: a grant
                    // chosen one way does not carry over to the other. The
                    // inherited floors do — they are the server's, not a choice.
                    if (enabled) onChange(AccessDraft(projectWide = true, inherited = draft.inherited))
                },
            )
            ZillitChoiceChip(
                label = str(S.drive_permissions_mode_specific),
                selected = !draft.projectWide,
                onClick = {
                    if (enabled) onChange(AccessDraft(projectWide = false, inherited = draft.inherited))
                },
            )
        }

        if (draft.projectWide) {
            ProjectWide(draft, onChange, people, forFolder, enabled)
        } else {
            SpecificPeople(draft, onChange, people, forFolder, locked, enabled)
            ZillitText(
                text = if (forFolder) str(S.desktop_drive_folder_nest_hint) else str(S.desktop_drive_file_nest_hint),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** The grid's heading: how many are selected, and what each box column means. */
@Composable
private fun GridHead(selectedCount: Int, forFolder: Boolean) {
    val colors = ZillitTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = when (selectedCount) {
                0 -> str(S.desktop_drive_no_users_selected)
                1 -> str(S.desktop_drive_user_selected_one)
                else -> str(S.drive_users_selected, selectedCount)
            },
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        levelColumns(forFolder).forEach { column ->
            ZillitText(
                text = column.label,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.width(LEVEL_COLUMN),
            )
        }
    }
}

/** The three nested grants, in level order — what the grid's columns are. */
private data class LevelColumn(val level: Int, val label: String)

@Composable
private fun levelColumns(forFolder: Boolean): List<LevelColumn> = if (forFolder) {
    DriveRole.entries.sortedBy { it.level }.map { LevelColumn(it.level, it.label) }
} else {
    FileAccessLevel.entries.sortedBy { it.level }.map { LevelColumn(it.level, it.label) }
}

@Composable
private fun ProjectWide(
    draft: AccessDraft,
    onChange: (AccessDraft) -> Unit,
    people: List<DrivePerson>,
    forFolder: Boolean,
    enabled: Boolean,
) {
    ZillitText(
        text = if (people.size == 1) {
            str(S.desktop_drive_applies_to_one_user)
        } else {
            str(S.drive_permissions_project_applies_format, people.size)
        },
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    if (forFolder) {
        val none = str(S.drive_permission_select_hint)
        ZillitSelect(
            value = draft.projectRole?.label ?: none,
            options = listOf(none) + DriveRole.entries.map { it.label },
            onSelect = { label ->
                onChange(draft.copy(projectRole = DriveRole.entries.firstOrNull { it.label == label }))
            },
            label = { it },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        val none = str(S.drive_permission_select_hint)
        ZillitSelect(
            value = draft.projectLevel?.label ?: none,
            options = listOf(none) + FileAccessLevel.entries.map { it.label },
            onSelect = { label ->
                onChange(draft.copy(projectLevel = FileAccessLevel.entries.firstOrNull { it.label == label }))
            },
            label = { it },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SpecificPeople(
    draft: AccessDraft,
    onChange: (AccessDraft) -> Unit,
    people: List<DrivePerson>,
    forFolder: Boolean,
    locked: Set<String>,
    enabled: Boolean,
) {
    val colors = ZillitTheme.colors
    val query = draft.search.trim().lowercase()
    val visible = people.filter { person ->
        person.id !in locked &&
            (
                query.isEmpty() || person.name.lowercase().contains(query) ||
                    person.designation.lowercase().contains(query)
                )
    }
    ZillitSearchField(
        value = draft.search,
        onValueChange = { onChange(draft.copy(search = it)) },
        placeholder = str(S.drive_search_team_members),
        enabled = enabled,
    )
    GridHead(draft.selectedCount, forFolder)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .background(colors.surface),
    ) {
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
                ZillitText(
                    text = str(S.desktop_drive_no_team_members_found),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        } else {
            // A fixed-height lazy list: this picker sits in a scrolling sheet,
            // and a lazy list measured against infinity throws. Sized to its
            // rows so a short crew does not leave a void beneath.
            LazyColumn(modifier = Modifier.height((ROW_HEIGHT * visible.size).coerceAtMost(LIST_MAX_HEIGHT))) {
                items(visible, key = { it.id }) { person ->
                    PersonRow(person, draft, onChange, forFolder, enabled)
                    ZillitDivider()
                }
            }
        }
    }
}

/**
 * One person's row: their name, and the three nested boxes.
 *
 * [AccessDraft.inherited] is the floor a folder above set. Its boxes and the
 * row's own checkbox are disabled, and the note under the name says where the
 * access came from instead of the person's designation — the same wording the
 * web shows.
 */
@Composable
private fun PersonRow(
    person: DrivePerson,
    draft: AccessDraft,
    onChange: (AccessDraft) -> Unit,
    forFolder: Boolean,
    enabled: Boolean,
) {
    val colors = ZillitTheme.colors
    val level = draft.levelOf(person.id, forFolder)
    val floor = draft.floor(person.id, forFolder)
    val selected = level > 0
    fun toggle(on: Boolean) {
        if (floor > 0) return
        onChange(draft.atLevel(person.id, if (on) 1 else 0, forFolder))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft.copy(alpha = SELECTED_WASH) else Color.Transparent)
            .clickable(enabled = enabled && floor == 0) { toggle(!selected) }
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitCheckbox(checked = selected, onCheckedChange = ::toggle, enabled = enabled && floor == 0)
        ZillitAvatar(name = person.name, userId = person.id, size = PERSON_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = person.name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            val note = accessNote(person, floor, forFolder)
            if (note.isNotBlank()) {
                ZillitText(
                    text = note,
                    style = ZillitTheme.typography.labelSmall,
                    color = if (floor > 0) colors.info else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        levelColumns(forFolder).forEach { column ->
            Box(modifier = Modifier.width(LEVEL_COLUMN)) {
                ZillitCheckbox(
                    checked = level >= column.level,
                    onCheckedChange = { onChange(draft.clicked(person.id, column.level, forFolder)) },
                    // At or below the inherited floor: this is not ours to take away.
                    enabled = enabled && column.level > floor,
                )
            }
        }
    }
}

private val LIST_MAX_HEIGHT = 280.dp
private val ROW_HEIGHT = 53.dp
/**
 * What is said under the name: where the access came from when a folder above
 * gave it, and the person's designation otherwise.
 */
@Composable
private fun accessNote(person: DrivePerson, floor: Int, forFolder: Boolean): String = when {
    floor > 0 && forFolder -> str(S.desktop_drive_access_through_parent)
    floor > 0 -> str(S.desktop_drive_access_through_folder)
    else -> person.designation
}

private val PERSON_AVATAR = 32.dp
/** Each box column, header and boxes alike, so the two line up. */
private val LEVEL_COLUMN = 58.dp
private const val SELECTED_WASH = 0.5f
