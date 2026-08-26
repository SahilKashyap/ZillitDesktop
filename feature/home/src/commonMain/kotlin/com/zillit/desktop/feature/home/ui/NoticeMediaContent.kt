package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitFileColors
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.feature.home.domain.AudioPlayer
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import com.zillit.desktop.feature.home.domain.formatFileSize
import kotlinx.coroutines.launch

/**
 * What a post's attachment looks like inside the bubble.
 *
 * Split by [NoticeKind] exactly as the web's `RenderMessage` is: images and
 * videos show the picture, everything else shows a file chip. Both open on
 * click — images into the in-app lightbox, the rest through [onOpen], which
 * saves to Downloads and hands the file to the OS.
 */
@Composable
internal fun AttachmentContent(
    kind: NoticeKind,
    attachment: NoticeAttachment?,
    media: NoticeMediaSource?,
    onPreview: (NoticeAttachment) -> Unit,
    onOpen: (NoticeAttachment) -> Unit,
    player: AudioPlayer? = null,
    location: GeoPoint? = null,
    onOpenLocation: (GeoPoint) -> Unit = {},
) {
    // A location can arrive without its screenshot — every desktop post does,
    // and the phones' only survives if their static-map fetch worked. The
    // card carries the place either way; the map is decoration.
    if (kind == NoticeKind.Location && location != null) {
        LocationCard(location, attachment, media, onOpenLocation)
        return
    }
    if (attachment == null) return

    when (kind) {
        NoticeKind.Image -> MediaThumbnail(
            attachment = attachment,
            media = media,
            overlay = null,
            onClick = { onPreview(attachment) },
        )

        // The thumbnail was made at upload time; the play badge is what says
        // this is not a still. Playback is the OS's job — a desktop video
        // player is not something to half-build inside a chat bubble.
        NoticeKind.Video -> MediaThumbnail(
            attachment = attachment,
            media = media,
            overlay = { PlayBadge() },
            onClick = { onOpen(attachment) },
        )

        NoticeKind.Audio -> AudioMessageContent(attachment, media, player, onOpen)

        // A document with a poster — a PDF's first page — shows it above the
        // chip, as the web's DocumentMessage does. Same click either way: save
        // and hand to the OS.
        NoticeKind.Document -> DocumentContent(attachment, media, onOpen)

        // Only reachable for a location post whose point failed to read — a
        // row from a client that sent the map image and nothing else. The
        // screenshot is then all there is, and opening it is all that is left.
        NoticeKind.Location -> MediaThumbnail(
            attachment = attachment,
            media = media,
            overlay = { LocationBadge() },
            onClick = { onOpen(attachment) },
        )

        NoticeKind.Text -> Unit
    }
}

/** A document: its poster (a PDF's first page) when one exists, and the chip. */
@Composable
private fun DocumentContent(
    attachment: NoticeAttachment,
    media: NoticeMediaSource?,
    onOpen: (NoticeAttachment) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        if (attachment.localBytes != null ||
            (!attachment.thumbnail.isNullOrBlank() && attachment.isFetchable)
        ) {
            MediaThumbnail(
                attachment = attachment,
                media = media,
                overlay = null,
                onClick = { onOpen(attachment) },
                // The chip is already below; a failed poster shows nothing.
                fallback = {},
            )
        }
        FileChip(attachment, onClick = { onOpen(attachment) })
    }
}

/**
 * The image itself, at the web's fixed media width.
 *
 * Fixed rather than intrinsic because the web pins media bubbles to 300 px —
 * a board where every image is its own size reads as clutter, and a 6000 px
 * set photo must not become a 6000 px bubble.
 */
@Composable
private fun MediaThumbnail(
    attachment: NoticeAttachment,
    media: NoticeMediaSource?,
    overlay: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    /** What a failed decode shows. Defaults to the chip; documents already
     *  have one below and pass nothing. */
    fallback: (@Composable () -> Unit)? = null,
) {
    val bitmap by rememberAttachmentImage(attachment, media, preview = true)
    val kind = fileKindOf(attachment.fileName, attachment.contentSubtype)
    val hue = kind.hue.colour()

    // A thumbnail that could not be decoded is not a thumbnail. Rendering the
    // chip *inside* the well leaves a 300×200 empty box with a row of text
    // floating in the middle of it — and the overlay badge, which is centred
    // too, lands on top of the file's own name.
    if (bitmap is AttachmentImage.Failed) {
        (fallback ?: { FileChip(attachment, onClick = onClick) })()
        return
    }

    Box(
        modifier = Modifier
            .width(MEDIA_WIDTH)
            .height(MEDIA_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            // Media needs an edge of its own now that the card is white in the
            // light theme. A document's first page is usually white paper, and
            // without this a PDF thumbnail on a white card is an empty rectangle
            // — the attachment looks like it failed to load when it is right
            // there.
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (val loaded = bitmap) {
            // The family's own mark while the bytes arrive, rather than the
            // word "Loading…". A 300×200 well with a line of text in the middle
            // of it says nothing about what is coming; the glyph says whether
            // to expect a photograph or a clip, and it is the same mark the
            // file chip would carry for the same file.
            //
            // Skipped where there is already an overlay. A video's play badge
            // and a location's pin are centred too, and they say the same thing
            // the glyph would — two marks stacked in the middle of one well is
            // the collision, not the message.
            null -> if (overlay == null) {
                ZillitIcon(
                    icon = kind.icon,
                    contentDescription = "Loading ${kind.label.lowercase()}",
                    tint = hue.copy(alpha = PLACEHOLDER_ALPHA),
                    size = PLACEHOLDER_GLYPH,
                )
            }

            // Returned above, before the well was drawn.
            is AttachmentImage.Failed -> Unit

            is AttachmentImage.Ready -> Image(
                bitmap = loaded.bitmap,
                contentDescription = attachment.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        overlay?.invoke()
    }
}

@Composable
private fun PlayBadge() {
    Box(
        modifier = Modifier
            .size(PLAY_BADGE)
            .clip(ZillitTheme.shapes.pill)
            .background(Color.Black.copy(alpha = PLAY_SCRIM)),
        contentAlignment = Alignment.Center,
    ) {
        // The chevron reads as "play" at this size without a dedicated glyph.
        ZillitIcon(
            icon = ZillitIcons.ChevronRight,
            contentDescription = "Play",
            tint = Color.White,
            size = PLAY_ICON,
        )
    }
}

/**
 * A document, audio file, or map screenshot: icon, name, size.
 *
 * The name matters more than the look — on a call sheet board the file *is*
 * "CallSheet_Day12.pdf", and a nameless tile would make people open everything
 * to find anything.
 */
@Composable
private fun FileChip(attachment: NoticeAttachment, onClick: () -> Unit) {
    val kind = fileKindOf(attachment.fileName, attachment.contentSubtype)
    val hue = kind.hue.colour()

    Row(
        modifier = Modifier
            .width(MEDIA_WIDTH)
            .clip(ZillitTheme.shapes.medium)
            // Was white-on-white-at-12%: invisible the moment the card stopped
            // being dark. Sunken surface and the page's own ink work in both.
            .background(ZillitTheme.colors.surfaceSunken)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        // The glyph sits on a tile of its own colour: a bare coloured glyph at
        // 16dp is a hint, and a tile is what makes the family readable at a
        // glance down a column of attachments.
        Box(
            modifier = Modifier
                .size(CHIP_TILE)
                .clip(ZillitTheme.shapes.small)
                .background(hue.copy(alpha = TILE_WASH)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = kind.icon,
                // Named, so a reader who cannot see the glyph — or the colour —
                // is told what the file is rather than that there is an icon.
                contentDescription = kind.label,
                tint = hue,
                size = CHIP_ICON,
            )
        }
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = attachment.fileName.ifBlank { kind.label },
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
            )
            // "PDF · 302 B" — the type in words as well as a glyph, because
            // eight file families share four glyphs between them.
            val detail = listOfNotNull(
                kind.label.takeIf { attachment.fileName.isNotBlank() },
                formatFileSize(attachment.sizeBytes).takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
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

/**
 * What an attachment is, for the chip that stands in for it.
 *
 * [hue] picks the mark's colour out of [ZillitFileColors] rather than holding a
 * `Color`: this is resolved once per theme, and a family that carried its own
 * literal would be the one thing on the board that ignored dark mode.
 */
internal data class FileKind(
    val icon: ImageVector,
    val label: String,
    val hue: FileHue,
)

/**
 * The colour a file family is marked in.
 *
 * The conventions people already carry from every other file manager they use:
 * PDFs red, sheets green, documents blue. Worth following rather than inventing
 * — the point of a colour is to be recognised before the label is read.
 */
internal enum class FileHue {
    Red, Blue, Green, Amber, Purple, Pink, Indigo, Teal, Neutral;

    @Composable
    fun colour(): Color = with(ZillitTheme.colors.files) {
        when (this@FileHue) {
            Red -> red
            Blue -> blue
            Green -> green
            Amber -> amber
            Purple -> purple
            Pink -> pink
            Indigo -> indigo
            Teal -> teal
            Neutral -> neutral
        }
    }
}

/**
 * The file's family, from its extension.
 *
 * Extension rather than MIME type: the wire's `content_subtype` is unreliable
 * here — plenty of rows carry `application/octet-stream` for a PDF, and the
 * name is what the person who uploaded it actually saw. The subtype is the
 * fallback for the rows that have no filename at all.
 *
 * The glyph set is the one the design system has, so several families share a
 * glyph; [FileKind.label] is what tells them apart, and it is shown next to the
 * name as well as read out as the icon's description.
 */
internal fun fileKindOf(fileName: String, subtype: String? = null): FileKind {
    val extension = fileName.substringAfterLast('.', "")
        .ifBlank { subtype?.substringAfterLast('/', "").orEmpty() }
        .lowercase()

    return when (extension) {
        "pdf" -> FileKind(ZillitIcons.File, "PDF", FileHue.Red)
        "doc", "docx", "rtf", "odt", "pages" -> FileKind(ZillitIcons.File, "Document", FileHue.Blue)
        "txt", "md", "log" -> FileKind(ZillitIcons.File, "Text", FileHue.Neutral)
        "xls", "xlsx", "csv", "tsv", "ods", "numbers" ->
            FileKind(ZillitIcons.Grid, "Spreadsheet", FileHue.Green)
        "ppt", "pptx", "odp", "key" -> FileKind(ZillitIcons.Monitor, "Presentation", FileHue.Amber)
        "zip", "rar", "7z", "tar", "gz", "bz2" -> FileKind(ZillitIcons.Drive, "Archive", FileHue.Purple)
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff", "svg" ->
            FileKind(ZillitIcons.Photo, "Image", FileHue.Pink)
        "mp4", "mov", "avi", "mkv", "webm", "m4v", "wmv" ->
            FileKind(ZillitIcons.Play, "Video", FileHue.Indigo)
        "mp3", "wav", "m4a", "aac", "ogg", "flac", "opus" ->
            FileKind(ZillitIcons.Mic, "Audio", FileHue.Teal)
        // Named by its extension when it is one this list has never seen — "XCF"
        // beats "File", and the uploader knows what theirs is. Left grey: an
        // unknown file has no family to be coloured by.
        else -> FileKind(
            icon = ZillitIcons.Paperclip,
            label = extension.uppercase().ifBlank { "Attachment" },
            hue = FileHue.Neutral,
        )
    }
}

/** The pin over a map screenshot — what says "this opens the map". */
@Composable
private fun LocationBadge() {
    Box(
        modifier = Modifier
            .padding(ZillitTheme.spacing.xs)
            .clip(ZillitTheme.shapes.pill)
            .background(Color.Black.copy(alpha = PLAY_SCRIM))
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = "📍 Open map",
            style = ZillitTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

/**
 * A shared place: its map picture when one came, then its name, its address
 * and the point itself, over one way out to Maps.
 *
 * The whole card is the affordance rather than a button inside it — a nested
 * clickable in a bubble that already carries a context menu and a long-press
 * is one press target too many, and the labelled line below says where the
 * click goes. [onOpenLocation] is the board's guarded external-URL launcher,
 * the same one the Links library uses; nothing here reaches for a browser.
 */
@Composable
private fun LocationCard(
    point: GeoPoint,
    attachment: NoticeAttachment?,
    media: NoticeMediaSource?,
    onOpenLocation: (GeoPoint) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        if (attachment != null) {
            MediaThumbnail(
                attachment = attachment,
                media = media,
                overlay = { LocationBadge() },
                onClick = { onOpenLocation(point) },
                // The card below already names the place; a failed map is a
                // missing picture, not a missing location.
                fallback = {},
            )
        }
        LocationDetails(point, onOpenLocation)
    }
}

/** The place in words: title, address, coordinates, and the way out. */
@Composable
private fun LocationDetails(point: GeoPoint, onOpenLocation: (GeoPoint) -> Unit) {
    Row(
        modifier = Modifier
            .width(MEDIA_WIDTH)
            .clip(ZillitTheme.shapes.medium)
            // Sunken surface and the page's own ink, for the reason FileChip
            // carries the same note: white-on-white-at-12% vanished the moment
            // the card stopped being dark.
            .background(ZillitTheme.colors.surfaceSunken)
            .clickable { onOpenLocation(point) }
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(CHIP_TILE)
                .clip(ZillitTheme.shapes.small)
                .background(ZillitTheme.colors.accent.copy(alpha = TILE_WASH)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = ZillitIcons.Pin,
                contentDescription = "Location",
                tint = ZillitTheme.colors.accent,
                size = CHIP_ICON,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            // Nameless is ordinary: the web and iOS send the point alone, and
            // a post from either arrives with nothing to title it.
            ZillitText(
                text = point.name.ifBlank { "Shared location" },
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 2,
            )
            if (point.detail.isNotBlank()) {
                ZillitText(
                    text = point.detail,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = ADDRESS_LINES,
                )
            }
            // The numbers stay visible: a unit driver types coordinates into
            // whatever their own navigation is, and cannot retype a hyperlink.
            ZillitText(
                text = point.coordinates,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
            ZillitText(
                text = "Open in Maps",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accentText,
                maxLines = 1,
            )
        }
    }
}

/**
 * A voice message: play/pause, a progress bar, and the clock.
 *
 * The web's `AudioMessage`, reduced to what a desktop needs. Formats the JVM
 * cannot decode — the phones record AAC — fall back to the file chip, whose
 * click hands the file to the OS player and its codecs.
 */
@Composable
internal fun AudioMessageContent(
    attachment: NoticeAttachment,
    media: NoticeMediaSource?,
    player: AudioPlayer?,
    onOpen: (NoticeAttachment) -> Unit,
) {
    if (player == null || media == null) {
        FileChip(attachment, onClick = { onOpen(attachment) })
        return
    }

    // Marked once this file failed to decode; the chip takes over for good.
    var undecodable by remember(attachment.media) { mutableStateOf(false) }
    if (undecodable) {
        FileChip(attachment, onClick = { onOpen(attachment) })
        return
    }

    val playback by player.state.collectAsState()
    val mine = playback?.takeIf { it.key == attachment.media }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .width(MEDIA_WIDTH)
            .clip(ZillitTheme.shapes.medium)
            .background(Color.White.copy(alpha = CHIP_ALPHA))
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(
            icon = if (mine?.isPlaying == true) ZillitIcons.Pause else ZillitIcons.Play,
            contentDescription = if (mine?.isPlaying == true) "Pause" else "Play",
            onClick = {
                scope.launch {
                    val bytes = media.fetch(attachment, preview = false)
                    when (bytes) {
                        is ZillitResult.Failure -> undecodable = true
                        is ZillitResult.Success ->
                            if (player.toggle(attachment.media, bytes.data)
                                is ZillitResult.Failure
                            ) {
                                undecodable = true
                            }
                    }
                }
            },
        )

        com.zillit.desktop.core.designsystem.component.ZillitAudioProgress(
            progress = mine?.progress ?: 0f,
            positionMillis = mine?.positionMillis ?: 0,
            // Position while this one is loaded; the stored duration otherwise.
            totalMillis = mine?.durationMillis?.takeIf { it > 0 } ?: attachment.durationMillis,
            // Only the loaded message can jump — the bar of one never played
            // has no decoded audio to jump within; play is the loading gesture.
            onSeek = if (mine != null) {
                { fraction -> player.seek(attachment.media, fraction) }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
            trackColor = Color.White.copy(alpha = TRACK_ALPHA),
            fillColor = Color.White,
            clockColor = Color.White.copy(alpha = MUTED_ALPHA),
        )
    }
}

/**
 * The full-size image over a scrim; click anywhere to close.
 *
 * Fetches the full object, not the thumbnail — this is the "let me actually
 * read the call sheet" view.
 */
@Composable
internal fun MediaLightbox(
    attachment: NoticeAttachment,
    media: NoticeMediaSource?,
    onClose: () -> Unit,
) {
    val bitmap by rememberAttachmentImage(attachment, media, preview = false)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = LIGHTBOX_SCRIM))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (val loaded = bitmap) {
            null -> ZillitText(
                text = "Loading…",
                style = ZillitTheme.typography.bodyMedium,
                color = Color.White,
            )

            is AttachmentImage.Failed -> ZillitText(
                text = "Could not load this image.",
                style = ZillitTheme.typography.bodyMedium,
                color = Color.White,
            )

            is AttachmentImage.Ready -> Image(
                bitmap = loaded.bitmap,
                contentDescription = attachment.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(LIGHTBOX_PADDING),
            )
        }
    }
}

internal sealed interface AttachmentImage {
    data class Ready(val bitmap: ImageBitmap) : AttachmentImage
    data object Failed : AttachmentImage
}

/**
 * Fetch-and-decode keyed on the object, so scrolling reuses what the source's
 * cache already holds and recomposition never refires an in-flight load.
 */
@Composable
internal fun rememberAttachmentImage(
    attachment: NoticeAttachment,
    media: NoticeMediaSource?,
    preview: Boolean,
) = produceState<AttachmentImage?>(initialValue = null, attachment.media, preview) {
    // An optimistic card carries its own bytes; nothing to fetch yet.
    attachment.localBytes?.let { local ->
        value = decodeImageBitmap(local)
            ?.let { AttachmentImage.Ready(it) }
            ?: AttachmentImage.Failed
        return@produceState
    }
    if (media == null) {
        value = AttachmentImage.Failed
        return@produceState
    }

    value = when (val fetched = media.fetch(attachment, preview)) {
        is ZillitResult.Failure -> AttachmentImage.Failed
        is ZillitResult.Success ->
            decodeImageBitmap(fetched.data)
                ?.let { AttachmentImage.Ready(it) }
                ?: AttachmentImage.Failed
    }
}

private val MEDIA_WIDTH = 300.dp
private val HAIRLINE = 1.dp
private const val TRACK_ALPHA = 0.3f
private val MEDIA_HEIGHT = 200.dp
private val PLAY_BADGE = 44.dp
private val PLAY_ICON = 22.dp
private val CHIP_ICON = 16.dp
/** The tile behind the glyph; square, so every family is the same shape. */
private val CHIP_TILE = 30.dp
/** Big enough to read across the media well, faint enough to be a placeholder. */
private val PLACEHOLDER_GLYPH = 44.dp
private const val PLACEHOLDER_ALPHA = 0.45f
private const val TILE_WASH = 0.14f
/** A street address runs long; two lines is a doorway, three is a paragraph. */
private const val ADDRESS_LINES = 3
private val LIGHTBOX_PADDING = 32.dp
private const val PLAY_SCRIM = 0.55f
private const val CHIP_ALPHA = 0.12f
private const val MUTED_ALPHA = 0.7f
private const val LIGHTBOX_SCRIM = 0.85f
