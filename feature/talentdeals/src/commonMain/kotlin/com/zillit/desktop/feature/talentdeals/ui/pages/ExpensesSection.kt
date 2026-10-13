package com.zillit.desktop.feature.talentdeals.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.ExpenseDraft
import com.zillit.desktop.feature.talentdeals.domain.TalentForm
import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers
import com.zillit.desktop.feature.talentdeals.domain.TalentWithholding
import com.zillit.desktop.feature.talentdeals.domain.WithholdingBasis
import com.zillit.desktop.feature.talentdeals.domain.WithholdingType
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEvent
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsUiState

/**
 * Expenses — per diem, accommodation, travel, an assistant: anything paid
 * alongside the fee. Two of the columns only exist when the contract withholds
 * tax, because "taxable" and "paid in kind" are questions about the withholding
 * and say nothing on a contract that has none. Below, the breakdown of what the
 * production pays, what the performer receives and what goes to the tax
 * authority.
 */
@Composable
internal fun ExpensesSection(state: TalentDealsUiState, onEvent: (TalentDealsEvent) -> Unit) {
    val form = state.form.form
    val edit = { change: TalentForm.() -> TalentForm -> onEvent(TalentDealsEvent.FormEdit(change)) }
    val basis = TalentWithholding.basis(form)
    val summary = TalentWithholding.summary(
        form.fee,
        form.expenses,
        basis,
        feeLabel = str(S.desktop_dm_fee),
        expenseLabel = str(S.desktop_expense),
    )
    val labels = (TalentWithholding.EXPENSE_TYPES + form.expenses.map { it.label })
        .filter { it.isNotBlank() }
        .distinct()

    FormSection(
        title = str(S.desktop_td_sec_expenses),
        action = if (form.hasExpenses) {
            {
                ZillitButton(
                    text = str(S.desktop_td_add_expense),
                    leadingIcon = ZillitIcons.Add,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    onClick = { onEvent(TalentDealsEvent.AddExpense) },
                )
            }
        } else {
            null
        },
    ) {
        ZillitCheckbox(
            checked = form.hasExpenses,
            onCheckedChange = { onEvent(TalentDealsEvent.ToggleExpenses(it)) },
            label = str(S.desktop_td_chk_expenses),
        )
        if (form.hasExpenses) {
            ExpenseHeader(form.withholding)
            form.expenses.forEachIndexed { index, expense ->
                ExpenseRow(index, expense, labels, form.withholding, canGrossUp = basis !is WithholdingBasis.Flat,
                    state.symbol, edit)
            }
            if (basis is WithholdingBasis.None) Hint(whyNoTax(form))
        }
        if (TalentNumbers.num(form.fee) > 0 || form.expenses.isNotEmpty()) {
            WithholdingBreakdown(summary, state.symbol, authorised = form.withholdingAuthorised)
        }
    }
}

/** Why the expense rows carry no tax yet, said where the rows are. */
private fun whyNoTax(form: TalentForm): String = when {
    !form.withholding -> str(S.desktop_td_why_tick_withholding)
    form.withholdingAuthorised -> str(S.desktop_td_why_authorised_rate)
    form.withholdingType == WithholdingType.Amount -> str(S.desktop_td_why_flat_amount)
    else -> str(S.desktop_td_why_rate, TalentWithholding.DEFAULT_RATE)
}

@Composable
private fun ExpenseHeader(withholding: Boolean) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText(str(S.desktop_expense).uppercase(), style = ZillitTheme.typography.columnHeader,
            color = colors.textMuted, modifier = Modifier.weight(LABEL_WEIGHT))
        ZillitText(str(S.amount).uppercase(), style = ZillitTheme.typography.columnHeader, color = colors.textMuted,
            modifier = Modifier.weight(AMOUNT_WEIGHT))
        if (withholding) {
            ZillitText(str(S.desktop_td_th_taxable).uppercase(), style = ZillitTheme.typography.columnHeader,
                color = colors.textMuted, modifier = Modifier.width(TAXABLE_WIDTH))
            ZillitText(str(S.desktop_td_th_in_kind).uppercase(), style = ZillitTheme.typography.columnHeader,
                color = colors.textMuted, modifier = Modifier.width(IN_KIND_WIDTH))
        }
        Box(Modifier.width(REMOVE_WIDTH))
    }
}

@Composable
private fun ExpenseRow(
    index: Int,
    expense: ExpenseDraft,
    labels: List<String>,
    withholding: Boolean,
    canGrossUp: Boolean,
    symbol: String,
    edit: (TalentForm.() -> TalentForm) -> Unit,
) {
    fun patch(change: (ExpenseDraft) -> ExpenseDraft) = edit {
        copy(expenses = expenses.map { if (it.id == expense.id) change(it) else it })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically) {
        LabelPicker(expense.label, labels, Modifier.weight(LABEL_WEIGHT)) { label -> patch { it.copy(label = label) } }
        Box(Modifier.weight(AMOUNT_WEIGHT)) {
            MoneyInput(expense.amount, symbol, { value -> patch { it.copy(amount = value) } })
        }
        if (withholding) {
            Box(Modifier.width(TAXABLE_WIDTH)) {
                // Nothing is withheld, so there is no tax to gross up.
                ZillitCheckbox(
                    checked = expense.withhold,
                    onCheckedChange = { on -> patch { it.copy(withhold = on, grossUp = on && it.grossUp) } },
                )
            }
            Box(Modifier.width(IN_KIND_WIDTH)) {
                // Nothing withheld on the line, so no tax to carry; and a flat withholding
                // has no rate to gross up against.
                ZillitCheckbox(
                    checked = expense.grossUp,
                    onCheckedChange = { on -> patch { it.copy(grossUp = on) } },
                    label = str(S.desktop_td_gross_up_hint),
                    enabled = expense.withhold && canGrossUp,
                )
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.desktop_td_remove_expense, index + 1),
            onClick = { edit { copy(expenses = expenses.filter { it.id != expense.id }) } },
            modifier = Modifier.width(REMOVE_WIDTH),
        )
    }
}

private val TAXABLE_WIDTH = 80.dp
private val IN_KIND_WIDTH = 130.dp
private val REMOVE_WIDTH = 36.dp
private const val LABEL_WEIGHT = 0.45f
private const val AMOUNT_WEIGHT = 0.30f
