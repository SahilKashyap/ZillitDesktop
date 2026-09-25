package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CrewInput
import com.zillit.desktop.feature.cashexpenses.domain.ExtraBankField
import com.zillit.desktop.feature.cashexpenses.domain.FollowUp
import com.zillit.desktop.feature.cashexpenses.domain.ReimbursementMethod
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cashexpenses.ui.CrewRules

/**
 * Step 2 on petty cash — "Choose your settlement" (`PCSettlementSection`).
 *
 * The primary card is decided, not chosen: over the float's headroom is a
 * reimbursement, within it a reduce. Every figure is the one
 * [com.zillit.desktop.feature.cashexpenses.domain.FloatSettlement].
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod") // The bar, the primary card, the follow-ups and their panels, in the web's order.
@Composable
internal fun SettlementSection(state: CashUiState, float: CashFloat, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val settlement = state.settlement
    val money = { amount: Double -> state.currencies.format(amount, float.currency) }
    val total = state.draft.total
    val count = state.draft.receipts.count { it.description.isNotBlank() && (it.amount.toDoubleOrNull() ?: 0.0) > 0 }
    val reimburse = settlement.reimburses
    StepCard(2, str(S.desktop_pc_choose_settlement), str(S.desktop_pc_sent_to_accountant)) {
        // The batch total bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.medium)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                BarFigure(str(S.desktop_pc_batch_total), money(total), str(S.desktop_card_receipt_count_other, count))
                BarFigure(str(S.desktop_ce_float_balance), money(float.balance), tone = colors.success)
                settlement.receiptsCommits?.let { committed ->
                    BarFigure(str(S.desktop_cr_committed), money(committed), tone = colors.warning)
                    BarFigure(str(S.desktop_pc_available_to_spend), money(settlement.headroom))
                }
            }
            ZillitStatusPill(label = str(S.desktop_pc_pending_submission), tone = StatusTone.Pending, dot = true)
        }

        // The primary settlement — decided by the arithmetic.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(colors.accentSoft)
                .border(BORDER_STRONG, colors.accent, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIcon(
                icon = if (reimburse) ZillitIcons.Bank else ZillitIcons.Wallet,
                tint = colors.accentText,
                size = PRIMARY_ICON,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(
                    text = if (reimburse) str(S.desktop_pc_reimburse_me) else str(S.desktop_pc_reduce_my_float),
                    style = ZillitTheme.typography.titleSmall,
                    color = colors.accentText,
                )
                ZillitText(
                    text = if (reimburse) str(S.desktop_pc_reimburse_me_desc) else str(S.desktop_pc_reduce_desc),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.accentText,
                )
                ZillitText(
                    text = if (reimburse) {
                        str(S.desktop_pc_reimburse_impact, money(settlement.overdraft), money(settlement.floatConsumed))
                    } else {
                        str(S.desktop_pc_reduce_impact, money(total))
                    },
                    style = ZillitTheme.typography.numeric,
                    color = colors.accentText,
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.medium)
                        .background(colors.surface)
                        .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                )
            }
            Box(
                modifier = Modifier.size(CHECK).clip(CircleShape).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(icon = ZillitIcons.Check, tint = colors.textOnAccent, size = CHECK_GLYPH) }
        }

        // The optional follow-ups: pick one or none.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            CrewHeading(str(S.desktop_pc_optional_also))
            ZillitChoiceChip(
                label = str(S.desktop_pc_reimburse_to_float),
                selected = state.crew.followUp == FollowUp.TOP_UP,
                onClick = { onEvent(CrewEvent.ToggleFollowUp(FollowUp.TOP_UP)) },
            )
            ZillitChoiceChip(
                label = str(S.desktop_pc_close_the_float),
                selected = state.crew.followUp == FollowUp.CLOSE,
                onClick = { onEvent(CrewEvent.ToggleFollowUp(FollowUp.CLOSE)) },
            )
        }

        if (reimburse) {
            ReimbursementPanel(state, settlement.overdraft, state.currencies.codeFor(float), onEvent)
        }
        if (state.crew.followUp == FollowUp.TOP_UP) {
            CrewInfoBox(text = str(S.desktop_pc_top_up_note, money(total)))
        }
        if (state.crew.followUp == FollowUp.CLOSE) CloseFloatPanel(state, float, total)
    }
}

@Composable
private fun BarFigure(
    label: String,
    value: String,
    suffix: String? = null,
    tone: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = label, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        ZillitText(text = value, style = ZillitTheme.typography.numeric, color = tone ?: ZillitTheme.colors.textPrimary)
        if (suffix != null) {
            ZillitText(
                text = "· $suffix",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** "Close Float" — the cash to hand back, and how it was worked out. */
@Composable
private fun CloseFloatPanel(state: CashUiState, float: CashFloat, total: Double) {
    val colors = ZillitTheme.colors
    val settlement = state.settlement
    val money = { amount: Double -> state.currencies.format(amount, float.currency) }
    val pending = CrewRules.pendingAgainst(float, state.myBatches)
    val returning = settlement.returnAmount > HALF_PENNY
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(text = str(S.desktop_ce_close_float), style = ZillitTheme.typography.titleSmall)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(if (returning) colors.warningSoft else colors.successSoft)
                .padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            val tone = if (returning) colors.warning else colors.success
            ZillitText(
                text = if (returning) {
                    str(S.desktop_pc_return_cash, money(settlement.returnAmount))
                } else {
                    str(S.desktop_pc_no_cash_to_return)
                },
                style = ZillitTheme.typography.label,
                color = tone,
            )
            LedgerLine(str(S.desktop_pc_current_float_balance), money(float.balance))
            LedgerLine(str(S.desktop_pc_minus_batch_total), "−" + money(total))
            if (pending.isNotEmpty()) {
                LedgerLine(
                    str(S.desktop_pc_minus_pending_batches, pending.size),
                    "−" + money(pending.sumOf { it.totalGross }),
                )
            }
            CrewRule()
            LedgerLine(
                str(S.desktop_pc_cash_to_return),
                money(settlement.returnAmount.coerceAtLeast(0.0)),
                strong = true,
            )
            if (settlement.returnAmount < -HALF_PENNY) {
                ZillitText(
                    text = str(S.desktop_pc_pending_exceed_balance, money(-settlement.returnAmount)),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.danger,
                )
            }
        }
    }
}

/**
 * BACS or Payroll, and the bank details BACS needs (`ReimbursementPanel`).
 *
 * Payroll is offered only when the production routes reimbursements that way
 * — `reimburse_to_payroll` on the cash settings, read quietly; an unread
 * setting is taken as off, and so is a choice made before it was.
 */
@Suppress("LongMethod") // The two methods, the four bank fields and the extra rows.
@Composable
internal fun ReimbursementPanel(state: CashUiState, amount: Double, currency: String, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val bank = state.crew.bank
    val method = state.crew.effectiveMethod
    val shown = state.currencies.format(amount, currency)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        if (state.crew.payrollAllowed) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                MethodCard(
                    title = str(S.desktop_pc_bank_transfer_bacs),
                    sub = str(S.desktop_pc_bacs_sub),
                    selected = method == ReimbursementMethod.Bacs,
                    onClick = { onEvent(CrewEvent.PickReimbursement(ReimbursementMethod.Bacs)) },
                    modifier = Modifier.weight(1f),
                )
                MethodCard(
                    title = str(S.desktop_pc_add_to_payroll),
                    sub = str(S.ah_payroll_description),
                    selected = method == ReimbursementMethod.Payroll,
                    onClick = { onEvent(CrewEvent.PickReimbursement(ReimbursementMethod.Payroll)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (method == ReimbursementMethod.Payroll) {
            CrewInfoBox(text = str(S.desktop_pc_payroll_note, shown))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            CrewField(str(S.desktop_pc_account_name), Modifier.weight(1f)) {
                ZillitTextField(
                    value = bank.accountName,
                    onValueChange = { onEvent(CrewEvent.EditBank(bank.copy(accountName = it))) },
                    placeholder = str(S.desktop_pc_full_name_on_account),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CrewField(str(S.ah_lbl_sort_code), Modifier.weight(1f)) {
                // Dashes for the eye only; the draft keeps the digits the payload sends.
                ZillitTextField(
                    value = CrewInput.sortCode(bank.sortCode),
                    onValueChange = {
                        val digits = CrewInput.digits(it, CrewInput.SORT_CODE_DIGITS)
                        onEvent(CrewEvent.EditBank(bank.copy(sortCode = digits)))
                    },
                    placeholder = "20-48-91",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            CrewField(str(S.account_number), Modifier.weight(1f)) {
                ZillitTextField(
                    value = bank.accountNumber,
                    onValueChange = {
                        val digits = CrewInput.digits(it, CrewInput.ACCOUNT_NUMBER_DIGITS)
                        onEvent(CrewEvent.EditBank(bank.copy(accountNumber = digits)))
                    },
                    placeholder = "00000000",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CrewField(str(S.amount), Modifier.weight(1f)) {
                ZillitTextField(value = shown, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
            }
        }
        bank.extras.forEachIndexed { index, row -> ExtraBankRow(index, row, onEvent) }
        ZillitButton(
            text = str(S.desktop_hub_add_additional_detail_iban_bic_routing_number_etc_paren),
            onClick = { onEvent(CrewEvent.AddBankExtra) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

@Composable
private fun MethodCard(title: String, sub: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (selected) colors.surface else colors.surfaceSunken)
            .border(
                if (selected) BORDER_STRONG else CREW_HAIRLINE,
                if (selected) colors.accent else colors.border,
                ZillitTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZillitText(
            text = title,
            style = ZillitTheme.typography.label,
            color = if (selected) colors.accentText else colors.textSecondary,
        )
        ZillitText(text = sub, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
    }
}

/** One extra bank detail: its type, its title, its value. */
@Composable
private fun ExtraBankRow(index: Int, row: ExtraBankField, onEvent: (CashEvent) -> Unit) {
    val edit = { changed: ExtraBankField -> onEvent(CrewEvent.EditBankExtra(index, changed)) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSelect(
            value = row.fieldType,
            options = ExtraBankField.FIELD_TYPES,
            onSelect = { edit(row.copy(fieldType = it)) },
            label = { it.uppercase().takeIf { t -> t == "URL" } ?: it.replaceFirstChar { c -> c.uppercase() } },
            modifier = Modifier.width(TYPE_WIDTH),
        )
        ZillitTextField(
            value = row.label,
            onValueChange = { edit(row.copy(label = it)) },
            placeholder = str(S.title),
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = row.value,
            onValueChange = { edit(row.copy(value = it)) },
            placeholder = str(S.ah_addl_value_hint),
            modifier = Modifier.weight(1f),
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.remove),
            onClick = { onEvent(CrewEvent.RemoveBankExtra(index)) },
        )
    }
}

private val BORDER_STRONG = 1.5.dp
private val PRIMARY_ICON = 20.dp
private val CHECK = 22.dp
private val CHECK_GLYPH = 12.dp
private val TYPE_WIDTH = 112.dp
private const val HALF_PENNY = 0.005
