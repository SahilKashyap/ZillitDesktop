@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitDropOverlay
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.externalPathDrop
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.data.FileStatus
import com.zillit.desktop.feature.selectstills.data.UploadItem
import com.zillit.desktop.feature.selectstills.data.UploadQueueState
import com.zillit.desktop.feature.selectstills.data.countsOf
import com.zillit.desktop.feature.selectstills.data.queueRows
import com.zillit.desktop.feature.selectstills.domain.formatBytes

/**
 * The web's Upload page (Photographer upload, before the 9 October terms
 * pass): drop the whole card.
 *
 * Each file goes straight from here to the production's storage, exactly as the
 * camera wrote it — nothing is resized on the way — and is then queued; faces
 * are found in the background and the gallery fills in live. The upload belongs
 * to the tool, not to this page: it keeps going on the other pages, and the top
 * bar shows how far it is.
 *
 * The web listed the uploaded photos under the card; here they are in the
 * gallery, one click away, so a card of 500 is not drawn twice.
 *
 * On a tool at least 1100 wide the list of files stands beside the drop zone
 * instead of under it, and is taller, so the page uses its width and does not
 * grow down.
 */
@Composable
internal fun UploadPage(state: StillsUiState, uploads: UploadQueueState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    var over by remember { mutableStateOf(false) }
    val beside = stillsWide() && uploads.items.isNotEmpty()

    // The rail, but not the wheel fix: the page holds the upload list, which
    // scrolls on its own, and a wheel fix here would swallow that list's wheel.
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SPageHead(str(S.upload), str(S.desktop_stk_upload_lede))

            when {
                // This production cannot use the tool at all.
                !state.me.usable -> SStatePanel(
                    title = str(S.desktop_stk_unusable_title),
                    hint = if (state.me.storageSupported) str(S.desktop_stk_unusable_region) else str(S.desktop_stk_unusable_storage),
                    bad = true,
                )
                !state.me.settings.attested -> FaceDataNotice(state.upload.accepting, onEvent)
                beside -> SCard(Modifier.fillMaxWidth()) {
                    // `grid-template-columns: minmax(360px, 5fr) 7fr`
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(Modifier.weight(5f).widthIn(min = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            UploadControls(state, uploads, over, { over = it }, onEvent, tall = true)
                        }
                        Box(Modifier.weight(7f)) { UploadQueuePanel(uploads, onEvent, listMax = 520.dp) }
                    }
                }
                else -> SCard(Modifier.fillMaxWidth()) {
                    UploadControls(state, uploads, over, { over = it }, onEvent, tall = false)
                    UploadQueuePanel(uploads, onEvent, listMax = 320.dp)
                    UploadFoot(state, onEvent)
                }
            }
        }
        ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * The card's own controls: a refusal the queue stopped on, the shoot label and
 * the drop zone — and, on a wide tool where the list stands beside them, the
 * line that opens the gallery.
 */
@Composable
private fun UploadControls(
    state: StillsUiState,
    uploads: UploadQueueState,
    over: Boolean,
    onOver: (Boolean) -> Unit,
    onEvent: (StillsEvent) -> Unit,
    tall: Boolean,
) {
    val k = StillsTheme.c
    if (uploads.stopped == "still_kills_not_attested") {
        SAlert(SAlertTone.Hint) { SText(str(S.desktop_stk_notice_needed), 15, color = k.accentInk) }
    }

    SField(str(S.desktop_stk_shoot_label), Modifier.widthIn(max = 360.dp)) {
        SInput(
            value = state.upload.shootLabel,
            onChange = { onEvent(StillsEvent.ShootLabel(it)) },
            placeholder = str(S.desktop_stk_shoot_label_placeholder),
            maxLength = 80,
        )
    }

    // "image/jpeg" → "JPG", as the web's hint names the types.
    val types = state.me.upload.types.joinToString(", ") { type ->
        when (type) {
            "image/jpeg" -> "JPG"
            "image/webp" -> "WebP"
            else -> type.removePrefix("image/").uppercase()
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            // A card is usually dragged in as a folder, so what is dropped
            // arrives by path and is walked through there, never read into
            // memory: 500 × 40 MB would not fit.
            .externalPathDrop(
                enabled = true,
                onHover = onOver,
                onPaths = { paths -> onEvent(StillsEvent.QueueFolders(paths)) },
            ),
    ) {
        SDropZone(
            label = str(S.desktop_stk_upload_drop),
            hint = str(S.desktop_stk_upload_drop_hint, types, formatBytes(state.me.upload.maxBytes)),
            onClick = { onEvent(StillsEvent.PickUploads) },
            over = over,
            secondary = str(S.desktop_stk_upload_choose_folder) to { onEvent(StillsEvent.ChooseFolder) },
            padding = if (tall) PaddingValues(horizontal = 30.dp, vertical = 56.dp) else PaddingValues(30.dp),
        )
        if (over) {
            ZillitDropOverlay(
                title = str(S.desktop_stk_upload_drop),
                hint = str(S.desktop_stk_upload_drop_hint, types, formatBytes(state.me.upload.maxBytes)),
            )
        }
    }

    if (tall) UploadFoot(state, onEvent)
}

/** "12 processing · open the gallery" under the card. */
@Composable
private fun UploadFoot(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.me.queueWaiting > 0) {
            SText("${str(S.desktop_stk_n_processing, state.me.queueWaiting)} ·", 13, color = k.muted)
        }
        SBtn(str(S.desktop_stk_open_gallery), { onEvent(StillsEvent.Open(StillsPage.Photos)) }, kind = SBtnKind.Link, small = true)
    }
}

/**
 * The notice the first posting user acknowledges before anything is uploaded or
 * enrolled — the web's `FaceDataNotice`, shown on both the upload and the
 * enrol page.
 */
@Composable
internal fun FaceDataNotice(busy: Boolean, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    SStatePanel(str(S.desktop_stk_notice_title), str(S.desktop_stk_notice_body)) {
        Column(Modifier.padding(top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                S.desktop_stk_notice_point_faces,
                S.desktop_stk_notice_point_consent,
                S.desktop_stk_notice_point_delete,
            ).forEach { key ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SText("•", 15, color = k.muted)
                    SText(str(key), 15, color = k.muted, modifier = Modifier.weight(1f))
                }
            }
        }
        SBtn(
            text = if (busy) str(S.ah_saving) else str(S.desktop_stk_notice_accept),
            onClick = { onEvent(StillsEvent.AcceptNotice) },
            kind = SBtnKind.Primary,
            enabled = !busy,
        )
    }
}

/**
 * `.stk-queue` — the upload in progress: how far it is, what went wrong, and
 * what can be done about each file.
 *
 * Under the progress line, every file of the upload with its state, in a box
 * that scrolls ([queueRows] says in what order) — from the moment it is chosen
 * until the list is cleared. Do not go back to listing only the moving files:
 * with three lanes that is two or three rows that come and go, and nothing at
 * all between two files. A very large card is listed up to [MAX_ROWS]; the
 * rest are counted.
 *
 * The box is a lazy list WITHOUT keys, on purpose: rows change place as files
 * move through, and a keyed list keeps its place by following the first row
 * it shows — down the list, until the box sits on the oldest files (the web's
 * `overflow-anchor` trap). By index, it stays where the reader left it.
 */
@Composable
private fun UploadQueuePanel(uploads: UploadQueueState, onEvent: (StillsEvent) -> Unit, listMax: Dp) {
    val k = StillsTheme.c
    if (uploads.items.isEmpty()) return
    val counts = countsOf(uploads.items)
    val shown = remember(uploads.items) { queueRows(uploads.items).take(MAX_ROWS) }
    val retryable = uploads.items.any { (it.status == FileStatus.Failed || it.status == FileStatus.Cancelled) && it.path.isNotBlank() }
    val finished = uploads.items.any { !it.status.isActive }

    val label = str(S.desktop_stk_upload_queue)
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = label }.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SProgress(counts.percent)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val line = listOfNotNull(
                str(S.desktop_stk_upload_progress, counts.done, counts.expected),
                counts.failed.takeIf { it > 0 }?.let { str(S.desktop_stk_upload_failed_n, it) },
                counts.duplicate.takeIf { it > 0 }?.let { str(S.desktop_stk_upload_duplicate_n, it) },
                counts.refused.takeIf { it > 0 }?.let { str(S.desktop_stk_upload_refused_n, it) },
                counts.cancelled.takeIf { it > 0 }?.let { str(S.desktop_stk_upload_cancelled_n, it) },
                "${formatBytes(counts.sent)} / ${formatBytes(counts.bytes)}",
                str(S.desktop_stk_upload_offline).takeIf { uploads.offline },
                uploads.stopped.takeIf { it == "project" }?.let { str(S.desktop_stk_upload_reason_project) },
                str(S.desktop_stk_upload_now_processing).takeIf { counts.done > 0 && !uploads.running },
            ).joinToString(" · ")
            SText(line, 13, color = k.muted, modifier = Modifier.weight(1f))

            if (uploads.running) SBtn(str(S.stop), { onEvent(StillsEvent.CancelUploads) }, small = true)
            if (retryable && !uploads.running) SBtn(str(S.desktop_stk_upload_retry_all), { onEvent(StillsEvent.RetryUpload(null)) }, small = true)
            if (finished) SBtn(str(S.desktop_stk_upload_clear), { onEvent(StillsEvent.ClearUploads) }, kind = SBtnKind.Link, small = true)
        }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            ZillitLazyColumn(Modifier.fillMaxWidth().heightIn(max = listMax)) {
                items(shown.size) { index -> QueueRow(shown[index], onEvent) }
            }
        }
        val over = uploads.items.size - MAX_ROWS
        if (over > 0) SText(str(S.desktop_stk_upload_more_rows, over), 13, color = k.muted)
    }
}

/** One row of `.stk-queue-list`: the name, the size, where it is, and what can be done. */
@Composable
private fun QueueRow(item: UploadItem, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val state = when (item.status) {
        FileStatus.Waiting -> str(S.ah_run_detail_tier_waiting)
        FileStatus.Signing -> str(S.desktop_stk_upload_preparing)
        FileStatus.Uploading -> "${(item.progress * 100).toInt()}%"
        // In storage: confirmed or about to be. Counted as uploaded, so shown as uploaded.
        FileStatus.Uploaded, FileStatus.Done -> str(S.sides_uploaded)
        FileStatus.Duplicate -> str(S.desktop_stk_upload_already_here)
        FileStatus.Failed, FileStatus.Refused, FileStatus.Cancelled -> reasonWords(item.reason)
    }
    val tone = when (item.status) {
        FileStatus.Failed, FileStatus.Refused -> k.warn
        FileStatus.Duplicate, FileStatus.Cancelled -> k.accentInk
        FileStatus.Uploaded, FileStatus.Done -> k.ok
        else -> k.muted
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(k.panel)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SText(item.name, 13, color = if (item.status == FileStatus.Waiting) k.muted else k.ink, modifier = Modifier.weight(1f), maxLines = 1)
            SText(formatBytes(item.size), 13, color = k.muted, maxLines = 1)
            SText(state, 13, color = tone, modifier = Modifier.weight(1.2f), maxLines = 1)
            when {
                (item.status == FileStatus.Failed || item.status == FileStatus.Cancelled) && item.path.isNotBlank() ->
                    SBtn(str(S.desktop_csync_try_again), { onEvent(StillsEvent.RetryUpload(item.ref)) }, small = true)
                item.status == FileStatus.Duplicate && item.path.isNotBlank() ->
                    SBtn(str(S.desktop_stk_upload_anyway), { onEvent(StillsEvent.UploadAnyway(item.ref)) }, small = true)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
    }
}

/**
 * Why a file did not go, in words. A reason is either one of this screen's own
 * (the file was turned away before anything was sent, or the transfer failed)
 * or the service's message key, which the messages dictionary translates.
 */
@Composable
private fun reasonWords(reason: String): String {
    if (reason.isBlank()) return ""
    val own = when (reason) {
        "raw" -> S.desktop_stk_upload_reason_raw
        "type" -> S.desktop_stk_upload_reason_type
        "empty" -> S.desktop_stk_upload_reason_empty
        "size" -> S.desktop_stk_upload_reason_size
        "network" -> S.desktop_stk_upload_reason_network
        "upload" -> S.desktop_stk_upload_reason_upload
        "missing" -> S.desktop_stk_upload_reason_missing
        "project" -> S.desktop_stk_upload_reason_project
        "cancelled" -> S.desktop_csync_cues_stopped
        else -> null
    }
    return if (own != null) {
        str(own)
    } else {
        com.zillit.desktop.core.localization.Labels.translate(reason, com.zillit.desktop.core.localization.LabelKind.Messages)
    }
}

/** A card of more than this many files is listed up to it, and the rest are counted. */
private const val MAX_ROWS = 1000
