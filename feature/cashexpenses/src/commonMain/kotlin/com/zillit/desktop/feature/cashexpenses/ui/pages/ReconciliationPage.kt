package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FundsAction
import kotlin.math.abs

/**
 * Cash reconciliation — the periods counted so far (`PCCashReconPage.jsx:536-672`).
 *
 * A row opens its count ([ReconEditorPage]), or reads a signed-off period.
 * New Reconciliation asks for the currency, the safe's opening balance and the
 * month first, as the web's modal does, and opens the count once created.
 */
@Composable
fun ReconciliationPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    state.recon?.let {
        ReconEditorPage(state, it, onEvent)
        return
    }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        FixedPage {
            TitledNotice(
                title = str(S.desktop_pc_cash_reconciliation),
                body = str(S.desktop_pc_recon_notice),
                icon = ZillitIcons.Calculator,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = str(S.desktop_pc_reconciliation_periods),
                    style = ZillitTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = str(S.desktop_ce_new_reconciliation),
                    onClick = { creating = true },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                    enabled = !state.busy && state.viewer.isAccountant,
                )
            }
            FundsCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ZillitDataTable(
                    rows = state.reconciliations,
                    columns = reconciliationColumns(state),
                    key = { it.id },
                    loading = state.loading,
                    onRowClick = { onEvent(CashEvent.OpenReconciliation(it.id)) },
                    emptyTitle = str(S.desktop_pc_no_reconciliations),
                    emptyMessage = str(S.desktop_pc_no_reconciliations_hint),
                )
            }
        }
        NewReconciliationDialog(state, visible = creating, onEvent = onEvent, onDismiss = { creating = false })
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun reconciliationColumns(state: CashUiState): List<TableColumn<Reconciliation>> = listOf(
    TableColumn(
        header = str(S.cr_meta_period),
        width = ColumnWidth.Weight(1.1f),
        cell = { row ->
            ZillitText(
                text = monthLabel(CashDates.monthOf(row.periodStart)),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        },
    ),
    TableColumn(
        header = str(S.status),
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { row -> ReconStatusPill(row.status) },
    ),
    textColumn(str(S.desktop_ce_opening_balance), ColumnWidth.Weight(1f), numeric = true) {
        state.formatMoney(it.openingBalance, it.currency)
    },
    textColumn(str(S.desktop_ce_physical_cash), ColumnWidth.Weight(1f), numeric = true) {
        state.formatMoney(it.countedBalance, it.currency)
    },
    TableColumn(
        header = str(S.desktop_variance),
        width = ColumnWidth.Weight(1f),
        numeric = true,
        cell = { row ->
            ZillitText(
                text = varianceText(state, row.variance, row.currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = varianceColor(row.variance),
                maxLines = 1,
            )
        },
    ),
    textColumn(str(S.ah_created_label), ColumnWidth.Weight(1.2f), numeric = true, muted = true) {
        EpochDate.dateTime(it.createdAt).ifEmpty { "—" }
    },
)

/** "Balanced" within a penny, else the signed figure (`PCCashReconPage.jsx:595-597`). */
internal fun varianceText(state: CashUiState, variance: Double, currency: String?): String = when {
    abs(variance) < PENNY -> str(S.desktop_card_balanced)
    variance > 0 -> "+${state.formatMoney(variance, currency)}"
    else -> state.formatMoney(variance, currency)
}

/** Green balanced, amber over, red short. */
@Composable
internal fun varianceColor(variance: Double): Color = when {
    abs(variance) < PENNY -> ZillitTheme.colors.success
    variance > 0 -> ZillitTheme.colors.warning
    else -> ZillitTheme.colors.danger
}

/** The web's New Reconciliation modal: currency, opening safe balance and period, all required. */
@Suppress("LongMethod") // Three required fields and the two buttons.
@Composable
private fun NewReconciliationDialog(
    state: CashUiState,
    visible: Boolean,
    onEvent: (CashEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val months = remember { CashDates.recentMonths() }
    var currency by remember(visible) { mutableStateOf(state.currencies.defaultCode.orEmpty()) }
    var opening by remember(visible) { mutableStateOf("") }
    var month by remember(visible) { mutableStateOf(months.first()) }
    val amount = opening.trim().toDoubleOrNull()
    val codes = (state.currencies.currencies.map { it.code } + listOfNotNull(state.currencies.defaultCode) + currency)
        .filter(String::isNotBlank)
        .distinct()
    val shownCurrency = currency.ifBlank { state.currencies.default }
    ZillitDialogShell(
        title = str(S.desktop_ce_new_reconciliation),
        icon = ZillitIcons.Calculator,
        visible = visible,
        onDismiss = onDismiss,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.cancel), onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = if (state.busy) str(S.desktop_creating) else str(S.desktop_pc_begin_reconciliation),
                onClick = {
                    val (year, number) = month
                    onEvent(CashEvent.Funds(FundsAction.CreateReconciliation(opening, year, number, shownCurrency)))
                },
                enabled = !state.busy && amount != null && amount > 0,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Caption("${str(S.ah_lbl_currency)} *")
            if (codes.isEmpty()) {
                ZillitTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase() },
                    placeholder = str(S.desktop_dm_select_project_currency),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ZillitSelect(
                    value = shownCurrency,
                    options = (codes + shownCurrency).distinct(),
                    onSelect = { currency = it },
                    label = { code ->
                        state.currencies.symbolFor(code).takeIf(String::isNotBlank)?.let { "$code ($it)" } ?: code
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Caption("${str(S.desktop_pc_opening_safe_balance)} *")
            ZillitTextField(
                value = opening,
                onValueChange = { opening = it },
                placeholder = "${com.zillit.desktop.core.common.Money.symbol(shownCurrency)}0.00",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Caption("${str(S.cr_meta_period)} *")
            ZillitSelect(
                value = month,
                options = months,
                onSelect = { month = it },
                label = { monthLabel(it) },
                modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.xs),
            )
        }
    }
}

@Composable
internal fun ReconStatusPill(status: String) {
    ZillitStatusPill(
        label = when (status) {
            ReconDraft.SIGNED_OFF -> str(S.desktop_signed_off)
            ReconDraft.UNDER_REVIEW -> str(S.ah_under_review)
            else -> str(S.draft)
        },
        tone = when (status) {
            ReconDraft.SIGNED_OFF -> StatusTone.Done
            ReconDraft.UNDER_REVIEW -> StatusTone.Progress
            else -> StatusTone.Pending
        },
        dot = true,
    )
}

/** `Sep 2026` — the web's `epochToLabel`. */
internal fun monthLabel(month: Pair<Int, Int>?): String {
    val (year, number) = month ?: return "—"
    return "${MONTHS.getOrElse(number - 1) { "" }} $year".trim()
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Below a penny is a rounding artefact, not a discrepancy worth flagging. */
internal const val PENNY = 0.005

private val STATUS_COLUMN = 140.dp
