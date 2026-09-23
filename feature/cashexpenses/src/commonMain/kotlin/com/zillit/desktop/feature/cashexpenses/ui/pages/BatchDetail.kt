package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseCategory
import com.zillit.desktop.feature.cashexpenses.domain.Settlement
import com.zillit.desktop.feature.cashexpenses.ui.BatchPanel
import com.zillit.desktop.feature.cashexpenses.ui.BatchStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.LifecycleBar
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.isPostLedger
import com.zillit.desktop.feature.cashexpenses.ui.isSignOff
import com.zillit.desktop.feature.cashexpenses.ui.money

/**
 * One batch, in full: what it settles, what is in it, and what can be done.
 *
 * The receipts are the ones fetched for this batch ([CashUiState.panelClaims]),
 * never only the queue row's. The ledger date and the sign-off notes sit
 * above the actions that send them, and a batch dated inside the locked
 * period keeps only its history and query — as the web removes every
 * mutating button (`PCPostLedgerPage.jsx:1054-1058`).
 */
@Suppress("LongMethod") // One batch, top to bottom; the order is the reading order.
@Composable
internal fun BatchDetail(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val submitter = LocalCashPeople.current.nameOrNull(batch.userId, batch.holderName)
    val panel = state.panel?.takeIf { it.batchId == batch.id }
    val claims = state.panelClaims
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = batch.reference.ifBlank { str(S.desktop_ce_batch_ref, batch.id.take(REF_FALLBACK)) },
                    style = ZillitTheme.typography.titleMedium,
                )
                CashPerson(
                    userId = batch.userId,
                    recordedName = batch.holderName,
                    secondary = str(S.desktop_ce_submitted_on, date(batch.createdAt)),
                    modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
                )
            }
            BatchStatusPill(batch.status, state.viewer.isAccountant)
        }

        LifecycleBar(batch.status)
        AssignmentLine(batch)
        if (state.selectedLocked) {
            ZillitNotice(
                text = str(S.desktop_ce_batch_in_locked_period, state.lockedThrough.orEmpty()),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Lock,
            )
        }
        EscalationNote(state, batch)
        ZillitDivider()

        Row(modifier = Modifier.fillMaxWidth()) {
            DetailFigure(str(S.ah_total_label), money(batch.totalGross, batch.currency), Modifier.weight(1f))
            DetailFigure(str(S.ah_receipts_label), claims.size.takeIf { it > 0 }?.toString()
                ?: batch.claimCount.toString(), Modifier.weight(1f))
            DetailFigure(
                label = str(S.desktop_ce_settlement),
                value = Settlement.label(batch.settlementType),
                modifier = Modifier.weight(1f),
            )
        }
        if (batch.reimbursementAmount > 0) {
            ZillitNotice(
                text = owedBackText(batch, submitter),
                tone = StatusTone.Progress,
                icon = ZillitIcons.Bank,
            )
        }
        batch.notes?.takeIf { it.isNotBlank() }?.let {
            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
        }

        ZillitDivider()
        ClaimsSection(state, batch, panel, claims, onEvent)
        LedgerInputs(state, batch, panel, onEvent)

        val actions = batchActions(state, batch)
        if (actions.isNotEmpty()) {
            ZillitDivider()
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                actions.chunked(ACTIONS_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        row.forEach { action ->
                            ZillitButton(
                                text = action.label,
                                onClick = { onEvent(action.event) },
                                variant = action.variant,
                                size = ButtonSize.Small,
                                enabled = !state.busy && action.enabled,
                            )
                        }
                    }
                }
            }
        }

        if (panel?.historyOpen == true) BatchHistory(panel)
        panel?.query?.let { QueryThreadView(it, onEvent) }
    }
}

/** Who has the batch, and who gave it to them — Post & Ledger's assignment line. */
@Composable
private fun AssignmentLine(batch: ClaimBatch) {
    val assignee = batch.assignedTo?.takeIf { it.isNotBlank() } ?: return
    val people = LocalCashPeople.current
    val by = batch.assignedBy?.takeIf { it.isNotBlank() }
    val detail = when {
        by == SYSTEM -> str(S.desktop_ce_auto_assigned)
        by != null -> str(S.ah_format_by_name, people.nameOf(by)) +
            (batch.assignmentReason?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        else -> null
    }
    ZillitText(
        text = listOfNotNull(str(S.desktop_ce_assigned_to, people.nameOf(assignee)), detail).joinToString(" · "),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
}

/** Sign-off shows who escalated the batch and why, as the web's escalation note does. */
@Composable
private fun EscalationNote(state: CashUiState, batch: ClaimBatch) {
    if (!state.destination.isSignOff) return
    val reason = batch.escalationReason ?: return
    val from = batch.escalatedBy?.let { LocalCashPeople.current.nameOf(it) }
    ZillitNotice(
        text = if (from != null) str(S.desktop_ce_note_from, from, reason) else reason,
        tone = StatusTone.Escalated,
        icon = ZillitIcons.Shield,
    )
}

@Suppress("LongMethod") // The receipts, their selection and their loading states.
@Composable
private fun ClaimsSection(
    state: CashUiState,
    batch: ClaimBatch,
    panel: BatchPanel?,
    claims: List<Claim>,
    onEvent: (CashEvent) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = str(S.ah_receipts_label),
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (panel != null && panel.claims == null && !panel.failed) ZillitSpinner(size = SPINNER)
    }
    if (panel?.failed == true && claims.isEmpty()) {
        ZillitNotice(text = str(S.desktop_ce_receipts_failed), tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
        return
    }
    // A partial approval: the approver ticks the receipts they approve.
    val selecting = state.destination == CashDestination.ApprovalQueue &&
        CashRules.mayApprove(state.viewer, batch) && panel?.claims != null
    val chosen = panel?.selectedClaimIds.orEmpty()
    if (selecting && claims.isNotEmpty()) SelectAllRow(claims, chosen, onEvent)
    claims.forEach { claim ->
        ClaimLine(state, batch, panel, claim, selecting = selecting, chosen = claim.id in chosen, onEvent = onEvent)
    }
}

/** One receipt in the pane, with its tick box on a partial approval and its Verify in audit. */
@Composable
private fun ClaimLine(
    state: CashUiState,
    batch: ClaimBatch,
    panel: BatchPanel?,
    claim: Claim,
    selecting: Boolean,
    chosen: Boolean,
    onEvent: (CashEvent) -> Unit,
) {
    val verifying = state.destination == CashDestination.AuditQueue && state.viewer.isAccountant &&
        !state.selectedLocked
    Row(verticalAlignment = Alignment.Top) {
        if (selecting) {
            ZillitCheckbox(
                checked = chosen,
                onCheckedChange = { onEvent(CashEvent.ToggleClaim(claim.id)) },
                modifier = Modifier.padding(top = ZillitTheme.spacing.xs, end = ZillitTheme.spacing.sm),
            )
        }
        ClaimRow(
            claim = claim,
            currency = batch.currency,
            onEvent = onEvent,
            modifier = Modifier.weight(1f),
            // Coding is offered where coding happens: a coordinator in the
            // coding queue, an accountant correcting one in audit or before
            // posting.
            onCode = if (state.canCode) {
                { onEvent(CashEvent.OpenCoding(batch.id, claim.id)) }
            } else {
                null
            },
            verify = if (verifying) {
                VerifyControl(
                    verified = claim.isVerified,
                    busy = panel?.verifying == claim.id,
                    onToggle = { onEvent(CashEvent.ToggleVerify(claim.id)) },
                )
            } else {
                null
            },
        )
    }
}

/** Select All over a partial approval, and how many are ticked. */
@Composable
private fun SelectAllRow(claims: List<Claim>, chosen: Set<String>, onEvent: (CashEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZillitCheckbox(
            checked = claims.all { it.id in chosen },
            onCheckedChange = { onEvent(CashEvent.SelectAllClaims(it)) },
            label = str(S.select_all),
        )
        ZillitText(
            text = str(S.desktop_ce_selected_of, chosen.size, claims.size),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(start = ZillitTheme.spacing.sm),
        )
    }
}

/** The ledger date and the senior's notes — only where a post takes them. */
@Composable
private fun LedgerInputs(state: CashUiState, batch: ClaimBatch, panel: BatchPanel?, onEvent: (CashEvent) -> Unit) {
    val dated = state.destination.isPostLedger || state.destination.isSignOff
    if (!dated || panel == null || state.selectedLocked) return
    val min = com.zillit.desktop.feature.cashexpenses.domain.CashDates.minimum(state.lockedThrough)
    ZillitDateField(
        value = panel.effectiveDate,
        onValueChange = { onEvent(CashEvent.EditEffectiveDate(it)) },
        label = str(S.ah_lbl_eff_date),
        helperText = if (min != null) {
            str(S.desktop_ce_ledger_date_from, min)
        } else {
            str(S.desktop_ce_ledger_posting_date)
        },
        modifier = Modifier.width(DATE_WIDTH),
    )
    if (state.destination.isSignOff && batch.status != BatchStatus.Posted) {
        ZillitTextField(
            value = panel.seniorNotes,
            onValueChange = { onEvent(CashEvent.EditSeniorNotes(it)) },
            label = str(S.desktop_ce_senior_signoff_notes),
            placeholder = str(S.desktop_ce_signoff_notes_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
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

/** The auditor's per-receipt Verify on one receipt row. */
internal data class VerifyControl(val verified: Boolean, val busy: Boolean, val onToggle: () -> Unit)

@Suppress("LongMethod") // One receipt line, with its flags and its coding affordance.
@Composable
private fun ClaimRow(
    claim: Claim,
    currency: String?,
    onCode: (() -> Unit)?,
    verify: VerifyControl?,
    onEvent: (CashEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = claim.codedDescription?.takeIf { it.isNotBlank() }
                    ?: claim.description.ifBlank { str(S.desktop_receipt) },
                style = ZillitTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitText(text = money(claim.grossAmount, currency), style = ZillitTheme.typography.numeric, maxLines = 1)
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
            claim.costCode?.takeIf { it.isNotBlank() }?.let { ZillitStatusPill(label = it, tone = StatusTone.Neutral) }
            // An uncoded receipt is named as such rather than left blank —
            // a missing pill reads as "nothing to see", which is the opposite.
            if (claim.costCode.isNullOrBlank() && claim.lineItems.isEmpty()) {
                ZillitStatusPill(label = str(S.desktop_uncoded), tone = StatusTone.Pending)
            }
            if (claim.vatAmount > 0) {
                ZillitStatusPill(
                    label = str(S.desktop_ce_vat_amount, money(claim.vatAmount, currency)),
                    tone = StatusTone.Progress,
                )
            }
        }
        // A backend-owned deduction row is called out rather than shown as an
        // ordinary line: it was not entered by anyone.
        claim.lineItems.filter { it.autoDeduction }.forEach { line ->
            ZillitStatusPill(
                label = str(S.desktop_ce_auto_deduction, line.description, money(line.total, currency)),
                tone = StatusTone.Escalated,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            verify?.let { control ->
                ZillitButton(
                    text = if (control.verified) str(S.ah_verified) else str(S.txt_verify),
                    onClick = control.onToggle,
                    variant = if (control.verified) ButtonVariant.Secondary else ButtonVariant.Primary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Check,
                    loading = control.busy,
                )
            }
            claim.receiptUrl?.takeIf { it.isNotBlank() }?.let { receipt ->
                ZillitButton(
                    // Named for what opens: on most productions this is a
                    // photograph, and "view receipt" is what the person
                    // checking the figures is actually after.
                    text = if (claim.receiptIsPdf) {
                        str(S.desktop_card_open_receipt_pdf)
                    } else {
                        str(S.desktop_card_view_receipt)
                    },
                    onClick = { onEvent(CashEvent.ViewReceipt(receipt)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            onCode?.let { code ->
                ZillitButton(
                    text = if (claim.lineItems.isEmpty()) {
                        str(S.desktop_ce_code_this_receipt)
                    } else {
                        str(S.desktop_ce_edit_coding)
                    },
                    onClick = code,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                )
            }
        }
    }
}

/** "£120.00 is owed back to Ada Lovelace via BACS", with the parts that are there. */
private fun owedBackText(batch: ClaimBatch, submitter: String?): String {
    val amount = money(batch.reimbursementAmount, batch.currency)
    val who = submitter ?: str(S.desktop_ce_the_submitter)
    val method = batch.paymentMethod
    return if (method.isNullOrBlank()) {
        str(S.desktop_ce_owed_back_to, amount, who)
    } else {
        str(S.desktop_ce_owed_back_to_via, amount, who, method)
    }
}

private const val SYSTEM = "system"
private const val REF_FALLBACK = 8
private const val ACTIONS_PER_ROW = 3
private val SPINNER = 14.dp
private val DATE_WIDTH = 240.dp
