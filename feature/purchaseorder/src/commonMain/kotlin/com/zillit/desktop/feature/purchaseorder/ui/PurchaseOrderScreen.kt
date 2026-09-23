package com.zillit.desktop.feature.purchaseorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TabStripSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoAddressesPage
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoDialogs
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoDraftsPage
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoEntryPage
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoFormPage
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoListPage
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoPostedPage
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoQueuePage
import com.zillit.desktop.feature.purchaseorder.ui.pages.PoTemplatesPage

/**
 * The Purchase Orders tool.
 *
 * The web's own shell, one tab strip deep: a search box, the role's tabs, the
 * action button that raises an order, then the right-hand group (Templates, PO
 * Drafts, Delivery Addresses). An accounts assistant gets a banner saying what
 * is missing rather than a quietly shorter strip.
 *
 * Two surfaces take over the page instead of appearing beside it — the form and
 * the processing page — for the reason the web suppresses its own header for
 * them: both are long, and a tab strip above a half-filled form invites the
 * click that loses it.
 */
@Composable
fun PurchaseOrderScreen(
    state: PoUiState,
    onEvent: (PoEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        val form = state.form
        val entry = state.entry
        when {
            form != null -> PoFormPage(state, form, onEvent)
            entry != null && state.destination == PoDestination.Entry -> PoEntryPage(state, entry, onEvent)
            else -> Console(state, onEvent)
        }

        PoDialogs(state, onEvent)
        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(PoEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

@Composable
private fun Console(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitPageHeader(
                eyebrow = str(S.desktop_transactions),
                title = str(S.ah_purchase_orders),
                description = str(S.desktop_po_lifecycle_description),
                actions = {
                    ZillitButton(
                        text = str(S.refresh_text),
                        onClick = { onEvent(PoEvent.Refresh) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Reload,
                        loading = state.loading,
                    )
                },
            )
            // Its own row, as the web has it, but **bounded**: stretched across
            // 1400 points it read as a banner rather than a field. It cannot
            // ride the tab row either — a senior sees nine tabs, which leaves
            // no width for it and truncates the last one.
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(PoEvent.Search(it)) },
                placeholder = str(S.desktop_po_search_placeholder),
                modifier = Modifier.widthIn(max = SEARCH_WIDTH).fillMaxWidth(),
            )
            TabBar(state, onEvent)
        }
        ZillitDivider()
        OfflineBanner(state)
        if (state.showAssistantBanner) AssistantBanner()

        val error = state.error
        if (error != null) {
            ZillitErrorState(message = error.localised(), onRetry = { onEvent(PoEvent.Refresh) })
            return@Column
        }
        // Bounded on purpose: the pages below scroll, and a scrolling child of
        // an unbounded column is handed infinite height — which is how the
        // order table ended up boxed into whatever was left over.
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Page(state, onEvent)
        }
    }
}

/**
 * The strip: every tab this role has, then the action button.
 *
 * The web draws two groups of identical underline tabs with its "Enter PO" /
 * "Create PO" button *between* them. One strip here, with the button in the
 * trailing slot, because the design system's strip owns the overflow scrolling
 * and the active underline — two strips side by side each claim half the row
 * and the tabs stop lining up. The labels and their order are the web's; only
 * the button moved to the end of the row, where a desktop primary action goes.
 */
@Composable
private fun TabBar(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val tabs = state.mainTabs + state.registerTabs
    ZillitTabStrip(
        tabs = tabs.map { tab ->
            // The web's tab chips: the four badged tabs by their slice, Invoices by its unit.
            val count = if (tab == PoDestination.Invoices) state.unread.invoices else state.unread.tab(tab.badgeScope)
            ZillitTab(tab.slug, tab.label, count = count)
        },
        activeId = state.destination.slug,
        onSelect = { slug -> tabs.firstOrNull { it.slug == slug }?.let { onEvent(PoEvent.Open(it)) } },
        size = TabStripSize.Primary,
        trailing = {
            Spacer(modifier = Modifier.width(ZillitTheme.spacing.sm))
            ZillitButton(
                text = state.createLabel,
                onClick = { onEvent(PoEvent.CreateOrder) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    )
}

@Composable
private fun Page(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    when (state.destination) {
        PoDestination.Queue -> PoQueuePage(state, onEvent)
        PoDestination.Posted -> PoPostedPage(state, onEvent)
        PoDestination.Templates -> PoTemplatesPage(state, onEvent)
        PoDestination.Drafts -> PoDraftsPage(state, onEvent)
        PoDestination.DeliveryAddresses -> PoAddressesPage(state, onEvent)
        PoDestination.Settings -> PoSettingsPage(state, onEvent)
        PoDestination.Reports -> ComingSoon(str(S.reports))
        // Both hand off: the tap already sent the host somewhere else, and this
        // is what is behind it if the hand-off could not be taken.
        PoDestination.Vendors -> HandOff(
            title = str(S.ah_vendors),
            message = str(S.desktop_po_vendors_handoff),
            icon = ZillitIcons.Users,
            action = str(S.desktop_open_vendors),
            onAction = { onEvent(PoEvent.OpenVendors) },
        )

        PoDestination.Invoices -> HandOff(
            title = str(S.ah_invoices),
            message = str(S.desktop_po_invoices_handoff),
            icon = ZillitIcons.Receipt,
            action = str(S.desktop_open_invoices),
            onAction = { onEvent(PoEvent.OpenInvoices) },
        )

        PoDestination.Entry -> PoEntryPlaceholder(onEvent)
        else -> PoListPage(state, onEvent)
    }
}

/**
 * The PO Entry tab with nothing being processed.
 *
 * The web's `activeTab === "entry"` with no `selectedPO` renders the same
 * prompt: the tab is a *place*, not a list, and it fills when an order is
 * picked from the queue.
 */
@Composable
private fun PoEntryPlaceholder(onEvent: (PoEvent) -> Unit) {
    ZillitEmptyState(
        title = str(S.desktop_po_no_po_open),
        message = str(S.desktop_po_entry_placeholder_message),
        icon = ZillitIcons.Ledger,
        action = {
            ZillitButton(
                text = str(S.desktop_go_to_queue),
                onClick = { onEvent(PoEvent.Open(PoDestination.Queue)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        },
    )
}

/** The web's own placeholder, for the tab it has not built either. */
@Composable
private fun ComingSoon(title: String) {
    ZillitEmptyState(
        title = title,
        message = str(S.ah_coming_soon),
        icon = ZillitIcons.BarChart,
    )
}

@Composable
private fun HandOff(
    title: String,
    message: String,
    icon: ImageVector,
    action: String,
    onAction: () -> Unit,
) {
    ZillitEmptyState(
        title = title,
        message = message,
        icon = icon,
        action = {
            ZillitButton(text = action, onClick = onAction, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        },
    )
}

/**
 * The web's "Assistant View" notice.
 *
 * Worth keeping verbatim: an accounts assistant opening this tool finds two
 * tabs missing and no explanation, and the alternative to saying so is a
 * support question every time somebody joins the department.
 */
@Composable
private fun AssistantBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
    ) {
        ZillitNotice(
            text = str(S.desktop_po_assistant_view_banner),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Shield,
        )
    }
}

/** What the person is looking at when the network is gone. */
@Composable
internal fun OfflineBanner(state: PoUiState) {
    val stale = state.staleSince
    if (!state.offline && stale == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(BANNER_DOT)
                .clip(RoundedCornerShape(BANNER_DOT))
                .background(ZillitTheme.colors.warning),
        )
        ZillitText(
            text = when {
                stale != null -> str(S.desktop_po_showing_saved_copy_offline, EpochDate.dateTime(stale))
                else -> str(S.desktop_po_offline_orders_queued)
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

private val BANNER_DOT = 8.dp
private val SEARCH_WIDTH = 560.dp
