package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.ui.PoConfirmAction
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoPrompt
import com.zillit.desktop.feature.purchaseorder.ui.PoReassignState
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.domain.isoDayToUtcMidnight

/** Every dialog this tool opens, hosted once so only one can be on screen. */
@Composable
internal fun PoDialogs(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    PoDetailDialog(state, onEvent)
    PoReassignDialog(state, onEvent)
    PoCloseDialog(state, onEvent)
    PoCloseOffDialog(state, onEvent)
    PoBulkDateDialog(state, onEvent)
    PoAddressDialog(state, onEvent)
    PoPromptDialog(state, onEvent)
}

/**
 * One order, in full — the web's `PODetailModal`.
 *
 * A read surface with the decisions on it: approve, reject, edit, delete,
 * process, send to the vendor, view the PDF. Each is gated by the same
 * predicate the server uses, so a button that appears is one that will work.
 */
@Suppress("LongMethod") // One order top to bottom; the order is the reading order.
@Composable
private fun PoDetailDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val order = state.detail ?: return
    // The form and the processing page take over the page; the dialog behind
    // them would be a second copy of the same order.
    if (state.form != null || state.entry != null) return
    ZillitDialogShell(
        title = order.number.ifBlank { "Purchase order" },
        subtitle = listOfNotNull(
            state.vendorName(order).takeIf { it.isNotBlank() },
            state.departmentName(order.departmentId).takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { null },
        onDismiss = { onEvent(PoEvent.CloseOrder) },
        visible = true,
        icon = ZillitIcons.File,
        width = DETAIL_WIDTH,
        actions = { DetailActions(state, order, onEvent) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = Money.format(order.gross, order.currency),
                style = ZillitTheme.typography.displayLarge,
                modifier = Modifier.weight(1f),
            )
            ZillitStatusPill(label = order.statusLabel, tone = order.status.tone())
        }
        if (order.isLocalOnly) {
            ZillitNotice(
                text = if (order.local?.failed == true) {
                    "This order could not be sent: ${order.local.error ?: "the server refused it"}."
                } else {
                    "Saved on this computer — it will be raised when you are back online."
                },
                tone = if (order.local?.failed == true) StatusTone.Rejected else StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }
        DetailTiles(state, order)
        if (order.description.isNotBlank()) {
            ZillitSectionLabel("Description")
            ZillitText(text = order.description, style = ZillitTheme.typography.bodyMedium)
        }
        if (order.rejectionReason?.isNotBlank() == true) {
            ZillitSectionLabel("Rejection Reason")
            ZillitText(
                text = order.rejectionReason,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.danger,
            )
        }
        if (order.closureReason?.isNotBlank() == true) {
            ZillitSectionLabel("Closure reason")
            ZillitText(text = order.closureReason, style = ZillitTheme.typography.bodyMedium)
        }
        LineItems(order)
        Approvals(order)
        Attachments(state, order, onEvent)
        History(state)
    }
}

/** The grid of facts: company, episode, dates, coding, the vendor email stamp. */
@Composable
private fun DetailTiles(state: PoUiState, order: PurchaseOrder) {
    ZillitDivider()
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Tile("Created By", state.personName(order.raisedBy))
        Tile("Eff. Date", EpochDate.date(order.effectiveDate))
        Tile("Delivery Date", EpochDate.date(order.deliveryDate))
        Tile("Account", order.nominalCode.orEmpty())
        Tile("Episode", order.episode.orEmpty())
        Tile("Company", state.companies.firstOrNull { it.id == order.companyId }?.name.orEmpty())
        Tile("Delivery Address", order.deliveryAddress.orEmpty())
        Tile("Gross Total", Money.format(order.gross, order.currency))
        Tile("Tax", Money.format(order.vatAmount, order.currency))
        Tile("Relieved", if (order.paidAmount > 0) Money.format(order.paidAmount, order.currency) else "")
        // Approval does not email anyone, so this stamp is the only evidence
        // that the vendor has the order at all.
        Tile(
            "Emailed To Vendor",
            if (order.emailed) {
                EpochDate.dateTime(order.emailAt) +
                    state.personName(order.emailBy).let { name -> if (name.isBlank()) "" else " · $name" }
            } else {
                ""
            },
        )
        Tile("Last Updated By", state.personName(order.updatedBy))
    }
}

/** A fact, or nothing: an empty row is noise on a dialog this long. */
@Composable
private fun Tile(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.width(TILE_LABEL),
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LineItems(order: PurchaseOrder) {
    if (order.lines.isEmpty()) {
        ZillitSectionLabel("Line Items")
        ZillitText(
            text = "No line items",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    ZillitDivider()
    ZillitSectionLabel("Line Items")
    order.lines.forEach { line ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = (if (line.isSplitChild) "↳ " else "") + line.description.ifBlank { "Line" },
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                ZillitText(
                    text = listOfNotNull(
                        "Qty ${line.quantity.trimmed().ifBlank { "1" }}",
                        "Unit ${Money.format(line.unitPrice, order.currency)}",
                        line.nominalCode?.takeIf { it.isNotBlank() },
                        line.expenditureType?.takeIf { it.isNotBlank() },
                        line.vatRate?.let { rate -> "Tax ${rate.trimmed()}%" },
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
            ZillitText(text = Money.format(line.total, order.currency), style = ZillitTheme.typography.numeric)
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = "Amount (Gross)",
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = Money.format(order.gross, order.currency), style = ZillitTheme.typography.titleSmall)
    }
}

@Composable
private fun Approvals(order: PurchaseOrder) {
    if (order.approvals.isEmpty()) return
    ZillitDivider()
    ZillitSectionLabel("Approval Progress")
    order.approvals.sortedBy { it.level }.forEach { step ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = "${step.level}. ${step.name.ifBlank { "No approver configured for this department" }}",
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitStatusPill(
                label = step.decision?.replaceFirstChar { it.uppercase() } ?: "Waiting",
                tone = if (step.decided) StatusTone.Done else StatusTone.Pending,
            )
        }
    }
}

@Composable
private fun Attachments(state: PoUiState, order: PurchaseOrder, onEvent: (PoEvent) -> Unit) {
    if (order.attachments.isEmpty()) return
    ZillitDivider()
    ZillitSectionLabel("Attachments")
    order.attachments.forEach { file ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = file.displayName,
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitButton(
                text = "View Attachment",
                onClick = { onEvent(PoEvent.OpenAttachment(file)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    }
}

@Composable
private fun History(state: PoUiState) {
    if (state.history.isEmpty()) return
    ZillitDivider()
    ZillitSectionLabel("History")
    state.history.forEach { entry ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = entry.action.replaceFirstChar { it.uppercase() },
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                entry.note?.takeIf { it.isNotBlank() }?.let { note ->
                    ZillitText(
                        text = note,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
            ZillitText(
                text = EpochDate.dateTime(entry.at),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

@Suppress("LongMethod") // A rights table; flattening it is what makes it readable.
@Composable
private fun DetailActions(state: PoUiState, order: PurchaseOrder, onEvent: (PoEvent) -> Unit) {
    if (order.isLocalOnly) return
    val viewer = state.viewer
    // The decision belongs to whoever the chain routed it to, which is what the
    // approval queue is; offering Approve everywhere would offer it to people
    // whose click the server refuses.
    if (state.destination == com.zillit.desktop.feature.purchaseorder.ui.PoDestination.ApprovalQueue &&
        order.status == PoStatus.AwaitingApproval
    ) {
        ZillitButton(
            text = "Approve",
            onClick = {
                onEvent(
                    PoEvent.Ask(
                        PoPrompt.Confirm(
                            action = PoConfirmAction.Approve,
                            targetId = order.id,
                            title = "Approve this order",
                            message = "${Money.format(order.gross, order.currency)} is committed with " +
                                state.vendorName(order).ifBlank { "the vendor" } + ".",
                        ),
                    ),
                )
            },
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
        ZillitButton(
            text = "Reject",
            onClick = {
                onEvent(
                    PoEvent.Ask(
                        PoPrompt.WithReason(
                            action = com.zillit.desktop.feature.purchaseorder.ui.PoReasonAction.Reject,
                            targetId = order.id,
                            title = "Reject this order",
                            label = "Why it is being refused",
                        ),
                    ),
                )
            },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
    }
    if (PoAccess.canEdit(order, viewer, state.projectSettings.allowAmendAfterApproval)) {
        ZillitButton(
            text = "Edit",
            onClick = { onEvent(PoEvent.EditOrder(order.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Edit,
            enabled = !state.busy,
        )
    }
    if (PoAccess.canProcess(order, viewer)) {
        ZillitButton(
            text = "Process",
            onClick = { onEvent(PoEvent.ProcessOrder(order.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Ledger,
            enabled = !state.busy,
        )
    }
    // One-shot from here: this is a read surface, so a second send could only
    // duplicate the same document. The processing page allows a resend.
    if (PoAccess.canSendVendorEmail(order, viewer)) {
        ZillitButton(
            text = "Send to Vendor",
            onClick = { onEvent(PoEvent.SendVendorEmail(order.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Send,
            enabled = !state.busy,
        )
    }
    ZillitButton(
        text = "View PDF",
        onClick = { onEvent(PoEvent.ViewPdf(order.id)) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Download,
        enabled = !state.busy,
    )
    if (PoAccess.canDelete(order, viewer)) {
        ZillitButton(
            text = "Delete PO",
            onClick = {
                onEvent(
                    PoEvent.Ask(
                        PoPrompt.Confirm(
                            action = PoConfirmAction.Delete,
                            targetId = order.id,
                            title = "Delete this order",
                            message = "${order.number.ifBlank { "This order" }} will be removed. " +
                                "This action cannot be undone.",
                            destructive = true,
                        ),
                    ),
                )
            },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
    }
}

/** The Reassign dialog — one order, or a whole selection. */
@Composable
private fun PoReassignDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val dialog = state.reassign ?: return
    ZillitDialogShell(
        title = "Reassign ${dialog.label}",
        subtitle = "The reason goes on the record, so the hand-off can be traced.",
        onDismiss = { onEvent(PoEvent.DismissReassign) },
        visible = true,
        icon = ZillitIcons.UserPlus,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(PoEvent.DismissReassign) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Reassign",
                onClick = { onEvent(PoEvent.ConfirmReassign) },
                size = ButtonSize.Small,
                loading = dialog.saving,
                enabled = !dialog.saving,
            )
        },
    ) {
        ZillitSelect(
            value = dialog.userId,
            options = listOf(null) + state.team.map { it.id },
            onSelect = { onEvent(PoEvent.EditReassign(dialog.copy(userId = it))) },
            label = { id ->
                id?.let { key -> state.team.firstOrNull { it.id == key }?.let(state::memberLabel) ?: "" }
                    ?: "Select team member..."
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitSelect(
            value = dialog.reason.ifBlank { null },
            options = listOf(null) + PoReassignState.REASONS,
            onSelect = { onEvent(PoEvent.EditReassign(dialog.copy(reason = it.orEmpty()))) },
            label = { it ?: "Select a reason..." },
            modifier = Modifier.fillMaxWidth(),
        )
        if (dialog.reason == PoReassignState.OTHER) {
            ZillitTextField(
                value = dialog.customReason,
                onValueChange = { onEvent(PoEvent.EditReassign(dialog.copy(customReason = it))) },
                placeholder = "Enter reason for reassignment...",
                singleLine = false,
            )
        }
    }
}

/** Closing one order: a reason and the period it lands in. */
@Composable
private fun PoCloseDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val dialog = state.closePo ?: return
    ZillitDialogShell(
        title = "Close ${dialog.number}",
        subtitle = "Closing releases the remaining commitment into ETC. No further invoices can be matched.",
        onDismiss = { onEvent(PoEvent.DismissClose) },
        visible = true,
        icon = ZillitIcons.Close,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(PoEvent.DismissClose) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Close PO",
                onClick = { onEvent(PoEvent.ConfirmClose) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                loading = dialog.saving,
                enabled = !dialog.saving,
            )
        },
    ) {
        ZillitTextField(
            value = dialog.reason,
            onValueChange = { onEvent(PoEvent.EditClose(dialog.copy(reason = it))) },
            label = "Reason",
            placeholder = "Reason for closing (optional)...",
            singleLine = false,
        )
        ZillitDateField(
            value = EpochDate.isoDate(dialog.date),
            onValueChange = { iso -> onEvent(PoEvent.EditClose(dialog.copy(date = iso.isoDayToUtcMidnight()))) },
            label = "Effective Closing Date",
            helperText = "The period the write-off lands in. Left empty, the server decides.",
        )
    }
}

/** Closing off a whole period — every open order in it, one shared date. */
@Composable
private fun PoCloseOffDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val dialog = state.closeOff ?: return
    val count = dialog.ids.size
    ZillitDialogShell(
        title = "Close Off ${dialog.period}",
        subtitle = "$count PO${if (count == 1) "" else "s"} will be closed",
        onDismiss = { onEvent(PoEvent.DismissCloseOff) },
        visible = true,
        icon = ZillitIcons.Calendar,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(PoEvent.DismissCloseOff) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Close Off Period",
                onClick = { onEvent(PoEvent.ConfirmCloseOff) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                loading = dialog.saving,
                enabled = dialog.confirmed && !dialog.saving,
            )
        },
    ) {
        ZillitDateField(
            value = EpochDate.isoDate(dialog.date),
            onValueChange = { iso -> onEvent(PoEvent.EditCloseOff(dialog.copy(date = iso.isoDayToUtcMidnight()))) },
            label = "Effective Closing Date",
        )
        ZillitText(
            text = "Closing off ${dialog.period} will release any remaining committed amounts from committed " +
                "spend into ETC. No further invoices can be matched.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitCheckbox(
            checked = dialog.confirmed,
            onCheckedChange = { onEvent(PoEvent.EditCloseOff(dialog.copy(confirmed = it))) },
            label = "I confirm I want to close off $count PO${if (count == 1) "" else "s"}",
        )
    }
}

/** The bulk effective-date dialog. */
@Composable
private fun PoBulkDateDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val dialog = state.bulkDate ?: return
    val count = dialog.ids.size
    ZillitDialogShell(
        title = "Set Effective Date — $count PO${if (count == 1) "" else "s"}",
        onDismiss = { onEvent(PoEvent.DismissBulkDate) },
        visible = true,
        icon = ZillitIcons.Calendar,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(PoEvent.DismissBulkDate) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Apply",
                onClick = { onEvent(PoEvent.ConfirmBulkDate) },
                size = ButtonSize.Small,
                loading = dialog.saving,
                enabled = dialog.date != null && !dialog.saving,
            )
        },
    ) {
        ZillitDateField(
            value = EpochDate.isoDate(dialog.date),
            onValueChange = { iso -> onEvent(PoEvent.EditBulkDate(dialog.copy(date = iso.isoDayToUtcMidnight()))) },
            label = "Effective Date",
        )
    }
}

/** Adding or editing a saved delivery address. */
@Suppress("LongMethod") // Nine address fields.
@Composable
private fun PoAddressDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val dialog = state.addressForm ?: return
    val address = dialog.address
    val set = { next: PoAddress -> onEvent(PoEvent.EditAddress(next)) }
    ZillitDialogShell(
        title = if (dialog.id == null) "Add Delivery Address" else "Edit Delivery Address",
        onDismiss = { onEvent(PoEvent.DismissAddress) },
        visible = true,
        icon = ZillitIcons.Home,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(PoEvent.DismissAddress) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Save",
                onClick = { onEvent(PoEvent.SaveAddress) },
                size = ButtonSize.Small,
                loading = dialog.saving,
                enabled = !dialog.saving,
            )
        },
    ) {
        dialog.problem?.let { problem ->
            ZillitNotice(text = problem, tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
        }
        ZillitTextField(
            value = address.name,
            onValueChange = { set(address.copy(name = it)) },
            label = "Recipient Name",
            placeholder = "Recipient name…",
        )
        ZillitTextField(
            value = address.email,
            onValueChange = { set(address.copy(email = it)) },
            label = "Email",
            placeholder = "email@example.com",
            keyboardType = KeyboardType.Email,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = address.phoneCode,
                onValueChange = { set(address.copy(phoneCode = it)) },
                label = "Code",
                placeholder = "+44",
                modifier = Modifier.width(PHONE_CODE),
            )
            ZillitTextField(
                value = address.phone,
                onValueChange = { set(address.copy(phone = it)) },
                label = "Phone",
                placeholder = "1753 651700",
                keyboardType = KeyboardType.Phone,
                modifier = Modifier.weight(1f),
            )
        }
        ZillitTextField(
            value = address.line1,
            onValueChange = { set(address.copy(line1 = it)) },
            label = "Address Line 1",
            placeholder = "Street address…",
        )
        ZillitTextField(
            value = address.line2,
            onValueChange = { set(address.copy(line2 = it)) },
            label = "Address Line 2",
            placeholder = "Suite, unit, building…",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = address.city,
                onValueChange = { set(address.copy(city = it)) },
                label = "City",
                placeholder = "City…",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = address.state,
                onValueChange = { set(address.copy(state = it)) },
                label = "State / County",
                placeholder = "State / County…",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = address.postalCode,
                onValueChange = { set(address.copy(postalCode = it)) },
                label = "Postal / Zip Code",
                placeholder = "Postal code…",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = address.country,
                onValueChange = { set(address.copy(country = it)) },
                label = "Country",
                placeholder = "Select country…",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The confirm-or-reason dialog every destructive action goes through. */
@Composable
internal fun PoPromptDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    when (val prompt = state.prompt) {
        null -> Unit
        is PoPrompt.Confirm -> ZillitDialogShell(
            title = prompt.title,
            onDismiss = { onEvent(PoEvent.DismissPrompt) },
            visible = true,
            icon = if (prompt.destructive) ZillitIcons.Warning else ZillitIcons.Info,
            actions = {
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(PoEvent.DismissPrompt) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = "Confirm",
                    onClick = { onEvent(PoEvent.ConfirmPrompt) },
                    variant = if (prompt.destructive) ButtonVariant.Danger else ButtonVariant.Primary,
                    size = ButtonSize.Small,
                    loading = state.busy,
                    enabled = !state.busy,
                )
            },
        ) {
            ZillitText(text = prompt.message, style = ZillitTheme.typography.bodyMedium)
        }

        is PoPrompt.WithReason -> ZillitDialogShell(
            title = prompt.title,
            onDismiss = { onEvent(PoEvent.DismissPrompt) },
            visible = true,
            icon = ZillitIcons.Edit,
            actions = {
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(PoEvent.DismissPrompt) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = "Confirm",
                    onClick = { onEvent(PoEvent.ConfirmPrompt) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    loading = state.busy,
                    enabled = prompt.reason.isNotBlank() && !state.busy,
                )
            },
        ) {
            ZillitTextField(
                value = prompt.reason,
                onValueChange = { onEvent(PoEvent.UpdatePrompt(prompt.copy(reason = it))) },
                label = prompt.label,
                singleLine = false,
            )
        }
    }
}

/**
 * The order's own document, beside the coding on the processing page.
 *
 * Read-only on purpose: the whole reason the panel is there is to check the
 * coding against what was agreed, and an editable copy of it would invite the
 * two to be reconciled by changing the wrong one.
 */
@Composable
internal fun PoDocumentPanel(state: PoUiState, order: PurchaseOrder) {
    ZillitText(text = "PO Preview", style = ZillitTheme.typography.titleSmall)
    ZillitText(
        text = order.number.ifBlank { "Not yet numbered" },
        style = ZillitTheme.typography.numeric,
    )
    ZillitDivider()
    Tile("Vendor", state.vendorName(order))
    Tile("Department", state.departmentName(order.departmentId))
    Tile("Description", order.description)
    Tile("Eff. Date", EpochDate.date(order.effectiveDate))
    Tile("Account", order.nominalCode.orEmpty())
    ZillitDivider()
    order.lines.forEach { line ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = line.description.ifBlank { "Line" },
                style = ZillitTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitText(text = Money.format(line.total, order.currency), style = ZillitTheme.typography.numeric)
        }
    }
    ZillitDivider()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(text = "Gross Total", style = ZillitTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        ZillitText(text = Money.format(order.gross, order.currency), style = ZillitTheme.typography.titleSmall)
    }
}

private val DETAIL_WIDTH = 720.dp
private val TILE_LABEL = 170.dp
private val PHONE_CODE = 90.dp
