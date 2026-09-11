package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BibleAccount
import com.zillit.desktop.feature.accounthub.domain.LedgerSource
import com.zillit.desktop.feature.accounthub.domain.LedgerTransaction
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage

private const val SOURCE_WIDTH = 150
private const val REFERENCE_WIDTH = 130
private const val AMOUNT_WIDTH = 150
private const val PENCE = 100.0

/**
 * Every transaction of the period, under the account it posted to.
 *
 * The production's closeout bible: what a set of books is checked against line
 * by line, and what an auditor is handed. Accounts fold away so a reader can
 * work through one at a time, and the uncoded bucket is named plainly rather
 * than shown as the sentinel the server sends.
 */
@Composable
fun BibleReportPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val bible = state.bible
    val report = bible.report

    HubPage {
        ZillitPageHeader(
            eyebrow = "Management",
            title = "Bible Report",
            description = "Every posted transaction of the period, grouped by account code.",
        )

        Filters(state, onEvent)

        // A bucket that failed to read is said out loud. This report is what a
        // production closes its books against, and a total that quietly omits
        // payroll is worse than one that admits payroll is missing.
        report.errors.forEach { (bucket, message) ->
            ZillitNotice(
                text = "${LedgerSource.labelFor(bucket)} could not be read: $message",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Info,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitStatTile(
                label = "Accounts",
                value = report.accounts.size.toString(),
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Transactions",
                value = report.transactionCount.toString(),
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Total",
                value = report.grandTotal.asMoney(report.currencyCode),
                modifier = Modifier.weight(1f),
            )
        }

        if (report.accounts.isEmpty() && !bible.loading) {
            ZillitEmptyState(
                title = "Nothing posted in this period",
                message = "Widen the period, or clear the source filter.",
                icon = ZillitIcons.Ledger,
            )
            return@HubPage
        }

        report.accounts.forEach { account ->
            AccountSection(
                account = account,
                currencyCode = report.currencyCode,
                collapsed = account.code in bible.collapsed,
                loading = bible.loading,
                onToggle = { onEvent(AccountHubEvent.ToggleBibleAccount(account.code)) },
            )
        }
    }
}

@Composable
private fun Filters(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val bible = state.bible
    val draft = bible.draft

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSelect(
            value = draft.source,
            options = listOf("") + LedgerSource.entries.map { it.wire },
            onSelect = { onEvent(AccountHubEvent.EditBibleQuery(draft.copy(source = it))) },
            label = { wire -> if (wire.isBlank()) "Every source" else LedgerSource.labelFor(wire) },
            modifier = Modifier.weight(1f),
        )
        ZillitCheckbox(
            checked = draft.includeOpenPurchaseOrders,
            onCheckedChange = {
                onEvent(AccountHubEvent.EditBibleQuery(draft.copy(includeOpenPurchaseOrders = it)))
            },
            label = "Include open purchase orders",
        )
        if (bible.isDirty) {
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(AccountHubEvent.RefreshBibleReport) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Reload,
                loading = bible.loading,
            )
        }
    }
}

@Composable
private fun AccountSection(
    account: BibleAccount,
    currencyCode: String,
    collapsed: Boolean,
    loading: Boolean,
    onToggle: () -> Unit,
) {
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        title = "${account.displayCode} ${account.displayName}".trim(),
        meta = "${account.transactions.size} transaction(s) · ${account.total.asMoney(currencyCode)}",
        padded = false,
        action = {
            ZillitButton(
                text = if (collapsed) "Show" else "Hide",
                onClick = onToggle,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
    ) {
        if (collapsed) return@ZillitSectionCard
        ZillitDataTable(
            rows = account.transactions,
            key = { it.invoiceNumber + it.purchaseOrderNumber + it.description + it.amount },
            loading = loading,
            columns = transactionColumns(currencyCode),
            emptyTitle = "No transactions on this account",
            emptyMessage = "Nothing posted here in the period.",
        )
    }
}

private fun transactionColumns(currencyCode: String): List<TableColumn<LedgerTransaction>> = listOf(
    TableColumn(
        header = "Source",
        width = ColumnWidth.Fixed(SOURCE_WIDTH.dp),
        cell = { txn ->
            ZillitText(
                text = LedgerSource.labelFor(txn.source),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        },
    ),
    TableColumn(
        header = "Invoice",
        width = ColumnWidth.Fixed(REFERENCE_WIDTH.dp),
        cell = { txn -> Reference(txn.invoiceNumber) },
    ),
    TableColumn(
        header = "Order",
        width = ColumnWidth.Fixed(REFERENCE_WIDTH.dp),
        cell = { txn -> Reference(txn.purchaseOrderNumber) },
    ),
    TableColumn(
        header = "Vendor or person",
        cell = { txn -> Reference(txn.party) },
    ),
    TableColumn(
        header = "Description",
        cell = { txn ->
            ZillitText(
                text = txn.description.ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        },
    ),
    TableColumn(
        header = "Amount",
        width = ColumnWidth.Fixed(AMOUNT_WIDTH.dp),
        numeric = true,
        cell = { txn ->
            ZillitText(
                text = txn.amount.asMoney(currencyCode),
                style = ZillitTheme.typography.bodyMedium,
                color = if (txn.amount < 0) ZillitTheme.colors.danger else ZillitTheme.colors.textPrimary,
            )
        },
    ),
)

@Composable
private fun Reference(value: String) {
    ZillitText(text = value.ifBlank { "—" }, style = ZillitTheme.typography.bodyMedium)
}

/**
 * A figure in the report's display currency.
 *
 * The code rather than a symbol: amounts are converted server-side into one
 * currency, and a wrong symbol on a converted figure reads as a wrong amount.
 */
private fun Double.asMoney(currencyCode: String): String {
    val rounded = kotlin.math.round(kotlin.math.abs(this) * PENCE) / PENCE
    val whole = rounded.toLong()
    val pence = kotlin.math.round((rounded - whole) * PENCE).toInt()
    val text = "$whole.${pence.toString().padStart(2, '0')}"
    val signed = if (this < 0) "($text)" else text
    return if (currencyCode.isBlank()) signed else "$currencyCode $signed"
}
