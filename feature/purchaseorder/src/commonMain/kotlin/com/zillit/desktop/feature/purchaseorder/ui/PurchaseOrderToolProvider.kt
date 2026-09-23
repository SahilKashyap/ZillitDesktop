package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/** Purchase Orders as a workspace window. */
class PurchaseOrderToolProvider(
    private val viewModel: PurchaseOrderViewModel,
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
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        // Every composition and every route change applies the route — the
        // hub re-embeds this tool on its bare path, and `start()` alone is
        // idempotent, so a route read only there was ignored on re-entry.
        // A page the viewer cannot see yet (rights still loading) is held by
        // the view model and applied when they arrive; see openRoute.
        LaunchedEffect(route) { viewModel.openRoute(route.path) }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
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
        LaunchedEffect(state.destination) {
            navigator.setTitle(str(S.desktop_po_window_title, state.destination.label))
        }

        PurchaseOrderScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val PURCHASE_ORDER_PATH = "/film-tools/purchase-order"

/** The Account Hub's Forms Configuration area, opened on purchase orders. */
const val PO_FORM_CONFIG_ROUTE = "/film-tools/account-hub/form-config/purchase_orders"

/** The Account Hub's Vendors area — the department view's Vendors tab. */
const val HUB_VENDORS_ROUTE = "/film-tools/account-hub/vendors"

/** The Invoices tool — the department view's Invoices tab. */
const val INVOICES_ROUTE = "/film-tools/invoices"
