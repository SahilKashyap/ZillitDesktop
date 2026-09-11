package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.InvoiceAlert
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.RunAuthorisationTier
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupSection

private const val USER_WIDTH = 180
private const val LIMIT_WIDTH = 120

/**
 * Who enters accounts-payable work, what they are told about, and who signs a
 * payment run off.
 *
 * The Invoices tool reads this document to decide what a row offers, but has
 * never written it — so this section is the only place it is edited on this
 * client, and it is a section rather than a tile for that reason.
 */
@Composable
internal fun InvoicesSetupSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val section = state.setup.invoicesSetup
    val value = section.edited
    val editable = state.viewer.canEdit

    SetupSectionCard(
        title = "Invoices Setup",
        description = "Posting limits for the accounts-payable team, the alerts they get, " +
            "and the levels a payment run passes through.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.InvoicesSetup)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.InvoicesSetup)) },
        editable = editable,
    ) {
        TeamMembers(value, editable, onEvent)
        Alerts(value, editable, onEvent)
        RunAuthorisation(value, editable, onEvent)
    }
}

private fun edit(value: InvoicesSetup) = AccountHubEvent.EditInvoicesSetup(value)

@Composable
private fun TeamMembers(
    value: InvoicesSetup,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitSectionLabel("Team and posting rights")
    if (value.teamMembers.isEmpty()) EmptyLine("Nobody can enter an invoice yet.")
    value.teamMembers.forEachIndexed { index, member ->
        TeamMemberRow(
            member = member,
            editable = editable,
            onChange = { next ->
                onEvent(
                    edit(
                        value.copy(
                            teamMembers = value.teamMembers.mapIndexed { i, m -> if (i == index) next else m },
                        ),
                    ),
                )
            },
            onRemove = {
                onEvent(edit(value.copy(teamMembers = value.teamMembers.filterIndexed { i, _ -> i != index })))
            },
        )
    }
    if (editable) {
        ZillitButton(
            text = "Add team member",
            onClick = { onEvent(edit(value.copy(teamMembers = value.teamMembers + InvoiceTeamMember()))) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

@Composable
private fun TeamMemberRow(
    member: InvoiceTeamMember,
    editable: Boolean,
    onChange: (InvoiceTeamMember) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = member.userId,
            onValueChange = { onChange(member.copy(userId = it)) },
            label = "User id",
            enabled = editable,
            modifier = Modifier.width(USER_WIDTH.dp),
        )
        // Left blank on purpose when unset: zero is a real limit that lets
        // somebody post nothing, and "not set" is a different answer.
        ZillitTextField(
            value = member.postingLimit,
            onValueChange = { onChange(member.copy(postingLimit = it)) },
            label = "Posting limit",
            enabled = editable,
            modifier = Modifier.width(LIMIT_WIDTH.dp),
        )
        ZillitCheckbox(
            checked = member.runAccess,
            onCheckedChange = { onChange(member.copy(runAccess = it)) },
            label = "Runs",
            enabled = editable,
        )
        ZillitCheckbox(
            checked = member.overrideAccess,
            onCheckedChange = { onChange(member.copy(overrideAccess = it)) },
            label = "Override",
            enabled = editable,
        )
        ZillitCheckbox(
            checked = member.isSenior,
            onCheckedChange = { onChange(member.copy(isSenior = it)) },
            label = "Senior",
            enabled = editable,
        )
        if (editable) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Remove ${member.userId.ifBlank { "this member" }}",
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun Alerts(value: InvoicesSetup, editable: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    ZillitSectionLabel("Alerts")
    InvoiceAlert.entries.forEach { alert ->
        ZillitCheckbox(
            checked = alert in value.alerts,
            onCheckedChange = { on ->
                val next = if (on) value.alerts + alert else value.alerts - alert
                onEvent(edit(value.copy(alerts = next)))
            },
            label = alert.label,
            enabled = editable,
        )
        ZillitText(
            text = alert.hint,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun RunAuthorisation(
    value: InvoicesSetup,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitSectionLabel("Run authorisation")
    if (value.runAuthorisation.isEmpty()) EmptyLine("No payment run can be authorised yet.")
    value.runAuthorisation.forEachIndexed { index, tier ->
        RunAuthorisationRow(
            tier = tier,
            editable = editable,
            onChange = { ids ->
                onEvent(
                    edit(
                        value.copy(
                            runAuthorisation = value.runAuthorisation.mapIndexed { i, existing ->
                                if (i == index) existing.copy(userIds = ids) else existing
                            },
                        ),
                    ),
                )
            },
            // Renumbered on removal, so the levels never show a gap.
            onRemove = {
                onEvent(
                    edit(
                        value.copy(
                            runAuthorisation = value.runAuthorisation.filterIndexed { i, _ -> i != index },
                        ).renumbered(),
                    ),
                )
            },
        )
    }
    if (editable) {
        ZillitButton(
            text = "Add level",
            onClick = {
                onEvent(
                    edit(
                        value.copy(
                            runAuthorisation = value.runAuthorisation + RunAuthorisationTier(),
                        ).renumbered(),
                    ),
                )
            },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

@Composable
private fun RunAuthorisationRow(
    tier: RunAuthorisationTier,
    editable: Boolean,
    onChange: (List<String>) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = "Level ${tier.tier}", style = ZillitTheme.typography.bodyMedium)
        ZillitTextField(
            value = tier.userIds.joinToString(", "),
            onValueChange = { text ->
                onChange(text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            },
            label = "Authoriser user ids",
            placeholder = "Comma-separated",
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        if (editable) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Remove level ${tier.tier}",
                onClick = onRemove,
            )
        }
    }
}
