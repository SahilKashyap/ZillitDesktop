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
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.shortDayLabel

private val DATE_WIDTH = 90.dp
private val CURRENCY_WIDTH = 90.dp
private val AMOUNT_WIDTH = 140.dp
private val STATUS_WIDTH = 110.dp
private val ACTION_WIDTH = 110.dp

/**
 * What a foreign payment actually cost against what it was budgeted at.
 *
 * A positive variance is a gain and a negative one a loss, in the project's
 * own currency. The service's own field names say `gbp`; they are not sterling
 * unless the project is, and nothing here repeats that mistake.
 */
@Composable
fun ColumnScope.FxVariancesPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val fx = state.fx
    val currency = state.projectCurrency

    PeriodFilter(state, fx.periodId) { onEvent(BankRecEvent.FilterFx(it)) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitStatTile(
            label = "Net variance",
            value = signedMoney(fx.netVariance, currency),
            sub = if (fx.netVariance >= 0) "Gain" else "Loss",
            tone = if (fx.netVariance >= 0) StatusTone.Done else StatusTone.Rejected,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Unposted",
            value = fx.unposted.size.toString(),
            sub = "of ${fx.rows.size}",
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Posted",
            value = (fx.rows.size - fx.unposted.size).toString(),
            modifier = Modifier.weight(1f),
        )
    }

    ZillitSectionCard(
        title = "Variances",
        icon = ZillitIcons.BarChart,
        meta = "${fx.rows.size}",
        padded = false,
        action = {
            ZillitButton(
                text = "Post all unposted",
                onClick = { onEvent(BankRecEvent.AskPostAllFx) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                loading = fx.saving,
                enabled = fx.unposted.isNotEmpty(),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitDataTable(
            rows = fx.rows,
            columns = columns(currency, onEvent),
            key = { it.id },
            loading = fx.loading,
            emptyTitle = "No foreign payments",
            emptyMessage = "Nothing on this period was paid in another currency.",
            virtualised = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun columns(
    currency: String,
    onEvent: (BankRecEvent) -> Unit,
): List<TableColumn<FxVariance>> = identityColumns() + moneyColumns(currency) + statusColumns(onEvent)

private fun identityColumns(): List<TableColumn<FxVariance>> = listOf(
    textColumn(header = "Date", width = ColumnWidth.Fixed(DATE_WIDTH), muted = true) {
        shortDayLabel(it.createdAtMillis)
    },
    textColumn(header = "Payee") { it.vendorName.ifBlank { it.reference }.ifBlank { "—" } },
    textColumn(header = "Currency", width = ColumnWidth.Fixed(CURRENCY_WIDTH)) { it.invoiceCurrency },
)

private fun moneyColumns(currency: String): List<TableColumn<FxVariance>> = listOf(
    TableColumn(
        header = "Foreign",
        width = ColumnWidth.Fixed(AMOUNT_WIDTH),
        numeric = true,
        cell = { row ->
            ZillitText(
                text = money(row.foreignAmount, row.invoiceCurrency),
                style = ZillitTheme.typography.bodyMedium,
            )
        },
    ),
    TableColumn(
        header = "Budgeted",
        width = ColumnWidth.Fixed(AMOUNT_WIDTH),
        numeric = true,
        cell = { row ->
            ZillitText(text = money(row.budgetAmount, currency), style = ZillitTheme.typography.bodyMedium)
        },
    ),
    TableColumn(
        header = "Paid",
        width = ColumnWidth.Fixed(AMOUNT_WIDTH),
        numeric = true,
        cell = { row ->
            ZillitText(text = money(row.paidAmount, currency), style = ZillitTheme.typography.bodyMedium)
        },
    ),
    TableColumn(
        header = "Variance",
        width = ColumnWidth.Fixed(AMOUNT_WIDTH),
        numeric = true,
        cell = { row ->
            ZillitText(
                text = signedMoney(row.variance, currency),
                style = ZillitTheme.typography.bodyMedium,
                color = if (row.isGain) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textSecondary,
            )
        },
    ),
)

private fun statusColumns(onEvent: (BankRecEvent) -> Unit): List<TableColumn<FxVariance>> = listOf(
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_WIDTH),
        cell = { row ->
            ZillitStatusPill(
                label = row.status.label,
                tone = if (row.isPosted) StatusTone.Done else StatusTone.Pending,
                dot = true,
            )
        },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTION_WIDTH),
        cell = { row ->
            if (!row.isPosted) {
                ZillitButton(
                    text = "Post",
                    onClick = { onEvent(BankRecEvent.ComposeFxPosting(row)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        },
    ),
)
