package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
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
 * "Process Selected Invoices" — the web's `ProcessModal` (`PaymentsPage.jsx:507-613`).
 *
 * One card per method code in the selection, each with its count, its own
 * button and the invoices it would act on: "Create BACs Run", "Mark Paid",
 * a disabled "Print Cheque", or "Process" for anything else. Without run
 * access every button is disabled; while one works, it says "Processing…"
 * and the others wait.
 */
@Composable
internal fun ProcessSheet(state: InvoicesUiState, request: ProcessRequest, onEvent: (InvoicesEvent) -> Unit) {
    val count = request.invoices.size
    ZillitDialogShell(
        title = str(S.desktop_inv_process_selected_invoices),
        subtitle = if (count == 1) {
            str(S.desktop_inv_one_selected_mixed, count)
        } else {
            str(S.desktop_inv_n_selected_mixed, count)
        },
        visible = true,
        onDismiss = { if (request.busy == null) onEvent(InvoicesEvent.CancelPaymentRun) },
        icon = ZillitIcons.Wallet,
        width = PROCESS_SHEET_WIDTH,
    ) {
        request.codes.forEach { code -> ProcessCard(state, request, code, onEvent) }
    }
}

@Composable
private fun ProcessCard(
    state: InvoicesUiState,
    request: ProcessRequest,
    code: String,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val cheque = code == PayMethod.Cheque.wire
    val wire = code in PaymentRuns.WIRE_CODES
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = PaymentRuns.methodLabel(code) { it.localised() }.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.textMuted,
                maxLines = 1,
            )
            ZillitStatusPill(
                label = request.countFor(code).toString(),
                tone = when {
                    code == PayMethod.Bacs.wire -> StatusTone.InTransit
                    wire -> StatusTone.Rejected
                    else -> StatusTone.Pending
                },
            )
            Spacer(Modifier.weight(1f))
            val busy = request.busy == code
            ZillitTooltip(if (cheque) str(S.desktop_inv_cheque_printing_unavailable) else "") {
                ZillitButton(
                    text = when {
                        busy -> str(S.desktop_processing_ellipsis)
                        code == PayMethod.Bacs.wire -> str(S.desktop_create_bacs_run)
                        wire -> str(S.desktop_mark_paid_title)
                        cheque -> str(S.desktop_inv_print_cheque)
                        else -> str(S.ah_process)
                    },
                    onClick = { onEvent(InvoicesEvent.ProcessSelected(code)) },
                    variant = if (wire) ButtonVariant.Danger else ButtonVariant.Primary,
                    size = ButtonSize.Small,
                    enabled = state.viewer.canOperateRuns && !cheque && request.busy == null,
                    loading = busy,
                )
            }
        }
        request.rowsFor(code).forEach { invoice ->
            ZillitDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = invoice.displayNumber,
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                ZillitText(
                    text = state.vendors[invoice.vendorId]?.name?.ifBlank { null } ?: str(S.desktop_unknown),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = InvoiceFormat.money(invoice.grossAmount, invoice.currency.ifBlank { state.projectCurrency }),
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Turning a run down — the web's `RejectRunModal` (`:881-930`). The reason is
 * mandatory: "Rejection reason is required" stands under the box until one
 * is typed, and the button waits for it.
 */
@Composable
internal fun RejectRunSheet(request: RunRejection, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_reject_named, request.run.number.ifBlank { str(S.desktop_payment_run_lower) }),
        subtitle = str(S.desktop_inv_reject_run_mandatory),
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
                text = str(if (request.busy) S.ah_run_detail_btn_rejecting else S.ah_run_detail_btn_reject_confirm),
                onClick = { onEvent(InvoicesEvent.ConfirmRejectRun) },
                variant = ButtonVariant.Danger,
                enabled = request.isReady && !request.busy,
                loading = request.busy,
            )
        },
    ) {
        ZillitTextField(
            value = request.reason,
            onValueChange = { onEvent(InvoicesEvent.RejectRunReasonChanged(it)) },
            placeholder = str(S.desktop_inv_rejection_reason_placeholder),
            errorText = if (request.reason.isBlank()) str(S.desktop_inv_rejection_reason_required) else null,
            singleLine = false,
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Handing entry work to someone else, with the reason on the record. */
@Composable
internal fun AssignSheet(state: InvoicesUiState, request: AssignRequest, onEvent: (InvoicesEvent) -> Unit) {
    // From the coding screen it names the invoice (`EntryDetailModal.jsx:2371`); from the queue, the count.
    val fromLedger = state.ledger?.invoice?.takeIf { request.invoiceIds == listOf(it.id) }
    ZillitDialogShell(
        title = when {
            fromLedger != null -> str(S.desktop_inv_assign_invoice_no, fromLedger.displayNumber)
            request.invoiceIds.size == 1 -> str(S.desktop_inv_assign_one_invoice)
            else -> str(S.desktop_inv_assign_n_invoices, request.invoiceIds.size)
        },
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
    val current = assignRows(state, request)
        .mapNotNull { state.assigneeName(it) }
        .distinct()
    if (current.isNotEmpty()) MutedLine(str(S.desktop_currently_assigned_to, current.joinToString(", ")))
    if (state.assignees.isEmpty()) {
        MutedLine(str(S.desktop_inv_nobody_from_accounts))
    }
    AssignFieldLabel(str(S.desktop_assign_to))
    ZillitSelect(
        value = state.assignees.firstOrNull { it.id == request.userId },
        options = listOf<InvoiceAssignee?>(null) + state.assignees.filter { it.id !in alreadyOn(state, request) },
        onSelect = { picked -> onEvent(InvoicesEvent.EditAssign(request.copy(userId = picked?.id.orEmpty()))) },
        label = { it?.label ?: str(S.desktop_select_team_member) },
        enabled = !request.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    AssignFieldLabel(str(S.desktop_inv_reason_for_assignment))
    ZillitSelect(
        value = request.reason,
        options = listOf<AssignmentReason?>(null) + AssignmentReason.entries,
        onSelect = { reason -> onEvent(InvoicesEvent.EditAssign(request.copy(reason = reason))) },
        label = { it?.label ?: str(S.desktop_select_a_reason) },
        enabled = !request.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (request.reason?.needsNotes() == true) {
        // 500 characters at most, counted as typed (`maxLength={500}`, `EntryPage.jsx:908-909`).
        ZillitTextField(
            value = request.notes,
            onValueChange = { onEvent(InvoicesEvent.EditAssign(request.copy(notes = it.take(ASSIGN_REASON_MAX)))) },
            label = str(S.desktop_inv_custom_reason),
            placeholder = str(S.desktop_inv_enter_reason_for_assignment),
            singleLine = false,
            maxLength = ASSIGN_REASON_MAX,
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AssignFieldLabel(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
}

/** The invoices being handed on — the open coding screen's own copy first, it is the freshest. */
private fun assignRows(state: InvoicesUiState, request: AssignRequest) =
    (listOfNotNull(state.ledger?.invoice) + state.invoices).distinctBy { it.id }.filter { it.id in request.invoiceIds }

/** Whoever already holds one of these invoices is not offered again. */
private fun alreadyOn(state: InvoicesUiState, request: AssignRequest): Set<String> =
    assignRows(state, request).map { it.assignedTo }.filter { it.isNotBlank() }.toSet()

/** The custom reason's cap. */
private const val ASSIGN_REASON_MAX = 500

private val PROCESS_SHEET_WIDTH = 560.dp
