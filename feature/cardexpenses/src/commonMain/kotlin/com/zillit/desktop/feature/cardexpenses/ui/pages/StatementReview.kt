package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.externalFileDrop
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.CrewSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ImportedRow
import com.zillit.desktop.feature.cardexpenses.domain.StatementFile
import com.zillit.desktop.feature.cardexpenses.domain.StatementImportResult
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.ImportState
import com.zillit.desktop.feature.cardexpenses.ui.InboxEvent
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.tone

/**
 * Import Statement (`pages/ImportStatementPage.jsx`).
 *
 * Pick or drop a CSV, OFX or QIF; state its currency in the dialog that
 * follows; the server extracts the rows, creates the statement and imports
 * every row itself. What is left to do here is send the new rows to the
 * cardholders — Submit to Crew Portals — and there is no separate "accept into
 * the ledger" step: the web has none (`processImport` is dead there).
 */
@Composable
fun StatementReviewPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val import = state.inbox.import
    val result = import.result
    Column(modifier = Modifier.fillMaxSize()) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            if (result == null) {
                DropZone(state, onEvent)
            } else {
                ImportedStatement(state, import, result, onEvent)
            }
        }
        // Out of the scroll, so the last rows are never under it; gone once
        // the rows have been sent (`ImportStatementPage.jsx:463-515`).
        if (result != null && import.selected.isNotEmpty() && import.submission == null) {
            SubmitBar(state, import, result, onEvent)
        }
    }
}

/** The drop zone: click to browse, or drop a file from the OS (`ImportStatementPage.jsx:280-310`). */
@Suppress("LongMethod") // The zone and its four lines.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DropZone(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var hovering by remember { mutableStateOf(false) }
    val enabled = state.inbox.canPickStatements && !state.inbox.import.importing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (hovering) colors.accentSoft else colors.accentSoft.copy(alpha = ZONE_WASH))
            .border(2.dp, colors.accent.copy(alpha = if (hovering) 1f else ZONE_RIM), ZillitTheme.shapes.large)
            .externalFileDrop(
                enabled = enabled,
                onHover = { hovering = it },
                onFiles = { files ->
                    files.firstOrNull()?.let { onEvent(InboxEvent.DropStatement(StatementFile(it.name, it.bytes))) }
                },
            )
            .clickable(enabled = enabled) { onEvent(InboxEvent.PickStatement) }
            .padding(vertical = ZONE_PADDING, horizontal = ZillitTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(ZONE_ICON_WELL)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.accent.copy(alpha = ZONE_RIM), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(ZillitIcons.Inbox, size = ZONE_GLYPH, tint = colors.accent)
        }
        ZillitText(
            str(S.desktop_ce_inbox_drop_here),
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
        )
        ZillitText(
            text = if (state.inbox.canPickStatements) {
                str(S.desktop_ce_inbox_browse_hint)
            } else {
                str(S.desktop_card_no_file_picker)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            FORMATS.forEach { format ->
                ZillitText(
                    text = format,
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.small)
                        .background(colors.surface)
                        .border(1.dp, colors.border, ZillitTheme.shapes.small)
                        .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                )
            }
        }
    }
}

/** The banner, the four tiles, the details grid, the rows, and what was sent. */
@Composable
private fun ImportedStatement(
    state: CardUiState,
    import: ImportState,
    result: StatementImportResult,
    onEvent: (CardEvent) -> Unit,
) {
    val currency = import.statementCurrency(result)
    result.rowsProcessed?.let { processed -> SuccessBanner(processed, result, onEvent) }
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        val summary = result.summary
        ZillitStatTile(
            label = str(S.desktop_ce_inbox_total_rows),
            value = summary.totalRows.toString(),
            sub = str(S.desktop_ce_inbox_rows_extracted),
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_imported),
            value = (result.rowsProcessed?.takeIf { it > 0 } ?: summary.newCount).toString(),
            sub = str(S.desktop_ce_inbox_new_transactions),
            tone = StatusTone.Done,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_ce_inbox_duplicates),
            value = summary.duplicateCount.toString(),
            sub = str(S.desktop_ce_inbox_already_exists),
            tone = StatusTone.Pending,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.txt_badge_declined),
            value = summary.declinedCount.toString(),
            sub = str(S.desktop_ce_inbox_rejected_by_bank),
            tone = StatusTone.Rejected,
            modifier = Modifier.weight(1f),
        )
    }
    StatementDetails(result, currency)
    if (result.rows.isEmpty()) {
        ZillitText(
            text = str(S.desktop_ce_inbox_no_rows),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl),
        )
    } else {
        ImportedRows(state, import, result.rows, currency, onEvent)
    }
    import.submission?.let { SubmittedCard(state, it, currency, onEvent) }
}

@Composable
private fun SuccessBanner(processed: Int, result: StatementImportResult, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.successSoft)
            .border(1.dp, colors.success.copy(alpha = ZONE_RIM), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(ZillitIcons.Check, size = BANNER_GLYPH, tint = colors.success)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = str(
                    if (processed == 1) S.desktop_ce_inbox_imported_one else S.desktop_ce_inbox_imported_many,
                    processed,
                ),
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = colors.success,
            )
            ZillitText(
                text = str(S.desktop_ce_inbox_statement_created, period(result)),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        ZillitButton(
            text = str(S.desktop_ce_inbox_new_import),
            onClick = { onEvent(InboxEvent.NewImport) },
            size = ButtonSize.Small,
        )
    }
}

/**
 * File, Issuer, Period, then Account Name, Account No. and Sort Code only
 * where the statement carries them, and Currency (`ImportStatementPage.jsx:374-394`).
 */
@Composable
private fun StatementDetails(result: StatementImportResult, currency: String?) {
    val info = result.info
    val cells = listOfNotNull(
        str(S.file) to (info.fileName ?: EM_DASH),
        str(S.desktop_ce_inbox_issuer) to (info.issuer ?: EM_DASH),
        str(S.cr_meta_period) to period(result),
        info.accountName?.let { str(S.desktop_pc_account_name) to it },
        info.accountNumber?.let { str(S.desktop_dm_account_no) to it },
        info.sortCode?.let { str(S.ah_lbl_sort_code) to it },
        str(S.ah_lbl_currency) to (currency ?: EM_DASH),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        cells.chunked(DETAIL_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        ZillitText(
                            label.uppercase(),
                            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = ZillitTheme.colors.textMuted,
                        )
                        ZillitText(value, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
                repeat(DETAIL_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The imported rows (`TransactionTable.jsx` with the import columns): only a
 * `new` row can be ticked, and a click anywhere on it toggles it.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Header and rows of one table.
@Composable
private fun ImportedRows(
    state: CardUiState,
    import: ImportState,
    rows: List<ImportedRow>,
    currency: String?,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val fresh = rows.filter { it.isNew }
    val allSelected = fresh.isNotEmpty() && fresh.all { it.id in import.selected }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(ROW_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(SELECT_COLUMN)) {
                TriStateBox(
                    state = when {
                        allSelected -> ToggleableState.On
                        import.selected.isNotEmpty() -> ToggleableState.Indeterminate
                        else -> ToggleableState.Off
                    },
                    enabled = fresh.isNotEmpty(),
                    onClick = { onEvent(InboxEvent.ToggleAllImportRows) },
                )
            }
            HeaderText("#", Modifier.width(INDEX_COLUMN))
            HeaderText(str(S.date), Modifier.width(DATE_COLUMN))
            HeaderText(str(S.ah_merchant), Modifier.weight(1f))
            HeaderText(str(S.desktop_card_card_holder), Modifier.width(HOLDER_COLUMN))
            HeaderText(str(S.ah_my_cards), Modifier.width(CARD_COLUMN))
            HeaderText(str(S.amount), Modifier.width(AMOUNT_COLUMN), TextAlign.End)
            Spacer(Modifier.width(ZillitTheme.spacing.md))
            HeaderText(str(S.status), Modifier.width(STATUS_COLUMN))
        }
        ZillitScrollColumn(modifier = Modifier.fillMaxWidth().heightIn(max = TABLE_MAX)) {
            rows.forEach { row ->
                val selected = row.id in import.selected
                val holder = state.people.firstOrNull { it.id == row.holderId }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) colors.accentSoft else colors.surface)
                        .clickable(enabled = row.isNew) { onEvent(InboxEvent.ToggleImportRow(row.id)) }
                        .padding(ROW_PADDING),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.width(SELECT_COLUMN)) {
                        if (row.isNew) {
                            TriStateBox(
                                state = if (selected) ToggleableState.On else ToggleableState.Off,
                                onClick = { onEvent(InboxEvent.ToggleImportRow(row.id)) },
                            )
                        }
                    }
                    Muted(row.rowIndex?.toString().orEmpty(), Modifier.width(INDEX_COLUMN))
                    Muted(row.date?.let(EpochDate::dateTime)?.ifBlank { null } ?: EM_DASH, Modifier.width(DATE_COLUMN))
                    ZillitText(
                        text = row.merchant.ifBlank { EM_DASH },
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    Column(Modifier.width(HOLDER_COLUMN)) {
                        ZillitText(
                            text = holder?.name ?: EM_DASH,
                            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = if (holder == null) colors.textMuted else colors.textPrimary,
                            maxLines = 1,
                        )
                        holder?.designation?.takeIf { it.isNotBlank() }?.let { Muted(it) }
                    }
                    Muted(row.cardLastFour?.let { "•••• $it" } ?: EM_DASH, Modifier.width(CARD_COLUMN))
                    ZillitText(
                        // The row's own currency, else the statement's — never the project's.
                        text = money(row.amount, row.currency ?: currency),
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(AMOUNT_COLUMN),
                    )
                    Spacer(Modifier.width(ZillitTheme.spacing.md))
                    Row(
                        modifier = Modifier.width(STATUS_COLUMN),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        if (currency != null && row.currency != null && row.currency != currency) {
                            ZillitStatusPill(str(S.desktop_fx), tone = StatusTone.Escalated)
                        }
                        ImportStatusPill(row.status)
                    }
                }
                ZillitDivider()
            }
        }
    }
}

/** The web table's status vocabulary, which knows `coded`, `matched` and `declined` besides the workflow. */
@Composable
private fun ImportStatusPill(wire: String) {
    val workflow = CardWorkflowStatus.from(wire)
    val (label, tone) = when {
        wire == CODED -> str(S.desktop_ce_coded) to StatusTone.Progress
        wire == MATCHED -> str(S.desktop_matched) to StatusTone.Ready
        wire == DECLINED -> str(S.txt_badge_declined) to StatusTone.Rejected
        workflow == CardWorkflowStatus.Unknown -> wire to StatusTone.Neutral
        else -> workflow.label to workflow.tone
    }
    ZillitStatusPill(label, tone = tone, dot = true)
}

/**
 * The floating bar (`ImportStatementPage.jsx:469-515`): up to three holders'
 * faces and a count of the rest, how many are ticked, the new count and the
 * ticked total, and Submit — counting only the rows that have a holder.
 */
@Suppress("LongMethod") // Faces, counts and the two buttons of one bar.
@Composable
private fun SubmitBar(
    state: CardUiState,
    import: ImportState,
    result: StatementImportResult,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val ticked = result.rows.filter { it.id in import.selected }
    val holders = ticked.mapNotNull { it.holderId?.takeIf(String::isNotBlank) }.distinct()
        .mapNotNull { id -> state.people.firstOrNull { it.id == id } }
    val shown = holders.take(MAX_FACES)
    val extra = import.selected.size - shown.size
    val withHolder = ticked.count { !it.holderId.isNullOrBlank() }
    val currency = import.statementCurrency(result)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        if (shown.isNotEmpty()) {
            Row {
                shown.forEachIndexed { index, person ->
                    ZillitAvatar(
                        name = person.name,
                        userId = person.id,
                        size = FACE,
                        modifier = Modifier.offset(x = -FACE_OVERLAP * index),
                    )
                }
                if (extra > 0) {
                    Box(
                        modifier = Modifier
                            .offset(x = -FACE_OVERLAP * shown.size)
                            .size(FACE)
                            .clip(CircleShape)
                            .background(colors.surfaceSunken),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitText("+$extra", style = ZillitTheme.typography.labelSmall, color = colors.textSecondary)
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = str(
                    if (import.selected.size == 1) {
                        S.desktop_ce_inbox_selected_one
                    } else {
                        S.desktop_ce_inbox_selected_many
                    },
                    import.selected.size,
                ),
                style = ZillitTheme.typography.titleSmall,
            )
            ZillitText(
                text = str(
                    S.desktop_ce_inbox_new_total,
                    result.rows.count { it.isNew },
                    money(ticked.sumOf { it.amount }, currency),
                ),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        ZillitButton(
            text = if (import.submitting) str(S.ah_submitting) else str(S.desktop_ce_inbox_submit_to_crew, withHolder),
            onClick = { onEvent(InboxEvent.SubmitToCrew) },
            leadingIcon = ZillitIcons.Send,
            enabled = !import.submitting,
        )
        ZillitButton(
            text = str(S.desktop_ce_inbox_clear_selection),
            onClick = { onEvent(InboxEvent.ClearImportSelection) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Close,
        )
    }
}

/** "Submitted to Crew Portals" (`ImportStatementPage.jsx:412-461`). */
@Composable
private fun SubmittedCard(
    state: CardUiState,
    submission: CrewSubmission,
    currency: String?,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(ZillitIcons.Check, size = BANNER_GLYPH, tint = colors.success)
            ZillitText(
                str(S.desktop_ce_inbox_submitted_to_crew),
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
            )
        }
        ZillitDivider()
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Row {
                Figure(str(S.txt_submitted), submission.submitted.toString(), colors.success, Modifier.weight(1f))
                Figure(str(S.ah_status_skipped), submission.skipped.toString(), colors.textPrimary, Modifier.weight(1f))
                Figure(
                    label = str(S.desktop_ce_inbox_to_holding),
                    value = money(submission.totalAmount, currency),
                    color = colors.accent,
                    modifier = Modifier.weight(1f),
                    end = true,
                )
            }
            ZillitDivider()
            if (submission.notified.isNotEmpty()) {
                val names = submission.notified.joinToString(", ") { (userId, count) ->
                    // Never an id where a person belongs.
                    val name = state.personName(userId)
                    val key = if (count == 1) S.desktop_ce_inbox_notified_one else S.desktop_ce_inbox_notified_many
                    str(key, name, count)
                }
                ZillitText("${str(S.desktop_ce_inbox_notified)} $names", style = ZillitTheme.typography.bodyMedium)
            }
            ZillitText(
                text = str(S.desktop_ce_inbox_cost_report_line, money(submission.totalAmount, currency)),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            ZillitText(
                text = str(S.desktop_ce_inbox_view_pending),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = colors.accent,
                modifier = Modifier.clickable { onEvent(CardEvent.Open(CardDestination.PendingCoding)) },
            )
        }
    }
}

@Composable
private fun Figure(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier,
    end: Boolean = false,
) {
    Column(modifier = modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        ZillitText(
            label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = color,
        )
    }
}

/**
 * "Import statement" (`ImportStatementPage.jsx:527-579`): the picked file,
 * and the currency it is in — hidden when there is nothing to choose, one
 * complete option taken for the accountant, Import held while the list is
 * still loading or a choice is still owed. Drawn at the screen root.
 */
@Suppress("LongMethod") // The dialog, its file card and its currency.
@Composable
internal fun ImportStatementDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val import = state.inbox.import
    val file = import.pendingFile ?: return
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.desktop_ce_inbox_import_statement),
        icon = ZillitIcons.Inbox,
        visible = true,
        width = IMPORT_DIALOG_WIDTH,
        onDismiss = { onEvent(InboxEvent.CancelImport) },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InboxEvent.CancelImport) },
                variant = ButtonVariant.Tertiary,
                enabled = !import.importing,
            )
            ZillitButton(
                text = if (import.importing) str(S.desktop_importing) else str(S.desktop_ce_inbox_import_statement),
                onClick = { onEvent(InboxEvent.StartImport) },
                enabled = !import.importBlocked,
                loading = import.importing,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(FILE_WELL).clip(ZillitTheme.shapes.medium).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(ZillitIcons.Inbox, size = FILE_GLYPH, tint = colors.accent)
            }
            Column {
                ZillitText(
                    file.name,
                    style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    maxLines = 1,
                )
                ZillitText(
                    text = str(S.desktop_ce_inbox_statement_file),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        if (import.showCurrency) {
            Spacer(Modifier.height(ZillitTheme.spacing.lg))
            ZillitText(
                str(S.ah_lbl_currency).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.textMuted,
            )
            ZillitSelect(
                value = import.currency,
                options = import.currencies.codes,
                onSelect = { onEvent(InboxEvent.ChooseCurrency(it)) },
                label = { code ->
                    when {
                        import.currenciesLoading -> str(S.desktop_loading_currencies)
                        code.isBlank() -> str(S.desktop_ce_cards_select_currency)
                        else -> code
                    }
                },
                enabled = !import.currenciesLoading && !import.importing,
                modifier = Modifier.width(CURRENCY_WIDTH),
            )
        }
    }
}

/**
 * What this statement's totals render in: what the import answered, else what
 * was sent, else the project default — never silently the default when the
 * accountant chose otherwise (`ImportStatementPage.jsx:140-148`).
 */
private fun ImportState.statementCurrency(result: StatementImportResult): String? =
    result.info.currency ?: importedCurrency.takeIf { it.isNotBlank() } ?: projectCurrencies.defaultCode

private fun period(result: StatementImportResult): String {
    val start = result.summary.periodStart
    val end = result.summary.periodEnd
    return if (start != null && end != null) "${date(start)} – ${date(end)}" else EM_DASH
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 1,
        modifier = modifier,
    )
}

private val FORMATS = listOf("CSV", "OFX", "QIF")
private const val CODED = "coded"
private const val MATCHED = "matched"
private const val DECLINED = "declined"
private const val DETAIL_COLUMNS = 3
private const val MAX_FACES = 3
private const val ZONE_WASH = 0.55f
private const val ZONE_RIM = 0.45f
private val ZONE_PADDING = 60.dp
private val ZONE_ICON_WELL = 56.dp
private val ZONE_GLYPH = 26.dp
private val BANNER_GLYPH = 20.dp
private val ROW_PADDING = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val TABLE_MAX = 640.dp
private val SELECT_COLUMN = 36.dp
private val INDEX_COLUMN = 36.dp
private val DATE_COLUMN = 130.dp
private val HOLDER_COLUMN = 170.dp
private val CARD_COLUMN = 84.dp
private val AMOUNT_COLUMN = 110.dp
private val STATUS_COLUMN = 170.dp
private val FACE = 32.dp
private val FACE_OVERLAP = 10.dp
private val IMPORT_DIALOG_WIDTH = 520.dp
private val FILE_WELL = 40.dp
private val FILE_GLYPH = 20.dp
private val CURRENCY_WIDTH = 300.dp
