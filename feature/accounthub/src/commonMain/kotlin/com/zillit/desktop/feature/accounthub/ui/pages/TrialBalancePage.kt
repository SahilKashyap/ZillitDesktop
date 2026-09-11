package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage

private const val CODE_WIDTH = 140
private const val MONEY_WIDTH = 150

/** Two decimal places, which is what a ledger is kept to. */
private const val PENCE = 100.0

/**
 * Account balances over a period.
 *
 * Grouped by cost type with a subtotal each and a grand total that says
 * whether the ledger balances. Expense reads last, everything else
 * alphabetically — the order an accountant reads a trial balance in.
 *
 * The report runs on an explicit refresh, not on every filter change: it is
 * the server's most expensive read on this service, and a period nobody meant
 * is not worth asking for.
 */
@Composable
fun TrialBalancePage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val trial = state.trialBalance

    HubPage {
        ZillitPageHeader(
            eyebrow = "Management",
            title = "Trial Balance",
            description = "Every account's movement and closing balance over the period.",
        )

        Filters(state, onEvent)
        Totals(trial.report)

        ZillitSectionCard(
            title = "Accounts",
            icon = ZillitIcons.Ledger,
            meta = "${trial.report.rows.size} account(s)",
            padded = false,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            ZillitDataTable(
                rows = trial.report.rows,
                key = { it.accountCode + it.name },
                loading = trial.loading,
                columns = columns(),
                emptyTitle = "Nothing posted in this period",
                emptyMessage = "Widen the period, or include accounts with no movement.",
            )
        }

        GroupSubtotals(trial.report)
    }
}

@Composable
private fun Filters(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val trial = state.trialBalance
    val draft = trial.draft

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitCheckbox(
            checked = draft.includeZeroAccounts,
            onCheckedChange = {
                onEvent(AccountHubEvent.EditTrialBalanceQuery(draft.copy(includeZeroAccounts = it)))
            },
            label = "Include accounts with no movement",
        )
        // Only once a filter differs from what produced the rows on screen:
        // a refresh that would change nothing is a button that does nothing.
        if (trial.isDirty) {
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(AccountHubEvent.RefreshTrialBalance) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Reload,
                loading = trial.loading,
            )
        }
    }
}

@Composable
private fun Totals(report: TrialBalance) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitStatTile(label = "Debit", value = report.debit.asMoney(), modifier = Modifier.weight(1f))
        ZillitStatTile(label = "Credit", value = report.credit.asMoney(), modifier = Modifier.weight(1f))
        ZillitStatTile(
            label = "Balance",
            value = report.balance.asMoney(),
            sub = if (report.isBalanced) "Balanced" else "Does not balance",
            tone = if (report.isBalanced) StatusTone.Done else StatusTone.Rejected,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The per-type subtotals, under the table they summarise. */
@Composable
private fun GroupSubtotals(report: TrialBalance) {
    if (report.groups.isEmpty()) return
    report.groups.forEach { group ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitStatusPill(label = group.label, tone = StatusTone.Neutral)
            ZillitText(
                text = "${group.rows.size} account(s)",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitText(text = group.balance.asMoney(), style = ZillitTheme.typography.bodyMedium)
        }
    }
}

private fun columns(): List<TableColumn<com.zillit.desktop.feature.accounthub.domain.TrialBalanceRow>> = listOf(
    TableColumn(
        header = "Code",
        width = ColumnWidth.Fixed(CODE_WIDTH.dp),
        cell = { row ->
            ZillitText(
                text = row.accountCode.ifBlank { "—" },
                style = ZillitTheme.typography.bodyMedium,
            )
        },
    ),
    TableColumn(
        header = "Account",
        cell = { row -> ZillitText(text = row.name, style = ZillitTheme.typography.bodyMedium) },
    ),
    TableColumn(
        header = "Type",
        cell = { row ->
            ZillitText(
                text = TrialBalance.labelFor(row.costType),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        },
    ),
    TableColumn(
        header = "Debit",
        width = ColumnWidth.Fixed(MONEY_WIDTH.dp),
        numeric = true,
        cell = { row -> MoneyCell(row.debit, dashZero = true) },
    ),
    TableColumn(
        header = "Credit",
        width = ColumnWidth.Fixed(MONEY_WIDTH.dp),
        numeric = true,
        cell = { row -> MoneyCell(row.credit, dashZero = true) },
    ),
    TableColumn(
        header = "Balance",
        width = ColumnWidth.Fixed(MONEY_WIDTH.dp),
        numeric = true,
        cell = { row -> MoneyCell(row.ending, dashZero = false) },
    ),
)

/** A zero debit or credit reads as a dash; a zero balance is a real zero. */
@Composable
private fun MoneyCell(value: Double, dashZero: Boolean) {
    if (dashZero && value == 0.0) {
        ZillitText(text = "—", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
        return
    }
    ZillitText(
        text = value.asMoney(),
        style = ZillitTheme.typography.bodyMedium,
        // Negatives in the danger colour, as the report prints them.
        color = if (value < 0) ZillitTheme.colors.danger else ZillitTheme.colors.textPrimary,
    )
}

/** Two decimals, with a negative shown in brackets as an accountant writes it. */
private fun Double.asMoney(): String {
    val rounded = kotlin.math.round(kotlin.math.abs(this) * PENCE) / PENCE
    val whole = rounded.toLong()
    val pence = kotlin.math.round((rounded - whole) * PENCE).toInt()
    val text = "$whole.${pence.toString().padStart(2, '0')}"
    return if (this < 0) "($text)" else text
}
