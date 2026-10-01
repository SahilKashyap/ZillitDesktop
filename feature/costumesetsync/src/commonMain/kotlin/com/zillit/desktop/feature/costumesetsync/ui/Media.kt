package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.PickedFile
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.StoredFile
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The media layer — the web's `MediaPicker`, `MediaButtons`, `ReferenceGrid`
 * and `lib/upload.js`. Photos, clips, files and links are attached to a
 * record in two steps: the file goes to project storage through the host,
 * then the service is told its `attachment` details (`POST /photos`) — it
 * never takes the file. A link goes straight to `POST /photos/link`.
 *
 * The page Scanner is [DocumentScannerDialog]; its pages come from the host's camera window or from picture files.
 * Not ported: HEIC conversion (the web turns an iPhone photo into a JPEG before
 * upload; here it uploads as picked and its preview falls back to a file tile).
 */

internal val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "avi", "mkv", "3gp")

/** The largest photo or clip a form accepts — anything bigger is refused at pick time. */
const val MAX_UPLOAD_BYTES: Long = 250L * 1024 * 1024

private const val DEFAULT_KIND = "REFERENCE"
private val DEFAULT_KINDS = listOf(
    "FRONT",
    "SIDE",
    "BACK",
    "CLOSEUP",
    "DETAIL",
    "STAIN",
    "REFERENCE",
    "DOCUMENT",
    "OTHER",
)
private val TILE_SIZE = 88.dp
private val KIND_WIDTH = 130.dp
private val GRID_MIN = 132.dp
private val COMPACT_MIN = 96.dp
private const val IMAGE_CACHE_LIMIT = 200
private const val BYTES_PER_KB = 1024
private const val ONE_DECIMAL_BELOW = 10

// -- the upload (lib/upload.js) ---------------------------------------------

private fun mediaType(file: PickedFile, videoType: String): String = when {
    file.isVideo -> videoType
    file.isImage -> "IMAGE"
    else -> "FILE"
}

private fun StoredFile.toJson() = buildJsonObject {
    put("media", media)
    put("name", name)
    put("content_type", contentType)
    put("content_subtype", contentSubtype)
    put("bucket", bucket)
    put("region", region)
    put("file_size", fileSize)
}

/**
 * One file up to storage and its descriptor to the service. False on any failure — counted by the caller, never
 * thrown.
 */
private suspend fun SyncCtx.attachFile(
    file: PickedFile,
    entityType: String,
    entityId: String,
    kind: String,
    caption: String = "",
): Boolean {
    val stored = host.store(file) as? ZillitResult.Success ?: return false
    // The service has no VIDEO media type (IMAGE, FILE, LINK) unless meta ever grows one.
    val videoType = if ("VIDEO" in metaList("media_types")) "VIDEO" else "FILE"
    val answer = api.post(
        "/photos",
        body(
            "entity_type" to entityType,
            "entity_id" to entityId,
            "kind" to kind,
            "media_type" to mediaType(file, videoType),
            "attachment" to stored.data.toJson(),
            "caption" to caption,
        ),
    )
    return answer is ZillitResult.Success
}

private suspend fun SyncCtx.attachLink(
    entityType: String,
    entityId: String,
    kind: String,
    url: String,
    title: String,
): Boolean =
    api.post(
        "/photos/link",
        body(
            "entity_type" to entityType,
            "entity_id" to entityId,
            "kind" to kind,
            "url" to url,
            "title" to title.ifBlank { url },
        ),
    ) is ZillitResult.Success

/**
 * Attaches what a form collected to the record it just created. A failure is
 * counted, never thrown — the record is already saved, and the caller says how
 * many did not make it. [keep] gets the entries that did NOT attach, so the form
 * can hold on to just those: pressing save again retries them without sending the
 * ones already up a second time. Returns how many failed.
 */
suspend fun SyncCtx.attachMedia(
    entries: List<MediaEntry>,
    entityType: String,
    entityId: String,
    kind: String = DEFAULT_KIND,
    keep: (List<MediaEntry>) -> Unit = {},
): Int {
    val left = mutableListOf<MediaEntry>()
    for (entry in entries) {
        val ok = when (entry) {
            is MediaEntry.Link -> attachLink(entityType, entityId, kind, entry.url, entry.title)
            is MediaEntry.Local -> attachFile(entry.file, entityType, entityId, entry.kind ?: kind)
        }
        if (!ok) left += entry
    }
    keep(left)
    return left.size
}

/** An address with `https://` added when it has no scheme ("drive.google.com/…"). */
internal fun withScheme(address: String): String {
    val raw = address.trim()
    if (raw.isEmpty()) return ""
    return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw)) raw else "https://$raw"
}

/** The site name, a link's title when none is typed — as the reference's server does. */
internal fun siteName(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").ifBlank { url }

/** A byte count as the web prints it: `1.2 MB`, `340 KB`. */
internal fun fileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var n = bytes.toDouble()
    var u = 0
    while (n >= BYTES_PER_KB && u < units.lastIndex) {
        n /= BYTES_PER_KB
        u += 1
    }
    val text = if (n < ONE_DECIMAL_BELOW && u > 0) "%.1f".format(n) else n.toLong().toString()
    return "$text ${units[u]}"
}

// -- reading a stored reference ----------------------------------------------

internal fun Rec.isVideo(): Boolean {
    val attachment = rec("attachment")
    return str("media_type") == "VIDEO" ||
        attachment?.str("content_subtype")?.lowercase() in VIDEO_EXTENSIONS ||
        attachment?.str("name")?.substringAfterLast('.', "")?.lowercase() in VIDEO_EXTENSIONS
}

/** Pictures and clips; the rest are files and links. */
internal fun Rec.isPicture(): Boolean = str("media_type") == "IMAGE" || isVideo()

/**
 * A stored reference as an address. A LINK carries its own `url`; an uploaded
 * one carries only the S3 key, which the host resolves against the project's
 * storage each time. Empty when it will not resolve (a placeholder is drawn).
 */
internal suspend fun SyncCtx.referenceUrl(reference: Rec): String {
    reference.str("url").takeIf { it.isNotBlank() }?.let { return it }
    val attachment = reference.rec("attachment") ?: return ""
    attachment.first("signed_url", "public_url").takeIf { it.isNotBlank() }?.let { return it }
    return host.resolveUrl(attachment.str("media"), attachment.str("bucket"), attachment.str("region")).orEmpty()
}

private val decodedCache = LinkedHashMap<String, ImageBitmap>()

private fun cacheImage(key: String, bitmap: ImageBitmap) {
    if (decodedCache.size >= IMAGE_CACHE_LIMIT) decodedCache.keys.firstOrNull()?.let(decodedCache::remove)
    decodedCache[key] = bitmap
}

/**
 * A stored picture, decoded; null while loading or when it will not decode. Kept by storage key so a re-list does not
 * refetch.
 */
@Composable
internal fun rememberStoredImage(reference: Rec): ImageBitmap? {
    val ctx = LocalSync.current
    val key = reference.rec("attachment")?.str("media").orEmpty().ifBlank { reference.id }
    val bitmap by produceState(decodedCache[key], key) {
        if (value != null || reference.isVideo()) return@produceState
        val url = ctx.referenceUrl(reference).ifBlank { return@produceState }
        val bytes = ctx.host.fetch(url) ?: return@produceState
        value = withContext(Dispatchers.Default) { decodeImage(bytes) }?.also { cacheImage(key, it) }
    }
    return bitmap
}

/**
 * One tile: a picture filling it, or [placeholder] while there is none; the web's bordered, 8dp-cornered `.csync-
 * ref__thumb`.
 */
@Composable
private fun PictureTile(bitmap: ImageBitmap?, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.clip(shape).background(colors.surfaceHover).border(1.dp, colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            placeholder()
        }
    }
}

/** The round ✕ on a tile (`.csync-ref__del`, 24dp; `.csync-take-media__remove` is the same dark disc, smaller). */
@Composable
internal fun RemoveMark(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(ZillitIcons.Close, tint = Color.White, size = 12.dp)
    }
}

/** A dark play plate, centred over a clip's thumbnail (`.csync-ref__play`). */
@Composable
private fun PlayPlate(modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(8.dp)) {
        ZillitIcon(ZillitIcons.Play, tint = Color.White, size = 14.dp)
    }
}

@Composable
private fun GlyphLabel(glyph: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
        ZillitText(glyph, style = ZillitTheme.typography.titleMedium)
        ZillitText(
            label,
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = ZillitTheme.colors.textMuted,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// -- the button row (MediaButtons.jsx) ---------------------------------------

/**
 * Photo · Video · Gallery · Scan · Add file · Add link: the module's one media row, as two groups (take it, attach it)
 * with a divider between. On a desktop Photo and Video are file choosers filtered to pictures / clips; Gallery takes either. [files]
 * = false
 * drops Add file; Add link shows only when [onAddLink] is given (a form whose record does not exist yet has nothing
 * to link to).
 * In a card narrower than 560dp, as the web's container query does, [leading] takes a full row and each group becomes
 * an even
 * two-column grid (capture first, then attach) instead of a ragged wrap.
 */
@Composable
fun MediaButtons(
    onFiles: (List<PickedFile>) -> Unit,
    modifier: Modifier = Modifier,
    /** Gets the scanned pages (cleaned-up JPEGs); [onFiles] when null. */
    onScans: ((List<PickedFile>) -> Unit)? = null,
    onAddLink: (() -> Unit)? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    files: Boolean = true,
    leading: (@Composable (narrow: Boolean) -> Unit)? = null,
) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    var scanOpen by remember { mutableStateOf(false) }
    fun choose(extensions: Set<String>) {
        scope.launch {
            val picked = ctx.host.pick(extensions, true)
            if (picked.isNotEmpty()) onFiles(picked)
        }
    }
    val capture = listOf<MediaButton>(
        MediaButton(if (busy) t("csync_uploading") else t("csync_take_photo"), AhIcons.Camera) { choose(
            IMAGE_EXTENSIONS,
        ) },
        MediaButton(t("csync_take_video"), AhIcons.Video) { choose(VIDEO_EXTENSIONS) },
        MediaButton(t("csync_nav_gallery"), ZillitIcons.Photo) { choose(IMAGE_EXTENSIONS + VIDEO_EXTENSIONS) },
        MediaButton(t("csync_scan"), AhIcons.Document) { scanOpen = true },
    )
    val attach = buildList {
        if (files) add(MediaButton(t("csync_add_file"), ZillitIcons.Paperclip) { choose(emptySet()) })
        if (onAddLink != null) add(MediaButton(t("csync_add_link"), ZillitIcons.Link) { onAddLink() })
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val narrow = maxWidth <= NARROW_CARD
        if (narrow) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                leading?.invoke(true)
                MediaGrid(capture, enabled)
                if (attach.isNotEmpty()) MediaGrid(attach, enabled)
            }
        } else {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke(false)
                capture.forEach { MediaButtonView(it, enabled) }
                if (attach.isNotEmpty()) {
                    Box(
                        Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(
                            ZillitTheme.colors.border,
                        ),
                    )
                    attach.forEach { MediaButtonView(it, enabled) }
                }
            }
        }
    }
    DocumentScannerDialog(scanOpen, { scanOpen = false }, onScans ?: onFiles)
}

private val NARROW_CARD = 560.dp

private class MediaButton(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun MediaButtonView(button: MediaButton, enabled: Boolean, modifier: Modifier = Modifier) {
    ZillitButton(
        button.label,
        onClick = button.onClick,
        modifier = modifier,
        variant = ButtonVariant.Secondary,
        leadingIcon = button.icon,
        enabled = enabled,
    )
}

/** Two even columns of buttons: `grid-template-columns: repeat(2, minmax(0, 1fr))`, 8dp apart. */
@Composable
private fun MediaGrid(buttons: List<MediaButton>, enabled: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        buttons.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                pair.forEach { MediaButtonView(it, enabled, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The "Add a link" dialog: an address and an optional title. [onAdd] gets the address with its scheme and a title. */
@Composable
fun AddLinkDialog(
    open: Boolean,
    onClose: () -> Unit,
    onAdd: (url: String, title: String) -> Unit,
    busy: Boolean = false,
) {
    var url by remember(open) { mutableStateOf("") }
    var title by remember(open) { mutableStateOf("") }
    FormDialog(
        open = open,
        title = t("csync_add_link_title"),
        onDismiss = onClose,
        confirmLabel = t("csync_add_link"),
        onConfirm = {
            val full = withScheme(url)
            onAdd(full, title.trim().ifBlank { siteName(full) })
        },
        confirmEnabled = url.isNotBlank(),
        busy = busy,
        width = 480.dp,
    ) {
        TextInput(
            url,
            { url = it },
            t("csync_link_address"),
            FormWide.width(432.dp),
            help = t("csync_link_address_hint"),
        )
        TextInput(
            title,
            { title = it },
            t("csync_field_title"),
            FormWide.width(432.dp),
            help = t("csync_link_title_hint"),
        )
    }
}

// -- what a form attaches before its record exists (MediaPicker.jsx) -----------

/**
 * Photos, videos and links a form collects, sent once the record is saved
 * ([attachMedia]). A form offers Photo · Video · Gallery · Add link; Add file belongs to a
 * saved record's [ReferenceGrid]. [help] is the line under the buttons.
 */
@Composable
fun MediaPicker(
    entries: List<MediaEntry>,
    onChange: (List<MediaEntry>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    help: String? = null,
    allowLinks: Boolean = false,
) {
    val ctx = LocalSync.current
    var linkOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        MediaButtons(
            onFiles = { picked ->
                // Too big is said now, while it can still be trimmed — not after save.
                val (over, fits) = picked.partition { it.bytes.size > MAX_UPLOAD_BYTES }
                if (over.isNotEmpty()) {
                    ctx.toast(
                        t(
                            "csync_files_too_big",
                            "names" to over.joinToString(", ") { it.name.ifBlank { t("csync_that_file") } },
                        ),
                        false,
                    )
                }
                if (fits.isNotEmpty()) onChange(entries + fits.map { MediaEntry.Local(it) })
            },
            onAddLink = if (allowLinks) ({ linkOpen = true }) else null,
            enabled = enabled,
            files = false,
        )
        if (entries.isEmpty()) MutedText(t("csync_attached_once_saved"))
        MutedText(help ?: t("csync_shoot_or_pick"), maxLines = 2)
        if (entries.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                entries.forEach { entry -> EntryTile(entry, enabled) { onChange(entries - entry) } }
            }
        }
    }
    AddLinkDialog(linkOpen, { linkOpen = false }, { url, title ->
        onChange(entries + MediaEntry.Link(url, title))
        linkOpen = false
    })
}

@Composable
private fun EntryTile(entry: MediaEntry, enabled: Boolean, onRemove: () -> Unit) {
    // `.csync-take-media__item`: 88dp, 8dp corners, a hairline; the ✕ sits 4dp in from the top right.
    Box(Modifier.size(TILE_SIZE)) {
        when (entry) {
            is MediaEntry.Link -> PictureTile(null, Modifier.size(TILE_SIZE)) { GlyphLabel("🔗", entry.title) }
            is MediaEntry.Local -> {
                val file = entry.file
                val bitmap by produceState<ImageBitmap?>(null, file) {
                    if (file.isImage) value = withContext(Dispatchers.Default) { decodeImage(file.bytes) }
                }
                val glyph = if (file.isVideo) "🎬" else "📄"
                PictureTile(bitmap, Modifier.size(TILE_SIZE)) { GlyphLabel(glyph, file.name) }
                if (file.isVideo) PlayPlate(Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
        }
        if (enabled) RemoveMark(onRemove, Modifier.align(Alignment.TopEnd).padding(4.dp), size = 18.dp)
    }
}

// -- a saved record's photos (ReferenceGrid.jsx) -------------------------------

@Composable
private fun KindSelect(kind: String, kinds: List<String>, narrow: Boolean, onSelect: (String) -> Unit) {
    ZillitSelect(
        value = kind,
        options = kinds,
        onSelect = onSelect,
        label = { tEnum(it) },
        modifier = if (narrow) Modifier.fillMaxWidth() else Modifier.width(KIND_WIDTH),
    )
}

/**
 * Photos, videos, files and links attached to one record — the reference's PhotoGrid.
 * A kind picker and the media row, then the pictures as tiles with their kind, and files
 * and links as a list. [kinds] narrows the Kind picker (a cleaning ticket offers STAIN);
 * `attachments = false` leaves out Add file / Add link; [bare] drops the card around it. Scanned pages are always filed
 * as DOCUMENT, whatever Kind is picked.
 */
@Composable
fun ReferenceGrid(
    entityType: String,
    entityId: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    kinds: List<String> = emptyList(),
    compact: Boolean = false,
    attachments: Boolean = true,
    bare: Boolean = false,
) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val kindList = kinds.ifEmpty { DEFAULT_KINDS }
    var kind by remember(kindList) { mutableStateOf(kindList.first()) }
    var uploading by remember { mutableStateOf(false) }
    var linkOpen by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Rec?>(null) }
    val query = mapOf("entityType" to entityType, "entityId" to entityId)
    val photos = rememberRows(entityType, entityId) { api.get("/photos", query) }
    SocketRefresh(SyncEvents.Photo) { photos.reload(silent = true) }

    val upload = { files: List<PickedFile>, kindOf: String ->
        val tooBig = files.firstOrNull { it.bytes.size > MAX_UPLOAD_BYTES }
        if (tooBig != null) {
            ctx.toast(t("csync_take_file_too_big", "name" to tooBig.name.ifBlank { t("csync_that_file") }), false)
        } else {
            scope.launch {
                uploading = true
                val failed = files.count { !ctx.attachFile(it, entityType, entityId, kindOf) }
                uploading = false
                if (failed > 0) ctx.toast(t("csync_upload_n_failed", "n" to failed), false) else ctx.changed()
                photos.reload(silent = true)
            }
        }
    }

    val content: @Composable () -> Unit = {
        // `.csync-refbox`: 12dp between the media row and what is attached.
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (ctx.canPost) {
                MediaButtons(
                    onFiles = { upload(it, kind) }, onScans = { upload(it, "DOCUMENT") },
                    onAddLink = if (attachments) ({ linkOpen = true }) else null,
                    enabled = !uploading,
                    busy = uploading,
                    files = attachments,
                    leading = { narrow -> KindSelect(kind, kindList, narrow) { kind = it } },
                )
            }
            Await(photos) { items -> ReferenceBody(
                items,
                compact,
                attachments,
                onOpen = { preview = it },
                onRemoved = { photos.reload(silent = true) },
            ) }
        }
    }
    if (bare) content() else SectionCard(title = title ?: t("csync_references")) { content() }

    AddLinkDialog(linkOpen, { linkOpen = false }, { url, linkTitle ->
        scope.launch {
            val ok = ctx.attachLink(entityType, entityId, kind, url, linkTitle)
            if (ok) {
                linkOpen = false
                ctx.changed()
                photos.reload(silent = true)
            }
        }
    })
    PreviewDialog(preview, onClose = { preview = null })
}

@Composable
private fun ReferenceBody(
    items: List<Rec>,
    compact: Boolean,
    attachments: Boolean,
    onOpen: (Rec) -> Unit,
    onRemoved: () -> Unit,
) {
    val ctx = LocalSync.current
    if (items.isEmpty()) {
        MutedText(t(if (attachments) "csync_references_empty" else "csync_no_photos_videos"), maxLines = 2)
        return
    }
    val (pictures, others) = items.partition { it.isPicture() }
    val remove: (Rec) -> Unit = { ref -> ctx.launchWrite({ ctx.api.delete("/photos/${ref.id}") }, { onRemoved() }) }
    if (pictures.isNotEmpty()) {
        // `.csync-refgrid`: auto-fill columns of at least 132 (96 compact), 12 apart, 4:3 tiles.
        AutoFillGrid(
            pictures.size,
            if (compact) COMPACT_MIN else GRID_MIN,
            ZillitTheme.spacing.md,
            stretch = false,
        ) { i, cell ->
            ReferenceTile(pictures[i], cell, { onOpen(pictures[i]) }, { remove(pictures[i]) })
        }
    }
    if (others.isNotEmpty()) {
        // `.csync-rows--flat`: a quiet 1dp frame, 8dp corners, hairlines between the rows.
        val shape = RoundedCornerShape(8.dp)
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, ZillitTheme.colors.border, shape)) {
            others.forEachIndexed { i, ref ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
                ReferenceRow(ref) { remove(ref) }
            }
        }
    }
}

@Composable
private fun ReferenceTile(ref: Rec, cell: Modifier, onOpen: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalSync.current
    val bitmap = rememberStoredImage(ref)
    Box(cell.aspectRatio(THUMB_RATIO)) {
        PictureTile(bitmap, Modifier.fillMaxSize().clickable(onClick = onOpen)) { GlyphLabel(
            if (ref.isVideo()) "🎬" else "🖼",
            tEnum(ref.str("kind")),
        ) }
        if (ref.isVideo()) PlayPlate(Modifier.align(Alignment.Center))
        if (ref.str("kind").isNotBlank()) {
            // `.csync-ref__kind`: bottom-left, 10 bold upper-case on a dark chip.
            ZillitText(
                tEnum(ref.str("kind")).uppercase(),
                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(
                    Color.Black.copy(alpha = 0.6f),
                ).padding(horizontal = 6.dp, vertical = 2.dp),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.06.em,
                ),
                color = Color.White,
                maxLines = 1,
            )
        }
        if (ctx.canPost) RemoveMark(onRemove, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

private const val THUMB_RATIO = 4f / 3f

/**
 * A file or a link (`.csync-plain-row`): its icon, name over "kind · size · caption", a go-there mark and a remove ✕.
 */
@Composable
private fun ReferenceRow(ref: Rec, onRemove: () -> Unit) {
    val ctx = LocalSync.current
    val colors = ZillitTheme.colors
    val scope = rememberCoroutineScope()
    val link = ref.str("media_type") == "LINK"
    val attachment = ref.rec("attachment")
    val detail = listOf(
        tEnum(ref.str("kind")),
        if (link) t("csync_link") else fileSize(attachment?.long("file_size") ?: 0L),
        ref.str("caption"),
    )
        .filter { it.isNotBlank() }.joinToString(" · ")
    // A link is only an address; an attached file is a download, which needs download rights.
    val open = {
        scope.launch {
            val url = ctx.referenceUrl(ref)
            if (url.isNotBlank()) ctx.host.openUrl(url)
        }
        Unit
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().background(if (hovered) colors.surfaceHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { if (link) open() else ctx.whenDownload { open() } }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitIcon(if (link) ZillitIcons.Link else ZillitIcons.File, tint = colors.textPrimary, size = 16.dp)
        Column(Modifier.weight(1f)) {
            ZillitText(
                ref.first("title", "caption").ifBlank { attachment?.str("name").orEmpty().ifBlank { ref.str("url") } },
                style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (detail.isNotBlank()) ZillitText(
                detail,
                style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitIcon(if (link) AhIcons.ExternalLink else ZillitIcons.Download, tint = colors.textPrimary, size = 14.dp)
        if (ctx.canPost) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRemove).padding(4.dp)) {
                ZillitIcon(ZillitIcons.Close, tint = colors.textMuted, size = 12.dp)
            }
        }
    }
}

/**
 * A picture full size, with its kind and date; a clip offers to open in the system player. [onOpenRecord]
 * (the gallery) adds "Open", which goes to the record the photo is attached to — the service's `link`.
 */
@Composable
internal fun PreviewDialog(ref: Rec?, onClose: () -> Unit, onOpenRecord: ((String) -> Unit)? = null) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val shown = remember(ref) { ref }
    SyncDialogShell(
        title = shown?.let {
            if (onOpenRecord != null) {
                it.str("label").ifBlank { tEnum(it.str("entity_type")) }
            } else {
                listOf(tEnum(it.str("kind")), fmtDateTime(it.long("created")))
                    .filter { part -> part.isNotBlank() }
                    .joinToString(" · ")
            }
        }.orEmpty(),
        onDismiss = onClose,
        visible = ref != null,
        width = 820.dp,
        actions = {
            if (shown != null && shown.isVideo()) {
                ZillitButton(t("csync_open_link"), onClick = {
                    ctx.whenDownload { scope.launch { ctx.referenceUrl(shown).takeIf { it.isNotBlank() }?.let(
                        ctx.host::openUrl,
                    ) } }
                })
            }
            val link = shown?.str("link").orEmpty()
            if (onOpenRecord != null && link.isNotBlank()) {
                ZillitButton(t("csync_open_link"), onClick = {
                    onClose()
                    onOpenRecord(link)
                })
            }
        },
    ) {
        val bitmap = shown?.let { rememberStoredImage(it) }
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black),
            )
        }
        val caption = shown?.let {
            val parts = if (onOpenRecord != null) {
                listOf(
                    tEnum(it.str("entity_type")),
                    tEnum(it.str("kind")),
                    fmtDateTime(it.long("created")),
                    it.str("caption"),
                )
            } else {
                listOf(it.str("caption"))
            }
            parts.filter { part -> part.isNotBlank() }
        }.orEmpty().joinToString(" · ")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { MutedText(caption, maxLines = 2) }
    }
}
