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
    override val title: String = "Purchase Orders"
    override val icon = ZillitToolIcons.PurchaseOrder
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is PoEffect.Failed -> failure = effect.message
                    // The file lives in the production's storage; fetching and
                    // handing it to the OS is the host's business, not this
                    // module's.
                    is PoEffect.OpenAttachment -> onOpenAttachment(effect.attachment)
                }
            }
        }
        LaunchedEffect(state.destination) {
            navigator.setTitle("Purchase Orders · ${state.destination.label}")
        }

        PurchaseOrderScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val PURCHASE_ORDER_PATH = "/film-tools/purchase-order"
