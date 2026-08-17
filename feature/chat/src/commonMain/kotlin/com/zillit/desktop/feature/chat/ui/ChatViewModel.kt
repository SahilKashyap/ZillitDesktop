package com.zillit.desktop.feature.chat.ui

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatVoice
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.PendingChatUpload
import com.zillit.desktop.feature.chat.domain.sortedRecents

data class ChatUiState(
    /** Who the open thread is with; null shows the contact card instead. */
    val peer: CrewContact? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val isLoading: Boolean = false,
    /** Peer ids with an existing thread — the Chats tab's rows. */
    val recents: List<String> = emptyList(),
    /** The open peer is writing right now. */
    val peerTyping: Boolean = false,
    /** Seconds on the open microphone; null when not recording. */
    val recordingSeconds: Int? = null,
    /** The open thread is a group room; sends ride `group_chat`. */
    val peerIsGroup: Boolean = false,
    /** The production's rooms, above the direct threads. */
    val groups: List<GroupRoom> = emptyList(),
    /** The newest cached line per peer — the recents list's previews. */
    val previews: Map<String, String> = emptyMap(),
    /** Unread badge per peer, absent when zero. */
    val unread: Map<String, Int> = emptyMap(),
    /** Newest activity per conversation, for the listing's time column. */
    val activity: Map<String, Long> = emptyMap(),
    /** Starred conversations, newest concern first in the Favourites filter. */
    val favourites: Set<String> = emptySet(),
    /**
     * Upload percent per in-flight attachment, keyed by the bubble's unique
     * id. -1 while the file is prepared (posters, PDF pages), 0..100 as the
     * bytes move; absent once the message is on the wire.
     */
    val uploads: Map<String, Int> = emptyMap(),
    val error: String? = null,
) {
    val canSend: Boolean get() = draft.isNotBlank() && peer != null
}

sealed interface ChatEvent {
    data class OpenThread(val contact: CrewContact) : ChatEvent
    data object CloseThread : ChatEvent
    data class DraftChanged(val text: String) : ChatEvent
    data object Send : ChatEvent
    data class Arrived(val message: ChatMessage) : ChatEvent
    data object DismissError : ChatEvent

    /** The Chats tab asking for the thread list. */
    data object RefreshRecents : ChatEvent

    /**
     * A different production is open. Everything on screen belonged to the
     * last one — conversations, threads, badges, stars — so it all goes.
     */
    data object ProjectChanged : ChatEvent

    /** Delivered by the socket: the open peer started or stopped writing. */
    data class PeerTyping(val peerId: String, val started: Boolean) : ChatEvent

    /** The other end reports how far our messages to them have got. */
    data class Receipt(val peerId: String, val state: ChatSendState) : ChatEvent

    /** A room row: the same thread machinery, group-flavoured. */
    data class OpenGroup(val room: GroupRoom) : ChatEvent

    /** The paperclip: pick, upload, and send into the open thread. */
    data object AttachFile : ChatEvent

    /** Withdraws one of our own messages, for everyone in the thread. */
    data class Delete(val messageId: String) : ChatEvent

    /** The server says these ids are gone — ours from another device, or theirs. */
    data class Deleted(val messageIds: List<String>) : ChatEvent
    /** Sets, switches or (same emoji again) removes our reaction. */
    data class React(val messageId: String, val emoji: String) : ChatEvent
    data object StartRecording : ChatEvent
    data object StopRecording : ChatEvent
    data object CancelRecording : ChatEvent

    /** The row's star: keep this conversation in the Favourites filter. */
    data class ToggleFavourite(val id: String) : ChatEvent
}

/**
 * One open DM thread. History loads when a thread opens; sends are optimistic
 * with the bubble flipping to Failed rather than vanishing; arrivals for the
 * open peer append, everything else is ignored until a recents list exists.
 */
@Suppress("LongParameterList")
class ChatViewModel(
    private val repository: ChatRepository,
    private val nowMillis: () -> Long,
    private val newUniqueId: () -> String,
    /** Opens the OS picker; the upload itself runs after the bubble is up. */
    private val pickAttachment: suspend () -> PendingChatUpload? = { null },
    /** The microphone, already wired to the uploader; null hides the mic. */
    private val voice: ChatVoice? = null,
    /** The starred set's home between sessions; hosts wire preferences. */
    private val loadFavourites: suspend () -> Set<String> = { emptySet() },
    private val saveFavourites: suspend (Set<String>) -> Unit = {},
    /**
     * Told after a thread's read marks land. Hosts hang badge refreshes here:
     * the server does not echo a badge event back for one's own reads, so
     * whoever draws counts must ask again themselves.
     */
    private val onThreadRead: suspend (String) -> Unit = {},
) : ZillitViewModel<ChatUiState, ChatEvent, Nothing>(ChatUiState()) {

    init {
        launch {
            val stars = loadFavourites()
            if (stars.isNotEmpty()) setState { copy(favourites = stars) }
        }
        // The join rides every connection, not just the first: startup can win
        // the race against the socket, and a reconnect drops the server-side
        // room — either way the user simply stops receiving.
        launch {
            repository.connections.collect {
                repository.join()
                onEvent(ChatEvent.RefreshRecents)
            }
        }
        launch { repository.incoming.collect { onEvent(ChatEvent.Arrived(it)) } }
        // A thread read on another of this user's devices: drop the row's
        // count here too, and let the badge store re-ask the server.
        launch {
            repository.selfReads.collect { conversationId ->
                repository.markThreadRead(conversationId, nowMillis())
                setState { copy(unread = unread - conversationId) }
                onThreadRead(conversationId)
            }
        }
        launch {
            repository.typing.collect { (peer, started) ->
                onEvent(ChatEvent.PeerTyping(peer, started))
            }
        }
        launch { repository.deletions.collect { onEvent(ChatEvent.Deleted(it)) } }
        launch {
            repository.receipts.collect { receipt ->
                onEvent(ChatEvent.Receipt(receipt.peerId, receipt.state))
            }
        }
    }

    // Exhaustive dispatch over the sealed event set — branch count is the
    // pattern, not a complexity smell (see HomeFeedViewModel's onEvent).
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.OpenThread -> openThread(event.contact)
            is ChatEvent.OpenGroup -> openThread(
                CrewContact(userId = event.room.id, fullName = event.room.name),
                isGroup = true,
            )
            ChatEvent.CloseThread -> setState { copy(peer = null, messages = emptyList()) }
            is ChatEvent.DraftChanged -> {
                // The transition is the signal: empty→writing says "start",
                // writing→empty says "end". Every keystroke would be noise.
                val was = currentState.draft.isNotBlank()
                val now = event.text.isNotBlank()
                setState { copy(draft = event.text) }
                val peer = currentState.peer
                if (peer != null && was != now) {
                    launch { repository.sendTyping(peer.userId, started = now) }
                }
            }
            ChatEvent.Send -> send()
            is ChatEvent.ToggleFavourite -> toggleFavourite(event.id)
            ChatEvent.AttachFile -> launch { sendPickedFile() }
            is ChatEvent.Delete -> deleteMessage(event.messageId)
            is ChatEvent.Deleted -> dropDeleted(event.messageIds)
            is ChatEvent.React -> react(event.messageId, event.emoji)
            ChatEvent.StartRecording -> startRecording()
            ChatEvent.StopRecording -> finishRecording(discard = false)
            ChatEvent.CancelRecording -> finishRecording(discard = true)
            is ChatEvent.Arrived -> arrived(event.message)
            ChatEvent.DismissError -> setState { copy(error = null) }
            is ChatEvent.Receipt -> applyReceipt(event.peerId, event.state)
            is ChatEvent.PeerTyping ->
                if (currentState.peer?.userId == event.peerId) {
                    setState { copy(peerTyping = event.started) }
                }
            ChatEvent.ProjectChanged -> startFreshProject()
            ChatEvent.RefreshRecents -> {
                launchResult(
                    block = { repository.rooms() },
                    onSuccess = { rooms -> setState { copy(groups = rooms) } },
                    onError = { },
                )
                launchResult(
                block = { repository.recentPeers() },
                onSuccess = { ids ->
                    // A cached thread proves a conversation even when the
                    // server's list misses it — the union is the truth.
                    val activity = repository.newestActivity()
                    val ordered = sortedRecents((ids + activity.keys).distinct(), activity)
                    setState {
                        copy(
                            recents = ordered,
                            previews = previewsFor(ordered),
                            unread = repository.unreadCounts(),
                            activity = activity,
                        )
                    }
                },
                // A failed list costs the tab, not the screen — no toast.
                onError = { },
                )
            }
        }
    }

    private fun openThread(contact: CrewContact, isGroup: Boolean = false) {
        // The session cache answers instantly; the fetch refreshes behind it.
        val known = repository.cached(contact.userId)
        setState {
            copy(
                peer = contact,
                peerIsGroup = isGroup,
                messages = known.orEmpty(),
                isLoading = known == null,
                peerTyping = false,
                error = null,
            )
        }
        // Re-join on every open: the init-time join can predate the socket
        // connecting, and a join is idempotent while a missed one costs
        // delivery for the whole session.
        launch { repository.join() }
        launchResult(
            block = { repository.history(contact.userId, nowMillis(), isGroup) },
            onSuccess = { rows ->
                setState {
                    if (peer?.userId == contact.userId) {
                        // A late history answer must not wipe bubbles still in
                        // flight — an upload started after the fetch went out
                        // exists only locally until its send is acknowledged.
                        val inFlight = messages.filter { local ->
                            local.isMine && local.sendState != ChatSendState.Sent &&
                                rows.none { it.uniqueId == local.uniqueId }
                        }
                        copy(isLoading = false, messages = rows + inFlight)
                    } else {
                        this
                    }
                }
                // The sender learns their words were seen the moment the
                // thread is on screen — Android's status 3 on the newest row.
                // Groups emit their own read-untill (room_id + my user id):
                // it is what clears the room's badge server-side, and a
                // client that keeps only a local mark leaves the count
                // standing forever on every device.
                rows.lastOrNull { !it.isMine }?.let { newest ->
                    launch { repository.markRead(contact.userId, newest.id, isGroup) }
                }
                // Opening reads everything the thread holds.
                rows.maxOfOrNull(ChatMessage::timestampMillis)?.let {
                    repository.markThreadRead(contact.userId, it)
                }
                setState { copy(unread = unread - contact.userId) }
                launch { onThreadRead(contact.userId) }
            },
            onError = { error ->
                setState { copy(isLoading = false, error = error.localised()) }
            },
        )
    }

    private fun send(attachment: ChatAttachment? = null) {
        val peer = currentState.peer ?: return
        val body = currentState.draft.trim().ifEmpty { if (attachment == null) return else "" }
        val optimistic = appendOptimistic(peer, body, attachment)
        setState { copy(draft = "") }

        launchResult(
            block = {
                repository.send(
                    peer.userId, body, optimistic.uniqueId, optimistic.timestampMillis,
                    isGroup = currentState.peerIsGroup,
                    attachment = attachment,
                )
            },
            onSuccess = { setSendState(optimistic.uniqueId, ChatSendState.Sent) },
            onError = { error ->
                setSendState(optimistic.uniqueId, ChatSendState.Failed)
                setState { copy(error = error.localised()) }
            },
        )
    }

    /** The bubble that appears before the server has spoken. */
    private fun appendOptimistic(
        peer: CrewContact,
        body: String,
        attachment: ChatAttachment?,
    ): ChatMessage {
        val uniqueId = newUniqueId()
        val optimistic = ChatMessage(
            id = uniqueId,
            uniqueId = uniqueId,
            senderId = "me",
            receiverId = peer.userId,
            body = body,
            timestampMillis = nowMillis(),
            isMine = true,
            sendState = ChatSendState.Sending,
            attachment = attachment,
        )
        setState {
            // The listing follows the send, not the ack: the shelf reorders,
            // the preview shows what was just written, the clock reads now —
            // the behaviour every messenger trains. The repository's caches
            // catch up when the server acks (see ChatRepositoryImpl.send),
            // so the next wholesale recompute agrees rather than snapping
            // back to the peer's last line.
            val bumped = activity + (peer.userId to optimistic.timestampMillis)
            copy(
                messages = messages + optimistic,
                // The first message to someone makes them a conversation.
                recents = sortedRecents(withPeer(recents, peer.userId), bumped),
                activity = bumped,
                previews = withPreview(previews, peer.userId, optimistic),
            )
        }
        return optimistic
    }

    /**
     * The paperclip's whole journey: bubble first, then bytes.
     *
     * The bubble goes up the moment the picker closes, wearing the file's name
     * and a progress bar fed by [ChatUiState.uploads]. Only once the file is in
     * storage does the message ride the socket — sent earlier it would name an
     * object that does not exist yet.
     */
    private suspend fun sendPickedFile() {
        val peer = currentState.peer ?: return
        val isGroup = currentState.peerIsGroup
        val pending = pickAttachment() ?: return
        ZillitLog.d(TAG) { "picked file for upload; bubble up" }
        val placeholder = ChatAttachment(
            media = "",
            name = pending.name,
            contentType = pending.contentType,
            bucket = "",
            region = "",
            thumbnail = "",
        )
        val optimistic = appendOptimistic(peer, body = "", attachment = placeholder)
        val uniqueId = optimistic.uniqueId
        setState { copy(uploads = uploads + (uniqueId to PREPARING)) }

        val stored = pending.upload { percent ->
            setState { copy(uploads = uploads + (uniqueId to percent)) }
        }
        ZillitLog.d(TAG) { "upload finished stored=${stored != null}" }
        if (stored == null) {
            setSendState(uniqueId, ChatSendState.Failed)
            setState {
                copy(uploads = uploads - uniqueId, error = "Could not upload ${pending.name}.")
            }
            return
        }
        setState {
            copy(
                messages = messages.map {
                    if (it.uniqueId == uniqueId) it.copy(attachment = stored) else it
                },
                uploads = uploads - uniqueId,
            )
        }
        when (
            val sent = repository.send(
                peer.userId, "", uniqueId, optimistic.timestampMillis,
                isGroup = isGroup,
                attachment = stored,
            )
        ) {
            is ZillitResult.Success -> setSendState(uniqueId, ChatSendState.Sent)
            is ZillitResult.Failure -> {
                setSendState(uniqueId, ChatSendState.Failed)
                setState { copy(error = sent.error.localised()) }
            }
        }
    }

    /**
     * A fresh state rather than a targeted clear: every field here belongs to
     * the open production, and a list of what to wipe would rot the next time
     * one is added.
     */
    private fun startFreshProject() {
        setState { ChatUiState() }
        launch {
            val stars = loadFavourites()
            setState { copy(favourites = stars) }
            onEvent(ChatEvent.RefreshRecents)
        }
    }

    /**
     * Withdraws one of our own messages, everywhere.
     *
     * The bubble stays until the server confirms — a delete is the one send
     * whose optimism would lie, because a line that reappears after "gone"
     * reads as the peer reposting it.
     */
    private fun deleteMessage(messageId: String) {
        launchResult(
            block = {
                repository.deleteMessages(listOf(messageId), currentState.peerIsGroup)
            },
            onSuccess = ::dropDeleted,
            onError = { error -> setState { copy(error = error.localised()) } },
        )
    }

    /**
     * Removes deleted ids from everything on screen.
     *
     * The listing recomputes from the repository — the caches forgot the rows
     * before this ran, so the preview falls back to whatever the thread now
     * ends on rather than echoing the withdrawn line.
     */
    private fun dropDeleted(messageIds: List<String>) {
        val gone = messageIds.toSet()
        val activity = repository.newestActivity()
        setState {
            copy(
                messages = messages.filterNot { it.id in gone },
                activity = activity,
                recents = sortedRecents(recents, activity),
                previews = previewsFor(recents),
            )
        }
    }

    /**
     * The toggle every client shares: tapping your current emoji takes it
     * back (the wire's removal is an empty reaction), tapping another
     * replaces it.
     */
    private fun react(messageId: String, emoji: String) {
        val me = repository.selfId() ?: return
        val current = currentState.messages.firstOrNull { it.id == messageId } ?: return
        val mine = current.reactions.firstOrNull { it.userId == me }?.emoji
        val sending = if (mine == emoji) "" else emoji
        launch {
            repository.sendReaction(messageId, sending)?.let { updated ->
                setState {
                    copy(
                        messages = messages.map {
                            if (it.uniqueId == updated.uniqueId) updated else it
                        },
                    )
                }
            }
        }
    }

    private var recordingTicker: kotlinx.coroutines.Job? = null

    private fun startRecording() {
        val mic = voice ?: return
        if (currentState.recordingSeconds != null) return
        launch {
            when (val started = mic.start()) {
                is com.zillit.desktop.core.common.ZillitResult.Failure ->
                    setState { copy(error = started.error.userMessage) }
                is com.zillit.desktop.core.common.ZillitResult.Success -> {
                    setState { copy(recordingSeconds = 0, error = null) }
                    recordingTicker = launch {
                        while (currentState.recordingSeconds != null) {
                            kotlinx.coroutines.delay(RECORDING_TICK_MILLIS)
                            setState { copy(recordingSeconds = recordingSeconds?.plus(1)) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Ends the take. Unlike the board — where audio parks in the draft — a
     * chat voice note sends on Stop: the thread is the conversation, and a
     * recorded message waiting behind a second send press is a step nobody
     * expects in a messenger.
     */
    private fun finishRecording(discard: Boolean) {
        val mic = voice ?: return
        if (currentState.recordingSeconds == null) return
        recordingTicker?.cancel()
        setState { copy(recordingSeconds = null) }
        if (discard) {
            mic.cancel()
            return
        }
        launch {
            when (val file = mic.stop()) {
                is com.zillit.desktop.core.common.ZillitResult.Failure ->
                    setState { copy(error = file.error.userMessage) }
                is com.zillit.desktop.core.common.ZillitResult.Success ->
                    send(attachment = file.data)
            }
        }
    }

    private fun toggleFavourite(id: String) {
        val next = if (id in currentState.favourites) {
            currentState.favourites - id
        } else {
            currentState.favourites + id
        }
        setState { copy(favourites = next) }
        launch { saveFavourites(next) }
    }

    private fun previewsFor(ids: List<String>): Map<String, String> =
        ids.mapNotNull { id ->
            repository.lastMessageOf(id)?.let { last ->
                id to (last.attachment?.let { "📎 ${it.name}" } ?: last.body)
            }
        }.toMap()

    /**
     * A read-untill receipt covers the whole thread, not one message: every
     * message we sent to that peer moves up to the reported state. Only
     * forwards — a later "delivered" must not un-read what was already read.
     */
    private fun applyReceipt(peerId: String, state: ChatSendState) {
        if (currentState.peer?.userId != peerId) return
        setState {
            copy(
                messages = messages.map { message ->
                    if (message.isMine && !message.sendState.atLeast(state)) {
                        message.copy(sendState = state)
                    } else {
                        message
                    }
                },
            )
        }
    }

    private fun setSendState(uniqueId: String, state: ChatSendState) {
        setState {
            copy(
                messages = messages.map {
                    if (it.uniqueId == uniqueId) it.copy(sendState = state) else it
                },
            )
        }
    }

    /**
     * A socket arrival. The echo of our own send replaces the optimistic
     * bubble by unique id; a peer's message appends when their thread is open.
     */
    /**
     * A live message. Every arrival updates the listing — a conversation with
     * no thread open still moves up the shelf and grows a badge, which is what
     * "synced" means to someone looking at the list rather than a thread.
     * Only the open thread also appends the bubble.
     */
    private fun arrived(message: ChatMessage) {
        val peer = currentState.peer
        val other = if (message.isMine) message.receiverId else message.senderId
        val isOpen = peer != null &&
            (message.senderId == peer.userId || message.receiverId == peer.userId)

        if (isOpen && !message.isMine && peer != null) {
            // Reading it here is what tells the sender it was seen — and,
            // for a room, what clears its badge server-side: the group emit
            // is a different event with a different envelope, and a live
            // arrival that took the DM path left the room's count standing.
            setState { copy(peerTyping = false) }
            repository.markThreadRead(peer.userId, message.timestampMillis)
            val isGroup = currentState.peerIsGroup
            launch {
                repository.markRead(peer.userId, message.id, isGroup)
                onThreadRead(peer.userId)
            }
        }

        val activity = repository.newestActivity()
        val unread = repository.unreadCounts()
        setState {
            copy(
                recents = sortedRecents(withPeer(recents, other), activity),
                activity = activity,
                unread = unread,
                previews = withPreview(previews, other, message),
                messages = merged(messages, message, isOpen),
            )
        }
    }

    /** A conversation that just spoke belongs on the shelf, once. */
    private fun withPeer(recents: List<String>, peerId: String): List<String> =
        if (peerId.isBlank() || peerId in recents) recents else recents + peerId

    private fun withPreview(
        previews: Map<String, String>,
        peerId: String,
        message: ChatMessage,
    ): Map<String, String> {
        if (peerId.isBlank()) return previews
        val line = message.attachment?.let { "📎 ${it.name}" } ?: message.body
        return previews + (peerId to line)
    }

    /** The echo of our own send replaces its optimistic bubble by unique id. */
    private fun merged(
        messages: List<ChatMessage>,
        message: ChatMessage,
        isOpen: Boolean,
    ): List<ChatMessage> = when {
        !isOpen -> messages
        messages.any { it.uniqueId == message.uniqueId } ->
            messages.map { if (it.uniqueId == message.uniqueId) message else it }
        else -> messages + message
    }
}

private const val TAG = "Chat"
private const val RECORDING_TICK_MILLIS = 1_000L

/** The file is being prepared (posters, PDF pages) — no bytes moving yet. */
private const val PREPARING = -1
