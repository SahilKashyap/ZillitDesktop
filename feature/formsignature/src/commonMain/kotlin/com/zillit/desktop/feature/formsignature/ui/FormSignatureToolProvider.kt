package com.zillit.desktop.feature.formsignature.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.formsignature.domain.FormSignatureHost

/**
 * Documents & Signature as a workspace tool.
 *
 * The path matches the web's (`/film-tools/form-signature`) so the tools
 * grid, badges and deep links agree across clients.
 */
class FormSignatureToolProvider(
    private val viewModel: FormSignatureViewModel,
    /**
     * Shows a file picker of the asked kind and reports the file's name and
     * bytes. A callback because file dialogs are the host's; a null answer
     * means "cancelled".
     */
    private val onPickFile: (kind: PickKind, onPicked: (Pair<String, ByteArray>?) -> Unit) -> Unit,
    private val host: FormSignatureHost = FormSignatureHost.None,
    /** The discussion room's board — the Home engine on the tool's unit — when the host has one. */
    private val chatBoard: (@Composable (WorkspaceRoute, WindowNavigator) -> Unit)? = null,
) : ToolProvider {

    override val path: String = FORM_SIGNATURE_PATH
    override val title: String = FormSignatureUiState.TOOL_TITLE
    override val icon = ZillitIcons.Signature
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        var problem by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is FormSignatureEffect.PickFile -> onPickFile(effect.kind) { picked ->
                        if (picked != null) {
                            viewModel.onEvent(FormSignatureEvent.FilePicked(effect.target, picked.first, picked.second))
                        }
                    }
                    is FormSignatureEffect.Notice -> notice = effect.message
                    is FormSignatureEffect.Failed -> problem = effect.message
                }
            }
        }

        FormSignatureScreen(
            state = state,
            onEvent = viewModel::onEvent,
            host = host,
            chatBoard = chatBoard?.let { board -> { board(route, navigator) } },
        )

        ZillitToast(message = notice, onDismiss = { notice = null }, tone = ZillitToastTone.Success)
        ZillitToast(message = problem, onDismiss = { problem = null }, tone = ZillitToastTone.Danger)
    }
}

const val FORM_SIGNATURE_PATH = "/film-tools/form-signature"
