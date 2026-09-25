package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cardexpenses.domain.TRANSACTION_STATUS_FILTERS
import com.zillit.desktop.feature.cardexpenses.domain.TransactionFilters
import com.zillit.desktop.feature.cardexpenses.domain.TransactionSelection
import com.zillit.desktop.feature.cardexpenses.domain.canDelete
import com.zillit.desktop.feature.cardexpenses.domain.matchesLedgerSearch
import com.zillit.desktop.feature.cardexpenses.ui.ALL_STATUSES
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.InboxEvent
import com.zillit.desktop.feature.cardexpenses.ui.WorkflowStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * All Transactions (`pages/AllTransactionsPage.jsx` + `ui/TransactionTable.jsx`):
 * the statement lines, narrowed by the server's filters, with a bulk delete.
 *
 * No detail pane and no row click — the web's register is a table, and a
 * click that armed a destructive selection would fire on a drag to copy a
 * merchant. The checkbox is the only way in; Delete is the one row action,
 * offered only short of approval.
 */
@Composable
fun AllTransactionsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.visibleLedgerRows()
    val visibleIds = TransactionSelection.selectableIds(rows)
    val selected = TransactionSelection.visibleSelection(state.selection, visibleIds)

    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        LedgerTiles(state)
        LedgerToolbar(state, onEvent)
        StatusChips(TRANSACTION_STATUS_FILTERS, state.statusFilter, onEvent)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                // Skeleton while there is nothing to show; a refetch after a
                // delete or a socket event swaps the rows in place rather than
                // blanking the table (`AllTransactionsPage.jsx:107-114`).
                state.loading && state.transactions.isEmpty() -> LedgerSkeleton()
                rows.isEmpty() -> ZillitText(
                    text = str(S.desktop_ce_inbox_no_transactions),
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxl),
                )

                else -> LedgerTable(state, rows, visibleIds, selected.toSet(), onEvent)
            }
        }
        // Below the table rather than above it: above, it pushed every row
        // down on the click that created it (`AllTransactionsPage.jsx:509-515`).
        if (!state.loading && selected.isNotEmpty()) SelectionBar(selected, onEvent)
    }
}

/** The rows on screen: the server's answer through the status chip and the search. */
internal fun CardUiState.visibleLedgerRows(): List<CardTransaction> = transactions
    .filter { statusFilter == ALL_STATUSES || it.status.wire == statusFilter }
    .filter { it.matchesLedgerSearch(search, personName(it.holderId, it.holderName)) }

/** What Delete selected would send: the visible, deletable selection, in table order. */
internal fun CardUiState.visibleLedgerSelection(): List<String> =
    TransactionSelection.visibleSelection(selection, TransactionSelection.selectableIds(visibleLedgerRows()))

/**
 * Total, New, In Approval, Posted and Value (`AllTransactionsPage.jsx:320-326`)
 * — Value in the production's default currency, as the web formats it.
 */
@Composable
private fun LedgerTiles(state: CardUiState) {
    val rows = state.transactions
    val currency = state.inbox.ledger.defaultCurrency ?: state.currency
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitStatTile(str(S.asset_total), rows.size.toString(), Modifier.weight(1f))
        ZillitStatTile(
            label = str(S.ah_txn_filter_new),
            value = rows.count { it.status == CardWorkflowStatus.New }.toString(),
            tone = StatusTone.Done,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_in_approval),
            value = rows.count { it.status == CardWorkflowStatus.InApproval }.toString(),
            tone = StatusTone.Progress,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.ah_status_posted),
            value = rows.count { it.status == CardWorkflowStatus.Posted }.toString(),
            tone = StatusTone.Ready,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.ah_addl_value_hint),
            value = money(rows.sumOf { it.amount }, currency),
            tone = StatusTone.Pending,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Search, the Filters button and its panel, and the one Export menu. */
@Composable
private fun LedgerToolbar(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    var filtersOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    val applied = state.transactionFilters.count
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CardEvent.Search(it)) },
                placeholder = str(S.desktop_ce_inbox_search_transactions),
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitButton(
                    text = str(S.asset_filters),
                    onClick = { filtersOpen = !filtersOpen },
                    variant = if (applied > 0 || filtersOpen) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Filter,
                )
                // Counted off the APPLIED filters: the badge describes the rows below it.
                ZillitBadge(count = applied, background = ZillitTheme.colors.accent)
            }
            Box {
                ZillitButton(
                    text = if (state.exporting) str(S.desktop_exporting) else str(S.asset_export),
                    onClick = { exportOpen = true },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Download,
                    enabled = !state.exporting,
                )
                ZillitActionMenu(
                    expanded = exportOpen,
                    onDismissRequest = { exportOpen = false },
                    entries = listOf(
                        ZillitMenuEntry.Action(str(S.recce_export_pdf), ZillitIcons.File) {
                            onEvent(CardEvent.ExportTransactions(ExportFormat.Pdf))
                        },
                        ZillitMenuEntry.Action(str(S.desktop_dm_export_excel), ZillitIcons.Grid) {
                            onEvent(CardEvent.ExportTransactions(ExportFormat.Excel))
                        },
                    ),
                )
            }
        }
        if (filtersOpen) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                FilterPanel(state, onDone = { filtersOpen = false }, onEvent = onEvent)
            }
        }
    }
}

/**
 * "Filter transactions" (`AllTransactionsPage.jsx:363-446`): statement, card,
 * department and a date window, edited as a DRAFT seeded from the applied set
 * when the panel opens. Reset clears the draft only; Done applies it — and is
 * the only thing that reaches the server.
 */
@Suppress("LongMethod") // One panel: four fields, its header and its footer.
@Composable
private fun FilterPanel(state: CardUiState, onDone: () -> Unit, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var draft by remember { mutableStateOf(state.transactionFilters) }
    var cardQuery by remember { mutableStateOf("") }
    val cardOptions = state.cards.filter { it.id.isNotBlank() }
    fun cardLabel(id: String): String {
        val card = cardOptions.firstOrNull { it.id == id } ?: return str(S.desktop_card_all_cards)
        val last = card.lastFour?.takeIf { it.isNotBlank() } ?: "????"
        val holder = state.personName(card.holderId, card.holderName).takeIf { it != EM_DASH }
        return if (holder != null) "•••• $last · $holder" else "•••• $last"
    }
    val matchingCards = cardOptions.filter { cardQuery.isBlank() || cardLabel(it.id).contains(cardQuery, true) }
    val departments = state.inbox.ledger.departments

    Column(
        modifier = Modifier
            .width(PANEL_WIDTH)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = str(S.desktop_ce_inbox_filter_transactions),
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = str(S.desktop_ce_inbox_reset_all),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = if (draft.count > 0) colors.accent else colors.textDisabled,
                modifier = Modifier.clickable(enabled = draft.count > 0) {
                    draft = TransactionFilters()
                },
            )
        }
        ZillitDivider()
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            FilterLabel(str(S.desktop_ce_inbox_statement))
            ZillitSelect(
                value = draft.statementId,
                options = listOf("") + state.imports.map { it.id },
                onSelect = { draft = draft.copy(statementId = it) },
                label = { id ->
                    if (id.isBlank()) {
                        str(S.desktop_card_all_statements)
                    } else {
                        state.imports.firstOrNull { it.id == id }?.filename ?: id
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FilterLabel(str(S.ah_my_cards))
            // Searchable by the holder as well as the digits: a card is
            // remembered by whose it is.
            ZillitSearchField(value = cardQuery, onValueChange = { cardQuery = it }, modifier = Modifier.fillMaxWidth())
            ZillitSelect(
                value = draft.cardId,
                options = listOf("") + matchingCards.map { it.id },
                onSelect = { draft = draft.copy(cardId = it) },
                label = { id -> if (id.isBlank()) str(S.desktop_card_all_cards) else cardLabel(id) },
                modifier = Modifier.fillMaxWidth(),
            )
            FilterLabel(str(S.department))
            ZillitSelect(
                value = draft.departmentId,
                options = listOf("") + departments.map { it.id },
                onSelect = { draft = draft.copy(departmentId = it) },
                label = { id ->
                    if (id.isBlank()) {
                        str(S.invitees_tab_all_depts)
                    } else {
                        departments.firstOrNull { it.id == id }?.name?.localised() ?: id
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FilterLabel(str(S.cs_date_range))
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitDateField(
                    value = draft.from,
                    onValueChange = { draft = draft.copy(from = it) },
                    modifier = Modifier.weight(1f),
                )
                ZillitText("–", color = colors.textMuted)
                ZillitDateField(
                    value = draft.to,
                    onValueChange = { draft = draft.copy(to = it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ZillitDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = str(
                    if (draft.count == 1) {
                        S.desktop_ce_inbox_filters_selected_one
                    } else {
                        S.desktop_ce_process_filters_selected
                    },
                    draft.count,
                ),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.ah_done),
                onClick = {
                    onEvent(CardEvent.SetTransactionFilters(draft))
                    onDone()
                },
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textMuted,
    )
}

/**
 * The register (`TransactionTable.jsx` with the All Transactions columns):
 * select, date and time, merchant, holder, the holder's department, card,
 * amount, receipt tick, status, code, and Delete.
 */
@Composable
private fun LedgerTable(
    state: CardUiState,
    rows: List<CardTransaction>,
    visibleIds: List<String>,
    selected: Set<String>,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val allSelected = visibleIds.isNotEmpty() && selected.size == visibleIds.size
    Column(
        modifier = Modifier
            .fillMaxSize()
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
                        selected.isNotEmpty() -> ToggleableState.Indeterminate
                        else -> ToggleableState.Off
                    },
                    enabled = visibleIds.isNotEmpty(),
                    onClick = { onEvent(InboxEvent.ToggleAllTransactions(visibleIds)) },
                )
            }
            HeaderText(str(S.date), Modifier.width(DATE_COLUMN))
            HeaderText(str(S.ah_merchant), Modifier.weight(MERCHANT_WEIGHT))
            HeaderText(str(S.desktop_card_card_holder), Modifier.weight(HOLDER_WEIGHT))
            HeaderText(str(S.department), Modifier.weight(DEPARTMENT_WEIGHT))
            HeaderText(str(S.ah_my_cards), Modifier.width(CARD_COLUMN))
            HeaderText(str(S.amount), Modifier.width(AMOUNT_COLUMN), TextAlign.End)
            HeaderText(str(S.desktop_receipt), Modifier.width(RECEIPT_COLUMN), TextAlign.Center)
            HeaderText(str(S.status), Modifier.width(STATUS_COLUMN))
            HeaderText(str(S.code), Modifier.width(CODE_COLUMN))
            Spacer(Modifier.width(ACTION_COLUMN))
        }
        ZillitScrollColumn(modifier = Modifier.fillMaxSize()) {
            rows.forEach { row ->
                LedgerRow(state, row, row.id in selected, onEvent)
                ZillitDivider()
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // One row's eleven cells.
@Composable
private fun LedgerRow(state: CardUiState, row: CardTransaction, selected: Boolean, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val holder = state.people.firstOrNull { it.id == row.holderId }
    val selectable = row.id.isNotBlank() && row.status.canDelete
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else colors.surface)
            .padding(ROW_PADDING),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(SELECT_COLUMN)) {
            if (selectable) {
                TriStateBox(
                    state = if (selected) ToggleableState.On else ToggleableState.Off,
                    onClick = { onEvent(InboxEvent.ToggleTransaction(row.id)) },
                )
            }
        }
        ZillitText(
            text = row.date?.let(EpochDate::dateTime)?.ifBlank { null } ?: EM_DASH,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.width(DATE_COLUMN),
        )
        ZillitText(
            text = row.merchant.ifBlank { EM_DASH },
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(MERCHANT_WEIGHT),
        )
        Column(Modifier.weight(HOLDER_WEIGHT)) {
            if (holder == null) {
                ZillitText(EM_DASH, color = colors.textMuted)
            } else {
                ZillitText(
                    text = holder.name,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                holder.designation.takeIf { it.isNotBlank() }?.let {
                    ZillitText(it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
                }
            }
        }
        ZillitText(
            text = holder?.department?.takeIf { it.isNotBlank() } ?: EM_DASH,
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(DEPARTMENT_WEIGHT),
        )
        ZillitText(
            text = row.cardLastFour?.let { "•••• $it" } ?: EM_DASH,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.width(CARD_COLUMN),
        )
        ZillitText(
            text = money(row.amount, row.currency),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_COLUMN),
        )
        Box(Modifier.width(RECEIPT_COLUMN), contentAlignment = Alignment.Center) {
            if (row.receiptId != null) {
                ZillitIcon(ZillitIcons.Check, size = TICK, tint = colors.success)
            } else {
                ZillitText(EM_DASH, color = colors.textMuted)
            }
        }
        Box(Modifier.width(STATUS_COLUMN)) { WorkflowStatusPill(row.status) }
        ZillitText(
            text = row.nominalCode ?: EM_DASH,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (row.nominalCode == null) colors.textMuted else colors.accent,
            modifier = Modifier.width(CODE_COLUMN),
        )
        Box(Modifier.width(ACTION_COLUMN), contentAlignment = Alignment.CenterEnd) {
            // Approved and posted are bookkeeping, not clutter (`transactionQuery.js:55-59`).
            if (row.status.canDelete) {
                DeleteChip { onEvent(InboxEvent.AskDelete(row)) }
            }
        }
    }
}

/** "N transactions selected" · Clear · Delete selected (`AllTransactionsPage.jsx:516-538`). */
@Composable
private fun SelectionBar(selected: List<String>, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = str(
                if (selected.size == 1) S.desktop_ce_inbox_selected_one else S.desktop_ce_inbox_selected_many,
                selected.size,
            ),
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(S.ah_clear),
            onClick = { onEvent(CardEvent.ClearSelection) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = str(S.desktop_ce_inbox_delete_selected),
            onClick = { onEvent(InboxEvent.AskBulkDelete(open = true)) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Trash,
        )
    }
}

/** A checkbox with a partial state — the header box's "some ticked" (`TransactionTable.jsx:63-69`). */
@Composable
internal fun TriStateBox(state: ToggleableState, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = ZillitTheme.colors
    val on = state != ToggleableState.Off
    Box(
        modifier = Modifier
            .size(BOX)
            .clip(ZillitTheme.shapes.small)
            .background(if (on) colors.accent else colors.surface)
            .border(1.dp, if (on) colors.accent else colors.borderStrong, ZillitTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            ToggleableState.On -> ZillitIcon(ZillitIcons.Check, size = BOX_GLYPH, tint = colors.textOnAccent)
            ToggleableState.Indeterminate -> ZillitIcon(ZillitIcons.Minus, size = BOX_GLYPH, tint = colors.textOnAccent)
            ToggleableState.Off -> Unit
        }
    }
}

/** The row's Delete: an icon chip that goes red under the pointer. */
@Composable
private fun DeleteChip(onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(CHIP)
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered) colors.danger else colors.surface)
            .border(1.dp, if (hovered) colors.danger else colors.borderStrong, ZillitTheme.shapes.medium)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            ZillitIcons.Trash,
            contentDescription = str(S.desktop_ce_inbox_delete_transaction),
            size = CHIP_GLYPH,
            tint = if (hovered) colors.textOnAccent else colors.textSecondary,
        )
    }
}

/** The table's silhouette, not "Loading…" text. */
@Composable
private fun LedgerSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        repeat(SKELETON_ROWS) { ZillitSkeletonBar() }
    }
}

private const val SKELETON_ROWS = 8
private const val MERCHANT_WEIGHT = 1.4f
private const val HOLDER_WEIGHT = 1.1f
private const val DEPARTMENT_WEIGHT = 0.9f
private val ROW_PADDING = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val PANEL_WIDTH = 340.dp
private val SELECT_COLUMN = 36.dp
private val DATE_COLUMN = 130.dp
private val CARD_COLUMN = 84.dp
private val AMOUNT_COLUMN = 110.dp
private val RECEIPT_COLUMN = 64.dp
private val STATUS_COLUMN = 140.dp
private val CODE_COLUMN = 80.dp
private val ACTION_COLUMN = 44.dp
private val TICK = 14.dp
private val BOX = 16.dp
private val BOX_GLYPH = 12.dp
private val CHIP = 32.dp
private val CHIP_GLYPH = 15.dp
