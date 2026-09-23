@file:Suppress("MagicNumber") // Modal geometry.

package com.zillit.desktop.feature.formsignature.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.domain.FormSignatureHost
import com.zillit.desktop.feature.formsignature.domain.HistoryPerson
import com.zillit.desktop.feature.formsignature.ui.ConfirmState
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.components.PersonChip
import com.zillit.desktop.feature.formsignature.ui.components.formDateTime

private data class ConfirmCopy(val title: String, val text: String, val isDelete: Boolean)

/** The web's words for each confirmation. */
private fun copyFor(confirm: ConfirmState?): ConfirmCopy = when (confirm) {
    is ConfirmState.DeleteForm, is ConfirmState.DeleteDocument ->
        ConfirmCopy(str(S.delete), str(S.are_you_sure_you_want_to_delete_this_document), isDelete = true)
    is ConfirmState.DeleteSignature ->
        ConfirmCopy(str(S.delete), str(S.desktop_fs_delete_this_confirm), isDelete = true)
    ConfirmState.LeaveSigned ->
        ConfirmCopy(
            str(S.sign_document_text),
            str(S.desktop_fs_leave_signed_confirm),
            isDelete = false,
        )
    ConfirmState.SendSigned ->
        ConfirmCopy(str(S.sign_document_text), str(S.txt_signature_submit), isDelete = false)
    null -> ConfirmCopy("", "", isDelete = false)
}

/** The web's confirmations — delete (with the trash glyph), leave-after-signing, send-signed. */
@Composable
internal fun ConfirmDialog(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val confirm = state.confirm
    val (title, text, isDelete) = copyFor(confirm)
    ZillitDialogShell(
        title = title,
        visible = confirm != null,
        onDismiss = { onEvent(FormSignatureEvent.ConfirmNo) },
        icon = if (isDelete) ZillitIcons.Trash else ZillitIcons.Signature,
        width = CONFIRM_WIDTH.dp,
        scrollable = false,
        actions = {
            ZillitButton(
                text = if (isDelete) str(S.cancel) else str(S.no),
                onClick = { onEvent(FormSignatureEvent.ConfirmNo) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = if (isDelete) str(S.delete) else str(S.yes),
                onClick = { onEvent(FormSignatureEvent.ConfirmYes) },
                variant = if (isDelete) ButtonVariant.Danger else ButtonVariant.Primary,
                size = ButtonSize.Small,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            if (isDelete) {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(ZillitTheme.colors.dangerSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(ZillitIcons.Trash, tint = ZillitTheme.colors.danger, size = 28.dp)
                }
            }
            ZillitText(
                text,
                style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Update History — the web's `UpdateHistoryModal` for a standard document: uploaded by, then signed by. */
@Composable
internal fun HistoryDialog(
    state: FormSignatureUiState,
    host: FormSignatureHost,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    val form = state.history?.form
    ZillitDialogShell(
        title = str(S.update_history),
        subtitle = form?.name?.takeIf { it.isNotBlank() },
        visible = form != null,
        onDismiss = { onEvent(FormSignatureEvent.CloseHistory) },
        icon = ZillitIcons.Clock,
        width = HISTORY_WIDTH.dp,
        scrollable = false,
    ) {
        if (form == null) return@ZillitDialogShell
        val uploader = HistoryPerson(
            userId = form.uploaderId,
            fullName = form.uploaderName.ifBlank { host.crew(form.uploaderId)?.fullName.orEmpty() },
            designation = form.uploaderDesignation.ifBlank { host.crew(form.uploaderId)?.designation.orEmpty() },
            at = form.createdOn,
        )
        val signers = form.signedCopies.map { copy ->
            val person = host.crew(copy.signedBy)
            HistoryPerson(
                userId = copy.signedBy,
                fullName = person?.fullName ?: if (copy.signedBy == state.currentUserId) str(S.you) else copy.signedBy,
                designation = person?.designation.orEmpty(),
                at = copy.signedOn,
            )
        }
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().height(HISTORY_HEIGHT.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                str(S.txt_uploaded_by),
                style = ZillitTheme.typography.label,
                color = ZillitTheme.colors.textSecondary,
            )
            HistoryRow(uploader)
            if (signers.isNotEmpty()) {
                ZillitDivider()
                ZillitText(
                    str(S.signed_by_label),
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.textSecondary,
                )
                signers.forEach { HistoryRow(it) }
            }
        }
    }
}

@Composable
private fun HistoryRow(person: HistoryPerson) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonChip(
            name = person.fullName.ifBlank { "—" },
            userId = person.userId,
            subtitle = person.designation.localised(),
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            formDateTime(person.at),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** "Select User" — the web's `SelectMembersModal`, single pick, self excluded. */
@Composable
internal fun ReceiverPickerDialog(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val chat = state.chat
    var search by remember { mutableStateOf("") }
    ZillitDialogShell(
        title = str(S.select_user),
        visible = chat.pickingReceiver,
        onDismiss = { onEvent(FormSignatureEvent.CloseReceiverPicker) },
        icon = ZillitIcons.User,
        width = RECEIVER_WIDTH.dp,
        scrollable = false,
        actions = {
            if (chat.receiver != null) {
                ZillitButton(
                    text = str(S.ah_clear_selection),
                    onClick = { onEvent(FormSignatureEvent.ChooseReceiver(null)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSearchField(value = search, onValueChange = { search = it }, placeholder = str(S.search))
            val needle = search.trim().lowercase()
            val rows = chat.options.filter { needle.isEmpty() || it.label.lowercase().contains(needle) }
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().height(RECEIVER_LIST_HEIGHT.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 24.dp),
            ) {
                if (chat.loadingOptions) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { ZillitSpinner() }
                }
                rows.forEach { option ->
                    val selected = option.userId == chat.receiver?.userId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ZillitTheme.shapes.medium)
                            .background(if (selected) ZillitTheme.colors.accentSoft else ZillitTheme.colors.surface)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onEvent(FormSignatureEvent.ChooseReceiver(option)) }
                            .padding(horizontal = ZillitTheme.spacing.sm, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PersonChip(
                            name = option.label,
                            userId = option.userId,
                            subtitle = option.designation,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) ZillitIcon(ZillitIcons.Tick, tint = ZillitTheme.colors.accent)
                    }
                }
            }
        }
    }
}

private const val CONFIRM_WIDTH = 420
private const val HISTORY_WIDTH = 520
private const val HISTORY_HEIGHT = 360
private const val RECEIVER_WIDTH = 480
private const val RECEIVER_LIST_HEIGHT = 380
