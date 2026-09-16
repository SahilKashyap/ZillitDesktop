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
        title = "Upload files",
        subtitle = if (state.folderId != null) "Destination: ${state.currentFolderName}" else null,
        visible = draft != null,
        onDismiss = { onEvent(DriveEvent.CloseUpload) },
        icon = ZillitIcons.Upload,
        width = SHEET_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DriveEvent.CloseUpload) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Upload",
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
                title = "Destination",
                rootLabel = "Drive root",
                rootHint = "Files will land at the top level of your Drive.",
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
            text = if (draft.showDetails) "Hide details" else "Add details (optional)",
            onClick = { onEvent(DriveEvent.ToggleUploadDetails) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        if (draft.showDetails) {
            ZillitTextField(
                value = draft.description,
                onValueChange = { onEvent(DriveEvent.UploadDescription(it)) },
                label = "Description",
                placeholder = "What these files are for…",
                singleLine = false,
            )
        }

        if (state.sharePeople.isNotEmpty()) {
            CollapsibleAccess(
                title = "File permissions",
                expanded = draft.accessExpanded,
                count = draft.access.selectedCount,
                onToggle = { onEvent(DriveEvent.ToggleUploadAccess) },
            ) {
                AccessPicker(
                    draft = draft.access,
                    onChange = { onEvent(DriveEvent.UploadAccess(it)) },
                    people = state.sharePeople.filterNot { it.id == state.viewer.userId },
                    forFolder = false,
                    privateHint = "Skip assigning permissions to keep these files private and accessible only to you.",
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
        ZillitText(text = "Drag files or folders here", style = ZillitTheme.typography.titleSmall)
        ZillitText(
            text = "Folder structure will be preserved on upload.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
        ) {
            ZillitButton(
                text = "Choose files",
                onClick = onPickFiles,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Choose folder",
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
        title = "${draft.files.size} file${if (draft.files.size == 1) "" else "s"} selected",
        trailing = {
            ZillitButton(
                text = "Clear all",
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
                            contentDescription = "Remove ${file.name}",
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
                text = "${draft.unsupported.size} unsupported file" +
                    (if (draft.unsupported.size == 1) "" else "s") + " skipped",
                style = ZillitTheme.typography.label,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Clear",
                onClick = { onEvent(DriveEvent.ClearUnsupported) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        ZillitText(
            text = "These files won't be uploaded — their format isn't supported.",
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
            ZillitText(text = "(optional)", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
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
                contentDescription = if (expanded) "Collapse" else "Expand",
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
        title = "Create folder",
        subtitle = if (state.folderId != null) "Inside ${state.currentFolderName}" else null,
        visible = draft != null,
        onDismiss = { onEvent(DriveEvent.CloseNewFolder) },
        icon = ZillitIcons.FolderPlus,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DriveEvent.CloseNewFolder) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Create folder",
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
                title = "Location",
                rootLabel = "Drive root",
                rootHint = "This folder will be created at the top level of your Drive.",
            )
        }
        if (state.sharePeople.isNotEmpty()) {
            SheetSection(
                title = "Access control",
                trailing = {
                    if (!draft.access.projectWide && draft.access.selectedCount > 0) {
                        ZillitText(
                            text = "${draft.access.selectedCount} selected",
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
                    privateHint = "Skip assigning permissions to keep this folder private and accessible only to you.",
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
            label = "Folder name",
            placeholder = "Enter a folder name",
            leadingIcon = ZillitIcons.Folder,
            errorText = "Folder name must be under $MAX_FOLDER_NAME characters"
                .takeIf { draft.name.length > MAX_FOLDER_NAME },
            enabled = !draft.submitting,
        )
        ZillitTextField(
            value = draft.description,
            onValueChange = { onEvent(DriveEvent.NewFolderDescription(it)) },
            label = "Description",
            placeholder = "Describe what this folder is for…",
            singleLine = false,
            enabled = !draft.submitting,
        )
    }
}

/** The edit-info drawer — `EditItemDrawer.jsx`. */
@Composable
internal fun EditItemSheet(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val draft: EditDraft? = state.edit
    val kind = if (draft?.item?.isFolder == true) "folder" else "file"
    DriveSideSheet(
        title = "Edit $kind",
        subtitle = draft?.item?.name,
        visible = draft != null,
        onDismiss = { onEvent(DriveEvent.CloseEdit) },
        icon = ZillitIcons.Edit,
        width = EDIT_WIDTH,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(DriveEvent.CloseEdit) }, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = "Save changes",
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
            label = "Name",
            placeholder = "Enter name",
            helperText = if (!draft.item.isFolder && draft.item.extension.isNotBlank()) {
                "The .${draft.item.extension} extension is kept."
            } else {
                null
            },
            enabled = !draft.submitting,
        )
        ZillitTextField(
            value = draft.description,
            onValueChange = { onEvent(DriveEvent.EditDescription(it)) },
            label = "Description",
            placeholder = "Enter a description",
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
