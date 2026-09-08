package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
fun AnalyticsPage(state: CardUiState) {
    val analytics = state.analytics

    ScrollingPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitStatTile(
                label = "Total spend",
                value = money(analytics?.totalSpend, null),
                sub = "Across every card",
                icon = ZillitIcons.BarChart,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Transactions",
                value = analytics?.transactionCount?.toString() ?: "—",
                sub = "Statement lines",
                tone = StatusTone.Progress,
                icon = ZillitIcons.Ledger,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Average",
                value = money(analytics?.averageTransaction, null),
                sub = "Per transaction",
                icon = ZillitIcons.Receipt,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            BreakdownCard("By category", analytics?.byCategory.orEmpty(), StatusTone.Progress, Modifier.weight(1f))
            BreakdownCard("By cardholder", analytics?.byHolder.orEmpty(), StatusTone.Done, Modifier.weight(1f))
        }

        BreakdownCard("By month", analytics?.byMonth.orEmpty(), StatusTone.Escalated, Modifier.fillMaxWidth())
    }
}

@Composable
private fun BreakdownCard(
    title: String,
    slices: List<AnalyticsSlice>,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    ZillitSectionCard(title = title, icon = ZillitIcons.BarChart, modifier = modifier) {
        if (slices.isEmpty()) {
            ZillitEmptyState(
                title = "No spend in this period",
                message = "Bars appear as transactions are imported and coded.",
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
                        text = slice.label.ifBlank { "Unlabelled" },
                        style = ZillitTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = Money.format(slice.amount, null),
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
 * matters, and a chronological list buries it under routine noise.
 */
@Composable
fun AlertsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.alerts.sortedBy { it.severity.ordinal }
    val open = rows.count { it.status == OPEN }

    FixedPage {
        if (open > 0) {
            ZillitNotice(
                text = "$open alert(s) open. Resolving one records why, so the next person does not re-investigate.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Bell,
            )
        }

        ZillitSectionCard(
            title = "Smart alerts",
            icon = ZillitIcons.Bell,
            meta = "${rows.size} total",
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = alertColumns(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "Nothing flagged",
                emptyMessage = "Duplicate receipts, personal spend and limit breaches are raised here.",
            )
        }
    }
}

@Suppress("LongMethod", "MagicNumber") // A column table; the numbers are its proportions.
private fun alertColumns(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<CardAlert>> = listOf(
    textColumn("Alert", ColumnWidth.Weight(2f)) { it.title.ifBlank { it.type ?: "Alert" } },
    textColumn("Detail", ColumnWidth.Weight(2f), muted = true) { it.description ?: "—" },
    textColumn("At stake", ColumnWidth.Weight(1f), numeric = true) { money(it.savings, null) },
    textColumn("Raised", ColumnWidth.Weight(1f), muted = true) { date(it.at) },
    TableColumn(
        header = "Severity",
        width = ColumnWidth.Fixed(SEVERITY_COLUMN),
        cell = { row -> ZillitStatusPill(row.severity.label, tone = row.severity.tone, dot = true) },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ALERT_ACTION_COLUMN),
        cell = { row ->
            if (row.status == OPEN) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitButton(
                        text = "Resolve",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.WithReason(
                                        CardReasonAction.ResolveAlert,
                                        row.id,
                                        "Resolve this alert",
                                        "What was found",
                                    ),
                                ),
                            )
                        },
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = "Dismiss",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.DismissAlert,
                                        row.id,
                                        "Dismiss this alert",
                                        "It closes without an explanation recorded.",
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                }
            } else {
                ZillitStatusPill(
                    label = row.status.replaceFirstChar { it.uppercase() }.ifBlank { "Closed" },
                    tone = StatusTone.Neutral,
                )
            }
        },
    ),
)

/** The production's card configuration. Senior accountants only. */
@Suppress("LongMethod") // A settings catalogue: splitting it hides what the page offers.
@Composable
fun CardSettingsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.settingsDraft ?: state.settings
    if (draft == null) {
        ScrollingPage { ZillitNotice(text = "Loading the project's card settings…") }
        return
    }
    val dirty = draft != state.settings

    ScrollingPage {
        ZillitNotice(
            text = "These settings apply to every card on this project.",
            tone = StatusTone.Progress,
            icon = ZillitIcons.Info,
        )

        ZillitSectionCard(title = "Workflow", icon = ZillitIcons.Shield) {
            Toggle(
                checked = draft.codingRequired,
                label = "Receipts must be coded before approval",
                detail = "Turns on the Coding Queue for department coordinators.",
                onChange = { onEvent(CardEvent.EditSettings(draft.copy(codingRequired = it))) },
            )
            Toggle(
                checked = draft.requireSeniorSignOff,
                label = "Senior sign-off before posting",
                detail = "Receipts cannot be posted until a senior accountant has cleared them.",
                onChange = { onEvent(CardEvent.EditSettings(draft.copy(requireSeniorSignOff = it))) },
            )
        }

        ZillitSectionCard(title = "Matching", icon = ZillitIcons.Ledger) {
            Toggle(
                checked = draft.autoMatchEnabled,
                label = "Match receipts to the statement automatically",
                detail = "Off means every receipt is matched by hand in the Receipt Inbox.",
                onChange = { onEvent(CardEvent.EditSettings(draft.copy(autoMatchEnabled = it))) },
            )
            ZillitTextField(
                value = draft.autoMatchThreshold.toString(),
                onValueChange = { text ->
                    // Clamped rather than validated on save: a threshold above
                    // 100 matches nothing and one below zero matches everything,
                    // and both are easier to prevent than to explain.
                    text.trim().toIntOrNull()?.let { value ->
                        onEvent(
                            CardEvent.EditSettings(
                                draft.copy(autoMatchThreshold = value.coerceIn(0, MAX_THRESHOLD)),
                            ),
                        )
                    }
                },
                label = "Confidence needed to match automatically (%)",
                keyboardType = KeyboardType.Number,
                enabled = draft.autoMatchEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ZillitSectionCard(title = "Exception detection", icon = ZillitIcons.Bell) {
            Toggle(
                checked = draft.duplicateDetection,
                label = "Flag possible duplicate receipts",
                detail = "Raises a Smart Alert when two receipts look like the same purchase.",
                onChange = { onEvent(CardEvent.EditSettings(draft.copy(duplicateDetection = it))) },
            )
            Toggle(
                checked = draft.personalSpendDetection,
                label = "Flag possible personal spend",
                detail = "Raises a Smart Alert on merchants that rarely appear on project spend.",
                onChange = { onEvent(CardEvent.EditSettings(draft.copy(personalSpendDetection = it))) },
            )
        }

        if (draft.providers.isNotEmpty()) {
            ZillitSectionCard(
                title = "Card providers",
                icon = ZillitIcons.CreditCard,
                meta = "${draft.providers.size} configured",
            ) {
                draft.providers.forEach { provider ->
                    ZillitText(text = provider.name, style = ZillitTheme.typography.bodyMedium)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = "Save settings",
                onClick = { onEvent(CardEvent.SaveSettings) },
                enabled = dirty && !state.busy,
                loading = state.busy,
            )
            if (dirty) {
                ZillitButton(
                    text = "Discard changes",
                    onClick = { state.settings?.let { onEvent(CardEvent.EditSettings(it)) } },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitText(
                    text = "Unsaved changes",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.warning,
                )
            }
        }
    }
}

@Composable
private fun Toggle(checked: Boolean, label: String, detail: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitCheckbox(checked = checked, onCheckedChange = onChange)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = label, style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

private const val OPEN = "open"
private const val MAX_THRESHOLD = 100
private val SEVERITY_COLUMN = 110.dp
private val ALERT_ACTION_COLUMN = 180.dp
