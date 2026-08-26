package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.calls.domain.ShareSource
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.SharePicker
import com.zillit.desktop.feature.calls.ui.ShareSourcePicker
import com.zillit.desktop.feature.home.ui.decodeImageBitmap

/**
 * The "choose what to share" dialog, in a window of its own.
 *
 * The window is the point. The call's picture is a heavyweight browser surface
 * that paints over every Compose layer inside its rectangle, so a dialog
 * composed into the call window would be hidden precisely where it needs to be
 * seen — the same trap that made the settings menu invisible and put the
 * reactions layer behind the video. Its own top-level window is its own
 * NSWindow, and nothing Chromium draws can reach it.
 */
@Composable
internal fun ShareSourceWindow(picker: SharePicker, onEvent: (CallEvent) -> Unit) {
    val state = rememberDialogState(size = DpSize(WIDTH, HEIGHT))
    DialogWindow(
        onCloseRequest = { onEvent(CallEvent.DismissSharePicker) },
        state = state,
        title = "Share your screen",
    ) {
        // Previews arrive as base64 PNG and are decoded once each. Keyed on
        // the string itself rather than the source id: a re-emitted list is a
        // new list of value objects, and keying on identity would decode every
        // tile again each time one preview lands.
        val decoded = remember { mutableMapOf<String, ImageBitmap?>() }
        val preview: (ShareSource) -> ImageBitmap? = { source ->
            source.previewPng.takeIf { it.isNotBlank() }?.let { encoded ->
                decoded.getOrPut(encoded) {
                    runCatching { decodeImageBitmap(java.util.Base64.getDecoder().decode(encoded)) }
                        .getOrNull()
                }
            }
        }
        ZillitTheme {
            ShareSourcePicker(
                picker = picker,
                preview = preview,
                onChoose = { id -> onEvent(CallEvent.ChooseShareSource(id)) },
                onShare = { onEvent(CallEvent.ConfirmShareSource) },
                onCancel = { onEvent(CallEvent.DismissSharePicker) },
            )
        }
    }
}

private val WIDTH = 720.dp
private val HEIGHT = 560.dp
