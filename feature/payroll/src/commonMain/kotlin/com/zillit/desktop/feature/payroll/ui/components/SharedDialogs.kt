package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.ui.AdjustmentDialog
import com.zillit.desktop.feature.payroll.ui.AdjustmentKind
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState

/**
 * Override Approval — the web's `OverrideTimecardModal`: force-approves the
 * week past every approval tier, with an optional reason the server records.
 */
@Composable
fun OverrideDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val prompt = state.override
    val shown = remember(prompt != null) { prompt } ?: prompt
    ZillitDialogShell(
        title = str(S.desktop_payroll_override_approval),
        subtitle = shown?.let { listOf(it.name, it.weekLabel).filter(String::isNotBlank).joinToString(" · ") },
        icon = ZillitIcons.Shield,
        visible = prompt != null,
        width = NARROW_DIALOG,
        onDismiss = { onEvent(PayrollEvent.DismissOverride) },
    ) {
        val open = prompt ?: shown ?: return@ZillitDialogShell
        ZillitText(
            text = str(S.desktop_payroll_override_explainer),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = open.reason,
            onValueChange = { onEvent(PayrollEvent.EditOverrideReason(it)) },
            label = str(S.av_reason_optional),
            placeholder = str(S.desktop_payroll_override_reason_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        DialogActions(
            cancel = { onEvent(PayrollEvent.DismissOverride) },
            confirmText = str(if (open.saving) S.desktop_payroll_overriding else S.desktop_payroll_override_approval),
            confirm = { onEvent(PayrollEvent.ConfirmOverride) },
            busy = open.saving,
        )
    }
}

/**
 * Claims or deductions on one timecard — the web's `AddClaimsModal` and
 * `AddDeductionModal`. Each add or remove saves at once; a locked, paid,
 * posted or rejected week is read-only, and the server's "locked" refusal
 * offers the payroll accountant the unlock.
 */
@Suppress("LongMethod") // One dialog: banner, what is on the week, what could be, the manual line.
@Composable
fun AdjustmentsDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val dialog = state.adjustment
    val shown = remember(dialog != null) { dialog } ?: dialog
    val claims = shown?.kind == AdjustmentKind.Claims
    ZillitDialogShell(
        title = shown?.let {
            str(
                if (claims) S.desktop_payroll_claims_for else S.desktop_payroll_deductions_for,
                state.nameOf(it.timecard.userId),
            )
        }.orEmpty(),
        subtitle = shown?.let { open ->
            val week = open.timecard.weekStarting?.let(PayPeriod::compactRangeLabel).orEmpty()
            listOf(week, open.timecard.status.label.uppercase()).filter(String::isNotBlank).joinToString(" · ")
        },
        icon = if (claims) ZillitIcons.Receipt else ZillitIcons.Minus,
        visible = dialog != null,
        width = WIDE_DIALOG,
        onDismiss = { onEvent(PayrollEvent.DismissAdjustments) },
    ) {
        val open = dialog ?: shown ?: return@ZillitDialogShell
        AdjustmentBanners(state, open, onEvent)
        if (claims) AttachedClaims(open, onEvent) else AttachedDeductions(open, onEvent)
        if (claims) PendingBatches(open, onEvent)
        if (!open.readOnly) ManualLine(open, onEvent)
        ZillitDivider()
        AdjustmentTotal(open)
    }
}

/** What the dialog is for; the read-only notice; the last refusal, with the unlock where it applies. */
@Composable
private fun AdjustmentBanners(state: PayrollUiState, open: AdjustmentDialog, onEvent: (PayrollEvent) -> Unit) {
    val claims = open.kind == AdjustmentKind.Claims
    ZillitText(
        text = str(if (claims) S.desktop_payroll_claims_subtitle else S.desktop_payroll_deductions_subtitle),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    if (open.readOnly) {
        ZillitNotice(
            text = str(if (claims) S.desktop_payroll_claims_locked else S.desktop_payroll_deductions_locked),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Lock,
        )
    }
    val error = open.error ?: return
    val unlock: (@Composable () -> Unit)? = if (open.lockedRefusal && state.viewer.isPayrollAccountant) {
        {
            ZillitButton(
                text = str(S.desktop_payroll_unlock_week),
                onClick = { onEvent(PayrollEvent.UnlockAdjusted) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    } else {
        null
    }
    ZillitNotice(text = error, tone = StatusTone.Rejected, icon = ZillitIcons.Warning, action = unlock)
}

/** The running total of what is on the week. */
@Composable
private fun AdjustmentTotal(open: AdjustmentDialog) {
    val claims = open.kind == AdjustmentKind.Claims
    val total = if (claims) open.timecard.claimsTotal else open.timecard.deductionsTotal
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = str(if (claims) S.desktop_payroll_running_total else S.desktop_payroll_total_deductions_label),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = (if (claims) "" else "−") + Money.format(total, open.timecard.currency),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun AttachedClaims(open: AdjustmentDialog, onEvent: (PayrollEvent) -> Unit) {
    SectionHead(str(S.desktop_payroll_on_this_payroll))
    if (open.timecard.claims.isEmpty()) {
        Muted(if (open.loading) str(S.desktop_payroll_loading_claims) else str(S.desktop_payroll_nothing_attached))
    }
    open.timecard.claims.forEach { claim ->
        LineRow(
            title = claim.name,
            sub = claim.cashExpenseBatchId?.let { str(S.desktop_payroll_cash_expense_batch, it) }
                ?: str(S.desktop_payroll_manual_line),
            tag = str(
                if (claim.cashExpenseBatchId != null) S.desktop_payroll_tag_cash else S.desktop_payroll_tag_manual,
            ),
            amount = Money.format(claim.amount, claim.currency ?: open.timecard.currency),
            action = if (open.readOnly) null else str(S.desktop_payroll_remove),
            busy = open.busyKey == (claim.id ?: claim.cashExpenseBatchId),
            onAction = { onEvent(PayrollEvent.RemoveClaim(claim)) },
        )
    }
}

@Composable
private fun PendingBatches(open: AdjustmentDialog, onEvent: (PayrollEvent) -> Unit) {
    SectionHead(str(S.desktop_payroll_available_to_add))
    if (open.pending.isEmpty() && !open.loading) Muted(str(S.desktop_payroll_no_pending_claims))
    open.pending.forEach { batch ->
        LineRow(
            title = batch.reference ?: batch.id,
            sub = str(S.desktop_ce_receipts_count, batch.claimCount) +
                (batch.postedAt?.let { " · ${PayPeriod.dayMonth(it)}" } ?: ""),
            tag = str(if (batch.isCash) S.desktop_payroll_tag_cash else S.desktop_payroll_tag_oop),
            amount = Money.format(batch.amount, batch.currency ?: open.timecard.currency),
            action = str(S.desktop_payroll_add_short),
            busy = open.busyKey == batch.id,
            onAction = { onEvent(PayrollEvent.AttachBatch(batch.id)) },
        )
    }
}

@Composable
private fun AttachedDeductions(open: AdjustmentDialog, onEvent: (PayrollEvent) -> Unit) {
    SectionHead(str(S.desktop_payroll_on_this_timecard))
    if (open.timecard.deductions.isEmpty()) {
        Muted(if (open.loading) str(S.desktop_payroll_loading_timecard) else str(S.desktop_payroll_no_deductions_yet))
    }
    open.timecard.deductions.forEach { row ->
        val sub = (if (row.isPercentage) str(S.desktop_payroll_percent_of_basic_pay, row.rateAmount.toString())
        else str(S.desktop_flat_amount)) + (row.nominalCode?.let { " · $it" } ?: "")
        LineRow(
            title = row.label,
            sub = sub,
            tag = if (row.isPercentage) "${row.rateAmount}%" else str(S.desktop_payroll_tag_flat),
            amount = "−" + Money.format(row.amount, open.timecard.currency),
            action = if (!open.readOnly && row.isCustom && row.id != null) str(S.desktop_payroll_remove) else null,
            busy = open.busyKey == row.id,
            onAction = { row.id?.let { onEvent(PayrollEvent.RemoveDeduction(it)) } },
        )
    }
}

/** A manual claim line or a flat deduction: description, amount, nominal code. */
@Composable
private fun ManualLine(open: AdjustmentDialog, onEvent: (PayrollEvent) -> Unit) {
    val claims = open.kind == AdjustmentKind.Claims
    SectionHead(str(if (claims) S.desktop_payroll_add_manual_line else S.desktop_payroll_add_deduction))
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.Bottom) {
        ZillitTextField(
            value = open.name,
            onValueChange = { onEvent(PayrollEvent.EditAdjustment(name = it)) },
            label = str(S.description),
            placeholder = str(
                if (claims) S.desktop_payroll_claim_placeholder else S.desktop_payroll_deduction_placeholder,
            ),
            modifier = Modifier.weight(2f),
        )
        ZillitTextField(
            value = open.amount,
            onValueChange = { onEvent(PayrollEvent.EditAdjustment(amount = it)) },
            label = str(S.amount),
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = open.nominal,
            onValueChange = { onEvent(PayrollEvent.EditAdjustment(nominal = it)) },
            label = str(S.desktop_payroll_nominal_code),
            placeholder = if (claims) "5300" else "",
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(
                when {
                    open.busyKey == ADD_KEY -> S.desktop_adding
                    claims -> S.desktop_po_add_line
                    else -> S.desktop_payroll_add_deduction
                },
            ),
            onClick = { onEvent(PayrollEvent.AddAdjustment) },
            enabled = open.canAdd && open.busyKey == null,
            loading = open.busyKey == ADD_KEY,
        )
    }
}

@Composable
private fun LineRow(
    title: String,
    sub: String,
    tag: String,
    amount: String,
    action: String?,
    busy: Boolean,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = title,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                ZillitTag(label = tag, tone = TagTone.Neutral)
            }
            ZillitText(
                text = sub,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitText(text = amount, style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold))
        if (action != null) {
            ZillitButton(
                text = action,
                onClick = onAction,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !busy,
                loading = busy,
            )
        }
    }
}

@Composable
private fun Muted(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}

/** Cancel and a primary action, right-aligned — every payroll dialog's footer. */
@Composable
internal fun DialogActions(
    cancel: () -> Unit,
    confirmText: String,
    confirm: () -> Unit,
    busy: Boolean,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Spacer(Modifier.weight(1f))
        ZillitButton(text = str(S.cancel), onClick = cancel, variant = ButtonVariant.Secondary, enabled = !busy)
        ZillitButton(
            text = confirmText,
            onClick = confirm,
            variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
            enabled = enabled && !busy,
            loading = busy,
        )
    }
}

private const val ADD_KEY = "add"
private val NARROW_DIALOG = 480.dp
private val WIDE_DIALOG = 640.dp
