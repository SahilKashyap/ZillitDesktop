// Bulk upload: the file list before sending, its sheet, and Ongoing Uploads after.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.externalFileDrop
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.BulkCounter
import com.zillit.desktop.feature.invoices.domain.BulkCounterKind
import com.zillit.desktop.feature.invoices.domain.BulkFile
import com.zillit.desktop.feature.invoices.domain.BulkFileProblem
import com.zillit.desktop.feature.invoices.domain.BulkPhase
import com.zillit.desktop.feature.invoices.domain.BulkRow
import com.zillit.desktop.feature.invoices.domain.BulkUploads
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.ui.BulkPick
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The department's Upload Invoices — the web's `BulkUploadModal`: files
 * only, no accounts form. Extraction, the vendor and the invoice itself are
 * the server's work once the files are sent.
 */
@Composable
internal fun BulkUploadSheet(pick: BulkPick, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_inv_upload_invoices),
        visible = true,
        onDismiss = { onEvent(InboxEvent.CancelBulk) },
        icon = ZillitIcons.Upload,
        actions = { BulkActions(pick, onEvent) },
    ) {
        BulkPanel(pick, onEvent)
    }
}

/**
 * The panel's action bar — "Uploading continues in the background" once
 * something is picked, Clear, and "Upload & Submit (N)" ("Checking…" while
 * the files are read). Lifted into whichever dialog hosts the panel, as the
 * web's `actionsInFooter` lifts it; [cancel] adds the host's own Cancel.
 */
@Composable
internal fun RowScope.BulkActions(pick: BulkPick, onEvent: (InvoicesEvent) -> Unit, cancel: (() -> Unit)? = null) {
    if (pick.files.isNotEmpty()) {
        ZillitText(
            text = str(S.desktop_inv_uploading_continues),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
    } else {
        Spacer(Modifier.weight(1f))
    }
    cancel?.let {
        ZillitButton(text = str(S.cancel), onClick = it, variant = ButtonVariant.Secondary)
    }
    if (pick.files.isNotEmpty() && !pick.checking) {
        ZillitButton(
            text = str(S.ah_clear),
            onClick = { onEvent(InboxEvent.ClearBulk) },
            variant = ButtonVariant.Tertiary,
        )
    }
    ZillitButton(
        text = when {
            pick.checking -> str(S.dm_nda_checking)
            pick.files.isEmpty() -> str(S.desktop_inv_upload_and_submit)
            else -> str(S.desktop_inv_upload_and_submit_n, pick.files.size)
        },
        onClick = { onEvent(InboxEvent.SubmitBulk) },
        leadingIcon = ZillitIcons.Upload,
        enabled = pick.canSubmit,
        loading = pick.checking,
    )
}

/**
 * The dropzone and the files picked, each with why it will not be sent when
 * it will not — the one moment a refusal can be shown beside its file name.
 * Nothing is sent until every file passes and there are no more than ten;
 * the banners say which, and that nothing has been uploaded. The
 * accountant's upload may mark files already paid, one by one or all at once.
 */
@Composable
internal fun ColumnScope.BulkPanel(pick: BulkPick, onEvent: (InvoicesEvent) -> Unit) {
    DropZone(pick, onEvent)
    if (pick.files.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium),
        ) {
            if (pick.allowPaid) PaidHeader(pick, onEvent)
            pick.files.forEach { file -> PickedRow(file, pick, onEvent) }
        }
    }
    if (pick.tooMany) {
        Banner(
            str(
                S.desktop_inv_too_many_selected,
                pick.files.size,
                BulkUploads.MAX_BATCH_FILES,
                pick.files.size - BulkUploads.MAX_BATCH_FILES,
            ),
        )
    }
    if (pick.rejected > 0) {
        Banner(
            if (pick.rejected == 1) {
                str(S.desktop_inv_one_file_cannot_be_sent)
            } else {
                str(S.desktop_inv_n_files_cannot_be_sent, pick.rejected)
            },
        )
    }
}

/** "Drop invoices here, or click to browse" — a click opens the picker, a drop from the OS adds its files. */
@Composable
private fun DropZone(pick: BulkPick, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var hovering by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (hovering) colors.accent.copy(alpha = DROP_TINT) else colors.surfaceSunken)
            .border(DROP_BORDER, if (hovering) colors.accent else colors.border, ZillitTheme.shapes.large)
            .externalFileDrop(
                enabled = !pick.checking,
                onHover = { hovering = it },
                onFiles = { dropped ->
                    onEvent(
                        InboxEvent.DropBulkFiles(
                            dropped.map { PickedInvoiceFile(name = it.name, contentType = it.contentType, bytes = it.bytes) },
                        ),
                    )
                },
            )
            .clickable(enabled = !pick.checking) { onEvent(InboxEvent.AddBulkFiles) }
            .padding(vertical = ZillitTheme.spacing.xl, horizontal = ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (pick.checking) {
            ZillitSpinner(size = DROP_ICON)
        } else {
            ZillitIcon(icon = ZillitIcons.Upload, tint = colors.accent, size = DROP_ICON)
        }
        ZillitText(
            text = str(S.desktop_inv_drop_invoices_here),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        )
        ZillitText(
            text = str(
                S.desktop_inv_drop_hint,
                BulkUploads.MAX_BATCH_FILES,
                (BulkUploads.MAX_FILE_BYTES / BYTES_PER_MB_LONG).toInt(),
                BulkUploads.MAX_PDF_PAGES,
            ),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    }
}

/** "N files · Paid [switch]" — a setter for every row, reading "are they all on". */
@Composable
private fun PaidHeader(pick: BulkPick, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val count = pick.files.size
        ZillitText(
            text = (if (count == 1) str(S.desktop_drive_contains_one_file) else str(S.drive_files_format, count))
                .uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = str(S.desktop_paid).uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitSwitch(
            checked = pick.allPaid,
            onCheckedChange = { onEvent(InboxEvent.SetAllBulkPaid(it)) },
            enabled = !pick.checking,
        )
        Spacer(Modifier.padding(horizontal = ZillitTheme.spacing.md))
    }
}

@Composable
private fun PickedRow(file: BulkFile, pick: BulkPick, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(text = file.name, style = ZillitTheme.typography.bodySmall, maxLines = 1)
            file.problem?.let {
                ZillitText(text = it.message(), style = ZillitTheme.typography.labelSmall, color = colors.danger)
            }
        }
        ZillitText(
            text = readableSize(file.size),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        if (pick.allowPaid) {
            ZillitSwitch(
                checked = file.paid,
                onCheckedChange = { onEvent(InboxEvent.ToggleBulkPaid(file.ref)) },
                enabled = !pick.checking,
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.remove),
            onClick = { onEvent(InboxEvent.RemoveBulkFile(file.ref)) },
            enabled = !pick.checking,
        )
    }
}

/** A red banner under the list — nothing has been uploaded, and why. */
@Composable
private fun Banner(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.danger,
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.dangerSoft, ZillitTheme.shapes.medium)
            .border(1.dp, ZillitTheme.colors.danger.copy(alpha = BANNER_BORDER_ALPHA), ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
    )
}

internal fun BulkFileProblem.message(): String = when (this) {
    BulkFileProblem.WrongType -> str(S.desktop_inv_file_wrong_type)
    BulkFileProblem.TooBig -> str(S.desktop_hub_file_must_be_10mb_or_smaller)
    BulkFileProblem.TooManyPages -> str(S.desktop_inv_file_too_many_pages, BulkUploads.MAX_PDF_PAGES)
    BulkFileProblem.Unreadable -> str(S.desktop_inv_file_unreadable)
}

/** `readableSize`: "2.4 MB" to one place, or whole KB under a megabyte. */
private fun readableSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / BYTES_PER_MB
    if (mb < 1.0) return "${(bytes / KB).coerceAtLeast(1)} KB"
    val tenths = kotlin.math.round(mb * TENTHS).toLong()
    return "${tenths / TENTHS.toLong()}.${tenths % TENTHS.toLong()} MB"
}

// -- Ongoing Uploads -------------------------------------------------------------------

/**
 * Ongoing Uploads — the web's `UploadsTab`: every batch the server is still
 * extracting (anyone's) merged with this session's own, in counts rather than
 * file names, with a bar that fills while sending and again while extracting.
 * While anything is still moving the list is re-read every eight seconds, as
 * cover for a progress frame that never arrives — an idle tab asks nothing.
 */
@Composable
internal fun ColumnScope.UploadsCard(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val rows = state.uploadRows
    val busy = BulkUploads.busy(rows)
    val events by rememberUpdatedState(onEvent)
    LaunchedEffect(busy) {
        while (busy) {
            delay(BulkUploads.POLL_MS)
            events(InboxEvent.RefreshUploads)
        }
    }
    ZillitSectionCard(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        title = str(S.desktop_inv_ongoing_uploads),
        icon = ZillitIcons.Inbox,
    ) {
        if (rows.isEmpty()) {
            MutedLine(str(S.desktop_inv_uploads_empty_hint))
            return@ZillitSectionCard
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            rows.forEach { row -> BatchRow(row, onEvent) }
        }
    }
}

@Composable
private fun BatchRow(row: BulkRow, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val retryable = BulkUploads.retryable(row.local).size
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (row.phase) {
                BulkPhase.Error -> ZillitIcon(ZillitIcons.Warning, tint = colors.warning, size = FILE_ICON)
                BulkPhase.Done -> ZillitIcon(ZillitIcons.Check, tint = colors.success, size = FILE_ICON)
                else -> ZillitSpinner(size = FILE_ICON)
            }
            ZillitText(
                text = row.summary(),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (row.createdAtMs > 0) MutedLine(startedAt(row.createdAtMs))
            if (row.elsewhere) MutedLine(str(S.desktop_inv_uploaded_elsewhere))
            if (retryable > 0) {
                ZillitButton(
                    text = str(S.desktop_inv_retry_n, retryable),
                    onClick = { onEvent(InboxEvent.RetryBatch(row.id)) },
                    variant = ButtonVariant.Secondary,
                )
            }
            // Ours only, and only once it has stopped: mid-flight a cross would read as "cancel".
            if (!row.elsewhere && (row.phase == BulkPhase.Done || row.phase == BulkPhase.Error)) {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.sync_action_dismiss),
                    onClick = { onEvent(InboxEvent.DismissBatch(row.id)) },
                )
            }
        }
        Counters(BulkUploads.counters(row))
        if (row.phase == BulkPhase.Error) {
            val cause = row.local?.error?.takeIf { it.isNotBlank() } ?: str(S.dm_nda_upload_failed)
            val tail = if ((row.local?.sentCount ?: 0) > 0) {
                str(S.desktop_inv_upload_error_queued_tail)
            } else {
                str(S.desktop_inv_upload_error_safe_tail)
            }
            ZillitText(text = cause + tail, style = ZillitTheme.typography.labelSmall, color = colors.danger)
        }
        if (row.phase == BulkPhase.Uploading || row.phase == BulkPhase.Processing) ProgressBar(row.progress)
    }
}

/** `batchCounters` in the server's own words; the failure kinds in red, zeros left out. */
@Composable
private fun Counters(counters: List<BulkCounter>) {
    if (counters.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        counters.forEach { counter ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = counter.kind.label().uppercase(),
                    style = ZillitTheme.typography.columnHeader,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitText(
                    text = counter.value.toString(),
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                    color = if (counter.kind.bad) ZillitTheme.colors.danger else ZillitTheme.colors.textPrimary,
                )
            }
        }
    }
}

private fun BulkCounterKind.label(): String = when (this) {
    BulkCounterKind.Uploading -> str(S.txt_uploading)
    BulkCounterKind.Sent -> str(S.cs_sent)
    BulkCounterKind.Completed -> str(S.completed)
    BulkCounterKind.Pending -> str(S.pending)
    BulkCounterKind.Failed -> str(S.dd_legend_failed)
    BulkCounterKind.FailedUpload -> str(S.desktop_inv_counter_failed_upload)
    BulkCounterKind.SendFailed -> str(S.desktop_inv_counter_send_failed)
    BulkCounterKind.Rejected -> str(S.rejected)
}

@Composable
private fun ProgressBar(value: Float) {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(ZillitTheme.shapes.pill)
            .background(colors.border),
    ) {
        Box(
            Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(colors.accent),
        )
    }
}

/** When the batch started — the time alone, as the web's `startedAt` shows it. */
private fun startedAt(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/** The row's headline, in words — `batchSummary`; the counters carry the numbers. */
private fun BulkRow.summary(): String = when (phase) {
    BulkPhase.Uploading -> str(S.ah_uploading)
    BulkPhase.Processing -> str(S.desktop_extracting)
    BulkPhase.Error -> str(S.desktop_inv_nothing_was_sent)
    BulkPhase.Done -> str(S.desktop_inv_upload_complete)
}

private val FILE_ICON = 16.dp
private val BAR_HEIGHT = 4.dp
private val DROP_ICON = 32.dp
private val DROP_BORDER = 2.dp
private const val DROP_TINT = 0.06f
private const val BANNER_BORDER_ALPHA = 0.3f
private const val BYTES_PER_MB = 1024.0 * 1024.0
private const val BYTES_PER_MB_LONG = 1024L * 1024L
private const val KB = 1024L
private const val TENTHS = 10.0
