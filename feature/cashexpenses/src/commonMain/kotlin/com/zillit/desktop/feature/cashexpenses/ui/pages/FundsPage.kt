package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.FundRequest
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.FundsState
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.money
import com.zillit.desktop.feature.cashexpenses.ui.personColumn

/**
 * Fund Requests — money asked for into the float custodian
 * (`RequestCashFundsModal.jsx`), opened from Active Floats' Funds button.
 *
 * Raising a request records nothing in the ledger; marking it received is
 * what posts. The list on the left, the new request on the right, as the web
 * lays it out.
 */
@Suppress("LongMethod") // The list and the form, read together.
@Composable
fun FundsPage(state: CashUiState, funds: FundsState, onEvent: (CashEvent) -> Unit) {
    val open = funds.requests.filter { it.isOpen }
    FixedPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitButton(
                text = str(S.desktop_ce_active_floats),
                onClick = { onEvent(CashEvent.ShowFunds(false)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            ZillitText(
                text = str(S.desktop_ce_funds),
                style = ZillitTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        ZillitNotice(text = str(S.desktop_ce_funds_intro), tone = StatusTone.Progress, icon = ZillitIcons.Info)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = str(S.desktop_ce_fund_requests),
                icon = ZillitIcons.Bank,
                meta = str(
                    S.desktop_ce_funds_outstanding,
                    money(open.sumOf { it.amount }, open.firstOrNull()?.currency),
                ),
                padded = false,
                modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = funds.requests,
                    columns = fundColumns(state, onEvent),
                    key = { it.id },
                    loading = funds.loading,
                    emptyTitle = str(S.desktop_ce_no_fund_requests),
                )
            }
            ZillitSectionCard(
                title = str(S.desktop_ce_new_fund_request),
                icon = ZillitIcons.Add,
                modifier = Modifier.weight(1f),
            ) {
                // One custodian for petty cash, from settings; the form shows
                // it rather than offering a picker (the web's `soleCustodian`).
                ZillitTextField(
                    value = funds.fundAccount,
                    onValueChange = { onEvent(CashEvent.EditFunds(funds.copy(fundAccount = it))) },
                    label = str(S.desktop_ce_float_custodian_account),
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitTextField(
                    value = funds.currency,
                    onValueChange = { onEvent(CashEvent.EditFunds(funds.copy(currency = it.uppercase()))) },
                    label = str(S.ah_lbl_currency),
                    placeholder = "GBP",
                    modifier = Modifier.width(CURRENCY_WIDTH),
                )
                ZillitTextField(
                    value = funds.amount,
                    onValueChange = { onEvent(CashEvent.EditFunds(funds.copy(amount = it))) },
                    label = str(S.amount),
                    placeholder = "0.00",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitButton(
                    text = str(S.desktop_ce_request_funds),
                    onClick = { onEvent(CashEvent.SubmitFunds) },
                    leadingIcon = ZillitIcons.Send,
                    enabled = !state.busy && funds.fundAccount.isNotBlank() &&
                        (funds.amount.trim().toDoubleOrNull() ?: 0.0) > 0,
                )
            }
        }
    }
}

@Suppress("MagicNumber", "LongMethod") // Column proportions, and the row's two actions.
private fun fundColumns(state: CashUiState, onEvent: (CashEvent) -> Unit): List<TableColumn<FundRequest>> = listOf(
    textColumn(str(S.desktop_ce_fund_account), ColumnWidth.Weight(1f)) { it.fundAccount.ifBlank { "—" } },
    textColumn(str(S.amount), ColumnWidth.Weight(1f), numeric = true) {
        money(it.receivedAmount ?: it.amount, it.currency)
    },
    personColumn(str(S.desktop_ce_requested_by), ColumnWidth.Weight(1.4f), userId = { it.requestedBy }),
    textColumn(str(S.desktop_card_raised), ColumnWidth.Weight(1f), muted = true) { date(it.requestedAt) },
    TableColumn(
        header = str(S.status),
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { row ->
            ZillitStatusPill(
                label = when (row.status) {
                    FundRequest.RECEIVED -> str(S.received_text)
                    FundRequest.CANCELLED -> str(S.cancelled)
                    else -> str(S.av_chip_requested)
                },
                tone = when (row.status) {
                    FundRequest.RECEIVED -> StatusTone.Done
                    FundRequest.CANCELLED -> StatusTone.Neutral
                    else -> StatusTone.Pending
                },
                dot = true,
            )
        },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTION_COLUMN),
        cell = { row ->
            if (row.isOpen) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitButton(
                        text = str(S.desktop_ce_mark_received),
                        onClick = {
                            onEvent(
                                CashEvent.Ask(
                                    CashPrompt.Confirm(
                                        ConfirmAction.ReceiveFunds,
                                        row.id,
                                        str(S.desktop_ce_mark_received),
                                        str(S.desktop_ce_mark_received_note, money(row.amount, row.currency)),
                                    ),
                                ),
                            )
                        },
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = str(S.cancel),
                        onClick = {
                            onEvent(
                                CashEvent.Ask(
                                    CashPrompt.Confirm(
                                        ConfirmAction.CancelFunds,
                                        row.id,
                                        str(S.desktop_ce_cancel_fund_request),
                                        str(S.desktop_ce_cancel_fund_request_note),
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                }
            } else {
                Column { ZillitText(text = "—", color = ZillitTheme.colors.textMuted) }
            }
        },
    ),
)

private const val LIST_WEIGHT = 1.6f
private val CURRENCY_WIDTH = 140.dp
private val STATUS_COLUMN = 120.dp
private val ACTION_COLUMN = 210.dp
