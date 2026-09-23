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

@Composable
internal fun DeleteDialog(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.ah_delete_invoice),
        onDismiss = { onEvent(InvoicesEvent.CancelDelete) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(InvoicesEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = state.busy,
            )
        },
    ) {
        ZillitText(
            text = "Delete invoice ${invoice.displayNumber} (${state.vendorName(invoice)})? This cannot be undone.",
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}
