package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.PortalExpiry
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.PortalNotify
import com.zillit.desktop.feature.bankrec.domain.PortalOrgType
import com.zillit.desktop.feature.bankrec.domain.PortalPermission
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState

/**
 * Sharing one period with somebody outside Zillit.
 *
 * What the recipient may see is the whole decision here. Two of the six name
 * individuals — fraud alerts and transaction detail — and neither is on by
 * default, because a guarantor who asked for balances should not receive a
 * list of who was paid what unless somebody chose that.
 */
@Composable
fun PortalLinkDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val draft = state.portal.draft
    ZillitDialogShell(
        title = if (draft?.isEdit == true) "Re-share this period" else "Share a period",
        subtitle = "A read-only summary, opened with an emailed code.",
        icon = ZillitIcons.Send,
        visible = draft != null,
        onDismiss = { onEvent(BankRecEvent.DismissPortalDraft) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissPortalDraft) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (draft?.isEdit == true) "Update link" else "Create link",
                onClick = { onEvent(BankRecEvent.SavePortalLink) },
                loading = draft?.saving == true,
                enabled = draft?.problem == null,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        Recipient(draft, onEvent)
        Scope(draft, state, onEvent)
        Permissions(draft, onEvent)
        Delivery(draft, onEvent)
    }
}

@Composable
private fun ColumnScope.Recipient(draft: PortalLinkDraft, onEvent: (BankRecEvent) -> Unit) {
    ZillitTextField(
        value = draft.recipientName,
        onValueChange = { onEvent(BankRecEvent.EditPortalDraft(draft.copy(recipientName = it))) },
        label = "Recipient",
        placeholder = "James Whitford",
        modifier = Modifier.fillMaxWidth(),
    )
    ZillitTextField(
        value = draft.recipientEmail,
        onValueChange = { onEvent(BankRecEvent.EditPortalDraft(draft.copy(recipientEmail = it))) },
        label = "Email address",
        // Said plainly because it is not obvious: this address is the
        // credential, not a delivery detail.
        helperText = "The code that opens the page is emailed here, so this address is what " +
            "actually grants access.",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.Scope(
    draft: PortalLinkDraft,
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
) {
    ZillitText(text = "Organisation", style = ZillitTheme.typography.label)
    ZillitSelect(
        value = draft.orgType.wire,
        options = PortalOrgType.entries.map { it.wire },
        onSelect = { onEvent(BankRecEvent.EditPortalDraft(draft.copy(orgType = PortalOrgType.from(it)))) },
        label = { wire -> PortalOrgType.from(wire).label },
        modifier = Modifier.fillMaxWidth(),
    )

    ZillitText(text = "Period", style = ZillitTheme.typography.label)
    ZillitSelect(
        value = draft.periodId,
        options = listOf(ALL_PERIODS) + state.periods.map { it.id },
        onSelect = { onEvent(BankRecEvent.EditPortalDraft(draft.copy(periodId = it))) },
        label = { id -> if (id == ALL_PERIODS) "Choose a period" else state.periodLabelFor(id) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.Permissions(draft: PortalLinkDraft, onEvent: (BankRecEvent) -> Unit) {
    ZillitText(text = "The recipient may see", style = ZillitTheme.typography.label)
    PortalPermission.entries.forEach { permission ->
        ZillitCheckbox(
            checked = permission in draft.permissions,
            onCheckedChange = { on ->
                val next = if (on) draft.permissions + permission else draft.permissions - permission
                onEvent(BankRecEvent.EditPortalDraft(draft.copy(permissions = next)))
            },
            label = permission.label,
        )
    }
    if (PortalPermission.TransactionDetail in draft.permissions ||
        PortalPermission.FraudAlerts in draft.permissions
    ) {
        ZillitNotice(
            text = "This link will show individual payments and who they went to. Share it only " +
                "with somebody entitled to that.",
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Warning,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColumnScope.Delivery(draft: PortalLinkDraft, onEvent: (BankRecEvent) -> Unit) {
    ZillitText(text = "Expires", style = ZillitTheme.typography.label)
    ZillitSelect(
        value = draft.expiry.wire,
        options = PortalExpiry.entries.map { it.wire },
        onSelect = { onEvent(BankRecEvent.EditPortalDraft(draft.copy(expiry = PortalExpiry.from(it)))) },
        label = { wire -> PortalExpiry.from(wire).label },
        modifier = Modifier.fillMaxWidth(),
    )

    ZillitText(text = "Tell me when it is opened", style = ZillitTheme.typography.label)
    ZillitSelect(
        value = draft.notifyOnView.wire,
        options = PortalNotify.entries.map { it.wire },
        onSelect = {
            onEvent(BankRecEvent.EditPortalDraft(draft.copy(notifyOnView = PortalNotify.from(it))))
        },
        label = { wire -> PortalNotify.from(wire).label },
        modifier = Modifier.fillMaxWidth(),
    )
}
