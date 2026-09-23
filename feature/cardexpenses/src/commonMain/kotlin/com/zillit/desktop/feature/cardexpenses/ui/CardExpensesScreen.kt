package com.zillit.desktop.feature.cardexpenses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.SideNavItem
import com.zillit.desktop.core.designsystem.component.SideNavSection
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSideNav
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.ui.pages.AlertsPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.AnalyticsPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardExtensionPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardOverviewPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardRegisterPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardEditDialog
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardSettingsPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.HistoryPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.NewCardDialog
import com.zillit.desktop.feature.cardexpenses.ui.pages.BulkProcessPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.SplitEditorDialog
import com.zillit.desktop.feature.cardexpenses.ui.pages.StatementReviewPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.MyCardPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.ReceiptQueuePage
import com.zillit.desktop.feature.cardexpenses.ui.pages.TopUpQueuePage
import com.zillit.desktop.feature.cardexpenses.ui.pages.TransactionsPage

/**
 * The Card Expenses tool.
 *
 * ## Two layouts
 *
 * An accountant gets the sidebar — fourteen surfaces do not fit a tab strip
 * and do not group without headings. A cardholder gets tabs, because they have
 * three or four places to be and a sidebar would make a small tool look like a
 * large one. Both draw from the same destination set; see [CardDestination].
 */
@Composable
fun CardExpensesScreen(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (state.viewer.isAccountant) {
            Row(modifier = Modifier.fillMaxSize()) {
                ZillitSideNav(
                    sections = state.navSections(),
                    activeId = state.destination.slug,
                    onSelect = { slug ->
                        CardDestination.fromSlug(slug)?.let { onEvent(CardEvent.Open(it)) }
                    },
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    CardBody(state, onEvent)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                CardholderHeader(state, onEvent)
                ZillitDivider()
                CardBody(state, onEvent)
            }
        }

        CardPromptDialog(state.prompt, onEvent)

        // Over the page: splitting is a focused task and the queue behind
        // stays where it was, so the next receipt is one click away. The two
        // card forms are over the page for the same reason — the register
        // behind them is the context for what is being filled in.
        SplitEditorDialog(state, onEvent)
        NewCardDialog(state, onEvent)
        CardEditDialog(state, onEvent)

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(CardEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

@Composable
private fun CardholderHeader(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            eyebrow = str(S.desktop_finance),
            title = str(S.ah_card_expenses),
            description = str(S.desktop_card_holder_subtitle),
            actions = {
                ZillitButton(
                    text = str(S.refresh_text),
                    onClick = { onEvent(CardEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = state.loading,
                )
            },
        )
        ZillitTabStrip(
            tabs = state.destinations.map { ZillitTab(it.slug, it.label, count = state.unreadFor(it)) },
            activeId = state.destination.slug,
            onSelect = { slug -> CardDestination.fromSlug(slug)?.let { onEvent(CardEvent.Open(it)) } },
        )
    }
}

@Suppress("CyclomaticComplexMethod") // A dispatch table; splitting it hides the mapping.
@Composable
private fun CardBody(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val error = state.error
    if (error != null) {
        ZillitErrorState(message = error.localised(), onRetry = { onEvent(CardEvent.Refresh) })
        return
    }

    when (state.destination) {
        CardDestination.Overview -> CardOverviewPage(state, onEvent)
        CardDestination.CardRegister, CardDestination.CardsForApproval -> CardRegisterPage(state, onEvent)
        CardDestination.MyCards -> MyCardPage(state, onEvent)
        CardDestination.CardExtension -> CardExtensionPage(state, onEvent)
        CardDestination.ImportStatement -> StatementReviewPage(state, onEvent)
        CardDestination.BulkProcess -> BulkProcessPage(state, onEvent)
        CardDestination.AllTransactions -> TransactionsPage(state, onEvent)
        CardDestination.TopUpQueue -> TopUpQueuePage(state, onEvent)
        CardDestination.Analytics -> AnalyticsPage(state, onEvent)
        CardDestination.Alerts -> AlertsPage(state, onEvent)
        CardDestination.Settings -> CardSettingsPage(state, onEvent)
        CardDestination.History -> HistoryPage(state, onEvent)

        // Everything else is a queue of receipts differing only in which rows
        // arrive and what a row may do — both derived from the destination.
        else -> ReceiptQueuePage(state, onEvent)
    }
}

/** The sidebar, grouped and filtered to what this viewer may open. */
private fun CardUiState.navSections(): List<SideNavSection> =
    destinations
        .groupBy { it.group }
        .map { (group, items) ->
            SideNavSection(
                title = group.title,
                items = items.map { destination ->
                    SideNavItem(
                        id = destination.slug,
                        label = destination.label,
                        icon = destination.icon,
                        count = badgeFor(destination),
                        unread = unreadFor(destination),
                    )
                },
            )
        }

/**
 * The count shown against a sidebar row.
 *
 * Read from the overview's own counts rather than a separate badge feed: the
 * dashboard and the sidebar disagreeing about how much work is waiting is
 * worse than either being slightly stale.
 */
/** Unread notifications filed under a page — the web's sidebar `Badge` (`card-expenses-badge-helpers.js`). */
private fun CardUiState.unreadFor(destination: CardDestination): Int = destination.badgeKeys.sumOf { unread[it] ?: 0 }

private fun CardUiState.badgeFor(destination: CardDestination): Int = when (destination) {
    CardDestination.ReceiptInbox -> overview?.inbox ?: 0
    CardDestination.PendingCoding -> overview?.pendingCoding ?: 0
    CardDestination.BulkProcess -> bulkItems.size
    CardDestination.ApprovalQueue -> overview?.inApproval ?: 0
    CardDestination.CardRegister -> overview?.requestedCards ?: 0
    CardDestination.TopUpQueue -> overview?.pendingTopUps?.size ?: 0
    CardDestination.Alerts -> alerts.count { it.status == "open" }
    else -> 0
}
