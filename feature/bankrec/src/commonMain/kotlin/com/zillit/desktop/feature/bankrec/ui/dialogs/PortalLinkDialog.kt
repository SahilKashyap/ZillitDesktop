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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
        title = if (draft.isEdit) {
            str(S.desktop_br_edit_portal_link)
        } else {
            str(S.desktop_br_generate_portal_link)
        },
        onDismiss = { if (!draft.saving) onEvent(BankRecEvent.DismissPortalDraft) },
        visible = state.portal.draft != null,
        icon = BankRecIcons.Share,
        width = 600.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(BankRecEvent.DismissPortalDraft) },
                variant = ButtonVariant.Tertiary,
                enabled = !draft.saving,
            )
            ZillitTooltip(problem.orEmpty()) {
                ZillitButton(
                    text = when {
                        draft.saving && draft.isEdit -> str(S.ah_saving)
                        draft.saving -> str(S.drive_generating)
                        draft.isEdit -> str(S.dm_setup_save)
                        else -> str(S.desktop_br_generate_link_send_email)
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
                Field(str(S.dd_recipient_name)) {
                    ZillitTextField(
                        value = draft.recipientName,
                        onValueChange = { edit(draft.copy(recipientName = it)) },
                        placeholder = str(S.desktop_br_recipient_name_hint),
                    )
                }
            },
            right = {
                Field(str(S.hint_email)) {
                    ZillitTextField(
                        value = draft.recipientEmail,
                        onValueChange = { edit(draft.copy(recipientEmail = it.trim())) },
                        placeholder = str(S.desktop_br_recipient_email_hint),
                    )
                }
            },
        )
        FieldPair(
            left = {
                Field(str(S.desktop_organisation_type)) {
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
                Field(str(S.cr_meta_period)) {
                    ZillitSelect(
                        value = draft.periodId,
                        options = listOf("") + state.periods.map { it.id },
                        onSelect = { edit(draft.copy(periodId = it)) },
                        label = { id ->
                            state.period(id)?.let { period ->
                                val month = BankRecFormat.periodLabel(period)
                                if (period.isOpen) {
                                    str(S.desktop_br_period_in_progress, month)
                                } else {
                                    str(S.desktop_br_period_complete, month)
                                }
                            } ?: str(S.desktop_br_select_period)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
        if (state.bankAccounts.size > 1) {
            Field(str(S.dm_pay_card_bank)) {
                ZillitSelect(
                    value = draft.bankAccountId,
                    options = listOf("") + state.bankAccounts.map { it.id },
                    onSelect = { edit(draft.copy(bankAccountId = it)) },
                    label = { id ->
                        if (id.isBlank()) {
                            str(S.desktop_all_accounts)
                        } else {
                            state.account(id)?.displayName?.ifBlank { null } ?: str(S.desktop_bank)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Field(str(S.desktop_br_what_can_they_see)) {
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
                Field(str(S.desktop_br_link_expiry)) {
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
                Field(str(S.desktop_br_notify_when_viewed)) {
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
