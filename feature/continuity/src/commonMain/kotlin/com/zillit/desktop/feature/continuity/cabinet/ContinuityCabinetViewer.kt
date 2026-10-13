package com.zillit.desktop.feature.continuity.cabinet

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
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.ui.ContinuityEffect
import com.zillit.desktop.feature.continuity.ui.ContinuityScreen
import com.zillit.desktop.feature.continuity.ui.ContinuityViewModel

/**
 * The File Cabinet's Continuity row: the web opens `<Continuity
 * isContintuityFileCabinet />` in a modal (`FileCabinateModal.jsx`), which is
 * the tool's own two boards (My Department, All Departments) with the page
 * header, the search card, the back arrow and the paperclip taken away.
 *
 * Nothing in that path changes a request — the flag never reaches
 * `continuityApi.js` — so this reads exactly what the tool reads; the
 * read-only mode of [ContinuityViewModel] and [ContinuityScreen] is what
 * keeps it a look: no upload, edit, forward or delete, no read receipts.
 * Viewing, opening and downloading stay.
 *
 * [viewModel] is the cabinet's own, so the folder open here is not the
 * tool window's.
 */
class ContinuityCabinetViewer(
    private val viewModel: ContinuityViewModel,
    private val loadImage: suspend (ContinuityAttachment, preview: Boolean) -> ImageBitmap?,
    private val resolveUser: (String) -> String?,
    private val formatDate: (Long) -> String,
    private val loadPdfPages: suspend (ContinuityAttachment) -> List<ImageBitmap>? = { null },
) : CabinetViewer {

    override val identifier: String = IDENTIFIER

    @Composable
    override fun Content(entry: CabinetEntry?, modifier: Modifier) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<ContinuityEffect.Notice?>(null) }

        LaunchedEffect(viewModel) { viewModel.startReadOnly() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect -> if (effect is ContinuityEffect.Notice) notice = effect }
        }

        Box(modifier) {
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
    }

    companion object {
        /** The cabinet row's identifier (`FileCabinateModal.jsx:414`). */
        const val IDENTIFIER = "continuity"
    }
}
