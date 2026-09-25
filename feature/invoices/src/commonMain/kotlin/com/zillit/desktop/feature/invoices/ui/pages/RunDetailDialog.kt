package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.RunDetailView

/**
 * One payment run — the web's run detail modal (`PaymentsPage.jsx`).
 *
 * What it pays (the BACs file contents and the invoice table), why it was
 * turned down if it was, and the footer: Cancel run for anyone who may operate
 * runs, and Reject / Approve only for the person the run's own chain waits on
 * next. No approval chain is drawn, on purpose: the server clears a run's
 * `approval` list when it is rejected, so a chain could only guess (ZL-20569).
 */
@Composable
internal fun RunDetailDialog(state: InvoicesUiState, view: RunDetailView, onEvent: (InvoicesEvent) -> Unit) {
    val run = view.shown
    val decision = state.runApproval(run)
    val invoices = view.detail?.invoices.orEmpty()
    // The department approver's modal (`DepartmentInvoiceModule.jsx:1297-1429`)
    // has no Cancel run, no BACs preview and no rejection banner — only what
    // the run pays, who built it, and Reject / Approve for the next signer.
    val department = !state.isAccountant
    ZillitDialogShell(
        // The accountant's modal is titled "{number} — {name}" once the run is
        // read, "Loading…" until then, with no status line (`PaymentsPage.jsx:2125-2132`).
        title = when {
            department -> listOf(run.number, run.name).filter { it.isNotBlank() }.joinToString(" — ")
                .ifBlank { str(S.desktop_payment_run_lower) }
            view.detail == null -> str(S.ah_loading)
            else -> "${run.number} — ${run.name}"
        },
        subtitle = if (department) run.status.label else null,
        visible = true,
        onDismiss = { if (!view.busy) onEvent(InvoicesEvent.CloseRun) },
        icon = ZillitIcons.Wallet,
        width = RUN_DIALOG_WIDTH,
        actions = {
            if (!department) {
                ZillitTooltip(if (state.viewer.canOperateRuns) "" else str(S.desktop_inv_no_run_access_tooltip)) {
                    ZillitButton(
                        text = str(S.desktop_inv_cancel_run),
                        onClick = { onEvent(InvoicesEvent.RequestCancelRun) },
                        variant = ButtonVariant.Tertiary,
                        enabled = state.viewer.canOperateRuns && !view.busy,
                    )
                }
            }
            if (decision.canApprove) {
                ZillitButton(
                    text = str(S.reject),
                    onClick = { onEvent(InvoicesEvent.StartRejectRun(run)) },
                    variant = ButtonVariant.Secondary,
                    enabled = !view.busy,
                )
                ZillitButton(
                    text = if (view.busy && !department) str(S.ah_run_detail_btn_approving) else str(S.approve),
                    onClick = { onEvent(InvoicesEvent.ApproveRun(run)) },
                    enabled = !view.busy,
                    loading = view.busy,
                )
            }
        },
    ) {
        if (view.loading && view.detail == null) {
            MutedLine(str(S.desktop_inv_loading_run_details))
            return@ZillitDialogShell
        }
        if (!department && run.status == PaymentRunStatus.Rejected) RejectionBanner(state, run)
        if (!department) BacsContents(state, invoices)
        ZillitSectionLabel(str(S.ah_run_detail_invoices_header))
        RunInvoiceTable(state, invoices)
        if (department) CreatedBy(state, run)
    }
    // After the dialog it answers, so it draws over it.
    CancelRunConfirm(view, onEvent)
}

/**
 * "Created by": the builder's name (or "System" when nobody the crew list
 * knows built it) and when — the department modal's footer.
 */
@Composable
private fun CreatedBy(state: InvoicesUiState, run: PaymentRun) {
    val who = run.createdBy.takeIf { it.isNotBlank() }?.let { state.userNames[it] }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitSectionLabel(str(S.cs_created_by))
        ZillitText(
            text = who ?: str(S.desktop_language_system_short),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        MutedLine(InvoiceFormat.dateTime(run.createdAtMs))
    }
}

/** "Cancel payment run?" — cancelling returns every invoice to open items, so it is asked first. */
@Composable
private fun CancelRunConfirm(view: RunDetailView, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_inv_cancel_run_title),
        visible = view.confirmCancel,
        onDismiss = { if (!view.busy) onEvent(InvoicesEvent.KeepRun) },
        icon = ZillitIcons.Warning,
        scrollable = false,
        actions = {
            ZillitButton(
                text = str(S.desktop_inv_keep_run),
                onClick = { onEvent(InvoicesEvent.KeepRun) },
                variant = ButtonVariant.Tertiary,
                enabled = !view.busy,
            )
            ZillitButton(
                text = str(S.desktop_inv_cancel_run),
                onClick = { onEvent(InvoicesEvent.ConfirmCancelRun) },
                variant = ButtonVariant.Danger,
                loading = view.busy,
            )
        },
    ) {
        ZillitText(text = str(S.desktop_inv_cancel_run_message), style = ZillitTheme.typography.bodyMedium)
    }
}

/** Why the run was turned down, and by whom — the web's rejection banner (ZL-20484). */
@Composable
private fun RejectionBanner(state: InvoicesUiState, run: PaymentRun) {
    val colors = ZillitTheme.colors
    val who = run.rejectedBy.takeIf { it.isNotBlank() }?.let { state.userNames[it] ?: it }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.dangerSoft)
            .border(1.dp, colors.danger.copy(alpha = BORDER_ALPHA), ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        // `DD Mon YYYY | h:mm AM/PM`, the web's `fmtDateTime` (`PaymentsPage.jsx:2158`).
        // The when rides on the who: with nobody named, the web prints neither.
        val stamp = who
            ?.let { name -> listOfNotNull(name, run.rejectedAtMs?.let { at -> PaymentRuns.stamp(at) }) }
            ?.joinToString(" · ")
            .orEmpty()
        ZillitText(
            text = if (stamp.isBlank()) str(S.ah_run_rejected_toast) else "${str(S.ah_run_rejected_toast)} · $stamp",
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
            color = colors.danger,
        )
        ZillitText(
            text = run.rejectionReason.ifBlank { str(S.desktop_inv_no_reason_provided) },
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

/** One line per vendor with what the file pays them, then the total — the web's BACs preview. */
@Composable
private fun BacsContents(state: InvoicesUiState, invoices: List<Invoice>) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitSectionLabel(str(S.desktop_inv_bacs_file_contents))
        MutedLine(str(S.desktop_inv_vol1_header))
        invoices.groupBy { runVendor(state, it) }.forEach { (vendor, rows) ->
            ZillitText(
                text = "$vendor · ${sumLabel(state, rows)}",
                style = ZillitTheme.typography.numeric,
                maxLines = 1,
            )
        }
        ZillitText(
            text = str(
                S.desktop_inv_bacs_total,
                invoices.map { it.vendorId }.distinct().size,
                sumLabel(state, invoices),
            ),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            color = colors.accentText,
        )
        MutedLine(str(S.desktop_inv_eof1_trailer))
    }
}

/** A run row's vendor — the web's `vendorMap[vendor_id] || "Unknown"`. */
private fun runVendor(state: InvoicesUiState, invoice: Invoice): String =
    state.vendors[invoice.vendorId]?.name?.ifBlank { null } ?: str(S.desktop_unknown)

/** The invoices the run pays — a plain list, because a data table cannot live in a scrolling dialog. */
@Composable
private fun RunInvoiceTable(state: InvoicesUiState, invoices: List<Invoice>) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        RunRow(
            invoice = str(S.ah_run_detail_col_invoice),
            vendor = str(S.ah_run_detail_col_vendor),
            description = str(S.ah_run_detail_col_description),
            due = str(S.ah_run_detail_col_due),
            amount = str(S.ah_run_detail_col_amount),
            header = true,
        )
        invoices.forEach { invoice ->
            RunRow(
                invoice = invoice.displayNumber,
                // The accountant's rows carry their unread chip (`renderUnread`, `:2225`).
                unread = if (state.isAccountant) state.rowUnread(PAYMENTS_KEY, invoice.id) else 0,
                vendor = runVendor(state, invoice),
                description = invoice.description.ifBlank { "—" },
                due = InvoiceFormat.date(invoice.dueDateMs),
                amount = InvoiceFormat.money(invoice.grossAmount, invoice.currency.ifBlank { state.projectCurrency }),
            )
        }
        RunRow(
            invoice = "",
            vendor = str(S.ah_run_detail_summary_vendors, invoices.map { it.vendorId }.distinct().size),
            description = str(S.ah_run_detail_summary_invoices, invoices.size),
            due = "",
            amount = sumLabel(state, invoices),
            header = true,
        )
    }
}

@Composable
private fun RunRow(
    invoice: String,
    vendor: String,
    description: String,
    due: String,
    amount: String,
    header: Boolean = false,
    unread: Int = 0,
) {
    val colors = ZillitTheme.colors
    val style = if (header) {
        ZillitTheme.typography.columnHeader
    } else {
        ZillitTheme.typography.bodySmall
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (header) colors.surfaceSunken else colors.surface)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(INVOICE_COL),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = invoice, style = style, maxLines = 1)
            ZillitBadge(count = unread)
        }
        ZillitText(text = vendor, style = style, maxLines = 1, modifier = Modifier.weight(1f))
        ZillitText(
            text = description,
            style = style,
            maxLines = 1,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = due, style = style, maxLines = 1, modifier = Modifier.width(DUE_COL))
        ZillitText(text = amount, style = style, maxLines = 1, modifier = Modifier.width(AMOUNT_COL))
    }
}

/** A mixed-currency run is converted and says so, as the payment tiles do. */
private fun sumLabel(state: InvoicesUiState, invoices: List<Invoice>): String {
    val total = state.rates.total(invoices.map { it.grossAmount to it.currency })
    return total.caveat?.let { "${total.text} · $it" } ?: total.text
}

/** Payment Runs' `level_1`, which a run's invoice chips are filed under. */
private val PAYMENTS_KEY: String = AccountantPage.Payments.badgeKey.orEmpty()

private val RUN_DIALOG_WIDTH = 900.dp
private val INVOICE_COL = 120.dp
private val DUE_COL = 110.dp
private val AMOUNT_COL = 130.dp
private const val BORDER_ALPHA = 0.3f
