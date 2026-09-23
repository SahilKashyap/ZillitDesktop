package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.AssignmentReason
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.ui.AssignRequest
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.ProcessRequest
import com.zillit.desktop.feature.invoices.ui.RunRejection
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
        title = str(S.desktop_inv_process_n_invoices, request.invoices.size),
        subtitle = str(S.desktop_inv_process_subtitle),
        visible = true,
        onDismiss = { if (request.busy == null) onEvent(InvoicesEvent.CancelPaymentRun) },
        icon = ZillitIcons.Wallet,
        actions = {
            ZillitButton(
                text = str(S.close),
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
            MutedLine(str(S.desktop_inv_bacs_runs_note, state.bacsGroups.size))
        }
    }
}

private val PayMethod.isProcessable: Boolean
    get() = this == PayMethod.Bacs || this == PayMethod.Cheque || this in PaymentRuns.WIRE_METHODS

private fun PayMethod.processLabel(): String = when {
    this == PayMethod.Bacs -> str(S.desktop_create_run)
    this == PayMethod.Cheque -> str(S.recce_open)
    this in PaymentRuns.WIRE_METHODS -> str(S.desktop_mark_paid)
    else -> str(S.desktop_not_here)
}

private fun PayMethod.processHint(): String = when {
    this == PayMethod.Bacs -> str(S.desktop_inv_one_run_per_vendor)
    this == PayMethod.Cheque -> str(S.desktop_inv_printed_from_the_invoice)
    this in PaymentRuns.WIRE_METHODS -> str(S.desktop_inv_settled_outside)
    else -> str(S.desktop_handled_elsewhere)
}

/** Turning a run down. The reason is mandatory, and it goes on the run's record. */
@Composable
internal fun RejectRunSheet(request: RunRejection, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_reject_named, request.run.number.ifBlank { str(S.desktop_payment_run_lower) }),
        subtitle = str(S.desktop_inv_reject_run_subtitle),
        visible = true,
        onDismiss = { if (!request.busy) onEvent(InvoicesEvent.CancelRejectRun) },
        icon = ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelRejectRun) },
                variant = ButtonVariant.Tertiary,
                enabled = !request.busy,
            )
            ZillitButton(
                text = str(S.ah_run_detail_btn_reject_confirm),
                onClick = { onEvent(InvoicesEvent.ConfirmRejectRun) },
                enabled = request.isReady && !request.busy,
                loading = request.busy,
            )
        },
    ) {
        ZillitTextField(
            value = request.reason,
            onValueChange = { onEvent(InvoicesEvent.RejectRunReasonChanged(it)) },
            label = str(S.docusign_decline_reason_label),
            singleLine = false,
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Handing entry work to someone else, with the reason on the record. */
@Composable
internal fun AssignSheet(state: InvoicesUiState, request: AssignRequest, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = if (request.invoiceIds.size == 1) {
            str(S.desktop_inv_assign_one_invoice)
        } else {
            str(S.desktop_inv_assign_n_invoices, request.invoiceIds.size)
        },
        subtitle = str(S.desktop_inv_assign_subtitle),
        visible = true,
        onDismiss = { if (!request.busy) onEvent(InvoicesEvent.CancelAssign) },
        icon = ZillitIcons.Users,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelAssign) },
                variant = ButtonVariant.Tertiary,
                enabled = !request.busy,
            )
            ZillitButton(
                text = str(S.assign),
                onClick = { onEvent(InvoicesEvent.ConfirmAssign) },
                enabled = request.isReady && !request.busy,
                loading = request.busy,
            )
        },
    ) {
        AssignSheetFields(state, request, onEvent)
    }
}

/** Who it goes to, why, and the free-text reason the picked reason may want. */
@Composable
private fun AssignSheetFields(
    state: InvoicesUiState,
    request: AssignRequest,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val current = state.invoices
        .filter { it.id in request.invoiceIds }
        .mapNotNull { state.assigneeName(it) }
        .distinct()
    if (current.isNotEmpty()) MutedLine(str(S.desktop_currently_assigned_to, current.joinToString(", ")))
    if (state.assignees.isEmpty()) {
        MutedLine(str(S.desktop_inv_nobody_from_accounts))
    }
    ZillitSelect(
        value = state.assignees.firstOrNull { it.id == request.userId },
        options = listOf<InvoiceAssignee?>(null) + state.assignees.filter { it.id !in alreadyOn(state, request) },
        onSelect = { picked -> onEvent(InvoicesEvent.EditAssign(request.copy(userId = picked?.id.orEmpty()))) },
        label = { it?.label ?: str(S.desktop_select_team_member) },
        enabled = !request.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    ZillitSelect(
        value = request.reason,
        options = listOf<AssignmentReason?>(null) + AssignmentReason.entries,
        onSelect = { reason -> onEvent(InvoicesEvent.EditAssign(request.copy(reason = reason))) },
        label = { it?.label ?: str(S.desktop_select_a_reason) },
        enabled = !request.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (request.reason?.needsNotes() == true) {
        ZillitTextField(
            value = request.notes,
            onValueChange = { onEvent(InvoicesEvent.EditAssign(request.copy(notes = it))) },
            label = str(S.desktop_custom_reason_required),
            singleLine = false,
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Whoever already holds one of these invoices is not offered again. */
private fun alreadyOn(state: InvoicesUiState, request: AssignRequest): Set<String> =
    state.invoices.filter { it.id in request.invoiceIds }.map { it.assignedTo }.filter { it.isNotBlank() }.toSet()
