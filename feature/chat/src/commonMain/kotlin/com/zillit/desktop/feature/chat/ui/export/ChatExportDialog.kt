package com.zillit.desktop.feature.chat.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.domain.ChatExport
import com.zillit.desktop.feature.chat.domain.ChatExportStatus
import com.zillit.desktop.feature.chat.domain.chatExportSizeLabel
import com.zillit.desktop.feature.chat.domain.lastEntryDate
import com.zillit.desktop.feature.chat.domain.lastMessageAt

/**
 * Export chats — the contract's request dialog and export screen as one
 * sheet, because on a desktop they are one thing: what the next export will
 * hold, what the current one is doing, and the last few before it.
 *
 * The export runs on the server and survives this dialog being closed, so
 * nothing here is a wizard — it opens on whatever state the server is in
 * and says so.
 */
@Composable
fun ChatExportDialog(
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    ZillitDialogShell(
        title = str(S.desktop_chat_export_title),
        subtitle = str(S.desktop_chat_export_subtitle),
        icon = ZillitIcons.Download,
        onDismiss = onDismiss,
        visible = visible,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = onDismiss,
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.asset_export),
                enabled = state.canStart && !state.isLoading,
                loading = state.isStarting,
                onClick = { onEvent(ChatExportEvent.Start) },
            )
        },
    ) {
        ChatExportBody(state, onEvent)
    }

    // Composed beside the sheet rather than inside it: a confirmation drawn
    // within a dialog's own scroller is clipped by it.
    if (visible) {
        state.pendingRemoval?.let {
            ChatExportRemovalDialog(
                onConfirm = { onEvent(ChatExportEvent.ConfirmRemove) },
                onDismiss = { onEvent(ChatExportEvent.DismissRemove) },
            )
        }
    }
}

/** What the sheet holds: the messages, the options, the exports, the help. */
@Composable
private fun ChatExportBody(state: ChatExportUiState, onEvent: (ChatExportEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        state.error?.let { ExportMessage(it, StatusTone.Rejected, onEvent) }
        state.info?.let { ExportMessage(it, StatusTone.Done, onEvent) }

        ZillitText(
            text = str(S.desktop_chat_export_what),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(
            text = str(S.desktop_chat_export_excluded),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )

        // The choice belongs to the *next* export, so it is locked while one
        // is running — the server settled it when that one was asked for,
        // and a box that moves without changing anything is a lie.
        ZillitCheckbox(
            checked = state.includeMedia,
            onCheckedChange = { onEvent(ChatExportEvent.IncludeMedia(it)) },
            label = str(S.desktop_chat_export_include_media),
            enabled = state.canStart,
        )
        ZillitText(
            text = str(S.desktop_chat_export_include_media_hint),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )

        when {
            state.isLoading && state.exports.isEmpty() -> ExportHint(str(S.ah_loading))
            state.exports.isEmpty() -> ExportHint(str(S.desktop_chat_export_none))
            else -> Unit
        }

        state.latest?.let { export -> ExportCard(export, state, onEvent) }

        if (state.earlier.isNotEmpty()) {
            ZillitText(
                // The mailbox export's own heading — the same words, already
                // translated, for the same list.
                text = str(S.desktop_email_export_history),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            state.earlier.forEach { export -> ExportCard(export, state, onEvent) }
        }

        ZillitText(
            text = str(S.desktop_chat_export_how_to_open),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** One export: its status, its progress or its size, and what can be done to it. */
@Composable
private fun ExportCard(
    export: ChatExport,
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
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
                    text = lastMessageAt(export.requestedOnMillis),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }

            // Which kind of export this is. On the row because a text-only
            // ZIP and a media one are the same name at a tenth of the size,
            // and the history is where someone picks between them.
            ZillitText(
                text = if (export.includeMedia) {
                    str(S.desktop_chat_export_with_media)
                } else {
                    str(S.desktop_chat_export_text_only)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )

            ExportDetail(export)
            if (export.isInProgress) ExportProgress(export)
        }

        ExportActions(export, state, onEvent)
    }
}

/** The line under the pill: what this status means for this export. */
@Composable
private fun ExportDetail(export: ChatExport) {
    val detail = when (export.status) {
        ChatExportStatus.Queued -> str(S.desktop_email_export_preparing)
        ChatExportStatus.Processing -> export.countsLine()
        ChatExportStatus.Completed -> export.readyLine()
        // A failure arrives as a message key, which the dictionary turns
        // into a sentence — `cnc_chat_export_no_chats` and the rest of §6.
        ChatExportStatus.Failed -> export.error.takeIf { it.isNotBlank() }?.localisedMessage().orEmpty()
        ChatExportStatus.Cancelled, ChatExportStatus.Expired -> ""
    }
    if (detail.isNotBlank()) {
        ZillitText(text = detail, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
    }
    if (export.skippedMedia > 0) {
        ZillitText(
            text = str(S.desktop_chat_export_skipped, export.skippedMedia),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * The bar, and the conversation count under it.
 *
 * Indeterminate until the server has counted the conversations —
 * `total_conversations` is `0` until then, and a bar pinned at zero reads as
 * stuck.
 */
@Composable
private fun ExportProgress(export: ChatExport) {
    val progress = export.progress
    if (progress == null) {
        ZillitText(
            text = str(S.desktop_chat_export_takes_a_while),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    ZillitProgressBar(fraction = progress, modifier = Modifier.fillMaxWidth())
    ZillitText(
        text = str(
            S.desktop_chat_export_progress,
            export.exportedConversations,
            export.totalConversations,
        ),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
}

/** Download while it is there, Cancel while it runs, Export again when it is not. */
@Composable
private fun ExportActions(
    export: ChatExport,
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
) {
    // Cancel and Remove are the same route and run one at a time: this
    // export's button spins, every other one is disabled.
    val busy = state.busyExportId == export.exportId
    val blocked = state.busyExportId.isNotBlank()
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        when {
            export.isInProgress -> ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                enabled = !blocked,
                loading = busy,
                onClick = { onEvent(ChatExportEvent.Cancel(export.exportId)) },
            )

            export.canDownload -> {
                ZillitButton(
                    text = str(S.download),
                    variant = ButtonVariant.Secondary,
                    // One link request at a time, so a second click cannot
                    // open two downloads of the same ZIP.
                    enabled = state.linkingExportId.isBlank(),
                    loading = state.linkingExportId == export.exportId,
                    onClick = { onEvent(ChatExportEvent.Download(export.exportId)) },
                )
                ZillitButton(
                    text = str(S.remove),
                    variant = ButtonVariant.Tertiary,
                    enabled = !blocked,
                    loading = busy,
                    onClick = { onEvent(ChatExportEvent.AskRemove(export)) },
                )
            }

            // Failed, cancelled or expired: there is nothing to download and
            // nothing to remove, so the only move left is a new export.
            else -> ZillitButton(
                text = str(S.desktop_chat_export_again),
                variant = ButtonVariant.Secondary,
                enabled = state.canStart,
                onClick = { onEvent(ChatExportEvent.Start) },
            )
        }
    }
}

/**
 * Asks before removing a finished export.
 *
 * It cannot be undone — only requested again, and a long history with media
 * takes many minutes to rebuild.
 */
@Composable
private fun ChatExportRemovalDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_email_export_remove_title),
        onDismiss = onDismiss,
        visible = true,
        actions = {
            ZillitButton(text = str(S.cancel), variant = ButtonVariant.Tertiary, onClick = onDismiss)
            ZillitButton(text = str(S.remove), variant = ButtonVariant.Danger, onClick = onConfirm)
        },
    ) {
        ZillitText(
            text = str(S.desktop_chat_export_remove_confirm),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** A failure or a confirmation, in one line, dismissable. */
@Composable
private fun ExportMessage(text: String, tone: StatusTone, onEvent: (ChatExportEvent) -> Unit) {
    ZillitNotice(
        text = text,
        tone = tone,
        action = {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.sync_action_dismiss),
                onClick = { onEvent(ChatExportEvent.DismissMessage) },
            )
        },
    )
}

@Composable
private fun ExportHint(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
    )
}

/** "8190 messages, 312 files" — the live counters, and no files line without media. */
private fun ChatExport.countsLine(): String =
    if (includeMedia) {
        str(S.desktop_chat_export_counts, exportedMessages, exportedMedia)
    } else {
        str(S.desktop_chat_export_message_count, exportedMessages)
    }

/** "46 MB · Available until Oct 21, 2026" — the size first, since it decides the click. */
private fun ChatExport.readyLine(): String {
    val size = chatExportSizeLabel(sizeBytes)
    val until = expiresOnMillis
        .takeIf { it > 0 }
        ?.let { str(S.desktop_email_export_available_until, lastEntryDate(it)) }
        .orEmpty()
    return listOf(size, until).filter { it.isNotBlank() }.joinToString(SEPARATOR)
}

private val ChatExportStatus.label: String
    get() = when (this) {
        // Queued and processing are one state to the user: a retry puts a
        // running export back to `queued` and that must not look like a stall.
        ChatExportStatus.Queued, ChatExportStatus.Processing -> str(S.in_progress)
        ChatExportStatus.Completed -> str(S.completed)
        // The app's own "Failed", already translated in 22 languages; a new
        // key for one word would read English in every other one.
        ChatExportStatus.Failed -> str(S.dd_status_failed)
        ChatExportStatus.Cancelled -> str(S.cancelled)
        ChatExportStatus.Expired -> str(S.expired)
    }

private val ChatExportStatus.tone: StatusTone
    get() = when (this) {
        ChatExportStatus.Queued, ChatExportStatus.Processing -> StatusTone.Progress
        ChatExportStatus.Completed -> StatusTone.Done
        ChatExportStatus.Failed -> StatusTone.Rejected
        ChatExportStatus.Cancelled, ChatExportStatus.Expired -> StatusTone.Neutral
    }

private const val SEPARATOR = " · "
private val HAIRLINE = 1.dp
