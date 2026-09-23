// Bulk upload: the file list before sending, its sheet, and Ongoing Uploads after.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.BulkFile
import com.zillit.desktop.feature.invoices.domain.BulkFileProblem
import com.zillit.desktop.feature.invoices.domain.BulkFileStatus
import com.zillit.desktop.feature.invoices.domain.BulkPhase
import com.zillit.desktop.feature.invoices.domain.BulkRow
import com.zillit.desktop.feature.invoices.domain.BulkUploads
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.ui.BulkPick
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

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

/** Clear and Upload & Submit — the panel's footer, lifted into whichever dialog hosts it. */
@Composable
internal fun BulkActions(pick: BulkPick, onEvent: (InvoicesEvent) -> Unit) {
    ZillitButton(
        text = str(S.cancel),
        onClick = { onEvent(InboxEvent.CancelBulk) },
        variant = ButtonVariant.Tertiary,
    )
    ZillitButton(
        text = str(S.desktop_inv_upload_and_submit),
        onClick = { onEvent(InboxEvent.SubmitBulk) },
        leadingIcon = ZillitIcons.Upload,
        enabled = pick.sendable > 0 && !pick.checking,
    )
}

/**
 * The files picked, each with why it will not be sent when it will not — the
 * one moment a refusal can be shown beside its file name. The accountant's
 * upload may mark a file already paid.
 */
@Composable
internal fun ColumnScope.BulkPanel(pick: BulkPick, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = str(S.desktop_drive_choose_files),
            onClick = { onEvent(InboxEvent.AddBulkFiles) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Paperclip,
            enabled = !pick.checking && pick.files.size < BulkUploads.MAX_BATCH_FILES,
        )
        if (pick.checking) ZillitSpinner()
        MutedLine(str(S.desktop_inv_too_many_files, BulkUploads.MAX_BATCH_FILES))
    }
    pick.files.forEach { file -> PickedRow(file, pick.allowPaid, onEvent) }
}

@Composable
private fun PickedRow(file: BulkFile, allowPaid: Boolean, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(
            icon = if (file.problem == null) ZillitIcons.File else ZillitIcons.Warning,
            tint = if (file.problem == null) colors.textMuted else colors.danger,
            size = FILE_ICON,
        )
        Column(Modifier.weight(1f)) {
            ZillitText(text = file.name, style = ZillitTheme.typography.bodySmall, maxLines = 1)
            val problem = file.problem
            ZillitText(
                text = if (problem != null) problem.message() else readableSize(file.size),
                style = ZillitTheme.typography.labelSmall,
                color = if (problem != null) colors.danger else colors.textMuted,
            )
        }
        if (allowPaid && file.problem == null) {
            ZillitCheckbox(
                checked = file.paid,
                onCheckedChange = { onEvent(InboxEvent.ToggleBulkPaid(file.ref)) },
                label = str(S.desktop_paid),
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.remove),
            onClick = { onEvent(InboxEvent.RemoveBulkFile(file.ref)) },
        )
    }
}

internal fun BulkFileProblem.message(): String = when (this) {
    BulkFileProblem.WrongType -> str(S.desktop_inv_file_wrong_type)
    BulkFileProblem.TooBig -> str(S.desktop_hub_file_must_be_10mb_or_smaller)
    BulkFileProblem.TooManyPages -> str(S.desktop_inv_file_too_many_pages, BulkUploads.MAX_PDF_PAGES)
    BulkFileProblem.Unreadable -> str(S.desktop_inv_file_unreadable)
}

private fun readableSize(bytes: Long): String {
    val mb = bytes / BYTES_PER_MB
    if (mb < 1.0) return "${(bytes / KB).coerceAtLeast(1)} KB"
    return "${InvoiceFormat.plain(mb).trimEnd('0').trimEnd('.')} MB"
}

// -- Ongoing Uploads -------------------------------------------------------------------

/**
 * Ongoing Uploads — the web's `UploadsTab`: every batch the server is still
 * extracting (anyone's) merged with this session's own, in counts rather than
 * file names, with a bar that fills while sending and again while extracting.
 */
@Composable
internal fun ColumnScope.UploadsCard(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val rows = state.uploadRows
    ZillitSectionCard(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        title = str(S.desktop_inv_ongoing_uploads),
        // No Refresh: `invoice:bulk_upload_progress` re-reads the counts, as the web's tab does.
        icon = ZillitIcons.Inbox,
    ) {
        if (rows.isEmpty()) {
            MutedLine(str(S.desktop_inv_uploads_empty))
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
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (row.phase) {
                BulkPhase.Error -> ZillitIcon(ZillitIcons.Warning, tint = colors.danger, size = FILE_ICON)
                BulkPhase.Done -> ZillitIcon(ZillitIcons.Check, tint = colors.success, size = FILE_ICON)
                else -> ZillitSpinner(size = FILE_ICON)
            }
            ZillitText(
                text = row.summary(),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (row.createdAtMs > 0) MutedLine(InvoiceFormat.dateTime(row.createdAtMs))
            if (row.elsewhere) MutedLine(str(S.desktop_inv_uploaded_elsewhere))
            if (!row.elsewhere && (row.phase == BulkPhase.Done || row.phase == BulkPhase.Error)) {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.sync_action_dismiss),
                    onClick = { onEvent(InboxEvent.DismissBatch(row.id)) },
                )
            }
        }
        Counters(row)
        if (row.phase == BulkPhase.Error) {
            val warning = if ((row.local?.sentCount ?: 0) > 0) {
                str(S.desktop_inv_upload_sent_elsewhere_warning)
            } else {
                str(S.desktop_inv_upload_safe_to_retry)
            }
            ZillitText(text = warning, style = ZillitTheme.typography.labelSmall, color = colors.danger)
        }
        if (row.phase == BulkPhase.Uploading || row.phase == BulkPhase.Processing) ProgressBar(row.progress)
    }
}

/** Sent, completed, pending and failed — the server's own words, the client's refusals added. */
@Composable
private fun Counters(row: BulkRow) {
    val local = row.local
    val server = row.server
    val counters = buildList {
        if (local != null) add(str(S.cs_sent) to local.sentCount)
        if (server != null) {
            add(str(S.completed) to server.completed)
            add(str(S.pending) to server.pending)
        }
        val failed = (server?.failed ?: 0) + (local?.files?.count { it.status.isLocalFailure } ?: 0)
        if (failed > 0) add(str(S.dd_legend_failed) to failed)
    }
    if (counters.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        counters.forEach { (label, value) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = label.uppercase(),
                    style = ZillitTheme.typography.columnHeader,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitText(
                    text = value.toString(),
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

private val BulkFileStatus.isLocalFailure: Boolean
    get() = this == BulkFileStatus.Invalid || this == BulkFileStatus.Failed || this == BulkFileStatus.SendFailed

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

/** The row's headline, in words — `batchSummary`; the counters carry the numbers. */
private fun BulkRow.summary(): String = when (phase) {
    BulkPhase.Uploading -> str(S.ah_uploading)
    BulkPhase.Processing -> str(S.desktop_extracting)
    BulkPhase.Error -> str(S.desktop_inv_nothing_was_sent)
    BulkPhase.Done -> str(S.desktop_inv_upload_complete)
}

private val FILE_ICON = 16.dp
private val BAR_HEIGHT = 4.dp
private const val BYTES_PER_MB = 1024.0 * 1024.0
private const val KB = 1024L
