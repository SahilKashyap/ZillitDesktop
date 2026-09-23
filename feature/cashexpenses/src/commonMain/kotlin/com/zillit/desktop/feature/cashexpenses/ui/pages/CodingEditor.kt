package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.EditorLine
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.CodingDraft

/**
 * Coding one receipt across cost codes.
 *
 * ## The running balance is the whole screen
 *
 * Coding that does not add up to the receipt is the commonest reason a batch
 * bounces back from audit, so the difference is on screen at all times and the
 * save is refused until it is zero. Everything else here — splitting a line,
 * the quick codes, the per-line tax — exists to get that number to zero
 * quickly.
 *
 * ## Engine rows are shown, not editable
 *
 * The processing-rules engine maintains its own deduction lines and re-derives
 * them on save. They are drawn so the coder can see why the receipt does not
 * reach its own total, and locked so nobody edits a row the server is about to
 * overwrite.
 */
@Suppress("LongMethod") // One editor; the running balance and the grid belong together.
@Composable
fun CodingEditorDialog(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val draft = state.coding

    ZillitDialogShell(
        title = str(S.desktop_ce_code_this_receipt),
        subtitle = draft?.let {
            str(S.desktop_card_receipt_total, Money.format(it.receiptGross, it.currency))
        },
        icon = ZillitIcons.Ledger,
        visible = draft != null,
        width = EDITOR_WIDTH,
        onDismiss = { onEvent(CashEvent.CloseCoding) },
    ) {
        if (draft == null) return@ZillitDialogShell

        BalanceBar(draft)
        ZillitDivider()

        draft.lines.forEachIndexed { index, line ->
            CodingRow(
                index = index,
                line = line,
                currency = draft.currency,
                quickCodes = state.settings?.quickCodes.orEmpty()
                    .map { it.nominalCode.ifBlank { it.name } }
                    .filter { it.isNotBlank() },
                removable = draft.lines.size > 1,
                onChange = { onEvent(CashEvent.EditCodingLine(index, it)) },
                onRemove = { onEvent(CashEvent.RemoveCodingLine(line.id)) },
                onSplit = { ways -> onEvent(CashEvent.SplitCodingLine(line.id, ways)) },
            )
        }

        if (draft.lines.any { it.autoDeduction }) {
            ZillitNotice(
                text = str(S.desktop_ce_automatic_rows_note),
                tone = StatusTone.Escalated,
                icon = ZillitIcons.Info,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = str(S.desktop_add_a_line),
                onClick = { onEvent(CashEvent.AddCodingLine) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CashEvent.CloseCoding) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.desktop_card_save_coding),
                onClick = { onEvent(CashEvent.SaveCoding) },
                // Disabled rather than failing on click: the reason is already
                // on screen in the balance bar, so a refusal here would only
                // repeat it.
                enabled = draft.balances && !state.busy,
                loading = state.busy,
            )
        }
    }
}

/** What is coded, what the receipt was, and the difference. */
@Composable
private fun BalanceBar(draft: CodingDraft) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = str(S.desktop_ce_coded_caps),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            ZillitText(
                text = Money.format(draft.total, draft.currency),
                style = ZillitTheme.typography.titleMedium,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = str(S.desktop_ce_receipt_caps),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            ZillitText(
                text = Money.format(draft.receiptGross, draft.currency),
                style = ZillitTheme.typography.titleMedium,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = if (draft.remaining < 0) {
                    str(S.desktop_card_over_by_caps)
                } else {
                    str(S.desktop_ce_left_to_code_caps)
                },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            ZillitText(
                text = Money.format(kotlin.math.abs(draft.remaining), draft.currency),
                style = ZillitTheme.typography.titleMedium,
                color = if (draft.balances) colors.success else colors.danger,
            )
        }
        ZillitStatusPill(
            label = if (draft.balances) {
                str(S.desktop_card_balanced)
            } else {
                str(S.desktop_payroll_does_not_add_up)
            },
            tone = if (draft.balances) StatusTone.Done else StatusTone.Rejected,
            dot = true,
        )
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // One coding row: every field it carries.
@Composable
private fun CodingRow(
    index: Int,
    line: EditorLine,
    currency: String?,
    quickCodes: List<String>,
    removable: Boolean,
    onChange: (EditorLine) -> Unit,
    onRemove: () -> Unit,
    onSplit: (Int) -> Unit,
) {
    val colors = ZillitTheme.colors
    val locked = line.autoDeduction

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A split child is indented under its parent, so the shape of the
            // split is readable without a tree control.
            .padding(start = if (line.isSplitChild) ZillitTheme.spacing.lg else ZillitTheme.spacing.none)
            .padding(vertical = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitTextField(
                value = line.description,
                onValueChange = { onChange(line.copy(description = it)) },
                label = if (index == 0) str(S.desktop_ce_what_this_line_covers) else null,
                enabled = !locked,
                modifier = Modifier.weight(DESCRIPTION_WEIGHT),
            )
            if (quickCodes.isEmpty()) {
                ZillitTextField(
                    value = line.account,
                    onValueChange = { onChange(line.copy(account = it)) },
                    label = if (index == 0) str(S.desktop_card_cost_code) else null,
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    if (index == 0) {
                        ZillitText(
                            text = str(S.desktop_card_cost_code),
                            style = ZillitTheme.typography.label,
                            color = colors.textSecondary,
                        )
                    }
                    // The production's saved codes, when it has any — typing a
                    // code from memory is how the wrong one gets used.
                    ZillitSelect(
                        value = line.account.takeIf { it.isNotBlank() },
                        options = listOf(null) + quickCodes,
                        onSelect = { onChange(line.copy(account = it.orEmpty())) },
                        label = { it ?: str(S.desktop_ce_choose_a_code) },
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ZillitTextField(
                value = if (line.quantity == 1.0) "" else line.quantity.toString(),
                onValueChange = { onChange(line.copy(quantity = it.trim().toDoubleOrNull() ?: 1.0)) },
                label = if (index == 0) str(S.ah_lbl_qty) else null,
                placeholder = "1",
                keyboardType = KeyboardType.Decimal,
                enabled = !locked,
                modifier = Modifier.width(SMALL_FIELD),
            )
            ZillitTextField(
                value = if (line.unitPrice == 0.0) "" else line.unitPrice.toString(),
                onValueChange = { onChange(line.copy(unitPrice = it.trim().toDoubleOrNull() ?: 0.0)) },
                label = if (index == 0) str(S.desktop_net) else null,
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = if (line.taxRatePercent == 0.0) "" else line.taxRatePercent.toString(),
                onValueChange = {
                    onChange(line.copy(taxRatePercent = it.trim().toDoubleOrNull() ?: 0.0))
                },
                label = if (index == 0) str(S.desktop_ce_vat_percent) else null,
                placeholder = "20",
                keyboardType = KeyboardType.Decimal,
                enabled = !locked,
                modifier = Modifier.width(SMALL_FIELD),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (locked) {
                ZillitStatusPill(label = str(S.desktop_ce_automatic), tone = StatusTone.Escalated)
            }
            if (line.isSplitChild) {
                ZillitStatusPill(label = str(S.desktop_ce_split), tone = StatusTone.Progress)
            }
            ZillitText(
                text = str(S.desktop_ce_gross_amount, Money.format(line.gross, currency)),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (!locked && !line.isSplitChild) {
                ZillitButton(
                    text = str(S.desktop_ce_split_in_two),
                    onClick = { onSplit(2) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            if (removable && !locked) {
                ZillitButton(
                    text = str(S.remove),
                    onClick = onRemove,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
        }
    }
}

private const val DESCRIPTION_WEIGHT = 2f
private val SMALL_FIELD = 80.dp
private val EDITOR_WIDTH = 900.dp
