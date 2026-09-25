package com.zillit.desktop.feature.accounthub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.LocalHostedBy
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubBadgeCounts
import com.zillit.desktop.feature.accounthub.ui.components.ProvideHubFaces
import kotlinx.coroutines.flow.StateFlow

/**
 * The Account Hub as a workspace window.
 *
 * Opens maximised and hosts its own routes: it is a console over nine screens
 * with a sidebar of its own, and giving it anything less than the window would
 * leave a settings page with fifteen sections inside a pane.
 *
 * The path matches the web's so the tools grid, badge routing and any deep link
 * agree across clients.
 */
class AccountHubToolProvider(
    private val viewModel: AccountHubViewModel,
    /** The sidebar's counts, from the host's notification ledger; null shows none. */
    private val badges: StateFlow<HubBadgeCounts>? = null,
    /**
     * Finds the provider for a tool route, so the console can render the other
     * film tools inside its shell the way the web's nested routes do. Null
     * keeps the older behaviour: a tool row opens the tool in its own window.
     */
    private val tools: ((String) -> ToolProvider?)? = null,
    /** A crew member's photo by user id, for the people the console draws; null shows initials. */
    private val loadAvatar: suspend (String) -> ImageBitmap? = { null },
) : ToolProvider {
    /** The provider for a tool route the console may embed — never the console itself. */
    internal fun resolveTool(path: String): ToolProvider? =
        tools?.invoke(path)?.takeIf { it.path != ACCOUNT_HUB_PATH }

    internal fun onEmbeddedEvent(event: AccountHubEvent) = viewModel.onEvent(event)


    override val path: String = ACCOUNT_HUB_PATH
    override val title: String = str(S.ah_account_hub)
    override val icon = ZillitIcons.Ledger
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        val counts = badges?.collectAsState()?.value

        // Held here rather than in the state so a failure that has been read
        // does not reappear when the window is switched away from and back.
        var failure by remember { mutableStateOf<String?>(null) }

        // The first time this tool is shown: the view model is built with the
        // app, before a production is open, so it resolves who the viewer is
        // here rather than in its constructor.
        LaunchedEffect(viewModel) { viewModel.start() }

        // The ledger's counts, mapped to the sidebar's units by the host.
        LaunchedEffect(counts) { counts?.let(viewModel::onBadges) }

        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AccountHubEffect.Failed -> failure = effect.message
                    // A *different* window, not this one. Purchase Orders and
                    // Payroll are their own tools with their own routes; taking
                    // over the console's window would mean the way back to the
                    // hub is to close the tool you just opened.
                    is AccountHubEffect.OpenTool ->
                        navigator.openInNewWindow(WorkspaceRoute.Tool(effect.path))
                    // The header card's back arrow — the web's `/film-tools`.
                    AccountHubEffect.Back -> navigator.navigate(WorkspaceRoute.Tool(TOOLS_GRID_PATH))
                }
            }
        }

        // A route below the console's own path names a screen — the web's
        // nested routes, and how another tool's Form Configuration card lands
        // here on the right editor.
        LaunchedEffect(route.path) { hubRouteEvents(route.path).forEach(viewModel::onEvent) }

        // The tab title names the open screen, so several torn-off windows of
        // the same console are told apart on the taskbar.
        LaunchedEffect(state.area, state.embedded?.title) {
            val shown = state.embedded?.title?.takeIf { it.isNotBlank() } ?: state.area?.label
            navigator.setTitle(shown?.let { str(S.desktop_hub_account_hub_window_title, it) } ?: str(S.ah_account_hub))
        }

        val embed: (@Composable (EmbeddedTool) -> Unit)? =
            if (tools == null) null else { tool -> Embedded(tool, navigator) }

        // Unsaved Production Setup work is worth a prompt before the window closes.
        LaunchedEffect(state.setup.dirtySections, state.formConfig.dirty) {
            navigator.setDirty(state.setup.dirtySections.isNotEmpty() || state.formConfig.dirty)
        }

        // Read once when the console is composed. A clock that ticked under
        // the period-close dialog would change which week the confirmation
        // was for.
        val now = remember { viewModel.nowMillis() }
        ProvideHubFaces(loadAvatar) {
            AccountHubScreen(
                state = state,
                onEvent = viewModel::onEvent,
                canAttachAgreements = viewModel.canAttachAgreements,
                nowMillis = now,
                canImportBudget = viewModel.canImportBudget,
                canExport = viewModel.canExport,
                canOpenDocuments = viewModel.canOpenDocuments,
                embed = embed,
            )
        }

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }

    private companion object {
        /** Home's second face — the grid of everything this user may open. */
        const val TOOLS_GRID_PATH = "/home/tools"
    }
}

const val ACCOUNT_HUB_PATH = "/film-tools/account-hub"

/**
 * What a route below the console's path asks for: `/<area-slug>` opens that
 * area, and `/form-config/<module>` opens the forms editor on that module.
 * The bare path, or a slug the console does not have, asks for nothing.
 *
 * Approvers reads `?module=<wire>` and Production Setup `?setup=<modal>`
 * (`payroll`, `po`, `invoices`, or the modal's own slug).
 *
 * Vendors reads the web's own address shape as well: `/vendors/<tab>` opens
 * that tab, `?action=add` the new-vendor form, and `?action=edit&id=<id>` that
 * vendor's form — which is how the web's Invoices suppliers page sends someone
 * to add or correct a supplier.
 */
internal fun hubRouteEvents(path: String): List<AccountHubEvent> {
    val bare = path.substringBefore('?')
    val query = path.substringAfter('?', "").split('&')
        .mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap()
    val segments = bare.removePrefix(ACCOUNT_HUB_PATH).trim('/').split('/').filter { it.isNotBlank() }
    val area = HubArea.fromSlug(segments.firstOrNull()) ?: return emptyList()
    val module = segments.getOrNull(1)?.let(FormModule::from)
    return listOfNotNull(
        AccountHubEvent.Open(area),
        module?.takeIf { area == HubArea.FormConfig }?.let(AccountHubEvent::OpenFormConfig),
        // `?module=card_expenses` — the web's "Set Approval Level" link from the
        // card and petty-cash modules lands on that module's chain.
        query["module"]?.takeIf { area == HubArea.Approvers }
            ?.let { wire -> ApprovalModule.entries.firstOrNull { it.wire == wire } }
            ?.let(AccountHubEvent::SwitchApprovalModule),
        // `?setup=payroll` — Payroll's Entry Setup tile opens the settings it
        // edits, which live here in Production Setup.
        query["setup"]?.takeIf { area == HubArea.ProductionSetup }
            ?.let { key -> SetupModal.entries.firstOrNull { it.slug == key || it.slug.startsWith("${key}_") } }
            ?.let(AccountHubEvent::OpenSetupModal),
        // `?tab=cash-close` — where the Invoices module's old `/cash-close`
        // redirects (`InvoicesModule.jsx:534`).
        query["tab"]?.takeIf { area == HubArea.PeriodClose }
            ?.let { slug -> PeriodCloseTab.entries.firstOrNull { it.slug == slug } }
            ?.let(AccountHubEvent::SwitchPeriodCloseTab),
    ) + if (area == HubArea.Vendors) vendorRouteEvents(segments.getOrNull(1), query) else emptyList()
}

private fun vendorRouteEvents(tab: String?, query: Map<String, String>): List<AccountHubEvent> =
    listOfNotNull(
        VendorFilter.entries.firstOrNull { it.slug == tab }?.let(AccountHubEvent::FilterVendors),
        when (query["action"]) {
            "add" -> AccountHubEvent.OpenVendorForm()
            "edit" -> query["id"]?.takeIf { it.isNotBlank() }?.let { AccountHubEvent.OpenVendorForm(it) }
            else -> null
        },
    )

/**
 * Another film tool, rendered inside the console.
 *
 * The tool's own navigation stays inside: a route within the same tool
 * re-renders in place, a close returns to the hub's own area, and only a
 * genuinely new window leaves. The title stays the console's.
 */
@Composable
private fun AccountHubToolProvider.Embedded(tool: EmbeddedTool, navigator: WindowNavigator) {
    val provider = resolveTool(tool.path)
    if (provider == null) {
        EmbedUnavailable(tool)
        return
    }
    val inner = remember(navigator, provider) {
        EmbeddedNavigator(
            delegate = navigator,
            ownPath = provider.path,
            onRoute = { path -> onEmbeddedEvent(AccountHubEvent.EmbedRoute(path)) },
            onOtherTool = { path ->
                val other = resolveTool(path)
                other?.let { onEmbeddedEvent(AccountHubEvent.EmbedTool(path, it.title)) }
                other != null
            },
            onClose = { onEmbeddedEvent(AccountHubEvent.CloseEmbedded) },
        )
    }
    // Tells the tool it is inside the console — the web's hub entry, as against
    // `?entry=tool` from a Film Tools tile — so it shows the console's view.
    CompositionLocalProvider(LocalHostedBy provides ACCOUNT_HUB_PATH) {
        provider.Content(WorkspaceRoute.Tool(tool.path), inner)
    }
}

/**
 * The navigator an embedded tool is handed.
 *
 * Navigation within the tool's own routes stays inside the console, and so
 * does a link to another tool the console hosts; anything else, or a new
 * window, goes to the real navigator — a route below the console's own path
 * reaches the console that way and opens the page it names. Closing the tool
 * shows the console's own area again. Titles are the console's to set.
 */
private class EmbeddedNavigator(
    private val delegate: WindowNavigator,
    private val ownPath: String,
    private val onRoute: (String) -> Unit,
    /** Shows another hosted tool in the console; false when the console cannot host it. */
    private val onOtherTool: (String) -> Boolean,
    private val onClose: () -> Unit,
) : WindowNavigator {
    override val windowId get() = delegate.windowId
    override val canGoBack: Boolean get() = false

    override fun navigate(route: WorkspaceRoute) {
        when {
            route.path.startsWith(ownPath) -> onRoute(route.path)
            onOtherTool(route.path) -> Unit
            else -> delegate.navigate(route)
        }
    }

    override fun back() = onClose()

    override fun openInNewWindow(route: WorkspaceRoute) = delegate.openInNewWindow(route)

    override fun setTitle(title: String) = Unit

    override fun setDirty(dirty: Boolean) = delegate.setDirty(dirty)

    override fun close() = onClose()
}

/** A row whose tool the host has not registered: named, rather than a blank pane. */
@Composable
private fun EmbedUnavailable(tool: EmbeddedTool) {
    ZillitEmptyState(
        title = str(S.desktop_hub_x_is_not_available_here, tool.title.ifBlank { str(S.desktop_this_tool) }),
        message = str(S.desktop_hub_no_screen_registered_for_path, tool.path),
        icon = ZillitIcons.Ledger,
    )
}
