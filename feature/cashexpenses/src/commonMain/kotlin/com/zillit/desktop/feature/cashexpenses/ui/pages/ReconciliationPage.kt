package com.zillit.desktop.feature.cashexpenses.ui.pages

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
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import com.zillit.desktop.feature.cashexpenses.ui.AmountAction
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.money
import kotlin.math.abs

/**
 * Cash reconciliation — counting the cash box against what the ledger says.
 *
 * The variance is the whole screen. It is shown as its own tile, tinted red
 * whenever it is not zero, because a reconciliation that balances needs no
 * attention and one that does not needs all of it.
 */
@Suppress("LongMethod") // Tiles, a notice and the register, read together.
@Composable
fun ReconciliationPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val latest = state.reconciliations.firstOrNull()
    val book = state.bookBalance

    FixedPage {
        StatRow(
            listOf(
                StatTileSpec(
                    label = "Book balance",
                    value = money(book, null),
                    sub = "What the ledger expects",
                    icon = ZillitIcons.Ledger,
                ),
                StatTileSpec(
                    label = "Last counted",
                    value = money(latest?.countedBalance, latest?.currency),
                    sub = latest?.let { date(it.createdAt) } ?: "Never counted",
                    icon = ZillitIcons.Wallet,
                ),
                StatTileSpec(
                    label = "Variance",
                    value = money(latest?.variance, latest?.currency),
                    sub = if (latest == null) {
                        "Start a count to compare"
                    } else if (abs(latest.variance) < PENNY) {
                        "Balanced"
                    } else {
                        "Needs explaining"
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
                text = "The ledger balance could not be fetched, so a variance cannot be computed here. " +
                    "You can still record a count.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitSectionCard(
            title = "Reconciliations",
            icon = ZillitIcons.Bank,
            padded = false,
            modifier = Modifier.weight(1f),
            action = {
                ZillitButton(
                    text = "Record a count",
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.WithAmount(
                                    action = AmountAction.CreateReconciliation,
                                    targetId = "",
                                    title = "Record a cash count",
                                    label = "Cash counted in the box",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                    enabled = !state.busy,
                )
            },
        ) {
            ZillitDataTable(
                rows = state.reconciliations,
                columns = reconciliationColumns(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No counts recorded",
                emptyMessage = "Record a cash count to reconcile the box against the ledger.",
            )
        }
    }
}

@Suppress("LongMethod", "MagicNumber") // A column table and its proportions.
private fun reconciliationColumns(
    state: CashUiState,
    onEvent: (CashEvent) -> Unit,
): List<TableColumn<Reconciliation>> = listOf(
    textColumn("Reference", ColumnWidth.Weight(1f)) { it.reference ?: it.id.take(REF_FALLBACK) },
    textColumn("Period", ColumnWidth.Weight(1.2f), muted = true) {
        listOf(date(it.periodStart), date(it.periodEnd)).joinToString(" – ")
    },
    textColumn("Book", ColumnWidth.Weight(1f), numeric = true) { money(it.bookBalance, it.currency) },
    textColumn("Counted", ColumnWidth.Weight(1f), numeric = true) { money(it.countedBalance, it.currency) },
    TableColumn(
        header = "Variance",
        width = ColumnWidth.Weight(1f),
        numeric = true,
        cell = { row ->
            ZillitText(
                text = money(row.variance, row.currency),
                style = ZillitTheme.typography.numeric,
                color = if (abs(row.variance) < PENNY) {
                    ZillitTheme.colors.success
                } else {
                    ZillitTheme.colors.danger
                },
                maxLines = 1,
            )
        },
    ),
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { row ->
            ZillitStatusPill(
                label = row.status.lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Draft" },
                tone = when (row.status) {
                    SIGNED_OFF -> StatusTone.Done
                    UNDER_REVIEW -> StatusTone.Progress
                    else -> StatusTone.Pending
                },
                dot = true,
            )
        },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTION_COLUMN),
        cell = { row ->
            // Sign-off is senior-only and terminal: it closes the period, so it
            // is offered only where it is both permitted and still possible.
            if (state.viewer.isSenior && row.status != SIGNED_OFF) {
                ZillitButton(
                    text = "Sign off",
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.Confirm(
                                    ConfirmAction.SignOffReconciliation,
                                    row.id,
                                    "Sign off this reconciliation",
                                    "The period is closed and the variance is accepted as recorded.",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            } else {
                ZillitText(
                    text = "—",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        },
    ),
)

private const val SIGNED_OFF = "SIGNED_OFF"
private const val UNDER_REVIEW = "UNDER_REVIEW"
private const val REF_FALLBACK = 8

/** Below a penny is a rounding artefact, not a discrepancy worth flagging. */
private const val PENNY = 0.005

private val STATUS_COLUMN = 130.dp
private val ACTION_COLUMN = 110.dp
