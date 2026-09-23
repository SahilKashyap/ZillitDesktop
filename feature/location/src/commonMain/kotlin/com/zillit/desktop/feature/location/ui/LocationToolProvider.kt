package com.zillit.desktop.feature.location.ui

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
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.domain.PickedLocationFile
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** The location library as a workspace tool, at the web's path (`/film-tools/location`). */
class LocationToolProvider(
    private val viewModel: LocationViewModel,
    /** Shows an image/video picker; a null answer means "cancelled". */
    private val onPickFile: (onPicked: (PickedLocationFile?) -> Unit) -> Unit,
    /** A stored image decoded — the thumbnail for tiles, the full file for the viewer. */
    private val loadImage: suspend (MediaAttachment, preview: Boolean) -> ImageBitmap?,
    private val resolveUser: (String) -> String?,
) : ToolProvider {

    override val path: String = LOCATION_PATH
    override val title: String get() = str(S.location)
    override val icon = ZillitToolIcons.Location
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is LocationEffect.Notice -> notice = effect.text
                    LocationEffect.PickFile -> onPickFile { picked ->
                        if (picked != null) viewModel.onEvent(LocationEvent.FilePicked(picked))
                    }
                }
            }
        }

        LocationScreen(state = state, onEvent = viewModel::onEvent, loadImage = loadImage, resolveUser = resolveUser)
        ZillitErrorToast(message = notice, onDismiss = { notice = null })
    }

    companion object {
        const val LOCATION_PATH = "/film-tools/location"
    }
}
