package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.PreparedAttachment
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.canRemoveAttachment
import com.zillit.desktop.feature.tasks.domain.docBadge
import com.zillit.desktop.feature.tasks.domain.formatAttachmentSize
import kotlinx.coroutines.delay

/**
 * The images and files under a task's description — the web's thumbnails,
 * file list and full-size viewer.
 *
 * Images are 128 × 96 tiles that open the viewer; files are rows with a
 * coloured type badge, their size, who added them, a download and a remove.
 * One waiting for Create says so on its tile ("Saved on create") and is taken
 * away at once; one going up is an "Uploading…" tile.
 *
 * Removal asks first, in place, and **disarms itself after three seconds** —
 * the web's own behaviour, which is what makes a stray × harmless.
 */
@Composable
internal fun TaskAttachmentsSection(
    state: TasksUiState,
    panel: TaskPanel,
    readOnly: Boolean,
    onEvent: (TasksEvent) -> Unit,
) {
    // A form opened from a saved draft also shows what the draft already holds:
    // those move onto the task when it is created (the service does it).
    val images = panel.images + panel.keptDraftImages
    val files = panel.files + panel.keptDraftDocs
    if (images.isNotEmpty() || panel.pendingImages.isNotEmpty() || panel.uploading > 0) {
        Thumbs(state, panel, readOnly, onEvent)
    }
    if (files.isNotEmpty() || panel.pendingFiles.isNotEmpty()) {
        Files(state, panel, readOnly, onEvent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Thumbs(
    state: TasksUiState,
    panel: TaskPanel,
    readOnly: Boolean,
    onEvent: (TasksEvent) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        panel.images.forEach { attachment ->
            Thumb(
                attachment = attachment,
                panel = panel,
                removable = !readOnly && canRemoveAttachment(attachment, panel.task, state.me),
                onOpen = { onEvent(TasksEvent.OpenAttachment(attachment.id)) },
                onEvent = onEvent,
            )
        }
        // The draft's own: shown from the link the single-draft read carried,
        // and removed by its draft key (it leaves the draft on Save draft, or
        // just before Create — so "Don't save" keeps it).
        panel.keptDraftImages.forEach { attachment ->
            Thumb(
                attachment = attachment,
                panel = panel,
                removable = !readOnly,
                onOpen = { onEvent(TasksEvent.OpenAttachment(attachment.id)) },
                onEvent = onEvent,
                removeEvent = TasksEvent.RemovePendingAttachment(DRAFT_FILE_PREFIX + attachment.id),
            )
        }
        panel.pendingImages.forEach { item ->
            PendingThumb(item, readOnly, onEvent)
        }
        repeat(panel.uploading) { UploadingThumb() }
    }
}

/** One image the task has: its bytes when they have arrived, else why not. */
@Composable
private fun Thumb(
    attachment: TaskAttachment,
    panel: TaskPanel,
    removable: Boolean,
    onOpen: () -> Unit,
    onEvent: (TasksEvent) -> Unit,
    /** What removing it sends; a task's own asks the service to take it off. */
    removeEvent: TasksEvent = TasksEvent.RemoveAttachment(attachment),
) {
    val k = TasksTheme.c
    val bytes = panel.imageBytes[attachment.id]
    val failed = attachment.id in panel.imagesFailed
    // Asked for once per attachment; the view model ignores a second ask.
    LaunchedEffect(attachment.id) { onEvent(TasksEvent.LoadAttachment(attachment)) }
    val bitmap = remember(bytes) { bytes?.let { decodeImageBitmap(it) } }

    Box(Modifier.size(THUMB_WIDTH, THUMB_HEIGHT).clip(TILE).background(k.surface2)) {
        ZillitTooltip(attachment.name) {
            Box(
                modifier = Modifier.fillMaxSize().clickable(enabled = bitmap != null, onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> Image(
                        bitmap = bitmap,
                        contentDescription = attachment.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )

                    // A fresh link is asked for on the press, not on its own: the
                    // first one failing is usually a link that ran out, and asking
                    // again unprompted would do it on every recomposition.
                    failed -> TileNote(str(S.desktop_tasks_attachment_load_failed)) {
                        onEvent(TasksEvent.ReloadAttachment(attachment))
                    }

                    else -> ZillitSpinner(size = 16.dp)
                }
            }
        }
        if (removable) {
            RemoveCorner(
                name = attachment.name,
                confirm = str(S.desktop_tasks_remove_image_confirm),
                onRemove = { onEvent(removeEvent) },
            )
        }
    }
}

/** An image waiting for Create: its own bytes are the preview. */
@Composable
private fun PendingThumb(item: PreparedAttachment, readOnly: Boolean, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    val bitmap = remember(item.key) { decodeImageBitmap(item.bytes) }
    Box(Modifier.size(THUMB_WIDTH, THUMB_HEIGHT).clip(TILE).background(k.surface2)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        ZillitText(
            text = str(S.desktop_tasks_attachment_saved_on_create),
            style = txt(10, FontWeight.SemiBold),
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(TILE_SCRIM)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        if (!readOnly) {
            // Nothing has been sent, so it goes at once: there is nothing to undo.
            RemoveCorner(
                name = item.name,
                confirm = null,
                onRemove = { onEvent(TasksEvent.RemovePendingAttachment(item.key)) },
            )
        }
    }
}

@Composable
private fun UploadingThumb() {
    val k = TasksTheme.c
    Column(
        modifier = Modifier.size(THUMB_WIDTH, THUMB_HEIGHT).clip(TILE).background(k.surface2),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZillitSpinner(size = 16.dp)
        TText(str(S.desktop_csync_uploading), 11, color = k.faint)
    }
}

@Composable
private fun TileNote(text: String, onRetry: () -> Unit) {
    val k = TasksTheme.c
    Box(Modifier.fillMaxSize().clickable(onClick = onRetry), contentAlignment = Alignment.Center) {
        TText(text, 11, color = k.faint)
    }
}

/**
 * The × in a tile's corner, and the "Remove image?" it arms.
 *
 * [confirm] null means there is nothing to confirm (an item that has not been
 * sent yet), so the × removes it straight away.
 */
@Composable
private fun BoxScope.RemoveCorner(name: String, confirm: String?, onRemove: () -> Unit) {
    val k = TasksTheme.c
    var armed by remember { mutableStateOf(false) }
    // Forgets the question after three seconds, as the web does.
    LaunchedEffect(armed) {
        if (armed) {
            delay(ARM_MILLIS)
            armed = false
        }
    }
    if (armed && confirm != null) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .align(Alignment.TopStart)
                .background(TILE_SCRIM)
                .clickable(onClick = onRemove)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            ZillitText(text = confirm, style = txt(10, FontWeight.SemiBold), color = Color.White)
        }
        return
    }
    // The alignment belongs to this Box, not to one inside the tooltip: a
    // tooltip lays its content out itself, and an `align` handed to it is
    // simply ignored — which put the × in the tile's top-left corner.
    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
        ZillitTooltip(str(S.bs_chip_remove, name)) {
            Box(
                modifier = Modifier.size(REMOVE_DOT)
                    .clip(RoundedCornerShape(50))
                    .background(TILE_SCRIM)
                    .clickable { if (confirm == null) onRemove() else armed = true },
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(ZillitIcons.Close, tint = Color.White, size = 10.dp)
            }
        }
    }
}

/** The files, one row each — badge, name, size, who added it, download, remove. */
@Composable
private fun Files(state: TasksUiState, panel: TaskPanel, readOnly: Boolean, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    Column(
        modifier = Modifier.fillMaxWidth().clip(TILE).border(1.dp, k.line, TILE),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        panel.files.forEachIndexed { index, attachment ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            FileRow(
                name = attachment.name,
                sizeBytes = attachment.fileSizeBytes,
                meta = addedBy(attachment, state),
                onDownload = { onEvent(TasksEvent.DownloadAttachment(attachment)) },
                onRemove = { onEvent(TasksEvent.RemoveAttachment(attachment)) }
                    .takeIf { !readOnly && canRemoveAttachment(attachment, panel.task, state.me) },
                pending = false,
            )
        }
        panel.keptDraftDocs.forEachIndexed { index, attachment ->
            if (index > 0 || panel.files.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            FileRow(
                name = attachment.name,
                sizeBytes = attachment.fileSizeBytes,
                meta = str(S.desktop_tasks_attachment_saved_when_created),
                onDownload = null,
                onRemove = { onEvent(TasksEvent.RemovePendingAttachment(DRAFT_FILE_PREFIX + attachment.id)) }
                    .takeIf { !readOnly },
                pending = true,
            )
        }
        panel.pendingFiles.forEachIndexed { index, item ->
            if (index > 0 || panel.files.isNotEmpty() || panel.keptDraftDocs.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            }
            FileRow(
                name = item.name,
                sizeBytes = item.sizeBytes,
                meta = str(S.desktop_tasks_attachment_saved_when_created),
                onDownload = null,
                onRemove = { onEvent(TasksEvent.RemovePendingAttachment(item.key)) }.takeIf { !readOnly },
                pending = true,
            )
        }
    }
}

@Composable
@Suppress("LongMethod") // One row: badge, name, size, who, download, remove — and its confirm.
private fun FileRow(
    name: String,
    sizeBytes: Long,
    meta: String,
    onDownload: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    pending: Boolean,
) {
    val k = TasksTheme.c
    val badge = docBadge(name)
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(ARM_MILLIS)
            armed = false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTooltip(badge.typeName) {
            Box(
                modifier = Modifier.width(BADGE_WIDTH)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(badge.colour))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(text = badge.label, style = txt(10, FontWeight.Bold), color = Color.White)
            }
        }
        Column(
            modifier = Modifier.weight(1f)
                .then(if (onDownload != null) Modifier.clickable(onClick = onDownload) else Modifier),
        ) {
            TText(name, 13, FontWeight.Medium, maxLines = 1)
            TText("${formatAttachmentSize(sizeBytes)} · $meta", 11, color = k.faint, maxLines = 1)
        }
        if (onDownload != null) {
            ZillitTooltip(str(S.desktop_docdist_download_named, name)) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDownload).padding(4.dp)) {
                    ZillitIcon(ZillitIcons.Download, tint = k.faint, size = 15.dp)
                }
            }
        }
        if (onRemove == null) return@Row
        if (armed || pending) {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(k.surface2)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                TText(
                    if (pending) str(S.remove) else str(S.desktop_tasks_remove_file_confirm),
                    11,
                    FontWeight.SemiBold,
                    k.late,
                )
            }
        } else {
            ZillitTooltip(str(S.bs_chip_remove, name)) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { armed = true }.padding(4.dp)) {
                    ZillitIcon(ZillitIcons.Close, tint = k.faint, size = 14.dp)
                }
            }
        }
    }
}

/**
 * The full-size viewer: the image, its name, size and who added it; ‹ › move
 * between the images, and the panel's own Escape closes this first.
 *
 * Drawn by [TaskPanelOverlay] over the panel rather than by the description,
 * and inside the tool — the same rule every overlay in this module follows.
 * Which image is open is [TaskPanel.viewing], because the thumbnail that
 * opens it and this are in different parts of the panel.
 */
@Composable
@Suppress("LongMethod") // One overlay: header, stage, and the two steps.
internal fun AttachmentViewerOverlay(state: TasksUiState, panel: TaskPanel, onEvent: (TasksEvent) -> Unit) {
    val images = panel.images
    val openId = panel.viewing ?: return
    val index = images.indexOfFirst { it.id == openId }
    // Gone: removed here or by somebody else while it was open.
    if (index < 0) {
        LaunchedEffect(openId) { onEvent(TasksEvent.CloseAttachment) }
        return
    }
    val attachment = images[index]
    val bytes = panel.imageBytes[attachment.id]
    val bitmap = remember(bytes) { bytes?.let { decodeImageBitmap(it) } }
    LaunchedEffect(attachment.id) { onEvent(TasksEvent.LoadAttachment(attachment)) }
    val close = { onEvent(TasksEvent.CloseAttachment) }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = VIEWER_SCRIM))
            .clickable(onClick = close),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TText(attachment.name, 13, FontWeight.SemiBold, Color.White, maxLines = 1)
                    TText(
                        viewerDetail(attachment, state),
                        11,
                        color = Color.White.copy(alpha = FAINT_WHITE),
                        maxLines = 1,
                    )
                }
                TText(
                    str(S.docusign_tour_progress, index + 1, images.size),
                    11,
                    color = Color.White.copy(alpha = FAINT_WHITE),
                )
                ZillitTooltip(str(S.close)) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = close).padding(6.dp)) {
                        ZillitIcon(ZillitIcons.Close, tint = Color.White, size = 16.dp)
                    }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    bitmap != null -> Image(
                        bitmap = bitmap,
                        contentDescription = attachment.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )

                    attachment.id in panel.imagesFailed ->
                        TText(str(S.desktop_tasks_attachment_load_failed), 12, color = Color.White)

                    else -> ZillitSpinner(size = 20.dp)
                }
                if (index > 0) {
                    ViewerStep(str(S.desktop_tasks_attachment_prev), Alignment.CenterStart, "‹") {
                        onEvent(TasksEvent.OpenAttachment(images[index - 1].id))
                    }
                }
                if (index < images.size - 1) {
                    ViewerStep(str(S.desktop_tasks_attachment_next), Alignment.CenterEnd, "›") {
                        onEvent(TasksEvent.OpenAttachment(images[index + 1].id))
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ViewerStep(label: String, at: Alignment, glyph: String, onClick: () -> Unit) {
    // The alignment is this Box's, not one inside the tooltip — see RemoveCorner.
    Box(Modifier.align(at).padding(8.dp)) {
        ZillitTooltip(label) {
            Box(
                modifier = Modifier.size(STEP_DOT)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = STEP_SCRIM))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(text = glyph, style = txt(18, FontWeight.Bold), color = Color.White)
            }
        }
    }
}

/** "1.2 MB · 2000×1333 · added by Riya Sen". */
private fun viewerDetail(attachment: TaskAttachment, state: TasksUiState): String = listOfNotNull(
    formatAttachmentSize(attachment.fileSizeBytes),
    "${attachment.width}×${attachment.height}".takeIf { attachment.width > 0 && attachment.height > 0 },
    attachment.uploadedBy?.let { id ->
        state.crew.firstOrNull { it.id == id }?.fullName?.let { str(S.desktop_tasks_attachment_added_by, it) }
    },
).joinToString(" · ")

/**
 * Who added it.
 *
 * The web puts "· 2 days ago" after the name; the row here shows the size and
 * the name only, because this module has no relative-time wording of its own
 * and the History tab already says when each one arrived.
 */
@Composable
private fun addedBy(attachment: TaskAttachment, state: TasksUiState): String =
    attachment.uploadedBy?.let { id -> state.crew.firstOrNull { it.id == id }?.fullName }
        ?: str(S.desktop_stk_unknown_person)

private val THUMB_WIDTH = 128.dp
private val THUMB_HEIGHT = 96.dp
private val BADGE_WIDTH = 34.dp
private val REMOVE_DOT = 18.dp
private val STEP_DOT = 32.dp
private val TILE = RoundedCornerShape(8.dp)
private val TILE_SCRIM = Color.Black.copy(alpha = 0.55f)
private const val ARM_MILLIS = 3_000L
private const val VIEWER_SCRIM = 0.82f
private const val STEP_SCRIM = 0.45f
private const val FAINT_WHITE = 0.72f
