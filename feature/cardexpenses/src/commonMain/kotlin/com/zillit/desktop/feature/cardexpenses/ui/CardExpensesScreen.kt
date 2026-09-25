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
import com.zillit.desktop.core.designsystem.component.SideNavItem
import com.zillit.desktop.core.designsystem.component.SideNavSection
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
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
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardsForApprovalPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CodingQueuePage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CrewDialogs
import com.zillit.desktop.feature.cardexpenses.ui.pages.MyTransactionsPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardOverviewPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardRegisterPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardEditDialog
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardActionDialogs
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardDetailPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.CardSettingsPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.HistoryPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.NewCardDialog
import com.zillit.desktop.feature.cardexpenses.ui.pages.ActivationDialog
import com.zillit.desktop.feature.cardexpenses.ui.pages.BulkProcessPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.FundRequestsPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.ApprovalQueuePage
import com.zillit.desktop.feature.cardexpenses.ui.pages.PendingCodingPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.ProcessEditorPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.ProcessQueuePage
import com.zillit.desktop.feature.cardexpenses.ui.pages.QueryDialog
import com.zillit.desktop.feature.cardexpenses.ui.pages.StatementReviewPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.MyCardPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.ReceiptQueuePage
import com.zillit.desktop.feature.cardexpenses.ui.pages.TopUpToDoPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.AllTransactionsPage
import com.zillit.desktop.feature.cardexpenses.ui.pages.InboxDialogs
import com.zillit.desktop.feature.cardexpenses.ui.pages.ReceiptInboxPage
import com.zillit.desktop.feature.cardexpenses.ui.components.CardNavHeader

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
    /** The title card's back chip; null draws no title card. */
    onBack: (() -> Unit)? = null,
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
                    // The hub renders this tool full-bleed for an accountant,
                    // without its own sidebar, so the tool's sidebar carries
                    // the title and the way back — the web's `Sidebar.jsx`.
                    header = onBack?.let { back ->
                        {
                            CardNavHeader(
                                title = str(S.ah_card_expenses),
                                backLabel = str(S.desktop_card_back_to_hub),
                                onBack = back,
                            )
                        }
                    },
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    // The process editor and an open card each take over the
                    // content column, their own breadcrumb in place of the
                    // page's heading (`ProcessReceiptModal fullPage`,
                    // `CardDetailModal`); the sidebar stays.
                    // Fund Requests takes over the whole column too, as the web's
                    // does when the register goes full screen (`RequestFundsModal.jsx:336-360`).
                    if (state.funds != null) {
                        FundRequestsPage(state, onEvent)
                    } else {
                        if (state.process == null && state.openCard == null) AccountantPageHeader(state.destination)
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            if (state.process != null) ProcessEditorPage(state, onEvent) else CardBody(state, onEvent)
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // A child view gone full-screen — an upload form, a card or an
                // approval detail — hides the header and tabs (`CardExpensesModule.jsx:199`).
                if (!state.fullScreen && !state.crew.fullScreen && state.openCard == null) {
                    CardholderHeader(state, onEvent, onBack)
                    ZillitDivider()
                }
                CardBody(state, onEvent)
            }
        }

        // Over the page: the card forms, because the register behind them is
        // the context for what is being filled in. The confirmation comes last
        // so it sits on top of whichever of these raised it.
        NewCardDialog(state, onEvent)
        CardEditDialog(state, onEvent)
        ActivationDialog(state, onEvent)
        // Before the query: a Query raised from the receipt detail opens over it.
        InboxDialogs(state, onEvent)
        // Under the query thread, which the crew receipt detail opens over itself.
        CrewDialogs(state, onEvent)
        QueryDialog(state, onEvent)
        CardActionDialogs(state, onEvent)
        CardPromptDialog(state.prompt, onEvent)

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(CardEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

/**
 * The cardholder's heading and tabs (`CardExpensesModule.jsx:199-221`): the
 * way back beside "Production Expense Cards" and its one line, then My
 * Transactions, Card, Card Extension and the two queues a grant opens. No
 * Refresh — the pages re-read on their own.
 */
@Composable
private fun CardholderHeader(state: CardUiState, onEvent: (CardEvent) -> Unit, onBack: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            onBack?.let { back ->
                ZillitIconButton(
                    icon = ZillitIcons.ArrowLeft,
                    contentDescription = str(S.desktop_card_back_to_hub),
                    onClick = back,
                )
            }
            ZillitPageHeader(
                eyebrow = str(S.ah_card_expenses),
                title = str(S.ah_card_expenses),
                description = str(S.desktop_ce_cards_holder_subtitle),
                modifier = Modifier.weight(1f),
            )
        }
        ZillitTabStrip(
            tabs = state.destinations.map { ZillitTab(it.slug, it.label, count = state.unreadFor(it)) },
            activeId = state.destination.slug,
            onSelect = { slug -> CardDestination.fromSlug(slug)?.let { onEvent(CardEvent.Open(it)) } },
        )
    }
}

/**
 * The page's own heading — eyebrow, title and the web's one-line account of it.
 *
 * Every web page opens with one (`PageHeader`, e.g. `OverviewPage.jsx:224`);
 * the desktop's accountant pages started straight on their tiles, which read
 * as fourteen screens with no names.
 */
@Composable
private fun AccountantPageHeader(destination: CardDestination) {
    val heading = destination.heading() ?: return
    ZillitPageHeader(
        eyebrow = heading.eyebrow,
        title = heading.title,
        description = heading.blurb,
        modifier = Modifier.padding(
            start = ZillitTheme.spacing.xl,
            end = ZillitTheme.spacing.xl,
            top = ZillitTheme.spacing.xl,
        ),
    )
}

@Suppress("CyclomaticComplexMethod") // A dispatch table; splitting it hides the mapping.
@Composable
private fun CardBody(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val error = state.error
    if (error != null) {
        ZillitErrorState(message = error.localised(), onRetry = { onEvent(CardEvent.Refresh) })
        return
    }

    // A card opened from the register or the Card tab takes over the page.
    val open = state.openCard
    if (open != null && state.destination in CARD_PAGES) {
        CardDetailPage(state, open, onEvent)
        return
    }

    when (state.destination) {
        CardDestination.Overview -> CardOverviewPage(state, onEvent)
        CardDestination.CardRegister -> CardRegisterPage(state, onEvent)
        CardDestination.CardsForApproval -> CardsForApprovalPage(state, onEvent)
        CardDestination.MyTransactions -> MyTransactionsPage(state, onEvent)
        CardDestination.CodingQueue -> CodingQueuePage(state, onEvent)
        CardDestination.MyCards -> MyCardPage(state, onEvent)
        CardDestination.CardExtension -> CardExtensionPage(state, onEvent)
        CardDestination.ImportStatement -> StatementReviewPage(state, onEvent)
        CardDestination.BulkProcess -> BulkProcessPage(state, onEvent)
        CardDestination.PendingCoding -> PendingCodingPage(state, onEvent)
        CardDestination.ApprovalQueue -> ApprovalQueuePage(state, onEvent)
        CardDestination.ProcessQueue -> ProcessQueuePage(state, onEvent)
        CardDestination.ReceiptInbox -> ReceiptInboxPage(state, onEvent)
        CardDestination.AllTransactions -> AllTransactionsPage(state, onEvent)
        CardDestination.TopUpQueue -> TopUpToDoPage(state, onEvent)
        CardDestination.Analytics -> AnalyticsPage(state)
        CardDestination.Alerts -> AlertsPage(state, onEvent)
        CardDestination.Settings -> CardSettingsPage(state, onEvent)
        CardDestination.History -> HistoryPage(state, onEvent)

        // Everything else is a queue of receipts differing only in which rows
        // arrive and what a row may do — both derived from the destination.
        else -> ReceiptQueuePage(state, onEvent)
    }
}

/**
 * The sidebar, grouped and filtered to what this viewer may open.
 *
 * Chips are the web's and only the web's (`Sidebar.jsx:18-26`): the red unread
 * count on the seven rows the spec gives one. The amber work counts this
 * sidebar used to add read from the overview's figures, which the web shows on
 * the dashboard and not beside the navigation.
 */
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
                        unread = if (destination in SIDEBAR_CHIPS) unreadFor(destination) else 0,
                    )
                },
            )
        }

/** Unread notifications filed under a page — the web's sidebar `Badge` (`card-expenses-badge-helpers.js`). */
private fun CardUiState.unreadFor(destination: CardDestination): Int = destination.badgeKeys.sumOf { unread[it] ?: 0 }

/** The pages a card opens full-page from. */
private val CARD_PAGES = setOf(CardDestination.CardRegister, CardDestination.MyCards)

/** The rows the web's sidebar puts a chip on (`BADGE_LEVEL1_BY_KEY`). */
private val SIDEBAR_CHIPS = setOf(
    CardDestination.MyTransactions,
    CardDestination.CardRegister,
    CardDestination.ReceiptInbox,
    CardDestination.ApprovalQueue,
    CardDestination.ProcessQueue,
    CardDestination.TopUpQueue,
    CardDestination.Alerts,
)
