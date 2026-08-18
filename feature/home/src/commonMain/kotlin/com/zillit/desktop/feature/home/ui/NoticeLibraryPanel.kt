package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.home.domain.LibraryEntry
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeLibrary
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import com.zillit.desktop.feature.home.domain.asWebHref
import com.zillit.desktop.feature.home.domain.formatFileSize
import com.zillit.desktop.feature.home.domain.toDateTimeLabel

/**
 * The unit's library — Android's *Gallery* (`HomeChatLibraryActivity`):
 * three tabs, Media / Docs / Links, over the posts on the board, newest
 * first. A picture opens in the lightbox, a video or document saves and
 * opens like its bubble does, a link opens in the browser.
 */
@Composable
internal fun NoticeLibraryPanel(
    visible: Boolean,
    unitLabel: String?,
    library: NoticeLibrary,
    media: NoticeMediaSource?,
    resolveAuthor: (String?) -> String?,
    onPreview: (NoticeAttachment) -> Unit,
    onOpen: (noticeId: String, NoticeAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember(visible) { mutableStateOf(LibraryTab.Media) }

    ZillitDialogShell(
        title = unitLabel?.takeIf { it.isNotBlank() } ?: "Media, docs & links",
        subtitle = "What has been shared on this board.",
        icon = ZillitIcons.Photo,
        visible = visible,
        onDismiss = onDismiss,
        width = LIBRARY_WIDTH,
        scrollable = false,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            LibraryTabPill("Media", library.media.size, tab == LibraryTab.Media) { tab = LibraryTab.Media }
            LibraryTabPill("Docs", library.docs.size, tab == LibraryTab.Docs) { tab = LibraryTab.Docs }
            LibraryTabPill("Links", library.links.size, tab == LibraryTab.Links) { tab = LibraryTab.Links }
        }

        Box(Modifier.fillMaxWidth().heightIn(min = LIBRARY_MIN_HEIGHT, max = LIBRARY_MAX_HEIGHT)) {
            when (tab) {
                LibraryTab.Media -> MediaTab(library.media, media, onPreview, onOpen)
                LibraryTab.Docs -> DocsTab(library.docs, onOpen)
                LibraryTab.Links -> LinksTab(library.links, resolveAuthor, onOpenLink)
            }
        }
    }
}

private enum class LibraryTab { Media, Docs, Links }

/** A count-carrying pill; the selected one wears the accent. */
@Composable
private fun LibraryTabPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(if (selected) ZillitTheme.colors.accentSoft else ZillitTheme.colors.canvas)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = if (selected) ZillitTheme.colors.accent else ZillitTheme.colors.textPrimary,
        )
        ZillitText(
            text = count.toString(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun EmptyTab(text: String) {
    Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

/** Square tiles, three across; a video wears the play badge like its bubble. */
@Composable
private fun MediaTab(
    entries: List<LibraryEntry.Media>,
    media: NoticeMediaSource?,
    onPreview: (NoticeAttachment) -> Unit,
    onOpen: (noticeId: String, NoticeAttachment) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyTab("No photos or videos on this board yet.")
        return
    }
    val grid = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(MEDIA_COLUMNS),
        state = grid,
        modifier = Modifier.fillMaxSize().then(rememberWheelScroll(grid)),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        items(entries, key = { it.notice.id }) { entry ->
            MediaTile(
                entry = entry,
                media = media,
                onClick = {
                    if (entry.isVideo) onOpen(entry.notice.id, entry.attachment) else onPreview(entry.attachment)
                },
            )
        }
    }
}

@Composable
private fun MediaTile(entry: LibraryEntry.Media, media: NoticeMediaSource?, onClick: () -> Unit) {
    val image by rememberAttachmentImage(entry.attachment, media, preview = true)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(ZillitTheme.shapes.small)
            .background(ZillitTheme.colors.surfaceSunken)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (val ready = image) {
            is AttachmentImage.Ready -> Image(
                bitmap = ready.bitmap,
                contentDescription = entry.attachment.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            else -> ZillitIcon(
                icon = if (entry.isVideo) ZillitIcons.Play else ZillitIcons.Photo,
                contentDescription = if (entry.isVideo) "Video" else "Photo",
                tint = ZillitTheme.colors.textMuted,
                size = TILE_GLYPH,
            )
        }
        if (entry.isVideo && image is AttachmentImage.Ready) {
            Box(
                modifier = Modifier
                    .size(TILE_BADGE)
                    .clip(ZillitTheme.shapes.small)
                    .background(ZillitTheme.colors.canvas.copy(alpha = BADGE_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.Play, contentDescription = "Video", size = BADGE_GLYPH)
            }
        }
    }
}

/** One row per document — the file's family tile, name, size and date. */
@Composable
private fun DocsTab(entries: List<LibraryEntry.Document>, onOpen: (noticeId: String, NoticeAttachment) -> Unit) {
    if (entries.isEmpty()) {
        EmptyTab("No documents on this board yet.")
        return
    }
    val list = rememberLazyListState()
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().then(rememberWheelScroll(list))) {
        items(entries, key = { it.notice.id }) { entry ->
            val kind = fileKindOf(entry.attachment.fileName, entry.attachment.contentSubtype)
            LibraryRow(
                icon = {
                    ZillitIcon(
                        icon = kind.icon,
                        contentDescription = kind.label,
                        tint = kind.hue.colour(),
                        size = ROW_ICON,
                    )
                },
                title = entry.attachment.fileName.ifBlank { kind.label },
                detail = listOfNotNull(
                    kind.label.takeIf { entry.attachment.fileName.isNotBlank() },
                    formatFileSize(entry.attachment.sizeBytes).takeIf { it.isNotEmpty() },
                    entry.notice.createdAtMillis.toDateTimeLabel().takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                onClick = { onOpen(entry.notice.id, entry.attachment) },
            )
        }
    }
}

/** One row per link — the address, who posted it, and when. */
@Composable
private fun LinksTab(
    entries: List<LibraryEntry.Link>,
    resolveAuthor: (String?) -> String?,
    onOpenLink: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyTab("No links on this board yet.")
        return
    }
    val list = rememberLazyListState()
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().then(rememberWheelScroll(list))) {
        items(entries, key = { it.notice.id }) { entry ->
            val author = resolveAuthor(entry.notice.authorId) ?: entry.notice.authorName
            LibraryRow(
                icon = {
                    ZillitIcon(
                        icon = ZillitIcons.ArrowRight,
                        contentDescription = "Link",
                        tint = ZillitTheme.colors.accent,
                        size = ROW_ICON,
                    )
                },
                title = entry.url,
                detail = listOf(author, entry.notice.createdAtMillis.toDateTimeLabel())
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                onClick = { onOpenLink(entry.url.asWebHref()) },
            )
        }
    }
}

@Composable
private fun LibraryRow(icon: @Composable () -> Unit, title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(ROW_TILE)
                .clip(ZillitTheme.shapes.small)
                .background(ZillitTheme.colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = title,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
            )
            if (detail.isNotEmpty()) {
                ZillitText(
                    text = detail,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

private val LIBRARY_WIDTH = 480.dp
private val LIBRARY_MIN_HEIGHT = 160.dp
private val LIBRARY_MAX_HEIGHT = 420.dp
private const val MEDIA_COLUMNS = 3
private val TILE_GLYPH = 28.dp
private val TILE_BADGE = 28.dp
private val BADGE_GLYPH = 14.dp
private val ROW_TILE = 32.dp
private val ROW_ICON = 16.dp
private const val BADGE_ALPHA = 0.7f
