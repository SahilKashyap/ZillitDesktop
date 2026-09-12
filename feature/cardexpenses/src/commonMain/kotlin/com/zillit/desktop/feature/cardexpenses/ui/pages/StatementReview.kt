package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import com.zillit.desktop.feature.cardexpenses.ui.CardConfirmAction
import com.zillit.desktop.feature.cardexpenses.ui.CardPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptLine
import com.zillit.desktop.feature.cardexpenses.domain.StatementImport
import com.zillit.desktop.feature.cardexpenses.domain.StatementRow
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Reviewing an imported statement, row by row.
 *
 * ## Two different actions, deliberately separate
 *
 * **Accept** brings rows into the transaction ledger. **Send to holders** asks
 * the people who spent the money for their receipts. They are not the same
 * decision and doing them with one button is how a statement gets sent out
 * before anyone has looked at it, so they are two buttons and each says what
 * it will do to how many rows.
 *
 * A row the matcher could not attribute has nobody to send to; it is shown as
 * such rather than quietly dropped from the send.
 */
@Suppress("LongMethod") // The picker, the two actions and the rows are one review.
@Composable
fun StatementReviewPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.importRows
    val ticked = rows.filter { it.id in state.selection }
    val sendable = ticked.count { it.canSubmit }
    val orphans = rows.count { it.holderId.isNullOrBlank() }

    FixedPage {
        StatementUploadPanel(state, onEvent)
        ImportPicker(state, onEvent)

        if (orphans > 0) {
            ZillitNotice(
                text = "$orphans row(s) could not be matched to a cardholder. " +
                    "They can be accepted into the ledger, but nobody can be asked for a receipt.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = "${rows.size} row(s) · ${state.selection.size} ticked",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (ticked.isNotEmpty()) {
                ZillitText(
                    text = Money.format(ticked.sumOf { it.amount }, rows.firstOrNull()?.currency),
                    style = ZillitTheme.typography.titleSmall,
                )
                ZillitButton(
                    text = "Accept ${ticked.size} into the ledger",
                    onClick = { onEvent(CardEvent.ProcessImportRows) },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = "Ask $sendable holder(s) for receipts",
                    onClick = { onEvent(CardEvent.SubmitRowsToHolders) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    // Nothing to send is not an error worth a dialog — the
                    // count on the button already says so.
                    enabled = sendable > 0 && !state.busy,
                )
            }
        }

        ZillitSectionCard(
            title = "Statement rows",
            icon = ZillitIcons.Ledger,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = rowColumns(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = if (state.openImportId == null) "No statement selected" else "No rows",
                emptyMessage = if (state.openImportId == null) {
                    "Import a statement, or pick one above to review its rows."
                } else {
                    "Every row on this statement has been processed."
                },
            )
        }
    }
}

/**
 * Choosing the statement file, and saying what it is denominated in.
 *
 * The service ingests from storage rather than from a multipart upload, so the
 * two steps are this application's: put the file where the server can read it,
 * then hand over the pointer. Before this the only way to reach that route was
 * a text box asking for a storage key, which nobody outside the accounts
 * server could have supplied — so a statement could be reviewed here but never
 * imported here.
 */
@Composable
private fun StatementUploadPanel(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    ZillitSectionCard(title = "Import a statement", icon = ZillitIcons.Upload) {
        ZillitText(
            text = "Choose the file the bank sent. Its rows are read and matched against the receipts " +
                "already uploaded, then reviewed here before anything reaches the ledger.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            ZillitTextField(
                value = state.statementCurrency,
                onValueChange = { onEvent(CardEvent.EditStatementCurrency(it.uppercase())) },
                label = "Statement currency",
                placeholder = "Leave blank for the project default",
                helperText = "What the statement is denominated in, if it is not the project's own.",
                modifier = Modifier.width(CURRENCY_WIDTH),
            )
            ZillitButton(
                text = "Choose a statement file",
                onClick = { onEvent(CardEvent.ImportStatement) },
                leadingIcon = ZillitIcons.Upload,
                enabled = state.canAttachFiles && !state.busy,
                loading = state.uploading || state.busy,
            )
            if (!state.canAttachFiles) {
                ZillitText(
                    text = "No file picker is available in this build.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

/** The statements on file, newest first, with the open one marked. */
@Composable
private fun ImportPicker(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    ZillitSectionCard(
        title = "Imported statements",
        icon = ZillitIcons.Upload,
        meta = "${state.imports.size} on file",
        padded = false,
    ) {
        ZillitDataTable(
            rows = state.imports.take(RECENT_IMPORTS),
            columns = importColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(CardEvent.OpenImport(it.id)) },
            isSelected = { it.id == state.openImportId },
            emptyTitle = "Nothing imported yet",
            emptyMessage = "Upload a statement to start reviewing its rows.",
            virtualised = false,
        )
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun importColumns(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<StatementImport>> = listOf(
    textColumn("File", ColumnWidth.Weight(2f)) { it.filename ?: it.id },
    textColumn("Rows", ColumnWidth.Weight(0.6f), numeric = true) { it.rowCount.toString() },
    textColumn("Matched", ColumnWidth.Weight(0.7f), numeric = true) { it.matchedCount.toString() },
    textColumn("Imported", ColumnWidth.Weight(1f), muted = true) { date(it.importedAt) },
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(OPEN_COLUMN),
        cell = { row ->
            ZillitStatusPill(
                label = if (row.id == state.openImportId) "Reviewing" else "Open",
                tone = if (row.id == state.openImportId) StatusTone.Progress else StatusTone.Neutral,
            )
        },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(REMATCH_COLUMN),
        cell = { row ->
            // Per statement rather than global, because that is what the
            // server takes — and because re-matching a year of imports is not
            // something anybody should reach by accident.
            ZillitButton(
                text = "Re-match",
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.RerunMatching,
                                row.id,
                                "Re-run matching",
                                "Every unmatched receipt is compared against this statement again. " +
                                    "Matches already confirmed are left alone.",
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        },
    ),
)

@Suppress("MagicNumber") // Column proportions.
private fun rowColumns(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<StatementRow>> = listOf(
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(CHECK_COLUMN),
        cell = { row ->
            // Only untouched rows are actionable; a processed one has already
            // become a transaction and cannot be accepted twice.
            if (row.isNew) {
                ZillitCheckbox(
                    checked = row.id in state.selection,
                    onCheckedChange = { onEvent(CardEvent.ToggleSelection(row.id)) },
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
    textColumn("Merchant", ColumnWidth.Weight(1.8f)) { it.merchant.ifBlank { it.description ?: "—" } },
    textColumn("Card", ColumnWidth.Weight(0.8f), muted = true) {
        it.cardLastFour?.let { last -> "•••• $last" } ?: "—"
    },
    TableColumn(
        header = "Holder",
        width = ColumnWidth.Weight(1.2f),
        cell = { row ->
            if (row.holderId.isNullOrBlank()) {
                ZillitStatusPill(label = "Unmatched", tone = StatusTone.Pending)
            } else {
                ZillitText(
                    // Never the raw id: an ObjectId on screen looks like corruption.
                    text = state.personName(row.holderId, row.holderName.orEmpty()),
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        },
    ),
    textColumn("Date", ColumnWidth.Weight(1f), muted = true) { date(it.date) },
    textColumn("Amount", ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) },
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { row ->
            ZillitStatusPill(
                label = row.status.replaceFirstChar { it.uppercase() },
                tone = if (row.isNew) StatusTone.Pending else StatusTone.Done,
                dot = true,
            )
        },
    ),
)

/**
 * Splitting one card receipt across nominal codes.
 *
 * Simpler than the cash module's editor — card splits are a flat set, with no
 * parent/child tree — but held to the same rule: the parts have to add up to
 * the receipt, the difference is on screen, and the save is refused until it
 * is zero.
 */
@Suppress("LongMethod") // The balance and the rows it explains belong together.
@Composable
fun SplitEditorDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.splits

    ZillitDialogShell(
        title = "Split this receipt",
        subtitle = draft?.let { "Receipt total ${Money.format(it.receiptGross, it.currency)}" },
        icon = ZillitIcons.Ledger,
        visible = draft != null,
        width = EDITOR_WIDTH,
        onDismiss = { onEvent(CardEvent.CloseSplits) },
    ) {
        if (draft == null) return@ZillitDialogShell

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = "SPLIT",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitText(
                    text = Money.format(draft.total, draft.currency),
                    style = ZillitTheme.typography.titleMedium,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = if (draft.remaining < 0) "OVER BY" else "LEFT TO SPLIT",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitText(
                    text = Money.format(kotlin.math.abs(draft.remaining), draft.currency),
                    style = ZillitTheme.typography.titleMedium,
                    color = if (draft.balances) ZillitTheme.colors.success else ZillitTheme.colors.danger,
                )
            }
            ZillitStatusPill(
                label = if (draft.balances) "Balanced" else "Does not add up",
                tone = if (draft.balances) StatusTone.Done else StatusTone.Rejected,
                dot = true,
            )
        }

        ZillitDivider()

        draft.lines.forEachIndexed { index, line ->
            SplitRow(
                index = index,
                line = line,
                removable = draft.lines.size > 1,
                onChange = { onEvent(CardEvent.EditSplit(index, it)) },
                onRemove = { onEvent(CardEvent.RemoveSplit(index)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitButton(
                text = "Add a split",
                onClick = { onEvent(CardEvent.AddSplit) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(CardEvent.CloseSplits) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save splits",
                onClick = { onEvent(CardEvent.SaveSplits) },
                enabled = draft.balances && !state.busy,
                loading = state.busy,
            )
        }
    }
}

@Composable
private fun SplitRow(
    index: Int,
    line: ReceiptLine,
    removable: Boolean,
    onChange: (ReceiptLine) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        ZillitTextField(
            value = line.description,
            onValueChange = { onChange(line.copy(description = it)) },
            label = if (index == 0) "What this covers" else null,
            modifier = Modifier.weight(2f),
        )
        ZillitTextField(
            value = line.nominalCode,
            onValueChange = { onChange(line.copy(nominalCode = it)) },
            label = if (index == 0) "Nominal code" else null,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = if (line.net == 0.0) "" else line.net.toString(),
            onValueChange = { onChange(line.copy(net = it.trim().toDoubleOrNull() ?: 0.0)) },
            label = if (index == 0) "Net" else null,
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = if (line.taxAmount == 0.0) "" else line.taxAmount.toString(),
            onValueChange = { onChange(line.copy(taxAmount = it.trim().toDoubleOrNull() ?: 0.0)) },
            label = if (index == 0) "VAT" else null,
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        if (removable) {
            ZillitButton(
                text = "",
                onClick = onRemove,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
            )
        }
    }
}

private const val RECENT_IMPORTS = 5
private val CHECK_COLUMN = 40.dp
private val STATUS_COLUMN = 130.dp
private val CURRENCY_WIDTH = 280.dp
private val REMATCH_COLUMN = 110.dp
private val OPEN_COLUMN = 110.dp
private val EDITOR_WIDTH = 860.dp
