package com.zillit.desktop.feature.drive.ui

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
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * The Drive as a workspace window.
 *
 * Opens maximised and hosts its own routes: it is a sub-application with five
 * surfaces, its own navigation and a docked details panel, so handing it the
 * whole window is both what the plan calls for and what makes a file browser
 * beside its details usable.
 *
 * The path matches the web's (`/film-tools/drive`) so the tools grid, badge
 * routing and any deep link agree across clients.
 */
@Suppress("LongParameterList") // One seam per host capability; see each parameter's doc.
class DriveToolProvider(
    private val viewModel: DriveViewModel,
    /** Hands a URL to the OS. Injected because this module has no file layer. */
    private val onOpenUrl: (String) -> Unit = {},
    /**
     * Shows a file picker and reports what was chosen.
     *
     * A callback rather than a return value: the picker is modal on the host's
     * own thread, and the files arrive as an event so the queue is filled by
     * the same path a future drag-and-drop would use.
     */
    private val onPickFiles: ((List<PickedFile>) -> Unit) -> Unit = {},
    /** Puts a share link on the system clipboard. */
    private val onCopy: (String) -> Unit = {},
    /**
     * Opens the document editor.
     *
     * Separate from [onOpenUrl] because it is not the same act: the editor is
     * an embedded browser surface with a session token in its URL, and handing
     * that to the system browser leaks the token into another application's
     * history. Defaults to the plain launcher only because a build without an
     * embedded surface is better than a dead button.
     */
    private val onOpenEditor: (url: String, fileName: String) -> Unit = { url, _ ->
        onOpenUrl(url)
    },
    /** Opens the desktop Drive widget. Null hides the button. */
    private val onOpenWidget: (() -> Unit)? = null,
) : ToolProvider {

    override val path: String = DRIVE_PATH
    override val title: String = "Drive"
    override val icon = ZillitIcons.Drive
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        // Held here rather than in the state so a failure that has been read
        // does not reappear when the window is switched away from and back.
        var failure by remember { mutableStateOf<String?>(null) }

        // The first time this tool is shown: the view model is built with the
        // app, before a production is open, so it resolves who the viewer is
        // here rather than in its constructor.
        LaunchedEffect(viewModel) { viewModel.start() }

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is DriveEffect.Failed -> failure = effect.message
                    is DriveEffect.OpenUrl -> onOpenUrl(effect.url)
                    is DriveEffect.OpenEditor -> onOpenEditor(effect.url, effect.fileName)
                    is DriveEffect.CopyToClipboard -> {
                        onCopy(effect.text)
                        // The label is the notice, not an error — but it is the
                        // only confirmation a clipboard copy can give, and a
                        // silent copy reads as a button that did nothing.
                        failure = effect.label
                    }

                    DriveEffect.PickFiles ->
                        onPickFiles { files -> viewModel.onEvent(DriveEvent.Upload(files)) }
                }
            }
        }

        // The tab title names the open page, so several torn-off windows of the
        // same tool are told apart on the taskbar.
        LaunchedEffect(state.destination) {
            navigator.setTitle("Drive · ${state.destination.label}")
        }

        DriveScreen(state = state, onEvent = viewModel::onEvent, onOpenWidget = onOpenWidget)

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val DRIVE_PATH = "/film-tools/drive"
