package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState

/**
 * "Resolve duplicates" — Android's `ResolveDuplicatesBottomSheet`.
 *
 * Lists the uploads the server refused because a sibling already has the name,
 * each with a free name close to it already typed in. It opens by itself when
 * the first one fails and takes the rest as they arrive, so a folder of fifty
 * with a handful of clashes is one dialog, not a handful of toasts. A row that
 * is renamed (or cancelled) drops out of the list; when none is left it closes.
 *
 * Dismissing it leaves the failed rows in the uploads panel with a Rename
 * button, so the names can be sorted out later.
 */
@Composable
internal fun DuplicatesDialog(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val rows = state.uploads.filter { it.needsRename }
    ZillitDialogShell(
        title = if (rows.size > 1) {
            str(S.drive_resolve_duplicates_title_multiple, rows.size)
        } else {
            str(S.drive_resolve_duplicates_title_single)
        },
        subtitle = str(S.drive_resolve_duplicates_subtitle),
        icon = ZillitIcons.Edit,
        visible = state.renameUploads.open && rows.isNotEmpty(),
        onDismiss = { onEvent(DriveEvent.CloseRenameUploads) },
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.drive_resolve_duplicates_cancel_all),
                onClick = { onEvent(DriveEvent.CancelRenames) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.drive_resolve_duplicates_submit),
                onClick = { onEvent(DriveEvent.SubmitRenames) },
                leadingIcon = ZillitIcons.Check,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            rows.forEach { row -> DuplicateRow(row, state, onEvent) }
        }
    }
}

@Composable
private fun DuplicateRow(row: QueuedUpload, state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = str(S.drive_resolve_duplicate_original_format, row.fileName),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        ZillitTextField(
            value = state.renameValue(row),
            onValueChange = { onEvent(DriveEvent.RenameUploadName(row.id, it)) },
            placeholder = str(S.drive_resolve_duplicate_name_hint),
            errorText = state.renameUploads.errors[row.id],
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val DIALOG_WIDTH = 520.dp
