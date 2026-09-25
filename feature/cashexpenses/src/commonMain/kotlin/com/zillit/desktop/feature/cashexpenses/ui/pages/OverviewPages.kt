package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.ui.BatchStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.personColumn
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.money

/** The accountant's out-of-pocket dashboard. */
@Suppress("LongMethod") // Tiles, bars and a table read as one screen.
@Composable
fun OutOfPocketOverviewPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val overview = state.outOfPocketOverview
    val currency = overview?.batches?.firstOrNull()?.currency

    ScrollingPage {
        ZillitNotice(
            text = str(S.desktop_ce_oop_intro),
            tone = StatusTone.Progress,
            icon = ZillitIcons.Receipt,
        )

        StatRow(
            listOf(
                StatTileSpec(
                    label = str(S.desktop_ce_pending_claims),
                    value = overview?.pendingClaims?.toString() ?: "—",
                    sub = str(S.desktop_ce_awaiting_routing),
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Receipt,
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_total_claimed),
                    value = money(overview?.totalClaimed, currency),
                    sub = str(S.desktop_ce_across_open_claims),
                    icon = ZillitIcons.BarChart,
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_bacs_ready),
                    value = money(overview?.bacsReady, currency),
                    sub = str(S.desktop_ce_approved_awaiting_file),
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Bank,
                    onClick = { onEvent(CashEvent.Open(CashDestination.PaymentRouting)) },
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_payroll_additions),
                    value = money(overview?.payrollAuto, currency),
                    sub = str(S.desktop_ce_routed_to_next_run),
                    tone = StatusTone.Done,
                    icon = ZillitIcons.Users,
                ),
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = str(S.desktop_ce_routing_split),
                icon = ZillitIcons.Bank,
                modifier = Modifier.weight(1f),
            ) {
                val routing = overview?.routing
                val total = routing?.total?.takeIf { it > 0 } ?: 1.0
                RoutingBar("BACS", routing?.bacs ?: 0.0, total, currency, StatusTone.Progress)
                RoutingBar(str(S.dm_step9_title), routing?.payroll ?: 0.0, total, currency, StatusTone.Done)
            }

            ZillitSectionCard(
                title = str(S.desktop_ce_spend_by_category),
                icon = ZillitIcons.BarChart,
                modifier = Modifier.weight(1f),
            ) {
                val rows = overview?.spendByCategory.orEmpty()
                if (rows.isEmpty()) {
                    ZillitEmptyState(
                        title = str(S.desktop_ce_nothing_claimed_yet),
                        message = str(S.desktop_ce_categories_appear),
                    )
                } else {
                    val max = rows.maxOf { it.amount }.takeIf { it > 0 } ?: 1.0
                    rows.forEach { row ->
                        RoutingBar(row.category, row.amount, max, currency, StatusTone.Escalated)
                    }
                }
            }
        }

        ZillitSectionCard(title = str(S.desktop_ce_claims), icon = ZillitIcons.Receipt, padded = false) {
            ZillitDataTable(
                rows = overview?.batches.orEmpty(),
                columns = batchColumns(state.viewer.isAccountant),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_ce_no_oop_claims),
                emptyMessage = str(S.desktop_ce_claims_empty),
                onRowClick = { onEvent(CashEvent.SelectBatch(it.id)) },
                isSelected = { it.id == state.selectedBatchId },
                virtualised = false,
            )
        }
    }
}

// -- shared pieces -----------------------------------------------------------

@Composable
private fun RoutingBar(
    label: String,
    amount: Double,
    total: Double,
    currency: String?,
    tone: StatusTone,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = label,
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitText(text = money(amount, currency), style = ZillitTheme.typography.numeric, maxLines = 1)
        }
        ZillitMeter(fraction = (amount / total).toFloat(), tone = tone)
    }
}

/**
 * The float register's columns, shared by every screen that lists floats.
 *
 * [compact] is for a list that sits beside a detail pane: it keeps whose float
 * it is, what is left and what state it is in, and drops the three columns the
 * pane beside it already shows. A full column set in half a window does not
 * shrink — it scrolls sideways and hides the status, which is the one column
 * the reader is scanning for.
 */
@Suppress("MagicNumber") // Column proportions; naming each would not clarify them.
fun floatColumns(compact: Boolean = false): List<TableColumn<CashFloat>> = buildList {
    add(personColumn(str(S.ah_holder), ColumnWidth.Weight(1.6f), userId = { it.userId }) { it.holderName })
    add(textColumn(str(S.desktop_reference), ColumnWidth.Weight(1f), muted = true) { it.requestNumber.ifBlank { "—" } })
    if (!compact) {
        add(
            textColumn(str(S.desktop_issued), ColumnWidth.Weight(1f), numeric = true) {
                money(it.issuedAmount, it.currency)
            },
        )
    }
    add(textColumn(str(S.ah_balance_label), ColumnWidth.Weight(1f), numeric = true) { money(it.balance, it.currency) })
    if (!compact) {
        add(
            TableColumn(
                header = str(S.desktop_ce_consumed),
                width = ColumnWidth.Fixed(METER_COLUMN),
                cell = { row ->
                    ZillitMeter(
                        fraction = row.consumedFraction,
                        tone = if (row.consumedFraction > NEARLY_SPENT) StatusTone.Rejected else StatusTone.Ready,
                        modifier = Modifier.width(METER_WIDTH),
                    )
                },
            ),
        )
        add(textColumn(str(S.av_chip_requested), ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) })
    }
    add(
        TableColumn(
            header = str(S.status),
            width = ColumnWidth.Fixed(STATUS_COLUMN),
            cell = { FloatStatusPill(it.status) },
        ),
    )
}

/** The batch table's columns. Identical everywhere a batch is listed. */
@Suppress("MagicNumber") // Column proportions; naming each would not clarify them.
fun batchColumns(accountant: Boolean, compact: Boolean = false): List<TableColumn<ClaimBatch>> = buildList {
    add(
        textColumn(str(S.desktop_reference), ColumnWidth.Weight(1.2f)) {
            it.reference.ifBlank { it.id.take(REF_FALLBACK) }
        },
    )
    add(
        personColumn(str(S.desktop_ce_submitted_by), ColumnWidth.Weight(1.6f), userId = { it.userId }) {
            it.holderName
        },
    )
    if (!compact) {
        add(
            textColumn(str(S.ah_receipts_label), ColumnWidth.Fixed(COUNT_COLUMN), numeric = true) {
                it.claimCount.toString()
            },
        )
    }
    add(textColumn(str(S.ah_total_label), ColumnWidth.Weight(1f), numeric = true) { money(it.totalGross, it.currency) })
    if (!compact) {
        add(textColumn(str(S.txt_submitted), ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) })
    }
    add(
        TableColumn(
            header = str(S.status),
            width = ColumnWidth.Fixed(STATUS_COLUMN),
            cell = { BatchStatusPill(it.status, accountant) },
        ),
    )
}

/** Compact money, for tiles where the pennies are noise. */
internal fun compactMoney(amount: Double?, currency: String?): String = Money.compact(amount, currency)

private const val NEARLY_SPENT = 0.85f
private const val REF_FALLBACK = 8
private val METER_COLUMN = 110.dp
private val METER_WIDTH = 90.dp
private val STATUS_COLUMN = 150.dp
private val COUNT_COLUMN = 70.dp
