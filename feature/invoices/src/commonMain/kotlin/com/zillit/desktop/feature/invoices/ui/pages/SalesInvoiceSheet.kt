package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.SalesInvoiceDraft

/**
 * Raising a sales invoice — money owed to the production, not by it. The
 * web's form: the client, an invoice date (today to start with), the due
 * date, the currency, and line items whose gross is the invoice's.
 */
@Suppress("LongMethod") // One form: three rows of fields over the line card, each a few lines.
@Composable
internal fun SalesInvoiceSheet(
    state: InvoicesUiState,
    draft: SalesInvoiceDraft,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val edit = { next: SalesInvoiceDraft -> onEvent(InvoicesEvent.EditSalesInvoice(next)) }
    val enabled = !draft.busy
    ZillitDialogShell(
        title = str(S.desktop_raise_an_invoice),
        subtitle = str(S.desktop_inv_raise_subtitle),
        visible = true,
        width = SALES_WIDTH,
        onDismiss = { if (!draft.busy) onEvent(InvoicesEvent.CancelSalesInvoice) },
        icon = ZillitIcons.CreditCard,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelSalesInvoice) },
                variant = ButtonVariant.Tertiary,
                enabled = enabled,
            )
            ZillitButton(
                text = str(S.create),
                onClick = { onEvent(InvoicesEvent.ConfirmSalesInvoice) },
                enabled = draft.isReady && enabled,
                loading = draft.busy,
            )
        },
    ) {
        FieldRow {
            ZillitTextField(
                value = draft.clientName,
                onValueChange = { edit(draft.copy(clientName = it)) },
                label = str(S.desktop_client_required),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.reference,
                onValueChange = { edit(draft.copy(reference = it)) },
                label = str(S.desktop_reference),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
        FieldRow {
            ZillitDateField(
                value = draft.invoiceDate,
                onValueChange = { edit(draft.copy(invoiceDate = it)) },
                label = "${str(S.desktop_invoice_date_title)} *",
                enabled = enabled,
                errorText = str(S.desktop_that_is_not_a_date)
                    .takeIf { draft.invoiceDate.isNotBlank() && draft.invoiceDateIsWrong },
                modifier = Modifier.weight(1f),
            )
            ZillitDateField(
                value = draft.dueDate,
                onValueChange = { edit(draft.copy(dueDate = it)) },
                label = str(S.ah_run_detail_col_due),
                enabled = enabled,
                errorText = str(S.desktop_that_is_not_a_date).takeIf { draft.dateIsWrong },
                helperText = draft.dueDateMs?.let { str(S.desktop_due_on, InvoiceFormat.date(it)) },
                modifier = Modifier.weight(1f),
            )
            val currency = draft.currency.ifBlank { state.projectCurrency }
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = str(S.asset_currency),
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitSelect(
                    value = currency,
                    options = (state.currencyOptions + currency).distinct(),
                    onSelect = { edit(draft.copy(currency = it)) },
                    label = { it },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ZillitTextField(
            value = draft.description,
            onValueChange = { edit(draft.copy(description = it)) },
            label = str(S.description),
            singleLine = false,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        LineDraftCard(
            state = state,
            draft = draft.lines,
            currency = salesCurrency(state, draft),
            frozen = !enabled,
            error = draft.lineError,
        ) { onEvent(InvoicesEvent.EditSalesLines(it)) }
    }
}

private fun salesCurrency(state: InvoicesUiState, draft: SalesInvoiceDraft) =
    draft.currency.ifBlank { state.projectCurrency }

/** "Delete it?" before a draft sales invoice goes — the web's ConfirmModal. */
@Composable
internal fun SalesDeleteDialog(invoice: SalesInvoice, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_inv_delete_sales_invoice),
        onDismiss = { onEvent(InvoicesEvent.CancelDeleteSales) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelDeleteSales) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(InvoicesEvent.ConfirmDeleteSales) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = str(
                S.desktop_inv_delete_credit_confirm,
                invoice.reference.ifBlank { str(S.desktop_inv_this_invoice) },
            ),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

private val SALES_WIDTH = 900.dp
