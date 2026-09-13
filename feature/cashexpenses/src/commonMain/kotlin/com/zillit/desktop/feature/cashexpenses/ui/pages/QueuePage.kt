package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashPeople
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseCategory
import com.zillit.desktop.feature.cashexpenses.domain.Settlement
import com.zillit.desktop.feature.cashexpenses.ui.BatchStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.LifecycleBar
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.ReasonedAction
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.money

/**
 * Every queue of batches, from one composable.
 *
 * ## List on the left, batch on the right
 *
 * The queues are all "work through a list, deciding on each one", and the
 * decision needs the receipts in front of you. A master–detail split keeps the
 * queue in view while a batch is open, so approving one moves straight on to
 * the next — the web opens a full-page view and returns to the top of the list
 * each time, which is why its approval queue is worked in two windows.
 *
 * ## Which actions appear
 *
 * From the destination and the viewer's rights, in [actionsFor]. The
 * alternative — every action always drawn, disabled when not applicable — puts
 * eight buttons under every batch and makes the one correct action hard to
 * find.
 */
@Suppress("LongMethod") // Header, queue and detail pane: one screen, read together.
@Composable
fun QueuePage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val people = LocalCashPeople.current
    val batches = state.queueBatches.filter { it.matches(state.search, people) }
    val selected = batches.firstOrNull { it.id == state.selectedBatchId }

    FixedPage {
        QueueHeader(state, batches.size, onEvent)

        // Float requests span the window rather than sharing the master
        // column: they have nothing to do with the batch in the detail pane,
        // and a table with three actions in half a window scrolls sideways and
        // hides its own status column.
        if (state.destination == CashDestination.ApprovalQueue && state.floatApprovals.isNotEmpty()) {
            ZillitSectionCard(
                title = "Float requests",
                icon = ZillitIcons.Wallet,
                meta = "${state.floatApprovals.size} waiting",
                padded = false,
                modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md),
            ) {
                ZillitDataTable(
                    rows = state.floatApprovals,
                    columns = floatColumns() + floatApprovalActions(state, onEvent),
                    key = { it.id },
                    emptyTitle = "No float requests waiting",
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Column(modifier = Modifier.weight(QUEUE_WEIGHT).fillMaxHeight()) {
                ZillitSectionCard(
                    title = state.destination.label,
                    icon = ZillitIcons.Receipt,
                    meta = "${batches.size} batch${if (batches.size == 1) "" else "es"}",
                    padded = false,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    ZillitDataTable(
                        rows = batches,
                        columns = batchColumns(state.viewer.isAccountant, compact = true),
                        key = { it.id },
                        loading = state.loading,
                        onRowClick = { onEvent(CashEvent.SelectBatch(it.id)) },
                        isSelected = { it.id == state.selectedBatchId },
                        emptyTitle = state.emptyTitle(),
                        emptyMessage = state.emptyMessage(),
                    )
                }
            }

            ZillitSectionCard(
                title = "Batch detail",
                icon = ZillitIcons.Ledger,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
                padded = false,
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = "Pick a batch",
                        message = "Its receipts, coding and history show here.",
                        icon = ZillitIcons.Eye,
                    )
                } else {
                    BatchDetail(state, selected, onEvent)
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(state: CashUiState, count: Int, onEvent: (CashEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(CashEvent.Search(it)) },
            placeholder = "Search by reference or submitter",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitText(
            text = "$count showing",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (state.destination == CashDestination.PostLedger ||
            state.destination == CashDestination.OutOfPocketPost
        ) {
            val limit = state.viewer.metadata.postingLimit
            if (limit != null) {
                ZillitStatusPill(
                    label = "Your posting limit: ${money(limit, null)}",
                    tone = StatusTone.Neutral,
                )
            }
        }
    }
}

/**
 * One batch, in full: what it settles, what is in it, and what can be done.
 *
 * The lifecycle bar sits at the top because "where is this" is the first
 * question asked of any batch, and the answer decides whether the buttons
 * below are even relevant.
 */
@Suppress("LongMethod") // One batch, top to bottom; the order is the reading order.
@Composable
private fun BatchDetail(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val submitter = LocalCashPeople.current.nameOrNull(batch.userId, batch.holderName)
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = batch.reference.ifBlank { "Batch ${batch.id.take(REF_FALLBACK)}" },
                    style = ZillitTheme.typography.titleMedium,
                )
                CashPerson(
                    userId = batch.userId,
                    recordedName = batch.holderName,
                    secondary = "Submitted ${date(batch.createdAt)}",
                    modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
                )
            }
            BatchStatusPill(batch.status, state.viewer.isAccountant)
        }

        LifecycleBar(batch.status)
        ZillitDivider()

        Row(modifier = Modifier.fillMaxWidth()) {
            DetailFigure("Total", money(batch.totalGross, batch.currency), Modifier.weight(1f))
            DetailFigure("Receipts", batch.claimCount.toString(), Modifier.weight(1f))
            DetailFigure(
                label = "Settlement",
                value = Settlement.label(batch.settlementType),
                modifier = Modifier.weight(1f),
            )
        }
        if (batch.reimbursementAmount > 0) {
            ZillitNotice(
                text = "${money(batch.reimbursementAmount, batch.currency)} is owed back to " +
                    (submitter ?: "the submitter") +
                    (batch.paymentMethod?.let { " via $it" } ?: ""),
                tone = StatusTone.Progress,
                icon = ZillitIcons.Bank,
            )
        }
        batch.notes?.takeIf { it.isNotBlank() }?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        if (batch.claims.isNotEmpty()) {
            ZillitDivider()
            ZillitText(text = "Receipts", style = ZillitTheme.typography.titleSmall)
            batch.claims.forEach { claim ->
                ClaimRow(
                    onEvent = onEvent,
                    claim = claim,
                    currency = batch.currency,
                    // Coding is offered where coding happens: a coordinator in
                    // the coding queue, an accountant correcting one in audit.
                    onCode = if (state.canCode()) {
                        { onEvent(CashEvent.OpenCoding(batch.id, claim.id)) }
                    } else {
                        null
                    },
                )
            }
        }

        val actions = actionsFor(state, batch)
        if (actions.isNotEmpty()) {
            ZillitDivider()
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                actions.chunked(ACTIONS_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        row.forEach { action ->
                            ZillitButton(
                                text = action.label,
                                onClick = { onEvent(CashEvent.Ask(action.prompt(batch))) },
                                variant = action.variant,
                                size = ButtonSize.Small,
                                enabled = !state.busy,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(text = value, style = ZillitTheme.typography.titleSmall, maxLines = 1)
    }
}

/** Whether this viewer may open the coding editor from this page. */
private fun CashUiState.canCode(): Boolean = when (destination) {
    CashDestination.CodingQueue -> viewer.isCoordinator
    CashDestination.AuditQueue, CashDestination.PostLedger, CashDestination.OutOfPocketPost ->
        viewer.isAccountant

    else -> false
}

@Suppress("LongMethod") // One receipt line, with its flags and its coding affordance.
@Composable
private fun ClaimRow(
    claim: Claim,
    currency: String?,
    onCode: (() -> Unit)?,
    onEvent: (CashEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = claim.description.ifBlank { "Receipt" },
                style = ZillitTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = money(claim.grossAmount, currency),
                style = ZillitTheme.typography.numeric,
                maxLines = 1,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = listOfNotNull(
                    claim.supplier?.takeIf { it.isNotBlank() },
                    ExpenseCategory.label(claim.category),
                    date(claim.receiptDate).takeIf { it != "—" },
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            claim.costCode?.takeIf { it.isNotBlank() }?.let {
                ZillitStatusPill(label = it, tone = StatusTone.Neutral)
            }
            // An uncoded receipt is named as such rather than left blank —
            // a missing pill reads as "nothing to see", which is the opposite.
            if (claim.costCode.isNullOrBlank() && claim.lineItems.isEmpty()) {
                ZillitStatusPill(label = "Uncoded", tone = StatusTone.Pending)
            }
            if (claim.vatAmount > 0) {
                ZillitStatusPill(
                    label = "VAT ${money(claim.vatAmount, currency)}",
                    tone = StatusTone.Progress,
                )
            }
        }
        // A backend-owned deduction row is called out rather than shown as an
        // ordinary line: it was not entered by anyone, and an accountant
        // hunting a discrepancy needs to know the engine put it there.
        claim.lineItems.filter { it.autoDeduction }.forEach { line ->
            ZillitStatusPill(
                label = "Auto deduction · ${line.description} ${money(line.total, currency)}",
                tone = StatusTone.Escalated,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            claim.receiptUrl?.takeIf { it.isNotBlank() }?.let { receipt ->
                ZillitButton(
                    // Named for what opens: on most productions this is a
                    // photograph, and "view receipt" is what the person
                    // checking the figures is actually after.
                    text = if (claim.receiptIsPdf) "Open receipt (PDF)" else "View receipt",
                    onClick = { onEvent(CashEvent.ViewReceipt(receipt)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            onCode?.let { code ->
                ZillitButton(
                    text = if (claim.lineItems.isEmpty()) "Code this receipt" else "Edit coding",
                    onClick = code,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                )
            }
        }
    }
}

// -- which actions a batch offers --------------------------------------------

private data class BatchAction(
    val label: String,
    val variant: ButtonVariant,
    val prompt: (ClaimBatch) -> CashPrompt,
)

/**
 * The actions this viewer may take on this batch, on this page.
 *
 * Deliberately assembled per destination rather than per status: the same
 * batch is actionable in different ways depending on which queue you reached
 * it through — an accountant in Audit verifies it, the same accountant in the
 * approval queue can only override it.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // A rights table; flattening it is what makes it readable.
private fun actionsFor(state: CashUiState, batch: ClaimBatch): List<BatchAction> {
    val viewer = state.viewer
    val query = BatchAction("Query", ButtonVariant.Tertiary) {
        CashPrompt.WithReason(
            action = ReasonedAction.QueryBatch,
            targetId = it.id,
            title = "Query this batch",
            label = "What needs correcting",
        )
    }
    val reject = BatchAction("Reject", ButtonVariant.Danger) {
        CashPrompt.WithReason(
            action = ReasonedAction.RejectBatch,
            targetId = it.id,
            title = "Reject this batch",
            label = "Why it is being rejected",
        )
    }

    // Handing a batch on is a Post & Ledger action: it is the queue where a
    // senior decides who takes each one, and the batch stays put afterwards —
    // only its owner moves. The word follows the batch, so an unassigned one
    // reads "Assign" and one already owned reads "Reassign".
    val assign = BatchAction(BatchAssignment.actionLabel(batch), ButtonVariant.Secondary) {
        CashPrompt.Assign(
            batchId = it.id,
            title = "${BatchAssignment.actionLabel(it)} batch ${it.reference}".trim(),
            label = BatchAssignment.actionLabel(it),
        )
    }

    return when (state.destination) {
        CashDestination.CodingQueue -> if (viewer.isCoordinator) {
            listOf(
                BatchAction("Submit coding", ButtonVariant.Primary) {
                    CashPrompt.Confirm(
                        ConfirmAction.SaveAndSubmitCoded,
                        it.id,
                        "Submit coding",
                        "The batch moves on to the accounts team.",
                    )
                },
                query,
            )
        } else {
            emptyList()
        }

        CashDestination.AuditQueue -> if (viewer.isAccountant) {
            listOf(
                BatchAction("Verify", ButtonVariant.Primary) {
                    CashPrompt.Confirm(
                        ConfirmAction.SaveAndVerify,
                        it.id,
                        "Verify this batch",
                        "Tax is treated as extracted and the batch moves to approval.",
                    )
                },
                query,
                reject,
            )
        } else {
            emptyList()
        }

        CashDestination.ApprovalQueue, CashDestination.ClaimReview -> buildList {
            if (viewer.isApprover) {
                add(
                    BatchAction("Approve", ButtonVariant.Primary) {
                        CashPrompt.Confirm(
                            ConfirmAction.ApproveBatch,
                            it.id,
                            "Approve this batch",
                            "It becomes available for the accounts team to post.",
                        )
                    },
                )
                add(reject)
            }
            // An accountant who is not an approver sees the queue read-only —
            // unless they hold the override right, which is the whole point of
            // that right existing.
            if (viewer.canOverrideBatch()) {
                add(
                    BatchAction("Override", ButtonVariant.Secondary) {
                        CashPrompt.Confirm(
                            ConfirmAction.OverrideBatch,
                            it.id,
                            "Override the approval chain",
                            "The batch skips its remaining approvers. This is recorded against your name.",
                        )
                    },
                )
            }
        }

        CashDestination.PettyCashSignOff, CashDestination.OutOfPocketSignOff -> if (viewer.canSeeSignOff) {
            listOf(
                assign,
                BatchAction("Sign off & post", ButtonVariant.Primary) {
                    CashPrompt.Confirm(
                        ConfirmAction.PostBatch,
                        it.id,
                        "Post this batch",
                        "${money(it.totalGross, it.currency)} goes to the ledger. This cannot be undone here.",
                    )
                },
                reject,
            )
        } else {
            emptyList()
        }

        CashDestination.PostLedger, CashDestination.OutOfPocketPost -> if (viewer.isAccountant) {
            buildList {
                add(
                    BatchAction("Post to ledger", ButtonVariant.Primary) {
                        CashPrompt.Confirm(
                            ConfirmAction.PostBatch,
                            it.id,
                            "Post this batch",
                            "${money(it.totalGross, it.currency)} goes to the ledger. This cannot be undone here.",
                        )
                    },
                )
                // Above the ceiling the only honest action is to escalate, so
                // that is the one offered.
                if (!viewer.canPost(batch.totalGross)) {
                    add(
                        BatchAction("Escalate", ButtonVariant.Secondary) {
                            CashPrompt.WithReason(
                                action = ReasonedAction.EscalateBatch,
                                targetId = it.id,
                                title = "Escalate for senior sign-off",
                                label = "Why it needs a senior",
                            )
                        },
                    )
                }
                add(query)
            }
        } else {
            emptyList()
        }

        else -> emptyList()
    }
}

@Suppress("LongMethod") // Approve, reject and override, each with its confirmation.
private fun floatApprovalActions(
    state: CashUiState,
    onEvent: (CashEvent) -> Unit,
): List<TableColumn<CashFloat>> = listOf(
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(FLOAT_ACTION_COLUMN),
        cell = { row ->
            val holder = LocalCashPeople.current.nameOrNull(row.userId, row.holderName) ?: "this crew member"
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                if (state.viewer.isApprover) {
                    ZillitButton(
                        text = "Approve",
                        onClick = {
                            onEvent(
                                CashEvent.Ask(
                                    CashPrompt.Confirm(
                                        ConfirmAction.ApproveFloat,
                                        row.id,
                                        "Approve this float",
                                        "${money(row.requestedAmount, row.currency)} for $holder.",
                                    ),
                                ),
                            )
                        },
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = "Reject",
                        onClick = {
                            onEvent(
                                CashEvent.Ask(
                                    CashPrompt.WithReason(
                                        action = ReasonedAction.RejectFloat,
                                        targetId = row.id,
                                        title = "Reject this float request",
                                        label = "Why it is being refused",
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                } else if (state.viewer.canOverrideFloat()) {
                    ZillitButton(
                        text = "Override",
                        onClick = {
                            onEvent(
                                CashEvent.Ask(
                                    CashPrompt.Confirm(
                                        ConfirmAction.OverrideFloat,
                                        row.id,
                                        "Override the approval chain",
                                        "The float skips its approvers. This is recorded against your name.",
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                } else {
                    FloatStatusPill(row.status)
                }
            }
        },
    ),
)

/** Payment routing — where approved reimbursements are going. */
@Composable
fun PaymentRoutingPage(state: CashUiState) {
    val routing = state.paymentRouting
    FixedPage {
        StatRow(
            listOf(
                StatTileSpec(
                    label = "BACS",
                    value = money(routing?.bacs, null),
                    sub = "Bank transfer",
                    tone = StatusTone.Progress,
                    icon = ZillitIcons.Bank,
                ),
                StatTileSpec(
                    label = "Payroll",
                    value = money(routing?.payroll, null),
                    sub = "Added to the next run",
                    tone = StatusTone.Done,
                    icon = ZillitIcons.Users,
                ),
                StatTileSpec(
                    label = "Total",
                    value = money(routing?.total, null),
                    sub = "Approved and unpaid",
                    icon = ZillitIcons.Ledger,
                ),
            ),
        )
        ZillitSectionCard(
            title = "Routed claims",
            icon = ZillitIcons.Bank,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = routing?.batches.orEmpty(),
                columns = batchColumns(accountant = true),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "Nothing waiting to pay",
                emptyMessage = "Approved out-of-pocket claims appear here with the rail they are paid on.",
            )
        }
    }
}

/** By the name on screen, as the web's queues search `getUserName(user_id)` — see [CashPeople]. */
private fun ClaimBatch.matches(query: String, people: CashPeople): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return reference.lowercase().contains(needle) ||
        people.nameOrNull(userId, holderName).orEmpty().lowercase().contains(needle) ||
        notes?.lowercase()?.contains(needle) == true
}

/** Empty states that name the queue, so "nothing here" is never ambiguous. */
private fun CashUiState.emptyTitle(): String = when {
    search.isNotBlank() -> "Nothing matches that search"
    destination == CashDestination.History -> "No posted batches yet"
    else -> "Nothing waiting"
}

private fun CashUiState.emptyMessage(): String? = when {
    search.isNotBlank() -> "Clear the search to see the whole queue."
    destination == CashDestination.CodingQueue -> "Batches appear here when a coordinator needs to code them."
    destination == CashDestination.AuditQueue -> "Coded batches arrive here for tax extraction and verification."
    destination == CashDestination.ApprovalQueue -> "Verified batches and float requests await your decision here."
    destination == CashDestination.History -> "Batches show here once they have been posted to the ledger."
    else -> null
}

private const val QUEUE_WEIGHT = 1.45f
private const val DETAIL_WEIGHT = 1f
private const val REF_FALLBACK = 8
private const val ACTIONS_PER_ROW = 3
private val SEARCH_WIDTH = 320.dp
private val FLOAT_ACTION_COLUMN = 190.dp
