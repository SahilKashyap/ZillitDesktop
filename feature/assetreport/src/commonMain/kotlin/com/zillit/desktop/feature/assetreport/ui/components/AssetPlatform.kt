package com.zillit.desktop.feature.assetreport.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.feature.assetreport.domain.PickedAssetFile

/**
 * A picture's bytes as a bitmap no longer than [maxEdge] pixels on its long
 * side — a thumbnail of a 12 MP photo must not hold 48 MB of pixels per tile.
 * Null for anything this platform cannot decode; the tile then shows the file's
 * badge, as the web's does.
 */
internal expect fun decodeAssetImage(bytes: ByteArray, maxEdge: Int): ImageBitmap?

/**
 * A file as pages to look at: an image is one, a PDF its pages rendered. The
 * web hands the file to an `<iframe>`; a Compose window has no such viewer.
 * Empty for anything that will not open.
 */
internal expect fun decodeAssetPages(bytes: ByteArray): List<ImageBitmap>

/**
 * Files dragged in from the OS. Each is read at once — except one past the
 * size limit, which is only named, so a stray video never lands in memory.
 */
@Composable
internal expect fun Modifier.assetFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onDrop: (files: List<PickedAssetFile>, tooLarge: List<Pair<String, Long>>) -> Unit,
): Modifier
