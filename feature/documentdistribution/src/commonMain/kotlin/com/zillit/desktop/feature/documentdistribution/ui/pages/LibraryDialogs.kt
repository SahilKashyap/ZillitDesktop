package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistPrompt
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.plural

/**
 * The confirmation for anything that cannot be undone.
 *
 * One dialog driven by [DocDistPrompt] rather than one per action: the copy
 * differs, the shape never does, and a dialog per action is how two of them
 * end up with different button orders.
 */
@Composable
fun DocDistPromptDialog(prompt: DocDistPrompt?, onEvent: (DocDistEvent) -> Unit) {
    ZillitDialogShell(
        title = prompt?.title.orEmpty(),
        visible = prompt != null,
        onDismiss = { onEvent(DocDistEvent.DismissPrompt) },
        icon = ZillitIcons.Warning,
        width = 460.dp,
    ) {
        ZillitText(text = prompt?.message.orEmpty())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DocDistEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = prompt?.confirmLabel.orEmpty(),
                onClick = { onEvent(DocDistEvent.ConfirmPrompt) },
                variant = ButtonVariant.Danger,
            )
        }
    }
}

/**
 * "Create folder" / "Edit folder". The date is mandatory on create because
 * the library groups by production day on every view; without it a folder
 * lands in the undated bucket at the bottom of the listing.
 */
@Composable
internal fun FolderEditorDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val editor = state.folderEditor
    ZillitDialogShell(
        title = when {
            editor == null -> ""
            !editor.isNew -> str(S.dd_edit_folder)
            editor.parent != null -> str(S.desktop_docdist_new_subfolder_inside, editor.parent.name)
            else -> str(S.dd_empty_create_cta)
        },
        subtitle = if (editor?.isNew == true && editor.parent == null) str(S.desktop_docdist_top_of_library) else null,
        visible = editor != null,
        onDismiss = { onEvent(DocDistEvent.CloseFolderEditor) },
        icon = ZillitIcons.Add,
        width = 480.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DocDistEvent.CloseFolderEditor) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(if (editor?.isNew == false) S.save else S.create),
                onClick = { onEvent(DocDistEvent.SaveFolder) },
                enabled = editor?.canSave == true,
                loading = editor?.saving == true,
            )
        },
    ) {
        FieldLabel(str(S.dd_publish_name_hint))
        ZillitTextField(
            value = editor?.name.orEmpty(),
            onValueChange = { onEvent(DocDistEvent.EditFolderName(it)) },
            placeholder = str(S.desktop_docdist_my_folder),
            onImeAction = { onEvent(DocDistEvent.SaveFolder) },
        )
        FieldLabel(str(S.description))
        ZillitTextField(
            value = editor?.description.orEmpty(),
            onValueChange = { onEvent(DocDistEvent.EditFolderDescription(it)) },
            placeholder = str(S.desktop_docdist_what_this_folder_contains),
        )
        if (editor?.isNew != false) {
            FieldLabel(str(S.recce_field_date))
            ZillitDateField(
                value = editor?.folderDate.orEmpty(),
                onValueChange = { onEvent(DocDistEvent.EditFolderDate(it)) },
                today = state.today,
            )
        }
    }
}

/**
 * "Move items": pick a destination folder for whatever is ticked. The list
 * leaves out the folders being moved along with everything under them —
 * dropping a folder into its own subtree detaches that branch from the root.
 */
@Composable
internal fun MoveItemsDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val move = state.moveTarget
    val folders = state.selectedFolderIds.size
    val documents = state.selectedDocumentIds.size
    ZillitDialogShell(
        title = str(S.desktop_docdist_move_items),
        subtitle = listOfNotNull(
            plural(folders, S.drive_count_folder_singular, S.drive_count_folder_plural).takeIf { folders > 0 },
            plural(documents, S.drive_count_file_singular, S.drive_count_file_plural).takeIf { documents > 0 },
        ).let { parts ->
            when (parts.size) {
                0 -> str(S.desktop_docdist_nothing_selected)
                1 -> parts.single()
                else -> str(S.desktop_docdist_x_and_y, parts[0], parts[1])
            }
        },
        visible = move != null,
        onDismiss = { onEvent(DocDistEvent.CloseMove) },
        icon = ZillitIcons.Grid,
        width = 520.dp,
        scrollable = false,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DocDistEvent.CloseMove) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.dd_action_move_here),
                onClick = { onEvent(DocDistEvent.ConfirmMove) },
                loading = move?.saving == true,
                enabled = move?.saving == false && state.selectionCount > 0 &&
                    (move.destinationId != null || !state.rootForbidden),
            )
        },
    ) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().height(MOVE_LIST_HEIGHT.dp),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            // Shown even when it cannot be chosen: a root that vanishes reads
            // as a missing destination, where a dimmed one with a reason reads as a rule.
            DestinationRow(
                label = str(if (state.rootForbidden) S.desktop_docdist_root_files_inside_folder else S.dd_root),
                indent = 0,
                selected = move?.destinationId == null && !state.rootForbidden,
                enabled = !state.rootForbidden,
                onClick = { onEvent(DocDistEvent.ChooseMoveDestination(null)) },
            )
            state.moveDestinations().forEach { destination ->
                DestinationRow(
                    label = destination.name,
                    indent = destination.depth + 1,
                    selected = move?.destinationId == destination.id,
                    onClick = { onEvent(DocDistEvent.ChooseMoveDestination(destination.id)) },
                )
            }
        }
    }
}

@Composable
private fun DestinationRow(
    label: String,
    indent: Int,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    ZillitButton(
        text = "${"    ".repeat(indent)}$label",
        onClick = onClick,
        variant = if (selected) ButtonVariant.Secondary else ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val MOVE_LIST_HEIGHT = 300
