package com.zillit.desktop.feature.chat.ui

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.sync.NewOperation
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.feature.chat.data.CHAT_SEND_KIND
import com.zillit.desktop.feature.chat.data.QueuedChatSend
import com.zillit.desktop.feature.chat.data.toQueuedBubble
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
    /**
     * The badge service's own split of this area — what the mobile clients
     * draw on the Chats and Calls tabs: `chat_label` (member + group chats)
     * and `call_label` (missed calls). Keyed by the wire's tool label.
     */
    val sectionBadges: Map<String, Int> = emptyMap(),
    val error: String? = null,
) {
    /** Unread across every conversation — the Chats tab. */
    val chatsBadge: Int get() = sectionBadges["chat_label"] ?: 0

    /** Missed calls — the Calls tab. */
    val callsBadge: Int get() = sectionBadges["call_label"] ?: 0

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

    /**
     * The Calls tab came on screen. Missed calls are read by looking at the
     * log — iOS's `readCNCMessage(.misscall)`, a `notification:read` on the
     * `call_label` segment — and the tab's count falls with them.
     */
    data object CallsViewed : ChatEvent
}

/**
 * One open DM thread. History loads when a thread opens; sends are optimistic
 * with the bubble flipping to Failed rather than vanishing; arrivals for the
 * open peer append, everything else is ignored until a recents list exists.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One function per chat event; the set is the surface.
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
    /**
     * This area's counts by tool (`chat_label`, `call_label`) — the badge
     * service's answer to `?section=cnc_label&group=tool`. Hosts wire it;
     * empty leaves the tabs bare.
     */
    private val sectionBadges: suspend () -> Map<String, Int>? = { emptyMap() },
    /** Reads the missed-call badge — the Calls tab was opened. Hosts wire the emit. */
    private val onCallsViewed: suspend () -> Unit = {},
    /**
     * With this wired, a message written with no network is kept on this
     * computer and sent when it is back — the clock the phones show. Null
     * keeps sends live-only, as before.
     */
    private val offline: OfflineSupport? = null,
) : ZillitViewModel<ChatUiState, ChatEvent, Nothing>(ChatUiState()) {

    private val json = Json { ignoreUnknownKeys = true }

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
        // A queued message's clock becomes a tick (or a failure mark) as the
        // outbox moves it, without waiting for the thread to be reopened.
        offline?.let { support -> launch { support.engine.status.collect { refreshQueued() } } }
        // A thread read on another of this user's devices: drop the row's
        // count here too, and let the badge store re-ask the server.
        launch {
            repository.selfReads.collect { conversationId ->
                repository.markThreadRead(conversationId, nowMillis())
                serverUnread.remove(conversationId)
                setState { copy(unread = unread - conversationId) }
                onThreadRead(conversationId)
                refreshSectionBadges()
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
    @Suppress("CyclomaticComplexMethod", "LongMethod")
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
            ChatEvent.CallsViewed -> if (currentState.callsBadge > 0) {
                launch {
                    onCallsViewed()
                    applySplit(sectionBadges())
                }
            }
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
                refreshSectionBadges()
                launchResult(
                    block = { repository.rooms() },
                    onSuccess = { rooms -> setState { copy(groups = rooms) } },
                    onError = { },
                )
                // The per-conversation counts, from the server's backlog. A
                // failed fetch keeps whatever the cache can say — no toast.
                launchResult(
                    block = { repository.conversationUnread() },
                    onSuccess = { counts ->
                        serverUnread.clear()
                        // A thread open right now was just read; its rows in
                        // the backlog predate that.
                        serverUnread.putAll(currentState.peer?.userId?.let { counts - it } ?: counts)
                        setState { copy(unread = combinedUnread(repository.unreadCounts())) }
                    },
                    onError = { },
                )
                launchResult(
                    block = { repository.recentPeers() },
                    onSuccess = { ids ->
                        launch { rememberRecents(ids) }
                        showRecents(ids)
                    },
                    // The list comes over the socket, so with no network it
                    // never answers: show the last list this production had,
                    // plus every thread kept on this computer — no toast.
                    onError = { launch { showRecents(rememberedRecents()) } },
                )
            }
        }
    }

    /**
     * A cached thread proves a conversation even when the server's list
     * misses it — the union is the truth.
     */
    private fun showRecents(ids: List<String>) {
        val activity = repository.newestActivity()
        val ordered = sortedRecents((ids + activity.keys).distinct(), activity)
        setState {
            copy(
                recents = ordered,
                previews = previewsFor(ordered),
                unread = combinedUnread(repository.unreadCounts()),
                activity = activity,
            )
        }
    }

    private suspend fun rememberRecents(ids: List<String>) {
        val support = offline ?: return
        val scope = support.currentScope() ?: return
        val encoded = json.encodeToString(ListSerializer(String.serializer()), ids)
        support.cache.put(scope, RECENTS_CACHE, encoded, nowMillis())
    }

    private suspend fun rememberedRecents(): List<String> {
        val support = offline ?: return emptyList()
        val scope = support.currentScope() ?: return emptyList()
        val kept = support.cache.get(scope, RECENTS_CACHE) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), kept.json) }
            .getOrDefault(emptyList())
    }

    /** Re-asks the badge service for this area's split — after anything moves. */
    private fun refreshSectionBadges() {
        launch { applySplit(sectionBadges()) }
    }

    /** A null answer is a failed ask — the last split stands, as the rail's does. */
    private fun applySplit(split: Map<String, Int>?) {
        split?.let { fresh -> setState { copy(sectionBadges = fresh) } }
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
                // Messages written offline for this thread, still waiting.
                launch { refreshQueued() }
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
                serverUnread.remove(contact.userId)
                setState { copy(unread = unread - contact.userId) }
                launch {
                    onThreadRead(contact.userId)
                    // The host's refetch waited for the server; the tab split
                    // asks now for the same reason.
                    applySplit(sectionBadges())
                }
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
        val isGroup = currentState.peerIsGroup

        // No network and no file to upload: straight to the outbox, no
        // round trip to fail first.
        val support = offline
        if (support != null && support.isOffline && attachment == null) {
            launch { queue(support, peer.userId, body, optimistic, isGroup) }
            return
        }

        launchResult(
            block = {
                repository.send(
                    peer.userId, body, optimistic.uniqueId, optimistic.timestampMillis,
                    isGroup = isGroup,
                    attachment = attachment,
                )
            },
            onSuccess = { setSendState(optimistic.uniqueId, ChatSendState.Sent) },
            onError = { error ->
                // The message never left this machine (socket down, no
                // network): keep it and send it later rather than fail it.
                if (support != null && attachment == null && error is ZillitError.NoConnection) {
                    launch { queue(support, peer.userId, body, optimistic, isGroup) }
                } else {
                    setSendState(optimistic.uniqueId, ChatSendState.Failed)
                    setState { copy(error = error.localised()) }
                }
            },
        )
    }

    // -- offline: the outbox -----------------------------------------------------

    private suspend fun queue(
        support: OfflineSupport,
        receiverId: String,
        body: String,
        optimistic: ChatMessage,
        isGroup: Boolean,
    ) {
        val payload = QueuedChatSend(
            receiverId = receiverId,
            body = body,
            uniqueId = optimistic.uniqueId,
            timestampMillis = optimistic.timestampMillis,
            isGroup = isGroup,
        )
        val enqueued = support.engine.enqueue(
            NewOperation(
                kind = CHAT_SEND_KIND,
                label = "Message to ${currentState.peer?.fullName?.ifBlank { null } ?: receiverId}",
                payload = json.encodeToString(QueuedChatSend.serializer(), payload),
                // One thread's messages leave in the order they were written.
                groupKey = "chat:$receiverId",
                // The message's own id, so the bubble and the queue agree by construction.
                id = optimistic.uniqueId,
            ),
        )
        setSendState(optimistic.uniqueId, if (enqueued != null) ChatSendState.Queued else ChatSendState.Failed)
    }

    /**
     * The open thread's queued messages, from the outbox: those still there
     * wear the clock (or the failure mark), those the outbox has sent become
     * sent — unless the server's echo already replaced the bubble.
     */
    private suspend fun refreshQueued() {
        val support = offline ?: return
        val peerId = currentState.peer?.userId ?: return
        val operations = support.engine.operations().filter { it.kind == CHAT_SEND_KIND }
        val byId = operations.associateBy { it.id }
        setState {
            if (peer?.userId != peerId) return@setState this
            val known = messages.map { it.uniqueId }.toSet()
            val restored = operations.mapNotNull { it.toQueuedBubble(peerId, json) }.filter { it.uniqueId !in known }
            val followed = messages.map { message ->
                val op = byId[message.uniqueId] ?: return@map message
                val state = when (op.state) {
                    SyncState.Done -> ChatSendState.Sent
                    SyncState.Failed -> ChatSendState.Failed
                    SyncState.Pending, SyncState.InFlight -> ChatSendState.Queued
                }
                if (message.isMine && message.sendState.isOurs()) message.copy(sendState = state) else message
            }
            copy(messages = (followed + restored).sortedBy { it.timestampMillis })
        }
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
    /**
     * The server's unread per conversation, seeded from the notification
     * backlog on every listing refresh and moved by arrivals and reads
     * between refreshes. The rows the local cache can count are only the
     * threads this desktop has opened; a peer who wrote while it was closed
     * has a badge on the phone and had none here until this.
     */
    private val serverUnread = mutableMapOf<String, Int>()

    /** What the rows show: the larger of the server's word and the cache's. */
    private fun combinedUnread(local: Map<String, Int>): Map<String, Int> =
        (local.keys + serverUnread.keys).associateWith { key ->
            maxOf(local[key] ?: 0, serverUnread[key] ?: 0)
        }.filterValues { it > 0 }

    private fun startFreshProject() {
        serverUnread.clear()
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
                applySplit(sectionBadges())
            }
        }

        val activity = repository.newestActivity()
        // A line for a thread not on screen is one more the server counts;
        // the cache counts it too, and the row shows whichever is larger.
        if (!isOpen && !message.isMine && other.isNotBlank()) {
            serverUnread[other] = (serverUnread[other] ?: 0) + 1
        }
        val unread = combinedUnread(repository.unreadCounts())
        setState {
            copy(
                recents = sortedRecents(withPeer(recents, other), activity),
                activity = activity,
                unread = unread,
                previews = withPreview(previews, other, message),
                messages = merged(messages, message, isOpen),
            )
        }
        // The server's own count moved with this message — ask it again
        // (a beat later, so a read of the open thread has landed first).
        launch {
            kotlinx.coroutines.delay(SECTION_BADGE_SETTLE_MILLIS)
            applySplit(sectionBadges())
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

/** The last DM list the socket gave this production, for when it cannot. */
private const val RECENTS_CACHE = "chat.recents"

/** How long the server gets to apply a read before the tab split is re-asked. */
private const val SECTION_BADGE_SETTLE_MILLIS = 1_800L

/** The file is being prepared (posters, PDF pages) — no bytes moving yet. */
private const val PREPARING = -1

/** The states this device assigns itself; the server's own words never yield to the outbox. */
private fun ChatSendState.isOurs(): Boolean =
    this == ChatSendState.Sending || this == ChatSendState.Queued || this == ChatSendState.Failed
