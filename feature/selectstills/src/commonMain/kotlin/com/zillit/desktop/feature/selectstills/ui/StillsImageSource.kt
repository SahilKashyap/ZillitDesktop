package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Fetches a signed image by link. One seam for the whole tool: thumbnails,
 * previews, face crops and headshots all come from the production's storage
 * with a link the service signed, so none of them rides the Zillit client.
 *
 * Null when the link could not be fetched or the bytes are not an image the
 * platform can draw — the caller then shows its own placeholder rather than
 * an empty frame.
 */
fun interface StillsImageSource {
    suspend fun bytes(url: String): ByteArray?
}

val LocalStillsImages = staticCompositionLocalOf<StillsImageSource?> { null }

/**
 * One remote image, fetched when its link changes and decoded once.
 *
 * [content] is drawn while there is nothing to show, so a tile can say
 * "processing…" or draw its initials rather than a hole.
 */
@Composable
internal fun SImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    description: String? = null,
    content: @Composable () -> Unit = {},
) {
    val source = LocalStillsImages.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, source) {
        if (url.isBlank() || source == null) return@LaunchedEffect
        bitmap = source.bytes(url)?.let(::decodeStillBitmap)
    }
    val shown = bitmap
    Box(modifier.background(StillsTheme.c.panel2), contentAlignment = Alignment.Center) {
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            content()
        }
    }
}

/** The image's own pixel size once it loaded, for the lightbox's fit. */
@Composable
internal fun rememberStillBitmap(url: String): ImageBitmap? {
    val source = LocalStillsImages.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, source) {
        if (url.isBlank() || source == null) return@LaunchedEffect
        bitmap = source.bytes(url)?.let(::decodeStillBitmap)
    }
    return bitmap
}
