// The three accountant pages; one composable per page piece.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.RegisterChip

@Composable
internal fun ColumnScope.AccountantPageContent(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit, nowMs: Long) {
    when (state.page) {
        AccountantPage.Register -> RegisterPage(state, onEvent, nowMs)
        AccountantPage.Inbox -> InboxPage(state, onEvent)
        AccountantPage.ApprovalQueue -> ApprovalPage(state, onEvent)
    }
}

// Register -----------------------------------------------------------------

@Composable
private fun ColumnScope.RegisterPage(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit, nowMs: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = "Search ref, supplier, description, PO",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        val options = listOf<String?>(null) + state.departmentOptions
        ZillitSelect(
            value = state.registerDepartment,
            options = options,
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDepartment(it)) },
            label = { id -> if (id == null) "All departments" else state.departmentName(id) },
            modifier = Modifier.width(DEPARTMENT_SELECT_WIDTH),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RegisterChip.entries.forEach { chip ->
            ZillitChoiceChip(
                label = chip.label,
                selected = state.registerChip == chip,
                onClick = { onEvent(InvoicesEvent.SelectRegisterChip(chip)) },
            )
        }
    }
    val rows = state.shownInvoices
    CountLine(rows.size)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            columns = registerColumns(state, nowMs),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = "No invoices match",
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun registerColumns(state: InvoicesUiState, nowMs: Long): List<TableColumn<Invoice>> = listOf(
    TableColumn("Invoice", ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    poColumn(),
    TableColumn("Department", ColumnWidth.Weight(1f)) { CellText(state.departmentName(it.departmentId), muted = true) },
    TableColumn("Status", ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
        if (invoice.payMethod.isUrgent && invoice.status != InvoiceStatus.Override) {
            BadgePill(InvoiceRules.urgentBadge(invoice))
        } else {
            ZillitStatusPill(label = invoice.statusLabel, tone = invoice.status.statusTone())
        }
    },
    TableColumn("Gross", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn("Due", ColumnWidth.Fixed(DUE_WIDTH)) { invoice ->
        val overdue = InvoiceRules.daysOverdue(invoice, nowMs)
        if (overdue != null) {
            ZillitText(
                text = "${overdue}d overdue",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
                maxLines = 1,
            )
        } else {
            CellText(InvoiceFormat.date(invoice.dueDateMs), muted = true)
        }
    },
)

// Inbox --------------------------------------------------------------------

@Composable
private fun ColumnScope.InboxPage(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = "Search inbox",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
    }
    val rows = state.shownInvoices
    CountLine(rows.size)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            columns = inboxColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = "The inbox is empty",
            emptyMessage = "Uploaded and entered invoices land here until they are processed.",
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun inboxColumns(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit): List<TableColumn<Invoice>> = listOf(
    TableColumn("Invoice", ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn("Gross", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn("Pay Method", ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = StatusTone.InTransit)
    },
    poColumn(),
    TableColumn("Status", ColumnWidth.Fixed(PO_WIDTH)) { invoice ->
        if (invoice.ocrConfidence != null) {
            ZillitStatusPill(label = "OCR", tone = StatusTone.Escalated)
        } else {
            ZillitStatusPill(label = "New", tone = StatusTone.Pending)
        }
    },
    TableColumn("", ColumnWidth.Fixed(ACTIONS_WIDTH)) { invoice ->
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (InvoiceRules.canDeleteInbox(invoice, state.viewer)) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete invoice",
                    tint = ZillitTheme.colors.danger,
                    onClick = { onEvent(InvoicesEvent.RequestDelete(invoice)) },
                )
            }
        }
    },
)

// Approval queue -----------------------------------------------------------

@Composable
private fun ColumnScope.ApprovalPage(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = "Search queue",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitStatusPill(label = "${state.awaitingCount} awaiting", tone = StatusTone.Pending)
        ZillitStatusPill(label = "${state.approvedCount} approved", tone = StatusTone.Done)
    }
    val rows = state.shownInvoices
    val canBatch = rows.any {
        it.id in state.selected && ApprovalChain.canApprove(it, state.tiersOf(it), state.viewer.userId)
    }
    if (state.selected.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.accentSoft, ZillitTheme.shapes.medium)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = "${state.selected.size} selected",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Clear",
                onClick = { onEvent(InvoicesEvent.ClearSelection) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Approve Selected",
                onClick = { onEvent(InvoicesEvent.ApproveSelected) },
                size = ButtonSize.Small,
                enabled = canBatch && !state.busy,
                loading = state.busy,
            )
        }
    } else {
        CountLine(rows.size)
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            columns = approvalColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = "Nothing awaiting approval",
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun approvalColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = listOf(
    TableColumn<Invoice>("", ColumnWidth.Fixed(CHECK_WIDTH)) { invoice ->
        val tiers = state.tiersOf(invoice)
        if (ApprovalChain.canApprove(invoice, tiers, state.viewer.userId)) {
            ZillitCheckbox(
                checked = invoice.id in state.selected,
                onCheckedChange = { onEvent(InvoicesEvent.ToggleSelect(invoice.id)) },
            )
        }
    },
) + leadingColumns(state) + poColumn() + approvalColumn(state, queue = true) + slaColumn(state) +
    TableColumn("Action", ColumnWidth.Fixed(QUEUE_ACTION_WIDTH)) { invoice -> QueueActions(state, invoice, onEvent) }

@Composable
private fun QueueActions(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    val tiers = state.tiersOf(invoice)
    val viewer = state.viewer
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            InvoiceRules.showOverrideAndPay(invoice, viewer) && !invoice.isApproved -> ZillitButton(
                text = "Override & Pay",
                onClick = { onEvent(InvoicesEvent.OverrideAndPay(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ApprovalChain.canApprove(invoice, tiers, viewer.userId) -> ZillitButton(
                text = "Approve",
                onClick = { onEvent(InvoicesEvent.Approve(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (InvoiceRules.showOverride(invoice, tiers, viewer)) {
            ZillitButton(
                text = "Override",
                onClick = { onEvent(InvoicesEvent.Override(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (InvoiceRules.showChase(invoice, tiers, viewer)) {
            val chased = invoice.id in state.chased
            ZillitButton(
                text = if (chased) "Chased" else "Chase",
                onClick = { onEvent(InvoicesEvent.Chase(invoice)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !chased,
            )
        }
    }
}

private val DEPARTMENT_SELECT_WIDTH = 220.dp
private val DUE_WIDTH = 110.dp
private val PAY_WIDTH = 120.dp
private val CHECK_WIDTH = 40.dp
private val QUEUE_ACTION_WIDTH = 250.dp
