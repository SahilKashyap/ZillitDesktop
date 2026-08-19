package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.htmlToSpans
import com.zillit.desktop.feature.email.domain.plainTextToSpans

/**
 * The third pane: the selected conversation.
 *
 * Oldest message at the top, because a thread is a narrative — a reply makes no
 * sense before the message it answers, and clients that invert this make you
 * scroll up to find the beginning.
 */
@Composable
internal fun ReadingPane(
    state: EmailUiState,
    downloads: Map<String, AttachmentDownload>,
    onEvent: (EmailEvent) -> Unit,
    modifier: Modifier = Modifier,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    loadThumbnail: suspend (EmailAttachment, String) -> ImageBitmap? = { _, _ -> null },
    /**
     * Opens a link from a message body in the browser. Null falls back to the
     * platform's own handler — the host wires its guarded launcher when it
     * has one, so the mailbox never has to know how a browser is opened.
     */
    onOpenLink: ((String) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = onOpenLink ?: { url -> uriHandler.openUri(url) }

    Column(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.surface)) {
        when {
            state.selectedMessageId == null -> Centred("Select a message to read it.")

            state.isLoadingThread && state.thread.isEmpty() -> Centred("Opening…")

            state.thread.isEmpty() -> Centred("This message could not be opened.")

            else -> {
                ThreadHeader(state.thread.first().subject, state.thread.size, onEvent)

                val paneState = rememberLazyListState()
                LazyColumn(
                    state = paneState,
                    modifier = Modifier.fillMaxSize().then(rememberWheelScroll(paneState)),
                    contentPadding = PaddingValues(PANE_PADDING),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    items(state.thread, key = EmailMessage::id) { message ->
                        MessageCard(message, downloads, onEvent, openLink, loadAvatar, loadThumbnail)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadHeader(subject: String, count: Int, onEvent: (EmailEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = PANE_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = subject,
                style = ZillitTheme.typography.titleMedium,
                maxLines = 2,
            )
            if (count > 1) {
                ZillitText(
                    text = "$count messages",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close message",
            onClick = { onEvent(EmailEvent.CloseMessage) },
        )
    }
}

@Composable
private fun MessageCard(
    message: EmailMessage,
    downloads: Map<String, AttachmentDownload>,
    onEvent: (EmailEvent) -> Unit,
    onOpenLink: (String) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    loadThumbnail: suspend (EmailAttachment, String) -> ImageBitmap? = { _, _ -> null },
) {
    val colors = ZillitTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.canvas)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        MessageHeader(message, loadAvatar)
        MessageBody(message, onOpenLink)
        if (message.attachments.isNotEmpty()) {
            AttachmentRow(message.attachments, message.id, downloads, onEvent, loadThumbnail)
        }
        ReplyActions(message, onEvent)
    }
}

/**
 * Reply, reply-all and forward, on each message rather than on the thread.
 *
 * Per message because replying to the *last* message is not always what is
 * wanted — a long thread often needs an answer to something said halfway up,
 * and every desktop mail client puts the buttons on the message for that reason.
 */
@Composable
private fun ReplyActions(message: EmailMessage, onEvent: (EmailEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        listOf(
            "Reply" to ComposeMode.Reply,
            "Reply all" to ComposeMode.ReplyAll,
            "Forward" to ComposeMode.Forward,
        ).forEach { (label, mode) ->
            ZillitButton(
                text = label,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { onEvent(EmailEvent.Compose(mode, message)) },
            )
        }
    }
}

@Composable
private fun MessageHeader(
    message: EmailMessage,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
) {
    val colors = ZillitTheme.colors
    val face = produceState<ImageBitmap?>(initialValue = null, message.senderAddress) {
        value = message.senderAddress.takeIf { it.isNotBlank() }?.let { loadAvatar(it) }
    }.value

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = message.senderName, image = face, size = HEADER_AVATAR)
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = message.senderName,
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        // The address as well as the name: two people called "Production" is
        // normal on a shoot, and the address is what tells them apart.
        ZillitText(
            text = message.senderAddress,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
        )
        if (message.recipients.isNotEmpty()) {
            ZillitText(
                text = "to ${message.recipients.joinToString(", ")}",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 2,
            )
        }
    }
    }
}

/**
 * The body.
 *
 * HTML is read into styled spans — see `htmlToSpans` for what is honoured and
 * why it is not rendered by a browser — and drawn by [HtmlBody]. Plain-text
 * mail passes through untouched, apart from its URLs becoming links.
 */
@Composable
private fun MessageBody(message: EmailMessage, onOpenLink: (String) -> Unit) {
    val spans = remember(message.id, message.isHtml, message.body) {
        if (message.isHtml) htmlToSpans(message.body) else plainTextToSpans(message.body)
    }
    HtmlBody(spans = spans, onOpenLink = onOpenLink)
}

@Composable
@Suppress("LongParameterList")
private fun AttachmentRow(
    attachments: List<EmailAttachment>,
    messageId: String,
    downloads: Map<String, AttachmentDownload>,
    onEvent: (EmailEvent) -> Unit,
    loadThumbnail: suspend (EmailAttachment, String) -> ImageBitmap? = { _, _ -> null },
) {
    // Pictures show themselves. An image behind a filename is a click to find
    // out what you were sent, which on a shoot is the whole point of sending it.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        attachments.filter { it.fileName.looksLikeImage() }.forEach { attachment ->
            AttachmentThumb(
                attachment = attachment,
                messageId = messageId,
                load = loadThumbnail,
                onClick = { onEvent(EmailEvent.DownloadAttachment(attachment, messageId)) },
            )
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        attachments.forEach { attachment ->
            AttachmentChip(
                attachment = attachment,
                state = downloads[attachment.id],
                onClick = { onEvent(EmailEvent.DownloadAttachment(attachment, messageId)) },
            )
        }
    }
}

/**
 * One attachment, and how far its download has got.
 *
 * The chip is the whole affordance: click to download, and it then reports
 * where the file went. There is no "open" — see `AttachmentStore` for why a
 * mail client should not open attachments on the user's behalf.
 */
/** One picture, fetched once; a failure quietly leaves the chip to do the job. */
@Composable
private fun AttachmentThumb(
    attachment: EmailAttachment,
    messageId: String,
    load: suspend (EmailAttachment, String) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    val image = produceState<ImageBitmap?>(initialValue = null, attachment.id) {
        value = load(attachment, messageId)
    }.value ?: return

    Image(
        bitmap = image,
        contentDescription = attachment.fileName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .sizeIn(maxWidth = THUMB_MAX, maxHeight = THUMB_MAX)
            .clip(ZillitTheme.shapes.medium)
            .clickable(onClick = onClick),
    )
}

/** Only these render inline; anything else is a chip. */
private fun String.looksLikeImage(): Boolean =
    substringAfterLast('.', "").lowercase() in
        setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")

@Composable
private fun AttachmentChip(
    attachment: EmailAttachment,
    state: AttachmentDownload?,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Only idle chips are clickable. A saved file re-downloaded on a stray
    // click becomes "report (2).pdf", which reads as a bug.
    val clickable = state == null

    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered && clickable) colors.surfaceHover else colors.surfaceSunken)
            .hoverable(interaction)
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        // The extension badge is the file's identity; a download that ended
        // takes the slot back to say how it ended.
        when (state) {
            is AttachmentDownload.Saved -> ZillitIcon(
                icon = ZillitIcons.Check,
                contentDescription = null,
                tint = colors.success,
                size = CHIP_ICON,
            )
            is AttachmentDownload.Failed -> ZillitIcon(
                icon = ZillitIcons.Info,
                contentDescription = null,
                tint = colors.danger,
                size = CHIP_ICON,
            )
            else -> ZillitFileBadge(fileName = attachment.fileName)
        }
        ZillitText(
            text = attachment.fileName,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1,
        )
        ZillitText(
            text = state.caption(attachment),
            style = ZillitTheme.typography.labelSmall,
            color = if (state is AttachmentDownload.Failed) colors.danger else colors.textMuted,
            maxLines = 1,
        )
    }
}

/**
 * The trailing text on a chip.
 *
 * Idle chips show the file size, which is what tells someone on a bad
 * connection whether to bother. Once downloaded it is replaced by where the
 * file went — more useful at that point than a size they have already paid for.
 */
private fun AttachmentDownload?.caption(attachment: EmailAttachment): String = when (this) {
    null -> attachment.readableSize
    AttachmentDownload.InProgress -> "Downloading…"
    is AttachmentDownload.Saved -> "Saved to ${path.parentDirectory()}"
    is AttachmentDownload.Failed -> reason
}

/**
 * The folder part of a path, on either separator.
 *
 * Both, because this ships to Windows as well as macOS — splitting on `/` alone
 * would print the whole path there instead of the folder.
 */
private fun String.parentDirectory(): String {
    val cut = maxOf(lastIndexOf('/'), lastIndexOf('\\'))
    return if (cut <= 0) this else substring(0, cut)
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(PANE_PADDING),
        )
    }
}

private val PANE_PADDING = 16.dp
private val CHIP_ICON = 12.dp
private val HAIRLINE = 1.dp

private val HEADER_AVATAR = 40.dp

private val THUMB_MAX = 260.dp
