package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
                text = str(S.desktop_card_orphan_rows_note, orphans),
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
                text = str(S.desktop_card_rows_ticked, rows.size, state.selection.size),
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
                    text = str(S.desktop_card_accept_into_ledger, ticked.size),
                    onClick = { onEvent(CardEvent.ProcessImportRows) },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = str(S.desktop_card_ask_holders_for_receipts, sendable),
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
            title = str(S.desktop_card_statement_rows),
            icon = ZillitIcons.Ledger,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = rowColumns(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = if (state.openImportId == null) {
                    str(S.desktop_card_no_statement_selected)
                } else {
                    str(S.desktop_card_no_rows)
                },
                emptyMessage = if (state.openImportId == null) {
                    str(S.desktop_card_import_or_pick)
                } else {
                    str(S.desktop_card_statement_processed)
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
    ZillitSectionCard(title = str(S.desktop_card_import_a_statement), icon = ZillitIcons.Upload) {
        ZillitText(
            text = str(S.desktop_card_import_help),
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
                label = str(S.desktop_card_statement_currency),
                placeholder = str(S.desktop_card_blank_for_project_default),
                helperText = str(S.desktop_card_statement_currency_helper),
                modifier = Modifier.width(CURRENCY_WIDTH),
            )
            ZillitButton(
                text = str(S.desktop_card_choose_statement_file),
                onClick = { onEvent(CardEvent.ImportStatement) },
                leadingIcon = ZillitIcons.Upload,
                enabled = state.canAttachFiles && !state.busy,
                loading = state.uploading || state.busy,
            )
            if (!state.canAttachFiles) {
                ZillitText(
                    text = str(S.desktop_card_no_file_picker_build),
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
        title = str(S.desktop_card_imported_statements),
        icon = ZillitIcons.Upload,
        meta = str(S.desktop_card_on_file_count, state.imports.size),
        padded = false,
    ) {
        ZillitDataTable(
            rows = state.imports.take(RECENT_IMPORTS),
            columns = importColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(CardEvent.OpenImport(it.id)) },
            isSelected = { it.id == state.openImportId },
            emptyTitle = str(S.desktop_card_nothing_imported_yet),
            emptyMessage = str(S.desktop_card_upload_statement_hint),
            virtualised = false,
        )
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun importColumns(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<StatementImport>> = listOf(
    textColumn(str(S.file), ColumnWidth.Weight(2f)) { it.filename ?: it.id },
    textColumn(str(S.desktop_card_rows_header), ColumnWidth.Weight(0.6f), numeric = true) { it.rowCount.toString() },
    textColumn(str(S.desktop_matched), ColumnWidth.Weight(0.7f), numeric = true) { it.matchedCount.toString() },
    textColumn(str(S.desktop_imported), ColumnWidth.Weight(1f), muted = true) { date(it.importedAt) },
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(OPEN_COLUMN),
        cell = { row ->
            ZillitStatusPill(
                label = if (row.id == state.openImportId) str(S.desktop_card_reviewing) else str(S.dd_action_open),
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
                text = str(S.desktop_card_re_match),
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.RerunMatching,
                                row.id,
                                str(S.desktop_card_rerun_matching),
                                str(S.desktop_card_rerun_matching_note),
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
    textColumn(str(S.ah_merchant), ColumnWidth.Weight(1.8f)) { it.merchant.ifBlank { it.description ?: "—" } },
    textColumn(str(S.ah_my_cards), ColumnWidth.Weight(0.8f), muted = true) {
        it.cardLastFour?.let { last -> "•••• $last" } ?: "—"
    },
    TableColumn(
        header = str(S.ah_holder),
        width = ColumnWidth.Weight(1.2f),
        cell = { row ->
            if (row.holderId.isNullOrBlank()) {
                ZillitStatusPill(label = str(S.desktop_dm_unmatched), tone = StatusTone.Pending)
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
    textColumn(str(S.date), ColumnWidth.Weight(1f), muted = true) { date(it.date) },
    textColumn(str(S.amount), ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) },
    TableColumn(
        header = str(S.status),
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

private const val RECENT_IMPORTS = 5
private val CHECK_COLUMN = 40.dp
private val STATUS_COLUMN = 130.dp
private val CURRENCY_WIDTH = 280.dp
private val REMATCH_COLUMN = 110.dp
private val OPEN_COLUMN = 110.dp
