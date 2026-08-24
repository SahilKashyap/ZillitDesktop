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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
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
            // The pane stands open before anything is picked — the web's
            // empty state (`NewEmailComponent.jsx:383-397`).
            state.selectedMessageId == null -> NoSelection()

            state.isLoadingThread && state.thread.isEmpty() -> Centred("Opening…")

            state.thread.isEmpty() -> Centred("This message could not be opened.")

            else -> MessageTrail(state, downloads, onEvent, openLink, loadAvatar, loadThumbnail)
        }
    }
}

/** The pane before anything is picked — the web's empty state. */
@Composable
private fun NoSelection() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Mail,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = EMPTY_ICON,
            )
            ZillitText(
                text = "No email has been selected",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * The open thread: toolbar, one subject, then the trail.
 *
 * Which messages are folded is held here rather than in [ReadingPane], so the
 * state dies with the thread it belongs to.
 */
@Composable
private fun MessageTrail(
    state: EmailUiState,
    downloads: Map<String, AttachmentDownload>,
    onEvent: (EmailEvent) -> Unit,
    openLink: (String) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    loadThumbnail: suspend (EmailAttachment, String) -> ImageBitmap?,
) {
    // Newest first, as the web reads a trail
    // (`NewEmailDetails.jsx:37-57`); the newest is the one open.
    val newestFirst = remember(state.thread) { state.thread.asReversed() }
    val newest = newestFirst.first()
    var expandedIds by remember(state.selectedMessageId) {
        androidx.compose.runtime.mutableStateOf(setOf(newest.id))
    }

    DetailToolbar(newest, state, onEvent)

    val paneState = rememberLazyListState()
    ZillitLazyColumn(
        state = paneState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(PANE_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        // One subject over the whole trail — the web's h1
        // (`NewEmailDetails.jsx:25-34`).
        item(key = "subject") {
            ZillitText(
                text = newest.subject.ifBlank { "(no subject)" },
                style = ZillitTheme.typography.titleLarge,
                maxLines = 3,
                modifier = Modifier.padding(bottom = ZillitTheme.spacing.xs),
            )
        }
        items(newestFirst, key = EmailMessage::id) { message ->
            val isNewest = message.id == newest.id
            MessageCard(
                message = message,
                downloads = downloads,
                onEvent = onEvent,
                onOpenLink = openLink,
                // Older messages start folded and open in place;
                // the newest never folds (`NewEmailTrailItem.jsx:52-56`).
                isExpanded = isNewest || message.id in expandedIds,
                onToggle = if (isNewest) {
                    null
                } else {
                    {
                        expandedIds = if (message.id in expandedIds) {
                            expandedIds - message.id
                        } else {
                            expandedIds + message.id
                        }
                    }
                },
                loadAvatar = loadAvatar,
                loadThumbnail = loadThumbnail,
            )
        }
    }
}

/**
 * The web's detail toolbar (`EmailDetailToolbar.jsx:226-314`): reply verbs on
 * the left answering the newest message, the destructive and the way out on
 * the right.
 */
@Composable
private fun DetailToolbar(newest: EmailMessage, state: EmailUiState, onEvent: (EmailEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        listOf(
            "Reply" to ComposeMode.Reply,
            "Reply all" to ComposeMode.ReplyAll,
            "Forward" to ComposeMode.Forward,
        ).forEach { (label, mode) ->
            ZillitButton(
                text = label,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { onEvent(EmailEvent.Compose(mode, newest)) },
            )
        }
        Spacer(Modifier.weight(1f))
        state.selectedMessageId?.let { openId ->
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Move to Trash",
                tint = ZillitTheme.colors.danger,
                onClick = { onEvent(EmailEvent.TrashMessage(openId)) },
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close message",
            onClick = { onEvent(EmailEvent.CloseMessage) },
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun MessageCard(
    message: EmailMessage,
    downloads: Map<String, AttachmentDownload>,
    onEvent: (EmailEvent) -> Unit,
    onOpenLink: (String) -> Unit,
    /** Folded cards show the header and a line of the body, as on the web. */
    isExpanded: Boolean = true,
    /** Null on the newest message — it never folds. */
    onToggle: (() -> Unit)? = null,
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
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        MessageHeader(message, isExpanded, loadAvatar)
        if (!isExpanded) {
            // The folded card's one-line taste of the body
            // (`NewEmailTrailItem.jsx:209-213`).
            ZillitText(
                text = message.previewLine(),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
            )
            return@Column
        }
        MessageBody(message, onOpenLink)
        if (message.attachments.isNotEmpty()) {
            // The web heads the block with the count
            // (`NewEmailTrailItem.jsx:422-439`).
            ZillitText(
                text = "${message.attachments.size} " +
                    if (message.attachments.size == 1) "Attachment" else "Attachments",
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
                color = colors.textSecondary,
            )
            AttachmentRow(message.attachments, message.id, downloads, onEvent, loadThumbnail)
        }
        ReplyActions(message, onEvent)
    }
}

/** The body as one plain line, for the folded card. */
private fun EmailMessage.previewLine(): String =
    body.replace(Regex("<[^>]*>"), " ")
        .replace(Regex("&[a-zA-Z#0-9]{1,8};"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(PREVIEW_CHARS)

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

/**
 * Who wrote it, to whom, and exactly when — the web's card header
 * (`NewEmailTrailItem.jsx:134-235`). The subject is not here; it stands once
 * over the whole trail.
 */
@Composable
private fun MessageHeader(
    message: EmailMessage,
    isExpanded: Boolean,
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
        Column(
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            modifier = Modifier.weight(1f),
        ) {
            ZillitText(
                text = message.senderName,
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
            )
            // The address as well as the name: two people called "Production"
            // is normal on a shoot, and the address is what tells them apart.
            ZillitText(
                text = message.senderAddress,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
            if (isExpanded && message.recipients.isNotEmpty()) {
                // The first two, then an ellipsis — the web's line
                // (`NewEmailTrailItem.jsx:188-208`).
                val shown = message.recipients.take(2).joinToString(", ")
                val more = if (message.recipients.size > 2) ", …" else ""
                ZillitText(
                    text = shown + more,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        ZillitText(
            text = com.zillit.desktop.feature.email.domain.mailFullTimeLabel(message.receivedAtMillis),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
        )
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
private val EMPTY_ICON = 48.dp
private val CHIP_ICON = 12.dp
private val HAIRLINE = 1.dp

private val HEADER_AVATAR = 40.dp

private val THUMB_MAX = 260.dp

/** How much of a stripped body the collapsed row shows. */
private const val PREVIEW_CHARS = 160
