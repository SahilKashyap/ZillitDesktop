package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.ui.LocalDocThumbnails

/**
 * A document's cover picture, falling back to what the caller passes — the
 * file-type icon on a card (the web's `DocThumb`).
 *
 * A document with no cover (Word, Excel, a PDF uploaded before covers existed)
 * and one whose picture cannot be fetched or decoded both keep the icon, so a
 * missing picture is never a hole in the grid.
 */
@Composable
internal fun DocCover(document: LibraryDocument, fallback: @Composable () -> Unit) {
    val source = LocalDocThumbnails.current
    val key = document.thumbnail?.key
    val picture: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, key1 = key) {
        value = if (key == null) null else source.image(document)
    }
    val shown = picture
    if (shown == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() }
        return
    }
    // The page is white whatever the theme, so it gets its own edge rather than melting into a light card.
    Box(
        Modifier.fillMaxSize()
            .clip(ZillitTheme.shapes.medium)
            .background(Color.White)
            .border(0.5.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium),
    ) {
        Image(
            bitmap = shown,
            contentDescription = document.name,
            modifier = Modifier.fillMaxSize(),
            // Page 1 from the top: the title block is what identifies a document.
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
        )
    }
}
