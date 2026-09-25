@file:Suppress("MatchingDeclarationName") // The page; the filter enum is only its state.

package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchEdits
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashPeople
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ExportRegister
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.isPostLedger
import com.zillit.desktop.feature.cashexpenses.ui.locksToAssignee
import com.zillit.desktop.feature.cashexpenses.ui.rememberFace
import com.zillit.desktop.feature.cashexpenses.ui.tone
import kotlin.time.Clock

/** The web's quick filters on the Audit, History and Coding queues. */
internal enum class BatchTypeFilter { All, PettyCash, OutOfPocket }

/**
 * Post & Ledger, the Audit Queue, History and the Coding Queue — the web's
 * `PCPostLedgerPage`, `PCAuditPage`, `PCHistoryPage` and `PCCoordPage` list
 * views, with the batch they open ([BatchWorkDetail]) beside the list rather
 * than over it (the master–detail layout the desktop keeps on purpose).
 */
@Suppress("LongMethod") // Notice, tiles, filters, list and detail: one page, read together.
@Composable
internal fun BatchWorkPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val people = LocalCashPeople.current
    val destination = state.destination
    var typeFilter by remember(destination) { mutableStateOf(BatchTypeFilter.All) }
    val filtered = state.queueBatches.filter { it.matches(typeFilter) && it.matches(state.search, people) }
    val selected = filtered.firstOrNull { it.id == state.selectedBatchId }

    FixedPage {
        PageNotice(state, onEvent)
        PageTiles(state)
        if (destination != CashDestination.PostLedger && destination != CashDestination.OutOfPocketPost) {
            FilterBar(
                filter = typeFilter,
                onFilter = { typeFilter = it },
                search = state.search,
                onSearch = { onEvent(CashEvent.Search(it)) },
                placeholder = if (destination == CashDestination.History) {
                    str(S.dm_picker_search_hint)
                } else {
                    str(S.desktop_pc_search_batches)
                },
                trailing = if (destination == CashDestination.CodingQueue) {
                    {
                        ZillitStatusPill(
                            label = str(S.desktop_card_pending_count, state.queueBatches.size),
                            tone = StatusTone.Pending,
                        )
                    }
                } else {
                    null
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(padded = false, modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight()) {
                when {
                    state.loading && state.queueBatches.isEmpty() -> Box(
                        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xxl),
                        contentAlignment = Alignment.Center,
                    ) { ZillitSpinner() }

                    filtered.isEmpty() -> ZillitText(
                        text = if (state.queueBatches.isEmpty()) {
                            state.emptyText()
                        } else {
                            str(S.desktop_pc_no_batches_match)
                        },
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textMuted,
                        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xxl),
                    )

                    else -> ZillitScrollColumn(modifier = Modifier.fillMaxSize()) {
                        filtered.forEachIndexed { index, batch ->
                            BatchRow(state, batch, batch.id == state.selectedBatchId, onEvent)
                            if (index < filtered.lastIndex) ZillitDivider()
                        }
                    }
                }
            }

            ZillitSectionCard(padded = false, modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight()) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = str(S.desktop_ce_pick_a_batch),
                        message = str(S.desktop_ce_pick_batch_hint),
                        icon = ZillitIcons.Eye,
                    )
                } else {
                    BatchWorkDetail(state, selected, onEvent)
                }
            }
        }
    }
}

/** The notice over each page, with the web's copy (History carries its Export). */
@Composable
private fun PageNotice(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val me = LocalCashPeople.current.nameOf(state.viewer.userId)
    when (state.destination) {
        CashDestination.AuditQueue -> TitledNotice(
            title = str(S.desktop_ce_audit_queue),
            body = str(S.desktop_pc_audit_notice),
            icon = ZillitIcons.Info,
        )

        CashDestination.History -> TitledNotice(
            title = str(S.desktop_pc_history_title, me),
            body = str(S.desktop_pc_history_notice),
            icon = ZillitIcons.Clock,
            action = if (state.viewer.isAccountant) {
                { ExportButton(listOf(ExportRegister.History), state.exporting, onEvent) }
            } else {
                null
            },
        )

        CashDestination.CodingQueue -> TitledNotice(
            title = str(S.desktop_pc_coding_title, me),
            body = str(S.desktop_pc_coding_notice),
            icon = ZillitIcons.Tag,
            tone = StatusTone.Escalated,
        )

        else -> TitledNotice(
            title = if (state.destination.expenseType == ExpenseType.OutOfPocket) {
                str(S.desktop_pc_oop_post_ledger)
            } else {
                str(S.desktop_ce_post_and_ledger)
            },
            body = str(S.desktop_pc_post_ledger_notice),
            icon = ZillitIcons.Ledger,
        )
    }
}

/** Post & Ledger's three tiles and History's four (`PCPostLedgerPage.jsx:1349-1353`, `PCHistoryPage.jsx:164-169`). */
@Suppress("LongMethod") // Two pages' tiles, as the web lays them.
@Composable
private fun PageTiles(state: CashUiState) {
    val rows = state.queueBatches
    val loading = state.loading && rows.isEmpty()
    fun figure(value: String) = if (loading) "—" else value
    when {
        state.destination.isPostLedger -> Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            val ready = rows.count { it.status == BatchStatus.ReadyToPost || it.status == BatchStatus.AcctOverride }
            ZillitStatTile(
                label = str(S.desktop_pc_ready_to_post),
                value = figure(ready.toString()),
                sub = str(S.desktop_pc_batches_queued),
                tone = StatusTone.Done,
                icon = ZillitIcons.Ledger,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_pc_total_to_post),
                value = figure(state.formatAggregate(rows.sumOf { it.totalGross })),
                sub = str(S.desktop_pc_to_gl_next_post),
                icon = ZillitIcons.Wallet,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_pc_under_review),
                value = figure(rows.count { it.status == BatchStatus.UnderReview }.toString()),
                sub = str(S.desktop_ce_senior_review),
                tone = StatusTone.Progress,
                icon = ZillitIcons.Clock,
                modifier = Modifier.weight(1f),
            )
        }

        state.destination == CashDestination.History -> Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            val petty = rows.filter { it.expenseType != ExpenseType.OutOfPocket }
            val oop = rows.filter { it.expenseType == ExpenseType.OutOfPocket }
            ZillitStatTile(
                label = str(S.desktop_pc_posted_this_period),
                value = figure(rows.size.toString()),
                sub = str(S.desktop_pc_all_batches),
                tone = StatusTone.Done,
                icon = ZillitIcons.Check,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_pc_total_posted),
                value = figure(state.formatAggregate(rows.sumOf { it.totalGross })),
                sub = str(S.desktop_pc_to_general_ledger),
                icon = ZillitIcons.Ledger,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_pc_petty_cash_lower),
                value = figure(state.formatAggregate(petty.sumOf { it.totalGross })),
                sub = batchCount(petty.size),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Wallet,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_pc_out_of_pocket_lower),
                value = figure(state.formatAggregate(oop.sumOf { it.totalGross })),
                sub = batchCount(oop.size),
                tone = StatusTone.Progress,
                icon = ZillitIcons.Receipt,
                modifier = Modifier.weight(1f),
            )
        }

        else -> Unit
    }
}

@Composable
private fun FilterBar(
    filter: BatchTypeFilter,
    onFilter: (BatchTypeFilter) -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    placeholder: String,
    trailing: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        BatchTypeFilter.entries.forEach { option ->
            ZillitChoiceChip(
                label = when (option) {
                    BatchTypeFilter.All -> str(S.all)
                    BatchTypeFilter.PettyCash -> str(S.desktop_petty_cash)
                    BatchTypeFilter.OutOfPocket -> str(S.desktop_ce_out_of_pocket)
                },
                selected = filter == option,
                onClick = { onFilter(option) },
            )
        }
        Box(Modifier.weight(1f))
        trailing?.invoke()
        ZillitSearchField(
            value = search,
            onValueChange = onSearch,
            placeholder = placeholder,
            modifier = Modifier.width(SEARCH_WIDTH),
        )
    }
}

/**
 * One queue row, as the web draws it: the submitter's face and name, the
 * pipeline pill and unread count, "Designation · Department", the reference
 * line, the amount over its status, and Review / Open — or the lock for a row
 * that is someone else's.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One row; the four pages differ only in its details.
@Composable
private fun BatchRow(state: CashUiState, batch: ClaimBatch, selected: Boolean, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val people = LocalCashPeople.current
    val destination = state.destination
    val access = !destination.locksToAssignee || CashRules.canOpenPostRow(state.viewer, batch)
    val name = people.nameOf(batch.userId, batch.holderName)
    val oop = batch.expenseType == ExpenseType.OutOfPocket
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surfaceSelected else colors.surface)
            .then(if (access) Modifier.clickable { onEvent(CashEvent.SelectBatch(batch.id)) } else Modifier)
            .alpha(if (access) 1f else LOCKED_ALPHA)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = name, image = rememberFace(batch.userId), userId = batch.userId, size = AVATAR)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = name,
                    style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (destination != CashDestination.CodingQueue) {
                    ZillitStatusPill(
                        label = if (oop) OOP else PC,
                        tone = if (oop) StatusTone.Progress else StatusTone.Pending,
                    )
                }
                UnreadBadge(state.unreadOnPage(batch.id))
            }
            personLine(people, state, batch)?.let {
                ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
            }
            ZillitText(
                text = referenceLine(batch, destination),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (destination == CashDestination.CodingQueue) {
                batch.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    ZillitText(
                        text = "“$notes”",
                        style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = state.formatMoney(batch.totalGross, batch.currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
            )
            val (label, tone) = rowStatus(state, batch, people)
            ZillitStatusPill(label = label, tone = tone, dot = true)
        }
        if (access) {
            ZillitButton(
                text = if (destination.isPostLedger) str(S.av_review) else str(S.recce_open),
                onClick = { onEvent(CashEvent.SelectBatch(batch.id)) },
                variant = if (destination == CashDestination.AuditQueue) {
                    ButtonVariant.Primary
                } else {
                    ButtonVariant.Secondary
                },
                size = ButtonSize.Small,
                trailingIcon = if (destination.isPostLedger) null else ZillitIcons.ArrowRight,
            )
        } else {
            ZillitIcon(ZillitIcons.Lock, tint = colors.textMuted, size = 15.dp)
        }
    }
}

/** "Designation · Department", with whichever parts are known. */
private fun personLine(people: CashPeople, state: CashUiState, batch: ClaimBatch): String? =
    listOfNotNull(
        people.designationOf(batch.userId)?.localised(),
        state.departmentName(batch.departmentId),
    ).joinToString(" · ").ifBlank { null }

/** "#REF · date · N receipts", and on the audit queue how long it has waited. */
private fun referenceLine(batch: ClaimBatch, destination: CashDestination): String {
    val receipts = if (batch.claimCount == 1) {
        str(S.desktop_card_receipt_count_one, batch.claimCount)
    } else {
        str(S.desktop_card_receipt_count_other, batch.claimCount)
    }
    val age = if (destination == CashDestination.AuditQueue) {
        BatchEdits.ageInQueue(batch.createdAt, Clock.System.now().toEpochMilliseconds())
            ?.let { str(S.desktop_pc_in_queue, it) }
    } else {
        null
    }
    return listOfNotNull("#${batch.reference}", date(batch.createdAt), receipts, age).joinToString(" · ")
}

/** The row's status pill, per page (`STATUS_MAP`, "In audit · …", "Posted", `STATUS_BADGE`). */
private fun rowStatus(state: CashUiState, batch: ClaimBatch, people: CashPeople): Pair<String, StatusTone> =
    when (state.destination) {
        CashDestination.AuditQueue -> str(
            S.desktop_pc_in_audit_with,
            batch.assignedTo?.takeIf { it.isNotBlank() }?.let { people.nameOf(it) } ?: str(S.unassigned),
        ) to StatusTone.Progress

        CashDestination.History -> str(S.ah_step_posted) to StatusTone.Done
        CashDestination.CodingQueue -> if (batch.status == BatchStatus.Coding) {
            str(S.desktop_card_coding) to StatusTone.Escalated
        } else {
            str(S.desktop_pc_needs_coding) to StatusTone.Pending
        }

        else -> when (batch.status) {
            BatchStatus.ReadyToPost -> str(S.desktop_pc_ready_to_post) to StatusTone.Done
            BatchStatus.AcctOverride -> str(S.dm_nom_table_override) to StatusTone.Pending
            BatchStatus.UnderReview -> str(S.desktop_pc_under_review) to StatusTone.Progress
            else -> batch.status.label(state.viewer.isAccountant) to batch.status.tone
        }
    }

private fun batchCount(count: Int): String =
    if (count == 1) str(S.desktop_ce_batch_count_one, count) else str(S.desktop_ce_batch_count_other, count)

private fun CashUiState.emptyText(): String = when (destination) {
    CashDestination.AuditQueue -> str(S.desktop_pc_no_batches_audit)
    CashDestination.History -> str(S.desktop_pc_no_posted_batches)
    CashDestination.CodingQueue -> str(S.desktop_pc_no_batches_coding)
    else -> str(S.desktop_pc_no_batches_ready)
}

private fun ClaimBatch.matches(filter: BatchTypeFilter): Boolean = when (filter) {
    BatchTypeFilter.All -> true
    BatchTypeFilter.PettyCash -> expenseType != ExpenseType.OutOfPocket
    BatchTypeFilter.OutOfPocket -> expenseType == ExpenseType.OutOfPocket
}

/** The web's search: the submitter's name and the reference. */
private fun ClaimBatch.matches(query: String, people: CashPeople): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return "${people.nameOf(userId, holderName)} $reference".lowercase().contains(needle)
}

private const val PC = "PC"
private const val OOP = "OOP"
private const val LIST_WEIGHT = 1f
private const val DETAIL_WEIGHT = 1.7f
private const val LOCKED_ALPHA = 0.6f
private val AVATAR = 36.dp
private val SEARCH_WIDTH = 240.dp
