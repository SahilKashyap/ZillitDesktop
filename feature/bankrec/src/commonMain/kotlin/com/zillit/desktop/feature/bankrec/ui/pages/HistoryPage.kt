package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.PeriodScope
import com.zillit.desktop.feature.bankrec.ui.components.BrAlign
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrBankIdentity
import com.zillit.desktop.feature.bankrec.ui.components.BrCard
import com.zillit.desktop.feature.bankrec.ui.components.BrColumn
import com.zillit.desktop.feature.bankrec.ui.components.BrEmpty
import com.zillit.desktop.feature.bankrec.ui.components.BrSkeletonRows
import com.zillit.desktop.feature.bankrec.ui.components.BrTable
import com.zillit.desktop.feature.bankrec.ui.components.mono

/**
 * Every period one bank account has reconciled — the record, where Overview is
 * the state of play.
 *
 * Balances are the statement's, so each is shown in its account's currency; a
 * period has no currency of its own. The selection is scoped to the account on
 * screen, because select-all reaching periods of accounts not shown would
 * delete what nobody was looking at.
 */
@Suppress("LongMethod") // The web's history table, its filter and its bulk bar.
@Composable
fun ColumnScope.HistoryPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val selected = state.historySelection
    ActionRow {
        if (selected.isEmpty() && state.bankAccounts.isNotEmpty()) {
            ZillitSelect(
                value = state.historyAccountId,
                options = state.bankAccounts.map { it.id },
                onSelect = { onEvent(BankRecEvent.SetHistoryAccount(it)) },
                label = { id -> state.account(id)?.displayName?.ifBlank { null } ?: str(S.dm_pay_card_bank) },
                modifier = Modifier.widthIn(min = 200.dp, max = 300.dp),
            )
        }
        SelectionActions(state, selected, PeriodScope.History, onEvent, exportLabel = str(S.recce_export_pdf))
    }

    BrCard(Modifier.fillMaxWidth()) {
        if (state.periodsLoading) {
            BrSkeletonRows(6)
            return@BrCard
        }
        val rows = state.historyPeriods
        val selecting = selected.isNotEmpty()
        BrTable(
            rows = rows,
            key = { it.id },
            isSelected = { it.id in selected },
            onRowClick = { period ->
                when {
                    selecting -> if (period.isDeletable) {
                        onEvent(BankRecEvent.TogglePeriodSelection(PeriodScope.History, period.id))
                    }

                    period.isOpen -> onEvent(BankRecEvent.OpenPeriod(period.id))
                    else -> onEvent(BankRecEvent.OpenPeriodDetail(period.id))
                }
            },
            empty = { BrEmpty(title = str(S.desktop_br_no_periods_found), icon = ZillitIcons.Clock) },
            columns = listOf(
                selectColumn(rows, selected, PeriodScope.History, onEvent),
                BrColumn(str(S.bs_month), width = 84.dp) { period ->
                    ZillitText(BankRecFormat.periodLabel(period), style = mono(13.sp, FontWeight.Medium), maxLines = 1)
                },
                BrColumn(str(S.ah_account_label), weight = 1.3f) { period ->
                    val account = state.account(period.bankAccountId)
                    BrBankIdentity(
                        name = account?.displayName.orEmpty(),
                        sortCode = account?.sortCode.orEmpty(),
                        accountNumber = account?.accountNumber.orEmpty(),
                        compact = true,
                    )
                },
                BrColumn(str(S.desktop_br_opening_column), width = 108.dp, align = BrAlign.End) { period ->
                    ZillitText(
                        BankRecFormat.plainMoney(period.openingBank, state.currencyOf(period)),
                        style = mono(12.5.sp),
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                },
                BrColumn(str(S.desktop_br_closing_column), width = 108.dp, align = BrAlign.End) { period ->
                    ZillitText(
                        BankRecFormat.plainMoney(period.closingBank, state.currencyOf(period)),
                        style = mono(12.5.sp),
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                },
                BrColumn(str(S.desktop_txns), width = 44.dp, align = BrAlign.End) { period ->
                    ZillitText(
                        period.totalTxns.toString(),
                        style = mono(12.5.sp),
                        color = ZillitTheme.colors.textSecondary,
                    )
                },
                BrColumn(str(S.desktop_fraud), width = 76.dp) { period ->
                    val (label, tone) = period.fraudBadge
                    BrBadge(label, tone)
                },
                BrColumn(str(S.desktop_br_fx_var), width = 88.dp, align = BrAlign.End) { period ->
                    ZillitText(
                        BankRecFormat.plainMoney(period.fxVarianceTotal, state.currencyOf(period)),
                        style = mono(12.5.sp),
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
                },
                BrColumn(str(S.status), width = 96.dp) { period -> BrBadge(period.status.label, period.status.tone) },
                BrColumn(str(S.desktop_signed_off_caps), weight = 1.2f) { period -> SignedOffCell(period) },
                BrColumn(str(S.dd_actions), width = 136.dp, align = BrAlign.End) { period ->
                    if (!selecting) RowActions(period, onEvent, viewOpensDetail = true)
                },
            ),
        )
    }
}
