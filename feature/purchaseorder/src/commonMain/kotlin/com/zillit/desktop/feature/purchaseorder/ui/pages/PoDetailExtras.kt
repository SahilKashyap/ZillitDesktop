package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.runtime.Composable
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoFormFields
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState

/**
 * "No approver configured for this department" — the accounts console's detail
 * warning on a pending order whose department has nobody on tier 1
 * (`PODetailModal.jsx`, shown only where `navigate` is passed, which is the
 * console alone), with the way to the Approvers screen.
 *
 * Read off the resolved chain: a chain with any tier at all has a tier 1,
 * because an empty tier is skipped and the rest renumbered.
 */
@Composable
internal fun NoApproverWarning(state: PoUiState, order: PurchaseOrder, onEvent: (PoEvent) -> Unit) {
    val departmentId = order.departmentId
    if (!state.viewer.isAccountant || order.status != PoStatus.AwaitingApproval || departmentId.isNullOrBlank()) return
    if (state.tiers.resolve(departmentId, order.gross).isNotEmpty()) return
    ZillitNotice(
        text = str(S.desktop_po_no_approver_for_department),
        tone = StatusTone.Pending,
        icon = ZillitIcons.Warning,
        action = {
            ZillitButton(
                text = str(S.desktop_po_set_approver),
                onClick = { onEvent(PoEvent.OpenApprovers) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        },
    )
}

/**
 * The order's extra fields as `name to value` rows: the template's own fields
 * under the name this production gave each, then any answer the template no
 * longer lists under the key it was saved with. Blank answers are left out, as
 * the web leaves them.
 */
internal fun customFieldRows(state: PoUiState, order: PurchaseOrder): List<Pair<String, String>> {
    val names = state.formLayout.custom(PoFormFields.DETAILS).associate { it.label to it.name }
    val answers = order.customFields.flatMap { group -> group.fields }
        .filter { it.value.isNotBlank() }
        .map { field -> (names[field.label.ifBlank { field.name }] ?: names[field.name] ?: field.name) to field.value }
    // The template's order first, then whatever else was saved.
    val templateOrder = names.values.toList()
    return answers.sortedBy { (name, _) -> templateOrder.indexOf(name).takeIf { it >= 0 } ?: templateOrder.size }
}
