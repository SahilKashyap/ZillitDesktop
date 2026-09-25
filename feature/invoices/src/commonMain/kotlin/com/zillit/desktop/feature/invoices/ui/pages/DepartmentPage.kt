// One composable per board piece.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.ui.DepartmentTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.QuickFilter
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The non-accountant view — the web's `DepartmentInvoiceModule`: the tab bar
 * (with "Upload Invoices" beside it), then either a batch list, the runs
 * awaiting this reader's signature, or the quick filters over one invoice table.
 */
@Composable
internal fun ColumnScope.DepartmentPage(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    // The run tab is on the strip only for someone on the run chain (`isRunApprover`).
    val tabs = DepartmentTab.entries.filter {
        it != DepartmentTab.RunApproval || state.viewer.isRunApprover || state.departmentTab == it
    }
    // The web's TabBar action (`DepartmentInvoiceModule.jsx:1050-1054`).
    // Always there — the web gates it on nothing.
    val upload: @Composable RowScope.() -> Unit = {
        ZillitButton(
            text = str(S.desktop_inv_upload_invoices),
            onClick = { onEvent(InvoicesEvent.UploadInvoice) },
            leadingIcon = ZillitIcons.Upload,
            size = ButtonSize.Small,
            enabled = state.bulkPick == null,
        )
    }
    ZillitTabStrip(
        // Counts only where the web shows them: the Approval Queue, My Invoices
        // and the run tab, each its own unread (`:1040-1044, 1055`).
        tabs = tabs.map { tab -> ZillitTab(tab.id, tab.label, count = tab.badgeKey?.let(state.unread::get) ?: 0) },
        activeId = state.departmentTab.id,
        onSelect = { id -> onEvent(InvoicesEvent.SelectDepartmentTab(DepartmentTab.fromId(id))) },
        trailing = upload,
    )
    when (state.departmentTab) {
        // The web's fourth tab: the batches this department sent, still being extracted.
        DepartmentTab.Uploads -> {
            UploadsCard(state, onEvent)
            return
        }
        DepartmentTab.RunApproval -> {
            RunApprovalList(state, onEvent)
            return
        }
        DepartmentTab.ApprovalQueue, DepartmentTab.MyDepartment, DepartmentTab.MyInvoices -> Unit
    }
    if (state.departmentTab == DepartmentTab.MyDepartment && state.viewer.departmentId.isBlank()) {
        ZillitNotice(text = str(S.desktop_inv_no_department_board))
    }
    val rows = state.shownInvoices
    QuickFilterRow(state, rows.size, onEvent)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            columns = departmentColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            // The tab's own sentence whatever the filter (`:1159-1163`).
            emptyTitle = state.departmentTab.emptyText,
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** "QUICK FILTERS", the four buttons, and the count inline beside them (`:1124-1145`). */
@Composable
private fun QuickFilterRow(state: InvoicesUiState, count: Int, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSectionLabel(str(S.desktop_inv_quick_filters))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            QuickFilter.entries.forEach { filter ->
                ZillitChoiceChip(
                    label = filter.label,
                    selected = state.quickFilter == filter,
                    onClick = { onEvent(InvoicesEvent.SelectQuickFilter(filter)) },
                )
            }
        }
        MutedLine(countMeta(count))
    }
}

/**
 * Whether the row may be deleted on the open tab — the web's `row.canDelete`
 * (`DepartmentInvoiceModule.jsx:953-969`).
 *
 * The Approval Queue takes the queue rule, [InvoiceRules.canDeleteFromQueue],
 * with the board's own override right (`me.can_override || me.is_senior`,
 * nothing else); My Department and My Invoices keep the owner-only rule.
 */
internal fun InvoicesUiState.departmentCanDelete(invoice: Invoice): Boolean =
    if (departmentTab == DepartmentTab.ApprovalQueue) {
        InvoiceRules.canDeleteFromQueue(
            invoice = invoice,
            tiers = tiersOf(invoice),
            viewer = viewer,
            canOverride = viewer.serverOverride,
            locked = isLocked(invoice),
        )
    } else {
        InvoiceRules.canDeleteOwn(invoice, viewer)
    }

/** Invoice · Vendor · Gross · PO · Approval · SLA, then the row's Delete (`:1167-1235`). */
private fun departmentColumns(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit): List<TableColumn<Invoice>> =
    listOf(
        TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
            RefWithUnread(invoice.displayNumber, state.rowUnread(state.departmentTab.rowBadgeKey, invoice.id))
        },
        TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_WIDE)) { invoice ->
            // The vendor list's name or "Unknown", greyed (`vendorMap[vendor_id] || "Unknown"`).
            val name = state.vendors[invoice.vendorId]?.name?.takeIf { it.isNotBlank() }
            CellText(name ?: str(S.desktop_unknown), muted = name == null)
        },
        TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
            MoneyText(it.grossAmount, it.currency, state.projectCurrency)
        },
        TableColumn(str(S.desktop_po), ColumnWidth.Fixed(PO_WIDTH)) { DepartmentPoCell(it) },
        approvalColumn(state, queue = false),
        TableColumn(str(S.desktop_sla), ColumnWidth.Fixed(SLA_WIDTH)) { invoice ->
            // The vendor's payment terms as a blue pill, or a grey "—" (`:916-919`).
            val vendor = state.vendors[invoice.vendorId]
            val terms = vendor?.slaLabel ?: vendor?.terms?.trim()?.takeIf { it.isNotEmpty() }
            ZillitStatusPill(
                label = terms ?: "—",
                tone = if (terms != null) StatusTone.InTransit else StatusTone.Neutral,
            )
        },
        TableColumn("", ColumnWidth.Fixed(ACTIONS_WIDTH)) { invoice ->
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                val deleting = state.busy && state.confirmDelete?.id == invoice.id
                when {
                    deleting -> ZillitSpinner()
                    state.departmentCanDelete(invoice) -> ZillitButton(
                        text = str(S.delete),
                        onClick = { onEvent(InvoicesEvent.RequestDelete(invoice)) },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                    )
                    else -> Unit
                }
            }
        },
    )

/** The reference in mono, with its unread chip beside it — the web's `renderUnread`. */
@Composable
private fun RefWithUnread(text: String, unread: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        ZillitBadge(count = unread)
    }
}

/**
 * The board's PO cell: green mono text for a matched order — `linked_pos` or
 * `po_id`, never a bare typed `po_number` — and a red "No PO" pill otherwise.
 * The id fallback is `PO-` and four characters, as the web cuts it.
 */
@Composable
private fun DepartmentPoCell(invoice: Invoice) {
    if (!invoice.hasMatchedPo) {
        ZillitStatusPill(label = str(S.desktop_no_po), tone = StatusTone.Rejected)
        return
    }
    val label = when {
        invoice.linkedPos.size > 1 -> str(S.desktop_po_count_pos, invoice.linkedPos.size)
        else -> invoice.linkedPos.firstOrNull()?.poNumber?.takeIf { it.isNotBlank() }
            ?: invoice.poNumber.takeIf { it.isNotBlank() }
            ?: invoice.poId.takeIf { it.isNotBlank() }?.let { "PO-" + it.take(PO_ID_CHARS) }
            ?: "—"
    }
    ZillitText(
        text = label,
        style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.success,
        maxLines = 1,
    )
}

// -- Payment Run Approval ----------------------------------------------------------

/**
 * The runs whose next tier this reader signs — the web's run-approval tab
 * (`DepartmentInvoiceModule.jsx:1060-1108`). A row opens the run; signing and
 * turning it down happen there.
 */
@Composable
private fun ColumnScope.RunApprovalList(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val runs = state.runsAwaitingMe
    if (state.loading && runs.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSpinner()
            MutedLine(str(S.desktop_inv_loading_payment_runs))
        }
        return
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = runs,
            columns = runColumns(state),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.OpenRun(it)) },
            emptyTitle = DepartmentTab.RunApproval.emptyText,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Run · Name · Method · Total · Invoices · Approval · Created (`:1076-1106`). */
private fun runColumns(state: InvoicesUiState): List<TableColumn<PaymentRun>> = listOf(
    TableColumn(str(S.ah_run_card_run_label), ColumnWidth.Weight(WEIGHT_NARROW)) { run ->
        RefWithUnread(run.number.ifBlank { "—" }, state.rowUnread(DepartmentTab.RunApproval.rowBadgeKey, run.id))
    },
    TableColumn(str(S.name), ColumnWidth.Weight(WEIGHT_WIDEST)) { run ->
        ZillitText(
            text = run.name.ifBlank { "—" },
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
    },
    TableColumn(str(S.desktop_method), ColumnWidth.Fixed(PO_WIDTH)) { run ->
        // The method's code, capitalised, as the web prints it.
        ZillitStatusPill(label = run.payMethod.wire.replaceFirstChar { it.uppercase() }, tone = StatusTone.InTransit)
    },
    TableColumn(str(S.ah_run_total_label), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) { run ->
        // `fmt(run.total_amount || run.computed_total)` — no currency, so the production's.
        MoneyText(run.total, "", state.projectCurrency)
    },
    TableColumn(str(S.ah_invoices), ColumnWidth.Fixed(SLA_WIDTH)) { run ->
        ZillitText(text = run.invoiceCount.toString(), style = ZillitTheme.typography.numeric, maxLines = 1)
    },
    TableColumn(str(S.ah_step_approval), ColumnWidth.Weight(WEIGHT_MEDIUM)) { run ->
        ZillitStatusPill(
            label = str(S.ah_status_pending_progress, run.approvals.size, state.runAuth.size),
            tone = StatusTone.Pending,
        )
    },
    TableColumn(str(S.drive_created), ColumnWidth.Fixed(SLA_WIDTH)) { run ->
        // `dd Mon` — the date without its year.
        CellText(InvoiceFormat.date(run.createdAtMs).substringBeforeLast(' '), muted = true)
    },
)

private const val PO_ID_CHARS = 4
