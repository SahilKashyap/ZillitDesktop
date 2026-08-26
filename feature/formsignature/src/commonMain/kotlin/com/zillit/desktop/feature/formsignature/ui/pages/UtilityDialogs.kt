package com.zillit.desktop.feature.formsignature.ui.pages

import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.domain.StandardFormType
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState

/** Publishing a picked file into the standard library. */
@Composable
internal fun UploadFormDialog(
    state: FormSignatureUiState,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    val upload = state.uploadForm

    ZillitDialogShell(
        title = "Upload to the library",
        subtitle = upload?.fileName,
        visible = upload != null,
        onDismiss = { onEvent(FormSignatureEvent.CancelUploadForm) },
        icon = ZillitIcons.Upload,
        actions = {
            ZillitButton(
                text = "Publish",
                onClick = { onEvent(FormSignatureEvent.SubmitUploadForm) },
                size = ButtonSize.Small,
                loading = upload?.uploading == true,
            )
        },
    ) {
        if (upload == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitSelect(
                value = upload.type,
                options = StandardFormType.entries.toList(),
                onSelect = {
                    onEvent(FormSignatureEvent.EditUploadForm(upload.copy(type = it)))
                },
                label = { it.label },
            )
            ZillitTextField(
                value = upload.note,
                onValueChange = {
                    onEvent(FormSignatureEvent.EditUploadForm(upload.copy(note = it)))
                },
                label = "Note",
                placeholder = "Shown alongside the document",
            )
        }
    }
}

/** A document's history, best-effort — whatever the service records. */
@Composable
internal fun HistoryDialog(
    state: FormSignatureUiState,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    val history = state.history

    ZillitDialogShell(
        title = "History",
        visible = history != null,
        onDismiss = { onEvent(FormSignatureEvent.CloseHistory) },
        icon = ZillitIcons.Clock,
    ) {
        when {
            history == null -> Unit
            history.loading -> ZillitSpinner()
            history.entries.isEmpty() -> ZillitText(
                text = "Nothing recorded for this document yet.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )

            else -> Column(
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                history.entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            text = listOf(entry.actorName, entry.action)
                                .filter { it.isNotBlank() }
                                .joinToString(" — ")
                                .ifBlank { "(unnamed change)" },
                        )
                        ZillitText(
                            text = entry.happenedOn,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Change who must sign a document that has already gone out.
 *
 * The service takes the whole signer list rather than a delta, so this shows
 * everyone and saves everyone — unticking a name is what removes them. People
 * who have already signed are shown but fixed: taking them off would discard
 * ink that exists.
 */
@Composable
internal fun SignerEditorDialog(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val editor = state.signerEditor ?: return

    ZillitDialogShell(
        title = "Who must sign",
        subtitle = editor.title.takeIf { it.isNotBlank() },
        icon = ZillitIcons.UserPlus,
        visible = true,
        onDismiss = { onEvent(FormSignatureEvent.CloseSignerEditor) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(FormSignatureEvent.CloseSignerEditor) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save",
                onClick = { onEvent(FormSignatureEvent.SaveSigners) },
                enabled = editor.canSave,
                loading = editor.saving,
            )
        },
    ) {
        if (editor.loading) {
            ZillitText(
                text = "Loading…",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            return@ZillitDialogShell
        }
        editor.options.forEach { option ->
            val signed = option.userId in editor.alreadySigned
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitCheckbox(
                    checked = option.userId in editor.chosen,
                    onCheckedChange = { onEvent(FormSignatureEvent.ToggleSigner(option.userId)) },
                    label = option.fullName.ifBlank { option.email.ifBlank { option.userId } },
                    enabled = !signed,
                )
                if (signed) {
                    ZillitStatusPill(label = "Signed", tone = StatusTone.Done)
                }
            }
        }
    }
}
