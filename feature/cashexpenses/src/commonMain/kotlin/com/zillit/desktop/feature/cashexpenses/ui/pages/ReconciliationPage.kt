package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.money
import kotlin.math.abs

/**
 * Cash reconciliation — the periods counted so far, and a new one.
 *
 * A row opens its count ([ReconEditorPage]); New Reconciliation asks for the
 * safe's opening balance, the month and the currency first, as the web does
 * (`PCCashReconPage.jsx:293-330`). The old one-field "counted balance"
 * dialog sent `{counted_balance, note}`, which the route does not read.
 */
@Suppress("LongMethod") // Tiles, a notice and the register, read together.
@Composable
fun ReconciliationPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    state.recon?.let {
        ReconEditorPage(state, it, onEvent)
        return
    }
    val latest = state.reconciliations.firstOrNull()
    val book = state.bookBalance

    FixedPage {
        StatRow(
            listOf(
                StatTileSpec(
                    label = str(S.desktop_ce_book_balance),
                    value = money(book, null),
                    sub = str(S.desktop_ce_what_ledger_expects),
                    icon = ZillitIcons.Ledger,
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_last_counted),
                    value = money(latest?.countedBalance, latest?.currency),
                    sub = latest?.let { date(it.createdAt) } ?: str(S.desktop_ce_never_counted),
                    icon = ZillitIcons.Wallet,
                ),
                StatTileSpec(
                    label = str(S.desktop_variance),
                    value = money(latest?.variance, latest?.currency),
                    sub = if (latest == null) {
                        str(S.desktop_ce_start_a_count)
                    } else if (abs(latest.variance) < PENNY) {
                        str(S.desktop_card_balanced)
                    } else {
                        str(S.desktop_ce_needs_explaining)
                    },
                    tone = when {
                        latest == null -> null
                        abs(latest.variance) < PENNY -> StatusTone.Done
                        else -> StatusTone.Rejected
                    },
                    icon = ZillitIcons.Warning,
                ),
            ),
        )

        if (book == null) {
            ZillitNotice(
                text = str(S.desktop_ce_no_ledger_balance),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitSectionCard(
            title = str(S.desktop_ce_reconciliations),
            icon = ZillitIcons.Bank,
            padded = false,
            modifier = Modifier.weight(1f),
            action = {
                ZillitButton(
                    text = str(S.desktop_ce_new_reconciliation),
                    onClick = {
                        val (year, month) = CashDates.recentMonths(1).first()
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.NewReconciliation(
                                    year = year,
                                    month = month,
                                    currency = state.reconciliations.firstNotNullOfOrNull { it.currency }
                                        ?: state.activeFloats.firstNotNullOfOrNull { it.currency }
                                        .orEmpty(),
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                    enabled = !state.busy,
                )
            },
        ) {
            ZillitDataTable(
                rows = state.reconciliations,
                columns = reconciliationColumns(),
                key = { it.id },
                loading = state.loading,
                onRowClick = { onEvent(CashEvent.OpenReconciliation(it.id)) },
                emptyTitle = str(S.desktop_ce_no_counts_recorded),
                emptyMessage = str(S.desktop_ce_record_count_hint),
            )
        }
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun reconciliationColumns(): List<TableColumn<Reconciliation>> = listOf(
    textColumn(str(S.cr_meta_period), ColumnWidth.Weight(1.1f)) { monthLabel(CashDates.monthOf(it.periodStart)) },
    textColumn(str(S.desktop_ce_opening_balance), ColumnWidth.Weight(1f), numeric = true) {
        money(it.openingBalance, it.currency)
    },
    textColumn(str(S.desktop_ce_counted), ColumnWidth.Weight(1f), numeric = true) {
        money(it.countedBalance, it.currency)
    },
    TableColumn(
        header = str(S.desktop_variance),
        width = ColumnWidth.Weight(1f),
        numeric = true,
        cell = { row ->
            ZillitText(
                text = money(row.variance, row.currency),
                style = ZillitTheme.typography.numeric,
                color = if (abs(row.variance) < PENNY) ZillitTheme.colors.success else ZillitTheme.colors.danger,
                maxLines = 1,
            )
        },
    ),
    textColumn(str(S.desktop_card_raised), ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) },
    TableColumn(
        header = str(S.status),
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { row -> ReconStatusPill(row.status) },
    ),
)

@Composable
internal fun ReconStatusPill(status: String) {
    ZillitStatusPill(
        label = when (status) {
            ReconDraft.SIGNED_OFF -> str(S.desktop_br_signed_off)
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

private val STATUS_COLUMN = 130.dp
