package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople

/**
 * Senior Sign-off — the web's `PCSeniorPage` + `SeniorBatchItem`, for either
 * pipeline.
 *
 * The queue beside the open batch, as every queue here is laid out; the
 * batch shows the web's Claims & Line Items table, the sign-off notes and
 * then the ledger date, and its two actions. The ledger date starts blank
 * unless the batch carries one — the senior picks it.
 *
 * Approve & Post keeps a confirmation although the web posts on the click:
 * it writes to the ledger and cannot be undone from here.
 */
@Suppress("LongMethod") // Notice, tiles, queue and batch: one page, read together.
@Composable
fun SignOffPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val batches = state.queueBatches
    val escalated = batches.count { it.status == BatchStatus.Escalated }
    val review = batches.count { it.status == BatchStatus.UnderReview }
    val ready = batches.count { it.status == BatchStatus.ReadyToPost }
    val selected = batches.firstOrNull { it.id == state.selectedBatchId }

    FixedPage {
        PcNotice(
            title = str(S.desktop_pc_senior_signoff),
            body = str(S.desktop_pc_senior_signoff_notice),
            tone = NoticeTone.Bad,
            icon = ZillitIcons.Shield,
        )
        StatRow(
            listOf(
                StatTileSpec(
                    label = str(S.desktop_pc_awaiting_signoff),
                    value = batches.size.toString(),
                    sub = str(S.desktop_pc_signoff_breakdown, escalated, review, ready),
                    tone = StatusTone.Pending,
                ),
                StatTileSpec(
                    label = str(S.ah_total_value),
                    value = state.describeTotal(batches.map { it.currency to it.totalGross }),
                    sub = batchesLabel(batches.size),
                ),
                StatTileSpec(
                    label = str(S.ah_escalated),
                    value = escalated.toString(),
                    sub = str(S.desktop_pc_requires_attention),
                    tone = StatusTone.Escalated,
                ),
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Column(
                modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitText(text = str(S.desktop_pc_signoff_queue), style = ZillitTheme.typography.titleSmall)
                when {
                    state.loading && batches.isEmpty() -> Box(
                        modifier = Modifier.fillMaxWidth().padding(EMPTY_PADDING),
                        contentAlignment = Alignment.Center,
                    ) { ZillitSpinner(size = SPINNER) }

                    batches.isEmpty() -> PcCard(modifier = Modifier.fillMaxWidth()) {
                        ZillitText(
                            text = str(S.desktop_pc_no_batches_signoff),
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(EMPTY_PADDING),
                        )
                    }

                    else -> PcCard(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        ZillitScrollColumn(modifier = Modifier.fillMaxWidth()) {
                            batches.forEachIndexed { index, batch ->
                                if (index > 0) ZillitDivider()
                                SignOffRow(state, batch, batch.id == state.selectedBatchId, onEvent)
                            }
                        }
                    }
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
                    SignOffPane(state, selected, onEvent)
                }
            }
        }
    }
}

@Composable
private fun SignOffRow(state: CashUiState, batch: ClaimBatch, selected: Boolean, onEvent: (CashEvent) -> Unit) {
    val (label, tone) = signOffStatus(batch.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) ZillitTheme.colors.surfaceSelected else ZillitTheme.colors.surface)
            .clickable { onEvent(CashEvent.SelectBatch(batch.id)) }
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            CashPerson(userId = batch.userId, recordedName = batch.holderName)
            RoleLine(state, batch.userId, batch.departmentId)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = "#${batch.reference} · ${EpochDate.dateTime(batch.createdAt)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
                ZillitBadge(count = state.unreadOnPage(batch.id))
            }
            batch.notes?.takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    text = "“$it”",
                    style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitStatusPill(label = label, tone = tone)
            ZillitText(
                text = state.formatMoney(batch.totalGross, batch.currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = ZillitTheme.colors.accentText,
            )
            ZillitText(
                text = receiptsLabel(batch.claimCount),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** The open batch — `SeniorBatchItem`'s expanded body (`SeniorBatchItem.jsx:99-221`). */
@Suppress("LongMethod") // The batch top to bottom, in the web's reading order.
@Composable
private fun SignOffPane(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val panel = state.panel?.takeIf { it.batchId == batch.id }
    val locked = state.selectedLocked
    val (label, tone) = signOffStatus(batch.status)
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = "#${batch.reference}", style = ZillitTheme.typography.titleMedium)
                CashPerson(
                    userId = batch.userId,
                    recordedName = batch.holderName,
                    secondary = EpochDate.dateTime(batch.createdAt),
                    modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
                )
            }
            ZillitStatusPill(label = label, tone = tone)
        }
        EscalationNote(batch)
        LineItemsTable(state, batch, panel?.claims, failed = panel?.failed == true)

        if (locked) {
            ZillitNotice(
                text = str(S.desktop_ce_batch_in_locked_period, state.lockedThrough.orEmpty()),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Lock,
            )
            return@ZillitScrollColumn
        }
        if (panel != null) {
            ZillitTextField(
                value = panel.seniorNotes,
                onValueChange = { onEvent(CashEvent.EditSeniorNotes(it)) },
                label = str(S.desktop_pc_signoff_notes_label),
                placeholder = str(S.desktop_ce_signoff_notes_placeholder),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().height(NOTES_HEIGHT),
            )
            val min = CashDates.minimum(state.lockedThrough)
            ZillitDateField(
                value = panel.effectiveDate,
                onValueChange = { onEvent(CashEvent.EditEffectiveDate(it)) },
                label = str(S.desktop_pc_effective_date_required),
                helperText = if (min != null) {
                    str(S.desktop_ce_ledger_date_from, min)
                } else {
                    str(S.desktop_ce_ledger_posting_date)
                },
                modifier = Modifier.width(DATE_WIDTH),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (CashRules.canReturnToAccounts(batch)) {
                ZillitButton(
                    text = str(S.desktop_pc_return_to_accounts_long),
                    onClick = {
                        onEvent(CashEvent.ActNow(CashPrompt.Confirm(ConfirmAction.ReturnToAccounts, batch.id, "", "")))
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.desktop_pc_approve_and_post),
                onClick = {
                    onEvent(
                        CashEvent.Ask(
                            CashPrompt.Confirm(
                                ConfirmAction.PostBatch,
                                batch.id,
                                str(S.desktop_ce_post_this_batch),
                                str(
                                    S.desktop_card_goes_to_ledger_undone,
                                    state.formatMoney(batch.totalGross, batch.currency),
                                ),
                            ),
                        ),
                    )
                },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    }
}

/** Who escalated it, why, and when (`SeniorBatchItem.jsx:101-112`). */
@Composable
private fun EscalationNote(batch: ClaimBatch) {
    if (batch.status != BatchStatus.Escalated) return
    val reason = batch.escalationReason?.takeIf { it.isNotBlank() }
    val by = batch.escalatedBy?.takeIf { it.isNotBlank() }
    if (reason == null && by == null) return
    val people = LocalCashPeople.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.accentSoft, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        by?.let {
            ZillitText(
                text = str(S.desktop_pc_note_from, people.nameOf(it)),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        reason?.let {
            ZillitText(
                text = "“$it”",
                style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = ZillitTheme.colors.textSecondary,
            )
        }
        batch.escalatedAt?.let {
            ZillitText(
                text = EpochDate.dateTime(it),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * Claims & Line Items: each receipt's coded lines — description, cost code,
 * price, amount — with the batch total under them. The consolidated tax line
 * is a posting artefact and is left out, as the web leaves it
 * (`SeniorBatchItem.jsx:118-163`).
 */
@Suppress("LongMethod") // Header, rows and the total, as one table.
@Composable
private fun LineItemsTable(state: CashUiState, batch: ClaimBatch, claims: List<Claim>?, failed: Boolean) {
    ZillitText(
        text = str(S.desktop_pc_claims_line_items).uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
    )
    if (claims == null && !failed) {
        ZillitSpinner(size = SPINNER)
        return
    }
    if (claims == null) {
        ZillitNotice(text = str(S.desktop_ce_receipts_failed), tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
        return
    }
    val money = { amount: Double -> state.formatMoney(amount, batch.currency) }
    PcCard(modifier = Modifier.fillMaxWidth()) {
        TableRow(
            cells = listOf(
                str(S.description),
                str(S.desktop_pc_col_cost_code),
                str(S.price),
                str(S.amount),
            ).map { it.uppercase() },
            header = true,
        )
        claims.forEach { claim ->
            val lines = claim.lineItems.filterNot { it.isTax }
            if (lines.isEmpty()) {
                ZillitDivider()
                TableRow(
                    cells = listOf(
                        claim.description.ifBlank { "—" },
                        claim.costCode?.takeIf { it.isNotBlank() } ?: "—",
                        money(claim.grossAmount),
                        money(claim.grossAmount),
                    ),
                )
            } else {
                lines.forEachIndexed { index, line ->
                    ZillitDivider()
                    TableRow(
                        cells = listOf(
                            line.description.ifBlank { "—" },
                            line.account?.takeIf { it.isNotBlank() } ?: "—",
                            money(line.unitPrice),
                            money(line.total),
                        ),
                        caption = claim.description.takeIf { index == 0 && it.isNotBlank() },
                    )
                }
            }
        }
        ZillitDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = str(S.asset_total),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = money(batch.totalGross),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = ZillitTheme.colors.accentText,
            )
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false, caption: String? = null) {
    val style = if (header) ZillitTheme.typography.labelSmall else ZillitTheme.typography.bodySmall
    val color = if (header) ZillitTheme.colors.textMuted else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (header) Modifier.background(ZillitTheme.colors.surfaceSunken) else Modifier)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(DESCRIPTION_WEIGHT)) {
            caption?.let {
                ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
            }
            ZillitText(text = cells[0], style = style, color = color)
        }
        cells.drop(1).forEach { cell ->
            ZillitText(
                text = cell,
                style = style,
                color = color,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(FIGURE_WEIGHT),
            )
        }
    }
}

/** Escalated, Under Review or Ready to Post — the web's three sign-off labels. */
private fun signOffStatus(status: BatchStatus): Pair<String, StatusTone> = when (status) {
    BatchStatus.Escalated -> str(S.ah_escalated) to StatusTone.Escalated
    BatchStatus.UnderReview -> str(S.ah_under_review) to StatusTone.Progress
    else -> str(S.ah_ready_to_post) to StatusTone.Ready
}

private fun batchesLabel(count: Int): String =
    if (count == 1) str(S.desktop_ce_batch_count_one, count) else str(S.desktop_ce_batch_count_other, count)

private const val LIST_WEIGHT = 1.2f
private const val DETAIL_WEIGHT = 1f
private const val DESCRIPTION_WEIGHT = 1.75f
private const val FIGURE_WEIGHT = 1f
private val EMPTY_PADDING = 40.dp
private val SPINNER = 16.dp
private val NOTES_HEIGHT = 112.dp
private val DATE_WIDTH = 240.dp
