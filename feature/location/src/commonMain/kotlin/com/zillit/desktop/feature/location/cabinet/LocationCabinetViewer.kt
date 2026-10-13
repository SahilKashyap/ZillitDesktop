package com.zillit.desktop.feature.location.cabinet

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.feature.location.domain.LocationRepository
import com.zillit.desktop.feature.location.domain.LocationTransfer
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.ui.LocationEffect
import com.zillit.desktop.feature.location.ui.LocationScreen
import com.zillit.desktop.feature.location.ui.LocationViewModel

/**
 * The File Cabinet's `location` row.
 *
 * The web mounts `LocationPage` in a modal with `locationIsFileCabinet`
 * (`FileCabinateModal.jsx:1222`). The row's submenu (Selected / Shortlisted /
 * Published) is commented out there, so the click hands the page no tab and
 * the page opens on its own default — Selected — with all three status tabs
 * still on screen. This does the same: the row opens directly, on the tool's
 * own tabs, folders and galleries, read-only.
 *
 * The cabinet is an admin-only page, so the viewer is built as an admin
 * rather than from the tool's permission grid (which the cabinet's wiring
 * does not have): the only right it spends is download, which an admin
 * inherits in this tool.
 *
 * One view model for the viewer's life — view models here are never
 * cleared, so a fresh one per window would leak its scope; [LocationViewModel.start]
 * resets a reopened window to the folders instead.
 */
class LocationCabinetViewer(
    repository: LocationRepository,
    transfer: LocationTransfer,
    isTelevision: () -> Boolean,
    nowMillis: () -> Long,
    private val loadImage: suspend (MediaAttachment, preview: Boolean) -> ImageBitmap?,
    private val resolveUser: (String) -> String?,
) : CabinetViewer {

    override val identifier: String = "location"

    private val viewModel = LocationViewModel(
        repository = repository,
        transfer = transfer,
        resolveViewer = { LocationViewer(isAdmin = true, isTelevision = isTelevision(), ready = true) },
        nowMillis = nowMillis,
        fileCabinet = true,
    )

    @Composable
    override fun Content(entry: CabinetEntry?, modifier: Modifier) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                if (effect is LocationEffect.Notice) notice = effect.text
            }
        }

        Box(modifier) {
            LocationScreen(
                state = state,
                onEvent = viewModel::onEvent,
                loadImage = loadImage,
                resolveUser = resolveUser,
                readOnly = true,
            )
            ZillitErrorToast(message = notice, onDismiss = { notice = null })
        }
    }
}
