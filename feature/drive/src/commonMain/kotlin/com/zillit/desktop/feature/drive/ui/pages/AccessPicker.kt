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
import androidx.compose.foundation.layout.size
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
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.feature.drive.domain.DrivePerson
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.FileAccessLevel
import com.zillit.desktop.feature.drive.ui.AccessDraft

/**
 * The permissions picker every drawer shares — `FilePermissionsPanel`,
 * `CreateFolderDrawer`'s access section, `ShareDrawer`'s people list.
 *
 * Two modes: **Project users** applies one grant to everyone eligible;
 * **Specific users** picks people one by one, each with their own grant.
 * Folders grant roles (owner / editor / viewer); files grant levels
 * (view / download / edit). [locked] people are listed but cannot be
 * changed — the item's creator, and the viewer themself.
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
    showInherit: Boolean = forFolder,
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
                label = "Project users",
                selected = draft.projectWide,
                onClick = {
                    if (enabled) onChange(AccessDraft(projectWide = true, inheritToChildren = draft.inheritToChildren))
                },
            )
            ZillitChoiceChip(
                label = "Specific users",
                selected = !draft.projectWide,
                onClick = {
                    if (enabled) onChange(AccessDraft(projectWide = false, inheritToChildren = draft.inheritToChildren))
                },
            )
        }
        if (forFolder) RoleLegend()

        if (draft.projectWide) {
            ProjectWide(draft, onChange, people, forFolder, enabled)
        } else {
            SpecificPeople(draft, onChange, people, forFolder, locked, enabled)
        }

        val anyGrant = if (draft.projectWide) {
            draft.projectRole != null || draft.projectLevel != null
        } else {
            draft.selectedCount > 0
        }
        if (showInherit && anyGrant) {
            ZillitCheckbox(
                checked = draft.inheritToChildren,
                onCheckedChange = { onChange(draft.copy(inheritToChildren = it)) },
                label = "Apply access to all subfolders",
                enabled = enabled,
            )
        }
    }
}

/** Owner / Editor / Viewer with their tints, as a hover hint — the web's role legend. */
@Composable
private fun RoleLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        DriveRole.entries.forEach { role ->
            ZillitTooltip(text = role.description) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(LEGEND_DOT)
                            .clip(ZillitTheme.shapes.pill)
                            .background(roleTint(role)),
                    )
                    ZillitText(
                        text = role.label,
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun roleTint(role: DriveRole): Color = when (role) {
    DriveRole.Owner -> ZillitTheme.colors.danger
    DriveRole.Editor -> ZillitTheme.colors.info
    DriveRole.Viewer -> ZillitTheme.colors.success
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
        text = "Applies to all ${people.size} project user${if (people.size == 1) "" else "s"} with Drive view access.",
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    if (forFolder) {
        val none = "Select permission"
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
        val none = "Select permission"
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
        placeholder = "Search team members…",
        enabled = enabled,
    )
    ZillitText(
        text = if (draft.selectedCount > 0) {
            "${draft.selectedCount} user${if (draft.selectedCount == 1) "" else "s"} selected"
        } else {
            "No users selected"
        },
        style = ZillitTheme.typography.labelSmall,
        color = colors.textSecondary,
    )
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
                    text = "No team members found",
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

@Composable
private fun PersonRow(
    person: DrivePerson,
    draft: AccessDraft,
    onChange: (AccessDraft) -> Unit,
    forFolder: Boolean,
    enabled: Boolean,
) {
    val colors = ZillitTheme.colors
    val selected = if (forFolder) person.id in draft.roles else person.id in draft.levels
    fun toggle(on: Boolean) {
        onChange(
            if (forFolder) {
                draft.copy(
                    roles = if (on) {
                        draft.roles + (person.id to (draft.roles[person.id] ?: DriveRole.Viewer))
                    } else {
                        draft.roles - person.id
                    },
                )
            } else {
                draft.copy(
                    levels = if (on) {
                        draft.levels + (person.id to (draft.levels[person.id] ?: FileAccessLevel.View))
                    } else {
                        draft.levels - person.id
                    },
                )
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft.copy(alpha = SELECTED_WASH) else Color.Transparent)
            .clickable(enabled = enabled) { toggle(!selected) }
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitCheckbox(checked = selected, onCheckedChange = ::toggle, enabled = enabled)
        ZillitAvatar(name = person.name, size = PERSON_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = person.name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            if (person.designation.isNotBlank()) {
                ZillitText(
                    text = person.designation,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            if (forFolder) {
                ZillitSelect(
                    value = draft.roles[person.id] ?: DriveRole.Viewer,
                    options = DriveRole.entries,
                    onSelect = { onChange(draft.copy(roles = draft.roles + (person.id to it))) },
                    label = { it.label },
                    enabled = enabled,
                    modifier = Modifier.width(GRANT_WIDTH),
                )
            } else {
                ZillitSelect(
                    value = draft.levels[person.id] ?: FileAccessLevel.View,
                    options = FileAccessLevel.entries,
                    onSelect = { onChange(draft.copy(levels = draft.levels + (person.id to it))) },
                    label = { it.label },
                    enabled = enabled,
                    modifier = Modifier.width(GRANT_WIDTH),
                )
            }
        }
    }
}

private val LEGEND_DOT = 8.dp
private val LIST_MAX_HEIGHT = 280.dp
private val ROW_HEIGHT = 53.dp
private val PERSON_AVATAR = 32.dp
private val GRANT_WIDTH = 120.dp
private const val SELECTED_WASH = 0.5f
