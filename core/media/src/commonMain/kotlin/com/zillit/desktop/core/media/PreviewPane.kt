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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The preview's top bar, WhatsApp's: close at the left; a picture's tools in
 * the middle — rotate, crop, draw, text — or, for a file, its name over its
 * page count or size; and remove at the right while more than one item is
 * selected (Android's `binding.delete.isVisible = size > 1`).
 */
@Composable
internal fun PreviewTopBar(session: MediaPreviewSession, measurer: TextMeasurer, onClose: () -> Unit) {
    val item = session.current ?: return
    Row(
        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.close),
            onClick = onClose,
            tint = ZillitTheme.colors.textPrimary,
            size = BAR_BUTTON,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val edit = session.currentEdit
            if (edit != null) {
                ImageTools(session, edit, measurer)
            } else {
                FileHeading(item, session.posters[item])
            }
        }
        if (session.items.size > 1) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = str(S.remove),
                onClick = session::removeCurrent,
                tint = ZillitTheme.colors.textPrimary,
                size = BAR_BUTTON,
            )
        } else {
            Spacer(Modifier.size(BAR_BUTTON))
        }
    }
}

/** Rotate, then the three tools of `EditImageActivity`'s menu; the open one lit. */
@Composable
private fun ImageTools(session: MediaPreviewSession, edit: ImageEditState, measurer: TextMeasurer) {
    val loaded = edit.working != null
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ToolGlyph(ZillitIcons.Reload, str(S.desktop_rotate), selected = false, enabled = loaded) {
            edit.rotate(measurer)
        }
        ToolGlyph(CropGlyph, str(S.desktop_media_crop), session.tool == EditTool.Crop, loaded) {
            session.tool = EditTool.Crop
        }
        ToolGlyph(ZillitIcons.Edit, str(S.docusign_create_tab_draw), session.tool == EditTool.Draw, loaded) {
            session.tool = EditTool.Draw
        }
        TextToolGlyph(selected = session.tool == EditTool.Text, enabled = loaded) { session.tool = EditTool.Text }
    }
}

@Composable
private fun ToolGlyph(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) ZillitTheme.colors.accentSoft else Color.Transparent),
    ) {
        ZillitIconButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            enabled = enabled,
            tint = if (selected) ZillitTheme.colors.accentText else ZillitTheme.colors.textPrimary,
            size = BAR_BUTTON,
        )
    }
}

/** WhatsApp's "Aa" for the text tool — a glyph no icon set draws as well as the letters do. */
@Composable
private fun TextToolGlyph(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(BAR_BUTTON)
            .clip(CircleShape)
            .background(if (selected) ZillitTheme.colors.accentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = str(S.docusign_field_text), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = "Aa",
            style = ZillitTheme.typography.titleMedium,
            color = when {
                !enabled -> ZillitTheme.colors.textDisabled
                selected -> ZillitTheme.colors.accentText
                else -> ZillitTheme.colors.textPrimary
            },
        )
    }
}

/** A file's name, and under it its page count (a PDF) or its kind and size. */
@Composable
private fun FileHeading(item: PreviewItem, poster: PreviewPoster?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitText(text = item.name, style = ZillitTheme.typography.titleSmall, maxLines = 1)
        ZillitText(
            text = poster?.pages?.let(::pageCount) ?: "${item.kind.label} · ${formatBytes(item.bytes.size.toLong())}",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

private fun pageCount(pages: Int): String =
    if (pages == 1) str(S.desktop_dm_one_page) else str(S.desktop_dm_n_pages, pages)

/** The current item, large: a picture with its edits, or the file as WhatsApp shows it. */
@Composable
internal fun ItemStage(session: MediaPreviewSession, measurer: TextMeasurer, modifier: Modifier = Modifier) {
    val item = session.current ?: return
    val edit = session.currentEdit
    if (edit != null) {
        EditableImageCanvas(edit, session.tool, modifier, measurer)
    } else {
        FileStage(item, session.posterBitmap(item), modifier)
    }
}

/**
 * Anything that is not an editable picture. A poster — a PDF's first page on
 * its white sheet, a clip's frame with a play mark — fills the stage; without
 * one, the file's badge with its name and size under it. No tools: Android's
 * editor is images-only.
 */
@Composable
private fun FileStage(item: PreviewItem, poster: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (poster != null) {
            // As large as the stage allows at the page's own shape, so the
            // rounded corners sit on the page and not on letterboxing.
            Image(
                bitmap = poster,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .aspectRatio(poster.width.toFloat() / poster.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(STAGE_CORNER)),
            )
            if (item.kind == PreviewKind.Video) PlayMark(PLAY_DISC)
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                when (item.kind) {
                    PreviewKind.Video, PreviewKind.Audio -> ZillitIcon(
                        icon = item.kind.icon,
                        contentDescription = null,
                        tint = ZillitTheme.colors.textSecondary,
                        size = STAGE_GLYPH,
                    )
                    else -> ZillitFileBadge(fileName = item.name, size = STAGE_BADGE)
                }
                ZillitText(text = item.name, style = ZillitTheme.typography.titleSmall, maxLines = 1)
                ZillitText(
                    text = "${item.kind.label} · ${formatBytes(item.bytes.size.toLong())}",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun PlayMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(PLAY_SCRIM),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.Play, contentDescription = null, tint = Color.White, size = size / 2)
    }
}

/**
 * The strip along the bottom, WhatsApp's: every selected item as a tile,
 * the current one ringed in the accent, and the "+" that adds more — a menu
 * of kinds where the host offers several, straight to the picker where one.
 */
@Composable
internal fun ThumbnailStrip(
    session: MediaPreviewSession,
    onAddMore: ((PreviewKind) -> Unit)?,
    addKinds: List<PreviewKind>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        session.items.forEachIndexed { index, item ->
            Thumbnail(session, item, selected = index == session.index) { session.select(index) }
        }
        if (onAddMore != null && addKinds.isNotEmpty()) AddTile(onAddMore, addKinds)
    }
}

@Composable
private fun Thumbnail(session: MediaPreviewSession, item: PreviewItem, selected: Boolean, onClick: () -> Unit) {
    val tile: ImageBitmap? = when {
        session.isEditable(item) -> session.editFor(item).working
        else -> session.posterBitmap(item)
    }
    Box(
        modifier = Modifier
            .size(TILE)
            .clip(RoundedCornerShape(TILE_CORNER))
            .background(ZillitTheme.colors.surfaceRaised)
            .border(
                width = if (selected) TILE_RING_SELECTED else TILE_RING,
                color = if (selected) ZillitTheme.colors.accent else ZillitTheme.colors.border,
                shape = RoundedCornerShape(TILE_CORNER),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            tile != null -> {
                Image(
                    tile,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (item.kind == PreviewKind.Video) PlayMark(TILE_PLAY)
            }
            // A clip or a recording with no frame: its kind's glyph, not an
            // extension badge for a play mark to sit on.
            item.kind == PreviewKind.Video || item.kind == PreviewKind.Audio -> ZillitIcon(
                icon = item.kind.icon,
                contentDescription = item.name,
                tint = ZillitTheme.colors.textSecondary,
                size = TILE_BADGE,
            )
            else -> ZillitFileBadge(fileName = item.name, size = TILE_BADGE)
        }
    }
}

@Composable
private fun AddTile(onAddMore: (PreviewKind) -> Unit, kinds: List<PreviewKind>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(TILE)
                .clip(RoundedCornerShape(TILE_CORNER))
                .border(TILE_RING, ZillitTheme.colors.borderStrong, RoundedCornerShape(TILE_CORNER))
                .clickable(onClickLabel = str(S.desktop_add_more)) {
                    val single = kinds.singleOrNull()
                    if (single != null) onAddMore(single) else open = true
                },
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Add, contentDescription = str(S.desktop_add_more), size = ADD_GLYPH)
        }
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = kinds.map { kind ->
                ZillitMenuEntry.Action(label = kind.label, icon = kind.icon) {
                    open = false
                    onAddMore(kind)
                }
            },
        )
    }
}

/**
 * The round send, WhatsApp's: the accent disc with the arrow, and how many
 * items will go in a white badge on its shoulder.
 */
@Composable
internal fun SendDisc(
    count: Int,
    enabled: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Box(
            modifier = Modifier
                .size(SEND_DISC)
                .clip(CircleShape)
                .background(if (enabled) ZillitTheme.colors.accent else ZillitTheme.colors.surfaceHover)
                .clickable(enabled = enabled && !sending, onClickLabel = str(S.send), onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = ZillitIcons.Send,
                contentDescription = if (sending) str(S.preparing) else str(S.send),
                tint = ZillitTheme.colors.textOnAccent,
                size = SEND_GLYPH,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = BADGE_NUDGE, y = -BADGE_NUDGE)
                .widthIn(min = COUNT_BADGE)
                .size(COUNT_BADGE)
                .clip(CircleShape)
                .background(Color.White)
                .border(BADGE_RING, ZillitTheme.colors.canvas, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(text = count.toString(), style = ZillitTheme.typography.labelSmall, color = Color(0xFF111B21))
        }
    }
}

/** A picture's poster for a tile or the stage: the app's render, or the host's own bytes. */
@Composable
internal fun MediaPreviewSession.posterBitmap(item: PreviewItem): ImageBitmap? {
    val bytes = posters[item]?.jpegBytes ?: item.thumbnailBytes
    return remember(item, bytes) { bytes?.let(::decodeImageBitmap) }
}

/** "2.4 MB" — the same shape every Zillit client prints under a file. */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < KILO -> "$bytes B"
    bytes < MEGA -> "${(bytes * TEN / KILO).toDouble() / TEN} KB"
    bytes < GIGA -> "${(bytes * TEN / MEGA).toDouble() / TEN} MB"
    else -> "${(bytes * TEN / GIGA).toDouble() / TEN} GB"
}

/** Two corner brackets — the crop mark, which the icon set does not draw. */
private val CropGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "Crop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(6f, 2f)
        lineTo(6f, 18f)
        lineTo(22f, 18f)
        moveTo(2f, 6f)
        lineTo(18f, 6f)
        lineTo(18f, 22f)
    }.build()
}

private const val KILO = 1024L
private const val MEGA = KILO * KILO
private const val GIGA = MEGA * KILO
private const val TEN = 10L
private val BAR_BUTTON = 40.dp
private val STAGE_CORNER = 6.dp
private val STAGE_GLYPH = 96.dp
private val STAGE_BADGE = 96.dp
private val PLAY_DISC = 64.dp
private val PLAY_SCRIM = Color(0x8C000000)
private val TILE = 64.dp
private val TILE_CORNER = 6.dp
private val TILE_BADGE = 30.dp
private val TILE_PLAY = 22.dp
private val TILE_RING = 1.dp
private val TILE_RING_SELECTED = 3.dp
private val ADD_GLYPH = 26.dp
private val SEND_DISC = 60.dp
private val SEND_GLYPH = 26.dp
private val COUNT_BADGE = 24.dp
private val BADGE_NUDGE = 4.dp
private val BADGE_RING = 2.dp
