package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.ui.CardConfirmAction
import com.zillit.desktop.feature.cardexpenses.ui.CardDetail
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardFace
import com.zillit.desktop.feature.cardexpenses.ui.CardPrompt
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.WorkflowStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.components.CardHistoryTrail
import com.zillit.desktop.feature.cardexpenses.ui.components.DetailLine
import com.zillit.desktop.feature.cardexpenses.ui.components.DetailPanePadding
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * One card, opened.
 *
 * Answers the two questions somebody opens a card to ask — what has been spent
 * on it, and where the money on it came from — side by side with the trail of
 * what has been done to the card itself. Before this the register's three
 * drilldown endpoints were reachable from nowhere in this application, so
 * clicking a row highlighted it and nothing else.
 */
@Suppress("LongMethod") // One card top to bottom; the order is the reading order.
@Composable
fun CardDetailPane(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val detail = state.cardDetail?.takeIf { it.cardId == card.id }
    val editable = CardRules.canEditRequest(card, state.viewer.userId, state.viewer.isAccountant)

    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = DetailPanePadding,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        CardFace(
            card = card,
            modifier = Modifier.fillMaxWidth(),
            issuer = state.issuerName(card),
            holder = state.holderName(card),
        )

        if (card.status == CardStatus.Rejected && !card.rejectionReason.isNullOrBlank()) {
            ZillitNotice(
                text = str(S.desktop_rejected_value, card.rejectionReason),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }

        Column {
            DetailLine(str(S.ah_holder), state.holderName(card).takeIf { it != "—" } ?: str(S.unassigned))
            DetailLine(str(S.desktop_card_authorised_limit), money(card.limit, card.currency), emphasised = true)
            card.proposedLimit?.takeIf { it > 0 && it != card.limit }?.let {
                DetailLine(str(S.desktop_card_proposed), money(it, card.currency))
            }
            DetailLine(str(S.ah_lbl_remaining), money(card.balance, card.currency))
            DetailLine(str(S.desktop_card_committed_in_receipts), money(card.receiptsCommit, card.currency))
            DetailLine(str(S.desktop_card_raised), date(card.createdAt))
        }

        if (!card.justification.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                FieldGroupLabel(str(S.desktop_card_justification))
                ZillitText(text = card.justification, style = ZillitTheme.typography.bodySmall)
            }
        }

        ZillitDivider()
        BsCodeField(state, card, onEvent)

        if (state.viewer.isAccountant) {
            ZillitDivider()
            CardLifecycleActions(state, card, editable, onEvent)
        }

        ZillitDivider()

        if (detail == null || detail.loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.lg),
                horizontalArrangement = Arrangement.Center,
            ) {
                ZillitSpinner()
            }
            return@ZillitScrollColumn
        }

        CardSpendSection(detail, card.currency, onEvent)

        ZillitDivider()
        CardFundingSection(detail, card.currency)

        ZillitDivider()
        FieldGroupLabel(str(S.history))
        CardHistoryTrail(
            entries = detail.history,
            emptyMessage = str(S.desktop_card_no_history_yet),
        )
    }
}

/** What has been charged to this card, newest first. */
@Composable
private fun CardSpendSection(detail: CardDetail, currency: String?, onEvent: (CardEvent) -> Unit) {
    FieldGroupLabel(str(S.desktop_card_spend_receipts_count, detail.receipts.size))
    if (detail.receipts.isEmpty()) {
        EmptyLine(str(S.desktop_card_nothing_charged_yet))
        return
    }
    DetailLine(str(S.ah_total_label), money(detail.spend, currency), emphasised = true)
    detail.receipts.take(MAX_ROWS).forEach { receipt -> CardReceiptRow(receipt, onEvent) }
    if (detail.receipts.size > MAX_ROWS) {
        EmptyLine(str(S.desktop_card_more_in_ledger, detail.receipts.size - MAX_ROWS))
    }
}

/** Where the money on this card came from. */
@Composable
private fun CardFundingSection(detail: CardDetail, currency: String?) {
    FieldGroupLabel(str(S.desktop_card_funding_topups_count, detail.topUps.size))
    if (detail.topUps.isEmpty()) {
        EmptyLine(str(S.desktop_card_no_topups_raised))
        return
    }
    DetailLine(str(S.desktop_card_funded_to_date), money(detail.funded, currency), emphasised = true)
    detail.topUps.take(MAX_ROWS).forEach { topUp -> CardTopUpRow(topUp, currency) }
}

@Composable
private fun EmptyLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
}

/**
 * The balance-sheet control code, editable in place.
 *
 * A narrow PATCH on purpose: the full details form stamps a resubmit that
 * wipes the card's approvals, which is the right thing on a request under
 * review and destructive on a card that is live. Correcting a code is a
 * correction, not a resubmission, so it gets its own field and its own call.
 */
@Composable
private fun BsCodeField(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    if (!state.viewer.isAccountant) {
        DetailLine(str(S.desktop_card_control_code), card.bsControlCode?.takeIf { it.isNotBlank() } ?: "—")
        return
    }
    val draft = state.cardDetail?.bsControlCode.orEmpty()
    val dirty = draft.trim() != card.bsControlCode.orEmpty().trim()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        ZillitTextField(
            value = draft,
            onValueChange = { onEvent(CardEvent.EditBsCode(it)) },
            label = str(S.desktop_card_bs_control_code),
            placeholder = "2100",
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(S.desktop_card_save_code),
            onClick = { onEvent(CardEvent.SaveBsCode(card.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = dirty && draft.isNotBlank() && !state.busy,
        )
    }
}

/**
 * The actions this card's status allows, beyond the one in the table row.
 *
 * The row carries the single obvious next step — approve, activate, suspend —
 * and these are the rest: rewriting a request, overriding its chain, deleting
 * one raised by mistake. Kept out of the table because a row of five buttons
 * per card makes the register unreadable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardLifecycleActions(
    state: CardUiState,
    card: ExpenseCard,
    editable: Boolean,
    onEvent: (CardEvent) -> Unit,
) {
    val canOverride = state.viewer.metadata.canOverride && card.status in OVERRIDABLE

    // Wraps: a requested card offers approve, reject, edit, override and
    // delete, which is more than fits across a detail pane at any width the
    // Account Hub leaves for one.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        // The one step this card's status allows, drawn here rather than in
        // the table row:
        // inside the Account Hub the register is narrow enough that its action
        // column scrolls off the end, and a card that cannot be approved from
        // the only place it is visible is a card that cannot be approved.
        CardPrimaryAction(state, card, onEvent)
        if (editable) {
            ZillitButton(
                text = str(S.txt_edit_details),
                onClick = { onEvent(CardEvent.OpenCardEdit(card.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
                enabled = !state.busy,
            )
        }
        if (canOverride) {
            ZillitButton(
                text = str(S.dm_nom_table_override),
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.OverrideCard,
                                card.id,
                                str(S.desktop_card_override_chain),
                                str(S.desktop_card_override_card_note),
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Shield,
                enabled = !state.busy,
            )
        }
        if (editable) DeleteRequestButton(state, card, onEvent)
    }
}

/** Removing a request that should never have been raised. */
@Composable
private fun DeleteRequestButton(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    ZillitButton(
        text = str(S.av_delete_request),
        onClick = {
            onEvent(
                CardEvent.Ask(
                    CardPrompt.Confirm(
                        CardConfirmAction.DeleteCard,
                        card.id,
                        str(S.desktop_card_delete_this_request),
                        str(S.desktop_card_delete_request_note),
                    ),
                ),
            )
        },
        variant = ButtonVariant.Danger,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Trash,
        enabled = !state.busy,
    )
}

@Composable
private fun CardReceiptRow(receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = receipt.description.ifBlank { receipt.merchant ?: str(S.desktop_receipt) },
                style = ZillitTheme.typography.bodySmall,
                maxLines = 1,
            )
            ZillitText(
                text = date(receipt.date),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitText(text = money(receipt.amount, receipt.currency), style = ZillitTheme.typography.numeric)
        WorkflowStatusPill(receipt.status)
        receipt.attachmentKey?.takeIf { it.isNotBlank() }?.let { key ->
            ZillitButton(
                text = "",
                onClick = { onEvent(CardEvent.ViewReceipt(key)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Eye,
            )
        }
    }
}

@Composable
private fun CardTopUpRow(topUp: CardTopUp, currency: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = money(topUp.amount, topUp.currency ?: currency),
                style = ZillitTheme.typography.bodyMedium,
            )
            ZillitText(
                text = listOfNotNull(date(topUp.createdAt).takeIf { it != "—" }, topUp.method)
                    .joinToString(" · ").ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitStatusPill(
            label = topUp.status.replaceFirstChar { it.uppercase() }.ifBlank { str(S.pending) },
            tone = when (topUp.status) {
                "completed" -> StatusTone.Done
                "skipped" -> StatusTone.Neutral
                "partial" -> StatusTone.Progress
                else -> StatusTone.Pending
            },
            dot = true,
        )
    }
}

/** The pane's stand-in before a card is chosen. */
@Composable
fun CardDetailPlaceholder() {
    ZillitEmptyState(
        title = str(S.desktop_card_pick_a_card),
        message = str(S.desktop_card_pick_a_card_hint),
        icon = ZillitIcons.CreditCard,
    )
}

private val OVERRIDABLE = setOf(CardStatus.Requested, CardStatus.Pending)
private const val MAX_ROWS = 8
