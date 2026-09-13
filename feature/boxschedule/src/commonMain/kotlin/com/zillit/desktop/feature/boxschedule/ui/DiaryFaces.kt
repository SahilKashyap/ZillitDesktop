package com.zillit.desktop.feature.boxschedule.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The crew's faces, loaded by user id — a composition local because people
 * are drawn deep inside chips, pickers and history rows. Null draws initials.
 */
internal val LocalDiaryFaces = staticCompositionLocalOf<(suspend (String) -> ImageBitmap?)?> { null }

@Composable
internal fun ProvideDiaryFaces(load: (suspend (String) -> ImageBitmap?)?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDiaryFaces provides load, content = content)
}

/** One person's face, once it has loaded. */
@Composable
internal fun rememberDiaryFace(userId: String?): ImageBitmap? {
    val load = LocalDiaryFaces.current
    var face by remember(userId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(userId, load) {
        if (load != null && !userId.isNullOrBlank()) face = runCatching { load(userId) }.getOrNull()
    }
    return face
}
