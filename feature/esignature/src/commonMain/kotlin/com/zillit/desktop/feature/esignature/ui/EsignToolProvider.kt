package com.zillit.desktop.feature.esignature.ui

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
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/** What the host is asked to pick: a PDF, a CSV, or an image. */
enum class EsignPickKind { Pdf, Csv, Image }

/** E-Signature as a workspace tool; the path matches the web's. */
class EsignToolProvider(
    private val viewModel: EsignViewModel,
    /** Opens the OS chooser for [EsignPickKind]; answers null when cancelled. */
    private val onPick: (kind: EsignPickKind, onPicked: (Pair<String, ByteArray>?) -> Unit) -> Unit,
) : ToolProvider {

    override val path: String = ESIGNATURE_PATH
    override val title: String = "E-Signature"
    override val icon = ZillitIcons.Edit
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 880.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        var problem by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    EsignEffect.PickPdf -> pick(EsignPickKind.Pdf)
                    EsignEffect.PickCsv -> pick(EsignPickKind.Csv)
                    EsignEffect.PickImage -> pick(EsignPickKind.Image)
                    is EsignEffect.Notice -> notice = effect.message
                    is EsignEffect.Failed -> problem = effect.message
                }
            }
        }

        EsignScreen(state = state, onEvent = viewModel::onEvent)

        ZillitErrorToast(message = problem, onDismiss = { problem = null })
        ZillitToast(message = notice, onDismiss = { notice = null }, tone = ZillitToastTone.Success)
    }

    private fun pick(kind: EsignPickKind) {
        onPick(kind) { picked ->
            if (picked != null) {
                viewModel.onEvent(EsignEvent.FilePicked(picked.first, picked.second))
            } else {
                viewModel.pickCancelled()
            }
        }
    }
}

const val ESIGNATURE_PATH = "/film-tools/e-signature"
