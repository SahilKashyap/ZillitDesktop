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
                    label = "Active floats",
                    value = overview?.stats?.activeFloats?.toString() ?: "—",
                    sub = "Cash out with crew",
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Wallet,
                    onClick = { onEvent(CashEvent.Open(CashDestination.ActiveFloats)) },
                ),
                StatTileSpec(
                    label = "Awaiting audit",
                    value = overview?.stats?.awaitingAudit?.toString() ?: "—",
                    sub = "Tax extraction pending",
                    tone = StatusTone.Progress,
                    icon = ZillitIcons.Receipt,
                    onClick = { onEvent(CashEvent.Open(CashDestination.AuditQueue)) },
                ),
                StatTileSpec(
                    label = "Awaiting approval",
                    value = overview?.stats?.awaitingApproval?.toString() ?: "—",
                    sub = "With the approvers",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Shield,
                    onClick = { onEvent(CashEvent.Open(CashDestination.ApprovalQueue)) },
                ),
                StatTileSpec(
                    label = "Ready to post",
                    value = overview?.stats?.readyToPost?.toString() ?: "—",
                    sub = money(overview?.stats?.readyToPostAmount, currency) + " to ledger",
                    tone = StatusTone.Done,
                    icon = ZillitIcons.Ledger,
                    onClick = { onEvent(CashEvent.Open(CashDestination.PostLedger)) },
                ),
            ),
        )

        if ((overview?.stats?.escalated ?: 0) > 0) {
            ZillitNotice(
                text = "${overview?.stats?.escalated} batch(es) escalated for senior sign-off.",
                tone = StatusTone.Escalated,
                icon = ZillitIcons.Shield,
                action = {
                    ZillitButton(
                        text = "Open sign-off",
                        onClick = { onEvent(CashEvent.Open(CashDestination.PettyCashSignOff)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                },
            )
        }

        if ((overview?.summary?.oopBacsQueued ?: 0.0) > 0) {
            ZillitNotice(
                text = "BACS file not yet generated — " +
                    "${money(overview?.summary?.oopBacsQueued, currency)} waiting to pay.",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Bank,
                action = {
                    ZillitButton(
                        text = "Payment routing",
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
                title = "Cash position",
                icon = ZillitIcons.Bank,
                modifier = Modifier.weight(1f),
            ) {
                SummaryLine("Total petty cash issued", money(overview?.summary?.totalPettyCashIssued, currency))
                SummaryLine("Receipts approved", money(overview?.summary?.totalReceiptsApproved, currency))
                SummaryLine("Cash still to account for", money(overview?.summary?.cashToAccount, currency))
                SummaryLine("VAT recoverable", money(overview?.summary?.vatRecoverable, currency))
                SummaryLine("Out of pocket — BACS queued", money(overview?.summary?.oopBacsQueued, currency))
                SummaryLine("Out of pocket — payroll", money(overview?.summary?.oopPayrollAdditions, currency))
            }

            ZillitSectionCard(
                title = "Outstanding",
                icon = ZillitIcons.Wallet,
                meta = "${overview?.floats?.size ?: 0} floats",
                modifier = Modifier.weight(1f),
            ) {
                ZillitText(
                    text = money(overview?.stats?.totalOutstanding, currency),
                    style = ZillitTheme.typography.displayLarge,
                )
                ZillitText(
                    text = "Cash issued and not yet accounted for.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                Spacer(Modifier.padding(ZillitTheme.spacing.xs))
                overview?.floats.orEmpty().take(TOP_FLOATS).forEach { row ->
                    FloatSummaryRow(row)
                }
            }
        }

        ZillitSectionCard(title = "Floats on this project", icon = ZillitIcons.Users, padded = false) {
            ZillitDataTable(
                rows = overview?.floats.orEmpty(),
                columns = floatColumns(),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No floats issued",
                emptyMessage = "Float requests appear here once crew ask for cash.",
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
            text = "Crew are reimbursed by BACS or through payroll for expenses they paid personally.",
            tone = StatusTone.Progress,
            icon = ZillitIcons.Receipt,
        )

        StatRow(
            listOf(
                StatTileSpec(
                    label = "Pending claims",
                    value = overview?.pendingClaims?.toString() ?: "—",
                    sub = "Awaiting routing or approval",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Receipt,
                ),
                StatTileSpec(
                    label = "Total claimed",
                    value = money(overview?.totalClaimed, currency),
                    sub = "Across every open claim",
                    icon = ZillitIcons.BarChart,
                ),
                StatTileSpec(
                    label = "BACS ready",
                    value = money(overview?.bacsReady, currency),
                    sub = "Approved, awaiting the file",
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Bank,
                    onClick = { onEvent(CashEvent.Open(CashDestination.PaymentRouting)) },
                ),
                StatTileSpec(
                    label = "Payroll additions",
                    value = money(overview?.payrollAuto, currency),
                    sub = "Routed to the next run",
                    tone = StatusTone.Done,
                    icon = ZillitIcons.Users,
                ),
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(title = "Routing split", icon = ZillitIcons.Bank, modifier = Modifier.weight(1f)) {
                val routing = overview?.routing
                val total = routing?.total?.takeIf { it > 0 } ?: 1.0
                RoutingBar("BACS", routing?.bacs ?: 0.0, total, currency, StatusTone.Progress)
                RoutingBar("Payroll", routing?.payroll ?: 0.0, total, currency, StatusTone.Done)
            }

            ZillitSectionCard(
                title = "Spend by category",
                icon = ZillitIcons.BarChart,
                modifier = Modifier.weight(1f),
            ) {
                val rows = overview?.spendByCategory.orEmpty()
                if (rows.isEmpty()) {
                    ZillitEmptyState(
                        title = "Nothing claimed yet",
                        message = "Categories appear as claims are coded.",
                    )
                } else {
                    val max = rows.maxOf { it.amount }.takeIf { it > 0 } ?: 1.0
                    rows.forEach { row ->
                        RoutingBar(row.category, row.amount, max, currency, StatusTone.Escalated)
                    }
                }
            }
        }

        ZillitSectionCard(title = "Claims", icon = ZillitIcons.Receipt, padded = false) {
            ZillitDataTable(
                rows = overview?.batches.orEmpty(),
                columns = batchColumns(state.viewer.isAccountant),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No out-of-pocket claims",
                emptyMessage = "Claims appear here as crew submit receipts they paid for themselves.",
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
            ZillitSectionCard(title = "Your float", icon = ZillitIcons.Wallet, meta = activeFloat.requestNumber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(
                            text = money(activeFloat.balance, activeFloat.currency),
                            style = ZillitTheme.typography.displayLarge,
                        )
                        ZillitText(
                            text = "left of ${money(activeFloat.issuedAmount, activeFloat.currency)} issued",
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
                        text = "Submit receipts",
                        onClick = { onEvent(CashEvent.Open(CashDestination.SubmitReceipts)) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                        enabled = activeFloat.status.isSubmittable,
                    )
                    ZillitButton(
                        text = "Ask for more cash",
                        onClick = { onEvent(CashEvent.Open(CashDestination.CashExtension)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            }
        } else {
            ZillitNotice(
                text = "You have no active float. Request one to start spending against petty cash.",
                tone = StatusTone.Progress,
                action = {
                    ZillitButton(
                        text = "Request a float",
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
                text = "${needsAction.size} of your batches came back — they need correcting and resubmitting.",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitSectionCard(title = "Petty cash receipts", icon = ZillitIcons.Receipt, padded = false) {
            ZillitDataTable(
                rows = overview?.pettyCashClaims.orEmpty(),
                columns = batchColumns(accountant = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "Nothing submitted yet",
                emptyMessage = "Receipts you submit against your float show up here.",
                virtualised = false,
            )
        }

        ZillitSectionCard(title = "Out of pocket", icon = ZillitIcons.Wallet, padded = false) {
            ZillitDataTable(
                rows = overview?.outOfPocketClaims.orEmpty(),
                columns = batchColumns(accountant = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "Nothing claimed",
                emptyMessage = "Expenses you paid for yourself and claimed back appear here.",
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
                    label = "Issued to the department",
                    value = money(overview?.totalIssued, null),
                    icon = ZillitIcons.Wallet,
                ),
                StatTileSpec(
                    label = "Spent",
                    value = money(overview?.totalSpent, null),
                    tone = StatusTone.Progress,
                    icon = ZillitIcons.Receipt,
                ),
                StatTileSpec(
                    label = "Floats",
                    value = overview?.floats?.size?.toString() ?: "—",
                    icon = ZillitIcons.Users,
                ),
                StatTileSpec(
                    label = "Batches",
                    value = overview?.batches?.size?.toString() ?: "—",
                    icon = ZillitIcons.Ledger,
                ),
            ),
        )

        ZillitSectionCard(title = "Department floats", icon = ZillitIcons.Wallet, padded = false) {
            ZillitDataTable(
                rows = overview?.floats.orEmpty(),
                columns = floatColumns(),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No floats in this department",
                virtualised = false,
            )
        }

        ZillitSectionCard(title = "Department batches", icon = ZillitIcons.Receipt, padded = false) {
            ZillitDataTable(
                rows = overview?.batches.orEmpty(),
                columns = batchColumns(accountant = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No batches in this department",
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
    add(personColumn("Holder", ColumnWidth.Weight(1.6f), userId = { it.userId }) { it.holderName })
    add(textColumn("Reference", ColumnWidth.Weight(1f), muted = true) { it.requestNumber.ifBlank { "—" } })
    if (!compact) {
        add(textColumn("Issued", ColumnWidth.Weight(1f), numeric = true) { money(it.issuedAmount, it.currency) })
    }
    add(textColumn("Balance", ColumnWidth.Weight(1f), numeric = true) { money(it.balance, it.currency) })
    if (!compact) {
        add(
            TableColumn(
                header = "Consumed",
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
        add(textColumn("Requested", ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) })
    }
    add(
        TableColumn(
            header = "Status",
            width = ColumnWidth.Fixed(STATUS_COLUMN),
            cell = { FloatStatusPill(it.status) },
        ),
    )
}

/** The batch table's columns. Identical everywhere a batch is listed. */
@Suppress("MagicNumber") // Column proportions; naming each would not clarify them.
fun batchColumns(accountant: Boolean, compact: Boolean = false): List<TableColumn<ClaimBatch>> = buildList {
    add(textColumn("Reference", ColumnWidth.Weight(1.2f)) { it.reference.ifBlank { it.id.take(REF_FALLBACK) } })
    add(personColumn("Submitted by", ColumnWidth.Weight(1.6f), userId = { it.userId }) { it.holderName })
    if (!compact) {
        add(textColumn("Receipts", ColumnWidth.Fixed(COUNT_COLUMN), numeric = true) { it.claimCount.toString() })
    }
    add(textColumn("Total", ColumnWidth.Weight(1f), numeric = true) { money(it.totalGross, it.currency) })
    if (!compact) {
        add(textColumn("Submitted", ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) })
    }
    add(
        TableColumn(
            header = "Status",
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
