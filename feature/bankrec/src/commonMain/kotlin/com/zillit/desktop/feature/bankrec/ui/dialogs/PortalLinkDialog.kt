package com.zillit.desktop.feature.bankrec.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.PortalExpiry
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.PortalNotify
import com.zillit.desktop.feature.bankrec.domain.PortalOrgType
import com.zillit.desktop.feature.bankrec.domain.PortalPermission
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons

/**
 * Generating, editing or re-sharing a read-only link.
 *
 * What the recipient may see is chosen here, section by section, and the two
 * sections that name people — fraud alerts and transaction detail — start off.
 * The recipient opens the link with a code emailed to the address given, so
 * the address is not a formality.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // A form: every field the web's link dialog asks for, in its order.
@Composable
internal fun PortalLinkDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val draft = rememberLast(state.portal.draft) ?: return
    val problem = draft.problem(state.periods.map { it.id })
    fun edit(next: PortalLinkDraft) = onEvent(BankRecEvent.EditPortalDraft(next))

    ZillitDialogShell(
        title = if (draft.isEdit) "Edit Portal Link" else "Generate Guarantor / Broadcaster Portal Link",
        onDismiss = { if (!draft.saving) onEvent(BankRecEvent.DismissPortalDraft) },
        visible = state.portal.draft != null,
        icon = BankRecIcons.Share,
        width = 600.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissPortalDraft) },
                variant = ButtonVariant.Tertiary,
                enabled = !draft.saving,
            )
            ZillitTooltip(problem.orEmpty()) {
                ZillitButton(
                    text = when {
                        draft.saving && draft.isEdit -> "Saving…"
                        draft.saving -> "Generating…"
                        draft.isEdit -> "Save Changes"
                        else -> "Generate Link & Send Email"
                    },
                    onClick = { onEvent(BankRecEvent.SavePortalLink) },
                    leadingIcon = BankRecIcons.Share,
                    loading = draft.saving,
                    enabled = problem == null && !draft.saving,
                )
            }
        },
    ) {
        FieldPair(
            left = {
                Field("Recipient Name") {
                    ZillitTextField(
                        value = draft.recipientName,
                        onValueChange = { edit(draft.copy(recipientName = it)) },
                        placeholder = "e.g. James Whitford",
                    )
                }
            },
            right = {
                Field("Email Address") {
                    ZillitTextField(
                        value = draft.recipientEmail,
                        onValueChange = { edit(draft.copy(recipientEmail = it.trim())) },
                        placeholder = "name@organisation.com",
                    )
                }
            },
        )
        FieldPair(
            left = {
                Field("Organisation Type") {
                    ZillitSelect(
                        value = draft.orgType,
                        options = PortalOrgType.entries,
                        onSelect = { edit(draft.copy(orgType = it)) },
                        label = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            right = {
                Field("Period") {
                    ZillitSelect(
                        value = draft.periodId,
                        options = listOf("") + state.periods.map { it.id },
                        onSelect = { edit(draft.copy(periodId = it)) },
                        label = { id ->
                            state.period(id)?.let { period ->
                                val status = if (period.isOpen) " (in progress)" else " (complete)"
                                BankRecFormat.periodLabel(period) + status
                            } ?: "Select period…"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
        if (state.bankAccounts.size > 1) {
            Field("Bank Account") {
                ZillitSelect(
                    value = draft.bankAccountId,
                    options = listOf("") + state.bankAccounts.map { it.id },
                    onSelect = { edit(draft.copy(bankAccountId = it)) },
                    label = { id ->
                        if (id.isBlank()) "All accounts" else state.account(id)?.displayName?.ifBlank { null } ?: "Bank"
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Field("What can they see?") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PortalPermission.entries.forEach { permission ->
                    ZillitCheckbox(
                        checked = permission in draft.permissions,
                        onCheckedChange = { on ->
                            val next = if (on) draft.permissions + permission else draft.permissions - permission
                            edit(draft.copy(permissions = next))
                        },
                        label = permission.label,
                    )
                }
            }
        }
        FieldPair(
            left = {
                Field("Link Expiry") {
                    ZillitSelect(
                        value = draft.expiry,
                        options = PortalExpiry.entries,
                        onSelect = { edit(draft.copy(expiry = it)) },
                        label = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            right = {
                Field("Notify me when viewed") {
                    ZillitSelect(
                        value = draft.notifyOnView,
                        options = PortalNotify.entries,
                        onSelect = { edit(draft.copy(notifyOnView = it)) },
                        label = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}
