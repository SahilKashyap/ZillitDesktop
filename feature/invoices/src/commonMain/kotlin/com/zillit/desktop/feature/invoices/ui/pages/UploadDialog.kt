// The delete confirmation.
package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.runtime.Composable
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * "Delete Invoice" — the web's `ConfirmModal` (`DepartmentInvoiceModule.jsx:1282-1293`):
 * the invoice named by its number, else its description, else the first
 * eight characters of its id; "Deleting…" while the delete is out, and no
 * way to dismiss it until it answers.
 */
@Composable
internal fun DeleteDialog(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    val deleting = state.busy
    val name = invoice.invoiceNumber.ifBlank { invoice.description }.ifBlank { invoice.id.take(ID_CHARS) }
    ZillitDialogShell(
        title = str(S.ah_delete_invoice),
        onDismiss = { if (!deleting) onEvent(InvoicesEvent.CancelDelete) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
                enabled = !deleting,
            )
            ZillitButton(
                text = if (deleting) str(S.ah_deleting) else str(S.delete),
                onClick = { onEvent(InvoicesEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = deleting,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_inv_delete_invoice_named, name),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

private const val ID_CHARS = 8
