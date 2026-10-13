package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CrewRules
import com.zillit.desktop.feature.cardexpenses.domain.UploadHeadroom
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cardexpenses.ui.ReceiptDetailSource
import com.zillit.desktop.feature.cardexpenses.ui.ReceiptDetailState
import com.zillit.desktop.feature.cardexpenses.ui.ReceiptEditDraft
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Every dialog the crew pages raise, drawn at the screen's root so each sits
 * over the whole tool rather than at the foot of a scrolling page.
 */
@Composable
fun CrewDialogs(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    ReceiptEditDialog(state, onEvent)
    DeleteReceiptDialog(state, onEvent)
    CrewRejectDialog(state, onEvent)
    CodeReceiptDialog(state, onEvent)
    TopUpRequestDialog(state, onEvent)
    TopUpTrailDialog(state, onEvent)
}

// -- the shared detail's footer -------------------------------------------------

/**
 * What the shared receipt detail adds to its footer for a cardholder: Edit
 * Receipt on My Transactions, Reject and Approve in the Approval Queue
 * (`ReceiptDetailModal`'s `onEdit` / `actions` props). Query is the view's.
 */
@Composable
internal fun CrewReceiptActions(state: CardUiState, open: ReceiptDetailState, onEvent: (CardEvent) -> Unit) {
    val receipt = open.detail.receipt
    when (open.source) {
        ReceiptDetailSource.CrewApproval -> {
            ZillitButton(
                text = str(S.reject),
                onClick = { onEvent(CrewEvent.AskReject(receipt.id, receipt = true)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(if (state.crew.actionId == receipt.id) S.ah_run_detail_btn_approving else S.approve),
                onClick = { onEvent(CrewEvent.ApproveReceipt(receipt.id)) },
                size = ButtonSize.Small,
                enabled = state.crew.actionId != receipt.id,
            )
        }

        ReceiptDetailSource.MyTransactions -> if (!open.loading && CrewRules.canEdit(receipt)) {
            ZillitButton(
                text = str(S.ah_edit_receipt),
                onClick = { onEvent(CrewEvent.OpenEdit(receipt.id)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
        }

        else -> Unit
    }
}

// -- the edit --------------------------------------------------------------------

/**
 * Edit Receipt / Edit & Resubmit Receipt (`UserReceiptsPage.jsx:1099-1501`) —
 * also the Upload Receipt dialog behind a pending-receipt card's button.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One form; the order is the order it is filled in.
@Composable
private fun ReceiptEditDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.crew.edit
    val active = CrewRules.activeCard(state.cards)
    val headroom = UploadHeadroom.of(active)
    val exceeds = draft != null &&
        headroom.editExceeds(draft.receipt.amount, draft.amount.trim().toDoubleOrNull() ?: 0.0)
    ZillitDialogShell(
        title = if (draft?.rejected == true) str(S.ah_edit_resubmit_receipt) else str(S.ah_edit_receipt),
        visible = draft != null,
        onDismiss = { onEvent(CrewEvent.CloseEdit) },
        icon = ZillitIcons.Edit,
        width = EDIT_WIDTH,
        actions = if (draft == null) {
            null
        } else {
            {
                val open = draft
                if (exceeds) {
                    ZillitText(
                        text = str(S.desktop_ce_crew_edit_over, money(headroom.available, active?.currency)),
                        style = ZillitTheme.typography.label,
                        color = ZillitTheme.colors.danger,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ZillitButton(
                    text = when {
                        open.saving -> str(S.ah_saving)
                        open.rejected -> str(S.ah_save_resubmit)
                        else -> str(S.ah_save_changes)
                    },
                    onClick = { onEvent(CrewEvent.SaveEdit) },
                    enabled = !open.saving && open.amount.isNotBlank() && !exceeds,
                    loading = open.saving,
                )
            }
        },
    ) {
        val open = draft ?: return@ZillitDialogShell
        val required = CrewRules.accountantSent(open.receipt)
        val change: (ReceiptEditDraft) -> Unit = { onEvent(CrewEvent.EditReceipt(it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            EditFilePane(state, open, required, onEvent, Modifier.width(EDIT_PANE))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                if (open.receipt.rejectionReason != null || open.receipt.rejectedBy != null) {
                    RejectionBanner(state, open.receipt)
                }
                ZillitTextField(
                    value = open.merchant,
                    onValueChange = { change(open.copy(merchant = it)) },
                    label = str(S.desktop_ce_crew_merchant_description) + if (required) " *" else "",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    ZillitTextField(
                        value = open.amount,
                        onValueChange = { change(open.copy(amount = it.crewAmount())) },
                        label = str(S.amount) + " *",
                        placeholder = "0.00",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitDateField(
                        value = open.date,
                        onValueChange = { change(open.copy(date = it)) },
                        label = str(S.date) + if (required) " *" else "",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    ZillitTextField(
                        value = open.receipt.currency ?: active?.currency ?: "—",
                        onValueChange = {},
                        label = str(S.ah_lbl_currency),
                        readOnly = true,
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                    CategoryPicker(open.category, { change(open.copy(category = it)) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
                    ZillitCheckbox(
                        checked = open.urgent,
                        onCheckedChange = { change(open.copy(urgent = it)) },
                        label = str(S.desktop_ce_crew_mark_urgent),
                    )
                    ZillitCheckbox(
                        checked = open.topUp,
                        onCheckedChange = { change(open.copy(topUp = it)) },
                        label = str(S.desktop_card_request_top_up),
                    )
                }
                ZillitDivider()
                CrewField(str(S.ah_budget_coding), "")
                CrewCodeField(
                    value = open.costCode,
                    onValueChange = { change(open.copy(costCode = it)) },
                    nominals = state.crew.nominals,
                    label = str(S.desktop_card_cost_code),
                    placeholder = str(S.desktop_ce_crew_enter_code),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    if (state.crew.isTelevision) {
                        ZillitTextField(
                            value = open.episode,
                            onValueChange = { change(open.copy(episode = it)) },
                            label = str(S.episode),
                            placeholder = str(S.ah_episode_hint),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ZillitTextField(
                        value = open.codeDescription,
                        onValueChange = { change(open.copy(codeDescription = it)) },
                        label = str(S.description),
                        placeholder = str(S.ah_coding_description_hint),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The file side of the edit: new file, kept file, or the Upload Receipt well (`:1114-1213`). */
@Suppress("LongMethod") // Three states of one pane.
@Composable
private fun EditFilePane(
    state: CardUiState,
    draft: ReceiptEditDraft,
    required: Boolean,
    onEvent: (CardEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val name = when {
        draft.newFile != null -> draft.newFile.fileName
        draft.keepExisting -> draft.receipt.attachmentName ?: str(S.desktop_ce_crew_receipt_file)
        else -> null
    }
    val canPick = state.canAttachFiles && !state.uploading
    Column(
        modifier = modifier
            .heightIn(min = MEDIA_HEIGHT)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterVertically),
    ) {
        ZillitText(
            text = str(S.desktop_ce_crew_receipt_attachment).uppercase() + if (required) " *" else "",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        if (name != null) {
            ZillitIcon(
                if (draft.newFile != null) ZillitIcons.Paperclip else ZillitIcons.File,
                tint = if (draft.newFile != null) colors.success else colors.textMuted,
            )
            ZillitText(
                text = name,
                style = ZillitTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitButton(
                    text = str(S.remove),
                    onClick = { onEvent(CrewEvent.RemoveEditFile) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = str(S.replace),
                    onClick = { onEvent(CrewEvent.PickEditFile) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = canPick,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.large)
                    .border(CREW_HAIRLINE, colors.borderStrong, ZillitTheme.shapes.large)
                    .then(if (canPick) Modifier.clickable { onEvent(CrewEvent.PickEditFile) } else Modifier)
                    .padding(ZillitTheme.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitIcon(ZillitIcons.Upload, tint = colors.textMuted)
                ZillitText(text = str(S.ah_upload_receipt_btn), style = ZillitTheme.typography.label)
                ZillitText(
                    text = str(S.desktop_ce_crew_file_types),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/** "Rejection:" — the reason, who, and when (`UserReceiptsPage.jsx:1218-1271`). */
@Composable
private fun RejectionBanner(state: CardUiState, receipt: CardReceipt) {
    val colors = ZillitTheme.colors
    val who = state.people.firstOrNull { it.id == receipt.rejectedBy }
    val name = who?.name?.takeIf { it.isNotBlank() } ?: str(S.desktop_unknown)
    val by = listOfNotNull(
        str(S.desktop_ce_crew_by, name),
        who?.designation?.takeIf { it.isNotBlank() }?.let { "($it)" },
        receipt.rejectedAt?.let { "· ${EpochDate.dateTime(it)}" },
    ).joinToString(" ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.dangerSoft)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(text = str(S.ah_rejection) + ":", style = ZillitTheme.typography.label, color = colors.danger)
        ZillitText(
            text = "\"${receipt.rejectionReason ?: str(S.desktop_inv_no_reason_provided).trimEnd('.')}\"",
            style = ZillitTheme.typography.bodySmall,
            color = colors.danger,
        )
        ZillitText(text = by, style = ZillitTheme.typography.labelSmall, color = colors.danger)
    }
}

@Composable
private fun DeleteReceiptDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val id = state.crew.deleteId
    val receipt = state.receipts.firstOrNull { it.id == id }
    ZillitDialogShell(
        title = str(S.ah_delete_receipt_title),
        visible = id != null,
        onDismiss = { onEvent(CrewEvent.AskDelete(null)) },
        icon = ZillitIcons.Trash,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CrewEvent.AskDelete(null)) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(CrewEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = str(
                S.desktop_ce_crew_delete_message,
                receipt?.description?.takeIf { it.isNotBlank() } ?: str(S.ah_this_transaction),
            ),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

private val EDIT_WIDTH = 900.dp
private val EDIT_PANE = 260.dp
private val MEDIA_HEIGHT = 320.dp
