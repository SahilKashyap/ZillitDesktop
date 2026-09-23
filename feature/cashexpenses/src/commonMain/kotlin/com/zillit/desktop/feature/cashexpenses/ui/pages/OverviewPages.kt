package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
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
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.personColumn
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.money

/**
 * The accountant's petty-cash dashboard.
 *
 * Built around *work waiting to be done* rather than around totals: the tiles
 * are counts of things in a queue, and each is a link into that queue. A
 * dashboard of figures with no way to act on them is a screen people look at
 * once.
 */
@Suppress("LongMethod") // A dashboard: tiles, notices and tables read as one screen.
@Composable
fun PettyCashOverviewPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val overview = state.pettyCashOverview
    val currency = overview?.floats?.firstOrNull()?.currency

    ScrollingPage {
        StatRow(
            listOf(
                StatTileSpec(
                    label = str(S.desktop_ce_active_floats),
                    value = overview?.stats?.activeFloats?.toString() ?: "—",
                    sub = str(S.desktop_ce_cash_out_with_crew),
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Wallet,
                    onClick = { onEvent(CashEvent.Open(CashDestination.ActiveFloats)) },
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_awaiting_audit),
                    value = overview?.stats?.awaitingAudit?.toString() ?: "—",
                    sub = str(S.desktop_ce_tax_extraction_pending),
                    tone = StatusTone.Progress,
                    icon = ZillitIcons.Receipt,
                    onClick = { onEvent(CashEvent.Open(CashDestination.AuditQueue)) },
                ),
                StatTileSpec(
                    label = str(S.av_subtab_awaiting_approval),
                    value = overview?.stats?.awaitingApproval?.toString() ?: "—",
                    sub = str(S.desktop_ce_with_the_approvers),
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Shield,
                    onClick = { onEvent(CashEvent.Open(CashDestination.ApprovalQueue)) },
                ),
                StatTileSpec(
                    label = str(S.ah_ready_to_post),
                    value = overview?.stats?.readyToPost?.toString() ?: "—",
                    sub = str(S.desktop_ce_amount_to_ledger, money(overview?.stats?.readyToPostAmount, currency)),
                    tone = StatusTone.Done,
                    icon = ZillitIcons.Ledger,
                    onClick = { onEvent(CashEvent.Open(CashDestination.PostLedger)) },
                ),
            ),
        )

        if ((overview?.stats?.escalated ?: 0) > 0) {
            ZillitNotice(
                text = str(S.desktop_ce_escalated_count, overview?.stats?.escalated ?: 0),
                tone = StatusTone.Escalated,
                icon = ZillitIcons.Shield,
                action = {
                    ZillitButton(
                        text = str(S.desktop_ce_open_sign_off),
                        onClick = { onEvent(CashEvent.Open(CashDestination.PettyCashSignOff)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                },
            )
        }

        if ((overview?.summary?.oopBacsQueued ?: 0.0) > 0) {
            ZillitNotice(
                text = str(
                    S.desktop_ce_bacs_not_generated,
                    money(overview?.summary?.oopBacsQueued, currency),
                ),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Bank,
                action = {
                    ZillitButton(
                        text = str(S.desktop_ce_payment_routing),
                        onClick = { onEvent(CashEvent.Open(CashDestination.PaymentRouting)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = str(S.desktop_ce_cash_position),
                icon = ZillitIcons.Bank,
                modifier = Modifier.weight(1f),
            ) {
                SummaryLine(
                    str(S.desktop_ce_total_petty_cash_issued),
                    money(overview?.summary?.totalPettyCashIssued, currency),
                )
                SummaryLine(
                    str(S.desktop_ce_receipts_approved),
                    money(overview?.summary?.totalReceiptsApproved, currency),
                )
                SummaryLine(str(S.desktop_ce_cash_to_account_for), money(overview?.summary?.cashToAccount, currency))
                SummaryLine(str(S.desktop_ce_vat_recoverable), money(overview?.summary?.vatRecoverable, currency))
                SummaryLine(str(S.desktop_ce_oop_bacs_queued), money(overview?.summary?.oopBacsQueued, currency))
                SummaryLine(str(S.desktop_ce_oop_payroll), money(overview?.summary?.oopPayrollAdditions, currency))
            }

            ZillitSectionCard(
                title = str(S.desktop_outstanding),
                icon = ZillitIcons.Wallet,
                meta = str(S.desktop_ce_floats_count, overview?.floats?.size ?: 0),
                modifier = Modifier.weight(1f),
            ) {
                ZillitText(
                    text = money(overview?.stats?.totalOutstanding, currency),
                    style = ZillitTheme.typography.displayLarge,
                )
                ZillitText(
                    text = str(S.desktop_ce_cash_not_accounted),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                Spacer(Modifier.padding(ZillitTheme.spacing.xs))
                overview?.floats.orEmpty().take(TOP_FLOATS).forEach { row ->
                    FloatSummaryRow(row)
                }
            }
        }

        ZillitSectionCard(title = str(S.desktop_ce_floats_on_project), icon = ZillitIcons.Users, padded = false) {
            ZillitDataTable(
                rows = overview?.floats.orEmpty(),
                columns = floatColumns(),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_ce_no_floats_issued),
                emptyMessage = str(S.desktop_ce_floats_empty),
                onRowClick = { onEvent(CashEvent.Open(CashDestination.ActiveFloats)) },
                virtualised = false,
            )
        }
    }
}

/** The accountant's out-of-pocket dashboard. */
@Suppress("LongMethod") // As the petty-cash dashboard above.
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

/**
 * What one crew member sees about their own cash.
 *
 * The float meter is the first thing on the page because it answers the
 * question they opened the tool with — how much have I got left.
 */
@Suppress("LongMethod") // The float, what came back, and both receipt lists.
@Composable
fun MyOverviewPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val overview = state.myOverview
    val activeFloat = overview?.floats?.firstOrNull { it.status.isOutstanding }
        ?: overview?.floats?.firstOrNull()

    ScrollingPage {
        if (activeFloat != null) {
            ZillitSectionCard(
                title = str(S.desktop_ce_your_float),
                icon = ZillitIcons.Wallet,
                meta = activeFloat.requestNumber,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(
                            text = money(activeFloat.balance, activeFloat.currency),
                            style = ZillitTheme.typography.displayLarge,
                        )
                        ZillitText(
                            text = str(
                                S.desktop_ce_left_of_issued,
                                money(activeFloat.issuedAmount, activeFloat.currency),
                            ),
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                    FloatStatusPill(activeFloat.status)
                }
                Spacer(Modifier.padding(ZillitTheme.spacing.xs))
                ZillitMeter(
                    fraction = activeFloat.consumedFraction,
                    tone = if (activeFloat.consumedFraction > NEARLY_SPENT) {
                        StatusTone.Rejected
                    } else {
                        StatusTone.Ready
                    },
                )
                Spacer(Modifier.padding(ZillitTheme.spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitButton(
                        text = str(S.desktop_ce_submit_receipts),
                        onClick = { onEvent(CashEvent.Open(CashDestination.SubmitReceipts)) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                        enabled = activeFloat.status.isSubmittable,
                    )
                    ZillitButton(
                        text = str(S.desktop_ce_ask_for_more_cash),
                        onClick = { onEvent(CashEvent.Open(CashDestination.CashExtension)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            }
        } else {
            ZillitNotice(
                text = str(S.desktop_ce_no_active_float),
                tone = StatusTone.Progress,
                action = {
                    ZillitButton(
                        text = str(S.ah_request_a_float),
                        onClick = { onEvent(CashEvent.Open(CashDestination.FloatRequest)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                },
            )
        }

        val needsAction = (overview?.pettyCashClaims.orEmpty() + overview?.outOfPocketClaims.orEmpty())
            .filter { it.status.needsSubmitterAction }
        if (needsAction.isNotEmpty()) {
            ZillitNotice(
                text = str(S.desktop_ce_your_batches_came_back, needsAction.size),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitSectionCard(title = str(S.desktop_ce_petty_cash_receipts), icon = ZillitIcons.Receipt, padded = false) {
            ZillitDataTable(
                rows = overview?.pettyCashClaims.orEmpty(),
                columns = batchColumns(accountant = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_ce_nothing_submitted_yet),
                emptyMessage = str(S.desktop_ce_your_receipts_empty),
                virtualised = false,
            )
        }

        ZillitSectionCard(title = str(S.desktop_ce_out_of_pocket), icon = ZillitIcons.Wallet, padded = false) {
            ZillitDataTable(
                rows = overview?.outOfPocketClaims.orEmpty(),
                columns = batchColumns(accountant = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_ce_nothing_claimed),
                emptyMessage = str(S.desktop_ce_your_claims_empty),
                virtualised = false,
            )
        }
    }
}

/** A coordinator's view of their department's cash. */
@Composable
fun DepartmentOverviewPage(state: CashUiState) {
    val overview = state.departmentOverview
    ScrollingPage {
        StatRow(
            listOf(
                StatTileSpec(
                    label = str(S.desktop_ce_issued_to_department),
                    value = money(overview?.totalIssued, null),
                    icon = ZillitIcons.Wallet,
                ),
                StatTileSpec(
                    label = str(S.ah_spent_label),
                    value = money(overview?.totalSpent, null),
                    tone = StatusTone.Progress,
                    icon = ZillitIcons.Receipt,
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_floats),
                    value = overview?.floats?.size?.toString() ?: "—",
                    icon = ZillitIcons.Users,
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_batches),
                    value = overview?.batches?.size?.toString() ?: "—",
                    icon = ZillitIcons.Ledger,
                ),
            ),
        )

        ZillitSectionCard(title = str(S.desktop_ce_department_floats), icon = ZillitIcons.Wallet, padded = false) {
            ZillitDataTable(
                rows = overview?.floats.orEmpty(),
                columns = floatColumns(),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_ce_no_floats_in_department),
                virtualised = false,
            )
        }

        ZillitSectionCard(title = str(S.desktop_ce_department_batches), icon = ZillitIcons.Receipt, padded = false) {
            ZillitDataTable(
                rows = overview?.batches.orEmpty(),
                columns = batchColumns(accountant = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_ce_no_batches_in_department),
                virtualised = false,
            )
        }
    }
}

// -- shared pieces -----------------------------------------------------------

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = value, style = ZillitTheme.typography.numeric, maxLines = 1)
    }
}

@Composable
private fun FloatSummaryRow(row: CashFloat) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        CashPerson(
            userId = row.userId,
            recordedName = row.holderName,
            // The reference under the name, as the web's overview row carries
            // it: it is what the float register is searched by, and it still
            // identifies the float when its holder has left the crew list.
            secondary = row.requestNumber.takeIf { it.isNotBlank() },
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = money(row.balance, row.currency),
            style = ZillitTheme.typography.numeric,
            maxLines = 1,
        )
        FloatStatusPill(row.status)
    }
}

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

private const val TOP_FLOATS = 5
private const val NEARLY_SPENT = 0.85f
private const val REF_FALLBACK = 8
private val METER_COLUMN = 110.dp
private val METER_WIDTH = 90.dp
private val STATUS_COLUMN = 150.dp
private val COUNT_COLUMN = 70.dp
