package com.zillit.desktop.feature.cashexpenses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashAccount
import com.zillit.desktop.feature.cashexpenses.domain.CashCompany
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.ui.pages.monthLabel

/**
 * Ready to Collect's company and BS code (`PCFloatsPage.jsx:455-487`).
 *
 * The company is required — the server refuses the transition without one —
 * and the BS code is optional, but this is the only point in the float's life
 * where it can be set.
 */
@Composable
internal fun ColumnScope.ReadyToCollectFields(
    prompt: CashPrompt.ReadyToCollect,
    companies: List<CashCompany>,
    onEvent: (CashEvent) -> Unit,
    /** The chart the BS code is picked from; null reads it, and a bare field stands in meanwhile. */
    chart: List<CashAccount>? = null,
) {
    FieldLabel(str(S.desktop_ce_company_required))
    if (companies.isEmpty()) {
        ZillitText(
            text = str(S.desktop_ce_no_companies),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    } else {
        ZillitSelect(
            value = prompt.companyId,
            options = listOf("") + companies.map { it.id },
            onSelect = { onEvent(CashEvent.UpdatePrompt(prompt.copy(companyId = it))) },
            label = { id ->
                companies.firstOrNull { it.id == id }
                    ?.let { listOf(it.name, it.country).filter(String::isNotBlank).joinToString(" · ") }
                    ?: str(S.desktop_ce_choose_company)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    BsCodePicker(
        value = prompt.bsCode,
        onValueChange = { onEvent(CashEvent.UpdatePrompt(prompt.copy(bsCode = it))) },
        chart = chart,
        onEvent = onEvent,
        label = str(S.desktop_ce_bs_code),
        placeholder = str(S.desktop_ce_bs_code_placeholder),
        helperText = str(S.desktop_ce_bs_code_help),
    )
}

/**
 * The web's `BsCodeInput`: type a code, or pick one of the chart's postable
 * balance-sheet codes that match what is typed. Reads the chart the first
 * time it is shown.
 */
@Composable
internal fun BsCodePicker(
    value: String,
    onValueChange: (String) -> Unit,
    chart: List<CashAccount>?,
    onEvent: (CashEvent) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    helperText: String? = null,
) {
    LaunchedEffect(chart == null) { if (chart == null) onEvent(CashEvent.LoadChartAccounts) }
    val needle = value.trim().lowercase()
    val matches = remember(needle, chart) {
        if (needle.isEmpty()) {
            emptyList()
        } else {
            chart.orEmpty()
                .filter { it.balanceSheet && it.postable && it.leaf }
                .filter { it.code.lowercase() != needle && it.label.lowercase().contains(needle) }
                .take(PICKER_SUGGESTIONS)
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            helperText = helperText,
            modifier = Modifier.fillMaxWidth(),
        )
        matches.forEach { account ->
            ZillitText(
                text = account.label,
                style = ZillitTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onValueChange(account.code) }
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            )
        }
    }
}

/**
 * A manual cash return — which float, how much, when, and why
 * (`RecordCashReturnModal.jsx`).
 */
@Suppress("LongMethod") // Five fields, in the web's order.
@Composable
internal fun ColumnScope.RecordReturnFields(
    prompt: CashPrompt.RecordReturn,
    state: CashUiState?,
    onEvent: (CashEvent) -> Unit,
) {
    val people = LocalCashPeople.current
    val floats = state?.activeFloats.orEmpty().filter { it.status in FloatDesk.RETURNABLE }
    val chosen = floats.firstOrNull { it.id == prompt.floatId }
        ?: state?.activeFloats?.firstOrNull { it.id == prompt.floatId }
    fun update(next: CashPrompt.RecordReturn) = onEvent(CashEvent.UpdatePrompt(next))

    FieldLabel(str(S.desktop_ce_crew_member_float))
    if (prompt.fixed && chosen != null) {
        // Opened from the float's own row: shown, not picked (`RecordCashReturnModal.jsx:143-151`).
        val designation = state?.assignees?.firstOrNull { it.userId == chosen.userId }?.designation
        ZillitText(
            text = listOfNotNull(
                people.nameOf(chosen.userId, chosen.holderName),
                designation?.takeIf { it.isNotBlank() },
                str(S.desktop_pc_float_balance_line, chosen.requestNumber, money(chosen.balance, chosen.currency)),
            ).joinToString(" · "),
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken, ZillitTheme.shapes.medium)
                .padding(ZillitTheme.spacing.md),
        )
    } else {
        ZillitSelect(
            value = prompt.floatId.orEmpty(),
            options = listOf("") + floats.map { it.id },
            onSelect = { update(prompt.copy(floatId = it.ifBlank { null })) },
            label = { id ->
                floats.firstOrNull { it.id == id }?.let { float ->
                    str(
                        S.desktop_ce_float_option,
                        people.nameOf(float.userId, float.holderName),
                        float.requestNumber,
                        money(float.balance, float.currency),
                    )
                } ?: str(S.desktop_ce_choose_crew_member_option)
            },
            enabled = floats.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    chosen?.takeIf { !prompt.fixed }?.let {
        ZillitText(
            text = str(S.desktop_ce_balance_now, money(it.balance, it.currency)),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitTextField(
            value = prompt.amount,
            onValueChange = { update(prompt.copy(amount = it)) },
            label = str(S.desktop_ce_amount_returned),
            placeholder = "0.00",
            helperText = str(S.desktop_pc_cash_physically_received),
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        ZillitDateField(
            value = prompt.receivedDate,
            onValueChange = { update(prompt.copy(receivedDate = it)) },
            label = str(S.desktop_ce_date_received),
            modifier = Modifier.weight(1f),
        )
    }
    FieldLabel(str(S.desktop_ce_return_reason))
    ZillitSelect(
        value = prompt.reason,
        options = ReturnReasons.ALL,
        onSelect = { update(prompt.copy(reason = it)) },
        label = ::returnReasonLabel,
        modifier = Modifier.fillMaxWidth(),
    )
    if (prompt.reason == ReturnReasons.OTHER) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            ZillitCheckbox(
                checked = prompt.otherCloses,
                onCheckedChange = { update(prompt.copy(otherCloses = true)) },
                label = str(S.desktop_ce_close_the_float),
            )
            ZillitCheckbox(
                checked = !prompt.otherCloses,
                onCheckedChange = { update(prompt.copy(otherCloses = false)) },
                label = str(S.desktop_ce_continue_the_float),
            )
        }
    }
    ZillitTextField(
        value = prompt.notes,
        onValueChange = { update(prompt.copy(notes = it)) },
        label = str(S.notes),
        placeholder = str(S.desktop_ce_return_notes_placeholder),
        singleLine = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun returnReasonLabel(reason: String): String = when (reason) {
    ReturnReasons.CLOSE_FULL -> str(S.desktop_ce_return_close_full)
    ReturnReasons.CONTINUE_PARTIAL -> str(S.desktop_ce_return_partial)
    ReturnReasons.OVERSPEND -> str(S.desktop_ce_return_overspend)
    ReturnReasons.CANCEL -> str(S.desktop_ce_return_cancelled)
    else -> str(S.other)
}

/** A new reconciliation period: the safe's opening balance, the month and the currency. */
@Composable
internal fun ColumnScope.NewReconciliationFields(prompt: CashPrompt.NewReconciliation, onEvent: (CashEvent) -> Unit) {
    fun update(next: CashPrompt.NewReconciliation) = onEvent(CashEvent.UpdatePrompt(next))
    ZillitTextField(
        value = prompt.openingBalance,
        onValueChange = { update(prompt.copy(openingBalance = it)) },
        label = str(S.desktop_ce_opening_balance),
        placeholder = "0.00",
        keyboardType = KeyboardType.Decimal,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Column(modifier = Modifier.weight(1f)) {
            FieldLabel(str(S.cr_meta_period))
            ZillitSelect(
                value = prompt.year to prompt.month,
                options = CashDates.recentMonths(),
                onSelect = { (year, month) -> update(prompt.copy(year = year, month = month)) },
                label = { monthLabel(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ZillitTextField(
            value = prompt.currency,
            onValueChange = { update(prompt.copy(currency = it.uppercase())) },
            label = str(S.ah_lbl_currency),
            placeholder = "GBP",
            modifier = Modifier.weight(1f),
        )
    }
}

private const val PICKER_SUGGESTIONS = 6

@Composable
private fun FieldLabel(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
}
