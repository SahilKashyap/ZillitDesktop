package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCategory
import com.zillit.desktop.feature.cardexpenses.ui.ALL_STATUSES
import com.zillit.desktop.feature.cardexpenses.ui.CardConfirmAction
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardPrompt
import com.zillit.desktop.feature.cardexpenses.ui.CardReasonAction
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CodingDraft
import com.zillit.desktop.feature.cardexpenses.ui.MatchStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.ReconciliationPill
import com.zillit.desktop.feature.cardexpenses.ui.WorkflowStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.components.AttachmentSlot
import com.zillit.desktop.feature.cardexpenses.ui.components.CardHistoryTrail
import com.zillit.desktop.feature.cardexpenses.ui.components.DetailPanePadding
import com.zillit.desktop.feature.cardexpenses.ui.components.PersonCell
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Every queue of receipts, from one table and one detail pane.
 *
 * The inbox, pending coding, approval, process, history and the cardholder's
 * own transactions differ in three ways only — which rows arrive, which status
 * vocabulary reads, and what a row may do. All three come from the
 * destination, so there is one table and one detail pane rather than six of
 * each.
 */
@Composable
fun ReceiptQueuePage(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
    /** Rendered above the toolbar — History's totals, and nothing else so far. */
    banner: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val rows = state.receipts
        .filter { state.statusFilter == ALL_STATUSES || it.status.wire == state.statusFilter }
        .filter { it.matches(state.search) }
    val selected = rows.firstOrNull { it.id == state.selectedReceiptId }
    val bulkable = state.destination == CardDestination.ApprovalQueue && state.viewer.isApprover

    FixedPage {
        banner?.invoke(this)
        QueueHeader(state, rows.size, bulkable, onEvent)

        if (state.destination == CardDestination.MyTransactions) {
            UploadPanel(state, onEvent)
        }

        if (state.destination == CardDestination.ReceiptInbox) {
            InboxTools(state, onEvent)
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
                // Measured rather than assumed: the same page is this wide
                // inside the Account Hub and half as wide again in its own
                // window, and the column set has to follow.
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    ZillitDataTable(
                        rows = rows,
                        columns = receiptColumns(state, bulkable, maxWidth < WIDE_ENOUGH, onEvent),
                        key = { it.id },
                        loading = state.loading,
                        onRowClick = { onEvent(CardEvent.SelectReceipt(it.id)) },
                        isSelected = { it.id == state.selectedReceiptId },
                        emptyTitle = state.emptyTitle(),
                        emptyMessage = state.emptyMessage(),
                    )
                }
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

@Suppress("LongMethod") // Search, filter and the bulk bar, which only exist together.
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
        val statuses = state.receipts.map { it.status }.distinct()
        if (statuses.size > 1) {
            ZillitSelect(
                value = state.statusFilter,
                options = listOf(ALL_STATUSES) + statuses.map { it.wire },
                onSelect = { onEvent(CardEvent.FilterStatus(it)) },
                label = { wire ->
                    if (wire == ALL_STATUSES) "All statuses" else CardWorkflowStatus.from(wire).label
                },
                modifier = Modifier.width(FILTER_WIDTH),
            )
        }
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
 * The inbox's one bulk tool: running the matcher again over a statement.
 *
 * Offered per statement rather than globally because that is what the server
 * takes, and because re-matching everything on a production with a year of
 * imports is not a button anybody should be able to press by accident.
 */
@Composable
private fun InboxTools(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val unmatched = state.receipts.count { it.matchStatus == MatchStatus.Unmatched }
    val latest = state.imports.firstOrNull() ?: return
    if (unmatched == 0) return

    ZillitNotice(
        text = "$unmatched receipt(s) are not tied to a statement line. " +
            "Running the matcher again over ${latest.filename ?: "the latest statement"} may find them.",
        tone = StatusTone.Pending,
        icon = ZillitIcons.Reload,
        action = {
            ZillitButton(
                text = "Re-run matching",
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.RerunMatching,
                                latest.id,
                                "Re-run matching",
                                "Every unmatched receipt is compared against this statement again. " +
                                    "Matches already confirmed are left alone.",
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        },
    )
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
        // Collapsed, the card is its own heading: a section body with nothing
        // in it reads as a panel that failed to load.
        padded = expanded || headroom.exhausted,
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
            if (index > 0) ZillitDivider()
            DraftRow(
                index = index,
                receipt = receipt,
                currency = card?.currency,
                canAttach = state.canAttachFiles,
                uploading = state.uploading,
                removable = state.draft.size > 1,
                onChange = { onEvent(CardEvent.EditDraftReceipt(index, it)) },
                onAttach = { onEvent(CardEvent.AttachDraftReceipt(index)) },
                onClearAttachment = { onEvent(CardEvent.ClearDraftAttachment(index)) },
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = "Add another",
                onClick = { onEvent(CardEvent.AddDraftReceipt) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            ZillitText(
                text = "${state.draft.size} receipt(s) · ${money(state.draftTotal, card?.currency)}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
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

/**
 * One receipt on the upload form.
 *
 * What, when and how much are asked for plainly; the budget coding sits behind
 * a disclosure because it is the accounts team's job unless the person
 * uploading happens to know it, and three more always-visible fields made crew
 * think they were required.
 */
@Suppress("LongMethod") // One receipt's whole form; the order is the order it is filled in.
@Composable
private fun DraftRow(
    index: Int,
    receipt: DraftCardReceipt,
    currency: String?,
    canAttach: Boolean,
    uploading: Boolean,
    removable: Boolean,
    onChange: (DraftCardReceipt) -> Unit,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onRemove: () -> Unit,
) {
    var codingOpen by remember(index) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitDateField(
                value = receipt.date.asIsoDate(),
                onValueChange = { onChange(receipt.copy(date = it.asEpochMillis())) },
                label = if (index == 0) "Date" else null,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = receipt.amount,
                onValueChange = { onChange(receipt.copy(amount = it.sanitisedAmount())) },
                label = if (index == 0) "Amount" else null,
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                trailingContent = currency?.takeIf { it.isNotBlank() }?.let { code ->
                    {
                        ZillitText(
                            text = code,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                },
                modifier = Modifier.weight(1f),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitTextField(
                value = receipt.description,
                onValueChange = { onChange(receipt.copy(description = it)) },
                label = if (index == 0) "Merchant or description" else null,
                placeholder = "What did you buy?",
                modifier = Modifier.weight(DESCRIPTION_FIELD),
            )
            Column(modifier = Modifier.weight(1f)) {
                if (index == 0) {
                    ZillitText(
                        text = "Category",
                        style = ZillitTheme.typography.label,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                ZillitSelect(
                    value = receipt.category,
                    options = ReceiptCategory.entries,
                    onSelect = { onChange(receipt.copy(category = it)) },
                    label = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        AttachmentSlot(
            fileName = receipt.attachmentName ?: receipt.attachmentKey?.substringAfterLast('/'),
            onPick = onAttach,
            onClear = onClearAttachment,
            enabled = canAttach,
            busy = uploading,
            label = "Attach the receipt",
            disabledHint = "No file picker is available in this build, so receipts cannot be uploaded here.",
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSwitch(
                checked = receipt.urgent,
                onCheckedChange = { onChange(receipt.copy(urgent = it)) },
                label = "Urgent",
            )
            ZillitSwitch(
                checked = receipt.requestTopUp,
                onCheckedChange = { onChange(receipt.copy(requestTopUp = it)) },
                label = "Request a top-up with it",
            )
            ZillitButton(
                text = if (codingOpen) "Hide budget coding" else "Budget coding (optional)",
                onClick = { codingOpen = !codingOpen },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = if (codingOpen) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
            )
        }

        if (codingOpen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitTextField(
                    value = receipt.costCode,
                    onValueChange = { onChange(receipt.copy(costCode = it)) },
                    label = "Cost code",
                    placeholder = "4100",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = receipt.episode,
                    onValueChange = { onChange(receipt.copy(episode = it)) },
                    label = "Episode",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = receipt.codedDescription,
                    onValueChange = { onChange(receipt.copy(codedDescription = it)) },
                    label = "Coding note",
                    modifier = Modifier.weight(DESCRIPTION_FIELD),
                )
            }
        }
    }
}

/** One receipt: what it matched, how it was coded, and what can be done. */
@Suppress("LongMethod") // One receipt, top to bottom; the order is the reading order.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceiptDetail(state: CardUiState, receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = DetailPanePadding,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = receipt.description.ifBlank { receipt.merchant ?: "Receipt" },
                    style = ZillitTheme.typography.titleMedium,
                )
                ZillitText(
                    text = "${state.personName(receipt.holderId, receipt.holderName)} · ${date(receipt.date)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            WorkflowStatusPill(receipt.status)
        }

        receipt.attachmentKey?.takeIf { it.isNotBlank() }?.let { key ->
            ZillitButton(
                // The document is what the figures are being checked against,
                // so it sits with them rather than among the decide actions.
                text = if (key.endsWith(".pdf", ignoreCase = true)) {
                    "Open receipt (PDF)"
                } else {
                    "View receipt"
                },
                onClick = { onEvent(CardEvent.ViewReceipt(key)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Eye,
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            ZillitText(
                text = money(receipt.amount, receipt.currency),
                style = ZillitTheme.typography.displayLarge,
                modifier = Modifier.weight(1f),
            )
            if (receipt.urgent) {
                ZillitStatusPill(label = "Urgent", tone = StatusTone.Escalated, dot = true)
            }
        }

        // The exception flags come first: a duplicate or a personal-spend
        // suspicion changes what should happen to the row, and finding it after
        // approving is finding it too late.
        ExceptionFlags(state, receipt, onEvent)

        ZillitDivider()

        MatchSection(state, receipt, onEvent)

        ZillitDivider()

        CodingSection(state, receipt, onEvent)

        ZillitDivider()
        ReceiptActions(state, receipt, onEvent)

        if (state.receiptHistory.isNotEmpty()) {
            ZillitDivider()
            FieldGroupLabel("History")
            CardHistoryTrail(entries = state.receiptHistory)
        }
    }
}

@Composable
private fun ExceptionFlags(state: CardUiState, receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
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
                    enabled = !state.busy,
                )
            },
        )
    }
    if (receipt.personalScore != null && !receipt.personalDismissed) {
        ZillitNotice(
            text = "Looks like personal spend — ${receipt.personalScore}% confidence.",
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Warning,
            action = {
                // The counterpart to Flag personal, and the flag is useless
                // without it: a wrong suspicion the accountant cannot clear
                // sits on the receipt for the life of the production.
                ZillitButton(
                    text = "Not personal",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.DismissPersonal,
                                    receipt.id,
                                    "Clear the personal-spend flag",
                                    "The receipt is treated as project spend and continues as normal.",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            },
        )
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
@OptIn(ExperimentalLayoutApi::class)
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
                // Wraps: three match actions do not fit across a detail pane
                // at the width the Account Hub leaves for one, and a button
                // broken over three lines reads as a layout failure.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
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
                    // The statement side of flag-personal, which releases the
                    // holder's committed headroom as well as flagging the row.
                    // Only reachable here: the accountant is looking at the
                    // line the charge actually landed on.
                    ZillitButton(
                        text = "Flag the charge personal",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.FlagTransactionPersonal,
                                        receipt.transactionId,
                                        "Flag the statement line as personal",
                                        "The charge leaves the project's workflow, the receipt follows it, " +
                                            "and the holder's committed limit is released.",
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

/**
 * The coding, and the three things that can be done with it.
 *
 * Only the two coding queues get the editor. Everywhere else the coding is a
 * fact about the receipt rather than a decision to make, and an editable field
 * on the approval queue invites an approver to change what they are approving.
 */
@Suppress("LongMethod") // The editor and its commits, which belong together.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodingSection(state: CardUiState, receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    // A holder may code their own receipt before sending it on, which is the
    // web's "Save code" on My Transactions; a coordinator codes other
    // people's on the two coding queues. Everywhere else the coding is a fact
    // about the receipt rather than a decision, and an editable field on the
    // approval queue invites an approver to change what they are approving.
    val ownReceipt = state.destination == CardDestination.MyTransactions
    val editable = (ownReceipt || state.destination in CODING_DESTINATIONS) && !receipt.status.isPosted
    if (!editable) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldGroupLabel("Coding")
            ZillitText(
                text = listOfNotNull(
                    receipt.nominalCode?.takeIf { it.isNotBlank() },
                    receipt.episode?.takeIf { it.isNotBlank() }?.let { "episode $it" },
                    receipt.codeDescription?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "Not coded yet." },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        return
    }

    val draft = state.coding?.takeIf { it.receiptId == receipt.id } ?: CodingDraft.of(receipt)

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        FieldGroupLabel("Coding")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.nominalCode,
                onValueChange = { onEvent(CardEvent.EditCoding(draft.copy(nominalCode = it))) },
                label = "Nominal code",
                placeholder = "4100",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.episode,
                onValueChange = { onEvent(CardEvent.EditCoding(draft.copy(episode = it))) },
                label = "Episode",
                modifier = Modifier.weight(1f),
            )
        }
        ZillitTextField(
            value = draft.codeDescription,
            onValueChange = { onEvent(CardEvent.EditCoding(draft.copy(codeDescription = it))) },
            label = "Coding note",
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitButton(
                // One button on a holder's own receipt, three on a
                // coordinator's queue: a holder has nothing to add after the
                // code, so there is no half-finished state to save.
                text = if (ownReceipt) "Save coding" else "Save draft",
                onClick = { onEvent(CardEvent.SaveCodingDraft) },
                variant = if (ownReceipt) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = (!ownReceipt || draft.coded) && !state.busy,
            )
            if (ownReceipt) return@FlowRow
            ZillitButton(
                text = "Save and send",
                onClick = { onEvent(CardEvent.SubmitCoding) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = draft.coded && !state.busy,
            )
            // Offered only to a coordinator who also approves: without it they
            // code the receipt here and then approve their own coding from the
            // next screen, which is two clicks to say one thing.
            if (state.viewer.isApprover) {
                ZillitButton(
                    text = "Code and approve",
                    onClick = { onEvent(CardEvent.ApproveAndSubmitCoding) },
                    size = ButtonSize.Small,
                    enabled = draft.coded && !state.busy,
                )
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // A rights table; flattening it is what makes it readable.
@OptIn(ExperimentalLayoutApi::class)
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
                                    "It leaves the project's expense workflow and the holder is charged back.",
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
            text = if (state.destination == CardDestination.History) {
                "This receipt is posted. Its trail below is the record of how it got here."
            } else {
                "Nothing to do on this receipt from here."
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
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
    narrow: Boolean,
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
    // Every row on My Transactions belongs to the person reading it.
    if (state.destination != CardDestination.MyTransactions) {
        add(
            TableColumn(
                header = "Holder",
                width = ColumnWidth.Weight(1.1f),
                cell = { PersonCell(state.personName(it.holderId, it.holderName), userId = it.holderId) },
            ),
        )
    }
    add(textColumn("Amount", ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    // Date comes off in a narrow table. Inside the Account Hub the hub's
    // sidebar, the tool's own and the detail pane leave this about five
    // hundred points, and the column pushed off the end was the status — which
    // is the one thing a queue is scanned for. The pane states the date.
    if (!narrow) {
        add(textColumn("Date", ColumnWidth.Weight(1f), muted = true) { date(it.date) })
    }
    if (state.destination == CardDestination.History) {
        add(textColumn("Code", ColumnWidth.Weight(0.8f), muted = true) { it.nominalCode ?: "—" })
    }
    add(
        TableColumn(
            header = "Status",
            width = ColumnWidth.Fixed(STATUS_COLUMN),
            cell = { row ->
                // The inbox reads in reconciliation vocabulary, everywhere else
                // in workflow vocabulary — see CardReceipt.reconciliationLabel.
                //
                // There is no separate match column: in the inbox this one
                // already *is* the match, and everywhere else the detail pane
                // states it with its confidence. Carrying both put the status
                // — the one thing a queue is scanned for — off the end of a
                // half-width table.
                if (state.destination == CardDestination.ReceiptInbox) {
                    ReconciliationPill(row.reconciliationLabel(), row.status)
                } else {
                    WorkflowStatusPill(row.status)
                }
            },
        ),
    )
}

/**
 * All Transactions — the statement side, rather than the receipt side.
 *
 * Deleting is offered here and nowhere else, because this is the only screen
 * that shows a statement line in its own right: a line imported twice, or
 * against the wrong card, has to be removable without touching the receipt
 * somebody uploaded against it — which the server unlinks rather than deletes.
 */
@Suppress("LongMethod") // Toolbar, table and pane; one screen.
@Composable
fun TransactionsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.transactions
        .filter { state.statusFilter == ALL_STATUSES || it.status.wire == state.statusFilter }
        .filter { it.matches(state.search) }
    val selected = state.selectedTransaction

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
            val statuses = state.transactions.map { it.status }.distinct()
            if (statuses.size > 1) {
                ZillitSelect(
                    value = state.statusFilter,
                    options = listOf(ALL_STATUSES) + statuses.map { it.wire },
                    onSelect = { onEvent(CardEvent.FilterStatus(it)) },
                    label = { wire ->
                        if (wire == ALL_STATUSES) "All statuses" else CardWorkflowStatus.from(wire).label
                    },
                    modifier = Modifier.width(FILTER_WIDTH),
                )
            }
            ZillitText(
                text = "${rows.size} transaction${if (rows.size == 1) "" else "s"}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (state.viewer.isAccountant && state.selection.isNotEmpty()) {
                ZillitButton(
                    text = "Delete ${state.selection.size} selected",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.BulkDeleteTransactions,
                                    "",
                                    "Delete ${state.selection.size} statement line(s)",
                                    "Any receipt matched to one returns to the inbox rather than being " +
                                        "deleted with it. This cannot be undone.",
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

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = "All transactions",
                icon = ZillitIcons.Ledger,
                meta = money(rows.sumOf { it.amount }, rows.firstOrNull()?.currency),
                padded = false,
                modifier = Modifier.weight(QUEUE_WEIGHT).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = rows,
                    columns = transactionColumns(state, onEvent),
                    key = { it.id },
                    loading = state.loading,
                    onRowClick = { onEvent(CardEvent.SelectTransaction(it.id)) },
                    isSelected = { it.id == state.selectedTransactionId },
                    emptyTitle = "No transactions",
                    emptyMessage = "Statement lines appear here once a statement is imported.",
                )
            }

            ZillitSectionCard(
                title = "Transaction",
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = "Pick a transaction",
                        message = "Its coding, its match and what can be done with it show here.",
                        icon = ZillitIcons.Ledger,
                    )
                } else {
                    TransactionDetail(state, selected, onEvent)
                }
            }
        }
    }
}

@Suppress("LongMethod") // One transaction top to bottom.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransactionDetail(
    state: CardUiState,
    transaction: CardTransaction,
    onEvent: (CardEvent) -> Unit,
) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = DetailPanePadding,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = transaction.merchant.ifBlank { transaction.description ?: "Statement line" },
                    style = ZillitTheme.typography.titleMedium,
                )
                ZillitText(
                    text = listOfNotNull(
                        state.personName(transaction.holderId, transaction.holderName)
                            .takeIf { it != "—" },
                        transaction.cardLastFour?.let { "•••• $it" },
                        date(transaction.date).takeIf { it != "—" },
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            WorkflowStatusPill(transaction.status)
        }

        ZillitText(
            text = money(transaction.amount, transaction.currency),
            style = ZillitTheme.typography.displayLarge,
        )

        if (transaction.personal) {
            ZillitNotice(
                text = "Flagged as personal spend. It has left the project's expense workflow.",
                tone = StatusTone.Escalated,
                icon = ZillitIcons.Warning,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldGroupLabel("Coding")
            ZillitText(
                text = listOfNotNull(
                    transaction.nominalCode?.takeIf { it.isNotBlank() },
                    transaction.episode?.takeIf { it.isNotBlank() }?.let { "episode $it" },
                    transaction.codeDescription?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "Not coded yet." },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldGroupLabel("Reconciliation")
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MatchStatusPill(transaction.matchStatus, null)
                ZillitText(
                    text = if (transaction.receiptId != null) {
                        "A receipt is attached to this line."
                    } else {
                        "No receipt has been matched to this line yet."
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }

        if (!state.viewer.isAccountant) return@ZillitScrollColumn

        ZillitDivider()
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            if (!transaction.status.isPosted && !transaction.personal) {
                ZillitButton(
                    text = "Post",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.PostTransaction,
                                    transaction.id,
                                    "Post this transaction",
                                    "${money(transaction.amount, transaction.currency)} goes to the ledger.",
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = "Flag personal",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.FlagTransactionPersonal,
                                    transaction.id,
                                    "Flag this charge as personal",
                                    "It leaves the project's workflow, a matched receipt follows it, " +
                                        "and the holder's committed limit is released.",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
            ZillitButton(
                text = "Delete",
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.DeleteTransaction,
                                transaction.id,
                                "Delete this statement line",
                                "A receipt matched to it returns to the inbox rather than being deleted " +
                                    "with it. This cannot be undone.",
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
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun transactionColumns(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<CardTransaction>> = buildList {
    if (state.viewer.isAccountant) {
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
    add(textColumn("Merchant", ColumnWidth.Weight(1.6f)) { it.merchant.ifBlank { it.description ?: "—" } })
    add(
        textColumn("Card", ColumnWidth.Weight(0.9f), muted = true) {
            it.cardLastFour?.let { last -> "•••• $last" } ?: "—"
        },
    )
    add(
        TableColumn(
            header = "Holder",
            width = ColumnWidth.Weight(1.1f),
            cell = { PersonCell(state.personName(it.holderId, it.holderName), userId = it.holderId) },
        ),
    )
    add(textColumn("Amount", ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    add(textColumn("Date", ColumnWidth.Weight(1f), muted = true) { date(it.date) })
    add(textColumn("Code", ColumnWidth.Weight(0.8f), muted = true) { it.nominalCode ?: "—" })
    add(
        TableColumn(
            header = "Status",
            width = ColumnWidth.Fixed(STATUS_COLUMN),
            cell = { WorkflowStatusPill(it.status) },
        ),
    )
}

private fun CardReceipt.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return description.lowercase().contains(needle) ||
        merchant?.lowercase()?.contains(needle) == true ||
        nominalCode?.lowercase()?.contains(needle) == true ||
        holderName.lowercase().contains(needle)
}

private fun CardTransaction.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return merchant.lowercase().contains(needle) ||
        holderName.lowercase().contains(needle) ||
        cardLastFour?.contains(needle) == true ||
        description?.lowercase()?.contains(needle) == true
}

/** `1739923200000` → `2026-08-12`, for the date field. See [CardDates]. */
private fun Long?.asIsoDate(): String = CardDates.toIso(this)

/** The date field's `YYYY-MM-DD` back to the millis the wire wants. */
private fun String.asEpochMillis(): Long? = CardDates.toMillis(this)

/**
 * Keeps an amount field to digits and one decimal point.
 *
 * Typed rather than validated on submit: the web shipped this as free text and
 * accepted "abc" as an amount, which the server then read as zero.
 */
private fun String.sanitisedAmount(): String {
    val kept = filter { it.isDigit() || it == '.' }
    val firstPoint = kept.indexOf('.')
    if (firstPoint < 0) return kept
    return kept.substring(0, firstPoint + 1) + kept.substring(firstPoint + 1).filter { it.isDigit() }
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
    destination == CardDestination.CodingQueue -> "Receipts your department has to code appear here."
    destination == CardDestination.ApprovalQueue -> "Receipts routed to you for approval appear here."
    destination == CardDestination.MyTransactions -> "Upload a receipt to get started."
    destination == CardDestination.History -> "Posted receipts are kept here for the life of the production."
    else -> null
}

private val CODING_DESTINATIONS = setOf(CardDestination.PendingCoding, CardDestination.CodingQueue)

private const val QUEUE_WEIGHT = 1.85f
private const val DETAIL_WEIGHT = 1f
private const val DESCRIPTION_FIELD = 2f
private const val MAX_CANDIDATES = 5

/** Below this, the queue drops its date column. See `receiptColumns`. */
private val WIDE_ENOUGH = 620.dp
private val SEARCH_WIDTH = 320.dp
private val FILTER_WIDTH = 190.dp
private val STATUS_COLUMN = 128.dp
private val CHECK_COLUMN = 40.dp
