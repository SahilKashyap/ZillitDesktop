package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoSortColumn
import com.zillit.desktop.feature.purchaseorder.domain.PoSortDirection
import com.zillit.desktop.feature.purchaseorder.domain.PoSortKey
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.defaultCurrency

/**
 * Every order list but the Queue and the Posted tab: All POs, My POs, My
 * Department POs and the Approval Queue.
 *
 * One composable for four tabs because the web's four are the same table with
 * different endpoints behind them — and because the stat cards above it have to
 * count the rows *under* it, which is easy to get wrong twice.
 */
@Composable
internal fun PoListPage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val rows = state.rows
    Box(modifier = Modifier.fillMaxSize()) {
        // One scroll for the whole page, as the web has: the cards and the
        // filters travel with the list instead of pinning a short window over
        // it. A table given the leftover height showed four rows and scrolled
        // inside itself, which reads as a broken panel.
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            PoStatRow(state, rows)
            if (state.showsFilters) PoFilterRow(state, onEvent)
            ZillitSectionCard(
                title = state.destination.label,
                icon = ZillitIcons.File,
                meta = str(
                    if (rows.size == 1) S.desktop_po_order_count_one else S.desktop_po_order_count_other,
                    rows.size,
                ),
                padded = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PoOrderTable(state, rows, onEvent)
            }
        }
        PoBulkBar(state, onEvent, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * The five cards above All POs — the web's `StatCard` row.
 *
 * Counted off the *filtered* rows, not the raw list: a card that says 40 over a
 * table showing 3 is a card nobody trusts again.
 */
@Composable
internal fun PoStatRow(state: PoUiState, rows: List<PurchaseOrder>) {
    val pending = rows.count { it.status == PoStatus.AwaitingApproval }
    val approved = rows.count {
        it.status == PoStatus.Approved || it.status == PoStatus.Queued || it.status == PoStatus.AccountsEntered
    }
    val posted = rows.count { it.status == PoStatus.Posted }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitStatTile(
            label = str(S.ah_tab_all),
            value = rows.size.toString(),
            icon = ZillitIcons.File,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.pending),
            value = pending.toString(),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Clock,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.approved),
            value = approved.toString(),
            tone = StatusTone.Progress,
            icon = ZillitIcons.Shield,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.ah_posted_label),
            value = posted.toString(),
            tone = StatusTone.Done,
            icon = ZillitIcons.Ledger,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.ah_total_value),
            value = rows.totalValue(state),
            sub = rows.currencyNote(),
            tone = StatusTone.Progress,
            icon = ZillitIcons.Bank,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A set of orders' total, as every card and period header shows it — the web's
 * `describeConvertedTotal`.
 *
 * One currency sums in that currency. Several are each converted into the
 * project's default through the project currencies' rates (`exr`, foreign per
 * default) and summed there; a currency with no rate is added at face value,
 * which is also what the web does. It used to read "Mixed", which answered
 * nothing an accountant asks a total for.
 */
internal fun List<PurchaseOrder>.totalValue(state: PoUiState): String {
    val default = (state.rates.defaultCode ?: state.defaultCurrency()).uppercase()
    val codes = map { it.currency?.uppercase()?.takeIf { code -> code.isNotBlank() } ?: default }.distinct()
    if (codes.size <= 1) return Money.format(sumOf { it.gross }, codes.firstOrNull() ?: default)
    val total = sumOf { order -> state.rates.toDefault(order.gross, order.currency, default) ?: order.gross }
    return Money.format(total, default)
}

internal fun List<PurchaseOrder>.currencyNote(): String? {
    val codes = map { it.currency.orEmpty().uppercase() }.filter { it.isNotBlank() }.distinct()
    return if (codes.size > 1) str(S.desktop_po_currencies_count, codes.size) else null
}

/** Quick filters, the department picker and the sort — the web's `QuickFilters`, in its order. */
@Composable
internal fun PoFilterRow(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.ah_quick_filters),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        state.quickFilters.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.quickFilter == filter,
                onClick = { onEvent(PoEvent.Filter(filter)) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        ZillitText(
            text = str(S.ah_dept_label_upper),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitSelect(
            value = state.departmentFilter,
            options = listOf(null) + state.departments.map { it.id },
            onSelect = { onEvent(PoEvent.FilterDepartment(it)) },
            label = { id -> id?.let { state.departmentName(it) } ?: str(S.all) },
            modifier = Modifier.width(DEPT_WIDTH),
        )
        ZillitText(
            text = str(S.drive_sort),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitSelect(
            value = state.sortKey,
            options = PoSortKey.entries,
            onSelect = { onEvent(PoEvent.Sort(it)) },
            label = { it.label },
            modifier = Modifier.width(SORT_WIDTH),
        )
    }
}

/**
 * The order table.
 *
 * Columns, headers and order are the web's: PO Number, Vendor, Dept, Amount,
 * Eff. Date, Status, Assigned — with the accountant's selection checkbox in
 * front. Clicking a header sorts by it; clicking a row opens the order.
 */
@Composable
internal fun PoOrderTable(state: PoUiState, rows: List<PurchaseOrder>, onEvent: (PoEvent) -> Unit) {
    ZillitDataTable(
        rows = rows,
        columns = poColumns(state, rows, onEvent),
        key = { it.id },
        loading = state.loading,
        onRowClick = { onEvent(PoEvent.OpenOrder(it.id)) },
        // The page scrolls, so the table lays out every row rather than
        // virtualising inside a box of its own.
        virtualised = false,
        emptyTitle = if (state.search.isBlank()) str(S.desktop_nothing_here) else str(S.dm_nda_empty_search),
        emptyMessage = when (state.destination) {
            PoDestination.ApprovalQueue -> str(S.desktop_po_empty_approval_queue)
            PoDestination.MyPos -> str(S.desktop_po_empty_my_pos)
            PoDestination.DepartmentPos -> str(S.desktop_po_empty_department_pos)
            PoDestination.Drafts -> str(S.desktop_po_empty_drafts)
            else -> str(S.desktop_po_empty_all_pos)
        },
    )
}

/**
 * A clickable column heading — the web's sortable `<th>`. The same column again
 * flips the direction; the arrow says which way the rows run now.
 */
private fun sortHeading(
    state: PoUiState,
    column: PoSortColumn,
    label: String,
    onEvent: (PoEvent) -> Unit,
): @Composable () -> Unit = {
    val arrow = when {
        state.sortColumn != column -> ""
        state.sortDirection == PoSortDirection.Ascending -> " ↑"
        else -> " ↓"
    }
    ZillitText(
        text = label.uppercase() + arrow,
        style = ZillitTheme.typography.columnHeader,
        color = if (state.sortColumn == column) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textMuted,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().clickable { onEvent(PoEvent.SortColumn(column)) },
    )
}

/**
 * The rows a bulk action may take: everything on screen except an order in the
 * locked cost-report period, which the web never lets into a selection.
 */
internal fun PoUiState.selectable(rows: List<PurchaseOrder>): List<PurchaseOrder> =
    rows.filterNot { it.isLocalOnly || isLocked(it) }

@Suppress("LongMethod") // A column table; splitting it hides the shape.
internal fun poColumns(
    state: PoUiState,
    rows: List<PurchaseOrder>,
    onEvent: (PoEvent) -> Unit,
): List<TableColumn<PurchaseOrder>> = buildList {
    if (state.viewer.isAccountant) {
        val selectable = state.selectable(rows).map { it.id }
        add(
            TableColumn(
                header = "",
                width = ColumnWidth.Fixed(TICK_WIDTH),
                // All on, or all off — the view model's SelectAll toggles.
                headerContent = {
                    ZillitCheckbox(
                        checked = selectable.isNotEmpty() && state.selection.containsAll(selectable),
                        onCheckedChange = { onEvent(PoEvent.SelectAll(selectable)) },
                        enabled = selectable.isNotEmpty(),
                    )
                },
                cell = { order ->
                    ZillitCheckbox(
                        checked = order.id in state.selection,
                        onCheckedChange = { onEvent(PoEvent.ToggleSelection(order.id)) },
                        enabled = !state.isLocked(order),
                    )
                },
            ),
        )
    }
    add(
        TableColumn(
            header = str(S.ah_lbl_po_number),
            headerContent = sortHeading(state, PoSortColumn.Number, str(S.ah_lbl_po_number), onEvent),
            width = ColumnWidth.Fixed(NUMBER_WIDTH),
            cell = { order ->
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        ZillitText(
                            text = order.number.ifBlank {
                                if (order.isLocalOnly) str(S.desktop_po_not_yet_numbered) else "—"
                            },
                            style = ZillitTheme.typography.numeric,
                            maxLines = 1,
                        )
                        // The row's unread on this tab — the web's per-PO badge.
                        ZillitBadge(count = state.unread.order(state.destination.badgeScope, order.id))
                    }
                    if (order.isLocalOnly) {
                        ZillitText(
                            text = if (order.local?.failed == true) {
                                str(S.desktop_po_needs_attention)
                            } else {
                                str(S.desktop_waiting_to_send)
                            },
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            },
        ),
    )
    add(
        TableColumn(
            header = str(S.ah_lbl_vendor),
            headerContent = sortHeading(state, PoSortColumn.Vendor, str(S.ah_lbl_vendor), onEvent),
            cell = { order ->
                Column {
                    ZillitText(
                        text = state.vendorName(order).ifBlank { str(S.desktop_po_no_vendor) },
                        style = ZillitTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    if (order.description.isNotBlank()) {
                        ZillitText(
                            text = order.description,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            },
        ),
    )
    add(
        textColumn<PurchaseOrder>(
            header = str(S.desktop_po_dept_column),
            width = ColumnWidth.Fixed(DEPT_COLUMN),
            muted = true,
        ) { order -> state.departmentName(order.departmentId).ifBlank { "—" } }
            .copy(headerContent = sortHeading(state, PoSortColumn.Department, str(S.desktop_po_dept_column), onEvent)),
    )
    add(
        textColumn<PurchaseOrder>(
            header = str(S.amount),
            width = ColumnWidth.Fixed(AMOUNT_WIDTH),
            numeric = true,
        ) { order -> Money.format(order.gross, order.currency) }
            .copy(headerContent = sortHeading(state, PoSortColumn.Amount, str(S.amount), onEvent)),
    )
    add(
        textColumn<PurchaseOrder>(
            header = str(S.ah_row_eff_date_upper),
            width = ColumnWidth.Fixed(DATE_WIDTH),
            muted = true,
        ) { order -> EpochDate.date(order.effectiveDate).ifBlank { "—" } }
            .copy(
                headerContent = sortHeading(state, PoSortColumn.EffectiveDate, str(S.ah_row_eff_date_upper), onEvent),
            ),
    )
    add(
        TableColumn(
            header = str(S.status),
            headerContent = sortHeading(state, PoSortColumn.Status, str(S.status), onEvent),
            width = ColumnWidth.Fixed(STATUS_WIDTH),
            cell = { order -> ZillitStatusPill(label = state.statusLabel(order), tone = order.status.tone()) },
        ),
    )
    add(
        TableColumn(
            header = str(S.assigned),
            headerContent = sortHeading(state, PoSortColumn.Assigned, str(S.assigned), onEvent),
            width = ColumnWidth.Fixed(ASSIGNED_WIDTH),
            cell = { order ->
                val name = state.assigneeName(order)
                if (PoAccess.canReassign(order, state.viewer)) {
                    ZillitButton(
                        text = name,
                        onClick = { onEvent(PoEvent.AskReassign(order.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                } else {
                    ZillitText(
                        text = name,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                }
            },
        ),
    )
}

/**
 * A status's colour — the web's `PO_STATUS_TONE` (`lib/poStatus.js`): posted
 * and approved green, rejected red, closed grey, and everything still moving
 * amber (pending, Acct Entered in both spellings, draft). An unknown status is
 * amber too, the web's fallback.
 */
internal fun PoStatus.tone(): StatusTone = when (this) {
    PoStatus.Posted, PoStatus.Approved -> StatusTone.Done
    PoStatus.Rejected, PoStatus.Cancelled -> StatusTone.Rejected
    PoStatus.Closed -> StatusTone.Neutral
    PoStatus.AwaitingApproval, PoStatus.AccountsEntered, PoStatus.Queued, PoStatus.Draft, PoStatus.Unknown ->
        StatusTone.Pending
}

/**
 * The bar that appears over a selection — the web's floating bulk bar.
 *
 * Three actions, and only the two that have a JSON route: Reassign, and Set
 * Effective Date. The web's third, Export, downloads a rendered file; the
 * desktop's API client has no binary-download path yet, and a button that
 * cannot deliver a file is worse than one that is not there.
 */
@Composable
internal fun PoBulkBar(state: PoUiState, onEvent: (PoEvent) -> Unit, modifier: Modifier = Modifier) {
    val selection = state.selection
    if (selection.isEmpty() || !state.viewer.isAccountant) return
    val selected = state.rows.filter { it.id in selection }
    ZillitSectionCard(
        modifier = modifier.padding(ZillitTheme.spacing.xl),
        padded = true,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Column {
                ZillitText(
                    text = str(
                        if (selection.size == 1) {
                            S.desktop_po_purchase_order_count_one
                        } else {
                            S.desktop_po_purchase_order_count_other
                        },
                        selection.size,
                    ),
                    style = ZillitTheme.typography.titleSmall,
                )
                ZillitText(
                    text = str(S.desktop_po_total_value, selected.totalValue(state)),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitButton(
                text = str(S.desktop_po_reassign),
                onClick = { onEvent(PoEvent.AskBulkReassign(selection.toList())) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.User,
                enabled = !state.busy,
            )
            ZillitButton(
                text = str(S.desktop_po_set_effective_date),
                onClick = { onEvent(PoEvent.AskBulkDate(selection.toList())) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Calendar,
                enabled = !state.busy,
            )
            ZillitButton(
                text = str(S.ah_clear_selection),
                onClick = { onEvent(PoEvent.ClearSelection) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Close,
            )
        }
    }
}

/** Shared paddings for every order table on this tool. */
internal val TICK_WIDTH = 40.dp
internal val NUMBER_WIDTH = 150.dp
internal val DEPT_COLUMN = 150.dp
internal val AMOUNT_WIDTH = 130.dp
internal val DATE_WIDTH = 120.dp
internal val STATUS_WIDTH = 150.dp
internal val ASSIGNED_WIDTH = 130.dp
private val DEPT_WIDTH = 180.dp
private val SORT_WIDTH = 140.dp
