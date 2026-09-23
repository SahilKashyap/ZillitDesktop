package com.zillit.desktop.feature.sides.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.sides.ui.components.LocalSidesFaces

/**
 * Sides as a workspace tool, at the web's path (`/film-tools/sides`).
 */
class SidesToolProvider(
    private val viewModel: SidesViewModel,
    /** Shows a file picker (PDF, or PDF/.fdx); a null answer means "cancelled". */
    private val onPickFile: (pdfOnly: Boolean, onPicked: (PickedDoc?) -> Unit) -> Unit,
    /** Opens a signed URL in the system browser (https only). */
    private val onOpenUrl: (String) -> Unit,
    /** Offers fetched bytes to save under a suggested name. */
    private val onSaveFile: (fileName: String, bytes: ByteArray) -> Unit,
    /** A crew member's profile picture, or null for initials. */
    private val loadAvatar: suspend (String) -> ImageBitmap? = { null },
) : ToolProvider {

    override val path: String = SIDES_PATH
    override val title: String get() = str(S.txt_sides)
    override val icon = ZillitToolIcons.ScriptNote
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<Pair<String, ZillitToastTone>?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SidesEffect.Notice -> notice = effect.message to effect.tone
                    is SidesEffect.PickFile -> onPickFile(effect.pdfOnly) { picked ->
                        viewModel.onEvent(SidesEvent.FilePicked(effect.purpose, picked))
                    }
                    is SidesEffect.OpenUrl -> onOpenUrl(effect.url)
                    is SidesEffect.SaveFile -> onSaveFile(effect.fileName, effect.bytes)
                }
            }
        }

        CompositionLocalProvider(LocalSidesFaces provides loadAvatar) {
            SidesScreen(state = state, onEvent = viewModel::onEvent)
        }
        ZillitToast(
            message = notice?.first,
            onDismiss = { notice = null },
            tone = notice?.second ?: ZillitToastTone.Success,
        )
    }
}

const val SIDES_PATH = "/film-tools/sides"
