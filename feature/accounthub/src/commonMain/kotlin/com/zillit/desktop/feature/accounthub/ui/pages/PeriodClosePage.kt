package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage

/**
 * Closing an accounting period.
 *
 * One boundary that only moves forward. Everything dated on or before it goes
 * read-only in every source module — purchase orders, invoices, cards, cash
 * and payroll — and **there is no way to reopen it**. That is the whole shape
 * of this screen: it states what is already closed, asks before it acts, and
 * offers nothing that looks like an undo.
 *
 * The server owns every rule. Which week a date falls in, whether anything in
 * that window is still unposted, and the refusal to move backwards are all
 * its calls, and its refusal is shown as it comes — only the server knows
 * which transactions are in the way.
 */
@Composable
fun PeriodClosePage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, nowMillis: Long) {
    val close = state.periodClose
    val lock = close.lock

    HubPage {
        ZillitPageHeader(
            eyebrow = "Reports",
            title = "Period Close",
            description = "Lock everything up to a date so the books for that period stop moving.",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitStatTile(
                label = "Closed through",
                value = lock.lockedThrough.ifBlank { "Nothing closed yet" },
                tone = if (lock.isClosed) StatusTone.Done else StatusTone.Neutral,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Production time zone",
                value = lock.timeZone.ifBlank { "—" },
                sub = "Decides where a day ends",
                modifier = Modifier.weight(1f),
            )
        }

        CloseCard(state, onEvent, nowMillis)
    }

    ConfirmDialog(state, onEvent)
}

@Composable
private fun CloseCard(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    nowMillis: Long,
) {
    val close = state.periodClose

    ZillitSectionCard(title = "Close the current period", icon = ZillitIcons.Shield) {
        ZillitText(
            text = "Closing locks every transaction dated on or before the end of that week, " +
                "across every module. It cannot be undone.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        if (!state.viewer.canActAsAccountant) {
            ZillitNotice(
                text = "Closing a period is the accounts department's, and an admin is not exempt.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
            return@ZillitSectionCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = "Close through this week",
                onClick = { onEvent(AccountHubEvent.ProposePeriodClose(nowMillis)) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Shield,
                enabled = !close.loading && !close.closing,
            )
            ZillitText(
                text = "The server resolves which week that is from the production's own " +
                    "cost-report week.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * The confirmation.
 *
 * A dialog rather than an inline toggle because the act has no undo: the
 * question has to be asked somewhere the answer cannot be given by accident.
 */
@Composable
private fun ConfirmDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val close = state.periodClose

    ZillitDialogShell(
        title = "Close this period?",
        visible = close.pendingCloseMillis != null,
        onDismiss = { onEvent(AccountHubEvent.CancelPeriodClose) },
        icon = ZillitIcons.Shield,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.CancelPeriodClose) },
                variant = ButtonVariant.Tertiary,
                enabled = !close.closing,
            )
            ZillitButton(
                text = "Close the period",
                onClick = { onEvent(AccountHubEvent.ConfirmPeriodClose) },
                loading = close.closing,
                enabled = !close.closing,
            )
        },
    ) {
        ZillitText(
            text = "Every transaction dated on or before the end of that week becomes " +
                "read-only, in every module. There is no way to reopen it.",
            style = ZillitTheme.typography.bodyMedium,
        )
        if (close.lock.isClosed) {
            ZillitText(
                text = "Currently closed through ${close.lock.lockedThrough}.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitNotice(
            text = "If anything in the period is still unposted the service will refuse, and " +
                "say what is in the way.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
        )
    }
}
