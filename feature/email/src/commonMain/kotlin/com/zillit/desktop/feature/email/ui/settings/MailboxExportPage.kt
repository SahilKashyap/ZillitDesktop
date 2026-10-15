package com.zillit.desktop.feature.email.ui.settings

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
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.MailboxExport
import com.zillit.desktop.feature.email.domain.MailboxExportPart
import com.zillit.desktop.feature.email.domain.exportSizeLabel
import com.zillit.desktop.feature.email.domain.mailDate
import com.zillit.desktop.feature.email.domain.mailFullTimeLabel
import kotlin.time.Clock

/**
 * Export mailbox — the web's `MailboxExportModal`, screen for screen.
 *
 * One card at the top says what can be done *now*: start an export, watch the
 * one running, or read why the last one failed. Under it, the exports that can
 * still be downloaded, each its own row. There is no history of cancelled or
 * expired exports — nothing can be done with them, so the web leaves them out
 * and so does this.
 *
 * The export runs on the server and survives this being closed, so nothing
 * here is a wizard: it opens on whatever state the server is in and says so.
 */
@Composable
internal fun MailboxExportPage(
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = str(S.desktop_email_export_title),
        subtitle = state.mailboxEmail.ifBlank { str(S.desktop_email_export_subtitle) },
        onBack = onBack,
    ) {
        MailboxExportBody(state, onEvent)
    }
}

/**
 * The same thing over the mailbox instead of in the Settings window — what the
 * sidebar's Export mailbox opens, the way the address book opens as the web's
 * `ContactListModal`. Exporting is something you start and come back to, so it
 * has no business taking the whole window away from the mail behind it.
 *
 * The header carries the mailbox address, as the web's does: the personal and
 * the shared Accounts mailbox export separately, and the address is the only
 * thing on screen that says which one this is.
 */
@Composable
internal fun MailboxExportDialog(
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    ZillitDialogShell(
        title = str(S.desktop_email_export_title),
        subtitle = state.mailboxEmail.ifBlank { str(S.desktop_email_export_subtitle) },
        onDismiss = onDismiss,
        visible = true,
        icon = ZillitIcons.Inbox,
        width = EXPORT_DIALOG_WIDTH,
    ) {
        MailboxExportBody(state, onEvent)
    }
}

/** The card, the list, and the messages over them — shared by the page and the dialog. */
@Composable
private fun ColumnScope.MailboxExportBody(state: MailboxExportUiState, onEvent: (MailboxExportEvent) -> Unit) {
    state.error?.let {
        SettingsMessage(it, StatusTone.Rejected, onDismiss = { onEvent(MailboxExportEvent.DismissMessage) })
    }
    state.info?.let {
        SettingsMessage(it, StatusTone.Done, onDismiss = { onEvent(MailboxExportEvent.DismissMessage) })
    }

    val running = state.running
    val failed = state.failed
    when {
        running != null -> RunningCard(running, state, onEvent)
        // Only while it is the newest: see [MailboxExportUiState.failed].
        failed != null -> FailedCard(failed, state, onEvent)
        else -> StartCard(state, onEvent)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_email_export_list_title).uppercase(),
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
        state.ready.isNotEmpty() -> {
            // "New" means finished in the last day, as the web's own badge
            // does — the newest row is otherwise marked new for ever, and a
            // month-old export wears it.
            val now = Clock.System.now().toEpochMilliseconds()
            state.ready.forEachIndexed { index, export ->
                ExportRow(export, isNew = index == 0 && export.completedOnMillis > now - NEW_FOR_MILLIS, state, onEvent)
            }
        }
        state.isLoading -> EmptyBox(str(S.ah_loading))
        // Never "No exports yet" while one is running: this page used to say
        // exactly that over a live progress bar, and the web's own comment
        // names it as the mistake not to repeat. The chat export's wording,
        // which is about any export and already says the right thing — the
        // card above is the one that says "Preparing your export…".
        running != null -> EmptyBox(str(S.desktop_chat_export_empty_running))
        else -> EmptyBox(str(S.desktop_email_export_empty))
    }
}

/** Nothing is running: what an export is, and the one button that starts it. */
@Composable
private fun StartCard(state: MailboxExportUiState, onEvent: (MailboxExportEvent) -> Unit) {
    ExportCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitText(
                text = str(S.desktop_email_export_intro),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.desktop_email_export_start),
                leadingIcon = ZillitIcons.Download,
                enabled = state.canStart && !state.isLoading,
                loading = state.isStarting,
                onClick = { onEvent(MailboxExportEvent.Start) },
            )
        }
    }
}

/**
 * The export being built: how far along, and what it is safe to do meanwhile.
 *
 * There is no bar until the server has counted the mailbox (`total_messages`
 * is 0 until then) — a bar pinned at zero reads as stuck, so the line that
 * says it takes a while stands in its place. Cancelling asks inline: the
 * button that asked is replaced by the answer, so nothing moves under the
 * pointer, and a dialog over this sheet would be clipped by its own scroller.
 */
@Composable
private fun RunningCard(
    export: MailboxExport,
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
) {
    val asking = state.pendingRemoval?.exportId == export.exportId
    ExportCard(tone = CardTone.Brand) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = str(S.desktop_email_export_preparing),
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
        RunningFooter(export, state, onEvent, asking)
    }
}

/** The bar and its count, or the line that stands in for a bar nobody can read yet. */
@Composable
private fun RunningProgress(export: MailboxExport) {
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

/** What is safe to do while it runs — and, once asked, the stop question in its place. */
@Composable
private fun RunningFooter(
    export: MailboxExport,
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
    asking: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = if (asking) {
                str(S.desktop_email_export_cancel_confirm)
            } else {
                str(S.desktop_email_export_running_note)
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (asking) {
            ZillitButton(
                text = str(S.desktop_email_export_keep_running),
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = state.busyExportId.isBlank(),
                onClick = { onEvent(MailboxExportEvent.DismissRemove) },
            )
            ZillitButton(
                text = str(S.stop),
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                loading = state.busyExportId == export.exportId,
                onClick = { onEvent(MailboxExportEvent.Cancel(export.exportId)) },
            )
        } else {
            ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { onEvent(MailboxExportEvent.AskRemove(export)) },
            )
        }
    }
}

/**
 * The last export failed.
 *
 * `error` is a server message key — the sentence comes from the dictionary the
 * backend ships, never from a line written here, so a key that dictionary does
 * not carry renders raw on purpose.
 */
@Composable
private fun FailedCard(
    export: MailboxExport,
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
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
                ZillitText(
                    text = export.error.takeIf { it.isNotBlank() }?.localisedMessage()
                        ?: str(S.desktop_email_export_failed_note),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitButton(
                text = str(S.try_again),
                leadingIcon = ZillitIcons.Reload,
                enabled = state.canStart,
                loading = state.isStarting,
                onClick = { onEvent(MailboxExportEvent.Start) },
            )
        }
    }
}

/**
 * One downloadable export.
 *
 * An export over the server's size limit arrives as several ZIPs and all of
 * them are needed to open it, so each part gets its own button; a single-part
 * export gets one plain Download. Removing asks inline — it deletes the files
 * for good.
 */
@Composable
private fun ExportRow(
    export: MailboxExport,
    isNew: Boolean,
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val asking = state.pendingRemoval?.exportId == export.exportId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(ROW_HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(
            icon = ZillitIcons.File,
            contentDescription = null,
            tint = colors.textMuted,
            size = ROW_ICON,
            // Beside the date, not halfway down a row that has grown parts
            // and a warning under it.
            modifier = Modifier.align(Alignment.Top).padding(top = ZillitTheme.spacing.xxs),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(text = mailDate(export.requestedOnMillis), style = ZillitTheme.typography.bodyMedium)
                if (isNew) ZillitStatusPill(label = str(S.mtg_new), tone = StatusTone.Done)
            }
            ZillitText(
                text = export.metaLine(),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            if (export.skippedMessages > 0) {
                ZillitText(
                    text = str(S.desktop_email_export_skipped, export.skippedMessages),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.warning,
                )
            }
            if (export.parts.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    export.parts.forEach { part -> PartButton(export, part, state, onEvent) }
                }
                ZillitText(
                    text = str(S.desktop_email_export_all_parts),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        ExportRowActions(export, state, onEvent, asking)
    }
}

/** Remove and Download — or, once Remove is asked, the question and its two answers. */
@Composable
private fun ExportRowActions(
    export: MailboxExport,
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
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
                onClick = { onEvent(MailboxExportEvent.DismissRemove) },
            )
            ZillitButton(
                text = str(S.remove),
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                loading = state.busyExportId == export.exportId,
                onClick = { onEvent(MailboxExportEvent.ConfirmRemove) },
            )
            return@Row
        }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.desktop_email_export_remove_title),
            onClick = { onEvent(MailboxExportEvent.AskRemove(export)) },
        )
        if (export.parts.size == 1) {
            ZillitButton(
                text = str(S.download),
                leadingIcon = ZillitIcons.Download,
                size = ButtonSize.Small,
                // One link request at a time, so a second click cannot hand
                // the browser two copies of the same ZIP.
                enabled = state.linkingExportId.isBlank(),
                loading = state.linkingExportId == export.exportId,
                onClick = { onEvent(MailboxExportEvent.Download(export.exportId, export.parts.first().partNo)) },
            )
        }
    }
}

/** `Part 2` — one ZIP of a multi-part export. */
@Composable
private fun PartButton(
    export: MailboxExport,
    part: MailboxExportPart,
    state: MailboxExportUiState,
    onEvent: (MailboxExportEvent) -> Unit,
) {
    ZillitButton(
        text = str(S.desktop_email_export_part, part.partNo),
        variant = ButtonVariant.Secondary,
        leadingIcon = ZillitIcons.Download,
        size = ButtonSize.Small,
        enabled = state.linkingExportId.isBlank(),
        loading = state.linkingExportId == export.exportId,
        onClick = { onEvent(MailboxExportEvent.Download(export.exportId, part.partNo)) },
    )
}

/** `ZIP · 1.3 GB · Expires Oct 22, 2026 at 09:14 AM` — the row's second line. */
@Composable
private fun MailboxExport.metaLine(): String {
    val kind = if (parts.size > 1) {
        str(S.desktop_email_export_zip_files, parts.size)
    } else {
        str(S.desktop_email_export_zip)
    }
    val size = exportSizeLabel(if (totalSizeBytes > 0) totalSizeBytes else parts.sumOf { it.sizeBytes })
    val expires = expiresOnMillis
        .takeIf { it > 0 }
        ?.let { str(S.drive_expires_prefix_format, mailFullTimeLabel(it)) }
        .orEmpty()
    return listOf(kind, size, expires).filter { it.isNotBlank() }.joinToString(SEPARATOR)
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

private const val SEPARATOR = " · "
private const val PERCENT = 100

/** A finished export wears "New" for its first day, as the web's does. */
private const val NEW_FOR_MILLIS = 24L * 60 * 60 * 1000
private val ROW_HAIRLINE = 1.dp
private val ROW_ICON = 20.dp
private val CARD_ICON = 20.dp
private val EXPORT_DIALOG_WIDTH = 640.dp
