package com.zillit.desktop.feature.email.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.MailboxExport
import com.zillit.desktop.feature.email.domain.MailboxExportPart
import com.zillit.desktop.feature.email.domain.MailboxExportStatus
import com.zillit.desktop.feature.email.domain.exportSizeLabel
import com.zillit.desktop.feature.email.domain.mailFullTimeLabel
import com.zillit.desktop.feature.email.ui.DialogButtons
import com.zillit.desktop.feature.email.ui.ModalCard

/**
 * Export mailbox — contract §5 and §7.
 *
 * One export at a time, drawn as its own card with whatever that status can
 * do: Cancel while it is being built, a Download button per ZIP part once it
 * is ready, Try again when it failed. Earlier exports sit underneath as a
 * short history, because the server keeps the last ten.
 *
 * The instructions for opening an mbox file are on the page rather than in a
 * tooltip: an `.mbox` means nothing to most people, and the same words are in
 * the `README.txt` inside every ZIP.
 */
@Composable
internal fun MailboxExportPage(
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = str(S.desktop_email_export_title),
        subtitle = str(S.desktop_email_export_subtitle),
        onBack = onBack,
    ) {
        state.error?.let {
            SettingsMessage(it, StatusTone.Rejected, onDismiss = { onEvent(MailboxExportEvent.DismissMessage) })
        }
        state.info?.let {
            SettingsMessage(it, StatusTone.Done, onDismiss = { onEvent(MailboxExportEvent.DismissMessage) })
        }

        ZillitText(
            text = str(S.desktop_email_export_what),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )

        ZillitButton(
            text = str(S.desktop_email_export_title),
            enabled = state.canStart && !state.isLoading,
            loading = state.isStarting,
            onClick = { onEvent(MailboxExportEvent.Start) },
        )

        when {
            state.isLoading && state.exports.isEmpty() -> SettingsHint(str(S.ah_loading))
            state.exports.isEmpty() -> SettingsHint(str(S.desktop_email_export_none))
            else -> Unit
        }

        state.latest?.let { export -> ExportCard(export, state, onEvent) }

        if (state.earlier.isNotEmpty()) {
            ZillitText(
                text = str(S.desktop_email_export_history),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            state.earlier.forEach { export -> ExportCard(export, state, onEvent) }
        }

        OpeningInstructions()
    }

    state.pendingRemoval?.let {
        ExportRemovalDialog(
            onConfirm = { onEvent(MailboxExportEvent.ConfirmRemove) },
            onDismiss = { onEvent(MailboxExportEvent.DismissRemove) },
        )
    }
}

/** One export: its status, its progress or its parts, and what can be done to it. */
@Composable
private fun ExportCard(
    export: MailboxExport,
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
) {
    SettingsRow {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitStatusPill(label = export.status.label, tone = export.status.tone)
                ZillitText(
                    text = mailFullTimeLabel(export.requestedOnMillis),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }

            // Which mailbox this is a copy of. On screen because the page
            // serves both the personal and the shared Accounts mailbox, and
            // a row that does not say which is a row you cannot check.
            if (export.mailboxEmail.isNotBlank()) {
                ZillitText(
                    text = export.mailboxEmail,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }

            ExportDetail(export)
            if (export.isInProgress) ExportProgress(export)
            if (export.canDownload) ExportParts(export, state, onEvent)
        }

        ExportActions(export, state, onEvent)
    }
}

/** The line under the pill: what this status means for this export. */
@Composable
private fun ExportDetail(export: MailboxExport) {
    val detail = when (export.status) {
        MailboxExportStatus.Queued -> str(S.desktop_email_export_preparing)
        MailboxExportStatus.Processing -> ""
        MailboxExportStatus.Completed -> export.expiresOnMillis
            .takeIf { it > 0 }
            ?.let { str(S.desktop_email_export_available_until, mailFullTimeLabel(it)) }
            .orEmpty()
        // A failure arrives as a message key, which the dictionary turns into
        // a sentence — `email_export_mailbox_empty` and the rest of §8.
        MailboxExportStatus.Failed -> export.error.takeIf { it.isNotBlank() }?.localisedMessage().orEmpty()
        MailboxExportStatus.Cancelled, MailboxExportStatus.Expired -> ""
    }
    if (detail.isNotBlank()) {
        ZillitText(text = detail, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
    }
    if (export.skippedMessages > 0) {
        ZillitText(
            text = str(S.desktop_email_export_skipped, export.skippedMessages),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * The bar, and the count under it.
 *
 * Indeterminate until the server has counted the mailbox — `total_messages`
 * is `0` until then, and a bar pinned at zero reads as stuck.
 */
@Composable
private fun ExportProgress(export: MailboxExport) {
    val progress = export.progress
    if (progress == null) {
        ZillitText(
            text = str(S.desktop_email_export_takes_a_while),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    ZillitProgressBar(fraction = progress, modifier = Modifier.fillMaxWidth())
    ZillitText(
        text = str(S.desktop_email_export_progress, export.exportedMessages, export.totalMessages),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
}

/**
 * One Download button per ZIP, with its size.
 *
 * All of them are needed to open the export, which the line underneath says
 * — a single "Download" would look like the whole mailbox and deliver a
 * fraction of it.
 */
@Composable
private fun ExportParts(
    export: MailboxExport,
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
) {
    // One link request at a time, so the buttons that are not the one
    // being fetched are *disabled* rather than silently ignored.
    val linking = state.linkingExportId == export.exportId
    val blocked = state.linkingExportId.isNotBlank()
    export.parts.forEach { part ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = part.label(export.parts.size),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.download),
                variant = ButtonVariant.Secondary,
                enabled = !blocked,
                loading = linking,
                onClick = { onEvent(MailboxExportEvent.Download(export.exportId, part.partNo)) },
            )
        }
    }
    if (export.parts.size > 1) {
        ZillitText(
            text = str(S.desktop_email_export_all_parts),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** Cancel while it runs, Remove once it is ready, Try again when it failed. */
@Composable
private fun ExportActions(
    export: MailboxExport,
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
) {
    // Cancel and Remove are the same route and run one at a time: this
    // export's button spins, every other one is disabled. Leaving them
    // enabled made a second Remove close its dialog and do nothing.
    val busy = state.busyExportId == export.exportId
    val blocked = state.busyExportId.isNotBlank()
    when {
        export.isInProgress -> ZillitButton(
            text = str(S.cancel),
            variant = ButtonVariant.Tertiary,
            enabled = !blocked,
            loading = busy,
            onClick = { onEvent(MailboxExportEvent.Cancel(export.exportId)) },
        )

        export.status == MailboxExportStatus.Completed -> ZillitButton(
            text = str(S.remove),
            variant = ButtonVariant.Tertiary,
            enabled = !blocked,
            loading = busy,
            onClick = { onEvent(MailboxExportEvent.AskRemove(export)) },
        )

        export.status == MailboxExportStatus.Failed -> ZillitButton(
            text = str(S.try_again),
            variant = ButtonVariant.Secondary,
            enabled = state.canStart && !blocked,
            onClick = { onEvent(MailboxExportEvent.Start) },
        )

        else -> Unit
    }
}

/** How to open an mbox file, in the words the ZIP's own README uses. */
@Composable
private fun OpeningInstructions() {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(text = str(S.desktop_email_export_how_to_open), style = ZillitTheme.typography.labelSmall)
        listOf(
            str(S.desktop_email_export_open_apple_mail),
            str(S.desktop_email_export_open_windows),
            str(S.desktop_email_export_open_several),
            str(S.desktop_email_export_readme_note),
        ).forEach { line ->
            ZillitText(
                text = line,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * Asks before removing a finished export.
 *
 * It cannot be undone — only requested again, and a large mailbox takes more
 * than an hour to rebuild.
 */
@Composable
private fun ExportRemovalDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalCard(onDismiss = onDismiss) {
        ZillitText(text = str(S.desktop_email_export_remove_title), style = ZillitTheme.typography.titleMedium)
        ZillitText(
            text = str(S.desktop_email_export_remove_confirm),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        DialogButtons(
            action = str(S.remove),
            variant = ButtonVariant.Danger,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/** `Part 1 of 2 · 965 MB` — the file's own name is too long for a row. */
private fun MailboxExportPart.label(total: Int): String {
    val part = str(S.desktop_drive_part_of, partNo, total)
    val size = exportSizeLabel(sizeBytes)
    return if (size.isBlank()) part else "$part · $size"
}

private val MailboxExportStatus.label: String
    get() = when (this) {
        // Queued and processing are one state to the user: a retry puts a
        // running export back to `queued` and that must not look like a stall.
        MailboxExportStatus.Queued, MailboxExportStatus.Processing -> str(S.in_progress)
        MailboxExportStatus.Completed -> str(S.completed)
        // The module's own "Failed", already translated (the rules page's
        // execution status); a second key for the same word would read
        // English in every other language.
        MailboxExportStatus.Failed -> str(S.email_rule_exec_failed)
        MailboxExportStatus.Cancelled -> str(S.cancelled)
        MailboxExportStatus.Expired -> str(S.expired)
    }

private val MailboxExportStatus.tone: StatusTone
    get() = when (this) {
        MailboxExportStatus.Queued, MailboxExportStatus.Processing -> StatusTone.Progress
        MailboxExportStatus.Completed -> StatusTone.Done
        MailboxExportStatus.Failed -> StatusTone.Rejected
        MailboxExportStatus.Cancelled, MailboxExportStatus.Expired -> StatusTone.Neutral
    }
