package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.money

/**
 * Payment Routing — approved reimbursements, split between a BACS run and the
 * next payroll (`OOPPaymentPage.jsx`).
 *
 * The figures are the route's `stats` and the rows its two lists; this read
 * `bacs`/`payroll`/`total`, which the route does not send, so every tile said
 * zero and the table was empty.
 */
@Suppress("LongMethod") // Tiles and the two rails, read together.
@Composable
fun PaymentRoutingPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val routing = state.paymentRouting
    val currency = (routing?.bacsBatches.orEmpty() + routing?.payrollBatches.orEmpty()).firstOrNull()?.currency
    FixedPage {
        StatRow(
            listOf(
                StatTileSpec(
                    label = str(S.desktop_ce_bacs_ready),
                    value = money(routing?.bacsReady, currency),
                    sub = str(S.desktop_ce_claims_ready_to_generate, routing?.bacsCount ?: 0),
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Bank,
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_payroll_additions),
                    value = money(routing?.payrollTotal, currency),
                    sub = str(S.desktop_ce_claims_count, routing?.payrollCount ?: 0),
                    tone = StatusTone.Progress,
                    icon = ZillitIcons.Users,
                ),
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = str(S.desktop_ce_bacs_payments),
                icon = ZillitIcons.Bank,
                meta = str(S.desktop_ce_claims_count, routing?.bacsBatches?.size ?: 0),
                padded = false,
                modifier = Modifier.weight(1f),
            ) {
                ZillitDataTable(
                    rows = routing?.bacsBatches.orEmpty(),
                    columns = batchColumns(
                        accountant = true,
                        compact = true,
                        amount = { it.reimbursementAmount },
                        extra = listOf(payeeColumn()),
                    ),
                    key = { it.id },
                    loading = state.loading,
                    emptyTitle = str(S.desktop_ce_nothing_waiting_to_pay),
                    emptyMessage = str(S.desktop_ce_routed_claims_empty),
                )
            }
            ZillitSectionCard(
                title = str(S.desktop_ce_payroll_additions),
                icon = ZillitIcons.Users,
                meta = str(S.desktop_ce_claims_count, routing?.payrollBatches?.size ?: 0),
                padded = false,
                modifier = Modifier.weight(1f),
            ) {
                ZillitDataTable(
                    rows = routing?.payrollBatches.orEmpty(),
                    columns = batchColumns(accountant = true, compact = true, amount = { it.reimbursementAmount }),
                    key = { it.id },
                    loading = state.loading,
                    emptyTitle = str(S.desktop_ce_nothing_waiting_to_pay),
                    emptyMessage = str(S.desktop_ce_routed_claims_empty),
                    modifier = Modifier.weight(1f),
                )
                PayrollFooter(
                    total = money(routing?.payrollTotal, currency),
                    count = routing?.payrollCount ?: 0,
                    onPreview = { onEvent(CashEvent.OpenPayrollRun) },
                )
            }
        }
    }
}

/** The payroll card's footer: what is going into the next run, and a way to the run itself. */
@Composable
private fun PayrollFooter(total: String, count: Int, onPreview: () -> Unit) {
    ZillitDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = total, style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = if (count == 1) {
                    str(S.desktop_ce_additions_one, count)
                } else {
                    str(S.desktop_ce_additions_other, count)
                },
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitButton(
            text = str(S.desktop_ce_preview_payroll_run),
            onClick = onPreview,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Eye,
        )
    }
}

/**
 * Who a BACS claim is paid to: the account name over its sort code and number,
 * as the web's `BankIdentity` shows them under the amount. A claim with no
 * account on it reads a dash — the run cannot pay it until one is added.
 */
private fun payeeColumn(): TableColumn<ClaimBatch> = TableColumn(
    header = str(S.payee_label),
    width = ColumnWidth.Weight(PAYEE_WEIGHT),
    cell = { batch ->
        val payee = batch.payee
        if (payee == null) {
            ZillitText(text = "—", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        } else {
            Column {
                if (payee.accountName.isNotEmpty()) {
                    ZillitText(text = payee.accountName, style = ZillitTheme.typography.bodySmall, maxLines = 1)
                }
                if (payee.sortAndAccount.isNotEmpty()) {
                    ZillitText(
                        text = payee.sortAndAccount,
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    },
)

private const val PAYEE_WEIGHT = 1.4f
