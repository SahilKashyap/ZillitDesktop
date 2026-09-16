package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.htmlToSpans
import com.zillit.desktop.feature.email.domain.mailFullTimeLabel
import com.zillit.desktop.feature.email.domain.plainTextToSpans

/**
 * What the reading pane needs beyond the mailbox's state — gathered so the
 * pane, the popped-out thread window and a test all build it the same way.
 */
data class ReadingPaneHooks(
    val downloads: Map<String, AttachmentDownload> = emptyMap(),
    val loadAvatar: suspend (String) -> ImageBitmap? = { null },
    val loadThumbnail: suspend (EmailAttachment, String, String) -> ImageBitmap? = { _, _, _ -> null },
    /** Opens a web link from a body; null falls back to the platform handler. */
    val onOpenLink: ((String) -> Unit)? = null,
    /** A `mailto:` link in a body starts a message to its address. */
    val onMailTo: (String) -> Unit = {},
    /** Whether [address] is already known — crew or a contact — so "add to contacts" can hide. */
    val isKnownAddress: (String) -> Boolean = { true },
    /** Read receipts for a sent message, when the host can ask for them. */
    val readBy: (suspend (messageId: String) -> MailReadBy?)? = null,
)

/** Who has read a sent message and who has not — `email-sent-log/read-by`. */
data class MailReadBy(val read: List<MailReadReceipt>, val unread: List<MailReadReceipt>)

data class MailReadReceipt(
    val name: String,
    val designation: String = "",
    val readAtMillis: Long = 0,
    /** For the reader's picture; blank when the row named nobody the crew list knows. */
    val userId: String = "",
)

/**
 * The third pane: the open conversation, or the web's empty state before
 * anything is picked.
 */
@Composable
internal fun ReadingPane(
    state: EmailUiState,
    onEvent: (EmailEvent) -> Unit,
    hooks: ReadingPaneHooks,
    modifier: Modifier = Modifier,
    /** True in a window of its own, where the toolbar's Close and Pop-out make no sense. */
    poppedOut: Boolean = false,
) {
    Column(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.openRowId == null -> NoSelection()
            state.isLoadingThread && state.thread.isEmpty() -> Centred { ZillitSpinner() }
            state.thread.isEmpty() -> Centred {
                ZillitText(
                    text = "This message could not be opened.",
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            else -> {
                if (!poppedOut) DetailToolbar(state, onEvent)
                MessageTrail(state.thread, state.conversationView, onEvent, hooks)
            }
        }
    }
}

/** The pane before anything is picked — the web's empty state. */
@Composable
private fun NoSelection() {
    Centred {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                icon = ZillitIcons.MailOpen,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted.copy(alpha = EMPTY_ALPHA),
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
 * The web's detail toolbar (`EmailDetailToolbar.jsx`): reply verbs with
 * their labels on the left answering the newest message; delete, move,
 * print and pop-out as icons on the right.
 */
@Composable
private fun DetailToolbar(state: EmailUiState, onEvent: (EmailEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val newest = state.newestOpen ?: return
    val ticked = state.hasSelection
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs)
            .testTag(DETAIL_TOOLBAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ReplyVerbs(newest, onEvent, enabled = !ticked, showLabels = true)
        Spacer(Modifier.weight(1f))
        ZillitTooltip("Delete") {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Delete",
                onClick = { onEvent(if (ticked) EmailEvent.DeleteSelected else EmailEvent.DeleteOpen) },
                modifier = Modifier.testTag(DETAIL_DELETE_TAG),
            )
        }
        if (!state.isViewingDrafts) {
            MoveButton(state.moveTargets, onCreateFolder = { onEvent(EmailEvent.EditFolder()) }) { folder ->
                onEvent(if (ticked) EmailEvent.MoveSelected(folder) else EmailEvent.MoveOpen(folder))
            }
        }
        ZillitTooltip(if (state.conversationView && state.thread.size > 1) "Print All" else "Print") {
            ZillitIconButton(
                icon = ZillitIcons.Print,
                contentDescription = "Print",
                onClick = { onEvent(EmailEvent.Print()) },
                modifier = Modifier.testTag(DETAIL_PRINT_TAG),
            )
        }
        ZillitTooltip("Popout") {
            ZillitIconButton(
                icon = ZillitIcons.Detach,
                contentDescription = "Popout",
                onClick = { onEvent(EmailEvent.PopOut) },
                modifier = Modifier.testTag(DETAIL_POPOUT_TAG),
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close message",
            onClick = { onEvent(EmailEvent.CloseMessage) },
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

/** Reply · Reply all · Forward, as text buttons or as the trail's quick actions. */
@Composable
private fun ReplyVerbs(message: EmailMessage, onEvent: (EmailEvent) -> Unit, enabled: Boolean, showLabels: Boolean) {
    listOf(
        Triple("Reply", ZillitIcons.Reply, ComposeMode.Reply),
        Triple("Reply all", ZillitIcons.ReplyAll, ComposeMode.ReplyAll),
        Triple("Forward", ZillitIcons.Forward, ComposeMode.Forward),
    ).forEach { (label, icon, mode) ->
        ZillitButton(
            text = label,
            leadingIcon = icon,
            variant = if (showLabels) ButtonVariant.Tertiary else ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = enabled,
            onClick = { onEvent(EmailEvent.Compose(mode, message)) },
            modifier = Modifier.testTag("detail-$label"),
        )
    }
}

/**
 * The open thread: one subject, then the trail newest first, the newest
 * open and the rest folded to a header line (`NewEmailDetails.jsx`).
 *
 * Which messages are unfolded is held here rather than in [ReadingPane], so
 * the state dies with the thread it belongs to.
 */
@Composable
internal fun MessageTrail(
    thread: List<EmailMessage>,
    conversationView: Boolean,
    onEvent: (EmailEvent) -> Unit,
    hooks: ReadingPaneHooks,
) {
    val newestFirst = remember(thread) { thread.sortedByDescending { it.receivedAtMillis } }
    val newest = newestFirst.first()
    var expandedIds by remember(newest.id) { mutableStateOf(setOf(newest.id)) }

    val paneState = rememberLazyListState()
    ZillitLazyColumn(
        state = paneState,
        modifier = Modifier.fillMaxSize().testTag(READING_PANE_TAG),
        contentPadding = PaddingValues(horizontal = PANE_PADDING, vertical = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        item(key = "subject") {
            ZillitText(
                text = newest.subject.ifBlank { "(no subject)" },
                style = ZillitTheme.typography.titleLarge,
                maxLines = 3,
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xs),
            )
        }
        items(newestFirst, key = EmailMessage::id) { message ->
            val isNewest = message.id == newest.id
            MessageCard(
                message = message,
                canDelete = conversationView && thread.size > 1,
                onEvent = onEvent,
                hooks = hooks,
                isExpanded = isNewest || message.id in expandedIds,
                onToggle = if (isNewest) null else {
                    {
                        expandedIds = if (message.id in expandedIds) {
                            expandedIds - message.id
                        } else {
                            expandedIds + message.id
                        }
                    }
                },
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun MessageCard(
    message: EmailMessage,
    canDelete: Boolean,
    onEvent: (EmailEvent) -> Unit,
    hooks: ReadingPaneHooks,
    isExpanded: Boolean,
    /** Null on the newest message — it never folds. */
    onToggle: (() -> Unit)?,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(
                HAIRLINE,
                if (isExpanded || hovered) colors.borderStrong else colors.border,
                ZillitTheme.shapes.large,
            )
            .hoverable(interaction)
            .testTag("trail-${message.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            if (onToggle != null) {
                ZillitIcon(
                    icon = if (isExpanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textMuted,
                    size = CARET,
                    modifier = Modifier.padding(top = ZillitTheme.spacing.md),
                )
            }
            MessageHeader(message, isExpanded, canDelete, onEvent, hooks)
        }

        if (isExpanded) OpenMessage(message, hooks, onEvent)
    }
}

/** An unfolded card's lower half: the body, its files, and the reply verbs. */
@Composable
private fun OpenMessage(message: EmailMessage, hooks: ReadingPaneHooks, onEvent: (EmailEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg)
            .height(1.dp)
            .background(colors.divider),
    )
    Column(
        modifier = Modifier.padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        MessageBody(message, hooks)
        val files = message.listedAttachments
        if (files.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            ZillitText(
                text = "${files.size} ${if (files.size == 1) "Attachment" else "Attachments"}",
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
            )
            AttachmentGrid(files, message, hooks, onEvent)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ReplyVerbs(message, onEvent, enabled = true, showLabels = false)
        }
    }
}

/**
 * Who wrote it, to whom, and exactly when — the web's card header
 * (`NewEmailTrailItem.jsx`): the face, the sender, the first two recipients
 * with the details caret, the date, and — open — reply, delete and the
 * three-dot menu.
 */
@Composable
@Suppress("LongParameterList")
private fun RowScope.MessageHeader(
    message: EmailMessage,
    isExpanded: Boolean,
    canDelete: Boolean,
    onEvent: (EmailEvent) -> Unit,
    hooks: ReadingPaneHooks,
) {
    val colors = ZillitTheme.colors
    val face = produceState<ImageBitmap?>(initialValue = null, message.senderAddress) {
        value = message.senderAddress.takeIf { it.isNotBlank() }?.let { hooks.loadAvatar(it) }
    }.value

    ZillitAvatar(name = message.senderName, image = face, size = HEADER_AVATAR)
    Column(
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        modifier = Modifier.weight(1f),
    ) {
        ZillitText(
            text = message.from.ifBlank { message.senderName },
            style = ZillitTheme.typography.titleSmall,
            maxLines = 1,
        )
        if (isExpanded) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                val shown = message.recipients.take(2).joinToString(", ")
                val more = if (message.recipients.size > 2) ", …" else ""
                ZillitText(
                    text = shown + more,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                DetailsCaret(message, hooks, onEvent)
            }
        } else {
            ZillitText(
                text = message.previewLine(),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = mailFullTimeLabel(message.receivedAtMillis),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
        )
        if (isExpanded) HeaderActions(message, canDelete, onEvent, hooks)
    }
}

/** Reply, delete (where the folder allows it) and the three-dot menu. */
@Composable
private fun HeaderActions(
    message: EmailMessage,
    canDelete: Boolean,
    onEvent: (EmailEvent) -> Unit,
    hooks: ReadingPaneHooks,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZillitTooltip("Reply") {
            ZillitIconButton(
                icon = ZillitIcons.Reply,
                contentDescription = "Reply",
                onClick = { onEvent(EmailEvent.Compose(ComposeMode.Reply, message)) },
                size = HEADER_BUTTON,
            )
        }
        if (canDelete) {
            ZillitTooltip("Delete this message") {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete this message",
                    tint = ZillitTheme.colors.danger,
                    onClick = { onEvent(EmailEvent.DeleteOne(message)) },
                    size = HEADER_BUTTON,
                )
            }
        }
        MessageMenu(message, onEvent, hooks)
    }
}

/** The three dots: reply verbs, "Read by user" on a sent message, and Print. */
@Composable
private fun MessageMenu(message: EmailMessage, onEvent: (EmailEvent) -> Unit, hooks: ReadingPaneHooks) {
    var open by remember { mutableStateOf(false) }
    var readBy by remember { mutableStateOf<MailReadBy?>(null) }
    var readByOpen by remember { mutableStateOf(false) }
    val sent = message.folderName.equals(EmailFolder.SENT, ignoreCase = true)

    Box {
        ZillitIconButton(
            icon = ZillitIcons.MoreVertical,
            contentDescription = "More",
            onClick = { open = true },
            size = HEADER_BUTTON,
            modifier = Modifier.testTag("trail-menu-${message.id}"),
        )
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = buildList {
                fun compose(label: String, icon: ImageVector, mode: ComposeMode) =
                    ZillitMenuEntry.Action(label, icon) { onEvent(EmailEvent.Compose(mode, message)) }
                add(compose("Reply", ZillitIcons.Reply, ComposeMode.Reply))
                add(compose("Reply all", ZillitIcons.ReplyAll, ComposeMode.ReplyAll))
                add(compose("Forward", ZillitIcons.Forward, ComposeMode.Forward))
                if (sent && hooks.readBy != null) {
                    add(ZillitMenuEntry.Divider)
                    add(ZillitMenuEntry.Action("Read By User", ZillitIcons.Users) { readByOpen = true })
                }
                add(ZillitMenuEntry.Divider)
                add(ZillitMenuEntry.Action("Print", ZillitIcons.Print) { onEvent(EmailEvent.Print(message)) })
            },
        )
        if (readByOpen) {
            val ask = hooks.readBy
            androidx.compose.runtime.LaunchedEffect(message.id) { readBy = ask?.invoke(message.id) }
            ReadByDialog(readBy, onDismiss = { readByOpen = false })
        }
    }
}

/**
 * The web's `ShowDetails` popover behind the caret: From, Reply-to, To, Cc,
 * Bcc, Date and Subject, each address offering "Add to contacts" when the
 * mailbox does not know it.
 */
@Composable
private fun DetailsCaret(message: EmailMessage, hooks: ReadingPaneHooks, onEvent: (EmailEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .clip(ZillitTheme.shapes.small)
                .clickable { open = true }
                .padding(ZillitTheme.spacing.xxs)
                .testTag("trail-details-${message.id}"),
        ) {
            ZillitIcon(
                ZillitIcons.ChevronDown,
                contentDescription = "Details",
                tint = colors.textSecondary,
                size = CARET,
            )
        }
        ZillitMenuSurface(expanded = open, onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier.widthIn(min = DETAILS_MIN, max = DETAILS_MAX).padding(ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                DetailLine("From", listOf(message.from), hooks, onEvent)
                if (!message.folderName.equals(EmailFolder.SENT, ignoreCase = true) && message.replyTo.isNotBlank()) {
                    DetailLine("Reply-to", listOf(message.replyTo), hooks, onEvent)
                }
                DetailLine("To", message.to, hooks, onEvent)
                if (message.cc.isNotEmpty()) DetailLine("Cc", message.cc, hooks, onEvent)
                if (message.bcc.isNotEmpty()) DetailLine("Bcc", message.bcc, hooks, onEvent)
                DetailText("Date", mailFullTimeLabel(message.receivedAtMillis))
                DetailText("Subject", message.subject.ifBlank { "(no subject)" })
            }
        }
    }
}

@Composable
private fun DetailText(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.Top) {
        ZillitText(
            text = "$label:",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = DETAIL_LABEL),
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodySmall)
    }
}

@Composable
private fun DetailLine(label: String, addresses: List<String>, hooks: ReadingPaneHooks, onEvent: (EmailEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.Top) {
        ZillitText(
            text = "$label:",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = DETAIL_LABEL),
        )
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            if (addresses.isEmpty()) ZillitText(text = "-", style = ZillitTheme.typography.bodySmall)
            addresses.forEach { raw ->
                val address = raw.headerAddressOf()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(text = raw, style = ZillitTheme.typography.bodySmall)
                    if (address.isNotBlank() && !hooks.isKnownAddress(address)) {
                        ZillitTooltip("Add to Contacts") {
                            ZillitIconButton(
                                icon = ZillitIcons.UserPlus,
                                contentDescription = "Add to Contacts",
                                onClick = { onEvent(EmailEvent.AddToContacts(address)) },
                                filled = true,
                                size = HEADER_BUTTON,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun String.headerAddressOf(): String {
    val open = indexOf('<')
    val close = indexOf('>', startIndex = open + 1)
    return if (open >= 0 && close > open) substring(open + 1, close).trim() else trim()
}

/** The body as one plain line, for the folded card. */
private fun EmailMessage.previewLine(): String =
    body.replace(Regex("<img[^>]*>"), " ")
        .replace(Regex("<[^>]*>"), " ")
        .replace(Regex("&[a-zA-Z#0-9]{1,8};"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(PREVIEW_CHARS)

/**
 * The body.
 *
 * HTML is read into styled spans — see `htmlToSpans` for what is honoured and
 * why it is not rendered by a browser — and drawn by [HtmlBody]. Plain-text
 * mail passes through untouched, apart from its URLs becoming links. A
 * `mailto:` link starts a message to its address, as the web's link
 * interceptor does.
 */
@Composable
private fun MessageBody(message: EmailMessage, hooks: ReadingPaneHooks) {
    val uriHandler = LocalUriHandler.current
    val spans = remember(message.id, message.isHtml, message.body) {
        if (message.isHtml) htmlToSpans(message.body) else plainTextToSpans(message.body)
    }
    HtmlBody(
        spans = spans,
        onOpenLink = { url ->
            when {
                url.startsWith("mailto:", ignoreCase = true) ->
                    hooks.onMailTo(url.removePrefix("mailto:").substringBefore('?'))
                hooks.onOpenLink != null -> hooks.onOpenLink.invoke(url)
                else -> uriHandler.openUri(url)
            }
        },
    )
}

/**
 * The web's attachment grid: pictures show themselves, every file is a
 * chip that downloads on click and then says where it went.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentGrid(
    files: List<EmailAttachment>,
    message: EmailMessage,
    hooks: ReadingPaneHooks,
    onEvent: (EmailEvent) -> Unit,
) {
    val folder = message.folderName
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        files.filter { it.fileName.looksLikeImage() }.forEach { attachment ->
            AttachmentThumb(attachment, message.id, folder, hooks.loadThumbnail) {
                onEvent(EmailEvent.DownloadAttachment(attachment, message.id, folder))
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        files.forEach { attachment ->
            AttachmentChip(attachment, hooks.downloads[attachment.id]) {
                onEvent(EmailEvent.DownloadAttachment(attachment, message.id, folder))
            }
        }
    }
}

/** One picture, fetched once; a failure quietly leaves the chip to do the job. */
@Composable
private fun AttachmentThumb(
    attachment: EmailAttachment,
    messageId: String,
    folderName: String,
    load: suspend (EmailAttachment, String, String) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    val image = produceState<ImageBitmap?>(initialValue = null, attachment.id) {
        value = load(attachment, messageId, folderName)
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
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")

@Composable
private fun AttachmentChip(attachment: EmailAttachment, state: AttachmentDownload?, onClick: () -> Unit) {
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
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .hoverable(interaction)
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        when (state) {
            is AttachmentDownload.Saved ->
                ZillitIcon(ZillitIcons.Check, contentDescription = null, tint = colors.success, size = CHIP_ICON)
            is AttachmentDownload.Failed ->
                ZillitIcon(ZillitIcons.Info, contentDescription = null, tint = colors.danger, size = CHIP_ICON)
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
        if (clickable) {
            ZillitIcon(ZillitIcons.Download, contentDescription = null, tint = colors.textMuted, size = CHIP_ICON)
        }
    }
}

private fun AttachmentDownload?.caption(attachment: EmailAttachment): String = when (this) {
    null -> attachment.readableSize
    AttachmentDownload.InProgress -> "Downloading…"
    is AttachmentDownload.Saved -> "Saved to ${path.parentDirectory()}"
    is AttachmentDownload.Failed -> reason
}

/** The folder part of a path, on either separator — this ships to Windows too. */
private fun String.parentDirectory(): String {
    val cut = maxOf(lastIndexOf('/'), lastIndexOf('\\'))
    return if (cut <= 0) this else substring(0, cut)
}

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** The web's `ReadByUsersModal` for a sent mail: who opened it, who has not. */
@Composable
private fun ReadByDialog(readBy: MailReadBy?, onDismiss: () -> Unit) {
    ModalCard(onDismiss = onDismiss) {
        ZillitText(text = "Read By User", style = ZillitTheme.typography.titleMedium)
        if (readBy == null) {
            Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
                ZillitSpinner()
            }
        } else {
            ReadByList("Read", readBy.read, showTime = true)
            ReadByList("Unread", readBy.unread, showTime = false)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ZillitButton(text = "Close", variant = ButtonVariant.Tertiary, onClick = onDismiss)
        }
    }
}

@Composable
private fun ReadByList(title: String, receipts: List<MailReadReceipt>, showTime: Boolean) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = "$title (${receipts.size})",
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMuted,
        )
        if (receipts.isEmpty()) {
            ZillitText(text = "Nobody yet", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
        receipts.forEach { receipt ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitAvatar(name = receipt.name, userId = receipt.userId, size = READ_BY_AVATAR)
                Column(Modifier.weight(1f)) {
                    ZillitText(text = receipt.name, style = ZillitTheme.typography.bodyMedium)
                    if (receipt.designation.isNotBlank()) {
                        ZillitText(
                            text = receipt.designation,
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }
                }
                if (showTime && receipt.readAtMillis > 0) {
                    ZillitText(
                        text = mailFullTimeLabel(receipt.readAtMillis),
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}

internal const val READING_PANE_TAG = "email-reading-pane"
internal const val DETAIL_TOOLBAR_TAG = "email-detail-toolbar"
internal const val DETAIL_DELETE_TAG = "email-detail-delete"
internal const val DETAIL_PRINT_TAG = "email-detail-print"
internal const val DETAIL_POPOUT_TAG = "email-detail-popout"

private val PANE_PADDING = 20.dp
private val EMPTY_ICON = 72.dp
private val CHIP_ICON = 12.dp
private val HAIRLINE = 1.dp
private val HEADER_AVATAR = 40.dp
private val HEADER_BUTTON = 28.dp
private val CARET = 14.dp
private val THUMB_MAX = 260.dp
private val DETAILS_MIN = 300.dp
private val DETAILS_MAX = 480.dp
private val DETAIL_LABEL = 56.dp
private val READ_BY_AVATAR = 28.dp
private const val EMPTY_ALPHA = 0.6f
private const val PREVIEW_CHARS = 160
