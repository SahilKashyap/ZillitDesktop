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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCategory
import com.zillit.desktop.feature.cardexpenses.domain.canDelete
import com.zillit.desktop.feature.cardexpenses.domain.InboxSection
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.feature.cardexpenses.ui.ProcessMode
import com.zillit.desktop.feature.cardexpenses.ui.ProcessTab
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
@Suppress("LongMethod") // One page, laid out in one place; the sweep's wrapped calls added the lines.
@Composable
fun ReceiptQueuePage(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
    /** Rendered above the toolbar — History's totals, and nothing else so far. */
    banner: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val rows = state.receipts
        .filter { state.destination != CardDestination.ProcessQueue || it.inTab(state.processTab) }
        .filter {
            state.destination != CardDestination.ReceiptInbox || state.inboxSection == null ||
                it.inboxSection == state.inboxSection
        }
        .filter { state.statusFilter == ALL_STATUSES || it.status.wire == state.statusFilter }
        .filter { it.matches(state.search) }
    val selected = rows.firstOrNull { it.id == state.selectedReceiptId }
    // Approvers tick to approve or reject, override-holders to override —
    // independently of one another, as the web's floating bar does.
    val bulkable = state.destination == CardDestination.ApprovalQueue &&
        (state.viewer.isApprover || state.viewer.canOverrideReceipt)

    FixedPage {
        banner?.invoke(this)
        if (state.destination == CardDestination.ProcessQueue) ProcessTabs(state, onEvent)
        QueueHeader(state, rows.size, bulkable, onEvent)

        if (state.destination == CardDestination.MyTransactions) {
            UploadPanel(state, onEvent)
        }

        if (state.destination == CardDestination.ReceiptInbox) {
            InboxTools(state, onEvent)
            InboxSections(state, onEvent)
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = state.destination.label,
                icon = ZillitIcons.Receipt,
                meta = if (rows.size == 1) {
                    str(S.desktop_card_row_count_one, rows.size)
                } else {
                    str(S.desktop_n_rows, rows.size)
                },
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
                title = str(S.desktop_receipt),
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = str(S.desktop_card_pick_a_receipt),
                        message = str(S.desktop_card_pick_receipt_hint),
                        icon = ZillitIcons.Eye,
                    )
                } else {
                    ReceiptDetail(state, selected, onEvent)
                }
            }
        }
    }
}

/**
 * The Process page's two queues (`ProcessPage.jsx:505-510`): what is ready to
 * post, and — for a senior only — what a colleague handed up for review.
 */
@Composable
private fun ProcessTabs(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val senior = state.viewer.isSenior
    val tabs = buildList {
        add(ProcessTab.Processing.tab(str(S.desktop_card_processing_queue), state))
        if (senior) {
            add(ProcessTab.Review.tab(str(S.desktop_card_posting_review_queue), state))
        }
    }
    ZillitTabStrip(
        tabs = tabs,
        activeId = state.processTab.name,
        onSelect = { id ->
            ProcessTab.entries.firstOrNull { it.name == id }?.let { onEvent(CardEvent.ShowProcessTab(it)) }
        },
    )
}

private fun ProcessTab.tab(label: String, state: CardUiState) =
    ZillitTab(name, label, count = state.receipts.count { it.inTab(this) })

/**
 * The inbox's four sections — system matched, no match, duplicate, personal —
 * as a switch over one table, each with its count (`ReceiptInboxPage.jsx:642-727`).
 */
@Composable
private fun InboxSections(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val options = listOf(ZillitTab(ALL_SECTIONS, str(S.all), state.receipts.size)) +
        InboxSection.entries.map { section ->
            ZillitTab(section.name, section.label, state.receipts.count { it.inboxSection == section })
        }
    ZillitTabStrip(
        tabs = options,
        activeId = state.inboxSection?.name ?: ALL_SECTIONS,
        onSelect = { id ->
            onEvent(CardEvent.FilterInboxSection(InboxSection.entries.firstOrNull { it.name == id }))
        },
    )
}

/** Which Process tab a receipt belongs on: approved ones to post, handed-up ones to review. */
private fun CardReceipt.inTab(tab: ProcessTab): Boolean = when (tab) {
    ProcessTab.Processing -> status == CardWorkflowStatus.Approved
    ProcessTab.Review -> status == CardWorkflowStatus.UnderReview || status == CardWorkflowStatus.Escalated
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
            placeholder = str(S.desktop_card_search_receipts),
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        val statuses = state.receipts.map { it.status }.distinct()
        if (statuses.size > 1) {
            ZillitSelect(
                value = state.statusFilter,
                options = listOf(ALL_STATUSES) + statuses.map { it.wire },
                onSelect = { onEvent(CardEvent.FilterStatus(it)) },
                label = { wire ->
                    if (wire == ALL_STATUSES) str(S.desktop_all_statuses) else CardWorkflowStatus.from(wire).label
                },
                modifier = Modifier.width(FILTER_WIDTH),
            )
        }
        ZillitText(
            text = str(S.desktop_card_showing_count, count),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        // The bulk bar appears only once something is ticked: a permanently
        // visible pair of Approve All / Reject All buttons above a queue is an
        // accident waiting for a mis-click.
        if (bulkable && state.selection.isNotEmpty()) {
            ZillitText(
                text = str(S.dd_n_selected, state.selection.size),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            if (state.viewer.isApprover) {
                ZillitButton(
                    text = str(S.desktop_approve_selected),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.BulkApprove,
                                    "",
                                    str(S.desktop_card_approve_receipts_count, state.selection.size),
                                    str(S.desktop_card_approve_bulk_note),
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = str(S.desktop_card_reject_selected),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.BulkReject,
                                    "",
                                    str(S.desktop_card_reject_receipts_count, state.selection.size),
                                    str(S.desktop_card_reject_bulk_note),
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
            // Overriding skips everybody still due to approve, which is why it
            // is its own button and asks first (ApprovalQueuePage.jsx:589-611).
            if (state.viewer.canOverrideReceipt) {
                ZillitButton(
                    text = str(S.desktop_card_override_count, state.selection.size),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.BulkOverride,
                                    "",
                                    str(S.desktop_card_override_count, state.selection.size),
                                    str(S.desktop_card_override_receipt_note),
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
            ZillitButton(
                text = str(S.ah_clear),
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
        text = str(
            S.desktop_card_unmatched_note,
            unmatched,
            latest.filename ?: str(S.desktop_card_latest_statement),
        ),
        tone = StatusTone.Pending,
        icon = ZillitIcons.Reload,
        action = {
            ZillitButton(
                text = str(S.desktop_card_rerun_matching),
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.RerunMatching,
                                latest.id,
                                str(S.desktop_card_rerun_matching),
                                str(S.desktop_card_rerun_matching_note),
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
        title = str(S.ah_upload_receipts),
        icon = ZillitIcons.Upload,
        meta = str(S.desktop_card_available_amount, money(headroom.available, card?.currency)),
        // Collapsed, the card is its own heading: a section body with nothing
        // in it reads as a panel that failed to load.
        padded = expanded || headroom.exhausted,
        action = {
            ZillitButton(
                text = if (expanded) str(S.hide) else str(S.desktop_card_add_receipts),
                onClick = { expanded = !expanded },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = if (expanded) ZillitIcons.Close else ZillitIcons.Add,
            )
        },
    ) {
        if (headroom.exhausted) {
            ZillitNotice(
                text = str(S.desktop_card_limit_committed_upload),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                action = {
                    ZillitButton(
                        text = str(S.ah_card_extension),
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
                text = str(
                    S.desktop_card_batch_over_available,
                    money(state.draftTotal, card?.currency),
                    money(headroom.available, card?.currency),
                ),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = str(S.desktop_dm_add_another),
                onClick = { onEvent(CardEvent.AddDraftReceipt) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            ZillitText(
                text = str(
                    S.desktop_card_draft_summary,
                    state.draft.size,
                    money(state.draftTotal, card?.currency),
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.desktop_card_upload_receipts_count, state.draft.size),
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
                label = if (index == 0) str(S.date) else null,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = receipt.amount,
                onValueChange = { onChange(receipt.copy(amount = it.sanitisedAmount())) },
                label = if (index == 0) str(S.amount) else null,
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
                label = if (index == 0) str(S.desktop_card_merchant_or_description) else null,
                placeholder = str(S.desktop_card_what_did_you_buy),
                modifier = Modifier.weight(DESCRIPTION_FIELD),
            )
            Column(modifier = Modifier.weight(1f)) {
                if (index == 0) {
                    ZillitText(
                        text = str(S.av_category),
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
            label = str(S.desktop_card_attach_the_receipt),
            disabledHint = str(S.desktop_card_no_picker_receipts),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSwitch(
                checked = receipt.urgent,
                onCheckedChange = { onChange(receipt.copy(urgent = it)) },
                label = str(S.ah_topup_filter_urgent),
            )
            ZillitSwitch(
                checked = receipt.requestTopUp,
                onCheckedChange = { onChange(receipt.copy(requestTopUp = it)) },
                label = str(S.desktop_card_request_topup_with_it),
            )
            ZillitButton(
                text = if (codingOpen) {
                    str(S.desktop_card_hide_budget_coding)
                } else {
                    str(S.desktop_card_budget_coding_optional)
                },
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
                    label = str(S.desktop_card_cost_code),
                    placeholder = "4100",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = receipt.episode,
                    onValueChange = { onChange(receipt.copy(episode = it)) },
                    label = str(S.episode),
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = receipt.codedDescription,
                    onValueChange = { onChange(receipt.copy(codedDescription = it)) },
                    label = str(S.desktop_card_coding_note),
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
                    text = receipt.description.ifBlank { receipt.merchant ?: str(S.desktop_receipt) },
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

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            receipt.attachmentKey?.takeIf { it.isNotBlank() }?.let { key ->
                ZillitButton(
                    // The document is what the figures are being checked against,
                    // so it sits with them rather than among the decide actions.
                    text = if (key.endsWith(".pdf", ignoreCase = true)) {
                        str(S.desktop_card_open_receipt_pdf)
                    } else {
                        str(S.desktop_card_view_receipt)
                    },
                    onClick = { onEvent(CardEvent.ViewReceipt(key)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Eye,
                )
            }
            // The receipt's query thread: the accounts team asking, the
            // holder answering (ReceiptDetailModal.jsx:406-415).
            if (state.viewer.isAccountant || receipt.holderId == state.viewer.userId) {
                ZillitButton(
                    text = str(S.ah_query_label),
                    onClick = { onEvent(CardEvent.OpenQuery(receipt.id)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Info,
                )
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            ZillitText(
                text = money(receipt.amount, receipt.currency),
                style = ZillitTheme.typography.displayLarge,
                modifier = Modifier.weight(1f),
            )
            if (receipt.urgent) {
                ZillitStatusPill(label = str(S.ah_topup_filter_urgent), tone = StatusTone.Escalated, dot = true)
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
            FieldGroupLabel(str(S.history))
            CardHistoryTrail(entries = state.receiptHistory)
        }
    }
}

@Composable
private fun ExceptionFlags(state: CardUiState, receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    if (receipt.duplicateScore != null && !receipt.duplicateDismissed) {
        ZillitNotice(
            text = str(S.desktop_card_possible_duplicate, receipt.duplicateScore),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Warning,
            action = {
                ZillitButton(
                    text = str(S.desktop_card_not_a_duplicate),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.DismissDuplicate,
                                    receipt.id,
                                    str(S.desktop_card_dismiss_duplicate_flag),
                                    str(S.desktop_card_duplicate_dismiss_note),
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
            text = str(S.desktop_card_looks_personal, receipt.personalScore),
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Warning,
            action = {
                // The counterpart to Flag personal, and the flag is useless
                // without it: a wrong suspicion the accountant cannot clear
                // sits on the receipt for the life of the production.
                ZillitButton(
                    text = str(S.desktop_card_not_personal),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.DismissPersonal,
                                    receipt.id,
                                    str(S.desktop_card_clear_personal_flag),
                                    str(S.desktop_card_personal_clear_note),
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
            ZillitText(text = str(S.desktop_card_statement_match), style = ZillitTheme.typography.titleSmall)
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
                        text = str(S.desktop_card_confirm_match),
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.ConfirmMatch,
                                        receipt.id,
                                        str(S.desktop_card_confirm_this_match),
                                        str(S.desktop_card_confirm_match_note),
                                    ),
                                ),
                            )
                        },
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = str(S.desktop_card_unmatch),
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.UnmatchReceipt,
                                        receipt.id,
                                        str(S.desktop_card_remove_this_match),
                                        str(S.desktop_card_unmatch_note),
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    // No separate "flag the charge" here: the receipt's own
                    // Flag Personal goes to the statement line whenever the
                    // receipt is linked to one, as the web's row menu does.
                }
            }
        } else if (state.matchCandidates.isEmpty()) {
            ZillitText(
                text = str(S.desktop_card_no_suggested_line),
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
                text = candidate.merchant.ifBlank { str(S.desktop_card_statement_line) },
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
            text = str(S.desktop_match),
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
            FieldGroupLabel(str(S.desktop_card_coding))
            ZillitText(
                text = listOfNotNull(
                    receipt.nominalCode?.takeIf { it.isNotBlank() },
                    receipt.episode?.takeIf { it.isNotBlank() }?.let { str(S.desktop_card_episode_value, it) },
                    receipt.codeDescription?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { str(S.desktop_card_not_coded_yet) },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        return
    }

    val draft = state.coding?.takeIf { it.receiptId == receipt.id } ?: CodingDraft.of(receipt)

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        FieldGroupLabel(str(S.desktop_card_coding))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.nominalCode,
                onValueChange = { onEvent(CardEvent.EditCoding(draft.copy(nominalCode = it))) },
                label = str(S.ah_lbl_nominal_code),
                placeholder = "4100",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.episode,
                onValueChange = { onEvent(CardEvent.EditCoding(draft.copy(episode = it))) },
                label = str(S.episode),
                modifier = Modifier.weight(1f),
            )
        }
        ZillitTextField(
            value = draft.codeDescription,
            onValueChange = { onEvent(CardEvent.EditCoding(draft.copy(codeDescription = it))) },
            label = str(S.desktop_card_coding_note),
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
                text = if (ownReceipt) str(S.desktop_card_save_coding) else str(S.ah_save_draft),
                onClick = { onEvent(CardEvent.SaveCodingDraft) },
                variant = if (ownReceipt) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = (!ownReceipt || draft.coded) && !state.busy,
            )
            if (ownReceipt) return@FlowRow
            ZillitButton(
                text = str(S.desktop_card_save_and_send),
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
                    text = str(S.desktop_card_code_and_approve),
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
                        Action(str(S.approve), ButtonVariant.Primary) {
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.ApproveReceipt,
                                    receipt.id,
                                    str(S.desktop_card_approve_this_receipt),
                                    str(S.desktop_card_moves_on_to_post, money(receipt.amount, receipt.currency)),
                                ),
                            )
                        },
                    )
                    add(
                        Action(str(S.reject), ButtonVariant.Danger) {
                            CardEvent.Ask(
                                CardPrompt.WithReason(
                                    CardReasonAction.RejectReceipt,
                                    receipt.id,
                                    str(S.desktop_card_reject_this_receipt),
                                    str(S.desktop_timecard_reject_label),
                                ),
                            )
                        },
                    )
                }
                if (state.viewer.canOverrideReceipt) {
                    add(
                        Action(str(S.dm_nom_table_override), ButtonVariant.Secondary) {
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.OverrideReceipt,
                                    receipt.id,
                                    str(S.desktop_card_override_chain),
                                    str(S.desktop_card_override_receipt_note),
                                ),
                            )
                        },
                    )
                }
            }

            // Straight to the editor: processing is a task, not a decision,
            // and a confirmation in front of it would ask a question with only
            // one sensible answer. A row assigned to somebody else is theirs
            // unless this viewer is a senior (ProcessPage.jsx:436-438).
            CardDestination.ProcessQueue -> {
                if (ProcessRules.canOpen(state.viewer, receipt)) {
                    add(
                        Action(str(S.ah_process_btn), ButtonVariant.Primary) {
                            CardEvent.OpenProcess(receipt.id, ProcessMode.Process)
                        },
                    )
                }
            }

            // History corrects a posted receipt — save only, no top-up and no
            // second post (HistoryPage.jsx:52-58).
            CardDestination.History -> {
                if (state.viewer.isAccountant) {
                    add(
                        Action(str(S.edit), ButtonVariant.Secondary) {
                            CardEvent.OpenProcess(receipt.id, ProcessMode.History)
                        },
                    )
                }
            }

            // The inbox reconciles; it does not post. Attach and Manual Match
            // live with the match above, and Flag Personal is the one row
            // action left (ReceiptInboxPage.jsx:569-631) — for receipts not
            // already set aside as personal or duplicate.
            CardDestination.ReceiptInbox -> {
                val open = receipt.inboxSection == InboxSection.SystemMatched ||
                    receipt.inboxSection == InboxSection.NoMatch
                if (state.viewer.isAccountant && open) {
                    add(
                        Action(str(S.ah_flag_personal), ButtonVariant.Tertiary) {
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.FlagPersonal,
                                    receipt.id,
                                    str(S.desktop_card_flag_as_personal_spend),
                                    str(S.desktop_card_flag_personal_note),
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
                        Action(str(S.av_send_for_approval), ButtonVariant.Primary) {
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.SubmitReceiptForApproval,
                                    receipt.id,
                                    str(S.desktop_card_send_this_for_approval),
                                    str(S.desktop_card_send_approval_note),
                                ),
                            )
                        },
                    )
                }
                if (!receipt.status.isPosted) {
                    add(
                        Action(str(S.delete), ButtonVariant.Danger) {
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.DeleteReceipt,
                                    receipt.id,
                                    str(S.desktop_card_delete_this_receipt),
                                    str(S.desktop_card_delete_receipt_note),
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
        val assignee = receipt.assignedTo?.takeIf { it.isNotBlank() }
        ZillitText(
            text = when {
                state.destination == CardDestination.History -> str(S.desktop_card_receipt_posted_trail)
                state.destination == CardDestination.ProcessQueue && assignee != null ->
                    str(S.desktop_card_assigned_to_other, state.personName(assignee))

                state.destination == CardDestination.ProcessQueue -> str(S.desktop_card_unassigned_senior)
                else -> str(S.desktop_card_nothing_to_do_receipt)
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
    add(
        textColumn(str(S.description), ColumnWidth.Weight(1.6f)) {
            it.description.ifBlank { it.merchant ?: str(S.desktop_receipt) }
        },
    )
    // Every row on My Transactions belongs to the person reading it.
    if (state.destination != CardDestination.MyTransactions) {
        add(
            TableColumn(
                header = str(S.ah_holder),
                width = ColumnWidth.Weight(1.1f),
                cell = { PersonCell(state.personName(it.holderId, it.holderName), userId = it.holderId) },
            ),
        )
    }
    add(textColumn(str(S.amount), ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    // Date comes off in a narrow table. Inside the Account Hub the hub's
    // sidebar, the tool's own and the detail pane leave this about five
    // hundred points, and the column pushed off the end was the status — which
    // is the one thing a queue is scanned for. The pane states the date.
    if (!narrow) {
        add(textColumn(str(S.date), ColumnWidth.Weight(1f), muted = true) { date(it.date) })
    }
    if (state.destination == CardDestination.History) {
        add(textColumn(str(S.code), ColumnWidth.Weight(0.8f), muted = true) { it.nominalCode ?: "—" })
    }
    // Who the row is with: the web's Assigned column, the lock a
    // non-senior reads before clicking a row they cannot open.
    if (state.destination == CardDestination.ProcessQueue) {
        add(
            textColumn(str(S.assigned), ColumnWidth.Weight(1f), muted = true) { row ->
                row.assignedTo?.takeIf { it.isNotBlank() }?.let { state.personName(it) } ?: str(S.unassigned)
            },
        )
    }
    add(
        TableColumn(
            header = str(S.status),
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
        TransactionTiles(state)
        TransactionFilterBar(state, onEvent)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CardEvent.Search(it)) },
                placeholder = str(S.desktop_card_search_transactions),
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            val statuses = state.transactions.map { it.status }.distinct()
            if (statuses.size > 1) {
                ZillitSelect(
                    value = state.statusFilter,
                    options = listOf(ALL_STATUSES) + statuses.map { it.wire },
                    onSelect = { onEvent(CardEvent.FilterStatus(it)) },
                    label = { wire ->
                        if (wire == ALL_STATUSES) str(S.desktop_all_statuses) else CardWorkflowStatus.from(wire).label
                    },
                    modifier = Modifier.width(FILTER_WIDTH),
                )
            }
            ZillitText(
                text = if (rows.size == 1) {
                    str(S.desktop_card_transaction_count_one, rows.size)
                } else {
                    str(S.desktop_card_transactions_count, rows.size)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (state.viewer.isAccountant) {
                ExportButtons(
                    busy = state.exporting,
                    onExport = { format -> onEvent(CardEvent.ExportTransactions(format)) },
                )
            }
            // Only what is on screen and still deletable counts: a stored
            // selection can outlive a filter, and a destructive action must
            // never reach a row nobody can see (transactionQuery.js:93-104).
            val deletable = rows.filter { it.id in state.selection && it.status.canDelete }.size
            if (state.viewer.isAccountant && deletable > 0) {
                ZillitButton(
                    text = str(S.desktop_card_delete_selected_count, deletable),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.BulkDeleteTransactions,
                                    "",
                                    str(S.desktop_card_delete_lines_count, deletable),
                                    str(S.desktop_card_delete_lines_note),
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = str(S.ah_clear),
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
                title = str(S.ah_all_transactions),
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
                    emptyTitle = str(S.desktop_card_no_transactions),
                    emptyMessage = str(S.desktop_card_transactions_empty),
                )
            }

            ZillitSectionCard(
                title = str(S.desktop_card_transaction),
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = str(S.desktop_card_pick_a_transaction),
                        message = str(S.desktop_card_pick_transaction_hint),
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
                    text = transaction.merchant.ifBlank {
                        transaction.description ?: str(S.desktop_card_statement_line)
                    },
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
                text = str(S.desktop_card_flagged_personal_note),
                tone = StatusTone.Escalated,
                icon = ZillitIcons.Warning,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldGroupLabel(str(S.desktop_card_coding))
            ZillitText(
                text = listOfNotNull(
                    transaction.nominalCode?.takeIf { it.isNotBlank() },
                    transaction.episode?.takeIf { it.isNotBlank() }?.let { str(S.desktop_card_episode_value, it) },
                    transaction.codeDescription?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { str(S.desktop_card_not_coded_yet) },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldGroupLabel(str(S.desktop_card_reconciliation))
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MatchStatusPill(transaction.matchStatus, null)
                ZillitText(
                    text = if (transaction.receiptId != null) {
                        str(S.desktop_card_receipt_attached)
                    } else {
                        str(S.desktop_card_no_receipt_matched)
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
                    text = str(S.txt_post),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.PostTransaction,
                                    transaction.id,
                                    str(S.desktop_card_post_this_transaction),
                                    str(
                                        S.desktop_card_goes_to_ledger,
                                        money(transaction.amount, transaction.currency),
                                    ),
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = str(S.ah_flag_personal),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.FlagTransactionPersonal,
                                    transaction.id,
                                    str(S.desktop_card_flag_this_charge_personal),
                                    str(S.desktop_card_flag_transaction_note),
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
            // Approved and posted lines are bookkeeping, not clutter: removing
            // either is a ledger event, so neither is offered.
            if (transaction.status.canDelete) {
                ZillitButton(
                    text = str(S.delete),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.DeleteTransaction,
                                    transaction.id,
                                    str(S.desktop_card_delete_this_line),
                                    str(S.desktop_card_delete_line_note),
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
                    // Selectable is the same question as deletable: a tick on a
                    // posted row refused at the confirm step is a worse answer
                    // than no tick at all.
                    if (row.status.canDelete) {
                        ZillitCheckbox(
                            checked = row.id in state.selection,
                            onCheckedChange = { onEvent(CardEvent.ToggleSelection(row.id)) },
                        )
                    }
                },
            ),
        )
    }
    add(textColumn(str(S.ah_merchant), ColumnWidth.Weight(1.6f)) { it.merchant.ifBlank { it.description ?: "—" } })
    add(
        textColumn(str(S.ah_my_cards), ColumnWidth.Weight(0.9f), muted = true) {
            it.cardLastFour?.let { last -> "•••• $last" } ?: "—"
        },
    )
    add(
        TableColumn(
            header = str(S.ah_holder),
            width = ColumnWidth.Weight(1.1f),
            cell = { PersonCell(state.personName(it.holderId, it.holderName), userId = it.holderId) },
        ),
    )
    add(textColumn(str(S.amount), ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    add(textColumn(str(S.date), ColumnWidth.Weight(1f), muted = true) { date(it.date) })
    add(textColumn(str(S.code), ColumnWidth.Weight(0.8f), muted = true) { it.nominalCode ?: "—" })
    add(
        TableColumn(
            header = str(S.status),
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
    search.isNotBlank() -> str(S.dm_nda_empty_search)
    destination == CardDestination.History -> str(S.desktop_board_nothing_posted_yet)
    else -> str(S.desktop_nothing_waiting)
}

private fun CardUiState.emptyMessage(): String? = when {
    search.isNotBlank() -> str(S.desktop_card_clear_search_hint)
    destination == CardDestination.ReceiptInbox -> str(S.desktop_card_inbox_empty)
    destination == CardDestination.PendingCoding -> str(S.desktop_card_pending_coding_empty)
    destination == CardDestination.CodingQueue -> str(S.desktop_card_coding_queue_empty)
    destination == CardDestination.ApprovalQueue -> str(S.desktop_card_receipt_approval_empty)
    destination == CardDestination.MyTransactions -> str(S.desktop_card_upload_to_start)
    destination == CardDestination.History -> str(S.desktop_card_history_empty)
    else -> null
}

/**
 * Where coding is edited: the coordinator's queue only. Pending Coding is the
 * accountant's view of what crew have still to code, and the web shows it
 * read-only (`PendingCodingPage.jsx`) — the coding is theirs to do.
 */
private val CODING_DESTINATIONS = setOf(CardDestination.CodingQueue)

/** The inbox switch's "every section" option. */
private const val ALL_SECTIONS = "all"

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
