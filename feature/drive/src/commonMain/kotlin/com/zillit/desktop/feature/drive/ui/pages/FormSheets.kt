package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.EditDraft
import com.zillit.desktop.feature.drive.ui.MAX_FOLDER_NAME
import com.zillit.desktop.feature.drive.ui.NewFolderDraft
import com.zillit.desktop.feature.drive.ui.PickedFile
import com.zillit.desktop.feature.drive.ui.UploadDraft
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The upload drawer — `UploadFilesDrawer.jsx`: a destination (at the root),
 * a drop zone with "Choose files" / "Choose folder", the picked files as a
 * tree, the unsupported ones set aside, optional details, and the file
 * permissions section.
 */
@Composable
@Suppress("LongMethod") // One drawer; its sections read top to bottom as the web's does.
internal fun UploadSheet(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val draft = state.upload
    var hovering by remember { mutableStateOf(false) }
    DriveSideSheet(
        title = str(S.dd_empty_upload_cta),
        subtitle = if (state.folderId != null) str(S.drive_destination_format, state.currentFolderName) else null,
        visible = draft != null,
        onDismiss = { onEvent(DriveEvent.CloseUpload) },
        icon = ZillitIcons.Upload,
        width = SHEET_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DriveEvent.CloseUpload) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.upload),
                onClick = { onEvent(DriveEvent.SubmitUpload) },
                enabled = draft?.canSubmit == true,
                leadingIcon = ZillitIcons.Upload,
            )
        },
    ) {
        if (draft == null) return@DriveSideSheet
        if (state.folderId == null) {
            DestinationField(
                folders = state.listing.folders,
                pickExisting = draft.pickExisting,
                selectedId = draft.destinationFolderId,
                onChange = { existing, id -> onEvent(DriveEvent.UploadDestination(existing, id)) },
                title = str(S.drive_pick_dest_title),
                rootLabel = str(S.drive_pick_drive_root),
                rootHint = str(S.desktop_drive_upload_files_root_hint),
            )
        }

        DropZone(
            hovering = hovering,
            onHover = { hovering = it },
            onFiles = { onEvent(DriveEvent.AddUploadFiles(it)) },
            onPickFiles = { onEvent(DriveEvent.PickUploadFiles) },
            onPickFolder = { onEvent(DriveEvent.PickUploadFolder) },
        )

        if (draft.files.isNotEmpty()) PickedFilesTree(draft, onEvent)
        if (draft.unsupported.isNotEmpty()) UnsupportedList(draft, onEvent)

        ZillitButton(
            text = if (draft.showDetails) {
                str(S.desktop_drive_hide_details)
            } else {
                str(S.desktop_drive_add_details_optional)
            },
            onClick = { onEvent(DriveEvent.ToggleUploadDetails) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        if (draft.showDetails) {
            ZillitTextField(
                value = draft.description,
                onValueChange = { onEvent(DriveEvent.UploadDescription(it)) },
                label = str(S.description),
                placeholder = str(S.desktop_drive_files_purpose_hint),
                singleLine = false,
            )
        }

        if (state.sharePeople.isNotEmpty()) {
            CollapsibleAccess(
                title = str(S.drive_file_permissions),
                expanded = draft.accessExpanded,
                count = draft.access.selectedCount,
                onToggle = { onEvent(DriveEvent.ToggleUploadAccess) },
            ) {
                AccessPicker(
                    draft = draft.access,
                    onChange = { onEvent(DriveEvent.UploadAccess(it)) },
                    people = state.sharePeople.filterNot { it.id == state.viewer.userId },
                    forFolder = false,
                    privateHint = str(S.desktop_drive_skip_permissions_files),
                    showInherit = false,
                )
            }
        }
    }
}

/** The dashed drop target with its two pickers — antd's `Dragger`. */
@Composable
internal fun DropZone(
    hovering: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .externalFileDrop(enabled = true, onHover = onHover, onFiles = onFiles)
            .clip(ZillitTheme.shapes.large)
            .background(if (hovering) colors.accentSoft else colors.surfaceSunken)
            .border(
                width = if (hovering) 2.dp else 1.dp,
                color = if (hovering) colors.accent else colors.borderStrong,
                shape = ZillitTheme.shapes.large,
            )
            .padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon = ZillitIcons.Inbox, tint = colors.accent, size = DROP_ICON)
        ZillitText(text = str(S.desktop_drive_drag_files_here), style = ZillitTheme.typography.titleSmall)
        ZillitText(
            text = str(S.desktop_drive_folder_structure_preserved),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
        ) {
            ZillitButton(
                text = str(S.desktop_drive_choose_files),
                onClick = onPickFiles,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.choose_folder),
                onClick = onPickFolder,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** The picked files, grouped under the folders they came from. */
@Composable
@Suppress("LongMethod") // The grouped file list, with its per-file row.
private fun PickedFilesTree(draft: UploadDraft, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val grouped = draft.files.groupBy { it.relativeDirectory }.toSortedMap()
    SheetSection(
        title = if (draft.files.size == 1) {
            str(S.desktop_drive_files_selected_one)
        } else {
            str(S.desktop_drive_files_selected_many, draft.files.size)
        },
        trailing = {
            ZillitButton(
                text = str(S.docusign_initials_clear_all),
                onClick = { onEvent(DriveEvent.ClearUploadFiles) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
    ) {
        Column(
            modifier = Modifier.heightIn(max = TREE_MAX).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            grouped.forEach { (directory, files) ->
                if (directory.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
                    ) {
                        ZillitIcon(icon = ZillitIcons.Folder, tint = colors.accent, size = ZillitTheme.spacing.lg)
                        ZillitText(text = directory, style = ZillitTheme.typography.label, maxLines = 1)
                    }
                }
                files.take(MAX_LISTED).forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = if (directory.isEmpty()) 0.dp else ZillitTheme.spacing.xl),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExtBadge(file.extension, size = SMALL_BADGE)
                        ZillitText(
                            text = file.name,
                            style = ZillitTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        ZillitText(
                            text = formatBytes(file.sizeBytes),
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Close,
                            contentDescription = str(S.bs_chip_remove, file.name),
                            onClick = { onEvent(DriveEvent.RemoveUploadFile(file.path)) },
                            size = REMOVE_SIZE,
                        )
                    }
                }
                if (files.size > MAX_LISTED) {
                    ZillitText(
                        text = "… and ${files.size - MAX_LISTED} more",
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(start = if (directory.isEmpty()) 0.dp else ZillitTheme.spacing.xl),
                    )
                }
            }
        }
    }
}

/** Files whose extension the drive refuses — shown, not silently dropped. */
@Composable
private fun UnsupportedList(draft: UploadDraft, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.warningSoft)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(icon = ZillitIcons.Warning, tint = colors.warning, size = ZillitTheme.spacing.lg)
            ZillitText(
                text = if (draft.unsupported.size == 1) {
                    str(S.desktop_drive_unsupported_skipped_one)
                } else {
                    str(S.desktop_drive_unsupported_skipped_many, draft.unsupported.size)
                },
                style = ZillitTheme.typography.label,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.ah_clear),
                onClick = { onEvent(DriveEvent.ClearUnsupported) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        ZillitText(
            text = str(S.desktop_drive_unsupported_note),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        draft.unsupported.take(MAX_LISTED).forEach { file ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIcon(icon = ZillitIcons.File, tint = colors.textMuted, size = ZillitTheme.spacing.md)
                ZillitText(
                    text = file.relativePath.ifBlank { file.name },
                    style = ZillitTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatusPill(label = file.extension.ifBlank { "unknown" }, tone = StatusTone.Pending)
            }
        }
    }
}

/** A titled section that folds — the web's `Collapse` around the permissions panel. */
@Composable
internal fun CollapsibleAccess(
    title: String,
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(icon = ZillitIcons.Lock, tint = colors.accent, size = ZillitTheme.spacing.lg)
            ZillitText(text = title, style = ZillitTheme.typography.label)
            ZillitText(text = str(S.optional), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.pill)
                        .background(colors.accent)
                        .padding(horizontal = ZillitTheme.spacing.sm, vertical = 1.dp),
                ) {
                    ZillitText(
                        text = count.toString(),
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textOnAccent,
                    )
                }
            }
            Box(Modifier.weight(1f))
            ZillitIconButton(
                icon = if (expanded) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
                contentDescription = if (expanded) str(S.desktop_collapse) else str(S.desktop_expand),
                onClick = onToggle,
            )
        }
        if (expanded) content()
    }
}

/**
 * The create-folder drawer — `CreateFolderDrawer.jsx`: name, description,
 * location (at the root) and access control.
 */
@Composable
@Suppress("LongMethod") // One drawer; its sections read top to bottom as the web's does.
internal fun NewFolderSheet(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val draft = state.newFolder
    DriveSideSheet(
        title = str(S.dd_empty_create_cta),
        subtitle = if (state.folderId != null) str(S.desktop_drive_inside_folder, state.currentFolderName) else null,
        visible = draft != null,
        onDismiss = { onEvent(DriveEvent.CloseNewFolder) },
        icon = ZillitIcons.FolderPlus,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DriveEvent.CloseNewFolder) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.dd_empty_create_cta),
                onClick = { onEvent(DriveEvent.SubmitNewFolder) },
                enabled = draft?.canSubmit == true,
                loading = draft?.submitting == true,
                leadingIcon = ZillitIcons.FolderPlus,
            )
        },
    ) {
        if (draft == null) return@DriveSideSheet
        NewFolderForm(draft, onEvent)
        if (state.folderId == null) {
            DestinationField(
                folders = state.listing.folders,
                pickExisting = draft.pickExisting,
                selectedId = draft.destinationFolderId,
                onChange = { existing, id -> onEvent(DriveEvent.NewFolderDestination(existing, id)) },
                title = str(S.location),
                rootLabel = str(S.drive_pick_drive_root),
                rootHint = str(S.drive_pick_create_root_hint),
            )
        }
        if (state.sharePeople.isNotEmpty()) {
            SheetSection(
                title = str(S.drive_access_control),
                trailing = {
                    if (!draft.access.projectWide && draft.access.selectedCount > 0) {
                        ZillitText(
                            text = str(S.dd_n_selected, draft.access.selectedCount),
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                },
            ) {
                AccessPicker(
                    draft = draft.access,
                    onChange = { onEvent(DriveEvent.NewFolderAccess(it)) },
                    people = state.sharePeople.filterNot { it.id == state.viewer.userId },
                    forFolder = true,
                    privateHint = str(S.drive_permissions_skip_hint_folder),
                    enabled = !draft.submitting,
                )
            }
        }
    }
}

@Composable
private fun NewFolderForm(draft: NewFolderDraft, onEvent: (DriveEvent) -> Unit) {
    SheetSection {
        ZillitTextField(
            value = draft.name,
            onValueChange = { onEvent(DriveEvent.NewFolderName(it)) },
            label = str(S.folder_name),
            placeholder = str(S.drive_enter_folder_name),
            leadingIcon = ZillitIcons.Folder,
            errorText = str(S.desktop_drive_folder_name_too_long, MAX_FOLDER_NAME)
                .takeIf { draft.name.length > MAX_FOLDER_NAME },
            enabled = !draft.submitting,
        )
        ZillitTextField(
            value = draft.description,
            onValueChange = { onEvent(DriveEvent.NewFolderDescription(it)) },
            label = str(S.description),
            placeholder = str(S.desktop_drive_folder_purpose_hint),
            singleLine = false,
            enabled = !draft.submitting,
        )
    }
}

/** The edit-info drawer — `EditItemDrawer.jsx`. */
@Composable
internal fun EditItemSheet(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val draft: EditDraft? = state.edit
    DriveSideSheet(
        title = if (draft?.item?.isFolder == true) str(S.dd_edit_folder) else str(S.drive_edit_file),
        subtitle = draft?.item?.name,
        visible = draft != null,
        onDismiss = { onEvent(DriveEvent.CloseEdit) },
        icon = ZillitIcons.Edit,
        width = EDIT_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DriveEvent.CloseEdit) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.dm_setup_save),
                onClick = { onEvent(DriveEvent.SubmitEdit) },
                enabled = draft?.canSubmit == true,
                loading = draft?.submitting == true,
                leadingIcon = ZillitIcons.Save,
            )
        },
    ) {
        if (draft == null) return@DriveSideSheet
        ZillitTextField(
            value = draft.name,
            onValueChange = { onEvent(DriveEvent.EditName(it)) },
            label = str(S.name),
            placeholder = str(S.cs_enter_name),
            helperText = if (!draft.item.isFolder && draft.item.extension.isNotBlank()) {
                str(S.desktop_drive_extension_kept, draft.item.extension)
            } else {
                null
            },
            enabled = !draft.submitting,
        )
        ZillitTextField(
            value = draft.description,
            onValueChange = { onEvent(DriveEvent.EditDescription(it)) },
            label = str(S.description),
            placeholder = str(S.desktop_drive_enter_description),
            singleLine = false,
            enabled = !draft.submitting,
        )
    }
}

private val DROP_ICON = 36.dp
private val SMALL_BADGE = 26.dp
private val REMOVE_SIZE = 20.dp
private val TREE_MAX = 320.dp
private val EDIT_WIDTH = 400.dp
private const val MAX_LISTED = 40
