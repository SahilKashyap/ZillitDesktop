package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.data.ChatExportUpdate
import com.zillit.desktop.feature.chat.domain.ChatExportStatus
import com.zillit.desktop.feature.chat.domain.chatExportSizeLabel
import com.zillit.desktop.feature.chat.domain.lastEntryDate
import com.zillit.desktop.feature.email.data.MailboxExportUpdate
import com.zillit.desktop.feature.email.domain.MailboxExportStatus
import com.zillit.desktop.feature.email.domain.exportSizeLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * "Your chat export is ready", wherever the user is in the app.
 *
 * ## Why the frame owns this
 *
 * An export runs on the server for minutes — an hour, for a large mailbox —
 * and the dialog that asked for it says so and invites you to close it. The
 * answer therefore has to arrive somewhere that is always on screen, not in a
 * tool window nobody is looking at; the web mounts the same notice app-wide
 * (`ChatExportHost`, `MailboxExportHost`) for exactly that reason. The socket
 * is already open here, so this listens to it beside the call overlay and the
 * rights requests rather than a module doing it twice.
 *
 * Download is the primary action because it is what the wait was for, and the
 * size is right above it, so going through the window first would be a step
 * that buys nothing. The link is fetched on the click — they live about an
 * hour, so one stored at completion is the one that will have expired.
 *
 * A notice stays until it is dismissed or acted on: the user may be away from
 * the screen for the whole of the export.
 */
@Composable
internal fun ExportNoticeSurface(
    chatUpdates: Flow<ChatExportUpdate>?,
    mailboxUpdates: Flow<MailboxExportUpdate>?,
    /** `GET /:id/download` for a chat export, answering a fresh link or null. */
    chatLink: suspend (exportId: String) -> String?,
    /** The same for a mailbox export; a multi-part export hands back its first ZIP. */
    mailboxLink: suspend (exportId: String, isAccountsMailbox: Boolean) -> String?,
    onOpenUrl: (String) -> Unit,
    onOpenChatExports: () -> Unit,
    onOpenMailboxExports: () -> Unit,
) {
    var notices by remember { mutableStateOf(listOf<ExportNotice>()) }
    var busyId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chatUpdates) {
        chatUpdates?.collect { update ->
            update.export.asNotice()?.let { notice -> notices = notices.put(notice) }
        }
    }
    LaunchedEffect(mailboxUpdates) {
        mailboxUpdates?.collect { update ->
            update.asNotice()?.let { notice -> notices = notices.put(notice) }
        }
    }

    if (notices.isEmpty()) return

    Box(Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.TopEnd) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            notices.forEach { notice ->
                ExportNoticeCard(
                    notice = notice,
                    busy = busyId == notice.exportId,
                    onDismiss = { notices = notices.filterNot { it.exportId == notice.exportId } },
                    onOpen = {
                        notices = notices.filterNot { it.exportId == notice.exportId }
                        if (notice.isChat) onOpenChatExports() else onOpenMailboxExports()
                    },
                    onDownload = {
                        busyId = notice.exportId
                        scope.launch {
                            val url = if (notice.isChat) {
                                chatLink(notice.exportId)
                            } else {
                                mailboxLink(notice.exportId, notice.isAccountsMailbox)
                            }
                            busyId = ""
                            notices = notices.filterNot { it.exportId == notice.exportId }
                            // No link: expired, or removed from another device.
                            // The window is where that is explained.
                            if (url != null) {
                                onOpenUrl(url)
                            } else if (notice.isChat) {
                                onOpenChatExports()
                            } else {
                                onOpenMailboxExports()
                            }
                        }
                    },
                )
            }
        }
    }
}

/** One card: what is ready (or what failed), and the two things to do about it. */
@Composable
private fun ExportNoticeCard(
    notice: ExportNotice,
    busy: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .width(NOTICE_WIDTH)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceRaised)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(
            icon = if (notice.failed) ZillitIcons.Warning else ZillitIcons.Check,
            contentDescription = null,
            tint = if (notice.failed) colors.danger else colors.success,
            size = NOTICE_ICON,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(text = notice.title, style = ZillitTheme.typography.titleSmall)
            // Two lines rather than one joined by "·": the card is narrow, and
            // a separator left hanging at a wrap reads as a typo.
            listOfNotNull(notice.detail, notice.footnote)
                .filter { it.isNotBlank() }
                .forEach { line ->
                    ZillitText(
                        text = line,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            NoticeActions(notice, busy, onOpen, onDownload)
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.sync_action_dismiss),
            onClick = onDismiss,
        )
    }
}

/**
 * Download, then the way into the window.
 *
 * Download is the primary action on a finished export because it is what the
 * wait was for; a failed one has nothing to download, so Open leads.
 */
@Composable
private fun NoticeActions(
    notice: ExportNotice,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!notice.failed) {
            ZillitButton(
                text = str(S.download),
                leadingIcon = ZillitIcons.Download,
                size = ButtonSize.Small,
                loading = busy,
                onClick = onDownload,
            )
        }
        ZillitButton(
            text = if (notice.failed) str(S.dd_action_open) else str(S.desktop_chat_export_view),
            variant = if (notice.failed) ButtonVariant.Primary else ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            onClick = onOpen,
        )
    }
}

/** What the card holds, already worded — the socket's export, read once. */
private data class ExportNotice(
    val exportId: String,
    val isChat: Boolean,
    val failed: Boolean,
    val title: String,
    val detail: String,
    /** A second line under [detail] — how long it lasts, or how big it is. */
    val footnote: String = "",
    val isAccountsMailbox: Boolean = false,
)

/** Newest first, one card per export: a later frame about the same one replaces it. */
private fun List<ExportNotice>.put(notice: ExportNotice): List<ExportNotice> =
    (listOf(notice) + filterNot { it.exportId == notice.exportId }).take(MAX_NOTICES)

/**
 * Only a finished or failed export is worth a notice; everything between is
 * progress the dialog draws for itself.
 */
private fun com.zillit.desktop.feature.chat.domain.ChatExport.asNotice(): ExportNotice? {
    val failed = status == ChatExportStatus.Failed
    if (status != ChatExportStatus.Completed && !failed) return null
    // Say WHAT is ready: a 47 KB text file and a 1 GB media one are the same
    // sentence otherwise, and the difference decides whether the user wants it
    // now, on this connection. On a failure `error` is a server message key,
    // so the sentence is the backend dictionary's.
    val kind = if (includeMedia) {
        str(S.desktop_chat_export_media_included)
    } else {
        str(S.desktop_chat_export_text_only_short)
    }
    val detail = if (failed) {
        error.takeIf { it.isNotBlank() }?.localisedMessage().orEmpty()
    } else {
        listOf(chatExportSizeLabel(sizeBytes), kind).filter { it.isNotBlank() }.joinToString(SEPARATOR)
    }
    return ExportNotice(
        exportId = exportId,
        isChat = true,
        failed = failed,
        title = if (failed) str(S.desktop_chat_export_failed_notice) else str(S.desktop_chat_export_ready_notice),
        detail = detail,
        footnote = if (failed) {
            ""
        } else {
            expiresOnMillis
                .takeIf { it > 0 }
                ?.let { str(S.desktop_email_export_available_until, lastEntryDate(it)) }
                .orEmpty()
        },
    )
}

private fun MailboxExportUpdate.asNotice(): ExportNotice? {
    val failed = export.status == MailboxExportStatus.Failed
    if (export.status != MailboxExportStatus.Completed && !failed) return null
    val size = exportSizeLabel(
        if (export.totalSizeBytes > 0) export.totalSizeBytes else export.parts.sumOf { it.sizeBytes },
    )
    // The address first: the personal and the shared Accounts mailbox export
    // separately, and the notice can arrive while neither is on screen.
    return ExportNotice(
        exportId = export.exportId,
        isChat = false,
        failed = failed,
        title = if (failed) str(S.desktop_email_export_failed_notice) else str(S.desktop_email_export_ready_notice),
        detail = if (failed) {
            export.error.takeIf { it.isNotBlank() }?.localisedMessage() ?: export.mailboxEmail
        } else {
            export.mailboxEmail
        },
        footnote = if (failed) "" else size,
        isAccountsMailbox = isAccountsMailbox,
    )
}

private const val SEPARATOR = " · "
private const val MAX_NOTICES = 3
private val NOTICE_WIDTH = 380.dp
private val NOTICE_ICON = 20.dp
private val HAIRLINE = 1.dp
