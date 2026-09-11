package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatDraft
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.ui.ReturnState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent

private const val GBP = "GBP"
private const val WHOLE_POUNDS = 0
private val BOX_WIDTH = 56.dp
private val AMOUNT_WIDTH = 160.dp
private val PERIOD_WIDTH = 320.dp

/** One registration's periods, its box mapping, and the return itself. */
@Composable
fun ColumnScope.ReturnPage(state: ReturnState, canReachAuthority: Boolean, onEvent: (TaxFilingEvent) -> Unit) {
    if (state.registration?.connected != true) {
        ZillitNotice(
            text = "This registration has not authorised HMRC yet. Periods and returns stay " +
                "unavailable until it has.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Shield,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    PeriodSection(state, canReachAuthority, onEvent)

    if (state.obligations.isEmpty()) {
        ZillitEmptyState(
            title = "No periods known",
            message = "Ask HMRC which periods this company owes a return for.",
            icon = ZillitIcons.Calendar,
        )
        return
    }

    // A fulfilled period gets the receipt and nothing else. Leaving the
    // mapping and the calculate button in front of a return already filed
    // invites an accountant to redo work that cannot be submitted.
    val period = state.period
    if (period != null && !period.isOpen) {
        FiledReturnPanel(period, state.filedForPeriod)
        return
    }

    BoxMappingSection(state, onEvent)
    DraftSection(state, onEvent)
}

@Composable
private fun ColumnScope.PeriodSection(
    state: ReturnState,
    canReachAuthority: Boolean,
    onEvent: (TaxFilingEvent) -> Unit,
) {
    ZillitSectionCard(
        title = "Period",
        icon = ZillitIcons.Calendar,
        meta = state.period?.let { "Due ${it.due}" },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitSelect(
                value = state.periodKey,
                options = state.obligations.map { it.periodKey },
                onSelect = { onEvent(TaxFilingEvent.SelectPeriod(it)) },
                label = { key ->
                    val row = state.obligations.firstOrNull { it.periodKey == key }
                    when {
                        row == null -> "Choose a period"
                        row.isOpen -> "${row.start} to ${row.end}"
                        else -> "${row.start} to ${row.end} · filed"
                    }
                },
                enabled = state.obligations.isNotEmpty(),
                modifier = Modifier.width(PERIOD_WIDTH),
            )
            ZillitButton(
                text = "Ask HMRC",
                onClick = { onEvent(TaxFilingEvent.SyncObligations) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Reload,
                loading = state.syncing,
                enabled = canReachAuthority && state.registration?.connected == true,
            )
        }

        state.period?.takeIf { !it.isOpen }?.let { fulfilled ->
            ZillitNotice(
                text = "HMRC received this period's return on ${fulfilled.received}. " +
                    "A fulfilled period cannot be filed again.",
                tone = StatusTone.Done,
                icon = ZillitIcons.Tick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ColumnScope.DraftSection(state: ReturnState, onEvent: (TaxFilingEvent) -> Unit) {
    val shown = state.shown
    ZillitSectionCard(
        title = "The nine boxes",
        icon = ZillitIcons.Ledger,
        meta = state.periodKey.takeIf { it.isNotBlank() },
        action = {
            ZillitButton(
                text = "Export ledger",
                onClick = { onEvent(TaxFilingEvent.ExportLedger) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Download,
                loading = state.exporting,
                enabled = state.periodKey.isNotBlank(),
            )
            ZillitButton(
                text = if (shown == null) "Calculate" else "Recalculate",
                onClick = { onEvent(TaxFilingEvent.Calculate) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.BarChart,
                loading = state.calculating,
                enabled = state.periodKey.isNotBlank(),
            )
        },
    ) {
        if (shown == null) {
            ZillitEmptyState(
                title = "Nothing calculated yet",
                message = "Zillit reads the ledger through the mapping above and fills the " +
                    "nine boxes. Nothing is sent to HMRC by calculating.",
                icon = ZillitIcons.BarChart,
            )
            return@ZillitSectionCard
        }

        AllZeroNotice(state)
        NetTile(shown)

        ZillitDataTable(
            rows = VatBox.entries.toList(),
            columns = boxColumns(shown),
            key = { it.number },
            virtualised = false,
            modifier = Modifier.fillMaxWidth(),
        )

        SubmitRow(state, onEvent)
    }
}

/**
 * Why a return of nine zeroes is nine zeroes.
 *
 * A quiet quarter and a mapping that selects nothing look identical, and the
 * second is the one that gets filed by mistake. The counts the server kept
 * while building tell them apart.
 */
@Composable
private fun ColumnScope.AllZeroNotice(state: ReturnState) {
    val draft = state.draft ?: return
    if (!VatDraft(draft, state.diagnostics).isAllZero) return

    val scope = state.diagnostics.rowsInScope
    val orphans = state.diagnostics.nullCompanyRows ?: 0
    ZillitNotice(
        text = "Every box came back at zero. Ledger rows for this company in the period: " +
            "${scope ?: "unknown"}." +
            (if (orphans > 0) " $orphans row(s) have no company against them." else "") +
            " A box with no date range uses the obligation period; set one per box to " +
            "include other dates.",
        tone = StatusTone.Pending,
        icon = ZillitIcons.Info,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.NetTile(shown: VatReturn) {
    ZillitStatTile(
        label = if (shown.isPayable) "To pay HMRC" else "To reclaim from HMRC",
        value = Money.format(shown[VatBox.NetDue], GBP),
        sub = "Box 5",
        tone = if (shown.isPayable) StatusTone.Pending else StatusTone.Done,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun boxColumns(shown: VatReturn): List<TableColumn<VatBox>> = listOf(
    TableColumn(
        header = "Box",
        width = ColumnWidth.Fixed(BOX_WIDTH),
        cell = { box ->
            ZillitText(text = box.number.toString(), style = ZillitTheme.typography.label)
        },
    ),
    TableColumn(
        header = "",
        cell = { box ->
            // A Box holds the cell, so the two lines need a Column of their
            // own or they draw on top of one another.
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(text = box.label, style = ZillitTheme.typography.bodyMedium)
                ZillitText(
                    text = box.description,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        },
    ),
    TableColumn(
        header = "Amount",
        width = ColumnWidth.Fixed(AMOUNT_WIDTH),
        numeric = true,
        cell = { box ->
            ZillitText(
                // Boxes 6 to 9 file to the pound, so they are shown to the
                // pound: a penny on screen that HMRC never receives is a
                // figure the accountant cannot reconcile afterwards.
                text = Money.format(
                    shown[box],
                    GBP,
                    if (box.wholePounds) WHOLE_POUNDS else DEFAULT_DECIMALS,
                ),
                style = ZillitTheme.typography.bodyMedium,
                color = if (box.computed) {
                    ZillitTheme.colors.textSecondary
                } else {
                    ZillitTheme.colors.textPrimary
                },
            )
        },
    ),
)

private const val DEFAULT_DECIMALS = 2

@Composable
private fun ColumnScope.SubmitRow(state: ReturnState, onEvent: (TaxFilingEvent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitText(
            text = "Filing sends these figures to HMRC as this company's legal VAT return.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = "File with HMRC",
            onClick = { onEvent(TaxFilingEvent.AskSubmit) },
            leadingIcon = ZillitIcons.Send,
            loading = state.submitting,
            enabled = state.canSubmit,
        )
    }
}
