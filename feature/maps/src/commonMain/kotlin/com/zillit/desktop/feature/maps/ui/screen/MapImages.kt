package com.zillit.desktop.feature.maps.ui.screen

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
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.PickedPhoto

/**
 * The pictures the tool shows but does not fetch itself: stored photos, the
 * zone previews, and photos just picked. Provided once by the tool; screens
 * and tests without it show placeholders.
 */
interface MapImages {
    suspend fun photo(attachment: MapAttachment, preview: Boolean): ImageBitmap?
    suspend fun zonePreview(centre: LatLng, radiusMiles: Double): ImageBitmap?
    fun decode(photo: PickedPhoto): ImageBitmap?

    object None : MapImages {
        override suspend fun photo(attachment: MapAttachment, preview: Boolean): ImageBitmap? = null
        override suspend fun zonePreview(centre: LatLng, radiusMiles: Double): ImageBitmap? = null
        override fun decode(photo: PickedPhoto): ImageBitmap? = null
    }
}

val LocalMapImages = staticCompositionLocalOf<MapImages> { MapImages.None }

/** A picture that arrives later: a spinner while it loads, a glyph if it never does. */
@Composable
internal fun AsyncPicture(
    key: Any,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    load: suspend MapImages.() -> ImageBitmap?,
) {
    val images = LocalMapImages.current
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var done by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        bitmap = runCatching { images.load() }.getOrNull()
        done = true
    }
    Box(modifier.background(ZillitTheme.colors.surfaceSunken), contentAlignment = Alignment.Center) {
        val shown = bitmap
        when {
            shown != null -> Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
            !done -> ZillitSpinner(size = 18.dp)
            else -> ZillitIcon(icon = ZillitIcons.Photo, tint = ZillitTheme.colors.textMuted, size = 20.dp)
        }
    }
}
