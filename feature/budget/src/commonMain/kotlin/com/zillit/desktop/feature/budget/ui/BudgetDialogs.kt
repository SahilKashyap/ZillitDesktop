package com.zillit.desktop.feature.budget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetRules
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** Every dialog the budget rail can open, drawn over the whole screen. */
@Composable
internal fun BudgetDialogs(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit, seams: BudgetScreenSeams) {
    state.upload?.let { UploadDialog(state, it, onEvent) }
    state.members?.let { MembersDialog(it, onEvent, seams) }
    state.activity?.let { ActivityDialog(it, onEvent, seams) }
    state.drawer?.let { DepartmentDrawer(it, onEvent) }
}

/**
 * The web's `DocumentModal` in budget dress (`DocumentModal.jsx:798-836`):
 * the picked file, the date that names the upload (required, never after
 * today), and on a television production the episode.
 */
@Composable
@Suppress("LongMethod") // One dialog, drawn in one place.
private fun UploadDialog(state: BudgetUiState, draft: BudgetUploadDraft, onEvent: (BudgetEvent) -> Unit) {
    val zone = TimeZone.currentSystemDefault()
    ZillitDialogShell(
        title = "Upload budget",
        subtitle = draft.departmentName.ifBlank { state.mode.title },
        icon = ZillitIcons.Upload,
        visible = true,
        onDismiss = { onEvent(BudgetEvent.UploadCancel) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BudgetEvent.UploadCancel) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Upload",
                onClick = { onEvent(BudgetEvent.UploadConfirm) },
                leadingIcon = ZillitIcons.Upload,
                enabled = !state.busy,
                loading = state.busy,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.medium)
                    .background(ZillitTheme.colors.surfaceSunken)
                    .padding(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitFileBadge(fileName = draft.fileName)
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = draft.fileName,
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textPrimary,
                        maxLines = 1,
                    )
                    ZillitText(
                        text = readableSize(draft.sizeBytes),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
            // The field is fed its own text back, not the parsed value: a
            // controlled field that only echoed a complete date threw away
            // every keystroke of a partial one (found live, 2026-09-15).
            ZillitDateField(
                value = draft.dateText,
                onValueChange = { text ->
                    val millis = runCatching {
                        LocalDate.parse(text.trim()).atStartOfDayIn(zone).toEpochMilliseconds()
                    }.getOrNull()
                    onEvent(BudgetEvent.UploadDateChanged(text, millis))
                },
                label = "Budget date",
                helperText = draft.dateMillis
                    ?.let { BudgetRules.uploadTitle(state.mode.type, draft.departmentName, BudgetRules.dateLabel(it)) }
                    ?.let { "Will be titled “$it”" }
                    ?: "Names the version — today or earlier.",
                errorText = draft.complaint?.takeIf { it.contains("date", ignoreCase = true) },
            )
            if (state.context.isTelevision) {
                ZillitTextField(
                    value = draft.episode,
                    onValueChange = { onEvent(BudgetEvent.UploadEpisodeChanged(it)) },
                    label = "Episode number",
                    placeholder = "e.g. 3",
                    errorText = draft.complaint?.takeIf { it.contains("episode", ignoreCase = true) },
                )
            }
            if (draft.sizeBytes > OVERSIZE_NOTICE_BYTES) {
                ZillitNotice(
                    text = "Over 25 MB: the file is kept here, and every admin is told it will not be mailed out.",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Warning,
                )
            }
        }
    }
}

/**
 * `MembersModal.jsx`: for a member, a list to pick one from — the pick opens
 * their thread; for a group, a name, checkboxes, and Create.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // One dialog, drawn in one place.
private fun MembersDialog(dialog: BudgetMembersDialog, onEvent: (BudgetEvent) -> Unit, seams: BudgetScreenSeams) {
    val isGroup = dialog.kind == BudgetMembersDialog.Kind.Group
    ZillitDialogShell(
        title = dialog.kind.title,
        subtitle = if (isGroup) "Everyone who can see this budget" else "Start a private conversation",
        icon = if (isGroup) ZillitIcons.Users else ZillitIcons.UserPlus,
        visible = true,
        onDismiss = { onEvent(BudgetEvent.DismissMembers) },
        scrollable = false,
        actions = if (!isGroup) {
            null
        } else {
            {
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(BudgetEvent.DismissMembers) },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitButton(
                    text = "Create group",
                    onClick = { onEvent(BudgetEvent.ConfirmMembers) },
                    leadingIcon = ZillitIcons.Users,
                    loading = dialog.saving,
                    enabled = !dialog.saving,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (isGroup) {
                ZillitTextField(
                    value = dialog.groupName,
                    onValueChange = { onEvent(BudgetEvent.GroupNameChanged(it)) },
                    label = "Group name",
                    placeholder = "3 to 25 characters",
                    errorText = dialog.complaint,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitSearchField(
                    value = dialog.search,
                    onValueChange = { onEvent(BudgetEvent.MembersSearch(it)) },
                    placeholder = "Search by name or designation",
                    modifier = Modifier.weight(1f),
                )
                if (isGroup && dialog.visible.isNotEmpty()) {
                    val allPicked = dialog.visible.all { it.userId in dialog.picked }
                    ZillitButton(
                        text = if (allPicked) "Clear" else "Select all",
                        onClick = { onEvent(BudgetEvent.PickAll) },
                        variant = ButtonVariant.Tertiary,
                    )
                }
            }
            when {
                dialog.loading -> repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth(), height = 48.dp) }
                dialog.visible.isEmpty() -> ZillitText(
                    text = if (dialog.candidates.isEmpty()) {
                        if (isGroup) {
                            "Nobody else can see this budget yet."
                        } else {
                            "Everyone who can see this budget is already listed."
                        }
                    } else {
                        "No one matches."
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.padding(ZillitTheme.spacing.md),
                )

                else -> ZillitScrollColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = MEMBERS_MAX_HEIGHT),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    dialog.visible.forEach { person ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .background(ZillitTheme.colors.surfaceSunken)
                                .clickable { onEvent(BudgetEvent.TogglePick(person.userId)) }
                                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            RailFace(person.fullName, person.userId, seams.loadAvatar)
                            Column(Modifier.weight(1f)) {
                                ZillitText(
                                    text = person.fullName + if (person.isAdmin) "  (Admin)" else "",
                                    style = ZillitTheme.typography.bodyMedium,
                                    color = ZillitTheme.colors.textPrimary,
                                    maxLines = 1,
                                )
                                if (person.designation.isNotBlank()) {
                                    ZillitText(
                                        text = person.designation,
                                        style = ZillitTheme.typography.labelSmall,
                                        color = ZillitTheme.colors.textMuted,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (isGroup) {
                                ZillitCheckbox(
                                    checked = person.userId in dialog.picked,
                                    onCheckedChange = { onEvent(BudgetEvent.TogglePick(person.userId)) },
                                )
                            } else {
                                ZillitIcon(
                                    ZillitIcons.ChevronRight,
                                    tint = ZillitTheme.colors.textMuted,
                                    size = 16.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** `ScriptDrawer.jsx`: who has viewed or downloaded, and how many times. */
@Composable
@Suppress("LongMethod") // One dialog, drawn in one place.
private fun ActivityDialog(dialog: BudgetActivityDialog, onEvent: (BudgetEvent) -> Unit, seams: BudgetScreenSeams) {
    val rows = dialog.rows
        .filter { it.countOf(dialog.activity) > 0 }
        .filter { row ->
            val needle = dialog.search.trim().lowercase()
            needle.isEmpty() || (seams.nameOf(row.userId) ?: row.userId).lowercase().contains(needle)
        }
    ZillitDialogShell(
        title = dialog.activity.title,
        icon = if (dialog.activity == BudgetActivity.View) ZillitIcons.Eye else ZillitIcons.Download,
        visible = true,
        onDismiss = { onEvent(BudgetEvent.DismissActivity) },
        scrollable = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSearchField(
                value = dialog.search,
                onValueChange = { onEvent(BudgetEvent.ActivitySearch(it)) },
                placeholder = "Search by name",
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                dialog.loading -> repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth(), height = 48.dp) }
                rows.isEmpty() -> ZillitText(
                    text = "No data found.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.padding(ZillitTheme.spacing.md),
                )

                else -> ZillitScrollColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = MEMBERS_MAX_HEIGHT),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    rows.forEach { row ->
                        val name = seams.nameOf(row.userId) ?: "Crew member"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .background(ZillitTheme.colors.infoSoft)
                                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            RailFace(name, row.userId, seams.loadAvatar)
                            Column(Modifier.weight(1f)) {
                                ZillitText(
                                    text = name,
                                    style = ZillitTheme.typography.titleSmall,
                                    color = ZillitTheme.colors.textPrimary,
                                    maxLines = 1,
                                )
                                ZillitText(
                                    text = "${dialog.activity.title} : ${row.countOf(dialog.activity)}",
                                    style = ZillitTheme.typography.labelSmall,
                                    color = ZillitTheme.colors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * `ShowDrawerForDepartments.jsx`: the departments with no budget yet, each
 * with its own "Upload budget in …" — the drawer the directory's "+" opens.
 */
@Composable
@Suppress("LongMethod") // One dialog, drawn in one place.
private fun DepartmentDrawer(drawer: BudgetDepartmentDrawer, onEvent: (BudgetEvent) -> Unit) {
    ZillitDialogShell(
        title = "Departments",
        subtitle = "Departments without a budget yet",
        icon = ZillitIcons.Building,
        visible = true,
        onDismiss = { onEvent(BudgetEvent.CloseDrawer) },
        scrollable = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSearchField(
                value = drawer.search,
                onValueChange = { onEvent(BudgetEvent.DrawerSearch(it)) },
                placeholder = "Search department",
                modifier = Modifier.fillMaxWidth(),
            )
            val rows = drawer.visible
            if (rows.isEmpty()) {
                ZillitText(
                    text = if (drawer.departments.isEmpty()) {
                        "Every department already has a budget."
                    } else {
                        "No department matches."
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.padding(ZillitTheme.spacing.md),
                )
            } else {
                ZillitScrollColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = MEMBERS_MAX_HEIGHT),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    rows.forEach { department ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .background(ZillitTheme.colors.surfaceSunken)
                                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            RailInitial(department.name.take(1), size = 32.dp)
                            ZillitText(
                                text = department.name,
                                style = ZillitTheme.typography.bodyMedium,
                                color = ZillitTheme.colors.textPrimary,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            ZillitButton(
                                text = "Upload budget",
                                onClick = { onEvent(BudgetEvent.UploadRequested(department.id)) },
                                variant = ButtonVariant.Secondary,
                                leadingIcon = ZillitIcons.Upload,
                                size = ButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SKELETON_ROWS = 3
private val MEMBERS_MAX_HEIGHT = 380.dp
private const val OVERSIZE_NOTICE_BYTES = 25L * 1024 * 1024
