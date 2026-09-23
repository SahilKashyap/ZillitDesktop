package com.zillit.desktop.feature.payroll.ui.history

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.ui.HistoryEvent
import com.zillit.desktop.feature.payroll.ui.HistoryPost
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.components.DialogActions
import com.zillit.desktop.feature.payroll.ui.components.FigureRow

/**
 * Post to the ledger from the history queue — the ticked paid rows, or every
 * paid row when nothing is ticked ("Post All Ready"). The settling account and
 * the effective date are both required by the server, and the date may not
 * fall on or before the cost-report lock.
 */
@Composable
fun HistoryPostDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val post = state.history.post
    val shown = remember(post != null) { post } ?: post
    val weekEnding = state.history.weekStarting?.let(PayPeriod::weekEnding).orEmpty()
    ZillitDialogShell(
        title = when {
            shown == null -> ""
            shown.fromSelection -> str(S.desktop_payroll_post_selected_title, weekEnding)
            else -> str(S.desktop_payroll_post_all_ready_title, weekEnding)
        },
        icon = ZillitIcons.Lock,
        visible = post != null,
        width = DIALOG_WIDTH,
        onDismiss = { onEvent(HistoryEvent.DismissPost) },
    ) {
        val dialog = post ?: shown ?: return@ZillitDialogShell
        PostScope(state, dialog)
        PostFields(state, dialog, onEvent)
        dialog.error?.let {
            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
        }
        DialogActions(
            cancel = { onEvent(HistoryEvent.DismissPost) },
            confirmText = if (dialog.saving) {
                str(S.ah_posting_btn)
            } else {
                str(S.desktop_payroll_post_n_timecards, dialog.ids.size)
            },
            confirm = { onEvent(HistoryEvent.ConfirmPost) },
            busy = dialog.saving,
            enabled = dialog.ids.isNotEmpty() && !dialog.bankId.isNullOrBlank() && dialog.effectiveDate.isNotBlank(),
        )
    }
}

/** How many go, that posting locks them, who they are and what they cost. */
@Composable
private fun PostScope(state: PayrollUiState, dialog: HistoryPost) {
    val rows = state.history.rows.filter { it.id in dialog.ids }
    ZillitText(
        text = if (dialog.fromSelection) {
            str(S.desktop_payroll_selected_will_post, dialog.ids.size)
        } else {
            str(S.desktop_payroll_ready_to_post_count, dialog.ids.size)
        },
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )
    ZillitNotice(
        text = str(S.desktop_payroll_posting_locks, dialog.ids.size),
        tone = StatusTone.Escalated,
        icon = ZillitIcons.Lock,
    )
    val names = rows.take(PREVIEW_NAMES).joinToString(", ") { state.nameOf(it.userId) }
    val more = if (rows.size > PREVIEW_NAMES) " + ${rows.size - PREVIEW_NAMES}" else ""
    FigureRow(str(S.desktop_payroll_crew_to_post), names + more)
    FigureRow(str(S.desktop_payroll_combined_cost), combinedCost(state, rows.map { it.slimGross to it.currency }))
}

/** The two fields the server requires: the effective date, after the lock, and the settling account. */
@Composable
private fun PostFields(state: PayrollUiState, dialog: HistoryPost, onEvent: (PayrollEvent) -> Unit) {
    ZillitDateField(
        value = dialog.effectiveDate,
        onValueChange = { onEvent(HistoryEvent.EditPost(effectiveDate = it)) },
        label = str(S.desktop_effective_date_required_label),
        helperText = state.earliestEffectiveDate?.let { str(S.desktop_payroll_earliest_date, it) },
        modifier = Modifier.fillMaxWidth(),
    )
    ZillitSelect(
        value = dialog.bankId,
        options = listOf<String?>(null) + state.bankAccounts.map { it.id },
        onSelect = { onEvent(HistoryEvent.EditPost(bankId = it)) },
        label = { id ->
            state.bankAccounts.firstOrNull { it.id == id }?.let { bank ->
                listOfNotNull(bank.name, bank.holderName, bank.accountNumber).joinToString(" · ")
            } ?: str(S.desktop_payroll_select_bank_account)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.bankAccounts.isEmpty()) {
        ZillitNotice(
            text = str(S.desktop_payroll_no_bank_accounts),
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Warning,
        )
    }
}

/**
 * The batch's gross, in its own currency — or the currencies named when the
 * batch spans several, because a cross-currency sum means nothing (the web's
 * `describeMoneyTotal`).
 */
internal fun combinedCost(state: PayrollUiState, amounts: List<Pair<Double, String?>>): String {
    val codes = amounts.map { it.second ?: state.history.detail?.currency }.distinct()
    return if (codes.size > 1) {
        str(S.desktop_payroll_mixed_currencies, codes.filterNotNull().joinToString(", "))
    } else {
        Money.format(amounts.sumOf { it.first }, codes.firstOrNull())
    }
}

private val DIALOG_WIDTH = 520.dp
private const val PREVIEW_NAMES = 4
