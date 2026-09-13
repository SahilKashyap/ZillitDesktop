package com.zillit.desktop.feature.productionreport.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import com.zillit.desktop.core.designsystem.component.ZillitAvatar

/**
 * Faces for names: the host's avatar loader, remembered per user for the
 * life of the screen so a list of forty rows asks once per person.
 */
internal class ReportFaces(val load: suspend (String) -> ImageBitmap?) {
    val cache = mutableStateMapOf<String, ImageBitmap?>()
}

internal val LocalReportFaces = staticCompositionLocalOf { ReportFaces { null } }

@Composable
internal fun ProvideReportFaces(load: suspend (String) -> ImageBitmap?, content: @Composable () -> Unit) {
    val faces = remember(load) { ReportFaces(load) }
    CompositionLocalProvider(LocalReportFaces provides faces, content = content)
}

/** A person's avatar: their photo when the host has one, their initials otherwise. */
@Composable
internal fun Face(userId: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val faces = LocalReportFaces.current
    val id = userId.orEmpty()
    if (id.isNotBlank() && !faces.cache.containsKey(id)) {
        LaunchedEffect(id) { faces.cache[id] = runCatching { faces.load(id) }.getOrNull() }
    }
    ZillitAvatar(name = name.ifBlank { "?" }, size = size, image = faces.cache[id], modifier = modifier)
}
