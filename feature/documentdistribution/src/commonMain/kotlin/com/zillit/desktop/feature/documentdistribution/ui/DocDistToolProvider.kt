package com.zillit.desktop.feature.documentdistribution.ui

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
 * Document Distribution as a workspace window.
 *
 * Opens maximised and hosts its own routes: it is a sub-application with five
 * surfaces and its own navigation, so handing it the whole window is both what
 * the plan calls for and what makes a library beside a composer usable.
 *
 * The path matches the web's (`/film-tools/document-distribution`) so the tools
 * grid, badge routing and any deep link agree across clients. Note that it is
 * *not* `/film-tools/distribution` — that is the older, separate distribution
 * tool, and the two have distinct identifiers in the permission grid.
 */
class DocDistToolProvider(
    private val viewModel: DocDistViewModel,
    /** Opens a document. Injected because this module has no file layer. */
    private val onOpenUrl: (String) -> Unit = {},
) : ToolProvider {

    override val path: String = DOCUMENT_DISTRIBUTION_PATH
    override val title: String get() = str(S.dd_title)
    override val icon = ZillitToolIcons.IcDistribution
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        // Held here rather than in the state so a failure that has been read
        // does not reappear when the window is switched away from and back —
        // the effect fires once, the toast times out, and that is that.
        var failure by remember { mutableStateOf<String?>(null) }

        // The first time this tool is shown: the view model is built with the
        // app, before a production is open, so it resolves who the viewer is
        // here rather than in its constructor.
        LaunchedEffect(viewModel) { viewModel.start() }

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is DocDistEffect.Failed -> failure = effect.message
                    is DocDistEffect.OpenUrl -> onOpenUrl(effect.url)
                }
            }
        }

        // The tab title names the open page, so several torn-off windows of the
        // same tool are told apart on the taskbar.
        LaunchedEffect(state.destination) {
            navigator.setTitle(str(S.desktop_docdist_tab_title, state.destination.label))
        }

        DocDistScreen(state = state, onEvent = viewModel::onEvent)

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val DOCUMENT_DISTRIBUTION_PATH = "/film-tools/document-distribution"
