package com.zillit.desktop.feature.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuDivider
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntries
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.animation.core.animateFloat
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.media.MediaPreviewDialog
import com.zillit.desktop.core.media.PreviewItem
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatReplyRef
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.domain.chatDayLabel
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.zillit.desktop.feature.chat.domain.MentionSpan
import com.zillit.desktop.feature.chat.domain.chatTimeLabel
import com.zillit.desktop.feature.chat.domain.designationLabel
import com.zillit.desktop.feature.chat.domain.mentionSpans
import kotlinx.datetime.toLocalDateTime

/** One open conversation: header, the bubbles, and the composer. */
@Composable
@Suppress("LongParameterList", "LongMethod") // The thread, its dialogs and its toasts, in one frame.
internal fun ThreadPane(
    state: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    resolveName: (String) -> String? = { null },
    /** Strict crew lookup for tags — null keeps an unknown id as raw text. */
    resolveMention: (String) -> String? = { null },
    /** A tapped tag — the host opens that person. */
    onOpenUser: (String) -> Unit = {},
    /** The crew member behind an id — the readers panel's names and roles. */
    resolveContact: (String) -> com.zillit.desktop.feature.chat.domain.CrewContact? = { null },
    /** Who Forward can send to, besides the rooms the state already lists. */
    forwardPeople: List<com.zillit.desktop.feature.chat.domain.CrewContact> = emptyList(),
    /** The signed-in user — dropped from the readers list, as the web drops them. */
    selfId: String? = null,
    onOpenAttachment: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit = {},
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
    loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) ->
    androidx.compose.ui.graphics.ImageBitmap? = { null },
    /** Rings the open thread on the chosen line. Null hides the call buttons entirely. */
    onCall: ((video: Boolean, line: CallLine) -> Unit)? = null,
    /** Which lines this production offers — Line 3 only where remote config lists it. */
    lines: List<CallLine> = CallLine.DEFAULT,
    /** The one shared speaker; null renders voice notes as plain chips. */
    player: com.zillit.desktop.core.designsystem.component.AudioPlayer? = null,
    /** Fetches a voice note's bytes for decoding. Null disables playback. */
    loadAudio: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray? =
        { null },
) {
    val peer = state.peer ?: return
    val seams = LocalChatSeams.current
    // The lightbox an image bubble opens; null keeps the thread bare.
    var viewing by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<ChatAttachment?>(null)
    }
    // Saving a file to disk is what the download right governs — a chip's
    // open, the menu's Download. The refusal is Android's own sentence.
    var refused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val gatedOpen: (ChatAttachment) -> Unit = { file ->
        if (seams.canDownload()) onOpenAttachment(file) else refused = true
    }
    // The picture being drawn on for an Image Reply; null keeps the editor shut.
    var imageReply by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<ChatMessage?>(null)
    }

    // Painted here, not left to the host: the pane is also embedded inside
    // other tools, and a thread with no ground of its own showed whatever
    // was behind it.
    Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            ThreadHeader(state, peer, loadAvatar, onCall, lines, onEvent)
            Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))

            val media = BubbleMedia(
                onOpen = gatedOpen,
                loadThumbnail = loadThumbnail,
                player = player,
                loadAudio = loadAudio,
                // Pictures open in-app; downloading stays a separate, gated act.
                onView = { viewing = it },
            )
            // Passed beside the react handler rather than through it: deletion is
            // keyed by the server's id, and only rows that have one can offer it.
            Messages(
                state, resolveName, MentionHooks(resolveMention, onOpenUser),
                media, loadAvatar, onEvent, Modifier.weight(1f),
                onImageReply = { imageReply = it },
            )

            // Slides up from the composer and fades back: the one line that
            // is about right now should arrive and leave like it.
            AnimatedVisibility(
                visible = state.peerTyping,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                // A room's typing signal names nobody, so neither does the line.
                TypingIndicator(if (state.peerIsGroup) "Someone" else peer.fullName.substringBefore(' '))
            }

            if (refused) {
                DownloadRefusedNotice(seams.requestDownloadRights) { refused = false }
            }

            state.replyTo?.let { parent ->
                ChatReplyBar(
                    parent = parent,
                    authorLabel = if (parent.isMine) {
                        "yourself"
                    } else {
                        resolveName(parent.senderId) ?: peer.fullName
                    },
                    onCancel = { onEvent(ChatEvent.CancelReply) },
                )
            }

            // No composer for someone who left — there is nobody to deliver
            // to, and Android hides its whole action row (userActive).
            if (state.peerIsGroup || !peer.hasLeft) {
                Composer(state, peer.fullName, onEvent)
            }
        }

        viewing?.let { file ->
            ChatMediaViewer(
                file = file,
                // The full object where the host offers it; the poster otherwise.
                loadImage = { seams.loadFullImage?.invoke(it) ?: loadThumbnail(it) },
                canDownload = seams.canDownload,
                onDownload = onOpenAttachment,
                onClose = { viewing = null },
            )
        }

        ChatPreviewHost(state, onEvent)

        // The bubble menu's dialogs, over the thread.
        EditMessageDialog(state, onEvent)
        ForwardDialog(state, forwardPeople, onEvent)
        ReadByDialog(state, selfId, resolveContact, onEvent)
        ChatImageReplyDialog(
            target = imageReply,
            loadImage = { file -> seams.loadFullImage?.invoke(file) ?: loadThumbnail(file) },
            onPost = { name, type, bytes, caption ->
                imageReply = null
                onEvent(ChatEvent.ImageReply(name, type, bytes, caption))
            },
            onDismiss = { imageReply = null },
        )

        // Refusals and confirmations float at the foot, as the web's
        // `message.error` / `message.success` do. The state carried an
        // error for a long time with nothing drawing it — a refused send or
        // delete simply looked like nothing happened.
        // Lifted clear of the composer, which the toast would otherwise sit on.
        com.zillit.desktop.core.designsystem.component.ZillitErrorToast(
            message = state.error,
            onDismiss = { onEvent(ChatEvent.DismissError) },
            modifier = Modifier.padding(bottom = TOAST_CLEARANCE),
        )
        com.zillit.desktop.core.designsystem.component.ZillitToast(
            message = state.info,
            onDismiss = { onEvent(ChatEvent.DismissInfo) },
            tone = com.zillit.desktop.core.designsystem.component.ZillitToastTone.Success,
            modifier = Modifier.padding(bottom = TOAST_CLEARANCE),
        )
    }
}

/**
 * The picked (or pasted) file, before it joins the thread — the phones'
 * gallery viewer: caption, and a picture's edit tools. One item at a time:
 * the chat wire takes one file per message.
 */
@Composable
private fun ChatPreviewHost(state: ChatUiState, onEvent: (ChatEvent) -> Unit) {
    val pending = state.pendingPreview
    MediaPreviewDialog(
        items = androidx.compose.runtime.remember(pending) {
            pending?.let { listOf(PreviewItem(it.name, it.contentType, it.bytes)) }.orEmpty()
        },
        initialCaption = "",
        onSend = { results, caption ->
            results.firstOrNull()?.let { onEvent(ChatEvent.PreviewSend(it, caption)) }
        },
        onCancel = { onEvent(ChatEvent.PreviewCancelled) },
    )
}

/** Android's refusal (`msg_download_right`), dismissed with its X. */
@Composable
private fun DownloadRefusedNotice(onAsk: (() -> Unit)?, onDismiss: () -> Unit) {
    ZillitNotice(
        text = DOWNLOAD_REFUSED,
        tone = StatusTone.Rejected,
        modifier = Modifier.padding(
            horizontal = ZillitTheme.spacing.md,
            vertical = ZillitTheme.spacing.xxs,
        ),
        action = {
            // The sentence names an admin without offering one. This does.
            if (onAsk != null) {
                ZillitButton(
                    text = "Ask an admin",
                    onClick = {
                        onAsk()
                        onDismiss()
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = "Dismiss",
                onClick = onDismiss,
                size = REACT_BUTTON,
            )
        },
    )
}

/**
 * "Replying to …" over the composer — feature/home's ReplyBar
 * (HomeFeedScreen.kt:976-1008) in chat's frame: who, one snippet line, and
 * the X that goes back to a plain message.
 */
@Composable
private fun ChatReplyBar(parent: ChatMessage, authorLabel: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xxs)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.accentSoft)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = "Replying to $authorLabel",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accentText,
            )
            val snippet = parent.body.ifBlank { parent.attachment?.name.orEmpty() }
            if (snippet.isNotBlank()) {
                ZillitText(
                    text = snippet,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Cancel the reply",
            onClick = onCancel,
        )
    }
}

/**
 * Green dot and the word, under the name — the same signal iOS's chat header
 * shows as green "Online" text and the web's shows as a green avatar dot.
 * Silent when offline or unknown: the header already carries a last-entry
 * line elsewhere, and a grey "offline" would just be furniture.
 */
@Composable
private fun OnlineLine(state: ChatUiState) {
    if (!state.peerOnline || state.peerIsGroup) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(
            Modifier
                .size(ONLINE_DOT)
                .background(ZillitTheme.colors.success, CircleShape),
        )
        ZillitText(
            text = "Online",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.success,
        )
    }
}

/**
 * "Disconnected", in red, under a peer who left the production. Their
 * history stays readable, but the composer below is gone — Android's
 * `userActive` (ChatAndGroupPage.kt:362-377) and its `disconnected_text`.
 */
@Composable
private fun DisconnectedLine(state: ChatUiState, peer: com.zillit.desktop.feature.chat.domain.CrewContact) {
    if (!state.peerIsGroup && peer.hasLeft) {
        ZillitText(
            text = "Disconnected",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.danger,
        )
    }
}

/**
 * Who the thread is with: face, name, where they sit — and the two calls.
 *
 * The face is the loaded avatar, not initials: the header is this pane's one
 * fixed landmark, and it should look like the person. The call buttons are
 * tinted discs rather than bare glyphs — they are the header's two actions,
 * and the close beside them is not one.
 */
/**
 * Who this conversation is with: name, presence, role, address.
 *
 * Everything the contact card used to carry, now that the card is no longer on
 * the way to a conversation — the header has to answer "which Sam is this" on
 * its own.
 */
@Composable
private fun ThreadIdentity(
    state: ChatUiState,
    peer: com.zillit.desktop.feature.chat.domain.CrewContact,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ZillitText(text = peer.fullName, style = ZillitTheme.typography.titleSmall)
        OnlineLine(state)
        DisconnectedLine(state, peer)
        // Department and role together — the same line their crew card leads
        // with.
        val role = listOfNotNull(
            peer.department?.takeIf { it.isNotBlank() },
            peer.designationLabel(),
        ).joinToString(" · ") { it.localised() }
        if (role.isNotBlank()) {
            ZillitText(
                text = role,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        // A line of its own rather than a third item on the role line: an
        // address is long, and appended there it would be the first thing
        // truncated — which is the same as not showing it. Groups have no
        // address, so the null check is the whole guard.
        peer.email?.takeIf { it.isNotBlank() }?.let { address ->
            ZillitText(
                text = address,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ThreadHeader(
    state: ChatUiState,
    peer: com.zillit.desktop.feature.chat.domain.CrewContact,
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    onCall: ((video: Boolean, line: CallLine) -> Unit)?,
    lines: List<CallLine>,
    onEvent: (ChatEvent) -> Unit,
) {
    val face = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        peer.userId,
    ) { value = loadAvatar(peer.userId) }.value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = peer.fullName, image = face, size = HEADER_AVATAR)
        ThreadIdentity(state = state, peer = peer, modifier = Modifier.weight(1f))
        // Callable when the peer has a device to ring (groups always do —
        // the room is the address). No device, no buttons: a call button
        // that fails on press is worse than none. Someone who has left the
        // production keeps their device id on the crew row — the phone is
        // still registered — but the call would be refused at the other end,
        // so they get the thread and not the buttons.
        val callable = state.peerIsGroup || (peer.deviceId != null && !peer.hasLeft)
        if (onCall != null && callable) {
            CallLineButton(
                icon = ZillitIcons.Phone,
                label = "Start call",
                tint = ZillitTheme.colors.success,
                disc = ZillitTheme.colors.successSoft,
                lines = lines,
                onPick = { line -> onCall(false, line) },
            )
            CallLineButton(
                icon = ZillitIcons.Camera,
                label = "Start video call",
                tint = ZillitTheme.colors.accentText,
                disc = ZillitTheme.colors.accentSoft,
                lines = lines,
                onPick = { line -> onCall(true, line) },
            )
            Spacer(Modifier.width(ZillitTheme.spacing.xs))
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close conversation",
            onClick = { onEvent(ChatEvent.CloseThread) },
        )
    }
}

/**
 * A call button that asks which line first.
 *
 * The two lines are separate call plumbing on the server — a different
 * endpoint each, different media stacks, different failure modes — so this is
 * a real choice rather than a preference. It is offered here, at the moment of
 * calling, because that is where the phones offer it and because the answer
 * can reasonably differ call to call while one line is being rolled out.
 *
 * Line 2 is listed first and is what a plain Return picks: it is the line
 * every deployment has, and the one the desktop has carried longest.
 */
@Composable
@Suppress("LongParameterList") // One button's look and its menu.
private fun CallLineButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    /** The tinted disc under the glyph — the header's two actions, not two more grey icons. */
    disc: androidx.compose.ui.graphics.Color,
    lines: List<CallLine>,
    onPick: (line: CallLine) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(CALL_DISC)
            .clip(CircleShape)
            .background(disc),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIconButton(
            icon = icon,
            contentDescription = label,
            onClick = { open = true },
            tint = tint,
            size = CALL_DISC,
        )
        // Each line's row: the line's number and nothing else. It used to
        // carry the media stack's name beside it — "Agora", "Mediasoup" — on
        // the theory that "Line 1" alone means nothing; but those are our
        // vendors, not the user's vocabulary, and naming them here puts an
        // implementation detail in front of somebody choosing how to ring a
        // colleague. The phones offer the same choice by number alone.
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = lines.map { line ->
                ZillitMenuEntry.Action(label = line.label, icon = icon, tone = ZillitMenuTone.Approve) {
                    onPick(line)
                }
            },
        )
    }
}

/**
 * Three breathing dots in a bubble-shaped pill, then the words.
 *
 * Animated because "is typing" is the one line on this screen about something
 * happening *right now* — static text reads as a state that got stuck.
 */
@Composable
private fun TypingIndicator(firstName: String) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.padding(
            horizontal = ZillitTheme.spacing.md,
            vertical = ZillitTheme.spacing.xxs,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.surface)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(TYPING_DOT_GAP),
        ) {
            repeat(TYPING_DOTS) { index ->
                val alpha by transition.animateFloat(
                    initialValue = TYPING_DOT_REST,
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(TYPING_DOT_MILLIS),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                        initialStartOffset = androidx.compose.animation.core.StartOffset(
                            index * TYPING_DOT_STAGGER_MILLIS,
                        ),
                    ),
                    label = "dot$index",
                )
                Box(
                    Modifier
                        .size(TYPING_DOT)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(ZillitTheme.colors.textMuted.copy(alpha = alpha)),
                )
            }
        }
        ZillitText(
            text = "$firstName is typing…",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}


/**
 * The writing bar, the board's bar to the pixel: plus, microphone, emoji,
 * the pill field, one filled send. A crew member moves between the Home
 * board and a thread without the controls moving under them.
 */
@Composable
private fun Composer(state: ChatUiState, peerName: String, onEvent: (ChatEvent) -> Unit) {
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))
    if (state.recordingSeconds != null) {
        Box(Modifier.fillMaxWidth().background(ZillitTheme.colors.surface).padding(ZillitTheme.spacing.sm)) {
            com.zillit.desktop.core.designsystem.component.ZillitRecordingBar(
                seconds = state.recordingSeconds ?: 0,
                onCancel = { onEvent(ChatEvent.CancelRecording) },
                onStop = { onEvent(ChatEvent.StopRecording) },
            )
        }
        return
    }
    // The caret belongs in the field after every send: Enter keeps it there
    // by never leaving, and the send button hands it straight back — a click
    // on a button takes focus with it, and a composer that goes dark after
    // each line makes the next one start with a click.
    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Cmd+V with a picture on the clipboard attaches it (through the same
    // preview a picked file gets); with none, the field pastes text as ever.
    val seams = LocalChatSeams.current
    val pasteImage: () -> Boolean = paste@{
        val image = seams.clipboard?.readImage() ?: return@paste false
        onEvent(ChatEvent.ImagePasted(image.name, image.contentType, image.bytes))
        true
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ComposerActions(state, onEvent)
        ZillitTextField(
            value = state.draft,
            onValueChange = { onEvent(ChatEvent.DraftChanged(it)) },
            placeholder = "Message $peerName…",
            shape = androidx.compose.foundation.shape.RoundedCornerShape(COMPOSER_RADIUS),
            // Multi-line like the board's composer: Enter sends, Shift+Enter
            // breaks the line — the same keys the board answers to.
            singleLine = false,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = COMPOSER_MIN_HEIGHT)
                .focusRequester(fieldFocus)
                .onPreviewKeyEvent { event ->
                    handleChatComposerKey(event, state.canSend, onEvent, pasteImage)
                },
        )
        ZillitIconButton(
            icon = ZillitIcons.Send,
            contentDescription = "Send",
            onClick = {
                onEvent(ChatEvent.Send)
                fieldFocus.requestFocus()
            },
            enabled = state.canSend,
            filled = true,
            size = SEND_BUTTON,
        )
    }
}

/**
 * Enter sends, Shift+Enter breaks the line — the board's rule, so the two
 * composers agree (see feature/home's handleComposerKey). A blank Enter is
 * swallowed rather than inserting a newline nobody asked for. Paste asks the
 * clipboard for a picture first: [onPasteImage] true consumes the key (the
 * image goes to the preview), false lets the field paste text normally.
 */
private fun handleChatComposerKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    canSend: Boolean,
    onEvent: (ChatEvent) -> Unit,
    onPasteImage: () -> Boolean = { false },
): Boolean {
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
    if (event.key == Key.V && (event.isMetaPressed || event.isCtrlPressed)) return onPasteImage()
    if (event.key != androidx.compose.ui.input.key.Key.Enter || event.isShiftPressed) return false
    if (canSend) onEvent(ChatEvent.Send)
    return true
}

@Composable
@Suppress("LongParameterList")
private fun Messages(
    state: ChatUiState,
    resolveName: (String) -> String?,
    mentions: MentionHooks,
    media: BubbleMedia,
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    onEvent: (ChatEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** The menu's Image Reply — the pane owns the editor, the list only asks for it. */
    onImageReply: (ChatMessage) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val seams = LocalChatSeams.current

    // Reversed layout pins the visual bottom by construction: media bubbles
    // above can inflate as thumbnails load without shoving the newest message
    // out of view. Opens at the newest, follows arrivals only while the
    // reader is at the bottom — scrolled up means reading, not following.
    com.zillit.desktop.core.designsystem.component.FollowLatestReversed(
        listState,
        state.messages.size,
        contentKey = state.peer?.userId,
    )

    if (state.isLoading && state.messages.isEmpty()) {
        LoadingThread(modifier)
        return
    }

    if (!state.isLoading && state.messages.isEmpty()) {
        EmptyThread(state.peer?.fullName.orEmpty(), modifier)
        return
    }

    val now = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    val rows = remember(state.messages, now) { threadRows(state.messages, now) }

    // A tapped quote scrolls to its original — Android's `handleReplyMessage`
    // (ChatAndGroupPage.kt:1903-1915). An original not loaded is a no-op.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val jumpTo: (String) -> Unit = { messageId ->
        val index = rows.indexOfFirst { (it as? ThreadRow.Message)?.message?.id == messageId }
        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
    }

    ZillitLazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md),
        // Newest at index 0, pinned to the visual bottom — see the note on
        // FollowLatestReversed above. The rows are built reversed to match.
        reverseLayout = true,
        // Tight by default — a run's lines sit together — and the run's first
        // line (and the day chip) add their own room; see IncomingAware.
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            vertical = ZillitTheme.spacing.md,
        ),
    ) {
        items(rows, key = ThreadRow::key) { row ->
            // A new line slides the rest up rather than snapping them: the
            // thread should look like it is moving, not reloading.
            Box(Modifier.animateItem()) {
                when (row) {
                    is ThreadRow.DayMark -> DayChip(row.label)
                    is ThreadRow.Message -> MessageRow(
                        row, state, resolveName, mentions, media, loadAvatar,
                        actions = bubbleActions(row.message, state, seams, onEvent, onImageReply),
                        onJumpTo = jumpTo,
                    )
                }
            }
        }
        olderPager(state, onEvent)
    }
}

/**
 * One message row. Incoming rows in a room carry the writer's face and
 * name; in a DM both would say the obvious, so the bubble stands alone.
 */
@Composable
@Suppress("LongParameterList") // The list's shared hooks, handed down once per row.
private fun MessageRow(
    row: ThreadRow.Message,
    state: ChatUiState,
    resolveName: (String) -> String?,
    mentions: MentionHooks,
    media: BubbleMedia,
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    actions: BubbleActions,
    onJumpTo: (String) -> Unit,
) {
    IncomingAware(
        message = row.message,
        senderName = if (state.peerIsGroup && !row.message.isMine) {
            resolveName(row.message.senderId)
        } else {
            null
        },
        run = row.run,
        loadAvatar = loadAvatar,
        media = media,
        actions = actions,
        onJumpTo = onJumpTo,
        uploadPercent = state.uploads[row.message.uniqueId],
        resolveName = resolveName,
        mentions = mentions,
        translation = state.translations[row.message.id],
    )
}

/**
 * Appended in a reversed list means the visual top: the quiet pager the call
 * log uses (feature/calls CallLogPane.kt:211-227).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.olderPager(
    state: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
) {
    if (!state.hasOlder) return
    item(key = "show-older") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(ChatEvent.ShowOlder) }
                .padding(ZillitTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = if (state.loadingOlder) "Loading…" else "Show older",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accentText,
            )
        }
    }
}

/**
 * A row in the reversed message list: a bubble, or the day chip above it.
 *
 * Built as data rather than composed inline so the reversed ordering — the
 * chip must land *above* its day's first message while the list renders
 * newest-first — is a list operation that a plain test can pin down.
 */
internal sealed interface ThreadRow {
    val key: String

    data class Message(val message: ChatMessage, val run: RunPosition = RunPosition.ALONE) : ThreadRow {
        override val key: String get() = message.uniqueId
    }

    data class DayMark(val label: String, override val key: String) : ThreadRow
}

/**
 * Where a message sits in a run of consecutive lines from one writer.
 *
 * Lines from the same person within [RUN_GAP_MILLIS] of each other read as
 * one turn of speech, so only the run's last bubble wears the tail and only
 * its first names the writer — every chat client's convention, and the
 * difference between a conversation and a stack of labelled cards.
 */
internal enum class RunPosition(val first: Boolean, val last: Boolean) {
    ALONE(first = true, last = true),
    FIRST(first = true, last = false),
    MIDDLE(first = false, last = false),
    LAST(first = false, last = true),
    ;

    companion object {
        fun of(first: Boolean, last: Boolean): RunPosition = entries.first { it.first == first && it.last == last }
    }
}

/**
 * Interleaves day chips into [messages], newest row first.
 *
 * Chronological first — a chip lands before each day's first message — then
 * reversed whole, which is exactly what a `reverseLayout` list renders
 * bottom-up. Messages with no timestamp never break the day: a chip for the
 * epoch would say "1 Jan 1970" over a message that just has no clock.
 */
internal fun threadRows(
    messages: List<ChatMessage>,
    nowMillis: Long,
    zone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault(),
): List<ThreadRow> {
    val rows = ArrayList<ThreadRow>(messages.size + DAY_ROOM)
    var lastDay: kotlinx.datetime.LocalDate? = null
    messages.forEachIndexed { index, message ->
        var newDay = false
        if (message.timestampMillis > 0) {
            val day = kotlin.time.Instant.fromEpochMilliseconds(message.timestampMillis)
                .toLocalDateTime(zone).date
            if (day != lastDay) {
                rows += ThreadRow.DayMark(
                    label = chatDayLabel(message.timestampMillis, nowMillis, zone),
                    key = "day-$day",
                )
                lastDay = day
                newDay = true
            }
        }
        // A day chip breaks a run: the first line after it starts fresh.
        val first = newDay || !continuesRun(messages.getOrNull(index - 1), message)
        val next = messages.getOrNull(index + 1)
        val last = next == null || !continuesRun(message, next) || next.startsNewDay(message, zone)
        rows += ThreadRow.Message(message, RunPosition.of(first, last))
    }
    return rows.asReversed()
}

/** [later] continues [earlier]'s run: same writer, same side, close in time. */
private fun continuesRun(earlier: ChatMessage?, later: ChatMessage): Boolean =
    earlier != null &&
        earlier.senderId == later.senderId &&
        earlier.isMine == later.isMine &&
        later.timestampMillis > 0 &&
        earlier.timestampMillis > 0 &&
        later.timestampMillis - earlier.timestampMillis in 0..RUN_GAP_MILLIS

private fun ChatMessage.startsNewDay(previous: ChatMessage, zone: kotlinx.datetime.TimeZone): Boolean {
    if (timestampMillis <= 0 || previous.timestampMillis <= 0) return false
    val mine = kotlin.time.Instant.fromEpochMilliseconds(timestampMillis).toLocalDateTime(zone).date
    val theirs = kotlin.time.Instant.fromEpochMilliseconds(previous.timestampMillis).toLocalDateTime(zone).date
    return mine != theirs
}

/** The day, centred on its own quiet line — every chat client's convention. */
@Composable
private fun DayChip(label: String) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.surface)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        )
    }
}

/** History on its way; the pane says so rather than sitting blank. */
@Composable
private fun LoadingThread(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = "Loading the conversation…",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** A thread with nothing in it yet is an invitation, not an error. */
@Composable
private fun EmptyThread(peerName: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Chat,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = EMPTY_GLYPH,
            )
            ZillitText(
                text = "No messages yet.",
                style = ZillitTheme.typography.titleSmall,
            )
            ZillitText(
                text = "Say hello to ${peerName.substringBefore(' ').ifBlank { "them" }} — " +
                    "only the two of you see this thread.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** An incoming row: face beside the bubble; own rows stay bare. */
@Composable
@Suppress("LongParameterList")
private fun IncomingAware(
    message: ChatMessage,
    senderName: String?,
    run: RunPosition = RunPosition.ALONE,
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    media: BubbleMedia,
    actions: BubbleActions,
    onJumpTo: (String) -> Unit = {},
    uploadPercent: Int? = null,
    resolveName: (String) -> String? = { null },
    mentions: MentionHooks = MentionHooks(),
    translation: String? = null,
) {
    // A run's first line stands a little clear of the turn before it; the
    // rest sit tight, so a turn of speech reads as one block.
    val runGap = Modifier.padding(top = if (run.first) ZillitTheme.spacing.sm else 0.dp)
    if (message.isMine || senderName == null) {
        Box(runGap) {
            Bubble(
                message, senderName, run, media, actions, onJumpTo,
                uploadPercent, resolveName, mentions, translation,
            )
        }
        return
    }
    // A room's line: the writer's face beside the run's last bubble, the
    // others indented to meet it. Names ride the first bubble instead.
    Row(
        modifier = runGap,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (run.last) {
            val face = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                initialValue = null,
                message.senderId,
            ) { value = loadAvatar(message.senderId) }.value
            ZillitAvatar(name = senderName, image = face, size = ROW_AVATAR)
        } else {
            Spacer(Modifier.width(ROW_AVATAR))
        }
        Bubble(
            message, senderName.takeIf { run.first }, run, media, actions,
            onJumpTo = onJumpTo,
            resolveName = resolveName,
            mentions = mentions,
            translation = translation,
        )
    }
}

/**
 * What one bubble's menu can do, gathered once. A null verb is one the
 * menu leaves out — the Home board's rule of showing nothing it would only
 * refuse, applied a step earlier than the web's show-then-refuse.
 */
@Suppress("LongParameterList") // One slot per verb the menu can show.
internal class BubbleActions(
    val onReact: (String) -> Unit,
    val onReply: () -> Unit,
    /** Our own delivered line — Edit for words or a caption, Delete for any. */
    val onEdit: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onForward: (() -> Unit)? = null,
    /** Hands the line to the mailbox composer; null when the host has none. */
    val onShare: (() -> Unit)? = null,
    /** A room's line only. */
    val onReadBy: (() -> Unit)? = null,
    /** Someone's words, when the production and this computer disagree on language. */
    val onTranslate: (() -> Unit)? = null,
    val translating: Boolean = false,
    /** Set once a translation is on screen; takes it back. */
    val onShowOriginal: (() -> Unit)? = null,
    /** A picture — the pen editor. */
    val onImageReply: (() -> Unit)? = null,
)

/**
 * The verbs for one line, decided as the web decides its `items`
 * (`MyMessage.jsx:205-563`, `SenderMessage.jsx:229-322`), with the
 * differences the two menus have from each other kept:
 *
 * - Edit: our own line with words (a media caption counts; a location and
 *   a bare file do not) — `MyMessage.jsx:478-528`.
 * - Delete: our own line. The web's menu on someone else's line in a room
 *   shows Delete and refuses the click with "no permission"
 *   (`SenderMessage.jsx:366-372`); it is left out here.
 * - Forward and Share: any delivered line, ours or theirs.
 * - Read by: a room's line — `if (!isprivate)` on both menus.
 * - Translate: someone else's words, not yet translated, when the languages
 *   differ (`SenderMessage.jsx:253-260`, ZL-16953).
 * - Image Reply: a picture, ours or theirs.
 *
 * A line the server has not taken yet (its id still our unique id) gets
 * React and Reply's guard only: nothing else can address it.
 */
private fun bubbleActions(
    message: ChatMessage,
    state: ChatUiState,
    seams: ChatSeams,
    onEvent: (ChatEvent) -> Unit,
    onImageReply: (ChatMessage) -> Unit,
): BubbleActions {
    val delivered = message.id.isNotBlank() && message.id != message.uniqueId
    val hasWords = message.body.isNotBlank() && message.location == null
    val isPicture = message.attachment?.kind == "image" && message.location == null
    val translated = message.id in state.translations
    return BubbleActions(
        onReact = { emoji -> onEvent(ChatEvent.React(message.id, emoji)) },
        onReply = { onEvent(ChatEvent.StartReply(message.id)) },
        onEdit = { onEvent(ChatEvent.StartEdit(message.id)) }.takeIf { message.isMine && delivered && hasWords },
        onDelete = { onEvent(ChatEvent.Delete(message.id)) }.takeIf { message.isDeletable },
        onForward = { onEvent(ChatEvent.StartForward(message.id)) }.takeIf { delivered },
        onShare = seams.shareAsEmail?.let { share ->
            {
                val refusal = share(message)
                if (refusal != null) onEvent(ChatEvent.Refused(refusal))
            }
        }?.takeIf { delivered },
        onReadBy = { onEvent(ChatEvent.ShowReadBy(message.id)) }.takeIf { state.peerIsGroup && delivered },
        onTranslate = { onEvent(ChatEvent.Translate(message.id)) }
            .takeIf { state.translateOffered && !message.isMine && hasWords && delivered && !translated },
        translating = message.id in state.translating,
        onShowOriginal = { onEvent(ChatEvent.ShowOriginal(message.id)) }.takeIf { translated },
        onImageReply = { onImageReply(message) }.takeIf { isPicture && delivered },
    )
}

/** The tag affordances, carried together so the bubble chain stays short. */
internal data class MentionHooks(
    /** Strict crew lookup — null keeps an unknown id as raw text. */
    val resolve: (String) -> String? = { null },
    val onOpenUser: (String) -> Unit = {},
)

/**
 * The body with its tags and links lit: `@{{id}}` renders as an accent-coloured
 * `@Full Name` that opens the person, and a web or email address as an
 * underlined accent link the OS opens — the phones' `autoLink` treatment. A
 * body with neither is one plain text node.
 */
@Composable
private fun MentionedBody(body: String, mentions: MentionHooks) {
    val spans = androidx.compose.runtime.remember(body) { mentionSpans(body, mentions.resolve) }
    val plain = spans.singleOrNull() as? MentionSpan.Words
    if (plain != null) {
        ZillitText(
            text = plain.text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
        return
    }

    val accent = ZillitTheme.colors.accentText
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
        hoveredStyle = SpanStyle(color = ZillitTheme.colors.accentHover, textDecoration = TextDecoration.Underline),
    )
    val annotated = buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is MentionSpan.Words -> append(span.text)
                is MentionSpan.Mention ->
                    withLink(
                        LinkAnnotation.Clickable("mention:${span.userId}") {
                            mentions.onOpenUser(span.userId)
                        },
                    ) {
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                            append("@${span.name}")
                        }
                    }
                // A Url annotation opens through LocalUriHandler on its own —
                // the desktop's hands the address to the default browser or
                // mail client.
                is MentionSpan.Link -> withLink(LinkAnnotation.Url(span.url, linkStyles)) { append(span.text) }
            }
        }
    }
    ZillitText(
        text = annotated,
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textPrimary,
    )
}

/**
 * Only rows the server can address offer deletion: a just-sent bubble still
 * carries its local unique id as [ChatMessage.id] until the echo or the next
 * history load replaces it, and asking the server to delete an id it never
 * issued is a refusal, not a removal.
 */
private val ChatMessage.isDeletable: Boolean get() = isMine && id != uniqueId

/** The trash beside our own bubble — hover-only, like the react next to it. */
@Composable
private fun DeleteAffordance(onDelete: () -> Unit) {
    ZillitIconButton(
        icon = ZillitIcons.Trash,
        contentDescription = "Delete for everyone",
        onClick = onDelete,
        tint = ZillitTheme.colors.textMuted,
        size = REACT_BUTTON,
    )
}

/** Everything a bubble can do with its attachment, gathered once. */
internal class BubbleMedia(
    /** Saves to disk and hands to the OS — the gated, download-right path. */
    val onOpen: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit,
    val loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) ->
    androidx.compose.ui.graphics.ImageBitmap?,
    val player: com.zillit.desktop.core.designsystem.component.AudioPlayer?,
    val loadAudio: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray?,
    /** Opens the in-app lightbox — looking, which no right governs. */
    val onView: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit = onOpen,
)

/**
 * The quoted parent above a reply's own words — the phones' quote block:
 * who, then one muted line (the parent's words, or its file's name), behind
 * an accent bar. Clicking jumps to the original when it is loaded.
 */
@Composable
private fun QuotedLine(
    quoted: ChatReplyRef,
    resolveName: (String) -> String?,
    onJump: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(bottom = ZillitTheme.spacing.xxs)
            .clip(ZillitTheme.shapes.small)
            .background(ZillitTheme.colors.surfaceHover)
            .clickable(onClick = onJump)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(QUOTE_BAR)
                .fillMaxHeight()
                .background(ZillitTheme.colors.accentText),
        )
        Column(Modifier.padding(ZillitTheme.spacing.xs)) {
            ZillitText(
                text = resolveName(quoted.senderId) ?: "Someone",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accentText,
                maxLines = 1,
            )
            // A quoted place reads as a place, not as the map screenshot's
            // file name — the web's quote block draws a pin and the word
            // "Location" for `reply.message_type === 'location'`
            // (SenderMessage.jsx:532-538). Here the pin leads and the
            // parent's own label (its address) follows when it has one.
            val isPlace = quoted.kind == com.zillit.desktop.feature.chat.data.LOCATION_KIND
            // A wordless quote names the file, as Android's does
            // (HoldersViewhandler.kt:792-795).
            val snippet = when {
                isPlace -> "📍 " + quoted.body.ifBlank { "Location" }
                else -> quoted.body.ifBlank {
                    quoted.attachmentName.ifBlank { quoted.kind.replaceFirstChar(Char::uppercase) }
                }
            }
            ZillitText(
                text = snippet,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 2,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod") // One bubble: its affordances, its menu, its body.
private fun Bubble(
    message: ChatMessage,
    senderName: String? = null,
    run: RunPosition = RunPosition.ALONE,
    media: BubbleMedia,
    actions: BubbleActions,
    onJumpTo: (String) -> Unit = {},
    uploadPercent: Int? = null,
    resolveName: (String) -> String? = { null },
    mentions: MentionHooks = MentionHooks(),
    translation: String? = null,
) {
    val mine = message.isMine
    val onReact = actions.onReact
    val hover = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val hovered by hover.collectIsHoveredAsState()
    // Open state lives here, not in the affordance: the popup steals the
    // pointer, the row loses hover, and an affordance gated on hover
    // alone would leave composition and take its own menu with it.
    var reactOpen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // The bubble's own menu — a right-click or a long-press, in a room or a
    // DM alike: the phones' long-press sheet, the desktop's right button.
    var menuOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .pointerInput(message.id) {
                awaitEachGesture {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        event.changes.forEach { it.consume() }
                        menuOpen = true
                    }
                }
            }
            .pointerInput(message.id) { detectTapGestures(onLongPress = { menuOpen = true }) },
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        // The menu's anchor sits at the bubble's own corner, not the row's
        // start: anchored to the full-width row, a right-aligned self bubble
        // opened its menu at the far LEFT of the pane (QA #5). A zero-size
        // box aligned per side gives the popup the bubble's edge to hang
        // from, and `alignEnd` walks it leftward so its end meets the
        // bubble's end.
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.align(if (mine) Alignment.TopEnd else Alignment.TopStart),
            ) {
                BubbleMenu(
                    open = menuOpen,
                    onDismiss = { menuOpen = false },
                    message = message,
                    media = media,
                    actions = actions,
                    alignEnd = mine,
                )
            }
        }
        // The affordance sits on the bubble's inner side — between it and the
        // row's empty half. Anchored at the row's outer edge, the menu's
        // popup window has no room to be placed and is never shown at all.
        // The affordances are always composed and *faded* until hover — not
        // conditionally composed on hover. Composed only while hovered, they
        // vanished for the instant of the press (the row's hover drops as the
        // press relays out) and the click landed on nothing; the reaction on
        // one's own message "did nothing" exactly this way. See the Home
        // board's kebab for the same lesson.
        val revealed = hovered || reactOpen
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            if (mine) {
                Row(Modifier.alpha(if (revealed) 1f else 0f)) {
                    actions.onDelete?.let { DeleteAffordance(it) }
                    ReactAffordance(
                        open = reactOpen,
                        onOpenChange = { reactOpen = it },
                        onReact = onReact,
                        openLeft = true,
                    )
                }
            }
            BubbleBody(
                message, senderName, media, onReact, mine,
                uploadPercent, resolveName, mentions, onJumpTo, translation,
                tail = run.last,
            )
            if (!mine) {
                Box(Modifier.alpha(if (revealed) 1f else 0f)) {
                    ReactAffordance(
                        open = reactOpen,
                        onOpenChange = { reactOpen = it },
                        onReact = onReact,
                    )
                }
            }
        }
    }
}

/** The tinted column itself: attachment, sender, words, clock, chips. */
@Composable
@Suppress("LongParameterList")
private fun BubbleBody(
    message: ChatMessage,
    senderName: String?,
    media: BubbleMedia,
    onReact: (String) -> Unit,
    mine: Boolean,
    uploadPercent: Int? = null,
    resolveName: (String) -> String? = { null },
    mentions: MentionHooks = MentionHooks(),
    onJumpTo: (String) -> Unit = {},
    /** The menu's translation of the words, drawn under them. */
    translation: String? = null,
    /** The run's last line wears the tail; the others are plain pills. */
    tail: Boolean = true,
) {
    Column(
        modifier = Modifier
            .widthIn(max = BUBBLE_MAX_WIDTH)
            // The flattened corner sits where the writer is — the classic
            // tail, drawn with radii instead of a path. Rounding all four
            // made every row read as belonging to nobody.
            .clip(
                androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = BUBBLE_RADIUS,
                    topEnd = BUBBLE_RADIUS,
                    bottomStart = if (mine || !tail) BUBBLE_RADIUS else BUBBLE_TAIL,
                    bottomEnd = if (!mine || !tail) BUBBLE_RADIUS else BUBBLE_TAIL,
                ),
            )
            .background(bubbleFill(mine))
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    ) {
        message.replyTo?.let { quoted ->
            QuotedLine(quoted, resolveName) { onJumpTo(quoted.messageId) }
        }
        // A shared place takes the whole bubble: its attachment is the
        // sender's map screenshot, which belongs inside the card rather than
        // as a second picture beside it, and its body is the card's label
        // rather than a line of words below — the phones hide that body
        // outright (HoldersViewhandler.kt:359,371).
        val place = message.location
        if (place != null) {
            LocationCard(place, message.body, message.attachment, media)
        } else {
            message.attachment?.let { file -> AttachmentBody(file, media, uploadPercent) }
        }
        if (senderName != null) {
            ZillitText(
                text = senderName,
                style = ZillitTheme.typography.labelSmall,
                // The writer's avatar hue, not the app accent: in a busy room
                // the colour is what lets the eye follow one voice.
                color = com.zillit.desktop.core.designsystem.component.avatarHue(senderName),
            )
        }
        if (place == null && message.body.isNotBlank()) {
            MentionedBody(message.body, mentions)
        }
        // The web replaces the words with "original / rule / Translated
        // Message: / translation" (`unitChatUtils.js` `generateTranslatedMessage`);
        // here the original stays and the translation reads beneath it.
        // No full-width rule between them: the bubble is content-sized,
        // and a fillMaxWidth child would stretch every translated line to
        // the bubble's ceiling (seen in the first render).
        if (translation != null) {
            ZillitText(
                text = "Translated message",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
            )
            ZillitText(text = translation, style = ZillitTheme.typography.bodyMedium)
        }
        BubbleFooter(message)
        ReactionChips(message, onReact, resolveName)
    }
}

/**
 * The file a message carries, drawn as what it is.
 *
 * Anything with a picture shows the picture; a voice note shows its player;
 * the chip is the fallback for the rest — unless the file is still climbing to
 * storage, which is its own look: the name over a live bar.
 */
@Composable
private fun AttachmentBody(
    file: com.zillit.desktop.feature.chat.domain.ChatAttachment,
    media: BubbleMedia,
    uploadPercent: Int?,
) {
    when {
        uploadPercent != null -> UploadingFile(file.name, uploadPercent)
        file.kind == "audio" -> VoiceBubble(file, media)
        // A picture opens the in-app viewer; saving stays gated behind its
        // Download (QA #11).
        file.kind == "image" -> MediaThumb(file, media.loadThumbnail, media.onView)
        // A PDF is asked for its poster whether or not the row names one —
        // the host draws page one itself when the server has nothing (a
        // Box production, a Drive share, the phones' placeholder key). The
        // name rides under the page: a cover page rarely says which file.
        file.thumbnail.isNotBlank() || file.isPdf ->
            MediaThumb(
                file,
                media.loadThumbnail,
                media.onOpen,
                playBadge = file.kind == "video",
                namePlate = file.kind == "document",
            )
        else -> FileChip(file, media.onOpen)
    }
}

/**
 * The bubble while its file climbs to storage: the name over a live bar.
 *
 * A negative percent means the file is still being prepared — poster frames
 * and PDF pages are extracted before any byte moves — which gets words
 * rather than a bar stuck at zero.
 */
@Composable
private fun UploadingFile(name: String, percent: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = name,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        if (percent < 0) {
            ZillitText(
                text = "Processing…",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            com.zillit.desktop.core.designsystem.component.ZillitProgressBar(
                fraction = percent / PERCENT_FULL,
                modifier = Modifier.width(UPLOAD_BAR_WIDTH),
            )
            ZillitText(
                text = "Uploading $percent%",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * The reactions already on the message: one chip per emoji with its count,
 * ours ringed. Clicking a chip toggles ours — the same tap-again-to-remove
 * every client shares.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReactionChips(
    message: ChatMessage,
    onReact: (String) -> Unit,
    resolveName: (String) -> String? = { null },
) {
    if (message.reactions.isEmpty()) return
    Row(
        modifier = Modifier.padding(top = ZillitTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        message.reactions.groupBy { it.emoji }.forEach { (emoji, rows) ->
            // Who: resting the pointer on a chip names the people behind it —
            // the phones open a sheet for the same question. The wire carries
            // only ids; the crew list gives the names.
            val who = rows.joinToString { resolveName(it.userId) ?: "Someone" }
            com.zillit.desktop.core.designsystem.component.ZillitTooltip(text = who) {
                Row(
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.pill)
                        .background(ZillitTheme.colors.surfaceHover)
                        .clickable { onReact(emoji) }
                        .padding(horizontal = ZillitTheme.spacing.xs, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ZillitText(text = emoji, style = ZillitTheme.typography.labelSmall)
                    if (rows.size > 1) {
                        ZillitText(
                            text = rows.size.toString(),
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The bubble's menu: the quick reactions in a row, then the web's list in
 * the web's order — Edit and Image Reply first (both menus `unshift` them),
 * Reply, Forward, Copy / Copy image / Download, Translate, Share, then Delete
 * and Read by at the end (`MyMessage.jsx:466-561`, `SenderMessage.jsx:274-322`).
 * Which of them a given line gets is [bubbleActions]' decision. Not here:
 * "Forward to Remote Projects", which needs another production's crew and
 * identity — nothing this client holds.
 *
 * A shared place loses the two file actions; see the note at that branch.
 * Copy stays, because it is gated on the body being non-empty and Android
 * gates its own the same way regardless of kind
 * (`ChatAndGroupPage.kt:2426` `showCopy = !chat.message.isNullOrEmpty()`) —
 * for a location that body is the address, which is a thing worth copying.
 * The web's Copy is text-only (`SenderMessage.jsx:201-215`); this follows the
 * wire authority rather than the browser there.
 */
@Composable
// The sheet is a flat list of its actions; each verb is one branch.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
private fun BubbleMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    message: ChatMessage,
    media: BubbleMedia,
    actions: BubbleActions,
    alignEnd: Boolean = false,
) {
    val seams = LocalChatSeams.current
    val onReact = actions.onReact
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    ZillitMenuSurface(
        expanded = open,
        onDismissRequest = onDismiss,
        // Beside a right-aligned self bubble the menu must hang leftward
        // from its anchor at the bubble's end (QA #5) — the same aim-by-
        // width trick the react affordance uses, and why the reaction row
        // below has a fixed width.
        offset = if (alignEnd) {
            androidx.compose.ui.unit.DpOffset(-REACT_MENU_WIDTH, MENU_DROP)
        } else {
            androidx.compose.ui.unit.DpOffset(0.dp, MENU_DROP)
        },
    ) {
        Row(
            modifier = Modifier
                .width(REACT_MENU_WIDTH)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            QUICK_REACTIONS.forEach { emoji ->
                ZillitText(
                    text = emoji,
                    style = ZillitTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.pill)
                        .clickable {
                            onDismiss()
                            onReact(emoji)
                        }
                        .padding(ZillitTheme.spacing.xs),
                )
            }
        }
        ZillitMenuDivider()
        val entries = buildList {
            actions.onEdit?.let { add(menuLine("Edit", ZillitIcons.Edit, ZillitMenuTone.Primary, it)) }
            actions.onImageReply?.let { add(menuLine("Image Reply", ZillitIcons.Photo, ZillitMenuTone.Primary, it)) }
            // Only a line the server can address can be quoted: a just-sent
            // bubble still wears its local id (see isDeletable's reasoning).
            if (message.id.isNotBlank() && (!message.isMine || message.id != message.uniqueId)) {
                add(menuLine("Reply", ZillitIcons.Reply, ZillitMenuTone.Primary, actions.onReply))
            }
            actions.onForward?.let { add(menuLine("Forward", ZillitIcons.Forward, onClick = it)) }
            if (message.body.isNotBlank()) {
                add(
                    menuLine("Copy", ZillitIcons.Copy) {
                        com.zillit.desktop.core.designsystem.component.copyTextToClipboard(message.body)
                    },
                )
            }
            // A shared place gets NEITHER file action, even though a phone-sent
            // one carries a map screenshot. Both web menus strip Save/Download
            // for `message_type === 'location'` — `MyMessage.jsx:503-508` and
            // `SenderMessage.jsx:292-295` drop key '10', and `DropDown.jsx:271`
            // gates SAVE_TO_DEVICE on `!isLocationMessage` — as does Android
            // (`ChatAndGroupPage.kt:2418` showSave, `:2432` showPrint, `:2438`
            // imageReply). Reply, Forward and Delete stay everywhere; Edit is
            // stripped too (`DropDown.jsx:233`, `ChatAndGroupPage.kt:2445`), and
            // this client has no Edit or Forward to strip.
            message.attachment?.takeIf { message.location == null }?.let { file ->
                // A received picture back onto the clipboard — the seam's write
                // half (AWT Transferable under the hood on the JVM).
                val clipboard = seams.clipboard
                if (file.kind == "image" && clipboard != null) {
                    add(
                        menuLine("Copy image", ZillitIcons.Photo) {
                            scope.launch {
                                val image = seams.loadFullImage?.invoke(file) ?: media.loadThumbnail(file)
                                image?.let { clipboard.writeImage(it) }
                            }
                        },
                    )
                }
                add(menuLine("Download", ZillitIcons.Download) { media.onOpen(file) })
            }
            actions.onTranslate?.let { translate ->
                val label = if (actions.translating) "Translating…" else "Translate"
                add(menuLine(label, ZillitIcons.Globe, onClick = translate))
            }
            actions.onShowOriginal?.let { add(menuLine("Show original", ZillitIcons.Globe, onClick = it)) }
            actions.onShare?.let { add(menuLine("Share", ZillitIcons.Mail, onClick = it)) }
            actions.onDelete?.let { add(menuLine("Delete for everyone", ZillitIcons.Trash, ZillitMenuTone.Danger, it)) }
            actions.onReadBy?.let { add(menuLine("Read by", ZillitIcons.Eye, onClick = it)) }
        }
        ZillitMenuEntries(entries = entries, onDismiss = onDismiss)
    }
}

/** One row of the bubble menu — the design system's entry, in this file's idiom. */
private fun menuLine(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: ZillitMenuTone = ZillitMenuTone.Neutral,
    onClick: () -> Unit,
) = ZillitMenuEntry.Action(label = label, icon = icon, tone = tone, onClick = onClick)

/** The quick six, on hover — the same shortlist the other clients offer. */
@Composable
private fun ReactAffordance(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
    openLeft: Boolean = false,
) {
    Box(modifier) {
        com.zillit.desktop.core.designsystem.component.ZillitIconButton(
            icon = ZillitIcons.Smiley,
            contentDescription = "React",
            onClick = { onOpenChange(true) },
            size = REACT_BUTTON,
        )
        ZillitMenuSurface(
            expanded = open,
            onDismissRequest = { onOpenChange(false) },
            // Beside a right-aligned bubble the menu must grow leftward: the
            // default start-aligned drop would cross the window edge, and a
            // popup that cannot fit inside it is never shown at all.
            offset = if (openLeft) {
                androidx.compose.ui.unit.DpOffset(REACT_BUTTON - REACT_MENU_WIDTH, MENU_DROP)
            } else {
                androidx.compose.ui.unit.DpOffset(0.dp, MENU_DROP)
            },
        ) {
            Row(
                // A fixed width because the offset above depends on it — a
                // content-sized menu has no width to aim with.
                modifier = Modifier
                    .width(REACT_MENU_WIDTH)
                    .padding(horizontal = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                QUICK_REACTIONS.forEach { emoji ->
                    ZillitText(
                        text = emoji,
                        style = ZillitTheme.typography.titleSmall,
                        modifier = Modifier
                            .clip(ZillitTheme.shapes.pill)
                            .clickable {
                                onOpenChange(false)
                                onReact(emoji)
                            }
                            .padding(ZillitTheme.spacing.xs),
                    )
                }
            }
        }
    }
}

/** The plus, the microphone, the pin and the emoji palette. */
@Composable
private fun ComposerActions(state: ChatUiState, onEvent: (ChatEvent) -> Unit) {
    var emojiOpen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // The phones' attach sheet, not a bare file dialog: Photo, Video,
    // Document, Audio — the microphone and the pin stay their own buttons.
    com.zillit.desktop.core.media.AttachMenu(
        kinds = com.zillit.desktop.core.media.ALL_ATTACHMENT_KINDS,
        contentDescription = "Attach a file",
        onPick = { kind -> onEvent(ChatEvent.AttachKind(kind)) },
    )
    ZillitIconButton(
        icon = ZillitIcons.Mic,
        contentDescription = "Record a voice message",
        onClick = { onEvent(ChatEvent.StartRecording) },
    )
    ShareLocationAction(onEvent)
    Box {
        ZillitIconButton(
            icon = ZillitIcons.Smiley,
            contentDescription = "Insert an emoji",
            onClick = { emojiOpen = true },
        )
        ZillitMenuSurface(
            expanded = emojiOpen,
            onDismissRequest = { emojiOpen = false },
        ) {
            com.zillit.desktop.core.designsystem.component.ZillitEmojiPicker(
                onPick = { emoji -> onEvent(ChatEvent.DraftChanged(state.draft + emoji)) },
                modifier = Modifier.padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

/**
 * The pin beside the paperclip: opens the shared map picker and, when the user
 * settles on a place, sends it as a `message_type: "location"` message — the
 * phones' own attach-sheet item (`PICKER_ITEM_LOCATION`,
 * `utils/MediaExtension.kt:40,58`; the CNC composer offers it at
 * `chatAndGroupChat/ChatAndGroupPage.kt:2369`).
 *
 * Absent when no picker is installed, exactly as `ZillitLocationField` hides
 * its own map button (`core:locationpicker/ZillitLocationField.kt:48`): a
 * button whose dialog can never open is worse than no button.
 *
 * The `pick` is launched from the composition's scope rather than the view
 * model because the picker is a composition-local service installed at the app
 * root, and the view model has no composition to read it from. Cancelling
 * answers null and raises no event at all.
 */
@Composable
private fun ShareLocationAction(onEvent: (ChatEvent) -> Unit) {
    val picker = com.zillit.desktop.core.locationpicker.LocalLocationPicker.current ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    ZillitIconButton(
        icon = ZillitIcons.Pin,
        contentDescription = "Share location",
        onClick = {
            scope.launch {
                picker.pick(title = "Share a location")?.let { place ->
                    onEvent(ChatEvent.ShareLocation(place))
                }
            }
        },
    )
}

/**
 * A shared place, as a card: the map raster when the sender uploaded one, the
 * label, the address, the coordinates, and the way out to a real map.
 *
 * ## Why a card and not just the picture
 *
 * The phones draw ONLY the picture — a screenshot of their own map, taken at
 * send time (`mapView/MapsActivity.kt:205-224`) and uploaded as the message's
 * attachment — and hide the body entirely
 * (`viewholders/HoldersViewhandler.kt:359,371`); the web does the same and has
 * its caption commented out (`components/sendMessage/RenderLocation.jsx:98-103`).
 * This client takes no raster, so a place it sent would be a blank bubble
 * under that rule. The card says where the pin is in words instead, and still
 * shows the raster when one arrived from a phone.
 *
 * The coordinates are shown for the reason the picker's own summary shows them
 * (`desktopApp/LocationPickerWiring.kt:157-207`, after the web's
 * `PlacePicker.jsx:249-289`): two places can share a name, and the pair is
 * what can be read out to a driver.
 */
@Composable
private fun LocationCard(
    location: com.zillit.desktop.feature.chat.domain.ChatLocation,
    label: String,
    file: com.zillit.desktop.feature.chat.domain.ChatAttachment?,
    media: BubbleMedia,
) {
    val open = LocalChatSeams.current.onOpenUrl
    val headline = label.ifBlank { location.address }.ifBlank { "Shared location" }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        // The sender's own map picture, where there is one. Its click opens
        // the map rather than the lightbox — Android's location branch in
        // `handleImageClickListener` (HoldersViewhandler.kt:306-318) does the
        // same, and a screenshot is not something to look at closely.
        if (file != null && file.media.isNotBlank() && open != null) {
            MediaThumb(file, media.loadThumbnail, onOpen = { open(location.mapsUrl) })
        }
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Pin,
                contentDescription = null,
                tint = ZillitTheme.colors.accentText,
                size = LOCATION_PIN,
            )
            Column {
                ZillitText(
                    text = headline,
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 2,
                )
                // Only when it adds something: a place picked with no name of
                // its own has the address as its label already.
                if (location.address.isNotBlank() && location.address != headline) {
                    ZillitText(
                        text = location.address,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 2,
                    )
                }
                ZillitText(
                    text = "${location.lat}, ${location.lng}",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        if (open != null) {
            ZillitText(
                text = "Open in Maps",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accentText,
                modifier = Modifier
                    .clip(ZillitTheme.shapes.small)
                    .clickable { open(location.mapsUrl) }
                    .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
            )
        }
    }
}

/**
 * A voice note: play/pause, the shared progress bar, and the clock — the
 * board's player, in the bubble's own colours. Formats the JVM cannot decode
 * fall back to the chip, whose click hands the file to the OS and its codecs.
 */
@Composable
private fun VoiceBubble(
    file: com.zillit.desktop.feature.chat.domain.ChatAttachment,
    media: BubbleMedia,
) {
    val player = media.player
    if (player == null) {
        FileChip(file, media.onOpen)
        return
    }
    var undecodable by androidx.compose.runtime.remember(file.media) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    if (undecodable) {
        FileChip(file, media.onOpen)
        return
    }

    val playback by player.state.collectAsState()
    val mine = playback?.takeIf { it.key == file.media }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Row(
        modifier = Modifier.widthIn(min = VOICE_MIN_WIDTH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        com.zillit.desktop.core.designsystem.component.ZillitIconButton(
            icon = if (mine?.isPlaying == true) ZillitIcons.Pause else ZillitIcons.Play,
            contentDescription = if (mine?.isPlaying == true) "Pause" else "Play",
            onClick = {
                scope.launch {
                    val bytes = media.loadAudio(file)
                    val undecoded = bytes == null ||
                        player.toggle(file.media, bytes) is
                        com.zillit.desktop.core.common.ZillitResult.Failure
                    if (undecoded) undecodable = true
                }
            },
        )
        com.zillit.desktop.core.designsystem.component.ZillitAudioProgress(
            progress = mine?.progress ?: 0f,
            positionMillis = mine?.positionMillis ?: 0,
            totalMillis = mine?.durationMillis?.takeIf { it > 0 } ?: file.durationMillis,
            onSeek = if (mine != null) {
                { fraction -> player.seek(file.media, fraction) }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** A message with a picture shows it, the board's convention; click opens it. */
@Composable
private fun MediaThumb(
    file: com.zillit.desktop.feature.chat.domain.ChatAttachment,
    loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) ->
    androidx.compose.ui.graphics.ImageBitmap?,
    onOpen: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit,
    playBadge: Boolean = false,
    /** The file's chip under the picture — a document's name and open verb. */
    namePlate: Boolean = false,
) {
    // Three states, not two: "still fetching" must keep the tile's size, and
    // only a definite miss falls back to the chip. Fetched-or-not as a
    // nullable bitmap conflated them, and the bubble grew from chip to
    // picture the moment the bytes landed — which, in a reversed list, is a
    // visible jump of everything above it, timed (S3 round trip) to land
    // right as a trackpad fling dies.
    val poster by androidx.compose.runtime.produceState<PosterState>(
        initialValue = PosterState.Loading,
        file.media,
    ) { value = PosterState.Done(loadThumbnail(file)) }

    val image = (poster as? PosterState.Done)?.image
    if (poster is PosterState.Done && image == null) {
        FileChip(file, onOpen)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        PosterTile(image, file, onOpen, playBadge)
        if (namePlate) FileChip(file, onOpen)
    }
}

private sealed interface PosterState {
    data object Loading : PosterState

    /** Fetched; a null [image] is a miss the chip stands in for. */
    data class Done(val image: androidx.compose.ui.graphics.ImageBitmap?) : PosterState
}

/**
 * The picture, at a size fixed before the bytes arrive.
 *
 * The frame comes from the row's own `width`/`height` when the wire carries
 * them (the phones send the picture's, and a poster's page), else a
 * document-shaped or picture-shaped default; the bitmap is cropped into it
 * rather than sizing it. So the layout is settled on the first frame and
 * the arrival of the image changes pixels, never positions.
 */
@Composable
private fun PosterTile(
    image: androidx.compose.ui.graphics.ImageBitmap?,
    file: com.zillit.desktop.feature.chat.domain.ChatAttachment,
    onOpen: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit,
    playBadge: Boolean,
) {
    val frame = posterFrame(file.widthPx, file.heightPx, portraitDefault = file.kind == "document")
    Box(
        modifier = Modifier
            .size(frame)
            .clip(ZillitTheme.shapes.small)
            .background(ZillitTheme.colors.surfaceSunken)
            .clickable { onOpen(file) },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = file.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (playBadge && image != null) {
            // The board's rule: the poster says what it is, the badge says
            // it moves.
            Box(
                modifier = Modifier
                    .size(PLAY_BADGE)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(ZillitTheme.colors.scrim),
                contentAlignment = Alignment.Center,
            ) {
                com.zillit.desktop.core.designsystem.component.ZillitIcon(
                    icon = com.zillit.desktop.core.designsystem.icon.ZillitIcons.Play,
                    contentDescription = "Video",
                    tint = androidx.compose.ui.graphics.Color.White,
                    size = PLAY_BADGE / 2,
                )
            }
        }
    }
}

/**
 * The tile a poster gets: the wire's aspect fitted into [THUMB_MAX], with
 * the short edge never under [THUMB_MIN] (a panorama still reads as a
 * picture, cropped); a page-shaped or picture-shaped default without one.
 */
internal fun posterFrame(
    widthPx: Long,
    heightPx: Long,
    portraitDefault: Boolean,
): androidx.compose.ui.unit.DpSize {
    if (widthPx <= 0 || heightPx <= 0) {
        return if (portraitDefault) {
            androidx.compose.ui.unit.DpSize(THUMB_MAX * PAGE_RATIO, THUMB_MAX)
        } else {
            androidx.compose.ui.unit.DpSize(THUMB_MAX, THUMB_MAX * PAGE_RATIO)
        }
    }
    val ratio = widthPx.toFloat() / heightPx.toFloat()
    val width = if (ratio >= 1f) THUMB_MAX else THUMB_MAX * ratio
    val height = if (ratio >= 1f) THUMB_MAX / ratio else THUMB_MAX
    return androidx.compose.ui.unit.DpSize(
        width = if (width < THUMB_MIN) THUMB_MIN else width,
        height = if (height < THUMB_MIN) THUMB_MIN else height,
    )
}

/**
 * When it was sent and, for our own messages, how far it got.
 *
 * Both sit inside the bubble on one quiet line — the convention every chat
 * client shares, and the reason a thread can be read without hunting for
 * metadata. A failure says so in words: a red tick is still a tick.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BubbleFooter(message: ChatMessage) {
    val now = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }

    Row(
        modifier = Modifier.align(Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        if (message.sendState == ChatSendState.Failed) {
            ZillitText(
                text = "Not sent",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
        if (message.isEdited) {
            ZillitText(
                text = "Edited",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = chatTimeLabel(message.timestampMillis, now),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        // Only our own messages carry a state: the other end's progress is
        // theirs to know, and a tick on their bubble would say nothing.
        if (message.isMine) {
            ZillitIcon(
                icon = message.sendState.icon(),
                contentDescription = message.sendState.describe(),
                tint = message.sendState.tint(),
                size = STATUS_ICON,
            )
        }
    }
}

private fun ChatSendState.icon() = when (this) {
    ChatSendState.Sending, ChatSendState.Queued -> ZillitIcons.Clock
    ChatSendState.Failed -> ZillitIcons.Info
    ChatSendState.Sent -> ZillitIcons.Tick
    ChatSendState.Delivered, ChatSendState.Read -> ZillitIcons.DoubleTick
}

@Composable
private fun ChatSendState.tint() = when (this) {
    // Read is the one state worth a colour — it is the answer to "did they
    // see it", and every other state is just progress toward that.
    ChatSendState.Read -> ZillitTheme.colors.success
    ChatSendState.Failed -> ZillitTheme.colors.danger
    else -> ZillitTheme.colors.textMuted
}

private fun ChatSendState.describe() = when (this) {
    ChatSendState.Sending -> "Sending"
    ChatSendState.Queued -> "Waiting to send — goes when you're back online"
    ChatSendState.Sent -> "Sent"
    ChatSendState.Delivered -> "Delivered"
    ChatSendState.Read -> "Read"
    ChatSendState.Failed -> "Not sent"
}

/** The file a message carries: name on a click target that fetches it. */
@Composable
private fun FileChip(
    file: com.zillit.desktop.feature.chat.domain.ChatAttachment,
    onOpen: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .clickable { onOpen(file) }
            .padding(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitFileBadge(fileName = file.name)
        ZillitText(
            text = file.name,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

/**
 * The two bubble tints.
 *
 * Light: the writer's own lines in the pale accent, the other side's on
 * white — the surface the canvas already frames. Dark: the canvas and the
 * surface are two near-blacks a shade apart, so the other side's lines
 * take the raised surface to stand off the page at all, and one's own take
 * the accent laid over it rather than over black, which read as mud.
 */
@Composable
private fun bubbleFill(mine: Boolean): androidx.compose.ui.graphics.Color {
    val colors = ZillitTheme.colors
    return when {
        !colors.isDark && mine -> colors.accentSoft
        !colors.isDark -> colors.surface
        mine -> colors.accent.copy(alpha = OWN_BUBBLE_DARK_ALPHA).compositeOver(colors.surfaceRaised)
        else -> colors.surfaceRaised
    }
}

private val HAIRLINE = 1.dp
private val BUBBLE_MAX_WIDTH = 420.dp
private val CALL_DISC = 34.dp
private const val OWN_BUBBLE_DARK_ALPHA = 0.22f

/** Lines from one writer this close together read as one turn of speech. */
internal const val RUN_GAP_MILLIS = 5 * 60 * 1000L
private val BUBBLE_RADIUS = 16.dp
private val BUBBLE_TAIL = 4.dp
private val HEADER_AVATAR = 40.dp
private val ONLINE_DOT = 8.dp
private val EMPTY_GLYPH = 28.dp
private val TYPING_DOT = 6.dp
private val TYPING_DOT_GAP = 4.dp
private const val TYPING_DOTS = 3
private const val TYPING_DOT_MILLIS = 500
private const val TYPING_DOT_STAGGER_MILLIS = 160
private const val TYPING_DOT_REST = 0.25f
private const val DAY_ROOM = 8
private val STATUS_ICON = 14.dp
private val ROW_AVATAR = 26.dp
private val THUMB_MAX = 240.dp
private val THUMB_MIN = 96.dp

/** A page-shaped tile's short edge as a share of its long one — near A4. */
private const val PAGE_RATIO = 0.72f
private val PLAY_BADGE = 40.dp
private val VOICE_MIN_WIDTH = 220.dp
private val REACT_BUTTON = 24.dp
private val REACT_MENU_WIDTH = 232.dp

/** The menu's gap under its anchor — the design system's own, restated for the aimed offsets. */
private val MENU_DROP = 4.dp

/** The composer's height, so a toast floats above it rather than on it. */
private val TOAST_CLEARANCE = 72.dp
private val UPLOAD_BAR_WIDTH = 220.dp
private val QUOTE_BAR = 3.dp
private val LOCATION_PIN = 16.dp
private const val PERCENT_FULL = 100f

// The shortlist every client leads with; the thread is not an emoji keyboard.
private val QUICK_REACTIONS = listOf(
    "\ud83d\udc4d", "\u2764\ufe0f", "\ud83d\ude02",
    "\ud83d\ude2e", "\ud83d\ude22", "\ud83d\ude4f",
)
// The board's composer metrics, matched exactly — see HomeFeedScreen.
private val COMPOSER_RADIUS = 22.dp
private val COMPOSER_MIN_HEIGHT = 44.dp
private val SEND_BUTTON = 40.dp
