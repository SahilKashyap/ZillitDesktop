package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
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
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoRelief
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState

/**
 * The Posted tab — the web's `POPosted`.
 *
 * Orders that reached the ledger, by accounting period, with the three columns
 * that are the point of the tab: Relieved (what has been invoiced against the
 * commitment), Remaining, and the relief label. Each period has its own total
 * and its own Close Off Period action.
 *
 * Every period stacks down one scrolling page, as the web does. Close Off
 * Period lives in each period's own card header, so the button always names the
 * period it will close — which is the action's whole risk, and the reason a
 * chips-and-one-table version stood here while `ZillitDataTable` could not be
 * nested in a scroll at all.
 */
@Composable
internal fun PoPostedPage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val rows = state.rows
    val periods = rows.byPeriod()
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.showsFilters) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            ) {
                PoFilterRow(state, onEvent)
            }
        }
        if (rows.isEmpty()) {
            ZillitEmptyState(
                title = "Nothing posted yet",
                message = "Orders reach this tab once they are coded and posted to the ledger.",
                icon = ZillitIcons.Ledger,
            )
            return@Column
        }
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = ZillitTheme.spacing.lg,
                end = ZillitTheme.spacing.lg,
                bottom = ZillitTheme.spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            periods.forEach { (period, group) -> PeriodCard(state, period, group, onEvent) }
        }
    }
}

@Composable
private fun PeriodCard(
    state: PoUiState,
    period: String,
    group: List<PurchaseOrder>,
    onEvent: (PoEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Anything not already closed can be closed off. The button says so when
    // there is nothing left, rather than going grey with no explanation.
    val closeable = group.filterNot { it.status == PoStatus.Closed }.map { it.id }
    ZillitSectionCard(
        modifier = modifier.fillMaxWidth(),
        title = period,
        icon = ZillitIcons.Calendar,
        meta = "${group.size} PO${if (group.size == 1) "" else "s"} · ${group.totalValue()}",
        padded = false,
        action = {
            if (state.viewer.isSeniorAccountant) {
                ZillitButton(
                    text = if (closeable.isEmpty()) {
                        "All POs in this period are already closed."
                    } else {
                        "Close Off Period"
                    },
                    onClick = { onEvent(PoEvent.AskCloseOff(period, closeable)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = closeable.isNotEmpty() && !state.busy,
                )
            }
        },
    ) {
        ZillitDataTable(
            rows = group,
            columns = postedColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(PoEvent.OpenOrder(it.id)) },
            emptyTitle = "Nothing in this period",
            // The page scrolls; each period draws every row it holds.
            virtualised = false,
        )
    }
}

@Suppress("LongMethod") // A column table; the shape is the documentation.
private fun postedColumns(state: PoUiState, onEvent: (PoEvent) -> Unit): List<TableColumn<PurchaseOrder>> = listOf(
    textColumn(header = "PO Number", width = ColumnWidth.Fixed(NUMBER_WIDTH), numeric = true) {
        it.number.ifBlank { "—" }
    },
    textColumn(header = "Vendor") { state.vendorName(it).ifBlank { "No vendor" } },
    textColumn(header = "Department", width = ColumnWidth.Fixed(DEPT_COLUMN), muted = true) {
        state.departmentName(it.departmentId).ifBlank { "—" }
    },
    textColumn(header = "Amount", width = ColumnWidth.Fixed(AMOUNT_WIDTH), numeric = true) {
        Money.format(it.gross, it.currency)
    },
    textColumn(header = "Relieved", width = ColumnWidth.Fixed(AMOUNT_WIDTH), numeric = true) {
        Money.format(it.paidAmount, it.currency)
    },
    textColumn(header = "Remaining", width = ColumnWidth.Fixed(AMOUNT_WIDTH), numeric = true) {
        Money.format(it.remaining, it.currency)
    },
    textColumn(header = "Eff. Date", width = ColumnWidth.Fixed(DATE_WIDTH), muted = true) {
        EpochDate.date(it.effectiveDate).ifBlank { "—" }
    },
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_WIDTH),
        cell = { order -> ZillitStatusPill(label = order.relief.label, tone = order.relief.tone()) },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTION_WIDTH),
        cell = { order ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                if (PoAccess.canProcess(order, state.viewer)) {
                    ZillitButton(
                        text = "Process",
                        onClick = { onEvent(PoEvent.ProcessOrder(order.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
                if (order.status != PoStatus.Closed && state.viewer.isSeniorAccountant) {
                    ZillitButton(
                        text = "Close",
                        onClick = { onEvent(PoEvent.AskClose(order.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
            }
        },
    ),
)

private fun PoRelief.tone(): StatusTone = when (this) {
    PoRelief.Open -> StatusTone.Pending
    PoRelief.PartiallyRelieved -> StatusTone.Progress
    PoRelief.FullyRelieved -> StatusTone.Done
    PoRelief.Closed -> StatusTone.Neutral
}

/**
 * Posted orders grouped by the accounting period their effective date falls in,
 * newest period first.
 *
 * The web groups on a field (`accountingPeriod`) that nothing ever sets, so
 * every order lands in one group called "Unknown" and the Close Off Period
 * button closes the entire ledger. Deriving the period from the effective date
 * is what that grouping was for, and it makes the action mean what it says.
 * Orders with no effective date keep the web's "Unknown" bucket, last.
 */
internal fun List<PurchaseOrder>.byPeriod(): List<Pair<String, List<PurchaseOrder>>> {
    val groups = groupBy { order -> order.effectiveDate?.let { EpochDate.isoDate(it).take(PERIOD_KEY) } }
    return groups.entries
        .sortedWith(compareByDescending { it.key ?: "" })
        .map { (key, orders) -> (key?.periodLabel() ?: "Unknown") to orders }
        .sortedBy { it.first == "Unknown" }
}

/** `2026-03` → `MAR 2026`, the way an accountant names a period. */
private fun String.periodLabel(): String {
    val parts = split('-')
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return this
    val year = parts.getOrNull(0) ?: return this
    return "${MONTHS.getOrElse(month - 1) { parts[1] }} $year"
}

private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
private const val PERIOD_KEY = 7
private val ACTION_WIDTH = 170.dp
