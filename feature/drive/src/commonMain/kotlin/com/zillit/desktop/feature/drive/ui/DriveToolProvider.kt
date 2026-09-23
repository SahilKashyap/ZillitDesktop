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
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * What the Drive asks the machine for: pickers, the browser, the clipboard,
 * the document editor and a media player. One seam per capability, so the
 * main window and the widget wire the same set once each.
 */
@Suppress("LongParameterList") // One seam per host capability; see each parameter's doc.
class DriveHostSeams(
    /** Hands a URL to the OS — a download, a share link. */
    val onOpenUrl: (String) -> Unit = {},
    /**
     * Shows a file picker and reports what was chosen.
     *
     * A callback rather than a return value: the picker is modal on the host's
     * own thread, and the files arrive as an event so the queue is filled by
     * the same path a drag-and-drop uses.
     */
    val onPickFiles: ((List<PickedFile>) -> Unit) -> Unit = {},
    /** Shows a folder picker; every file beneath comes back with its relative path. */
    val onPickFolder: ((List<PickedFile>) -> Unit) -> Unit = {},
    /** The same picker, filtered to one kind from the widget's attach sheet. */
    val onPickFilesOf: (PreviewKind, (List<PickedFile>) -> Unit) -> Unit = { _, report -> onPickFiles(report) },
    /** Puts text on the system clipboard. */
    val onCopy: (String) -> Unit = {},
    /**
     * Opens the document editor.
     *
     * Separate from [onOpenUrl] because it is not the same act: the editor is
     * an embedded browser surface with a session token in its URL, and handing
     * that to the system browser leaks the token into another application's
     * history. Defaults to the plain launcher only because a build without an
     * embedded surface is better than a dead button.
     */
    val onOpenEditor: (url: String, fileName: String) -> Unit = { url, _ -> onOpenUrl(url) },
    /** Plays a video or audio file at its presigned address — the same surface as the editor. */
    val onOpenMedia: (url: String, title: String) -> Unit = { url, _ -> onOpenUrl(url) },
    /** The wall clock, for "3 days ago". */
    val now: () -> Long = { 0L },
)

/**
 * The Drive as a workspace window.
 *
 * Opens maximised and hosts its own routes: it is a sub-application with
 * several surfaces, its own navigation and a docked details panel, so handing
 * it the whole window is both what the plan calls for and what makes a file
 * browser beside its details usable.
 *
 * The path matches the web's (`/film-tools/drive`) so the tools grid, badge
 * routing and any deep link agree across clients.
 */
class DriveToolProvider(
    private val viewModel: DriveViewModel,
    private val host: DriveHostSeams = DriveHostSeams(),
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
            viewModel.effects.collect { effect -> failure = handle(effect, viewModel, host) ?: failure }
        }

        LaunchedEffect(state.section, state.showTrash) {
            val place = if (state.showTrash) str(S.trash_text) else state.section.label
            navigator.setTitle(str(S.txt_drive) + " · " + place)
        }

        DriveScreen(state = state, onEvent = viewModel::onEvent, onOpenWidget = onOpenWidget, now = host.now)

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

/**
 * Routes one effect to the host. Returns a message to show as a toast, or
 * null when the effect made no noise — shared by the tool and the widget.
 */
@Suppress("CyclomaticComplexMethod") // One branch per effect.
fun handle(effect: DriveEffect, viewModel: DriveViewModel, host: DriveHostSeams): String? = when (effect) {
    is DriveEffect.Failed -> effect.message
    is DriveEffect.OpenUrl -> null.also { host.onOpenUrl(effect.url) }
    is DriveEffect.OpenEditor -> null.also { host.onOpenEditor(effect.url, effect.fileName) }
    is DriveEffect.OpenMedia -> null.also { host.onOpenMedia(effect.url, effect.title) }
    // The label is the notice, not an error — but it is the only
    // confirmation a clipboard copy can give, and a silent copy reads as a
    // button that did nothing.
    is DriveEffect.CopyToClipboard -> effect.label.also { host.onCopy(effect.text) }
    DriveEffect.PickFiles -> null.also {
        host.onPickFiles { files -> viewModel.onEvent(DriveEvent.AddUploadFiles(files)) }
    }

    DriveEffect.PickFolder -> null.also {
        host.onPickFolder { files -> viewModel.onEvent(DriveEvent.AddUploadFiles(files)) }
    }

    is DriveEffect.PickFilesOf -> null.also {
        host.onPickFilesOf(effect.kind) { files -> viewModel.onEvent(DriveEvent.AddUploadFiles(files)) }
    }
}

const val DRIVE_PATH = "/film-tools/drive"
