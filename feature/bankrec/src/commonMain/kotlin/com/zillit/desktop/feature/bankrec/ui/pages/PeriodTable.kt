package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.dayLabel

private val PERIOD_WIDTH = 180.dp
private val COUNT_WIDTH = 110.dp
private val STATUS_WIDTH = 130.dp
private val SIGNED_WIDTH = 170.dp
private val ACTIONS_WIDTH = 180.dp

/**
 * Every period, with what it holds and who closed it.
 *
 * Shared by Overview and History: the two show the same rows, and having
 * written it twice on the web they had drifted by a column.
 */
@Composable
fun ColumnScope.PeriodTable(
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
    title: String,
) {
    ZillitSectionCard(
        title = title,
        icon = ZillitIcons.Ledger,
        meta = "${state.periods.size} period(s)",
        padded = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitDataTable(
            rows = state.periods,
            columns = periodColumns(state, onEvent),
            key = { it.id },
            loading = state.periodsLoading,
            emptyTitle = "No periods yet",
            emptyMessage = "A period opens when a statement is imported.",
            virtualised = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun periodColumns(
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
): List<TableColumn<BankPeriod>> = listOf(
    textColumn(header = "Period", width = ColumnWidth.Fixed(PERIOD_WIDTH)) { state.periodLabel(it) },
    textColumn(header = "Bank account") { state.account(it.bankAccountId)?.name.orEmpty().ifBlank { "—" } },
    textColumn(header = "Transactions", width = ColumnWidth.Fixed(COUNT_WIDTH), numeric = true) {
        it.totalTxns.toString()
    },
    textColumn(header = "Matched", width = ColumnWidth.Fixed(COUNT_WIDTH), numeric = true) {
        "${it.matchedCount} · ${it.matchedPercent}%"
    },
    TableColumn(
        header = "Fraud",
        width = ColumnWidth.Fixed(COUNT_WIDTH),
        cell = { period ->
            ZillitStatusPill(
                label = if (period.fraudCount > 0) "${period.fraudCount} flags" else "Clear",
                tone = if (period.fraudCount > 0) StatusTone.Escalated else StatusTone.Done,
            )
        },
    ),
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_WIDTH),
        cell = { period ->
            ZillitStatusPill(label = period.status.label, tone = period.status.tone(), dot = true)
        },
    ),
    TableColumn(
        header = "Signed off",
        width = ColumnWidth.Fixed(SIGNED_WIDTH),
        cell = { period ->
            ZillitText(
                text = if (period.isOpen) {
                    "—"
                } else {
                    listOfNotNull(
                        period.signedBy.takeIf { it.isNotBlank() },
                        dayLabel(period.signedAtMillis).takeIf { it != "—" },
                    ).joinToString(" · ").ifBlank { "Signed off" }
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTIONS_WIDTH),
        cell = { period -> RowActions(period, onEvent) },
    ),
)

@Composable
private fun RowActions(period: BankPeriod, onEvent: (BankRecEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitButton(
            text = if (period.isOpen) "Open" else "View",
            onClick = { onEvent(BankRecEvent.OpenPeriod(period.id)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        // A signed-off period is refused by the server, and saying so here
        // spares the accountant a refusal they cannot act on.
        if (period.isOpen) {
            ZillitButton(
                text = "Delete",
                onClick = { onEvent(BankRecEvent.AskDeletePeriods(listOf(period))) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
            )
        }
    }
}
