// The pieces the board and its dialogs share: cards, posters, chips.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.continuity.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityScene

/** A stored file decoded — the thumbnail for cards, the full image for the viewer. */
typealias LoadImage = suspend (ContinuityAttachment, preview: Boolean) -> ImageBitmap?

/**
 * A row card that lifts on hover — the web's antd `Card.Grid` folder tile
 * and the department rows, one look: a hairline that warms to the accent,
 * a shadow that grows.
 */
@Composable
internal fun HoverCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateDpAsState(if (hovered) HOVER_LIFT else 0.dp, tween(HOVER_MILLIS), label = "lift")
    val elevation by animateDpAsState(
        targetValue = if (hovered) HOVER_SHADOW else REST_SHADOW,
        animationSpec = tween(HOVER_MILLIS),
        label = "shadow",
    )
    val outline by animateColorAsState(
        targetValue = if (selected || hovered) colors.accent else colors.border,
        animationSpec = tween(HOVER_MILLIS),
        label = "outline",
    )
    Box(
        modifier = modifier
            .offset(y = -lift)
            .shadow(elevation, ZillitTheme.shapes.large, clip = false)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(if (selected) 2.dp else 1.dp, outline, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        content()
    }
}

/** The red count in a card's corner — antd's `Badge`, capped at 99+. */
@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = BADGE_SIZE, minHeight = BADGE_SIZE)
            .clip(CircleShape)
            .background(ZillitTheme.colors.danger)
            .padding(horizontal = ZillitTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = if (count > BADGE_CAP) "$BADGE_CAP+" else count.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textOnAccent,
            maxLines = 1,
        )
    }
}

/**
 * A card's picture: the image, a video's poster frame with the play badge,
 * or a document glyph with its name — the web's tile top
 * (`ContinuityModal.jsx:748-800`).
 */
@Composable
internal fun ScenePoster(
    scene: ContinuityScene,
    loadImage: LoadImage,
    modifier: Modifier = Modifier,
    /** The thumbnail (a card) or the full file (the viewer). */
    preview: Boolean = true,
    /** Whole picture within the frame (the viewer) rather than filling it (a card). */
    fit: Boolean = false,
) {
    val colors = ZillitTheme.colors
    val attachment = scene.attachment
    var bitmap by remember(scene.id, attachment?.media, preview) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(scene.id, attachment?.media, preview) { mutableStateOf(false) }
    LaunchedEffect(scene.id, attachment?.media, preview) {
        if (attachment != null && attachment.hasPoster) {
            bitmap = loadImage(attachment, preview)
            failed = bitmap == null
        }
    }
    Box(modifier.background(colors.surfaceSunken), contentAlignment = Alignment.Center) {
        val ready = bitmap
        when {
            ready != null -> Image(
                bitmap = ready,
                contentDescription = scene.attachment?.name ?: scene.notes,
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            attachment == null -> PosterGlyph(ZillitIcons.Photo, "No file")
            attachment.hasPoster && !failed -> ZillitSkeletonBar(Modifier.fillMaxSize(), height = SKELETON_HEIGHT)
            else -> PosterGlyph(attachment)
        }
        if (attachment?.isVideo == true && ready != null) PlayBadge(Modifier.align(Alignment.Center))
    }
}

/** The stand-in for a file with no picture to show: a document, a poster-less video, an image that would not decode. */
@Composable
private fun PosterGlyph(attachment: ContinuityAttachment) = when {
    attachment.isDocument -> PosterGlyph(
        icon = ZillitIcons.File,
        caption = attachment.name.ifBlank { attachment.contentSubtype.uppercase() },
        tag = attachment.contentSubtype.uppercase().ifBlank { "DOC" },
    )
    attachment.isVideo -> PosterGlyph(ZillitIcons.Play, attachment.name.ifBlank { "Video" })
    else -> PosterGlyph(ZillitIcons.Photo, attachment.name.ifBlank { "Image" })
}

/** A glyph on the sunken ground with the file's name under it. */
@Composable
private fun PosterGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, caption: String, tag: String? = null) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.padding(ZillitTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(Modifier.size(GLYPH_TILE)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(ZillitTheme.shapes.large)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = icon, tint = colors.accent, size = GLYPH)
            }
            if (tag != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = TAG_NUDGE, y = TAG_NUDGE)
                        .clip(ZillitTheme.shapes.small)
                        .background(colors.accent)
                        .padding(horizontal = ZillitTheme.spacing.xs, vertical = 1.dp),
                ) {
                    ZillitText(
                        text = tag,
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.textOnAccent,
                    )
                }
            }
        }
        ZillitText(
            text = caption,
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The web's play icon over a video poster. */
@Composable
internal fun PlayBadge(modifier: Modifier = Modifier, size: Dp = PLAY_BADGE) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = PLAY_SCRIM_ALPHA))
            .border(1.dp, Color.White.copy(alpha = PLAY_RING_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.Play, tint = Color.White, size = size / 2)
    }
}

/** "Scene No - 12", with the episode when the production has them. */
internal fun sceneTitle(scene: ContinuityScene): String =
    "Scene No - ${scene.sceneNumber}" + if (scene.episode.isNotBlank()) "  ·  Ep ${scene.episode}" else ""

internal fun formatBytes(bytes: Long): String = when {
    bytes >= MB -> "${bytes / MB} MB"
    bytes >= KB -> "${bytes / KB} KB"
    else -> "$bytes B"
}

/** Ten grey cards while the first list is in flight — the shape of what is coming. */
@Composable
internal fun SkeletonTile(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large),
    ) {
        ZillitSkeletonBar(Modifier.fillMaxWidth().aspectRatio(POSTER_RATIO), height = SKELETON_HEIGHT)
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION))
            ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_META_FRACTION), height = SKELETON_META_HEIGHT)
        }
    }
}

private const val KB = 1024L
private const val MB = KB * KB
internal const val POSTER_RATIO = 4f / 3f
private val BADGE_SIZE = 20.dp
private const val BADGE_CAP = 99
private val HOVER_LIFT = 2.dp
private val HOVER_SHADOW = 8.dp
private val REST_SHADOW = 1.dp
private const val HOVER_MILLIS = 180
private val GLYPH_TILE = 56.dp
private val GLYPH = 28.dp
private val TAG_NUDGE = 6.dp
private val PLAY_BADGE = 44.dp
private const val PLAY_SCRIM_ALPHA = 0.55f
private const val PLAY_RING_ALPHA = 0.8f
private val SKELETON_HEIGHT = 120.dp
private const val SKELETON_TITLE_FRACTION = 0.6f
private const val SKELETON_META_FRACTION = 0.8f
private val SKELETON_META_HEIGHT = 10.dp
