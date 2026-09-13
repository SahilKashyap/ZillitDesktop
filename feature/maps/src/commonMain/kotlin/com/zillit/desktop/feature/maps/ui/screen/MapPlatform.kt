package com.zillit.desktop.feature.maps.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.feature.maps.domain.PickedPhoto

/**
 * Accepts image files dragged in from the desktop — the web's photo drop
 * zone. [onDrop] gets what was taken and the names of anything turned away
 * (not an image).
 */
@Composable
internal expect fun Modifier.photoDrop(
    onHover: (Boolean) -> Unit,
    onDrop: (photos: List<PickedPhoto>, refused: List<String>) -> Unit,
): Modifier

/** Decodes a picture, scaled so its long edge is at most [maxEdge] pixels; null when it is not one. */
expect fun decodeMapImage(bytes: ByteArray, maxEdge: Int): ImageBitmap?

/** The image extensions the photo pickers take — the web's `accept`, HEIC and HEIF included. */
val PHOTO_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
