package com.zillit.desktop.feature.assetreport.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.assetreport.ui.AssetDetail
import com.zillit.desktop.feature.assetreport.ui.AssetEvent
import com.zillit.desktop.feature.assetreport.ui.AssetMediaLoader
import com.zillit.desktop.feature.assetreport.ui.DraftFile
import com.zillit.desktop.feature.assetreport.ui.components.SectionLabel
import com.zillit.desktop.feature.assetreport.ui.components.assetFileDrop
import com.zillit.desktop.feature.assetreport.ui.components.onBackdropTap
import com.zillit.desktop.feature.assetreport.ui.components.swallowPresses
import com.zillit.desktop.feature.assetreport.ui.components.decodeAssetImage
import com.zillit.desktop.feature.assetreport.ui.components.decodeAssetPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The detail's Attachments: a count, then a grid of tiles and an add tile — or,
 * with nothing attached, one wide drop zone. Picks wait, dashed, for Save.
 *
 * The whole section takes a drop, not just the zone: a file let go a few
 * pixels outside a dashed border should not vanish into the window.
 */
@Composable
internal fun AttachmentsSection(detail: AssetDetail, media: AssetMediaLoader, onEvent: (AssetEvent) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val files = detail.files
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .assetFileDrop(
                enabled = !detail.isLocked,
                onHover = { dragging = it },
                onDrop = { dropped, tooLarge -> onEvent(AssetEvent.DropFiles(dropped, tooLarge)) },
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Attachments", Modifier.weight(1f))
            ZillitText(
                text = when {
                    detail.isHydrating -> "Loading…"
                    else -> buildString {
                        append("${files.size} file${if (files.size == 1) "" else "s"}")
                        if (detail.pendingCount > 0) append(" · ${detail.pendingCount} pending")
                    }
                },
                style = ZillitTheme.typography.numeric.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = ZillitTheme.colors.textDisabled,
                maxLines = 1,
            )
        }
        when {
            detail.isHydrating -> TileGrid(count = SKELETON_TILES) { index -> SkeletonTile(index) }
            files.isEmpty() -> DropZone(
                highlighted = dragging,
                enabled = !detail.isLocked,
                onClick = { onEvent(AssetEvent.AddFiles) },
            )
            else -> TileGrid(count = files.size + 1) { index ->
                val file = files.getOrNull(index)
                Appearing(index) {
                    if (file == null) {
                        DropTile(
                            highlighted = dragging,
                            enabled = !detail.isLocked,
                            onClick = { onEvent(AssetEvent.AddFiles) },
                        )
                    } else {
                        Thumb(
                            file = file,
                            media = media,
                            removable = !detail.isLocked,
                            onOpen = { onEvent(AssetEvent.ViewFile(file.key)) },
                            onRemove = { onEvent(AssetEvent.RemoveFile(file.key)) },
                        )
                    }
                }
            }
        }
    }
}

/** Four square tiles to a row, fourteen apart — the web's `att-grid`. */
@Composable
private fun TileGrid(count: Int, tile: @Composable (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        List(count) { it }.chunked(TILES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                row.forEach { index -> Box(Modifier.weight(1f).aspectRatio(1f)) { tile(index) } }
                repeat(TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Tiles grow in one after another rather than all at once — the web's staggered
 * `ahAssetTileIn`. Driven by the frame clock through the transition's own delay,
 * so nothing waits on a wall-clock timer.
 */
@Composable
private fun Appearing(index: Int, content: @Composable () -> Unit) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val timing = tween<Float>(durationMillis = TILE_IN_MILLIS, delayMillis = index * STAGGER_MILLIS)
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(timing) + scaleIn(timing, initialScale = TILE_IN_SCALE),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun Thumb(
    file: DraftFile,
    media: AssetMediaLoader,
    removable: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pending = file is DraftFile.Pending
    val image = if (file.isImage) rememberPicture(file, media, THUMB_EDGE) else null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(TILE_SHAPE)
            .background(colors.surfaceSunken)
            .then(
                if (pending) {
                    Modifier.dashedOutline(colors.accent, 1.5.dp, TILE_RADIUS)
                } else {
                    Modifier.border(1.dp, if (hovered) colors.accent else colors.border, TILE_SHAPE)
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            DocumentFace(file)
        }
        // Always composed and revealed by alpha: a control that only exists
        // while hovered never receives the press.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .alpha(if (hovered && removable) 1f else 0f)
                .clip(RoundedCornerShape(6.dp))
                .background(REMOVE_SCRIM)
                .clickable(enabled = removable, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = ZillitIcons.Close,
                contentDescription = "Remove ${file.name}",
                tint = Color.White,
                size = 11.dp,
            )
        }
    }
}

/** A file with no picture to show: its coloured type badge over the extension. */
@Composable
private fun DocumentFace(file: DraftFile) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitFileBadge(fileName = file.name.ifBlank { "file.${file.extension}" }, size = 30.dp)
        ZillitText(
            text = file.extension.ifBlank { "file" }.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.05.em,
            ),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun SkeletonTile(index: Int) {
    Box(Modifier.fillMaxSize().clip(TILE_SHAPE)) {
        // Offset pulses read as a wave across the row, as the web's staggered delays do.
        ZillitSkeletonBar(Modifier.fillMaxSize().alpha(1f - index * SKELETON_FADE_STEP), height = 400.dp)
    }
}

/** The wide empty-state zone: an upload badge, "Click to upload or drag & drop", the rule. */
@Composable
private fun DropZone(highlighted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lit = (hovered && enabled) || highlighted
    val background by animateColorAsState(if (lit) colors.accentSoft else colors.surfaceSunken, label = "dropZone")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZONE_SHAPE)
            .background(background)
            .dashedOutline(if (lit) colors.accent else colors.border, 1.6.dp, ZONE_RADIUS)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UploadBadge(box = 36.dp, icon = 18.dp)
        UploadCopy(size = 13f)
        ZillitText(
            text = "Images or PDF · up to 10MB",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textDisabled,
        )
    }
}

/** The grid's last tile: the zone, folded into a square. */
@Composable
private fun DropTile(highlighted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lit = (hovered && enabled) || highlighted
    val background by animateColorAsState(if (lit) colors.accentSoft else colors.surfaceSunken, label = "dropTile")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(TILE_SHAPE)
            .background(background)
            .dashedOutline(if (lit) colors.accent else colors.border, 1.5.dp, TILE_RADIUS)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        UploadBadge(box = 30.dp, icon = 15.dp)
        UploadCopy(size = 10.5f)
        ZillitText(
            text = "Images or PDF · up to 10MB",
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 8.5.sp, lineHeight = 11.sp),
            color = colors.textDisabled,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UploadBadge(box: Dp, icon: Dp) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(box / 3.6f)
    Box(
        modifier = Modifier
            .size(box)
            .shadow(1.dp, shape)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.Upload, tint = colors.accent, size = icon)
    }
}

@Composable
private fun UploadCopy(size: Float) {
    val colors = ZillitTheme.colors
    ZillitText(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) { append("Click to upload") }
            append(" or drag & drop")
        },
        style = ZillitTheme.typography.bodyMedium.copy(
            fontSize = size.sp,
            lineHeight = (size * LINE_RATIO).sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
    )
}

// -- the large viewer -------------------------------------------------------------------------

/**
 * One file, full size — the same large view the purchase orders and receipts
 * use: the picture fitted, or a PDF's pages stacked; Download saves a copy.
 */
@Composable
internal fun AttachmentViewer(
    file: DraftFile?,
    media: AssetMediaLoader,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = file != null
    // Held through the exit, so the card does not blank as it fades. A plain
    // holder, not state: remembering it must not itself trigger a recomposition.
    val held = remember { arrayOfNulls<DraftFile>(1) }
    if (file != null) held[0] = file
    val current = held[0] ?: return

    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(VIEWER_MILLIS)),
        exit = fadeOut(tween(VIEWER_MILLIS)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.scrim.copy(alpha = SCRIM_ALPHA))
                .onBackdropTap(onClose)
                .padding(horizontal = 40.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            ViewerCard(
                file = current,
                media = media,
                onClose = onClose,
                onDownload = onDownload,
                modifier = Modifier.animateEnterExit(
                    enter = scaleIn(tween(VIEWER_MILLIS), initialScale = VIEWER_SCALE),
                    exit = scaleOut(tween(VIEWER_MILLIS), targetScale = VIEWER_SCALE),
                ),
            )
        }
    }
}

private sealed interface Pages {
    data object Loading : Pages
    data object Failed : Pages
    data class Ready(val pages: List<ImageBitmap>) : Pages
}

@Composable
private fun ViewerCard(
    file: DraftFile,
    media: AssetMediaLoader,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier,
) {
    val colors = ZillitTheme.colors
    val pages by produceState<Pages>(Pages.Loading, file.key) {
        value = when (val bytes = media.load(file)) {
            is ZillitResult.Failure -> Pages.Failed
            is ZillitResult.Success -> withContext(Dispatchers.Default) { decodeAssetPages(bytes.data) }
                .takeIf { it.isNotEmpty() }
                ?.let { Pages.Ready(it) }
                ?: Pages.Failed
        }
    }
    Column(
        modifier = modifier
            .widthIn(max = VIEWER_MAX_WIDTH)
            .fillMaxSize()
            .shadow(24.dp, VIEWER_SHAPE)
            .clip(VIEWER_SHAPE)
            .background(colors.surface)
            .border(1.dp, colors.border, VIEWER_SHAPE)
            // A press on the card is the card's, never the backdrop's close.
            .swallowPresses(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitText(
                text = file.name,
                style = ZillitTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // Offered once there is something to save, as the web's link appears with its URL.
            if (pages is Pages.Ready) DownloadLink(onDownload)
            ZillitIconButton(icon = ZillitIcons.Close, contentDescription = "Close", onClick = onClose)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Box(Modifier.fillMaxWidth().weight(1f).background(colors.surfaceSunken), contentAlignment = Alignment.Center) {
            ViewerBody(file, pages)
        }
    }
}

/** The loading line, the failure line, a fitted picture, or a PDF's pages one above another. */
@Composable
private fun ViewerBody(file: DraftFile, pages: Pages) {
    val colors = ZillitTheme.colors
    when (pages) {
        Pages.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitSpinner(size = 16.dp)
            ZillitText(
                text = "Loading attachment…",
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
        Pages.Failed -> ZillitText(
            text = "Failed to load attachment",
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textMuted,
        )
        is Pages.Ready -> if (pages.pages.size == 1) {
            Image(
                bitmap = pages.pages.single(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            )
        } else {
            ZillitLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(pages.pages) { index, page ->
                    Image(
                        bitmap = page,
                        contentDescription = "${file.name}, page ${index + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().shadow(2.dp).background(Color.White),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadLink(onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) colors.accentSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Download, tint = colors.accentText, size = 12.dp)
        ZillitText(
            text = "Download",
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
            color = colors.accentText,
        )
    }
}

/** A stored file's picture, fetched once per key and decoded off the frame. */
@Composable
private fun rememberPicture(file: DraftFile, media: AssetMediaLoader, maxEdge: Int): ImageBitmap? {
    val picture by produceState<ImageBitmap?>(null, file.key) {
        value = when (val bytes = media.load(file)) {
            is ZillitResult.Failure -> null
            is ZillitResult.Success -> withContext(Dispatchers.Default) { decodeAssetImage(bytes.data, maxEdge) }
        }
    }
    return picture
}

/**
 * A dashed rounded outline, drawn over the content — Compose's `border` has no
 * dash, and a pending photo would otherwise cover its own "not saved yet" ring.
 */
private fun Modifier.dashedOutline(color: Color, width: Dp, radius: Dp): Modifier = drawWithContent {
    drawContent()
    val stroke = width.toPx()
    val inset = stroke / 2
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP))),
    )
}

private const val TILES_PER_ROW = 4
private const val SKELETON_TILES = 5
private const val SKELETON_FADE_STEP = 0.12f
private const val STAGGER_MILLIS = 45
private const val TILE_IN_MILLIS = 340
private const val TILE_IN_SCALE = 0.94f
private const val THUMB_EDGE = 480
private const val VIEWER_MILLIS = 180
private const val VIEWER_SCALE = 0.96f
private const val SCRIM_ALPHA = 0.6f
private const val LINE_RATIO = 1.35f
private const val DASH = 7f
private const val GAP = 5f
private val TILE_GAP = 14.dp
private val TILE_RADIUS = 12.dp
private val TILE_SHAPE = RoundedCornerShape(TILE_RADIUS)
private val ZONE_RADIUS = 13.dp
private val ZONE_SHAPE = RoundedCornerShape(ZONE_RADIUS)
private val VIEWER_SHAPE = RoundedCornerShape(16.dp)
private val VIEWER_MAX_WIDTH = 1100.dp
private val REMOVE_SCRIM = Color(0x9E0F141A)
