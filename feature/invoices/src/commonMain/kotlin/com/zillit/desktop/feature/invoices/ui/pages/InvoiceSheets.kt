package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.AssignmentReason
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.ui.AssignRequest
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.ProcessRequest
import com.zillit.desktop.feature.invoices.ui.RunRejection
import com.zillit.desktop.feature.invoices.ui.SalesInvoiceDraft

/**
 * Processing a mixed selection.
 *
 * Each payment method leaves the queue its own way, so a selection spanning
 * more than one cannot have a single button: this sheet offers one per method,
 * with what that method would do spelled out beside it.
 */
@Composable
internal fun ProcessSheet(state: InvoicesUiState, request: ProcessRequest, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = "Process ${request.invoices.size} invoices",
        subtitle = "They are paid different ways, so each method is actioned on its own.",
        visible = true,
        onDismiss = { if (request.busy == null) onEvent(InvoicesEvent.CancelPaymentRun) },
        icon = ZillitIcons.Wallet,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(InvoicesEvent.CancelPaymentRun) },
                variant = ButtonVariant.Tertiary,
                enabled = request.busy == null,
            )
        },
    ) {
        request.methods.forEach { method ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = "${method.label} · ${request.countFor(method)}",
                    style = ZillitTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = method.processHint(),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitButton(
                    text = method.processLabel(),
                    onClick = { onEvent(InvoicesEvent.ProcessSelected(method)) },
                    size = ButtonSize.Small,
                    enabled = request.busy == null && method.isProcessable,
                    loading = request.busy == method,
                )
            }
        }
        if (state.bacsGroups.size > 1) {
            MutedLine("BACs makes one run per vendor and currency: ${state.bacsGroups.size} runs.")
        }
    }
}

private val PayMethod.isProcessable: Boolean
    get() = this == PayMethod.Bacs || this == PayMethod.Cheque || this in PaymentRuns.WIRE_METHODS

private fun PayMethod.processLabel(): String = when {
    this == PayMethod.Bacs -> "Create run"
    this == PayMethod.Cheque -> "Open"
    this in PaymentRuns.WIRE_METHODS -> "Mark paid"
    else -> "Not here"
}

private fun PayMethod.processHint(): String = when {
    this == PayMethod.Bacs -> "one run per vendor"
    this == PayMethod.Cheque -> "printed from the invoice"
    this in PaymentRuns.WIRE_METHODS -> "settled outside, marked here"
    else -> "handled elsewhere"
}

/** Turning a run down. The reason is mandatory, and it goes on the run's record. */
@Composable
internal fun RejectRunSheet(request: RunRejection, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = "Reject ${request.run.number.ifBlank { "payment run" }}",
        subtitle = "Say why. The reason is kept against the run and shown to whoever built it.",
        visible = true,
        onDismiss = { if (!request.busy) onEvent(InvoicesEvent.CancelRejectRun) },
        icon = ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(InvoicesEvent.CancelRejectRun) },
                variant = ButtonVariant.Tertiary,
                enabled = !request.busy,
            )
            ZillitButton(
                text = "Reject run",
                onClick = { onEvent(InvoicesEvent.ConfirmRejectRun) },
                enabled = request.isReady && !request.busy,
                loading = request.busy,
            )
        },
    ) {
        ZillitTextField(
            value = request.reason,
            onValueChange = { onEvent(InvoicesEvent.RejectRunReasonChanged(it)) },
            label = "Reason (required)",
            singleLine = false,
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Handing entry work to someone else, with the reason on the record. */
@Composable
internal fun AssignSheet(state: InvoicesUiState, request: AssignRequest, onEvent: (InvoicesEvent) -> Unit) {
    val plural = if (request.invoiceIds.size == 1) "invoice" else "invoices"
    ZillitDialogShell(
        title = "Assign ${request.invoiceIds.size} $plural",
        subtitle = "The person you pick can open and post them; everyone else still cannot.",
        visible = true,
        onDismiss = { if (!request.busy) onEvent(InvoicesEvent.CancelAssign) },
        icon = ZillitIcons.Users,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(InvoicesEvent.CancelAssign) },
                variant = ButtonVariant.Tertiary,
                enabled = !request.busy,
            )
            ZillitButton(
                text = "Assign",
                onClick = { onEvent(InvoicesEvent.ConfirmAssign) },
                enabled = request.isReady && !request.busy,
                loading = request.busy,
            )
        },
    ) {
        val current = state.invoices
            .filter { it.id in request.invoiceIds }
            .mapNotNull { state.assigneeName(it) }
            .distinct()
        if (current.isNotEmpty()) MutedLine("Currently assigned to: ${current.joinToString(", ")}")
        if (state.assignees.isEmpty()) {
            MutedLine("Nobody from the accounts department is on this production yet.")
        }
        ZillitSelect(
            value = state.assignees.firstOrNull { it.id == request.userId },
            options = listOf<InvoiceAssignee?>(null) + state.assignees.filter { it.id !in alreadyOn(state, request) },
            onSelect = { picked -> onEvent(InvoicesEvent.EditAssign(request.copy(userId = picked?.id.orEmpty()))) },
            label = { it?.label ?: "Select team member…" },
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitSelect(
            value = request.reason,
            options = listOf<AssignmentReason?>(null) + AssignmentReason.entries,
            onSelect = { reason -> onEvent(InvoicesEvent.EditAssign(request.copy(reason = reason))) },
            label = { it?.label ?: "Select a reason…" },
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (request.reason?.needsNotes() == true) {
            ZillitTextField(
                value = request.notes,
                onValueChange = { onEvent(InvoicesEvent.EditAssign(request.copy(notes = it))) },
                label = "Custom reason (required)",
                singleLine = false,
                enabled = !request.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Whoever already holds one of these invoices is not offered again. */
private fun alreadyOn(state: InvoicesUiState, request: AssignRequest): Set<String> =
    state.invoices.filter { it.id in request.invoiceIds }.map { it.assignedTo }.filter { it.isNotBlank() }.toSet()

/** Raising a sales invoice — money owed to the production, not by it. */
@Composable
internal fun SalesInvoiceSheet(
    state: InvoicesUiState,
    draft: SalesInvoiceDraft,
    onEvent: (InvoicesEvent) -> Unit,
) {
    ZillitDialogShell(
        title = "Raise an invoice",
        subtitle = "It is drafted here and only leaves the production when you send it.",
        visible = true,
        onDismiss = { if (!draft.busy) onEvent(InvoicesEvent.CancelSalesInvoice) },
        icon = ZillitIcons.CreditCard,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(InvoicesEvent.CancelSalesInvoice) },
                variant = ButtonVariant.Tertiary,
                enabled = !draft.busy,
            )
            ZillitButton(
                text = "Create",
                onClick = { onEvent(InvoicesEvent.ConfirmSalesInvoice) },
                enabled = draft.isReady && !draft.busy,
                loading = draft.busy,
            )
        },
    ) {
        SalesFields(state, draft, onEvent)
    }
}

@Composable
private fun ColumnScope.SalesFields(
    state: InvoicesUiState,
    draft: SalesInvoiceDraft,
    onEvent: (InvoicesEvent) -> Unit,
) {
        ZillitTextField(
            value = draft.clientName,
            onValueChange = { onEvent(InvoicesEvent.EditSalesInvoice(draft.copy(clientName = it))) },
            label = "Client (required)",
            enabled = !draft.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = draft.reference,
            onValueChange = { onEvent(InvoicesEvent.EditSalesInvoice(draft.copy(reference = it))) },
            label = "Reference",
            enabled = !draft.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = draft.description,
            onValueChange = { onEvent(InvoicesEvent.EditSalesInvoice(draft.copy(description = it))) },
            label = "Description",
            singleLine = false,
            enabled = !draft.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.amount,
                onValueChange = { onEvent(InvoicesEvent.EditSalesInvoice(draft.copy(amount = it))) },
                label = "Amount (required)",
                enabled = !draft.busy,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.currency.ifBlank { state.projectCurrency },
                onValueChange = { onEvent(InvoicesEvent.EditSalesInvoice(draft.copy(currency = it))) },
                label = "Currency",
                enabled = !draft.busy,
                modifier = Modifier.weight(1f),
            )
        }
        ZillitDateField(
            value = draft.dueDate,
            onValueChange = { onEvent(InvoicesEvent.EditSalesInvoice(draft.copy(dueDate = it))) },
            label = "Due date",
            enabled = !draft.busy,
            errorText = "That is not a date.".takeIf { draft.dateIsWrong },
            helperText = draft.dueDateMs?.let { "Due ${InvoiceFormat.date(it)}" },
            modifier = Modifier.fillMaxWidth(),
        )
}
