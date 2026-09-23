package com.zillit.desktop.feature.assetreport.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.assetreport.ui.components.swallowPresses
import com.zillit.desktop.feature.assetreport.ui.pages.AssetDetailPage
import com.zillit.desktop.feature.assetreport.ui.pages.AssetRegisterPage
import com.zillit.desktop.feature.assetreport.ui.pages.AttachmentViewer
import com.zillit.desktop.feature.assetreport.ui.pages.UnsavedChangesDialog

/**
 * The Asset Register: the register, and one asset's detail laid over it —
 * over, not instead, so the list keeps its scroll and filters underneath, as
 * the web's overlay does.
 */
@Composable
fun AssetScreen(
    state: AssetUiState,
    onEvent: (AssetEvent) -> Unit,
    modifier: Modifier = Modifier,
    media: AssetMediaLoader = NoMedia,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (state.viewer.isBlocked) {
            ZillitText(
                text = str(S.desktop_asset_no_access),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        AssetRegisterPage(state, onEvent)

        // Held through the fade-out, so the page does not blank as it leaves.
        val held = remember { arrayOfNulls<AssetDetail>(1) }
        state.detail?.let { held[0] = it }
        AnimatedVisibility(
            visible = state.detail != null,
            enter = fadeIn(tween(DETAIL_IN_MILLIS)),
            exit = fadeOut(tween(DETAIL_OUT_MILLIS)),
        ) {
            val detail = state.detail ?: held[0]
            if (detail != null) {
                // Takes the presses that land on the page's empty space, so none reach the table beneath.
                Box(Modifier.fillMaxSize().swallowPresses()) {
                    AssetDetailPage(detail = detail, state = state, media = media, onEvent = onEvent)
                }
            }
        }

        UnsavedChangesDialog(state.detail, onEvent)
        AttachmentViewer(
            file = state.detail?.viewingFile,
            media = media,
            onClose = { onEvent(AssetEvent.CloseViewer) },
            onDownload = { onEvent(AssetEvent.DownloadViewed) },
        )
    }
}

private val NoMedia = AssetMediaLoader {
    ZillitResult.Failure(ZillitError.Storage(userMessage = str(S.desktop_files_unavailable)))
}

private const val DETAIL_IN_MILLIS = 300
private const val DETAIL_OUT_MILLIS = 180
