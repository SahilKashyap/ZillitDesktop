package com.zillit.desktop.feature.pagedistribution.ui.dod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The crew photo loader — the web's `ProfilePicture` on every D.O.D card
 * and every counts row.
 *
 * A composition local rather than a parameter because the faces sit inside
 * list rows inside dialogs; threading a suspend function through each
 * signature to reach them would be most of the diff. Null by default, so a
 * screen composed without a host (tests) draws initials.
 */
val LocalDodFaces: ProvidableCompositionLocal<suspend (String) -> ImageBitmap?> =
    staticCompositionLocalOf { { _: String -> null } }

/** Puts [load] in reach of every person drawn below it. */
@Composable
fun ProvideDodFaces(load: suspend (String) -> ImageBitmap?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDodFaces provides load, content = content)
}

/**
 * The photo for [userId], or null while it loads and for anyone without one.
 * Keyed on the id so a recycled row re-reads rather than showing the
 * previous occupant's face; the host's loader caches.
 */
@Composable
internal fun rememberDodFace(userId: String?): ImageBitmap? {
    val load = LocalDodFaces.current
    return produceState<ImageBitmap?>(initialValue = null, userId) {
        value = userId?.takeIf { it.isNotBlank() }?.let { load(it) }
    }.value
}
