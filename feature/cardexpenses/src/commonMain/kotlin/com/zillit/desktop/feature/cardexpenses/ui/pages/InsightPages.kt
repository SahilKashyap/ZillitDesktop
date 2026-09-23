package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.ui.AnalyticsRange
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
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
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cardexpenses.domain.AnalyticsSlice
import com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.ui.CardConfirmAction
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardPrompt
import com.zillit.desktop.feature.cardexpenses.ui.CardReasonAction
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.tone

/**
 * Card spend, broken down.
 *
 * Bars rather than a pie: comparing lengths is a task people are good at and
 * comparing angles is one they are not, and every question this page answers
 * ("who spent most", "which month was heaviest") is a comparison.
 */
@Composable
fun AnalyticsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val analytics = state.analytics
    val range = state.analyticsRange
    val currency = state.currency
    val period = if (range.isAllTime) str(S.desktop_all_time) else str(S.desktop_card_selected_period)

    ScrollingPage {
        PeriodPicker(state, onEvent)

        AnalyticsTotals(analytics, currency, period)

        // The service breaks spend down by department and by holder, naming
        // both by id (AnalyticsPage.jsx:99-125); the names come from the crew.
        // An older service's by-category and by-month lists still draw.
        val byDepartment = analytics?.byDepartment.orEmpty()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            BreakdownCard(
                title = str(if (byDepartment.isNotEmpty()) S.desktop_by_department else S.desktop_card_by_category),
                slices = byDepartment.map { it.copy(label = state.departmentName(it)) }
                    .ifEmpty { analytics?.byCategory.orEmpty() },
                currency = currency,
                tone = StatusTone.Progress,
                modifier = Modifier.weight(1f),
            )
            BreakdownCard(
                title = str(S.desktop_card_by_cardholder),
                slices = analytics?.byHolder.orEmpty().map { it.copy(label = state.holderLabel(it)) },
                currency = currency,
                tone = StatusTone.Done,
                modifier = Modifier.weight(1f),
            )
        }

        analytics?.byMonth?.takeIf { it.isNotEmpty() }?.let { months ->
            BreakdownCard(
                title = str(S.desktop_card_by_month),
                slices = months,
                currency = currency,
                tone = StatusTone.Escalated,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A department slice's name: the server's own label, else the crew's name for the id. */
private fun CardUiState.departmentName(slice: AnalyticsSlice): String =
    slice.label.takeIf { it.isNotBlank() }
        ?: slice.departmentId?.let { id -> people.firstOrNull { it.departmentId == id }?.department }
        ?: str(S.unassigned)

/** A holder slice's name, with the card it was spent on. */
private fun CardUiState.holderLabel(slice: AnalyticsSlice): String {
    val name = slice.label.takeIf { it.isNotBlank() } ?: personName(slice.userId)
    return listOfNotNull(name, slice.cardLastFour?.let { "•••• $it" }).joinToString(" · ")
}

/** What the period came to, before it is broken down three ways. */
@Composable
private fun AnalyticsTotals(analytics: CardAnalytics?, currency: String?, period: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitStatTile(
            label = str(S.ah_total_spend),
            value = money(analytics?.totalSpend, currency),
            sub = str(S.desktop_card_period_every_card, period),
            icon = ZillitIcons.BarChart,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.ah_transactions),
            value = analytics?.transactionCount?.toString() ?: "—",
            sub = str(S.desktop_card_statement_lines),
            tone = StatusTone.Progress,
            icon = ZillitIcons.Ledger,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_card_average),
            value = money(analytics?.averageTransaction, currency),
            sub = str(S.desktop_card_per_transaction),
            icon = ZillitIcons.Receipt,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The window the figures cover.
 *
 * Both ends optional and blank meaning open: "everything since the shoot
 * started" and "everything up to the end of last month" are both real
 * questions, and forcing a pair of dates to ask either is how a period filter
 * ends up unused. Applied on a press rather than as each field changes — a
 * half-typed date would otherwise fetch a period nobody asked for.
 */
@Composable
private fun PeriodPicker(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    var from by remember(state.analyticsRange) { mutableStateOf(state.analyticsRange.from) }
    var to by remember(state.analyticsRange) { mutableStateOf(state.analyticsRange.to) }
    val pending = from != state.analyticsRange.from || to != state.analyticsRange.to

    ZillitSectionCard(title = str(S.cr_meta_period), icon = ZillitIcons.Calendar) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitDateField(
                value = from,
                onValueChange = { from = it },
                label = str(S.fromText),
                modifier = Modifier.weight(1f),
            )
            ZillitDateField(
                value = to,
                onValueChange = { to = it },
                label = str(S.toText),
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.dm_filter_apply),
                onClick = { onEvent(CardEvent.SetAnalyticsRange(AnalyticsRange(from, to))) },
                size = ButtonSize.Small,
                enabled = pending && !state.loading,
                loading = state.loading,
            )
            if (!state.analyticsRange.isAllTime || pending) {
                ZillitButton(
                    text = str(S.desktop_all_time),
                    onClick = {
                        from = ""
                        to = ""
                        onEvent(CardEvent.SetAnalyticsRange(AnalyticsRange()))
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun BreakdownCard(
    title: String,
    slices: List<AnalyticsSlice>,
    currency: String?,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    ZillitSectionCard(title = title, icon = ZillitIcons.BarChart, modifier = modifier) {
        if (slices.isEmpty()) {
            ZillitEmptyState(
                title = str(S.desktop_card_no_spend_in_period),
                message = str(S.desktop_card_bars_appear_hint),
            )
            return@ZillitSectionCard
        }
        val max = slices.maxOf { it.amount }.takeIf { it > 0 } ?: 1.0
        slices.forEach { slice ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZillitText(
                        text = slice.label.ifBlank { str(S.desktop_card_unlabelled) },
                        style = ZillitTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = Money.format(slice.amount, currency),
                        style = ZillitTheme.typography.numeric,
                        maxLines = 1,
                    )
                }
                ZillitMeter(fraction = (slice.amount / max).toFloat(), tone = tone)
            }
        }
    }
}

/**
 * Smart alerts — the exception engine's findings.
 *
 * Sorted by severity because the page exists to surface the one thing that
 * matters, and a chronological list buries it under routine noise. An alert is
 * open while it is `active` or `investigating` (`SmartAlertsPage.jsx:28-32,
 * 124`); the desktop looked for an `open` status the service never sends, so
 * no alert ever offered an action.
 */
@Composable
fun AlertsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.alerts.sortedWith(compareBy<CardAlert> { !it.isOpen }.thenBy { it.severity.ordinal })
    val open = rows.count { it.isOpen }

    FixedPage {
        if (open > 0) {
            ZillitNotice(
                text = str(S.desktop_card_alerts_open_note, open),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Bell,
            )
        }

        ZillitSectionCard(
            title = str(S.desktop_card_smart_alerts),
            icon = ZillitIcons.Bell,
            meta = str(S.desktop_card_total_count, rows.size),
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = alertColumns(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_card_nothing_flagged),
                emptyMessage = str(S.desktop_card_alerts_empty),
            )
        }
    }
}

@Suppress("LongMethod", "MagicNumber") // A column table; the numbers are its proportions.
private fun alertColumns(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<CardAlert>> = listOf(
    textColumn(str(S.alert), ColumnWidth.Weight(2f)) { it.title.ifBlank { it.type ?: str(S.alert) } },
    textColumn(str(S.desktop_detail), ColumnWidth.Weight(2f), muted = true) { alert ->
        // A resolved alert says what was found; an open one what it saw.
        alert.resolution?.takeIf { alert.status == RESOLVED }
            ?.let { str(S.desktop_card_resolution_value, it) }
            ?: alert.description ?: "—"
    },
    textColumn(str(S.desktop_card_at_stake), ColumnWidth.Weight(1f), numeric = true) {
        money(it.savings, state.currency)
    },
    textColumn(str(S.desktop_card_raised), ColumnWidth.Weight(1f), muted = true) { date(it.at) },
    TableColumn(
        header = str(S.desktop_card_severity),
        width = ColumnWidth.Fixed(SEVERITY_COLUMN),
        cell = { row -> ZillitStatusPill(row.severity.label, tone = row.severity.tone, dot = true) },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ALERT_ACTION_COLUMN),
        cell = { row ->
            if (row.isOpen && state.viewer.isAccountant) {
                AlertActions(state, row, onEvent)
            } else {
                ZillitStatusPill(label = row.statusLabel(), tone = row.statusTone(), dot = row.isOpen)
            }
        },
    ),
)

/**
 * What an open alert offers: Investigate while it is new — after which it
 * reads "Under investigation" so a second accountant does not pick it up —
 * then Resolve or Dismiss either way.
 */
@Suppress("LongMethod") // Three buttons, each with its own confirmation.
@Composable
private fun AlertActions(state: CardUiState, row: CardAlert, onEvent: (CardEvent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.isInvestigating) {
            ZillitStatusPill(label = str(S.ah_under_investigation_toast), tone = StatusTone.Pending, dot = true)
        } else {
            ZillitButton(
                text = str(S.desktop_card_investigate),
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.InvestigateAlert,
                                row.id,
                                str(S.desktop_card_mark_investigating),
                                str(S.desktop_card_investigate_note),
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        ZillitButton(
            text = str(S.desktop_resolve),
            onClick = {
                onEvent(
                    CardEvent.Ask(
                        CardPrompt.WithReason(
                            CardReasonAction.ResolveAlert,
                            row.id,
                            str(S.desktop_card_resolve_this_alert),
                            str(S.desktop_card_what_was_found),
                        ),
                    ),
                )
            },
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
        ZillitButton(
            text = str(S.sync_action_dismiss),
            onClick = {
                onEvent(
                    CardEvent.Ask(
                        CardPrompt.Confirm(
                            CardConfirmAction.DismissAlert,
                            row.id,
                            str(S.desktop_card_dismiss_this_alert),
                            str(S.desktop_card_dismiss_alert_note),
                        ),
                    ),
                )
            },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
    }
}

/** The web's status badges (`SmartAlertsPage.jsx:27-33`). */
private fun CardAlert.statusLabel(): String = when (status) {
    CardAlert.ACTIVE -> str(S.active)
    CardAlert.INVESTIGATING -> str(S.desktop_investigating)
    RESOLVED -> str(S.ah_alert_filter_resolved)
    DISMISSED -> str(S.ah_dismissed_toast)
    AUTO_CLOSED -> str(S.desktop_card_auto_closed)
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { str(S.ah_status_closed) }
}

private fun CardAlert.statusTone(): StatusTone = when (status) {
    CardAlert.ACTIVE -> StatusTone.Rejected
    CardAlert.INVESTIGATING -> StatusTone.Pending
    RESOLVED -> StatusTone.Done
    else -> StatusTone.Neutral
}

private const val RESOLVED = "resolved"
private const val DISMISSED = "dismissed"
private const val AUTO_CLOSED = "auto_closed"
private val SEVERITY_COLUMN = 110.dp
private val ALERT_ACTION_COLUMN = 320.dp
