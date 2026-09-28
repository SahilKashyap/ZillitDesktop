package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.LocalHostedBy
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Purchase Orders as a workspace window.
 *
 * ## Two doors, two views
 *
 * The web mounts two different modules at this one address and picks between
 * them by how the reader arrived: `PurchaseOrdersRouter` reads `?entry=tool`
 * and gives an accountant the **department view** through the Film Tools tile
 * and the **accounts console** through the Account Hub's own sidebar. The
 * tile's link says so literally —
 * `/film-tools/account-hub/purchase-orders?entry=tool`
 * (`useAvailableFilmTools.js`) — and the hub's sidebar deliberately omits the
 * marker (`AccountHubSidebar.jsx`: "the hub sidebar IS the Account Hub").
 *
 * Here the same question is [LocalHostedBy], which is null exactly when this
 * window stands on its own rather than inside the hub's console. A department
 * user is unaffected either way, as on the web: they have no console to be
 * kept out of.
 *
 * Each door gets its own view model — [viewModel] for the hub, [toolViewModel]
 * for the tile — because the web's two entries are two sessions with their own
 * page, filters, selection and open form. An accountant can have the console
 * up in the hub and their own orders up from the tile, and neither disturbs
 * the other. A host that passes one model for both (tests) still works: every
 * event is preceded by its door, so the shared model follows the window being
 * looked at.
 */
class PurchaseOrderToolProvider(
    private val viewModel: PurchaseOrderViewModel,
    /** The Film Tools tile's session — the department view. Defaults to sharing the hub's. */
    private val toolViewModel: PurchaseOrderViewModel = viewModel,
    /**
     * Fetches one of an order's files from storage and hands it to the OS.
     * Defaulted to nothing so a host without a downloader still composes.
     */
    private val onOpenAttachment: (PoAttachment) -> Unit = {},
) : ToolProvider {

    override val path: String = PURCHASE_ORDER_PATH
    override val title: String get() = str(S.ah_purchase_orders)
    override val icon = ZillitToolIcons.PurchaseOrder
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        // Standing alone is the tile; inside the hub's console is the hub.
        val asTool = LocalHostedBy.current == null
        val model = if (asTool) toolViewModel else viewModel
        val state by model.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }
        val focused = LocalWindowInfo.current.isWindowFocused

        // Before `start`, so the very first landing page is already this
        // door's — the web captures `entry=tool` at shell mount for the same
        // reason.
        LaunchedEffect(model, asTool) { model.onEvent(PoEvent.Enter(asTool)) }
        // A window coming to the front takes a shared view model with it.
        LaunchedEffect(model, asTool, focused) { if (focused) model.onEvent(PoEvent.Enter(asTool)) }

        LaunchedEffect(model) { model.start() }
        // Every composition and every route change applies the route — the
        // hub re-embeds this tool on its bare path, and `start()` alone is
        // idempotent, so a route read only there was ignored on re-entry.
        // A page the viewer cannot see yet (rights still loading) is held by
        // the view model and applied when they arrive; see openRoute.
        LaunchedEffect(model, route) { model.openRoute(route.path) }
        LaunchedEffect(model) {
            model.effects.collect { effect ->
                when (effect) {
                    is PoEffect.Failed -> failure = effect.message
                    // The file lives in the production's storage; fetching and
                    // handing it to the OS is the host's business, not this
                    // module's.
                    is PoEffect.OpenAttachment -> onOpenAttachment(effect.attachment)
                    // The editor is the Account Hub's Forms Configuration
                    // area, opened on this module — the web's
                    // `FormConfigLauncher` overlay, as a route. Inside the hub
                    // that route re-shows the console; standalone it takes the
                    // window there.
                    PoEffect.OpenFormConfig -> navigator.navigate(WorkspaceRoute.Tool(PO_FORM_CONFIG_ROUTE))
                    // The department view's two hand-off tabs. The web renders
                    // both modules *inside* the PO page; the desktop sends the
                    // host to them, because both already exist here as their
                    // own surfaces and a second copy of either would disagree
                    // with the first the moment one changed.
                    PoEffect.OpenVendors -> navigator.navigate(WorkspaceRoute.Tool(HUB_VENDORS_ROUTE))
                    PoEffect.OpenInvoices -> navigator.navigate(WorkspaceRoute.Tool(INVOICES_ROUTE))
                }
            }
        }
        val shown = state.enteredThrough(asTool)

        LaunchedEffect(shown.destination) {
            navigator.setTitle(str(S.desktop_po_window_title, shown.destination.label))
        }

        // Every event is preceded by its door, so a host sharing one model
        // between the two windows still draws and acts as the door in front.
        val onEvent: (PoEvent) -> Unit = remember(model, asTool) {
            { event ->
                model.onEvent(PoEvent.Enter(asTool))
                model.onEvent(event)
            }
        }

        PurchaseOrderScreen(state = shown, onEvent = onEvent)
        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

/**
 * This state as the composition that entered through [asTool] draws it.
 *
 * Only a host that shares one view model between the two doors ever sees a
 * difference — the app builds one per door — but that host is the one a test
 * uses, and without this it would draw the other door's page for a frame: a
 * console tab to somebody the tile says is a department user. The viewer
 * carries the door, so a page this viewer may not see reads as their landing
 * until an event from here moves the view model over.
 */
internal fun PoUiState.enteredThrough(asTool: Boolean): PoUiState {
    if (viewer.enteredAsTool == asTool) return this
    val own = viewer.copy(enteredAsTool = asTool)
    val page = destination.takeIf { it.visibleTo(own) } ?: PoDestination.landingFor(own)
    return copy(viewer = own, destination = page)
}

const val PURCHASE_ORDER_PATH = "/film-tools/purchase-order"

/** The Account Hub's Forms Configuration area, opened on purchase orders. */
const val PO_FORM_CONFIG_ROUTE = "/film-tools/account-hub/form-config/purchase_orders"

/**
 * The Account Hub's Vendors area — the department view's Vendors tab.
 *
 * The `from` marker matters. The hub's own vendors route is the accounts
 * team's and turns a department user away, exactly as the web's does; but the
 * web *also* mounts the same vendors module inside the department PO page, so
 * the page is theirs when PO is the one asking. This says PO is asking.
 */
const val HUB_VENDORS_ROUTE = "/film-tools/account-hub/vendors?from=purchase-orders"

/** The Invoices tool — the department view's Invoices tab. */
const val INVOICES_ROUTE = "/film-tools/invoices"
