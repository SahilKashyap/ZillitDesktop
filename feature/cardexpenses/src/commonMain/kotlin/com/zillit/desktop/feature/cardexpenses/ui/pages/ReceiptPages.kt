package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.ui.CardConfirmAction
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardPrompt
import com.zillit.desktop.feature.cardexpenses.ui.CardReasonAction
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.MatchStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.ReconciliationPill
import com.zillit.desktop.feature.cardexpenses.ui.WorkflowStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Every receipt queue, from one composable.
 *
 * The inbox, pending coding, approval, process, history and the cardholder's
 * own transactions differ in three ways only — which rows arrive, which status
 * vocabulary reads, and what a row may do. All three come from the
 * destination, so there is one table and one detail pane rather than six of
 * each.
 */
@Composable
fun ReceiptQueuePage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.receipts.filter { it.matches(state.search) }
    val selected = rows.firstOrNull { it.id == state.selectedReceiptId }
    val bulkable = state.destination == CardDestination.ApprovalQueue && state.viewer.isApprover

    FixedPage {
        QueueHeader(state, rows.size, bulkable, onEvent)

        if (state.destination == CardDestination.MyTransactions) {
            UploadPanel(state, onEvent)
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = state.destination.label,
                icon = ZillitIcons.Receipt,
                meta = "${rows.size} row${if (rows.size == 1) "" else "s"}",
                padded = false,
                modifier = Modifier.weight(QUEUE_WEIGHT).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = rows,
                    columns = receiptColumns(state, bulkable, onEvent),
                    key = { it.id },
                    loading = state.loading,
                    onRowClick = { onEvent(CardEvent.SelectReceipt(it.id)) },
                    isSelected = { it.id == state.selectedReceiptId },
                    emptyTitle = state.emptyTitle(),
                    emptyMessage = state.emptyMessage(),
                )
            }

            ZillitSectionCard(
                title = "Receipt",
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = "Pick a receipt",
                        message = "Its match, coding and history show here.",
                        icon = ZillitIcons.Eye,
                    )
                } else {
                    ReceiptDetail(state, selected, onEvent)
                }
            }
        }
    }
}

@Suppress("LongMethod") // Search plus the bulk bar, which only exists together.
@Composable
private fun QueueHeader(
    state: CardUiState,
    count: Int,
    bulkable: Boolean,
    onEvent: (CardEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(CardEvent.Search(it)) },
            placeholder = "Search by merchant, holder or description",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitText(
            text = "$count showing",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        // The bulk bar appears only once something is ticked: a permanently
        // visible pair of Approve All / Reject All buttons above a queue is an
        // accident waiting for a mis-click.
        if (bulkable && state.selection.isNotEmpty()) {
            ZillitText(
                text = "${state.selection.size} selected",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitButton(
                text = "Approve selected",
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.BulkApprove,
                                "",
                                "Approve ${state.selection.size} receipt(s)",
                                "They move on to the accounts team together.",
                            ),
                        ),
                    )
                },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ZillitButton(
                text = "Reject selected",
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.BulkReject,
                                "",
                                "Reject ${state.selection.size} receipt(s)",
                                "Each goes back to the person who uploaded it.",
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ZillitButton(
                text = "Clear",
                onClick = { onEvent(CardEvent.ClearSelection) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

/**
 * The cardholder's upload form, with the headroom gate in front of it.
 *
 * The available figure is shown before anything is typed, because a refusal
 * after filling in four receipts is the worst moment to learn the card is
 * committed.
 */
@Suppress("LongMethod") // The gate and the form it gates, which belong together.
@Composable
private fun UploadPanel(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val headroom = state.headroom
    val card = state.myCard

    ZillitSectionCard(
        title = "Upload receipts",
        icon = ZillitIcons.Upload,
        meta = "${money(headroom.available, card?.currency)} available",
        action = {
            ZillitButton(
                text = if (expanded) "Hide" else "Add receipts",
                onClick = { expanded = !expanded },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = if (expanded) ZillitIcons.Close else ZillitIcons.Add,
            )
        },
    ) {
        if (headroom.exhausted) {
            ZillitNotice(
                text = "This card's limit is fully committed, so nothing further can be uploaded against it. " +
                    "Request a top-up first.",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                action = {
                    ZillitButton(
                        text = "Card Extension",
                        onClick = { onEvent(CardEvent.Open(CardDestination.CardExtension)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                },
            )
            return@ZillitSectionCard
        }

        if (!expanded) return@ZillitSectionCard

        state.draft.forEachIndexed { index, receipt ->
            DraftRow(
                index = index,
                receipt = receipt,
                removable = state.draft.size > 1,
                onChange = { onEvent(CardEvent.EditDraftReceipt(index, it)) },
                onRemove = { onEvent(CardEvent.RemoveDraftReceipt(index)) },
            )
        }

        val over = headroom.batchExceeds(state.draftTotal)
        if (over) {
            ZillitNotice(
                text = "This batch totals ${money(state.draftTotal, card?.currency)}, over the " +
                    "${money(headroom.available, card?.currency)} still available on the card.",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = "Add another",
                onClick = { onEvent(CardEvent.AddDraftReceipt) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            ZillitButton(
                text = "Upload ${state.draft.size} receipt(s)",
                onClick = { onEvent(CardEvent.SubmitDraftReceipts) },
                leadingIcon = ZillitIcons.Send,
                loading = state.busy,
                enabled = !over && !state.busy,
            )
        }
    }
}

@Composable
private fun DraftRow(
    index: Int,
    receipt: DraftCardReceipt,
    removable: Boolean,
    onChange: (DraftCardReceipt) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = receipt.description,
                onValueChange = { onChange(receipt.copy(description = it)) },
                label = if (index == 0) "What was bought" else null,
                placeholder = "Batteries and gaffer tape",
                modifier = Modifier.weight(1.6f),
            )
            ZillitTextField(
                value = receipt.merchant,
                onValueChange = { onChange(receipt.copy(merchant = it)) },
                label = if (index == 0) "Merchant" else null,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = receipt.amount,
                onValueChange = { onChange(receipt.copy(amount = it)) },
                label = if (index == 0) "Amount" else null,
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(SMALL_FIELD),
            )
            ZillitTextField(
                value = receipt.attachmentKey.orEmpty(),
                onValueChange = {
                    onChange(
                        receipt.copy(
                            attachmentKey = it.takeIf(String::isNotBlank),
                            attachmentName = it.substringAfterLast('/').takeIf(String::isNotBlank),
                        ),
                    )
                },
                label = if (index == 0) "Receipt file" else null,
                placeholder = "Uploaded file reference",
                leadingIcon = ZillitIcons.Paperclip,
                modifier = Modifier.weight(1.2f),
            )
            if (removable) {
                ZillitButton(
                    text = "",
                    onClick = onRemove,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
        }
    }
}

/** One receipt: what it matched, how it was coded, and what can be done. */
@Suppress("LongMethod") // One receipt, top to bottom; the order is the reading order.
@Composable
private fun ReceiptDetail(state: CardUiState, receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    var code by remember(receipt.id) { mutableStateOf(receipt.nominalCode.orEmpty()) }

    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = receipt.description.ifBlank { receipt.merchant ?: "Receipt" },
                    style = ZillitTheme.typography.titleMedium,
                )
                ZillitText(
                    text = "${receipt.holderName.ifBlank { "Unknown" }} · ${date(receipt.date)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            WorkflowStatusPill(receipt.status)
        }

        ZillitText(
            text = money(receipt.amount, receipt.currency),
            style = ZillitTheme.typography.displayLarge,
        )

        // The exception flags come first: a duplicate or a personal-spend
        // suspicion changes what should happen to the row, and finding it after
        // approving is finding it too late.
        if (receipt.duplicateScore != null && !receipt.duplicateDismissed) {
            ZillitNotice(
                text = "Possible duplicate — ${receipt.duplicateScore}% similar to another receipt.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
                action = {
                    ZillitButton(
                        text = "Not a duplicate",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.DismissDuplicate,
                                        receipt.id,
                                        "Dismiss the duplicate flag",
                                        "The receipt continues through the workflow as normal.",
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                },
            )
        }
        if (receipt.personalScore != null && !receipt.personalDismissed) {
            ZillitNotice(
                text = "Looks like personal spend — ${receipt.personalScore}% confidence.",
                tone = StatusTone.Escalated,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitDivider()

        MatchSection(state, receipt, onEvent)

        ZillitDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitTextField(
                value = code,
                onValueChange = { code = it },
                label = "Nominal code",
                placeholder = "4100",
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Save coding",
                onClick = { onEvent(CardEvent.CodeReceipt(receipt.id, code, receipt.codeDescription)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = code.isNotBlank() && !state.busy,
            )
        }

        ZillitDivider()
        ReceiptActions(state, receipt, onEvent)
    }
}

/**
 * What this receipt is tied to on the statement, and how to change it.
 *
 * Candidates are offered only where there is no match: a matched receipt with
 * a list of alternatives underneath invites someone to re-point it for no
 * reason.
 */
@Suppress("LongMethod") // Matched and unmatched are one decision shown two ways.
@Composable
private fun MatchSection(state: CardUiState, receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(text = "Statement match", style = ZillitTheme.typography.titleSmall)
            Row(modifier = Modifier.weight(1f)) {}
            MatchStatusPill(receipt.matchStatus, receipt.matchScore)
        }

        if (receipt.matchStatus == MatchStatus.Matched && receipt.transactionId != null) {
            ZillitText(
                text = listOfNotNull(
                    receipt.transactionMerchant,
                    receipt.transactionAmount?.let { money(it, receipt.currency) },
                    date(receipt.transactionDate).takeIf { it != "—" },
                    receipt.transactionCardLastFour?.let { "•••• $it" },
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            if (state.viewer.isAccountant) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitButton(
                        text = "Confirm match",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.ConfirmMatch,
                                        receipt.id,
                                        "Confirm this match",
                                        "The receipt and the statement line are treated as reconciled.",
                                    ),
                                ),
                            )
                        },
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = "Unmatch",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.UnmatchReceipt,
                                        receipt.id,
                                        "Remove this match",
                                        "The receipt returns to the unreconciled pile.",
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                }
            }
        } else if (state.matchCandidates.isEmpty()) {
            ZillitText(
                text = "No statement line suggested for this receipt yet.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            state.matchCandidates.take(MAX_CANDIDATES).forEach { candidate ->
                CandidateRow(candidate, receipt, state, onEvent)
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: CardTransaction,
    receipt: CardReceipt,
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = candidate.merchant.ifBlank { "Statement line" },
                style = ZillitTheme.typography.bodyMedium,
                maxLines = 1,
            )
            ZillitText(
                text = "${money(candidate.amount, candidate.currency)} · ${date(candidate.date)}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitButton(
            text = "Match",
            onClick = { onEvent(CardEvent.MatchReceipt(receipt.id, candidate.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
    }
}

@Suppress("LongMethod") // A rights table; flattening it is what makes it readable.
@Composable
private fun ReceiptActions(state: CardUiState, receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    val actions = buildList {
        when (state.destination) {
            CardDestination.ApprovalQueue -> {
                if (state.viewer.isApprover) {
                    add(
                        Action("Approve", ButtonVariant.Primary) {
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.ApproveReceipt,
                                    receipt.id,
                                    "Approve this receipt",
                                    "${money(receipt.amount, receipt.currency)} moves on to be posted.",
                                ),
                            )
                        },
                    )
                    add(
                        Action("Reject", ButtonVariant.Danger) {
                            CardEvent.Ask(
    CardPrompt.WithReason(
                                    CardReasonAction.RejectReceipt,
                                    receipt.id,
                                    "Reject this receipt",
                                    "Why it is being refused",
                                ),
                            )
                        },
                    )
                }
                if (state.viewer.isAccountant && state.viewer.metadata.canOverride) {
                    add(
                        Action("Override", ButtonVariant.Secondary) {
                            CardEvent.Ask(
    CardPrompt.Confirm(
                                    CardConfirmAction.OverrideReceipt,
                                    receipt.id,
                                    "Override the approval chain",
                                    "The receipt skips its remaining approvers. This is recorded against your name.",
                                ),
                            )
                        },
                    )
                }
            }

            CardDestination.ProcessQueue, CardDestination.ReceiptInbox -> {
                if (state.viewer.isAccountant) {
                    add(
                        // Straight to the editor: splitting is a task, not a
                        // decision, and a confirmation in front of it would ask
                        // a question with only one sensible answer.
                        Action("Split across codes", ButtonVariant.Secondary) {
                            CardEvent.OpenSplits(receipt.id)
                        },
                    )
                    add(
                        Action("Post", ButtonVariant.Primary) {
                            CardEvent.Ask(
    CardPrompt.Confirm(
                                    CardConfirmAction.PostReceipt,
                                    receipt.id,
                                    "Post this receipt",
                                    "${money(receipt.amount, receipt.currency)} goes to the ledger. " +
                                        "This cannot be undone here.",
                                ),
                            )
                        },
                    )
                    add(
                        Action("Flag personal", ButtonVariant.Tertiary) {
                            CardEvent.Ask(
    CardPrompt.Confirm(
                                    CardConfirmAction.FlagPersonal,
                                    receipt.id,
                                    "Flag as personal spend",
                                    "It leaves the production's expense workflow and the holder is charged back.",
                                ),
                            )
                        },
                    )
                }
            }

            CardDestination.MyTransactions -> {
                if (receipt.status == CardWorkflowStatus.PendingCode ||
                    receipt.status == CardWorkflowStatus.Imported
                ) {
                    add(
                        Action("Send for approval", ButtonVariant.Primary) {
                            CardEvent.Ask(
    CardPrompt.Confirm(
                                    CardConfirmAction.SubmitReceiptForApproval,
                                    receipt.id,
                                    "Send this for approval",
                                    "It goes to whoever approves your card spend.",
                                ),
                            )
                        },
                    )
                }
                if (!receipt.status.isPosted) {
                    add(
                        Action("Delete", ButtonVariant.Danger) {
                            CardEvent.Ask(
    CardPrompt.Confirm(
                                    CardConfirmAction.DeleteReceipt,
                                    receipt.id,
                                    "Delete this receipt",
                                    "The upload and its document are removed. This cannot be undone.",
                                ),
                            )
                        },
                    )
                }
            }

            else -> Unit
        }
    }

    if (actions.isEmpty()) {
        ZillitText(
            text = "Nothing to do on this receipt from here.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        actions.forEach { action ->
            ZillitButton(
                text = action.label,
                onClick = { onEvent(action.event()) },
                variant = action.variant,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    }
}

/**
 * One button under a receipt.
 *
 * Carries an *event* rather than a prompt, because not every action asks first
 * — splitting opens an editor, and forcing it through a yes/no dialog would
 * put a pointless click in front of the work.
 */
private data class Action(
    val label: String,
    val variant: ButtonVariant,
    val event: () -> CardEvent,
)

// -- columns -----------------------------------------------------------------

@Suppress("MagicNumber") // Column proportions.
private fun receiptColumns(
    state: CardUiState,
    bulkable: Boolean,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<CardReceipt>> = buildList {
    if (bulkable) {
        add(
            TableColumn(
                header = "",
                width = ColumnWidth.Fixed(CHECK_COLUMN),
                cell = { row ->
                    ZillitCheckbox(
                        checked = row.id in state.selection,
                        onCheckedChange = { onEvent(CardEvent.ToggleSelection(row.id)) },
                    )
                },
            ),
        )
    }
    add(textColumn("Description", ColumnWidth.Weight(1.6f)) { it.description.ifBlank { it.merchant ?: "Receipt" } })
    add(textColumn("Holder", ColumnWidth.Weight(1.1f), muted = true) { it.holderName.ifBlank { "—" } })
    add(textColumn("Amount", ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    add(textColumn("Date", ColumnWidth.Weight(1f), muted = true) { date(it.date) })
    add(
        TableColumn(
            header = "Match",
            width = ColumnWidth.Fixed(MATCH_COLUMN),
            cell = { MatchStatusPill(it.matchStatus, it.matchScore) },
        ),
    )
    add(
        TableColumn(
            header = "Status",
            width = ColumnWidth.Fixed(STATUS_COLUMN),
            cell = { row ->
                // The inbox reads in reconciliation vocabulary, everywhere else
                // in workflow vocabulary — see CardReceipt.reconciliationLabel.
                if (state.destination == CardDestination.ReceiptInbox) {
                    ReconciliationPill(row.reconciliationLabel(), row.status)
                } else {
                    WorkflowStatusPill(row.status)
                }
            },
        ),
    )
}

/** All Transactions — the statement side, rather than the receipt side. */
@Composable
fun TransactionsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.transactions.filter { it.matches(state.search) }

    FixedPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CardEvent.Search(it)) },
                placeholder = "Search by merchant or holder",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            ZillitText(
                text = "${rows.size} transaction${if (rows.size == 1) "" else "s"}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        ZillitSectionCard(
            title = "All transactions",
            icon = ZillitIcons.Ledger,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = transactionColumns(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No transactions",
                emptyMessage = "Statement lines appear here once a statement is imported.",
            )
        }
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun transactionColumns(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<CardTransaction>> = listOf(
    textColumn("Merchant", ColumnWidth.Weight(1.6f)) { it.merchant.ifBlank { it.description ?: "—" } },
    textColumn("Card", ColumnWidth.Weight(0.9f), muted = true) {
        it.cardLastFour?.let { last -> "•••• $last" } ?: "—"
    },
    textColumn("Holder", ColumnWidth.Weight(1.1f), muted = true) { it.holderName.ifBlank { "—" } },
    textColumn("Amount", ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) },
    textColumn("Date", ColumnWidth.Weight(1f), muted = true) { date(it.date) },
    textColumn("Code", ColumnWidth.Weight(0.8f), muted = true) { it.nominalCode ?: "—" },
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { WorkflowStatusPill(it.status) },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(TXN_ACTION_COLUMN),
        cell = { row ->
            if (state.viewer.isAccountant && !row.status.isPosted && !row.personal) {
                ZillitButton(
                    text = "Post",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.PostTransaction,
                                    row.id,
                                    "Post this transaction",
                                    "${money(row.amount, row.currency)} goes to the ledger.",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            } else {
                ZillitText(
                    text = "—",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        },
    ),
)

/** Statement import — upload, then watch the matcher work through it. */
@Suppress("LongMethod") // Upload form plus the imports table.
@Composable
fun ImportStatementPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    var key by remember { mutableStateOf("") }

    ScrollingPage {
        ZillitNotice(
            text = "Statements are ingested from storage: upload the file, then paste its reference here. " +
                "Matching runs automatically once the rows are read.",
            tone = StatusTone.Progress,
            icon = ZillitIcons.Info,
        )

        ZillitSectionCard(title = "Import a statement", icon = ZillitIcons.Upload) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                ZillitTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = "Statement file reference",
                    placeholder = "statements/2026-08-visa.csv",
                    leadingIcon = ZillitIcons.Paperclip,
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = "Import",
                    onClick = { onEvent(CardEvent.ImportStatement(key)) },
                    enabled = key.isNotBlank() && !state.busy,
                    loading = state.busy,
                )
            }
        }

        ZillitSectionCard(title = "Recent imports", icon = ZillitIcons.Ledger, padded = false) {
            ZillitDataTable(
                rows = state.imports,
                columns = listOf(
                    textColumn("File", ColumnWidth.Weight(1.8f)) { it.filename ?: it.id },
                    textColumn("Rows", ColumnWidth.Weight(0.7f), numeric = true) { it.rowCount.toString() },
                    textColumn("Matched", ColumnWidth.Weight(0.8f), numeric = true) { it.matchedCount.toString() },
                    textColumn("Imported", ColumnWidth.Weight(1f), muted = true) { date(it.importedAt) },
                    TableColumn(
                        header = "Status",
                        width = ColumnWidth.Fixed(STATUS_COLUMN),
                        cell = { row ->
                            ZillitStatusPill(
                                label = row.status.replaceFirstChar { it.uppercase() }.ifBlank { "Pending" },
                                tone = when (row.status) {
                                    "completed", "processed" -> StatusTone.Done
                                    "failed" -> StatusTone.Rejected
                                    else -> StatusTone.Progress
                                },
                                dot = true,
                            )
                        },
                    ),
                ),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "Nothing imported yet",
                emptyMessage = "Imported statements and their match rates appear here.",
                virtualised = false,
            )
        }
    }
}

private fun CardReceipt.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return description.lowercase().contains(needle) ||
        merchant?.lowercase()?.contains(needle) == true ||
        holderName.lowercase().contains(needle)
}

private fun CardTransaction.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return merchant.lowercase().contains(needle) ||
        holderName.lowercase().contains(needle) ||
        description?.lowercase()?.contains(needle) == true
}

private fun CardUiState.emptyTitle(): String = when {
    search.isNotBlank() -> "Nothing matches that search"
    destination == CardDestination.History -> "Nothing posted yet"
    else -> "Nothing waiting"
}

private fun CardUiState.emptyMessage(): String? = when {
    search.isNotBlank() -> "Clear the search to see the whole queue."
    destination == CardDestination.ReceiptInbox -> "Receipts land here as cardholders upload them."
    destination == CardDestination.PendingCoding -> "Receipts needing a nominal code appear here."
    destination == CardDestination.ApprovalQueue -> "Receipts routed to you for approval appear here."
    destination == CardDestination.MyTransactions -> "Upload a receipt to get started."
    else -> null
}

private const val QUEUE_WEIGHT = 1.5f
private const val DETAIL_WEIGHT = 1f
private const val MAX_CANDIDATES = 5
private const val SMALL_FIELD = 0.8f

private val SEARCH_WIDTH = 320.dp
private val STATUS_COLUMN = 150.dp
private val MATCH_COLUMN = 130.dp
private val CHECK_COLUMN = 40.dp
private val TXN_ACTION_COLUMN = 90.dp
