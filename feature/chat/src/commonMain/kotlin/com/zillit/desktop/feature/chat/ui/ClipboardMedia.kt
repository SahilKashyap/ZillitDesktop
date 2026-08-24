package com.zillit.desktop.feature.chat.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The system clipboard's picture half, as chat needs it: Cmd+V in the
 * composer attaches the copied screenshot, and "Copy image" on a received
 * picture puts it back. A seam rather than a direct AWT call so the view
 * model and the render tests can fake a clipboard without owning a display.
 */
interface ClipboardMediaSource {
    /** The image on the clipboard as an encoded file, or null when it holds none. */
    fun readImage(): ClipboardImage?

    /** Puts [image] on the clipboard as a picture; false when the platform refused. */
    fun writeImage(image: ImageBitmap): Boolean
}

/** What a paste yields: PNG bytes with a name the bubble and the wire can wear. */
class ClipboardImage(
    val name: String,
    val bytes: ByteArray,
    val contentType: String = "image/png",
)

/**
 * The desktop's own clipboard, or null where the platform has none (tests,
 * headless CI). Composer paste and "Copy image" both no-op on null.
 */
expect fun systemClipboardMedia(): ClipboardMediaSource?
