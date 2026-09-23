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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.ui.PoConfirmAction
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoPrompt
import com.zillit.desktop.feature.purchaseorder.ui.PoReassignState
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.mayQuery
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
    // Over the detail and the processing page, which is where it is opened from.
    PoQueryDialog(state, onEvent)
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
        title = order.number.ifBlank { str(S.purchase_order) },
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
            ZillitStatusPill(label = state.statusLabel(order), tone = order.status.tone())
        }
        if (state.isLocked(order)) {
            ZillitNotice(
                text = str(S.desktop_po_period_locked_banner, state.periodLock.lockedThrough),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Lock,
            )
        }
        if (order.isLocalOnly) {
            ZillitNotice(
                text = if (order.local?.failed == true) {
                    str(
                        S.desktop_po_local_send_failed,
                        order.local.error ?: str(S.desktop_the_server_refused_it),
                    )
                } else {
                    str(S.desktop_po_queued_offline_row)
                },
                tone = if (order.local?.failed == true) StatusTone.Rejected else StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }
        DetailTiles(state, order)
        if (order.description.isNotBlank()) {
            ZillitSectionLabel(str(S.description))
            ZillitText(text = order.description, style = ZillitTheme.typography.bodyMedium)
        }
        if (order.rejectionReason?.isNotBlank() == true) {
            ZillitSectionLabel(str(S.ah_float_rejection_reason))
            ZillitText(
                text = order.rejectionReason,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.danger,
            )
        }
        if (order.closureReason?.isNotBlank() == true) {
            ZillitSectionLabel(str(S.desktop_po_closure_reason))
            ZillitText(text = order.closureReason, style = ZillitTheme.typography.bodyMedium)
        }
        LineItems(order)
        Approvals(state, order)
        Attachments(state, order, onEvent)
        History(state)
    }
}

/** The grid of facts: company, episode, dates, coding, the vendor email stamp. */
@Composable
private fun DetailTiles(state: PoUiState, order: PurchaseOrder) {
    ZillitDivider()
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Tile(str(S.ah_lbl_created_by), state.personName(order.raisedBy))
        Tile(str(S.ah_row_eff_date_upper), EpochDate.date(order.effectiveDate))
        Tile(str(S.delivery_date), EpochDate.date(order.deliveryDate))
        Tile(str(S.ah_account_label), order.nominalCode.orEmpty())
        Tile(str(S.episode), order.episode.orEmpty())
        Tile(str(S.company), state.companies.firstOrNull { it.id == order.companyId }?.name.orEmpty())
        Tile(str(S.delivery_address), order.deliveryAddress.orEmpty())
        Tile(str(S.ah_lbl_gross_total), Money.format(order.gross, order.currency))
        Tile(str(S.ah_lbl_vat_tax), Money.format(order.vatAmount, order.currency))
        Tile(str(S.ah_lbl_relieved), if (order.paidAmount > 0) Money.format(order.paidAmount, order.currency) else "")
        // Approval does not email anyone, so this stamp is the only evidence
        // that the vendor has the order at all.
        Tile(
            str(S.desktop_po_emailed_to_vendor),
            if (order.emailed) {
                EpochDate.dateTime(order.emailAt) +
                    state.personName(order.emailBy).let { name -> if (name.isBlank()) "" else " · $name" }
            } else {
                ""
            },
        )
        Tile(str(S.desktop_po_last_updated_by), state.personName(order.updatedBy))
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
        ZillitSectionLabel(str(S.ah_line_items))
        ZillitText(
            text = str(S.ah_no_line_items),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    ZillitDivider()
    ZillitSectionLabel(str(S.ah_line_items))
    order.lines.forEach { line ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = (if (line.isSplitChild) "↳ " else "") + line.description.ifBlank { str(S.desktop_line) },
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                ZillitText(
                    text = listOfNotNull(
                        str(S.desktop_po_qty_value, line.quantity.trimmed().ifBlank { "1" }),
                        str(S.desktop_po_unit_value, Money.format(line.unitPrice, order.currency)),
                        line.nominalCode?.takeIf { it.isNotBlank() },
                        line.expenditureType?.takeIf { it.isNotBlank() },
                        line.vatRate?.let { rate -> str(S.desktop_po_tax_rate_value, rate.trimmed()) },
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
            text = str(S.desktop_po_amount_gross),
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = Money.format(order.gross, order.currency), style = ZillitTheme.typography.titleSmall)
    }
}

/**
 * The chain, tier by tier: who approved each tier that has, and the tiers the
 * configuration still has waiting — the server keeps an entry only for a tier
 * that *has* approved (`{ user_id, tier_number, approved_at }`), so the waiting
 * ones come from the tier configuration, never from the order.
 */
@Composable
private fun Approvals(state: PoUiState, order: PurchaseOrder) {
    val total = if (order.status == PoStatus.AwaitingApproval) state.approvalStep(order).totalTiers else 0
    val tiers = (order.approvals.map { it.level } + (1..total)).distinct().sorted()
    if (tiers.isEmpty()) return
    ZillitDivider()
    ZillitSectionLabel(str(S.desktop_po_approval_progress))
    tiers.forEach { tier ->
        val step = order.approvals.firstOrNull { it.level == tier && it.decided }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            val who = step?.let { it.name.ifBlank { state.personName(it.userId) } }.orEmpty()
            ZillitText(
                text = listOfNotNull(
                    "$tier.",
                    who.takeIf { it.isNotBlank() },
                    EpochDate.date(step?.at).ifBlank { null },
                ).joinToString(" "),
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitStatusPill(
                label = step?.decision?.replaceFirstChar { it.uppercase() } ?: str(S.ah_run_detail_tier_waiting),
                tone = if (step != null) StatusTone.Done else StatusTone.Pending,
            )
        }
    }
}

@Composable
private fun Attachments(state: PoUiState, order: PurchaseOrder, onEvent: (PoEvent) -> Unit) {
    if (order.attachments.isEmpty()) return
    ZillitDivider()
    ZillitSectionLabel(str(S.attachments))
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
                text = str(S.ah_view_attachment),
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
    ZillitSectionLabel(str(S.history))
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
    // Nothing that writes is offered on an order in the locked cost-report
    // period — the web hides Edit, Delete, Process and the decision there.
    val locked = state.isLocked(order)
    // The decision belongs to whoever the chain routes this tier to — on any
    // tab, as the web's `vis.canApprove` has it. Gating on the department
    // Approval Queue alone hid it from every accountant who sits on a tier.
    if (!locked && state.approvalStep(order).canApprove) {
        ZillitButton(
            text = str(S.ah_approve),
            onClick = {
                onEvent(
                    PoEvent.Ask(
                        PoPrompt.Confirm(
                            action = PoConfirmAction.Approve,
                            targetId = order.id,
                            title = str(S.desktop_po_approve_this_order),
                            message = str(
                                S.desktop_po_approve_message,
                                Money.format(order.gross, order.currency),
                                state.vendorName(order).ifBlank { str(S.desktop_po_the_vendor) },
                            ),
                        ),
                    ),
                )
            },
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
        ZillitButton(
            text = str(S.ah_reject),
            onClick = {
                onEvent(
                    PoEvent.Ask(
                        PoPrompt.WithReason(
                            action = com.zillit.desktop.feature.purchaseorder.ui.PoReasonAction.Reject,
                            targetId = order.id,
                            title = str(S.desktop_po_reject_this_order),
                            label = str(S.desktop_timecard_reject_label),
                        ),
                    ),
                )
            },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
    }
    if (!locked && PoAccess.canEdit(order, viewer, state.projectSettings.allowAmendAfterApproval)) {
        ZillitButton(
            text = str(S.edit),
            onClick = { onEvent(PoEvent.EditOrder(order.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Edit,
            enabled = !state.busy,
        )
    }
    if (state.mayQuery(order)) {
        ZillitButton(
            text = str(S.ah_query_label),
            onClick = { onEvent(PoEvent.OpenQuery(order.id)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Chat,
        )
    }
    if (!locked && PoAccess.canProcess(order, viewer)) {
        ZillitButton(
            text = str(S.ah_process),
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
            text = str(S.desktop_po_send_to_vendor),
            onClick = { onEvent(PoEvent.SendVendorEmail(order.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Send,
            enabled = !state.busy,
        )
    }
    ZillitButton(
        text = str(S.ah_view_pdf),
        onClick = { onEvent(PoEvent.ViewPdf(order.id)) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Download,
        enabled = !state.busy,
    )
    if (!locked && PoAccess.canDelete(order, viewer)) {
        ZillitButton(
            text = str(S.desktop_po_delete_po),
            onClick = {
                onEvent(
                    PoEvent.Ask(
                        PoPrompt.Confirm(
                            action = PoConfirmAction.Delete,
                            targetId = order.id,
                            title = str(S.desktop_po_delete_this_order),
                            message = str(
                                S.desktop_po_order_will_be_removed,
                                order.number.ifBlank { str(S.desktop_po_this_order_capital) },
                            ),
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
        title = str(S.desktop_po_reassign_target, dialog.label),
        subtitle = str(S.desktop_po_reassign_reason_note),
        onDismiss = { onEvent(PoEvent.DismissReassign) },
        visible = true,
        icon = ZillitIcons.UserPlus,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(PoEvent.DismissReassign) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_po_reassign),
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
                    ?: str(S.desktop_select_team_member)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitSelect(
            value = dialog.reason.ifBlank { null },
            options = listOf(null) + PoReassignState.REASONS,
            onSelect = { onEvent(PoEvent.EditReassign(dialog.copy(reason = it.orEmpty()))) },
            label = { it ?: str(S.desktop_select_a_reason) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (dialog.reason == PoReassignState.OTHER) {
            ZillitTextField(
                value = dialog.customReason,
                onValueChange = { onEvent(PoEvent.EditReassign(dialog.copy(customReason = it))) },
                placeholder = str(S.desktop_po_enter_reassignment_reason),
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
        title = str(S.desktop_workspace_close_window, dialog.number),
        subtitle = str(S.desktop_po_close_subtitle),
        onDismiss = { onEvent(PoEvent.DismissClose) },
        visible = true,
        icon = ZillitIcons.Close,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(PoEvent.DismissClose) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_po_close_po),
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
            label = str(S.reason),
            placeholder = str(S.desktop_po_reason_for_closing),
            singleLine = false,
        )
        ZillitDateField(
            value = EpochDate.isoDate(dialog.date),
            onValueChange = { iso -> onEvent(PoEvent.EditClose(dialog.copy(date = iso.isoDayToUtcMidnight()))) },
            label = str(S.desktop_po_effective_closing_date),
            helperText = str(S.desktop_po_write_off_period_hint),
        )
    }
}

/** Closing off a whole period — every open order in it, one shared date. */
@Composable
private fun PoCloseOffDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val dialog = state.closeOff ?: return
    val count = dialog.ids.size
    ZillitDialogShell(
        title = str(S.desktop_po_close_off_target, dialog.period),
        subtitle = str(
            if (count == 1) S.desktop_po_will_be_closed_one else S.desktop_po_will_be_closed_other,
            count,
        ),
        onDismiss = { onEvent(PoEvent.DismissCloseOff) },
        visible = true,
        icon = ZillitIcons.Calendar,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(PoEvent.DismissCloseOff) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_po_close_off_period),
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
            label = str(S.desktop_po_effective_closing_date),
        )
        ZillitText(
            text = str(S.desktop_po_close_off_warning, dialog.period),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitCheckbox(
            checked = dialog.confirmed,
            onCheckedChange = { onEvent(PoEvent.EditCloseOff(dialog.copy(confirmed = it))) },
            label = str(
                if (count == 1) S.desktop_po_confirm_close_off_one else S.desktop_po_confirm_close_off_other,
                count,
            ),
        )
    }
}

/** The bulk effective-date dialog. */
@Composable
private fun PoBulkDateDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val dialog = state.bulkDate ?: return
    val count = dialog.ids.size
    ZillitDialogShell(
        title = str(
            if (count == 1) S.desktop_po_set_effective_date_one else S.desktop_po_set_effective_date_other,
            count,
        ),
        onDismiss = { onEvent(PoEvent.DismissBulkDate) },
        visible = true,
        icon = ZillitIcons.Calendar,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(PoEvent.DismissBulkDate) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.dm_filter_apply),
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
            label = str(S.ah_lbl_eff_date),
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
        title = if (dialog.id == null) str(S.add_delivery_address) else str(S.edit_delivery_address),
        onDismiss = { onEvent(PoEvent.DismissAddress) },
        visible = true,
        icon = ZillitIcons.Home,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(PoEvent.DismissAddress) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.save),
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
            label = str(S.dd_recipient_name),
            placeholder = str(S.dd_recipient_name),
        )
        ZillitTextField(
            value = address.email,
            onValueChange = { set(address.copy(email = it)) },
            label = str(S.email),
            placeholder = "email@example.com",
            keyboardType = KeyboardType.Email,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = address.phoneCode,
                onValueChange = { set(address.copy(phoneCode = it)) },
                label = str(S.code),
                placeholder = "+44",
                modifier = Modifier.width(PHONE_CODE),
            )
            ZillitTextField(
                value = address.phone,
                onValueChange = { set(address.copy(phone = it)) },
                label = str(S.phone),
                placeholder = "1753 651700",
                keyboardType = KeyboardType.Phone,
                modifier = Modifier.weight(1f),
            )
        }
        ZillitTextField(
            value = address.line1,
            onValueChange = { set(address.copy(line1 = it)) },
            label = str(S.address_line_1),
            placeholder = str(S.ah_street_hint),
        )
        ZillitTextField(
            value = address.line2,
            onValueChange = { set(address.copy(line2 = it)) },
            label = str(S.address_line_2),
            placeholder = str(S.ah_suite_hint),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = address.city,
                onValueChange = { set(address.copy(city = it)) },
                label = str(S.city),
                placeholder = str(S.city),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = address.state,
                onValueChange = { set(address.copy(state = it)) },
                label = str(S.ah_lbl_state_county_row),
                placeholder = str(S.ah_lbl_state_county_row),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = address.postalCode,
                onValueChange = { set(address.copy(postalCode = it)) },
                label = str(S.desktop_postal_zip_code),
                placeholder = str(S.ah_lbl_postal_code),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = address.country,
                onValueChange = { set(address.copy(country = it)) },
                label = str(S.ah_lbl_country),
                placeholder = str(S.ah_select_country),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * An order's query thread — the web's `QueryPanel`: the messages oldest first,
 * each with who asked and when, and one line to add to it. The first message
 * opens the thread.
 */
@Suppress("LongMethod") // One thread: header, messages, composer.
@Composable
private fun PoQueryDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val query = state.query ?: return
    ZillitDialogShell(
        title = str(S.ah_query_label),
        subtitle = query.title,
        onDismiss = { onEvent(PoEvent.CloseQuery) },
        visible = true,
        icon = ZillitIcons.Chat,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(PoEvent.CloseQuery) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.send),
                onClick = { onEvent(PoEvent.SendQuery) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
                loading = query.sending,
                enabled = query.draft.isNotBlank() && !query.sending,
            )
        },
    ) {
        val messages = query.thread?.messages.orEmpty()
        when {
            query.loading -> ZillitNotice(
                text = str(S.ah_loading),
                tone = StatusTone.Progress,
                icon = ZillitIcons.Clock,
            )

            messages.isEmpty() -> ZillitText(
                text = str(S.ah_no_queries_yet),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )

            else -> messages.forEach { message ->
                val mine = message.by == state.viewer.userId
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                ) {
                    ZillitText(
                        text = listOfNotNull(
                            state.personName(message.by).ifBlank { null },
                            EpochDate.dateTime(message.at).ifBlank { null },
                        ).joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitText(text = message.text, style = ZillitTheme.typography.bodyMedium)
                }
            }
        }
        ZillitTextField(
            value = query.draft,
            onValueChange = { onEvent(PoEvent.EditQuery(it)) },
            placeholder = str(S.type_a_message),
            singleLine = false,
        )
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
                    text = str(S.cancel),
                    onClick = { onEvent(PoEvent.DismissPrompt) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = str(S.confirm),
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
                    text = str(S.cancel),
                    onClick = { onEvent(PoEvent.DismissPrompt) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = str(S.confirm),
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
    ZillitText(text = str(S.desktop_po_preview), style = ZillitTheme.typography.titleSmall)
    ZillitText(
        text = order.number.ifBlank { str(S.desktop_po_not_yet_numbered) },
        style = ZillitTheme.typography.numeric,
    )
    ZillitDivider()
    Tile(str(S.ah_lbl_vendor), state.vendorName(order))
    Tile(str(S.department), state.departmentName(order.departmentId))
    Tile(str(S.description), order.description)
    Tile(str(S.ah_row_eff_date_upper), EpochDate.date(order.effectiveDate))
    Tile(str(S.ah_account_label), order.nominalCode.orEmpty())
    ZillitDivider()
    order.lines.forEach { line ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = line.description.ifBlank { str(S.desktop_line) },
                style = ZillitTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitText(text = Money.format(line.total, order.currency), style = ZillitTheme.typography.numeric)
        }
    }
    ZillitDivider()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = str(S.ah_lbl_gross_total),
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = Money.format(order.gross, order.currency), style = ZillitTheme.typography.titleSmall)
    }
}

private val DETAIL_WIDTH = 720.dp
private val TILE_LABEL = 170.dp
private val PHONE_CODE = 90.dp
