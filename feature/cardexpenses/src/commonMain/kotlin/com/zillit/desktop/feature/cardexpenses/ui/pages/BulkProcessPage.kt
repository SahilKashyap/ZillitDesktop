package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cardexpenses.domain.BulkItem
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMode
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.WorkflowStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Coding and posting many card receipts at once.
 *
 * ## Blank means "keep"
 *
 * The override fields at the bottom apply to every ticked row, and an empty
 * one leaves each row's own coding alone. That distinction is the whole
 * screen: it is the difference between correcting forty rows and overwriting
 * forty rows, and it is why the fields say so in as many words rather than
 * relying on the reader to infer it from an empty box.
 *
 * ## Rows assigned to someone else cannot be ticked
 *
 * They are somebody's open work. Posting them from here would take a decision
 * out of their hands without telling them, so the checkbox is simply absent
 * and the row says who has it.
 */
@Suppress("LongMethod") // The grid and the override bar are one screen; splitting hides the link.
@Composable
fun BulkProcessPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val selectable = state.selectableBulkItems
    val allTicked = selectable.isNotEmpty() && state.selection.containsAll(selectable.map { it.id })

    FixedPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = if (allTicked) "Clear selection" else "Select all ${selectable.size}",
                onClick = { onEvent(CardEvent.SelectAllBulk) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = selectable.isNotEmpty(),
            )
            ZillitText(
                text = "${state.bulkItems.size} ready · ${state.selection.size} selected",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (state.selection.isNotEmpty()) {
                ZillitText(
                    text = Money.format(state.bulkSelectedTotal, state.bulkItems.firstOrNull()?.currency),
                    style = ZillitTheme.typography.titleSmall,
                )
            }
        }

        val locked = state.bulkItems.size - selectable.size
        if (locked > 0) {
            ZillitNotice(
                text = "$locked row(s) are assigned to someone else and cannot be posted from here.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Users,
            )
        }

        ZillitSectionCard(
            title = "Ready to process",
            icon = ZillitIcons.Grid,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = state.bulkItems,
                columns = bulkColumns(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "Nothing waiting to process",
                emptyMessage = "Coded receipts that are ready to post appear here.",
            )
        }

        if (state.selection.isNotEmpty()) {
            OverrideBar(state, onEvent)
        }
    }
}

@Suppress("LongMethod") // The override fields and what they apply to, together.
@Composable
private fun OverrideBar(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val coding = state.bulkCoding

    ZillitSectionCard(
        title = "Apply to all ${state.selection.size} selected",
        icon = ZillitIcons.Edit,
        meta = "Leave a field blank to keep each row's own coding",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitTextField(
                value = coding.nominalCode.orEmpty(),
                onValueChange = {
                    onEvent(CardEvent.EditBulkCoding(coding.copy(nominalCode = it.takeIf(String::isNotBlank))))
                },
                label = "Nominal code",
                placeholder = "Keep each row's",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = coding.episode.orEmpty(),
                onValueChange = {
                    onEvent(CardEvent.EditBulkCoding(coding.copy(episode = it.takeIf(String::isNotBlank))))
                },
                label = "Episode",
                placeholder = "Keep each row's",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = coding.taxType.orEmpty(),
                onValueChange = {
                    onEvent(CardEvent.EditBulkCoding(coding.copy(taxType = it.takeIf(String::isNotBlank))))
                },
                label = "Tax treatment",
                placeholder = "Per line",
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = "Top-up",
                style = ZillitTheme.typography.bodyMedium,
            )
            // Four choices, not a tick box: restoring a float and topping up by
            // what was spent are different amounts on any card that was not at
            // its limit, and the wrong one leaves the holder short.
            ZillitSelect(
                value = coding.topUp,
                options = TopUpMode.entries,
                onSelect = { onEvent(CardEvent.EditBulkCoding(coding.copy(topUp = it))) },
                label = { it.label },
                modifier = Modifier.width(TOPUP_WIDTH),
            )
            Spacer(Modifier.weight(1f))
            ZillitText(
                text = Money.format(state.bulkSelectedTotal, state.bulkItems.firstOrNull()?.currency),
                style = ZillitTheme.typography.titleMedium,
            )
            ZillitButton(
                text = "Post ${state.selection.size} item(s)",
                onClick = { onEvent(CardEvent.BulkPost) },
                variant = ButtonVariant.Danger,
                loading = state.busy,
                enabled = !state.busy,
            )
        }
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun bulkColumns(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<BulkItem>> = listOf(
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(CHECK_COLUMN),
        cell = { row ->
            if (row.selectableBy(state.viewer.userId)) {
                ZillitCheckbox(
                    checked = row.id in state.selection,
                    onCheckedChange = { onEvent(CardEvent.ToggleSelection(row.id)) },
                )
            } else {
                // No checkbox at all rather than a disabled one: the row says
                // why in its own status column, and a dead control invites
                // clicking.
                ZillitText(
                    text = "—",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        },
    ),
    textColumn("Description", ColumnWidth.Weight(1.8f)) { it.description.ifBlank { it.merchant ?: "Receipt" } },
    textColumn("Holder", ColumnWidth.Weight(1.1f), muted = true) { it.holderName.ifBlank { "—" } },
    textColumn("Card", ColumnWidth.Weight(0.8f), muted = true) {
        it.cardLastFour?.let { last -> "•••• $last" } ?: "—"
    },
    textColumn("Code", ColumnWidth.Weight(0.8f), muted = true) { it.nominalCode ?: "Uncoded" },
    textColumn("Date", ColumnWidth.Weight(1f), muted = true) { date(it.date) },
    textColumn("Amount", ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) },
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { row ->
            Column {
                WorkflowStatusPill(row.status)
                if (!row.selectableBy(state.viewer.userId)) {
                    ZillitStatusPill(label = "Assigned elsewhere", tone = StatusTone.Neutral)
                }
                if (row.urgent) {
                    ZillitStatusPill(label = "Urgent", tone = StatusTone.Rejected)
                }
            }
        },
    ),
)

private val TOPUP_WIDTH = 180.dp
private val CHECK_COLUMN = 40.dp
private val STATUS_COLUMN = 150.dp
