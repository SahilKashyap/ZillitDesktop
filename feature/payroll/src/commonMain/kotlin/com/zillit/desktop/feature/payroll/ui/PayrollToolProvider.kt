package com.zillit.desktop.feature.payroll.ui

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

/**
 * Payroll as a workspace window — and, embedded, as the Account Hub's
 * Payroll area.
 *
 * The route decides the screen (see [PayrollDestination.forRoute]), and every
 * move between screens is a navigation rather than a local switch: in its own
 * window the route changes; inside the hub the hub's navigator keeps a route
 * under this tool's path in the hub and re-renders it here.
 */
class PayrollToolProvider(
    private val viewModel: PayrollViewModel,
) : ToolProvider {

    override val path: String = PAYROLL_PATH
    override val title: String get() = str(S.dm_section_payroll)
    override val icon = ZillitToolIcons.Payroll
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 880.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        // The route is re-read whenever the viewer changes too: a deep link
        // that arrived before the crew list said who this is lands once it does.
        LaunchedEffect(route.path, state.viewer) { viewModel.onEvent(PayrollEvent.Route(route.path)) }
        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is PayrollEffect.Failed -> failure = effect.message
                    is PayrollEffect.Navigate -> navigator.navigate(WorkspaceRoute.Tool(effect.path))
                }
            }
        }
        LaunchedEffect(state.destination) {
            navigator.setTitle(
                if (state.destination == PayrollDestination.Landing) {
                    str(S.dm_section_payroll)
                } else {
                    str(S.desktop_payroll_window_title, state.destination.label)
                },
            )
        }

        PayrollScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}
