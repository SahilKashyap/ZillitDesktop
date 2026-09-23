package com.zillit.desktop.core.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Over the preview: the file's name and size on the left; on the right
 * Edit and Rotate for a picture (Android's `edit` button, images only —
 * `GalleryViewer.kt:761`), and Remove while more than one item is selected
 * (`binding.delete.isVisible = size > 1`, `:779`).
 */
@Composable
internal fun PreviewToolbar(session: MediaPreviewSession, measurer: TextMeasurer) {
    val item = session.current ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(text = item.name, style = ZillitTheme.typography.labelSmall, maxLines = 1)
            ZillitText(
                text = "${item.kind.name} · ${formatBytes(item.bytes.size.toLong())}",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        val edit = session.currentEdit
        if (edit != null) {
            val loaded = edit.working != null
            ZillitButton(
                text = str(S.desktop_rotate),
                onClick = { edit.rotate(measurer) },
                enabled = loaded,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.edit),
                onClick = { session.tool = EditTool.Draw },
                enabled = loaded,
                leadingIcon = ZillitIcons.Edit,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        if (session.items.size > 1) {
            ZillitButton(
                text = str(S.remove),
                onClick = session::removeCurrent,
                leadingIcon = ZillitIcons.Trash,
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
    }
}

/** The current item, large: a picture with its edits, or the file's poster or glyph. */
@Composable
internal fun ItemPreview(session: MediaPreviewSession, item: PreviewItem, modifier: Modifier = Modifier) {
    val edit = session.currentEdit
    if (edit != null) {
        EditableImageCanvas(edit, tool = null, modifier = modifier)
    } else {
        FilePreview(item, modifier)
    }
}

/**
 * Anything that is not an editable picture: the poster frame when the
 * picker made one (videos, PDFs), the extension badge otherwise, with the
 * name and size under it. No tools — Android's editor is images-only.
 */
@Composable
private fun FilePreview(item: PreviewItem, modifier: Modifier = Modifier) {
    val poster = remember(item) { item.thumbnailBytes?.let(::decodeImageBitmap) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FILE_PREVIEW_MIN_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (poster != null) {
            Image(
                bitmap = poster,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().clip(ZillitTheme.shapes.small),
            )
        } else {
            ZillitFileBadge(fileName = item.name, size = FILE_BADGE)
        }
        Spacer(Modifier.size(ZillitTheme.spacing.md))
        ZillitText(text = item.name, style = ZillitTheme.typography.titleSmall, maxLines = 1)
        ZillitText(
            text = "${item.kind.name} · ${formatBytes(item.bytes.size.toLong())}",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * Every selected item as a tile; the current one ringed in the accent —
 * Android's `allRV` strip under the pager. Pictures show their edited
 * state, so a crop is visible in the strip too.
 */
@Composable
internal fun Filmstrip(session: MediaPreviewSession) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        session.items.forEachIndexed { index, item ->
            val selected = index == session.index
            val tile: ImageBitmap? = when {
                session.isEditable(item) -> session.editFor(item).working
                else -> remember(item) { item.thumbnailBytes?.let(::decodeImageBitmap) }
            }
            Box(
                modifier = Modifier
                    .size(TILE)
                    .clip(ZillitTheme.shapes.small)
                    .background(ZillitTheme.colors.surfaceSunken)
                    .border(
                        width = if (selected) TILE_RING_SELECTED else TILE_RING,
                        color = if (selected) ZillitTheme.colors.accent else ZillitTheme.colors.border,
                        shape = ZillitTheme.shapes.small,
                    )
                    .clickable { session.select(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (tile != null) {
                    Image(
                        bitmap = tile,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ZillitFileBadge(fileName = item.name, size = TILE_BADGE)
                }
            }
        }
    }
}

/** "2.4 MB" — the same shape every Zillit client prints under a file. */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < KILO -> "$bytes B"
    bytes < MEGA -> "${(bytes * TEN / KILO).toDouble() / TEN} KB"
    bytes < GIGA -> "${(bytes * TEN / MEGA).toDouble() / TEN} MB"
    else -> "${(bytes * TEN / GIGA).toDouble() / TEN} GB"
}

private const val KILO = 1024L
private const val MEGA = KILO * KILO
private const val GIGA = MEGA * KILO
private const val TEN = 10L
private val FILE_PREVIEW_MIN_HEIGHT = 200.dp
private val FILE_BADGE = 72.dp
private val TILE = 56.dp
private val TILE_BADGE = 28.dp
private val TILE_RING = 1.dp
private val TILE_RING_SELECTED = 2.dp
