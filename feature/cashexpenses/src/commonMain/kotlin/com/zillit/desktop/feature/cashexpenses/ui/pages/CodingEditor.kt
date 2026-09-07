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
        title = "Code this receipt",
        subtitle = draft?.let {
            "Receipt total ${Money.format(it.receiptGross, it.currency)}"
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
                quickCodes = state.settings?.quickCodes.orEmpty().map { it.code },
                removable = draft.lines.size > 1,
                onChange = { onEvent(CashEvent.EditCodingLine(index, it)) },
                onRemove = { onEvent(CashEvent.RemoveCodingLine(line.id)) },
                onSplit = { ways -> onEvent(CashEvent.SplitCodingLine(line.id, ways)) },
            )
        }

        if (draft.lines.any { it.autoDeduction }) {
            ZillitNotice(
                text = "Rows marked automatic are maintained by the project's deduction rules. " +
                    "They are re-applied on save and cannot be edited here.",
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
                text = "Add a line",
                onClick = { onEvent(CashEvent.AddCodingLine) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(CashEvent.CloseCoding) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save coding",
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
                text = "CODED",
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
                text = "RECEIPT",
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
                text = if (draft.remaining < 0) "OVER BY" else "LEFT TO CODE",
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
            label = if (draft.balances) "Balanced" else "Does not add up",
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
                label = if (index == 0) "What this line covers" else null,
                enabled = !locked,
                modifier = Modifier.weight(DESCRIPTION_WEIGHT),
            )
            if (quickCodes.isEmpty()) {
                ZillitTextField(
                    value = line.account,
                    onValueChange = { onChange(line.copy(account = it)) },
                    label = if (index == 0) "Cost code" else null,
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    if (index == 0) {
                        ZillitText(
                            text = "Cost code",
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
                        label = { it ?: "Choose a code" },
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ZillitTextField(
                value = if (line.quantity == 1.0) "" else line.quantity.toString(),
                onValueChange = { onChange(line.copy(quantity = it.trim().toDoubleOrNull() ?: 1.0)) },
                label = if (index == 0) "Qty" else null,
                placeholder = "1",
                keyboardType = KeyboardType.Decimal,
                enabled = !locked,
                modifier = Modifier.width(SMALL_FIELD),
            )
            ZillitTextField(
                value = if (line.unitPrice == 0.0) "" else line.unitPrice.toString(),
                onValueChange = { onChange(line.copy(unitPrice = it.trim().toDoubleOrNull() ?: 0.0)) },
                label = if (index == 0) "Net" else null,
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
                label = if (index == 0) "VAT %" else null,
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
                ZillitStatusPill(label = "Automatic", tone = StatusTone.Escalated)
            }
            if (line.isSplitChild) {
                ZillitStatusPill(label = "Split", tone = StatusTone.Progress)
            }
            ZillitText(
                text = "Gross ${Money.format(line.gross, currency)}",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (!locked && !line.isSplitChild) {
                ZillitButton(
                    text = "Split in two",
                    onClick = { onSplit(2) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            if (removable && !locked) {
                ZillitButton(
                    text = "Remove",
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
