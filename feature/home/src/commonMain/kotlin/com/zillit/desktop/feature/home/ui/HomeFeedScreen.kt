package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.zillitHorizontalScroll
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitTag
import androidx.compose.foundation.layout.ColumnScope
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.HomeUnitKind
import com.zillit.desktop.feature.home.domain.BoardRow
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.ReadReceipt
import com.zillit.desktop.feature.home.domain.toClockTime
import com.zillit.desktop.feature.home.domain.toDateTimeLabel
import com.zillit.desktop.feature.home.domain.MIN_QUERY_LENGTH
import com.zillit.desktop.feature.home.domain.completeMention
import com.zillit.desktop.feature.home.domain.mentionMatchedIndices
import com.zillit.desktop.feature.home.domain.mentionRangesIn
import com.zillit.desktop.feature.home.domain.parseGeoInput
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.AudioPlayer
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import com.zillit.desktop.feature.home.domain.PickedMedia
import com.zillit.desktop.feature.home.domain.formatFileSize
import com.zillit.desktop.core.designsystem.component.ZillitEmojiPicker
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Everything a bubble needs besides its own notice — one object instead of a
 * nine-parameter relay through board, card, thread and comment. Assembled once
 * per screen; the values inside are stable for its lifetime.
 */
private data class BoardUi(
    val onEvent: (HomeFeedEvent) -> Unit,
    val media: NoticeMediaSource?,
    val onPreview: (NoticeAttachment) -> Unit,
    val onOpen: (NoticeAttachment) -> Unit,
    val resolveAuthor: (String?) -> String?,
    val player: AudioPlayer?,
    val onOpenLocation: (GeoPoint) -> Unit,
    val highlightQuery: String?,
    val canReply: Boolean,
    val canModifyComment: (NoticeComment) -> Boolean,
    val canModifyNotice: (Notice) -> Boolean,
    /** Pin rides the edit route, so it takes the author's rights — untimed. */
    val canPin: (Notice) -> Boolean,
    /** The production's crew names, for mention highlights. */
    val crewNames: () -> List<String>,
    /** One crew member's profile picture, or null for the initials fallback. */
    val loadAvatar: suspend (String) -> ByteArray?,
    /** Upload percent for an in-flight post's local id — see [HomeFeedUiState.uploadProgress]. */
    val uploadProgress: (String) -> Int? = { null },
)

/**
 * Home: a tab per unit, and that unit's notice board beneath it.
 *
 * The web renders the same thing at `/home`, deciding per tab whether to show
 * the calendar or the board. Here the decision is [HomeUnitKind], resolved in
 * the domain rather than inline.
 */
@Composable
internal fun HomeFeedScreen(
    state: HomeFeedUiState,
    onEvent: (HomeFeedEvent) -> Unit,
    modifier: Modifier = Modifier,
    calendar: (@Composable () -> Unit)? = null,
    media: NoticeMediaSource? = null,
    /** Save to Downloads and hand to the OS — injected; the UI cannot do IO. */
    onOpenAttachment: (NoticeAttachment) -> Unit = {},
    /** Plays voice messages; null when the platform has no audio out. */
    player: AudioPlayer? = null,
    /** Opens a shared location in the browser's maps. Injected — no IO here. */
    onOpenLocation: (GeoPoint) -> Unit = {},
    /**
     * The sender's display line, from the production's crew list.
     *
     * The wire does not name senders — the web resolves `sender` against the
     * users list at render time, and so does this. Null falls back to whatever
     * the post itself carried.
     */
    resolveAuthor: (String?) -> String? = { null },
    /** A profile picture's bytes by user id; null shows initials instead. */
    loadAvatar: suspend (String) -> ByteArray? = { null },
    /** The production's crew names — the mention picker and highlights. */
    crewNames: () -> List<String> = { emptyList() },
    /** Unread count for one unit's tab; zero draws nothing. */
    unitBadge: (String) -> Int = { 0 },
) {
    // Which image is blown up, if any. Screen-level so the lightbox covers the
    // whole board, not one bubble.
    var lightbox by remember { mutableStateOf<NoticeAttachment?>(null) }
    // An OS drag is over the board; drives the drop overlay.
    var dropHover by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        UnitTabs(state, onEvent, unitBadge)

        if (state.searchQuery != null) {
            BoardSearchBar(state, onEvent)
        }

        // Call sheets are published documents, so "what went out and when" is a
        // question people actually ask. The web puts this behind a floating
        // button; a toggle in the header says what it does.
        if (state.selectedUnit?.kind == HomeUnitKind.CallSheet) {
            HistoryToggle(state.isHistory, onEvent)
        }

        // weight, NOT fillMaxSize: a fillMaxSize child of a Column consumes
        // every remaining pixel, which measured the composer below at zero
        // height. The bar was never gated away — it was crushed.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .externalFileDrop(
                    enabled = state.showsComposer,
                    onHover = { dropHover = it },
                    onFiles = { files -> droppedEvent(files)?.let(onEvent) },
                ),
        ) {
            BoardArea(
                state = state,
                onEvent = onEvent,
                calendar = calendar,
                media = media,
                onOpenAttachment = onOpenAttachment,
                resolveAuthor = resolveAuthor,
                player = player,
                onOpenLocation = onOpenLocation,
                onPreview = { lightbox = it },
                loadAvatar = loadAvatar,
                crewNames = crewNames,
            )
        }

        if (state.showsComposer) Composer(state, onEvent, crewNames)
    }

    if (dropHover) DropOverlay()

    lightbox?.let { attachment ->
        MediaLightbox(attachment = attachment, media = media, onClose = { lightbox = null })
    }

    // Overlays stay composed and drive the shell's visible flag — an `if`
    // would unmount them before the exit animation could play.
    ForwardPicker(state, onEvent)
    ReadByPanel(state.readBy, canNotify = state.canCompose, resolveAuthor, loadAvatar, onEvent)

    // Errors float, as the web's `message.error` does; the board stays put.
    if (state.notices.isNotEmpty()) {
        ZillitErrorToast(
            message = state.error,
            onDismiss = { onEvent(HomeFeedEvent.DismissError) },
        )
    }
    ZillitToast(
        message = state.info,
        onDismiss = { onEvent(HomeFeedEvent.DismissInfo) },
        tone = ZillitToastTone.Success,
    )
    }
}

/** The first dropped file as an attach event; the rest are counted, not lost. */
private fun droppedEvent(files: List<DroppedFile>): HomeFeedEvent? =
    files.firstOrNull()?.let { file ->
        HomeFeedEvent.AttachDropped(
            picked = PickedMedia(file.name, file.contentType, file.bytes),
            extra = files.size - 1,
        )
    }

/** "Drop to attach" — the whole board answers an OS drag. */
@Composable
private fun DropOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.accent.copy(alpha = DROP_SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .border(DROP_RING, ZillitTheme.colors.accent, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Add,
                tint = ZillitTheme.colors.accent,
                size = DROP_ICON,
            )
            ZillitText(
                text = "Drop to attach",
                style = ZillitTheme.typography.titleSmall,
                color = ZillitTheme.colors.textPrimary,
            )
            ZillitText(
                text = "The file posts to this board with your next message.",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * The forward destination picker — a unit list over a scrim, the desktop's
 * shape of the web's `ForwardMsgModal` and iOS's `ForwardOnUnitsViewController`.
 *
 * Every visible board unit is listed, the current one included (both live
 * clients allow forwarding back to the same board). Rights are not pre-filtered:
 * choosing a unit without posting access answers with the permission popup,
 * exactly as tapping one does on iOS.
 */
@Composable
private fun ForwardPicker(state: HomeFeedUiState, onEvent: (HomeFeedEvent) -> Unit) {
    val targets = state.tabs.filter { it.kind != HomeUnitKind.Calendar }

    ZillitDialogShell(
        title = "Forward to",
        subtitle = "A copy posts to the board you choose.",
        icon = ZillitIcons.Send,
        visible = state.forwarding != null,
        onDismiss = { onEvent(HomeFeedEvent.CancelForward) },
        width = FORWARD_PICKER_WIDTH,
    ) {
        targets.forEach { unit ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.small)
                    .clickable { onEvent(HomeFeedEvent.ForwardTo(unit.id)) }
                    .padding(
                        horizontal = ZillitTheme.spacing.sm,
                        vertical = ZillitTheme.spacing.xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitIcon(
                    icon = unit.kind.icon(),
                    contentDescription = null,
                    tint = ZillitTheme.colors.textMuted,
                    size = TAB_ICON,
                )
                ZillitText(
                    text = unit.label,
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (!unit.canPost && !state.isAdmin) {
                    ZillitText(
                        text = "no posting rights",
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

/**
 * Who has and hasn't read a post — the web's `ReadByUsersModal`, as a card
 * over the board: two tabs, a row per crew member, the read time on the read
 * side. Receipts live server-side only, so the panel opens loading.
 */
@Composable
private fun ReadByPanel(
    view: ReadByView?,
    canNotify: Boolean,
    resolveAuthor: (String?) -> String?,
    loadAvatar: suspend (String) -> ByteArray?,
    onEvent: (HomeFeedEvent) -> Unit,
) {
    // The last opened receipts survive the exit animation — the state goes
    // null the moment the panel dismisses, but its content must not blank
    // while fading out.
    val shown = remember { mutableStateOf<ReadByView?>(null) }
    if (view != null) shown.value = view
    val current = shown.value

    var showUnread by remember(current?.noticeId) { mutableStateOf(false) }

    ZillitDialogShell(
        title = "Read by",
        icon = ZillitIcons.Check,
        visible = view != null,
        onDismiss = { onEvent(HomeFeedEvent.DismissReadBy) },
        width = FORWARD_PICKER_WIDTH,
    ) {
        if (current != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ReadByTab("Read", current.lists?.read?.size, !showUnread) { showUnread = false }
                ReadByTab("Unread", current.lists?.unread?.size, showUnread) { showUnread = true }
            }

            ReadByContent(current.lists, showUnread, resolveAuthor, loadAvatar)

            // The web's notify button: unread tab, someone to reach, and the
            // rights to post here. Confirm-first, as its Popconfirm does —
            // this pushes a phone notification to everyone listed.
            val unread = current.lists?.unread.orEmpty()
            if (showUnread && unread.isNotEmpty() && canNotify) {
                NotifyUnreadFooter(unread.size, onEvent)
            }
        }
    }
}

/** The notify control: one click to arm, a second to actually send. */
@Composable
private fun NotifyUnreadFooter(count: Int, onEvent: (HomeFeedEvent) -> Unit) {
    var arming by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = if (arming) {
                "Send a notification to $count ${if (count == 1) "person" else "people"}?"
            } else {
                ""
            },
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        if (arming) {
            ZillitButton(
                text = "Cancel",
                onClick = { arming = false },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Send",
                onClick = {
                    arming = false
                    onEvent(HomeFeedEvent.NotifyUnread)
                },
                size = ButtonSize.Small,
            )
        } else {
            ZillitButton(
                text = "Notify",
                onClick = { arming = true },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** The panel's body: loading, an empty note, or the receipt list itself. */
@Composable
private fun ReadByContent(
    lists: ReadBy?,
    showUnread: Boolean,
    resolveAuthor: (String?) -> String?,
    loadAvatar: suspend (String) -> ByteArray?,
) {
    when {
        lists == null -> ZillitText(
            text = "Loading…",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        else -> {
            val rows = if (showUnread) lists.unread else lists.read
            if (rows.isEmpty()) {
                ZillitText(
                    text = if (showUnread) {
                        "Everyone with access has read this."
                    } else {
                        "No one has read this yet."
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            } else {
                val readByState = rememberLazyListState()
                LazyColumn(
                    state = readByState,
                    modifier = Modifier
                        .heightIn(max = READ_BY_LIST_HEIGHT)
                        .then(rememberWheelScroll(readByState)),
                ) {
                    items(rows, key = { it.userId }) { receipt ->
                        ReceiptRow(receipt, resolveAuthor, loadAvatar)
                    }
                }
            }
        }
    }
}

/** A count-carrying pill; the selected one wears the accent. */
@Composable
private fun ReadByTab(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(
                if (selected) ZillitTheme.colors.accentSoft else ZillitTheme.colors.canvas,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = if (selected) ZillitTheme.colors.accent else ZillitTheme.colors.textPrimary,
        )
        ZillitText(
            text = count?.toString() ?: "…",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** One crew member: avatar, name, designation, and when they read it. */
@Composable
private fun ReceiptRow(
    receipt: ReadReceipt,
    resolveAuthor: (String?) -> String?,
    loadAvatar: suspend (String) -> ByteArray?,
) {
    val resolved = resolveAuthor(receipt.userId)
    val name = receipt.userName?.takeIf { it.isNotBlank() }
        ?: resolved
        ?: "Unknown crew member"
    val role = receipt.designation?.takeIf { it.isNotBlank() }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitAvatar(name = name, image = rememberAvatarBitmap(receipt.userId, loadAvatar))
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = name,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textPrimary,
                )
                if (role != null) {
                    ZillitText(
                        text = role,
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
            if (receipt.readTimeMillis > 0) {
                ZillitText(
                    text = receipt.readTimeMillis.toDateTimeLabel(),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))
    }
}

/**
 * Fetch-and-decode for one avatar — null until it lands, and permanently for
 * crew without a picture, which [ZillitAvatar] answers with initials. The
 * fetch layer caches, so lists and boards do not refetch per row.
 */
@Composable
private fun rememberAvatarBitmap(
    userId: String?,
    load: suspend (String) -> ByteArray?,
): ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, userId) {
    value = userId?.let { load(it)?.let(::decodeImageBitmap) }
}.value

/**
 * Writing a notice.
 *
 * Enter sends, Shift+Enter breaks the line — the convention every chat client
 * on the desktop uses, and the board is used like one. The counter appears only
 * near the limit so it is a warning rather than decoration.
 */
@Composable
private fun Composer(
    state: HomeFeedUiState,
    onEvent: (HomeFeedEvent) -> Unit,
    crewNames: () -> List<String> = { emptyList() },
) {
    val colors = ZillitTheme.colors
    val draft = state.draft

    // The bar spans the window; what is *in* it lines up with the board's
    // column above. A full-width composer under a centred column reads as two
    // screens stacked, and the send button ends up nowhere near the posts it
    // is adding to.
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    // The theme's own surface under a hairline, not the web's fixed gray:
    // the bar follows light and dark, and the pill field plus one orange
    // send button carry the hierarchy.
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(colors.border))
    Column(
        modifier = Modifier
            .widthIn(max = BOARD_COLUMN_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        state.replyTo?.let { parent ->
            ReplyBar(parent, onCancel = { onEvent(HomeFeedEvent.CancelReply) })
        }

        if (state.editing != null) {
            EditBar(onCancel = { onEvent(HomeFeedEvent.CancelEditComment) })
        }

        draft.media?.let { picked ->
            AttachedChip(picked, onRemove = { onEvent(HomeFeedEvent.RemoveAttachment) })
        }

        if (draft.media == null && draft.location != null) {
            LocationChip(
                point = draft.location ?: return@Column,
                onRemove = { onEvent(HomeFeedEvent.RemoveAttachment) },
            )
        }

        val picker = remember { MentionPickerState() }
        picker.sync(draft.text, crewNames(), state.recentMentions)
        MentionPicker(state, onEvent, picker)

        if (state.recordingSeconds != null) {
            RecordingRow(state.recordingSeconds ?: 0, onEvent)
        } else {
            ComposerInput(state, onEvent, picker)
        }

        if (draft.showsCounter) {
            ZillitText(
                text = "${draft.remaining} characters left",
                style = ZillitTheme.typography.labelSmall,
                color = if (draft.isOverLimit) colors.danger else colors.textMuted,
            )
        }
    }
    }
}

/** The paperclip, the emoji palette, the field, and the send button — one row. */
@Composable
private fun ComposerInput(
    state: HomeFeedUiState,
    onEvent: (HomeFeedEvent) -> Unit,
    picker: MentionPickerState,
) {
    val draft = state.draft
    var emojiOpen by remember { mutableStateOf(false) }
    Row(
            // Centred on the field, not bottom-hung: with the pill at its
            // one-line height the icons and send sit on its midline.
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            // Replies are text on this endpoint; hiding the paperclip says so
            // more honestly than a click that does nothing.
            if (state.replyTo == null && state.editing == null) {
                MediaButtons(enabled = !state.isSending, onEvent = onEvent)
            }

            // Emoji work in every text mode — a reply deserves a 👍 as much as
            // a post does — so this sits outside the plain-posting guard.
            EmojiButton(
                open = emojiOpen,
                onOpenChange = { emojiOpen = it },
                enabled = !state.isSending,
                onPick = { emoji -> onEvent(HomeFeedEvent.DraftChanged(draft.text + emoji)) },
            )
            ZillitTextField(
                value = draft.text,
                onValueChange = { onEvent(HomeFeedEvent.DraftChanged(it)) },
                placeholder = when {
                    state.editing != null -> "Rewrite the reply…"
                    state.replyTo != null -> "Write a reply…"
                    draft.media != null -> "Add a caption…"
                    else -> "Type your message here..."
                },
                shape = RoundedCornerShape(COMPOSER_RADIUS),
                singleLine = false,
                enabled = !state.isSending,
                errorText = if (draft.isOverLimit) "Too long by ${-draft.remaining}" else null,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = COMPOSER_MIN_HEIGHT)
                    .onPreviewKeyEvent { event ->
                        handleMentionKey(event, picker) { name ->
                            onEvent(HomeFeedEvent.MentionPicked(name))
                            onEvent(HomeFeedEvent.DraftChanged(completeMention(draft.text, name)))
                        } || handleComposerKey(event, draft.canSend, onEvent)
                    },
            )
            // One filled control on the bar — the send. The mode it acts in
            // is already announced by the reply and edit bars above.
            ZillitIconButton(
                icon = ZillitIcons.Send,
                contentDescription = when {
                    state.editing != null -> "Save"
                    state.replyTo != null -> "Send the reply"
                    else -> "Post"
                },
                onClick = { onEvent(HomeFeedEvent.Send) },
                enabled = draft.canSend && !state.isSending,
                filled = true,
                size = SEND_BUTTON,
            )
        }

}

/**
 * What the composer is answering: author and a line of the post, plus escape.
 *
 * Without this bar a reply is indistinguishable from a post at the moment of
 * typing — and sending to the wrong one puts words inside a stranger's thread.
 */
@Composable
private fun ReplyBar(parent: Notice, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.accentSoft)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = "Replying to ${parent.authorName}",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accentText,
            )
            val snippet = parent.body.ifBlank { parent.attachment?.fileName.orEmpty() }
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

/** The paperclip and the mic — the web's orange pair, plain posting only. */
@Composable
private fun MediaButtons(enabled: Boolean, onEvent: (HomeFeedEvent) -> Unit) {
    ZillitIconButton(
        icon = ZillitIcons.Add,
        contentDescription = "Attach a file",
        onClick = { onEvent(HomeFeedEvent.Attach) },
        enabled = enabled,
    )
    ZillitIconButton(
        icon = ZillitIcons.Mic,
        contentDescription = "Record a voice message",
        onClick = { onEvent(HomeFeedEvent.StartRecording) },
        enabled = enabled,
    )
    LocationButton(enabled, onEvent)
}

/** The pin, and the picker it opens: paste coordinates or a Maps link. */
@Composable
private fun LocationButton(enabled: Boolean, onEvent: (HomeFeedEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val parsed = remember(input) { parseGeoInput(input) }

    Box {
        ZillitIconButton(
            icon = ZillitIcons.Pin,
            contentDescription = "Share a location",
            onClick = { open = true; input = "" },
            enabled = enabled,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier
                    .width(LOCATION_PICKER_WIDTH)
                    .padding(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "34.05, -118.24 — or paste a Maps link",
                    modifier = Modifier.fillMaxWidth(),
                    // Wrong before it is finished; only a filled field that
                    // still parses to nothing earns the correction.
                    errorText = if (input.isNotBlank() && parsed == null) {
                        "Coordinates like 34.05, -118.24 or a maps.google.com link."
                    } else {
                        null
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        ZillitTheme.spacing.sm,
                        Alignment.End,
                    ),
                ) {
                    ZillitButton(
                        text = "Cancel",
                        variant = ButtonVariant.Tertiary,
                        onClick = { open = false },
                    )
                    ZillitButton(
                        text = "Attach location",
                        variant = ButtonVariant.Primary,
                        enabled = parsed != null,
                        onClick = {
                            parsed?.let { onEvent(HomeFeedEvent.AttachLocation(it)) }
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** The smiley and the palette it opens. */
@Composable
private fun EmojiButton(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Box {
        ZillitIconButton(
            icon = ZillitIcons.Smiley,
            contentDescription = "Insert an emoji",
            onClick = { onOpenChange(true) },
            enabled = enabled,
        )
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            ZillitEmojiPicker(
                // Appended rather than inserted at the cursor: the field's API
                // is a plain String, and rebuilding it on TextFieldValue for
                // cursor maths is its own change. The dominant flow — type,
                // emoji, send — lands the same either way.
                onPick = onPick,
                modifier = Modifier.padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

/** The shared recording bar; the board and the chat swap in the same one. */
@Composable
private fun RecordingRow(seconds: Int, onEvent: (HomeFeedEvent) -> Unit) {
    com.zillit.desktop.core.designsystem.component.ZillitRecordingBar(
        seconds = seconds,
        onCancel = { onEvent(HomeFeedEvent.CancelRecording) },
        onStop = { onEvent(HomeFeedEvent.StopRecording) },
    )
}

/** The composer is rewriting a reply; the escape hatch restores the old draft. */
@Composable
private fun EditBar(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.accentSoft)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = "Editing your reply",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.accentText,
            modifier = Modifier.weight(1f),
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Cancel the edit",
            onClick = onCancel,
        )
    }
}

/** A location waiting to be posted, before (or without) its map image. */
@Composable
private fun LocationChip(point: GeoPoint, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = "📍 ${point.lat}, ${point.long}",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textPrimary,
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Remove the location",
            onClick = onRemove,
        )
    }
}

/**
 * The file waiting to be posted: name, size, and a way to change your mind.
 *
 * Shown above the input rather than inside it — the web previews picked files
 * in a modal, but a chip keeps the file visible while the caption is typed.
 */
@Composable
private fun AttachedChip(picked: PickedMedia, onRemove: () -> Unit) {
    // Decoded once per pick, not per recomposition — these are the user's own
    // bytes, already in memory, so there is nothing to fetch. Videos show the
    // poster frame extracted at pick time, when the codec allowed one.
    val thumbnail = remember(picked, picked.thumbnailBytes) {
        when {
            picked.kind == NoticeKind.Image -> decodeImageBitmap(picked.bytes)
            picked.thumbnailBytes != null -> decodeImageBitmap(picked.thumbnailBytes)
            else -> null
        }
    }

    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = picked.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(CHIP_THUMBNAIL)
                    .clip(ZillitTheme.shapes.small),
            )
        } else {
            // The same glyph and colour the posted chip will carry, so what
            // you attach looks like what you sent.
            val kind = fileKindOf(picked.name)
            ZillitIcon(
                icon = kind.icon,
                contentDescription = kind.label,
                tint = kind.hue.colour(),
                size = PICKED_CHIP_ICON,
            )
        }
        ZillitText(
            text = picked.name,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        ZillitText(
            text = formatFileSize(picked.bytes.size.toLong()),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Remove attachment",
            onClick = onRemove,
        )
    }
}

/**
 * Enter sends; Shift+Enter is a newline.
 *
 * Handled on **key-down only**. Acting on both down and up sends twice, which
 * on a board everyone reads is a visible bug rather than a quirk.
 */
/**
 * The picker's keys, tried before the send handler while suggestions show.
 *
 * Enter and Tab complete the lit row instead of sending — one more Enter
 * sends, the same bargain every desktop chat's mention popup strikes. Escape
 * hides the list for this token and gives Enter straight back to send.
 */
private fun handleMentionKey(
    event: KeyEvent,
    picker: MentionPickerState,
    onPick: (String) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || !picker.isOpen) return false
    return when (event.key) {
        Key.DirectionDown -> {
            picker.moveDown()
            true
        }
        Key.DirectionUp -> {
            picker.moveUp()
            true
        }
        Key.Enter, Key.Tab -> when {
            event.isShiftPressed -> false
            else -> {
                picker.selectedName()?.let(onPick)
                true
            }
        }
        Key.Escape -> {
            picker.dismiss()
            true
        }
        else -> false
    }
}

private fun handleComposerKey(
    event: KeyEvent,
    canSend: Boolean,
    onEvent: (HomeFeedEvent) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key != Key.Enter || event.isShiftPressed) return false
    if (canSend) onEvent(HomeFeedEvent.Send)
    // Consumed either way: a blank Enter must not insert a newline the user did
    // not ask for by pressing what they think is "send".
    return true
}

@Composable
private fun HistoryToggle(isHistory: Boolean, onEvent: (HomeFeedEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = if (isHistory) "Showing published history" else "Call sheet",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = if (isHistory) "Back to board" else "History",
            style = ZillitTheme.typography.button,
            color = ZillitTheme.colors.accent,
            modifier = Modifier.clickable { onEvent(HomeFeedEvent.ShowHistory(!isHistory)) },
        )
    }
}

@Composable
private fun UnitTabs(
    state: HomeFeedUiState,
    onEvent: (HomeFeedEvent) -> Unit,
    unitBadge: (String) -> Int = { 0 },
) {
    val colors = ZillitTheme.colors
    val selected = state.selectedUnit

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Row(
        modifier = Modifier
            .weight(1f)
            .zillitHorizontalScroll(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        state.tabs.forEach { unit ->
            UnitTab(
                unit = unit,
                isActive = unit.id == selected?.id,
                unread = unitBadge(unit.id),
                onClick = { onEvent(HomeFeedEvent.SelectUnit(unit.id)) },
            )
        }
    }

    // In the tab strip, not the composer: find works in history and on
    // read-only boards too, where the composer has other rules.
    if (selected != null && selected.kind != HomeUnitKind.Calendar) {
        ZillitIconButton(
            icon = ZillitIcons.Search,
            contentDescription = "Search this board",
            onClick = {
                onEvent(
                    if (state.searchQuery == null) {
                        HomeFeedEvent.OpenSearch
                    } else {
                        HomeFeedEvent.CloseSearch
                    },
                )
            },
        )
    }
    }
}

/** One unit's tab: kind glyph, name, and — when it has any — its unread. */
@Composable
private fun UnitTab(unit: HomeUnit, isActive: Boolean, unread: Int, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (isActive) colors.accentSoft else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        // The kind is worth showing: a tab called "Main Unit" gives no
        // clue whether it holds a board or a calendar.
        ZillitIcon(
            icon = unit.kind.icon(),
            contentDescription = null,
            tint = if (isActive) colors.accentText else colors.textMuted,
            size = TAB_ICON,
        )
        ZillitText(
            text = unit.label,
            style = ZillitTheme.typography.label,
            color = if (isActive) colors.accentText else colors.textSecondary,
            maxLines = 1,
        )
        // The web badges each unit tab the same way; an open tab's count
        // clears the moment its board reports itself read.
        if (unread > 0) {
            com.zillit.desktop.core.designsystem.component.ZillitBadge(count = unread)
        }
    }
}

/**
 * The find bar: needle, tally, the two arrows, and the way out.
 *
 * "2 of 12" mirrors iOS's `searchResultLabel`; the arrows wrap, because a find
 * bar that dead-ends at the last match makes the user close and reopen it.
 */
@Composable
private fun BoardSearchBar(state: HomeFeedUiState, onEvent: (HomeFeedEvent) -> Unit) {
    val matches = state.searchMatches

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = state.searchQuery.orEmpty(),
            onValueChange = { onEvent(HomeFeedEvent.SearchChanged(it)) },
            placeholder = "Search this board…",
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = when {
                state.searchQuery.orEmpty().trim().length < MIN_QUERY_LENGTH -> ""
                matches.isEmpty() -> "No matches"
                else -> "${state.searchIndex + 1} of ${matches.size}"
            },
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = "Previous match",
            onClick = { onEvent(HomeFeedEvent.StepSearch(forward = false)) },
            enabled = matches.isNotEmpty(),
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronRight,
            contentDescription = "Next match",
            onClick = { onEvent(HomeFeedEvent.StepSearch(forward = true)) },
            enabled = matches.isNotEmpty(),
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close search",
            onClick = { onEvent(HomeFeedEvent.CloseSearch) },
        )
    }
}

@Composable
private fun NoticeBoard(
    rows: List<BoardRow>,
    unit: HomeUnit,
    ui: BoardUi,
    currentMatch: String?,
) {
    val listState = rememberLazyListState()

    // Opens on the newest post, and follows arrivals only for a reader
    // already at the bottom — the messaging-list convention.
    com.zillit.desktop.core.designsystem.component.FollowLatest(listState, rows.size, contentKey = unit.id)

    // The find bar's pointer moved: bring that post to the top, as iOS's
    // `scrollToRow(.top)` does.
    LaunchedEffect(currentMatch) {
        val index = rows.indexOfFirst { it is BoardRow.Post && it.notice.id == currentMatch }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().then(rememberWheelScroll(listState)),
        contentPadding = PaddingValues(PAGE_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(rows, key = { row -> rowKey(row) }) { row ->
            when (row) {
                is BoardRow.Separator -> DateSeparator(row.label)

                // One centred column, every card the same width.
                //
                // They used to hang right at a chat-bubble's width, each card
                // sized to its own content. That reads well when the side
                // carries meaning — as it does in a two-person chat, where
                // left and right are the two people. A board is one column of
                // posts from everybody, so the side said nothing, and cards of
                // eight different widths down a ragged edge is what it cost.
                //
                // Sender identity reads from the header line, as it did before.
                is BoardRow.Post -> Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    NoticeCard(row.notice, ui)
                }
            }
        }

        if (!unit.canPost) {
            item {
                ZillitText(
                    text = "You do not have posting rights for ${unit.label}. " +
                        "Ask a production admin to grant them.",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: Notice, ui: BoardUi) {
    val canModifyNotice = ui.canModifyNotice(notice)
    val colors = ZillitTheme.colors
    val pending = notice.sendState != NoticeSendState.Sent
    val failed = notice.sendState == NoticeSendState.Failed
    val ringColor = when {
        failed -> colors.danger
        notice.isPinned -> colors.accent
        else -> Color.Transparent
    }
    val ringWidth = if (notice.isPinned && !failed) PINNED_RING else HAIRLINE
    var confirmingDelete by remember(notice.id) { mutableStateOf(false) }
    // Rounded on all four corners. The web's bubble squares off one corner as
    // a tail pointing at whichever edge it hangs from; centred, there is no
    // edge to point at, and a single square corner reads as a rendering fault.
    val bubbleShape = RoundedCornerShape(BUBBLE_RADIUS)

    // The web puts these behind a kebab dropdown; the desktop convention for
    // "actions on this thing" is the right button, so that is where they live.
    NoticeContextMenu(
        items = noticeMenuItems(notice, ui.canReply, canModifyNotice, ui.canPin(notice), ui.onEvent) {
            confirmingDelete = true
        },
    ) {
    Column(
        modifier = Modifier
            // Capped first, then filled: `widthIn` narrows the incoming
            // constraint and `fillMaxWidth` takes all of what is left, so every
            // card is exactly the column width and their edges line up.
            .widthIn(min = BUBBLE_MIN_WIDTH, max = BUBBLE_MAX_WIDTH)
            .fillMaxWidth()
            .shadow(BUBBLE_ELEVATION, bubbleShape)
            .clip(bubbleShape)
            .background(colors.noticeBubble)
            // Pinned wears an accent ring, not an accent fill. A filled card
            // was legible at a chat bubble's width; across the board's full
            // column it is a slab of orange that pulls the eye off everything
            // posted since. The board already gathers pinned posts under their
            // own heading, so a card here has to be identifiable, not loud.
            //
            // A post that failed to send outranks it: "this never went out" is
            // the more urgent thing to notice, and two rings would be one ring
            // too many to read at a glance.
            .border(width = ringWidth, color = ringColor, shape = bubbleShape)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        NoticeHeader(notice, ui)

        NoticeAttachment(notice.kind, notice.attachment, notice.location, ui)

        if (notice.body.isNotBlank()) {
            ZillitText(
                text = highlighted(notice.body, ui.highlightQuery, ui.crewNames),
                style = ZillitTheme.typography.bodyMedium,
                color = if (pending) ZillitTheme.colors.textMuted else ZillitTheme.colors.textPrimary,
            )
        }

        NoticeFooter(notice)

        if (confirmingDelete) {
            DeleteConfirmRow(
                prompt = "Delete this post?",
                onConfirm = {
                    confirmingDelete = false
                    ui.onEvent(HomeFeedEvent.DeleteNotice(notice.id))
                },
                onDismiss = { confirmingDelete = false },
            )
        }

        NoticeThread(notice, ui)
    }
    }
}

/** One bubble's attachment, with the shared context unpacked once. */
@Composable
private fun NoticeAttachment(
    kind: NoticeKind,
    attachment: NoticeAttachment?,
    location: GeoPoint?,
    ui: BoardUi,
) {
    AttachmentContent(
        kind = kind,
        attachment = attachment,
        media = ui.media,
        onPreview = ui.onPreview,
        onOpen = ui.onOpen,
        player = ui.player,
        location = location,
        onOpenLocation = ui.onOpenLocation,
    )
}

/** The right-click menu on a post: Reply, and Edit / Delete where allowed. */
private fun noticeMenuItems(
    notice: Notice,
    canReply: Boolean,
    canModifyNotice: Boolean,
    canPin: Boolean,
    onEvent: (HomeFeedEvent) -> Unit,
    onArmDelete: () -> Unit,
): () -> List<NoticeMenuItem> = {
    buildList {
        if (canReply && notice.sendState == NoticeSendState.Sent) {
            add(NoticeMenuItem("Reply") { onEvent(HomeFeedEvent.StartReply(notice.id)) })
        }
        // Anyone may forward any sent post — rights are the *target* unit's
        // business, checked when one is chosen, as on both live clients.
        if (notice.sendState == NoticeSendState.Sent) {
            add(NoticeMenuItem("Forward…") { onEvent(HomeFeedEvent.StartForward(notice.id)) })
            add(NoticeMenuItem("Read by…") { onEvent(HomeFeedEvent.ShowReadBy(notice.id)) })
            // The server lets only the author (or an admin) edit, and the pin
            // flag rides the edit route — offering it elsewhere earns a
            // refusal (found live: "You do not have access to this").
            if (canPin) {
                add(
                    NoticeMenuItem(if (notice.isPinned) "Unpin" else "Pin") {
                        onEvent(HomeFeedEvent.TogglePin(notice.id))
                    },
                )
            }
        }
        if (canModifyNotice) {
            // Editing rewrites the text; a media post's caption is its text.
            add(NoticeMenuItem("Edit") { onEvent(HomeFeedEvent.StartEditNotice(notice.id)) })
            add(NoticeMenuItem("Delete") { onArmDelete() })
        }
    }
}

/**
 * The crew list over the composer while an `@name` is being typed. Picking
 * completes the trailing token — plain text on the wire, readable on every
 * client; see `Mentions.kt` for why there is no id token.
 */
@Composable
private fun MentionPicker(
    state: HomeFeedUiState,
    onEvent: (HomeFeedEvent) -> Unit,
    picker: MentionPickerState,
) {
    if (!picker.isOpen) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.canvas)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.xxs),
    ) {
        picker.matches.forEachIndexed { index, name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.small)
                    .then(
                        if (index == picker.selected) {
                            Modifier.background(ZillitTheme.colors.surfaceHover)
                        } else {
                            Modifier
                        },
                    )
                    // Not focusable: a click that stole the keyboard from the
                    // field would make "pick then keep typing" a two-step —
                    // found live on the first self-test.
                    .focusProperties { canFocus = false }
                    .clickable {
                        onEvent(HomeFeedEvent.MentionPicked(name))
                        onEvent(
                            HomeFeedEvent.DraftChanged(
                                completeMention(state.draft.text, name),
                            ),
                        )
                    }
                    .padding(
                        horizontal = ZillitTheme.spacing.sm,
                        vertical = ZillitTheme.spacing.xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitAvatar(name = name, size = MENTION_AVATAR)
                ZillitText(
                    text = matchedName(name, picker.query.orEmpty()),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textPrimary,
                )
            }
        }
    }
}

/**
 * A suggestion with the letters that answered the query lit — the same accent
 * and weight a finished mention gets in a bubble, so the picker explains the
 * fuzzy rank: `vp` lights two initials, `vdya` its scattered path.
 */
@Composable
private fun matchedName(name: String, query: String): AnnotatedString {
    val indices = mentionMatchedIndices(query, name)
    if (indices.isEmpty()) return AnnotatedString(name)

    val mark = SpanStyle(color = ZillitTheme.colors.accent, fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        append(name)
        indices.forEach { addStyle(mark, it, it + 1) }
    }
}

/**
 * The body with search hits and crew mentions marked — the web's
 * `HighlightText`, plus this client's mention accents. Nothing to mark, and
 * the string passes through untouched.
 */
@Composable
private fun highlighted(
    text: String,
    query: String?,
    crewNames: () -> List<String> = { emptyList() },
): AnnotatedString {
    val mentions = mentionRangesIn(text, crewNames())
    if (query.isNullOrBlank() && mentions.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        append(text)
        mentions.forEach { range ->
            addStyle(
                SpanStyle(color = ZillitTheme.colors.accent, fontWeight = FontWeight.SemiBold),
                range.first,
                (range.last + 1).coerceAtMost(text.length),
            )
        }
        if (!query.isNullOrBlank()) {
            var from = 0
            while (from < text.length) {
                val hit = text.indexOf(query, startIndex = from, ignoreCase = true)
                if (hit < 0) break
                addStyle(
                    SpanStyle(background = HIGHLIGHT, color = Color.Black),
                    hit,
                    hit + query.length,
                )
                from = hit + query.length
            }
        }
    }
}

/** "Delete? Yes / No", in place — a modal would cover the thing being deleted. */
@Composable
private fun DeleteConfirmRow(prompt: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitText(
            text = prompt,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = "Yes",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.danger,
            modifier = Modifier.clickable(onClick = onConfirm),
        )
        ZillitText(
            text = "No",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.clickable(onClick = onDismiss),
        )
    }
}

/** Everything under the post: its replies, and the Reply / Try again actions. */
@Composable
private fun NoticeThread(notice: Notice, ui: BoardUi) {
    // Replies live inside the parent's bubble, as on the web — a thread is
    // read in the context of what it answers.
    notice.comments.forEach { comment ->
        CommentBubble(parentId = notice.id, comment = comment, ui = ui)
    }

    if (notice.sendState == NoticeSendState.Sent && ui.canReply) {
        ZillitText(
            text = "Reply",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.accentText,
            modifier = Modifier.clickable { ui.onEvent(HomeFeedEvent.StartReply(notice.id)) },
        )
    }

    if (notice.sendState == NoticeSendState.Failed) {
        ZillitText(
            text = "Try again",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.danger,
            modifier = Modifier.clickable {
                notice.localId?.let { ui.onEvent(HomeFeedEvent.Retry(it)) }
            },
        )
    }
}

/**
 * The day divider between runs of posts.
 *
 * A centred pill rather than a full-width rule: on a tinted board a rule reads
 * as another card edge.
 */
@Composable
private fun DateSeparator(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xxs),
        )
    }
}

private fun rowKey(row: BoardRow): String = when (row) {
    is BoardRow.Separator -> "sep-${row.label}"
    is BoardRow.Post -> row.notice.id
}

/**
 * What fills the space between the tabs and the composer: a loading line, an
 * explanation, the calendar, or the board itself.
 */
@Composable
private fun BoardArea(
    state: HomeFeedUiState,
    onEvent: (HomeFeedEvent) -> Unit,
    calendar: (@Composable () -> Unit)?,
    media: NoticeMediaSource?,
    onOpenAttachment: (NoticeAttachment) -> Unit,
    resolveAuthor: (String?) -> String?,
    player: AudioPlayer?,
    onOpenLocation: (GeoPoint) -> Unit,
    onPreview: (NoticeAttachment) -> Unit,
    loadAvatar: suspend (String) -> ByteArray?,
    crewNames: () -> List<String>,
) {
    val unit = state.selectedUnit
    when {
        state.isLoadingUnits -> Centred("Loading…")

        // Full-screen only when there is nothing else to show — an action
        // error over a loaded board is the popup's job, and replacing the
        // board with it was how a failed delete used to blank the feed.
        state.error != null && state.notices.isEmpty() -> Centred(state.error)

        unit == null -> Centred(
            "No units are shared with you in this production yet.",
        )

        // The calendar unit renders the real calendar when the host supplies
        // one; the placeholder is only for a graph that could not build it.
        unit.kind == HomeUnitKind.Calendar ->
            calendar?.invoke() ?: CalendarPlaceholder(unit)

        state.isLoadingNotices && state.notices.isEmpty() -> Centred("Loading notices…")

        state.notices.isEmpty() -> Centred("Nothing has been posted to ${unit.label} yet.")

        else -> NoticeBoard(
            rows = state.rows,
            unit = unit,
            ui = BoardUi(
                onEvent = onEvent,
                media = media,
                onPreview = onPreview,
                onOpen = onOpenAttachment,
                resolveAuthor = resolveAuthor,
                player = player,
                onOpenLocation = onOpenLocation,
                highlightQuery = state.searchQuery
                    ?.trim()?.takeIf { it.length >= MIN_QUERY_LENGTH },
                canReply = state.canCompose,
                canModifyComment = state::canModify,
                canModifyNotice = state::canModify,
                canPin = { notice ->
                    state.isAdmin ||
                        (notice.authorId != null && notice.authorId == state.currentUserId)
                },
                crewNames = crewNames,
                loadAvatar = loadAvatar,
                uploadProgress = { localId -> state.uploadProgress[localId] },
            ),
            currentMatch = state.currentSearchMatch,
        )
    }
}

/** The bubble's first line: who posted, and what qualifies the post. */
@Composable
private fun NoticeHeader(notice: Notice, ui: BoardUi) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        notice.authorId?.let { author ->
            ZillitAvatar(
                name = ui.resolveAuthor(author) ?: notice.authorName,
                size = HEADER_AVATAR,
                image = rememberAvatarBitmap(author, ui.loadAvatar),
            )
        }
        ZillitText(
            text = ui.resolveAuthor(notice.authorId) ?: notice.authorName,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.accentText,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (notice.isPinned) {
            ZillitIcon(
                icon = ZillitIcons.Pin,
                contentDescription = "Pinned",
                // Accent, matching the ring the card usually wears — and still
                // accent on a post that failed to send, where the ring has
                // turned red. The glyph says "pinned"; the ring says "look at
                // this one", and on that card those are two different facts.
                tint = ZillitTheme.colors.accent,
                size = PIN_GLYPH,
            )
        }
        when (notice.sendState) {
            // The tag narrates the send: percent while the file's bytes move,
            // "Processing" while the server writes the post, plain "Sending"
            // for a post with nothing to upload.
            NoticeSendState.Sending -> {
                val percent = notice.localId?.let(ui.uploadProgress)
                ZillitTag(
                    when {
                        percent == null -> "Sending"
                        percent >= UPLOAD_DONE_PERCENT -> "Processing…"
                        else -> "Uploading $percent%"
                    },
                    tone = TagTone.Neutral,
                )
            }
            NoticeSendState.Failed -> ZillitTag("Not sent", tone = TagTone.Danger)
            NoticeSendState.Sent -> Unit
        }
    }
}

/**
 * One reply, nested inside its parent's bubble.
 *
 * A darker inset rather than its own free-standing bubble: the web renders
 * replies inside the parent's container, and pulling them out would detach an
 * answer from what it answers.
 */
@Composable
private fun CommentBubble(parentId: String, comment: NoticeComment, ui: BoardUi) {
    val canModify = ui.canModifyComment(comment)
    var confirmingDelete by remember(comment.id) { mutableStateOf(false) }

    NoticeContextMenu(
        items = commentMenuItems(parentId, comment, canModify, ui.onEvent) {
            confirmingDelete = true
        },
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            // A wash of the page's own ink, so a reply is recessed into the
            // card in either theme. A flat black at 25% did that on the dark
            // bubble and turned into a grey slab once the card went white.
            .background(ZillitTheme.colors.textPrimary.copy(alpha = COMMENT_INSET_ALPHA))
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        CommentHeader(comment, ui)

        NoticeAttachment(comment.kind, comment.attachment, comment.location, ui)

        if (comment.body.isNotBlank()) {
            ZillitText(
                text = highlighted(comment.body, ui.highlightQuery, ui.crewNames),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textPrimary,
            )
        }

        if (comment.isEdited) {
            ZillitText(
                text = "Edited",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }

        if (canModify) {
            CommentActions(
                parentId = parentId,
                comment = comment,
                confirmingDelete = confirmingDelete,
                onConfirmingChange = { confirmingDelete = it },
                onEvent = ui.onEvent,
            )
        }
    }
    }
}

/** The right-click menu on a reply: Edit / Delete where allowed. */
private fun commentMenuItems(
    parentId: String,
    comment: NoticeComment,
    canModify: Boolean,
    onEvent: (HomeFeedEvent) -> Unit,
    onArmDelete: () -> Unit,
): () -> List<NoticeMenuItem> = {
    buildList {
        if (canModify) {
            if (comment.kind == NoticeKind.Text) {
                add(
                    NoticeMenuItem("Edit reply") {
                        onEvent(HomeFeedEvent.StartEditComment(parentId, comment.id))
                    },
                )
            }
            add(NoticeMenuItem("Delete reply") { onArmDelete() })
        }
    }
}

/** The reply's first line: who wrote it, and when. */
@Composable
private fun CommentHeader(comment: NoticeComment, ui: BoardUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ZillitText(
            text = ui.resolveAuthor(comment.authorId) ?: "Unknown",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.accentText,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        ZillitText(
            text = comment.createdAtMillis.toClockTime(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * Edit and Delete, with the delete confirmed in place.
 *
 * An inline "Delete? Yes / No" rather than a dialog: the question is small,
 * the target is right there, and a modal would cover the thing being deleted.
 */
@Composable
private fun CommentActions(
    parentId: String,
    comment: NoticeComment,
    confirmingDelete: Boolean,
    onConfirmingChange: (Boolean) -> Unit,
    onEvent: (HomeFeedEvent) -> Unit,
) {

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        if (confirmingDelete) {
            DeleteConfirmRow(
                prompt = "Delete?",
                onConfirm = {
                    onConfirmingChange(false)
                    onEvent(HomeFeedEvent.DeleteComment(parentId, comment.id))
                },
                onDismiss = { onConfirmingChange(false) },
            )
        } else {
            // Only text replies are rewritable — there is no editing a photo.
            if (comment.kind == NoticeKind.Text) {
                ZillitText(
                    text = "Edit",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.accentText,
                    modifier = Modifier.clickable {
                        onEvent(HomeFeedEvent.StartEditComment(parentId, comment.id))
                    },
                )
            }
            ZillitText(
                text = "Delete",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.clickable { onConfirmingChange(true) },
            )
        }
    }
}

/**
 * The bubble's bottom line: file size on the left, time on the right —
 * the web's footer, field for field. "Edited" sits with the size, in red,
 * as it does there.
 */
@Composable
private fun NoticeFooter(notice: Notice) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            val size = notice.attachment?.sizeBytes?.let(::formatFileSize).orEmpty()
            if (size.isNotEmpty() && notice.kind != NoticeKind.Text) {
                ZillitText(
                    text = size,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            if (notice.isEdited) {
                ZillitText(
                    text = "Edited",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.danger,
                )
            }
        }
        ZillitText(
            text = notice.createdAtMillis.toClockTime(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * The calendar tab.
 *
 * Named honestly rather than left blank: the unit exists and the tab is
 * correct, but the calendar itself is M7. A blank pane would read as a bug.
 */
@Composable
private fun CalendarPlaceholder(unit: HomeUnit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Calendar,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = EMPTY_ICON,
            )
            ZillitText(
                text = "${unit.label} arrives with the calendar module.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

private fun HomeUnitKind.icon() = when (this) {
    HomeUnitKind.Calendar -> ZillitIcons.Calendar
    HomeUnitKind.CallSheet -> ZillitIcons.Tools
    HomeUnitKind.Notices -> ZillitIcons.Chat
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MESSAGE_WIDTH).padding(PAGE_PADDING),
        )
    }
}

// The find highlight: amber, matching the web's yellow mark on dark bubbles.
private val HIGHLIGHT = Color(0xFFFFC94D)
private const val COMMENT_INSET_ALPHA = 0.07f
private val LOCATION_PICKER_WIDTH = 340.dp
private val FORWARD_PICKER_WIDTH = 340.dp
private val READ_BY_LIST_HEIGHT = 320.dp
private val COMPOSER_RADIUS = 22.dp
private val HEADER_AVATAR = 26.dp
private val SEND_BUTTON = 40.dp
private val MENTION_AVATAR = 24.dp
private val BUBBLE_ELEVATION = 2.dp
private val DROP_RING = 2.dp
private val DROP_ICON = 32.dp
private const val DROP_SCRIM_ALPHA = 0.12f
private val CHIP_THUMBNAIL = 40.dp
private val PICKED_CHIP_ICON = 14.dp
/**
 * The board's reading measure.
 *
 * 340dp was a chat bubble's width, and a notice is not a chat message: these
 * carry call sheets, schedules and attachments, and at that width a two-line
 * sentence wrapped four times. 660 is the wide end of a comfortable measure —
 * past roughly 75 characters a line the eye loses its place returning to the
 * left margin, which is the same reason this is a centred column rather than
 * a card stretched across a 27-inch display.
 */
private val BUBBLE_MAX_WIDTH = 660.dp

/** Holds the shape of a two-word post, and stops a narrow window collapsing it. */
private val BUBBLE_MIN_WIDTH = 280.dp

private val BUBBLE_RADIUS = 16.dp

/** Thicker than a hairline so the ring reads as deliberate, not as a seam. */
private val PINNED_RING = 2.dp
private val PIN_GLYPH = 13.dp


private val PAGE_PADDING = 20.dp
/**
 * The board's column, composer included.
 *
 * Derived rather than written twice: the composer's contents have to sit on the
 * same left and right edges as the cards above them, and two constants that
 * mean "the column" is one edit away from them disagreeing.
 */
private val BOARD_COLUMN_WIDTH = BUBBLE_MAX_WIDTH + PAGE_PADDING * 2
private val MESSAGE_WIDTH = 420.dp
private val TAB_ICON = 14.dp
private val EMPTY_ICON = 32.dp
private val HAIRLINE = 1.dp
private val COMPOSER_MIN_HEIGHT = 44.dp

/** Matches the view model's UPLOAD_DONE: bytes done, server writing. */
private const val UPLOAD_DONE_PERCENT = 100
