package com.zillit.desktop.feature.chat.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.feature.chat.domain.ChatAttachment

/**
 * Host seams the thread pane needs but [ChatScreen]'s parameter list does not
 * carry — the download right, the clipboard, the full-size image fetch.
 *
 * A CompositionLocal rather than three more ThreadPane parameters because the
 * pane is composed *through* ChatScreen, which is being edited concurrently
 * by another session: threading new parameters through it would collide,
 * while a local provided by [ChatToolProvider] flows past it untouched.
 */
class ChatSeams(
    /**
     * The viewer's download right on the C&C tool — `ProjectPermissions`'
     * `canDownload`, wired by the host. Every save-to-disk path checks it:
     * the lightbox's Download, the menu's Download, a file chip's open.
     * Without it the refusal is Android's own sentence (`msg_download_right`,
     * `res/values/strings.xml:7884`).
     */
    val canDownload: () -> Boolean = { true },
    /** The system clipboard's picture half; null pastes nothing and hides Copy image. */
    val clipboard: ClipboardMediaSource? = null,
    /**
     * The full object, for the lightbox — the thumbnail seam fetches the
     * poster (`preview = true`), which is not the "let me read it" size.
     * Null falls back to the thumbnail.
     */
    val loadFullImage: (suspend (ChatAttachment) -> ImageBitmap?)? = null,
)

internal val LocalChatSeams = staticCompositionLocalOf { ChatSeams() }
