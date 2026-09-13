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
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistPrompt
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState

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
                text = "Cancel",
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
            !editor.isNew -> "Edit folder"
            editor.parent != null -> "New subfolder inside \"${editor.parent.name}\""
            else -> "Create folder"
        },
        subtitle = if (editor?.isNew == true && editor.parent == null) "At the top of the library" else null,
        visible = editor != null,
        onDismiss = { onEvent(DocDistEvent.CloseFolderEditor) },
        icon = ZillitIcons.Add,
        width = 480.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseFolderEditor) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (editor?.isNew == false) "Save" else "Create",
                onClick = { onEvent(DocDistEvent.SaveFolder) },
                enabled = editor?.canSave == true,
                loading = editor?.saving == true,
            )
        },
    ) {
        FieldLabel("Name *")
        ZillitTextField(
            value = editor?.name.orEmpty(),
            onValueChange = { onEvent(DocDistEvent.EditFolderName(it)) },
            placeholder = "My folder",
            onImeAction = { onEvent(DocDistEvent.SaveFolder) },
        )
        FieldLabel("Description")
        ZillitTextField(
            value = editor?.description.orEmpty(),
            onValueChange = { onEvent(DocDistEvent.EditFolderDescription(it)) },
            placeholder = "What this folder contains",
        )
        if (editor?.isNew != false) {
            FieldLabel("Date *")
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
        title = "Move items",
        subtitle = listOfNotNull(
            "$folders folder".takeIf { folders > 0 }?.plus(if (folders == 1) "" else "s"),
            "$documents file".takeIf { documents > 0 }?.plus(if (documents == 1) "" else "s"),
        ).joinToString(" and ").ifBlank { "Nothing selected" },
        visible = move != null,
        onDismiss = { onEvent(DocDistEvent.CloseMove) },
        icon = ZillitIcons.Grid,
        width = 520.dp,
        scrollable = false,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseMove) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Move here",
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
                label = if (state.rootForbidden) "Root · files must be inside a folder" else "Root",
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
