package com.zillit.desktop.feature.chat.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.domain.ChatExport
import com.zillit.desktop.feature.chat.domain.chatExportSizeLabel
import com.zillit.desktop.feature.chat.domain.lastEntryDate

/**
 * Export chats — the web's `ChatExportWindow`, screen for screen.
 *
 * One card at the top says what can be done *now*: ask for an export, watch
 * the one running, or read why the last one failed. Under it, "Your exports":
 * the ZIPs that can still be downloaded. Cancelled and expired exports are not
 * listed — nothing can be done with them.
 *
 * The export runs on the server and keeps running with this closed, so nothing
 * here is a wizard: it opens on whatever state the server is in and says so.
 */
@Composable
fun ChatExportDialog(
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
    visible: Boolean,
    onDismiss: () -> Unit,
    /** The production these chats belong to — an export covers one, and says so. */
    projectName: String = "",
) {
    ZillitDialogShell(
        title = str(S.desktop_chat_export_title),
        subtitle = projectName.ifBlank { str(S.desktop_chat_export_subtitle) },
        icon = ZillitIcons.Inbox,
        onDismiss = onDismiss,
        visible = visible,
        width = EXPORT_DIALOG_WIDTH,
    ) {
        ChatExportBody(state, onEvent, projectName)
    }
}

/** The card, the list, the help line — and the messages over all of it. */
@Composable
private fun ColumnScope.ChatExportBody(
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
    projectName: String,
) {
    state.error?.let { ExportMessage(it, StatusTone.Rejected, onEvent) }
    state.info?.let { ExportMessage(it, StatusTone.Done, onEvent) }

    val running = state.running
    val failed = state.failed
    when {
        running != null -> RunningCard(running, state, onEvent)
        failed != null -> FailedCard(failed, state, onEvent)
        else -> StartCard(state, onEvent, projectName)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_chat_export_list_title).uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = str(S.desktop_email_export_kept),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }

    when {
        state.ready.isNotEmpty() -> state.ready.forEach { export -> ExportRow(export, state, onEvent) }
        state.isLoading -> EmptyBox(str(S.ah_loading))
        // Never "No exports yet" over a live progress bar: the two contradict
        // each other on screen, which is the web's own note on this page.
        running != null -> EmptyBox(str(S.desktop_chat_export_empty_running))
        else -> EmptyBox(str(S.desktop_email_export_empty))
    }

    if (state.ready.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Check,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = HINT_ICON,
            )
            ZillitText(
                text = str(S.desktop_chat_export_how_to_open),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * Nothing is running: what an export holds, the one choice, and the button.
 *
 * The choice belongs to the *next* export, so it is locked while one runs —
 * the server settled it when that one was asked for, and a box that moves
 * without changing anything is a lie.
 */
@Composable
private fun StartCard(
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
    projectName: String,
) {
    ExportCard {
        ZillitText(
            text = str(S.desktop_chat_export_intro),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )

        ZillitCheckbox(
            checked = state.includeMedia,
            onCheckedChange = { onEvent(ChatExportEvent.IncludeMedia(it)) },
            label = str(S.desktop_chat_export_include_media),
            enabled = state.canStart,
        )
        ZillitText(
            // What ticking it costs, or what unticking it leaves out: the hint
            // answers the box as it stands, not in general.
            text = if (state.includeMedia) {
                str(S.desktop_chat_export_media_on_hint)
            } else {
                str(S.desktop_chat_export_media_off_hint)
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(start = CHOICE_INDENT),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitText(
                text = projectNote(projectName),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.desktop_chat_export_title),
                leadingIcon = ZillitIcons.Download,
                enabled = state.canStart && !state.isLoading,
                loading = state.isStarting,
                onClick = { onEvent(ChatExportEvent.Start) },
            )
        }
    }
}

/**
 * The export being built.
 *
 * Indeterminate until the server has counted the conversations
 * (`total_conversations` is 0 until then): the line that says it takes a while
 * stands in for a bar pinned at zero, which reads as stuck.
 */
@Composable
private fun RunningCard(
    export: ChatExport,
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
) {
    ExportCard(tone = CardTone.Brand) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = if (export.progress == null) {
                    str(S.desktop_email_export_preparing)
                } else {
                    str(S.desktop_chat_export_running)
                },
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            export.progress?.let {
                ZillitText(
                    text = "${(it * PERCENT).toInt()}%",
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.accentText,
                )
            }
        }

        RunningProgress(export)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = str(S.desktop_chat_export_running_note),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                // Say what happens next, not only that leaving is safe: the
                // notice reaches them wherever they are in the app.
                ZillitText(
                    text = str(S.desktop_chat_export_running_notify),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = state.busyExportId.isBlank(),
                loading = state.busyExportId == export.exportId,
                onClick = { onEvent(ChatExportEvent.Cancel(export.exportId)) },
            )
        }
    }
}

/** The bar and its counters, or the line that stands in for a bar nobody can read yet. */
@Composable
private fun RunningProgress(export: ChatExport) {
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
        ) + SEPARATOR + export.countsLine(),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
}

/**
 * The last export failed.
 *
 * `error` is a server message key: the sentence comes from the dictionary the
 * backend ships, with no copy written here.
 */
@Composable
private fun FailedCard(
    export: ChatExport,
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
) {
    ExportCard(tone = CardTone.Danger) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Warning,
                contentDescription = null,
                tint = ZillitTheme.colors.danger,
                size = CARD_ICON,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(text = str(S.asset_export_failed), style = ZillitTheme.typography.titleSmall)
                export.error.takeIf { it.isNotBlank() }?.let { key ->
                    ZillitText(
                        text = key.localisedMessage(),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            }
            ZillitButton(
                text = str(S.desktop_chat_export_again),
                leadingIcon = ZillitIcons.Reload,
                enabled = state.canStart,
                loading = state.isStarting,
                onClick = { onEvent(ChatExportEvent.Start) },
            )
        }
    }
}

/**
 * One downloadable export: the size first, since it decides the click.
 *
 * Removing asks inline rather than in a dialog of its own — a confirmation
 * composed inside this sheet's scroller is clipped by it.
 */
@Composable
private fun ExportRow(
    export: ChatExport,
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val asking = state.pendingRemoval?.exportId == export.exportId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(
            icon = ZillitIcons.File,
            contentDescription = null,
            tint = colors.textMuted,
            size = ROW_ICON,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(text = export.sizeLine(), style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = export.availabilityLine(),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            if (export.skippedMedia > 0) {
                ZillitText(
                    text = str(S.desktop_chat_export_skipped, export.skippedMedia),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.warning,
                )
            }
        }

        ExportRowActions(export, state, onEvent, asking)
    }
}

/** Delete and Download — or, once Delete is asked, the question and its two answers. */
@Composable
private fun ExportRowActions(
    export: ChatExport,
    state: ChatExportUiState,
    onEvent: (ChatExportEvent) -> Unit,
    asking: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (asking) {
            ZillitText(
                text = str(S.desktop_email_export_remove_title),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitButton(
                text = str(S.asset_cat_keep),
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = state.busyExportId.isBlank(),
                onClick = { onEvent(ChatExportEvent.DismissRemove) },
            )
            ZillitButton(
                text = str(S.delete),
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                loading = state.busyExportId == export.exportId,
                onClick = { onEvent(ChatExportEvent.ConfirmRemove) },
            )
            return@Row
        }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.delete),
            onClick = { onEvent(ChatExportEvent.AskRemove(export)) },
        )
        ZillitButton(
            text = str(S.download),
            leadingIcon = ZillitIcons.Download,
            size = ButtonSize.Small,
            // One link request at a time, so a second click cannot open two
            // downloads of the same ZIP.
            enabled = state.linkingExportId.isBlank(),
            loading = state.linkingExportId == export.exportId,
            onClick = { onEvent(ChatExportEvent.Download(export.exportId)) },
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

/** The well in place of the list: nothing to download, and why. */
@Composable
private fun EmptyBox(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(vertical = ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.Center,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** The one card at the top: neutral, the brand tint while it runs, danger when it failed. */
private enum class CardTone { Neutral, Brand, Danger }

@Composable
private fun ExportCard(tone: CardTone = CardTone.Neutral, content: @Composable ColumnScope.() -> Unit) {
    val colors = ZillitTheme.colors
    val background = when (tone) {
        CardTone.Neutral -> colors.surfaceSunken
        CardTone.Brand -> colors.accentSoft
        CardTone.Danger -> colors.dangerSoft
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        content = content,
    )
}

/** "Covers this project only · SG Document Distribution" — the name only where there is one. */
@Composable
private fun projectNote(projectName: String): String {
    val note = str(S.desktop_chat_export_project_note)
    return if (projectName.isBlank()) note else note + SEPARATOR + projectName
}

/** "46 MB" on its own, or with "· text only" when the media was left out. */
@Composable
private fun ChatExport.sizeLine(): String {
    val size = chatExportSizeLabel(sizeBytes)
    return if (includeMedia) size else listOf(size, str(S.desktop_chat_export_text_only_short))
        .filter { it.isNotBlank() }
        .joinToString(SEPARATOR)
}

/** "Available until Oct 22, 2026", or just that it is ready when the server named no date. */
@Composable
private fun ChatExport.availabilityLine(): String =
    expiresOnMillis
        .takeIf { it > 0 }
        ?.let { str(S.desktop_email_export_available_until, lastEntryDate(it)) }
        ?: str(S.completed)

/** "8190 messages, 312 files" — the live counters, and no files line without media. */
@Composable
private fun ChatExport.countsLine(): String =
    if (includeMedia) {
        str(S.desktop_chat_export_counts, exportedMessages, exportedMedia)
    } else {
        str(S.desktop_chat_export_message_count, exportedMessages)
    }

private const val SEPARATOR = " · "
private const val PERCENT = 100
private val HAIRLINE = 1.dp
private val ROW_ICON = 20.dp
private val CARD_ICON = 20.dp
private val HINT_ICON = 15.dp
private val CHOICE_INDENT = 28.dp
private val EXPORT_DIALOG_WIDTH = 640.dp
