package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Crew photos for the hub — the web's `UserAvatar`, which replaces a person's
 * initials with their profile picture wherever one exists.
 *
 * A composition local rather than a parameter: the people are drawn deep
 * inside level cards, chips and picker rows, and a loader threaded through
 * every page signature to reach them would be most of the diff. The default
 * loads nothing, so previews and render tests draw initials.
 */
val LocalHubFaces: ProvidableCompositionLocal<suspend (String) -> ImageBitmap?> =
    staticCompositionLocalOf { { _: String -> null } }

/** Puts [load] in reach of every person drawn below it. */
@Composable
fun ProvideHubFaces(load: suspend (String) -> ImageBitmap?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHubFaces provides load, content = content)
}

/**
 * The photo for [userId], or null while it loads and for anyone without one.
 *
 * Keyed on the id, so a row reused for somebody else re-reads rather than
 * keeping the previous face; the host's loader caches, so a list of twenty
 * costs twenty cache reads.
 */
@Composable
fun rememberHubFace(userId: String?): ImageBitmap? {
    val load = LocalHubFaces.current
    return produceState<ImageBitmap?>(initialValue = null, userId) {
        value = userId?.takeIf { it.isNotBlank() }?.let { load(it) }
    }.value
}
