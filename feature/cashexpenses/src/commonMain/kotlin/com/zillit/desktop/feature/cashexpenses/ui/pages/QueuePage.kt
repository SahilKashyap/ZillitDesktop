package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashPeople
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.isPostLedger
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
 * The detail — receipts, inputs and actions — is [BatchDetail].
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
                title = str(S.desktop_ce_float_requests),
                icon = ZillitIcons.Wallet,
                meta = str(S.desktop_ce_waiting_count, state.floatApprovals.size),
                padded = false,
                modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md),
            ) {
                ZillitDataTable(
                    rows = state.floatApprovals,
                    columns = floatRequestColumns() + floatApprovalActions(state, onEvent),
                    key = { it.id },
                    emptyTitle = str(S.desktop_ce_no_float_requests),
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
                    meta = if (batches.size == 1) {
                        str(S.desktop_ce_batch_count_one, batches.size)
                    } else {
                        str(S.desktop_ce_batch_count_other, batches.size)
                    },
                    padded = false,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    ZillitDataTable(
                        rows = batches,
                        columns = batchColumns(state.viewer.isAccountant, compact = true) + accessColumn(state),
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
                title = str(S.desktop_ce_batch_detail),
                icon = ZillitIcons.Ledger,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
                padded = false,
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = str(S.desktop_ce_pick_a_batch),
                        message = str(S.desktop_ce_pick_batch_hint),
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
            placeholder = str(S.desktop_ce_search_batches),
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitText(
            text = str(S.desktop_card_showing_count, count),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (state.destination.isPostLedger) {
            val limit = state.viewer.metadata.postingLimit
            if (limit != null && !state.viewer.isSenior) {
                ZillitStatusPill(
                    label = str(S.desktop_ce_your_posting_limit, money(limit, null)),
                    tone = StatusTone.Neutral,
                )
            }
        }
        if (state.destination == CashDestination.History && state.viewer.isAccountant) {
            ExportButton(
                registers = listOf(com.zillit.desktop.feature.cashexpenses.ui.ExportRegister.History),
                busy = state.exporting,
                onEvent = onEvent,
            )
        }
    }
}

/**
 * Post & Ledger's lock: a row assigned to someone else is theirs and a senior's.
 *
 * Drawn on the row, as the web's lock icon is, and refused by the view model
 * if it is opened anyway.
 */
private fun accessColumn(state: CashUiState): List<TableColumn<ClaimBatch>> {
    if (!state.destination.isPostLedger) return emptyList()
    return listOf(
        TableColumn(
            header = "",
            width = ColumnWidth.Fixed(LOCK_COLUMN),
            cell = { row ->
                if (!CashRules.canOpenPostRow(state.viewer, row)) {
                    ZillitIcon(
                        icon = ZillitIcons.Lock,
                        tint = ZillitTheme.colors.textMuted,
                        size = LOCK_ICON,
                    )
                }
            },
        ),
    )
}

/** Float requests waiting on a signature: what was asked for, not a balance that does not exist yet. */
@Suppress("MagicNumber") // Column proportions; naming each would not clarify them.
internal fun floatRequestColumns(): List<TableColumn<com.zillit.desktop.feature.cashexpenses.domain.CashFloat>> =
    listOf(
        com.zillit.desktop.feature.cashexpenses.ui.personColumn(
            str(S.ah_holder),
            ColumnWidth.Weight(1.6f),
            userId = { it.userId },
        ) { it.holderName },
        textColumn(str(S.desktop_reference), ColumnWidth.Weight(1f), muted = true) {
            it.requestNumber.ifBlank { "—" }
        },
        textColumn(str(S.av_chip_requested), ColumnWidth.Weight(1f), numeric = true) {
            money(it.requestedAmount, it.currency)
        },
        textColumn(str(S.txt_submitted), ColumnWidth.Weight(1f), muted = true) {
            com.zillit.desktop.feature.cashexpenses.ui.date(it.createdAt)
        },
    )

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
    search.isNotBlank() -> str(S.dm_nda_empty_search)
    destination == CashDestination.History -> str(S.desktop_ce_no_posted_batches)
    else -> str(S.desktop_nothing_waiting)
}

private fun CashUiState.emptyMessage(): String? = when {
    search.isNotBlank() -> str(S.desktop_card_clear_search_hint)
    destination == CashDestination.CodingQueue -> str(S.desktop_ce_coding_queue_empty)
    destination == CashDestination.AuditQueue -> str(S.desktop_ce_audit_queue_empty)
    destination == CashDestination.ApprovalQueue -> str(S.desktop_ce_approval_queue_empty)
    destination == CashDestination.History -> str(S.desktop_ce_history_empty)
    else -> null
}

private const val QUEUE_WEIGHT = 1.45f
private const val DETAIL_WEIGHT = 1f
private val SEARCH_WIDTH = 320.dp
private val LOCK_COLUMN = 40.dp
private val LOCK_ICON = 14.dp
