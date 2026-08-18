// One composable per board piece.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.ui.DepartmentTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.QuickFilter

/** The non-accountant view: three tabs, a quick filter, one table. */
@Composable
internal fun ColumnScope.DepartmentPage(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    ZillitTabStrip(
        tabs = DepartmentTab.entries.map { ZillitTab(it.id, it.label) },
        activeId = state.departmentTab.id,
        onSelect = { id -> onEvent(InvoicesEvent.SelectDepartmentTab(DepartmentTab.entries.first { it.id == id })) },
    )
    if (state.departmentTab == DepartmentTab.MyDepartment && state.viewer.departmentId.isBlank()) {
        ZillitNotice(text = "Your crew record has no department, so there is no department board to show.")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.quickFilter == filter,
                onClick = { onEvent(InvoicesEvent.SelectQuickFilter(filter)) },
            )
        }
    }
    val rows = state.shownInvoices
    CountLine(rows.size)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            columns = departmentColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = if (state.quickFilter == QuickFilter.All) {
                state.departmentTab.emptyText
            } else {
                "No ${state.quickFilter.label.lowercase()} invoices"
            },
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun departmentColumns(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit): List<TableColumn<Invoice>> =
    leadingColumns(state) + poColumn() + approvalColumn(state, queue = false) + slaColumn(state) +
        TableColumn("", ColumnWidth.Fixed(ACTIONS_WIDTH)) { invoice ->
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (InvoiceRules.canDeleteOwn(invoice, state.viewer)) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Delete invoice",
                        tint = ZillitTheme.colors.danger,
                        onClick = { onEvent(InvoicesEvent.RequestDelete(invoice)) },
                    )
                }
            }
        }
