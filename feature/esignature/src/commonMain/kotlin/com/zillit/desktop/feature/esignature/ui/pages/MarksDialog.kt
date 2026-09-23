@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "ComplexCondition")

package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.PadMode
import com.zillit.desktop.feature.esignature.ui.components.ConfirmDialog
import com.zillit.desktop.feature.esignature.ui.components.SignaturePad

/**
 * "My Saved Signatures" — the web's `SavedSignaturePicker` in manage mode:
 * save signatures and initials before a document arrives, so signing is a
 * pick rather than a drawing.
 */
@Composable
internal fun MarksDialog(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val marks = state.marks
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.docusign_my_saved_signatures),
        subtitle = str(S.desktop_ds_save_signatures_and_initials_to_reuse_when_signing),
        visible = marks.open,
        onDismiss = { onEvent(EsignEvent.CloseMarks) },
        scrollable = false,
        icon = ZillitIcons.Edit,
        width = 600.dp,
        actions = {
            ZillitButton(
                str(S.close),
                onClick = { onEvent(EsignEvent.CloseMarks) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            if (marks.pad.mode != PadMode.Saved) {
                ZillitButton(
                    if (marks.forSignature) {
                        str(S.docusign_saved_sig_save_signature_title)
                    } else {
                        str(S.docusign_saved_sig_save_initial_title)
                    },
                    onClick = { onEvent(EsignEvent.SaveMark) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Save,
                    enabled = marks.pad.canApply,
                    loading = marks.pad.busy,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ZillitSegmented(
                options = listOf(
                    ZillitTab("sig", "Signature (${marks.signatures.size})"),
                    ZillitTab("ini", "Initials (${marks.initials.size})"),
                ),
                activeId = if (marks.forSignature) "sig" else "ini",
                onSelect = { onEvent(EsignEvent.SetMarksKind(it == "sig")) },
            )
            ZillitText(
                if (marks.forSignature) {
                    str(S.desktop_ds_the_signature_stamped_wherever_a_document_asks_you)
                } else {
                    str(S.desktop_ds_the_initials_stamped_on_every_page_that_asks)
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            SignaturePad(
                pad = marks.pad,
                saved = marks.items,
                savedImages = marks.images,
                forSignature = marks.forSignature,
                showSaved = true,
                onMode = { onEvent(EsignEvent.SetPadMode(it)) },
                onStroke = { onEvent(EsignEvent.AddPadStroke(it)) },
                onClear = { onEvent(EsignEvent.ClearPad) },
                onTyped = { onEvent(EsignEvent.EditTypedName(it)) },
                onFont = { onEvent(EsignEvent.SetPadFont(it)) },
                onPickImage = { onEvent(EsignEvent.PickPadImage) },
                onUseSaved = { },
                onDeleteSaved = { onEvent(EsignEvent.AskDeleteMark(it)) },
            )
            Spacer(Modifier.height(2.dp))
        }
    }
    ConfirmDialog(
        visible = marks.confirmDeleteId != null,
        title = str(S.desktop_ds_delete_this_saved_mark),
        body = str(S.desktop_ds_envelopes_already_signed_with_it_keep_their_copy),
        confirmLabel = str(S.delete),
        onConfirm = { onEvent(EsignEvent.ConfirmDeleteMark) },
        onDismiss = { onEvent(EsignEvent.AskDeleteMark(null)) },
    )
}
