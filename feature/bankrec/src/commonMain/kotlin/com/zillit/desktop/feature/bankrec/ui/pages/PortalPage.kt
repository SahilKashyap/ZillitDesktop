package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalStatus
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.dayLabel

private val PERIOD_WIDTH = 150.dp
private val STATUS_WIDTH = 110.dp
private val EXPIRY_WIDTH = 140.dp
private val VIEWS_WIDTH = 90.dp
private val ACTIONS_WIDTH = 250.dp

/**
 * Read-only links to one period, for people outside Zillit.
 *
 * A completion guarantor, a broadcaster or an auditor gets a page they can
 * open without a Zillit account. The link's address is not the credential:
 * they prove who they are with a code emailed to the address on the link.
 */
@Composable
fun ColumnScope.PortalPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val portal = state.portal

    ZillitNotice(
        text = "A recipient opens the page with a code emailed to the address on their link, " +
            "so passing the address on gives nothing away to somebody who cannot read that mailbox.",
        tone = StatusTone.Neutral,
        icon = ZillitIcons.Shield,
        modifier = Modifier.fillMaxWidth(),
    )

    ZillitSectionCard(
        title = "Shared links",
        icon = ZillitIcons.Send,
        meta = "${portal.links.count { it.isActive }} active",
        padded = false,
        action = {
            ZillitButton(
                text = "Share a period",
                onClick = { onEvent(BankRecEvent.ComposePortalLink) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitDataTable(
            rows = portal.links,
            columns = columns(state, onEvent),
            key = { it.id },
            loading = portal.loading,
            emptyTitle = "Nothing shared",
            emptyMessage = "Generate a link to give somebody outside Zillit a read-only " +
                "view of one period.",
            virtualised = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun columns(
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
): List<TableColumn<PortalLink>> = listOf(
    TableColumn(
        header = "Recipient",
        cell = { link ->
            androidx.compose.foundation.layout.Column {
                ZillitText(text = link.recipientName, style = ZillitTheme.typography.bodyMedium)
                ZillitText(
                    text = link.recipientEmail,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        },
    ),
    textColumn(header = "Organisation") { it.orgType.label },
    textColumn(header = "Period", width = ColumnWidth.Fixed(PERIOD_WIDTH)) {
        state.periodLabelFor(it.periodId)
    },
    TableColumn(
        header = "Sees",
        cell = { link ->
            ZillitText(
                text = link.permissions.joinToString(", ") { it.label },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        },
    ),
    textColumn(header = "Expires", width = ColumnWidth.Fixed(EXPIRY_WIDTH), muted = true) {
        it.expiresAtMillis?.let(::dayLabel) ?: "No expiry"
    },
    textColumn(header = "Views", width = ColumnWidth.Fixed(VIEWS_WIDTH), numeric = true) {
        it.viewCount.toString()
    },
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_WIDTH),
        cell = { link ->
            ZillitStatusPill(
                label = link.status.label,
                tone = when (link.status) {
                    PortalStatus.Active -> StatusTone.Done
                    PortalStatus.Revoked -> StatusTone.Rejected
                    PortalStatus.Expired -> StatusTone.Neutral
                },
                dot = true,
            )
        },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTIONS_WIDTH),
        cell = { link -> RowActions(link, state, onEvent) },
    ),
)

@Composable
private fun RowActions(link: PortalLink, state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (link.isActive) {
            ZillitButton(
                text = if (state.portal.copiedToken == link.token) "Copied" else "Copy link",
                onClick = { onEvent(BankRecEvent.CopyPortalLink(link)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Revoke",
                onClick = { onEvent(BankRecEvent.AskRevokeLink(link)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        ZillitButton(
            text = "Re-share",
            onClick = { onEvent(BankRecEvent.EditPortalLink(link)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}
