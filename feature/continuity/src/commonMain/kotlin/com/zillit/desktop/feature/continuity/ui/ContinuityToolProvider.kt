package com.zillit.desktop.feature.continuity.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile

/** Continuity as a workspace tool, at the web's path (`/film-tools/continuity`). */
class ContinuityToolProvider(
    private val viewModel: ContinuityViewModel,
    /** Shows a multi-file picker of one kind; an empty answer means "cancelled". */
    private val onPickFiles: (kind: PickKind, onPicked: (List<PickedContinuityFile>) -> Unit) -> Unit,
    /** A stored file decoded — the thumbnail for cards, the full file for the viewer. */
    private val loadImage: suspend (ContinuityAttachment, preview: Boolean) -> ImageBitmap?,
    private val resolveUser: (String) -> String?,
    private val formatDate: (Long) -> String,
    /** A stored PDF's pages as bitmaps for the viewer; null when the host cannot render. */
    private val loadPdfPages: suspend (ContinuityAttachment) -> List<ImageBitmap>? = { null },
) : ToolProvider {

    override val path: String = CONTINUITY_PATH
    override val title: String = "Continuity"
    override val icon = ZillitToolIcons.Continuity
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<ContinuityEffect.Notice?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ContinuityEffect.Notice -> notice = effect
                    is ContinuityEffect.PickFiles -> onPickFiles(effect.kind) { picked ->
                        if (picked.isNotEmpty()) viewModel.onEvent(ContinuityEvent.FilesPicked(picked))
                    }
                }
            }
        }

        ContinuityScreen(
            state = state,
            onEvent = viewModel::onEvent,
            loadImage = loadImage,
            resolveUser = resolveUser,
            formatDate = formatDate,
            loadPdfPages = loadPdfPages,
        )
        ZillitToast(
            message = notice?.text,
            onDismiss = { notice = null },
            tone = if (notice?.success == false) ZillitToastTone.Danger else ZillitToastTone.Success,
        )
    }

    companion object {
        const val CONTINUITY_PATH = "/film-tools/continuity"
    }
}
