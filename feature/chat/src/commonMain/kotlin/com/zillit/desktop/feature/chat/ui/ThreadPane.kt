package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.animation.core.animateFloat
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.domain.chatDayLabel
import com.zillit.desktop.feature.chat.domain.chatTimeLabel
import kotlinx.datetime.toLocalDateTime

/** One open conversation: header, the bubbles, and the composer. */
@Composable
@Suppress("LongParameterList")
internal fun ThreadPane(
    state: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    resolveName: (String) -> String? = { null },
    onOpenAttachment: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit = {},
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
    loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) ->
    androidx.compose.ui.graphics.ImageBitmap? = { null },
    /** Rings the open thread. Null hides the call buttons entirely. */
    onCall: ((video: Boolean) -> Unit)? = null,
    /** The one shared speaker; null renders voice notes as plain chips. */
    player: com.zillit.desktop.core.designsystem.component.AudioPlayer? = null,
    /** Fetches a voice note's bytes for decoding. Null disables playback. */
    loadAudio: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray? =
        { null },
) {
    val peer = state.peer ?: return

    Column(Modifier.fillMaxSize()) {
        ThreadHeader(state, peer, loadAvatar, onCall, onEvent)
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))

        val media = BubbleMedia(onOpenAttachment, loadThumbnail, player, loadAudio)
        // Passed beside the react handler rather than through it: deletion is
        // keyed by the server's id, and only rows that have one can offer it.
        Messages(state, resolveName, media, loadAvatar, onEvent, Modifier.weight(1f))

        if (state.peerTyping) {
            TypingIndicator(peer.fullName.substringBefore(' '))
        }

        Composer(state, peer.fullName, onEvent)
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
@Composable
private fun ThreadHeader(
    state: ChatUiState,
    peer: com.zillit.desktop.feature.chat.domain.CrewContact,
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    onCall: ((video: Boolean) -> Unit)?,
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
        Column(Modifier.weight(1f)) {
            ZillitText(text = peer.fullName, style = ZillitTheme.typography.titleSmall)
            // Department and role together — the same line their crew card
            // leads with, so the header answers "which Sam is this".
            val role = listOfNotNull(
                peer.department?.takeIf { it.isNotBlank() },
                peer.designation?.takeIf { it.isNotBlank() },
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
            // truncated — which is the same as not showing it.
            //
            // This is the one thing the contact card carried that the header
            // did not, and the card is no longer on the way to a conversation.
            // Groups have no address, so the null check is the whole guard.
            peer.email?.takeIf { it.isNotBlank() }?.let { address ->
                ZillitText(
                    text = address,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        // Callable when the peer has a device to ring (groups always do —
        // the room is the address). No device, no buttons: a call button
        // that fails on press is worse than none.
        if (onCall != null && (state.peerIsGroup || peer.deviceId != null)) {
            ZillitIconButton(
                icon = ZillitIcons.Phone,
                contentDescription = "Start call",
                onClick = { onCall(false) },
                tint = ZillitTheme.colors.success,
            )
            ZillitIconButton(
                icon = ZillitIcons.Camera,
                contentDescription = "Start video call",
                onClick = { onCall(true) },
                tint = ZillitTheme.colors.accentText,
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close conversation",
            onClick = { onEvent(ChatEvent.CloseThread) },
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
            modifier = Modifier.weight(1f).heightIn(min = COMPOSER_MIN_HEIGHT),
            onImeAction = { if (state.canSend) onEvent(ChatEvent.Send) },
        )
        ZillitIconButton(
            icon = ZillitIcons.Send,
            contentDescription = "Send",
            onClick = { onEvent(ChatEvent.Send) },
            enabled = state.canSend,
            filled = true,
            size = SEND_BUTTON,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun Messages(
    state: ChatUiState,
    resolveName: (String) -> String?,
    media: BubbleMedia,
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    onEvent: (ChatEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

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
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ZillitText(
                text = "Loading the conversation…",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        return
    }

    if (!state.isLoading && state.messages.isEmpty()) {
        EmptyThread(state.peer?.fullName.orEmpty(), modifier)
        return
    }

    val now = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    val rows = remember(state.messages, now) { threadRows(state.messages, now) }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md)
            .then(rememberWheelScroll(listState)),
        // Newest at index 0, pinned to the visual bottom — see the note on
        // FollowLatestReversed above. The rows are built reversed to match.
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            vertical = ZillitTheme.spacing.md,
        ),
    ) {
        items(rows, key = ThreadRow::key) { row ->
            when (row) {
                is ThreadRow.DayMark -> DayChip(row.label)

                // Incoming rows carry the writer's face like the home board;
                // rooms also name them — a DM's name would say the obvious.
                is ThreadRow.Message -> IncomingAware(
                    message = row.message,
                    senderName = if (state.peerIsGroup && !row.message.isMine) {
                        resolveName(row.message.senderId)
                    } else {
                        null
                    },
                    loadAvatar = loadAvatar,
                    media = media,
                    onReact = { emoji -> onEvent(ChatEvent.React(row.message.id, emoji)) },
                    onDelete = { onEvent(ChatEvent.Delete(row.message.id)) },
                    uploadPercent = state.uploads[row.message.uniqueId],
                )
            }
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

    data class Message(val message: ChatMessage) : ThreadRow {
        override val key: String get() = message.uniqueId
    }

    data class DayMark(val label: String, override val key: String) : ThreadRow
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
    messages.forEach { message ->
        if (message.timestampMillis > 0) {
            val day = kotlin.time.Instant.fromEpochMilliseconds(message.timestampMillis)
                .toLocalDateTime(zone).date
            if (day != lastDay) {
                rows += ThreadRow.DayMark(
                    label = chatDayLabel(message.timestampMillis, nowMillis, zone),
                    key = "day-$day",
                )
                lastDay = day
            }
        }
        rows += ThreadRow.Message(message)
    }
    return rows.asReversed()
}

/** The day, centred on its own quiet line — every chat client's convention. */
@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    media: BubbleMedia,
    onReact: (String) -> Unit,
    onDelete: () -> Unit = {},
    uploadPercent: Int? = null,
) {
    if (message.isMine) {
        Bubble(message, senderName, media, onReact, onDelete, uploadPercent)
        return
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        val face = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
            initialValue = null,
            message.senderId,
        ) { value = loadAvatar(message.senderId) }.value
        ZillitAvatar(name = senderName ?: "?", image = face, size = ROW_AVATAR)
        Bubble(message, senderName, media, onReact)
    }
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
    val onOpen: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit,
    val loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) ->
    androidx.compose.ui.graphics.ImageBitmap?,
    val player: com.zillit.desktop.core.designsystem.component.AudioPlayer?,
    val loadAudio: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray?,
)

@Composable
private fun Bubble(
    message: ChatMessage,
    senderName: String? = null,
    media: BubbleMedia,
    onReact: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    uploadPercent: Int? = null,
) {
    val mine = message.isMine
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
    Box(
        Modifier.fillMaxWidth().hoverable(hover),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        // The affordance sits on the bubble's inner side — between it and the
        // row's empty half. Anchored at the row's outer edge, the menu's
        // popup window has no room to be placed and is never shown at all.
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            if (mine && (hovered || reactOpen)) {
                if (message.isDeletable) DeleteAffordance(onDelete)
                ReactAffordance(
                    open = reactOpen,
                    onOpenChange = { reactOpen = it },
                    onReact = onReact,
                    openLeft = true,
                )
            }
            BubbleBody(message, senderName, media, onReact, mine, uploadPercent)
            if (!mine && (hovered || reactOpen)) {
                ReactAffordance(
                    open = reactOpen,
                    onOpenChange = { reactOpen = it },
                    onReact = onReact,
                )
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
                    bottomStart = if (mine) BUBBLE_RADIUS else BUBBLE_TAIL,
                    bottomEnd = if (mine) BUBBLE_TAIL else BUBBLE_RADIUS,
                ),
            )
            .background(
                if (mine) ZillitTheme.colors.accentSoft else ZillitTheme.colors.surface,
            )
            .padding(ZillitTheme.spacing.sm),
    ) {
        message.attachment?.let { file ->
            // Anything with a picture shows the picture; a voice note
            // shows its player; the chip is the fallback for the rest —
            // unless the file is still climbing to storage, which is its
            // own look: the name over a live bar.
            when {
                uploadPercent != null -> UploadingFile(file.name, uploadPercent)
                file.kind == "audio" -> VoiceBubble(file, media)
                file.kind == "image" || file.thumbnail.isNotBlank() ->
                    MediaThumb(
                        file,
                        media.loadThumbnail,
                        media.onOpen,
                        playBadge = file.kind == "video",
                    )
                else -> FileChip(file, media.onOpen)
            }
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
        if (message.body.isNotBlank()) {
            ZillitText(
                text = message.body,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
            )
        }
        BubbleFooter(message)
        ReactionChips(message, onReact)
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
) {
    if (message.reactions.isEmpty()) return
    Row(
        modifier = Modifier.padding(top = ZillitTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        message.reactions.groupBy { it.emoji }.forEach { (emoji, rows) ->
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
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { onOpenChange(false) },
            // Beside a right-aligned bubble the menu must grow leftward: the
            // default start-aligned drop would cross the window edge, and a
            // popup that cannot fit inside it is never shown at all.
            offset = if (openLeft) {
                androidx.compose.ui.unit.DpOffset(REACT_BUTTON - REACT_MENU_WIDTH, 0.dp)
            } else {
                androidx.compose.ui.unit.DpOffset(0.dp, 0.dp)
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

/** The plus, the microphone and the emoji palette — the board's own three. */
@Composable
private fun ComposerActions(state: ChatUiState, onEvent: (ChatEvent) -> Unit) {
    var emojiOpen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    ZillitIconButton(
        icon = ZillitIcons.Add,
        contentDescription = "Attach a file",
        onClick = { onEvent(ChatEvent.AttachFile) },
    )
    ZillitIconButton(
        icon = ZillitIcons.Mic,
        contentDescription = "Record a voice message",
        onClick = { onEvent(ChatEvent.StartRecording) },
    )
    Box {
        ZillitIconButton(
            icon = ZillitIcons.Smiley,
            contentDescription = "Insert an emoji",
            onClick = { emojiOpen = true },
        )
        androidx.compose.material3.DropdownMenu(
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
) {
    val image = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        file.media,
    ) { value = loadThumbnail(file) }.value

    if (image == null) {
        FileChip(file, onOpen)
        return
    }
    Box(contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = file.name,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .sizeIn(maxWidth = THUMB_MAX, maxHeight = THUMB_MAX)
                .clip(ZillitTheme.shapes.small)
                .clickable { onOpen(file) },
        )
        if (playBadge) {
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
    ChatSendState.Sending -> ZillitIcons.Clock
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

private val HAIRLINE = 1.dp
private val BUBBLE_MAX_WIDTH = 420.dp
private val BUBBLE_RADIUS = 16.dp
private val BUBBLE_TAIL = 4.dp
private val HEADER_AVATAR = 40.dp
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
private val PLAY_BADGE = 40.dp
private val VOICE_MIN_WIDTH = 220.dp
private val REACT_BUTTON = 24.dp
private val REACT_MENU_WIDTH = 232.dp
private val UPLOAD_BAR_WIDTH = 220.dp
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
