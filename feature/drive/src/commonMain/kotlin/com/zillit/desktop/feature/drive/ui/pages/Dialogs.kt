package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.descendantsOf
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DrivePrompt
import com.zillit.desktop.feature.drive.ui.DriveUiState

/**
 * "Move to…" — `MoveToDialog.jsx`: the scope's folder tree with the Drive
 * root on top, the items being moved (and everything beneath a moved
 * folder) left out.
 */
@Composable
internal fun MoveToDialog(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val picker = state.moveTo
    val names = picker?.items?.joinToString(", ") { it.name }.orEmpty()
    val excluded = picker?.items
        ?.filter { it.isFolder }
        ?.flatMap { descendantsOf(it.id, state.listing.folders) }
        ?.toSet()
        .orEmpty()
    ZillitDialogShell(
        title = "Move to…",
        subtitle = "Moving: " + if (names.length > NAMES_MAX) names.take(NAMES_MAX) + "…" else names,
        visible = picker != null,
        onDismiss = { onEvent(DriveEvent.CloseMoveTo) },
        icon = ZillitIcons.ArrowRight,
        width = MOVE_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DriveEvent.CloseMoveTo) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Move here",
                onClick = { onEvent(DriveEvent.ConfirmMove) },
                leadingIcon = ZillitIcons.Check,
            )
        },
    ) {
        if (picker == null) return@ZillitDialogShell
        FolderTree(
            folders = state.listing.folders,
            selectedId = picker.targetFolderId,
            onSelect = { onEvent(DriveEvent.PickMoveTarget(it)) },
            excluded = excluded,
            rootLabel = if (state.section == DriveSection.SharedWithMe) "Shared with me (root)" else "Drive (root)",
        )
    }
}

/**
 * "Set File Permissions" before a dropped upload starts — the modal the web
 * shows after a page-level drop, with "Skip & upload" beside it.
 */
@Composable
internal fun DropPermissionsDialog(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val drop = state.dropUpload
    val count = drop?.files?.size ?: 0
    ZillitDialogShell(
        title = "Set file permissions",
        subtitle = "$count file${if (count == 1) "" else "s"} ready to upload to ${state.currentFolderName}",
        visible = drop != null,
        onDismiss = { onEvent(DriveEvent.CancelDrop) },
        icon = ZillitIcons.Lock,
        width = DROP_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DriveEvent.CancelDrop) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Skip & upload",
                onClick = { onEvent(DriveEvent.ConfirmDrop(withAccess = false)) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = "Set permissions & upload",
                onClick = { onEvent(DriveEvent.ConfirmDrop(withAccess = true)) },
                leadingIcon = ZillitIcons.Upload,
            )
        },
    ) {
        if (drop == null) return@ZillitDialogShell
        ZillitText(
            text = "Set who can access ${if (count > 1) "these files" else "this file"}, or skip to use the defaults.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        AccessPicker(
            draft = drop.access,
            onChange = { onEvent(DriveEvent.DropAccess(it)) },
            people = state.sharePeople.filterNot { it.id == state.viewer.userId },
            forFolder = false,
            showInherit = false,
        )
    }
}

/**
 * The confirmation for anything that cannot be undone.
 *
 * One dialog driven by [DrivePrompt] rather than one per action: the copy
 * differs, the shape never does, and a dialog per action is how two of them
 * end up with different button orders and someone empties the trash by
 * muscle memory.
 */
@Composable
internal fun DrivePromptDialog(prompt: DrivePrompt?, onEvent: (DriveEvent) -> Unit) {
    ZillitDialogShell(
        title = prompt?.title.orEmpty(),
        visible = prompt != null,
        onDismiss = { onEvent(DriveEvent.DismissPrompt) },
        icon = if (prompt?.danger == false) ZillitIcons.Info else ZillitIcons.Warning,
        width = PROMPT_WIDTH,
    ) {
        ZillitText(text = prompt?.message.orEmpty())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DriveEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = prompt?.confirmLabel.orEmpty(),
                onClick = { onEvent(DriveEvent.ConfirmPrompt) },
                variant = if (prompt?.danger == false) ButtonVariant.Primary else ButtonVariant.Danger,
            )
        }
    }
}

private val MOVE_WIDTH = 480.dp
private val DROP_WIDTH = 520.dp
private val PROMPT_WIDTH = 440.dp
private const val NAMES_MAX = 80
