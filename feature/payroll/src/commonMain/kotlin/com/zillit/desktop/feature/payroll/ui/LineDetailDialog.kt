package com.zillit.desktop.feature.payroll.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.payroll.domain.NominalAllocation
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * One crew line, opened: what they are paid, and where it is charged.
 *
 * ## Two halves that answer different questions
 *
 * The payslip answers "why is this the figure" and is read-only, because it is
 * derived from the timecard and the deal — correcting it means correcting one
 * of those. The nominal split answers "where does this cost land" and is the
 * accountant's to set, so that half is editable and has to add up to the line.
 */
@Suppress("LongMethod") // The two halves are read against each other; splitting them hides that.
@Composable
fun LineDetailDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val line = state.openLine

    ZillitDialogShell(
        title = line?.crewName?.ifBlank { line.crewId } ?: "",
        subtitle = line?.let {
            "${Money.format(it.net, it.currency)} net · ${Money.format(it.gross, it.currency)} gross"
        },
        icon = ZillitIcons.Users,
        visible = line != null,
        width = DIALOG_WIDTH,
        onDismiss = { onEvent(PayrollEvent.OpenLine(null)) },
    ) {
        if (line == null) return@ZillitDialogShell

        val slip = state.payslip
        if (slip == null) {
            ZillitText(
                text = str(S.desktop_payroll_no_payslip),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            ZillitText(text = str(S.desktop_payroll_payslip), style = ZillitTheme.typography.titleSmall)
            slip.lines.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = row.label,
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    ZillitText(
                        // A deduction reads as a subtraction rather than as a
                        // positive figure the reader has to know to subtract.
                        text = (if (row.isDeduction) "-" else "") +
                            Money.format(row.amount, slip.currency),
                        style = ZillitTheme.typography.numeric,
                        color = if (row.isDeduction) {
                            ZillitTheme.colors.danger
                        } else {
                            ZillitTheme.colors.textPrimary
                        },
                        maxLines = 1,
                    )
                }
            }
        }

        ZillitDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(text = str(S.desktop_payroll_where_charged), style = ZillitTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            ZillitText(
                text = "${Money.format(state.splitTotal, line.currency)} of " +
                    Money.format(line.gross, line.currency),
                style = ZillitTheme.typography.bodySmall,
                color = if (state.splitBalances) {
                    ZillitTheme.colors.textSecondary
                } else {
                    ZillitTheme.colors.danger
                },
            )
            ZillitStatusPill(
                label = if (state.splitBalances) {
                    str(S.txt_vehicle_status_allocated)
                } else {
                    str(S.desktop_payroll_does_not_add_up)
                },
                tone = if (state.splitBalances) StatusTone.Done else StatusTone.Rejected,
                dot = true,
            )
        }

        state.nominalSplit.forEachIndexed { index, allocation ->
            AllocationRow(
                index = index,
                allocation = allocation,
                removable = state.nominalSplit.size > 1,
                onChange = { onEvent(PayrollEvent.EditAllocation(index, it)) },
                onRemove = { onEvent(PayrollEvent.RemoveAllocation(index)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitButton(
                text = str(S.desktop_payroll_add_allocation),
                onClick = { onEvent(PayrollEvent.AddAllocation) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
                // Posted coding is fixed: it is already in the ledger.
                enabled = state.codingEditable,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(PayrollEvent.OpenLine(null)) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.desktop_payroll_save_allocations),
                onClick = { onEvent(PayrollEvent.SaveAllocations) },
                enabled = state.splitBalances && !state.busy && state.codingEditable,
                loading = state.busy,
            )
        }
    }
}

@Composable
private fun AllocationRow(
    index: Int,
    allocation: NominalAllocation,
    removable: Boolean,
    onChange: (NominalAllocation) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        ZillitTextField(
            value = allocation.nominalCode,
            onValueChange = { onChange(allocation.copy(nominalCode = it)) },
            label = if (index == 0) str(S.dm_allow_nominal) else null,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = allocation.description,
            onValueChange = { onChange(allocation.copy(description = it)) },
            label = if (index == 0) str(S.desktop_payroll_what_it_covers) else null,
            modifier = Modifier.weight(2f),
        )
        ZillitTextField(
            value = if (allocation.amount == 0.0) "" else allocation.amount.toString(),
            onValueChange = { onChange(allocation.copy(amount = it.trim().toDoubleOrNull() ?: 0.0)) },
            label = if (index == 0) str(S.amount) else null,
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
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
}

private val DIALOG_WIDTH = 820.dp
