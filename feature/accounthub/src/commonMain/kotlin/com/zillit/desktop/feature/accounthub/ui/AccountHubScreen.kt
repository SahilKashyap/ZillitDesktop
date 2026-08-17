package com.zillit.desktop.feature.accounthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.SideNavItem
import com.zillit.desktop.core.designsystem.component.SideNavSection
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSideNav
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubItem
import com.zillit.desktop.feature.accounthub.domain.HubSection
import com.zillit.desktop.feature.accounthub.domain.HubTarget
import com.zillit.desktop.feature.accounthub.ui.pages.ApproversPage
import com.zillit.desktop.feature.accounthub.ui.pages.ChartOfAccountsPage
import com.zillit.desktop.feature.accounthub.ui.pages.ProductionSetupPage
import com.zillit.desktop.feature.accounthub.ui.pages.VendorsPage

/**
 * The Account Hub console.
 *
 * ## A sidebar, not tabs
 *
 * The hub reaches a dozen places across five groups. As a tab strip they
 * neither fit nor group, which is why the web ships a sidebar here and tabs
 * everywhere else — and why the groups are rendered as separate cards, so the
 * eye lands on a group of three rather than a list of twelve.
 *
 * ## Half the rows leave
 *
 * Purchase Orders, Payroll, Timecard and the rest are separate tools with their
 * own windows. Selecting one is a hand-off, not navigation: it raises an effect
 * and the console stays where it was, so coming back does not mean re-finding
 * your place.
 */
@Composable
fun AccountHubScreen(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (state.viewer.isBlocked) {
            ZillitEmptyState(
                title = "No access to the Account Hub",
                message = "An administrator has not granted you view rights for the Account " +
                    "Hub on this production.",
                icon = ZillitIcons.Shield,
            )
            return@Box
        }

        Row(modifier = Modifier.fillMaxSize()) {
            ZillitSideNav(
                sections = state.sections.map(::toNavSection),
                activeId = state.area?.slug,
                onSelect = { id -> onNavSelect(state, id, onEvent) },
                modifier = Modifier.fillMaxHeight().background(ZillitTheme.colors.surface),
            )
            // Vertical, not `ZillitDivider`: that one fills its width, and in a
            // Row it takes the whole thing and blanks the console.
            ZillitVerticalDivider()

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                AccountHubBody(state, onEvent)
            }
        }

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(AccountHubEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

@Composable
private fun AccountHubBody(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    when (state.area) {
        HubArea.ProductionSetup -> ProductionSetupPage(state, onEvent)
        HubArea.ChartOfAccounts -> ChartOfAccountsPage(state, onEvent)
        HubArea.Vendors -> VendorsPage(state, onEvent)
        HubArea.Approvers -> ApproversPage(state, onEvent)
        // Not a loading state — a department user reaching the console has the
        // three spend tools and nothing the hub itself renders. Saying so beats
        // an empty frame that looks broken.
        null -> ZillitEmptyState(
            title = "Nothing here for you yet",
            message = "The Account Hub's own screens are the accounts department's. " +
                "The tools you can use are listed on the left.",
            icon = ZillitIcons.Ledger,
        )
    }
}

/**
 * Routes a sidebar click.
 *
 * Ids are matched against the hub's own areas first: an area's id and its slug
 * are the same string, and a hand-off row can never collide with one because
 * the tool rows are keyed by tool id.
 */
private fun onNavSelect(
    state: AccountHubUiState,
    id: String,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val item = state.sections.flatMap { it.items }.firstOrNull { navId(it) == id } ?: return
    when (item.target) {
        is HubTarget.Page -> onEvent(AccountHubEvent.Open(item.target.area))
        is HubTarget.Tool -> onEvent(AccountHubEvent.OpenTool(item))
    }
}

private fun toNavSection(section: HubSection) =
    SideNavSection(
        title = section.title,
        items = section.items.map { item ->
            SideNavItem(id = navId(item), label = item.label, icon = iconFor(item))
        },
    )

/** An area is addressed by its slug so the active row survives a reload. */
private fun navId(item: HubItem): String = when (item.target) {
    is HubTarget.Page -> item.target.area.slug
    is HubTarget.Tool -> item.id
}

private fun iconFor(item: HubItem): ImageVector = when (item.id) {
    "production-setup" -> ZillitIcons.Settings
    "purchase-orders" -> ZillitIcons.Receipt
    "card-expenses" -> ZillitIcons.CreditCard
    "cash-expenses" -> ZillitIcons.Wallet
    "payroll" -> ZillitIcons.Bank
    "timecard" -> ZillitIcons.Clock
    "deal-memo" -> ZillitIcons.File
    "vendors" -> ZillitIcons.Users
    "approvers" -> ZillitIcons.Shield
    "chart-of-accounts" -> ZillitIcons.Ledger
    else -> ZillitIcons.Grid
}

/**
 * The frame every hub screen sits in.
 *
 * A page that scrolls its own body owns the scrolling; this supplies only the
 * padded column, because a page hosting a data table must not be nested inside
 * a scrolling parent — the table is measured against an unbounded height and
 * Compose refuses outright.
 */
@Composable
internal fun HubPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}
