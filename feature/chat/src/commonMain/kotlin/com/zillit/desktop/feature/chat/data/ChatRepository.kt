package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.database.ChatCache
import com.zillit.desktop.core.database.ChatMessageRow
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatSendState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Direct messages: history over REST, live traffic over the chat socket. */
@Suppress("TooManyFunctions") // One function per REST route or socket act.
interface ChatRepository {
    /** Messages arriving on the socket, decrypted; the caller filters by peer. */
    val incoming: Flow<ChatMessage>

    /** Our own user id in the open production, for reaction toggling. */
    fun selfId(): String?

    /**
     * Sets or clears this user's reaction (empty [emoji] clears), returning
     * the server's updated message — reactions included — or null when the
     * server refused.
     */
    suspend fun sendReaction(messageId: String, emoji: String): ChatMessage?

    /** Registers this socket for the signed-in user's messages. Idempotent. */
    suspend fun join()

    /** Fires each time the socket becomes connected — the cue to re-join. */
    val connections: Flow<Unit>

    /** Ids of everyone this user has a DM thread with, newest server order. */
    suspend fun recentPeers(): ZillitResult<List<String>>

    /**
     * The server's unread per conversation — DMs keyed by the peer's user id,
     * rooms by the room id — read off the notification backlog, exactly as
     * both phones and the web seed their chat-list badges. A count exists for
     * a thread this desktop has never opened; the local cache alone cannot
     * say that.
     */
    suspend fun conversationUnread(): ZillitResult<Map<String, Int>> = conversationBacklog().map { it.unread }

    /**
     * [conversationUnread] and, from the same rows, when each conversation
     * last moved — the server's activity stamps, for the listing's order. One
     * fetch serves both: a badge and the row it lifts must agree on their
     * source, or "sorted as per badge" is a coincidence.
     */
    suspend fun conversationBacklog(): ZillitResult<ConversationBacklog> =
        ZillitResult.Success(ConversationBacklog())

    /** Peers currently typing to us: peer id to started/stopped. */
    val typing: Flow<Pair<String, Boolean>>

    /** How far our own messages have got, as the other end reports it. */
    val receipts: Flow<ReadReceipt>

    /**
     * Messages edited after delivery, carrying their new text.
     *
     * Separate from [incoming] rather than folded into it: an edit is not a
     * new line in the thread, and a caller that treated it as one would append
     * a duplicate of something already on screen.
     */
    val edits: Flow<ChatMessage>

    /**
     * Server ids of messages deleted anywhere — this device's own emit is
     * answered through the ack, so this flow is the *other* ends: the peer
     * withdrawing something, or our own delete made on the phone.
     */
    val deletions: Flow<List<String>>

    /**
     * Withdraws [messageIds] for everyone in the conversation, returning the
     * ids the server confirmed gone. The caches forget them before this
     * returns — a deleted line must not resurface as a preview.
     */
    suspend fun deleteMessages(
        messageIds: List<String>,
        isGroup: Boolean = false,
    ): ZillitResult<List<String>>

    /** This session's copy of a thread, for instant reopen; null before load. */
    fun cached(otherUserId: String): List<ChatMessage>?

    /** The newest cached line of a thread — the recents list's preview. */
    fun lastMessageOf(otherUserId: String): ChatMessage?

    /** Tells the sender their messages up to [messageId] were read here. */
    suspend fun markRead(peerId: String, messageId: String, isGroup: Boolean = false)

    /**
     * Conversations this user read on ANOTHER device — peer id for DMs, room
     * id for groups. The listing clears its row and the badge refetches;
     * without this, a thread read on the phone stays badged here until
     * restart.
     */
    val selfReads: Flow<String>

    /** Remembers locally how far a thread has been read, for the badges. */
    fun markThreadRead(peerId: String, uptoMillis: Long)

    /** Cached non-mine messages newer than each thread's read mark. */
    fun unreadCounts(): Map<String, Int>

    /** Each cached thread's newest activity, for recency ordering. */
    fun newestActivity(): Map<String, Long>

    /** Tells [receiverId] we started or stopped writing. */
    suspend fun sendTyping(receiverId: String, started: Boolean)

    suspend fun history(
        otherUserId: String,
        nowMillis: Long,
        isGroup: Boolean = false,
    ): ZillitResult<List<ChatMessage>>

    @Suppress("LongParameterList")
    suspend fun send(
        receiverId: String,
        body: String,
        uniqueId: String,
        nowMillis: Long,
        isGroup: Boolean = false,
        attachment: com.zillit.desktop.feature.chat.domain.ChatAttachment? = null,
    ): ZillitResult<Unit>

    /** The production's group rooms. */
    suspend fun rooms(): ZillitResult<List<com.zillit.desktop.feature.chat.domain.GroupRoom>>

    /**
     * Creates a CNC group room — `POST chat-room` with Android's
     * `ReqGroupModel` body — answering the room the server saved. Defaulted
     * to a refusal so test fakes that never create rooms need not care.
     */
    suspend fun createRoom(
        name: String,
        memberIds: List<String>,
    ): ZillitResult<com.zillit.desktop.feature.chat.domain.GroupRoom> =
        ZillitResult.Failure(ZillitError.Unknown("group creation is not wired"))

    /**
     * Deletes a group room — `DELETE chat-room/{id}`, the web's
     * `deleteChatRoom` (`cncChatApi.js:101-107`), offered to the creator
     * alone (`InfoSiderGroup.jsx:119,798`). Defaulted like [createRoom].
     */
    suspend fun deleteRoom(roomId: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("group deletion is not wired"))

    /**
     * The Chats search's second reach (QA#12): every locally cached line —
     * the session map plus the disk copy — whose decrypted body contains
     * [query], case-blind, newest first. Purely local: nothing rides the
     * wire, and bodies are never logged. Empty when the query is blank.
     */
    fun searchMessages(query: String): List<MessageHit> = emptyList()
}

/**
 * One cached line matching the Chats search: which conversation to open
 * (peer user id for DMs, room id for groups) and a readable window of the
 * matched body. [isGroup] is best-effort — a thread restored from disk lost
 * the flag, so the listing resolves the conversation against its own rooms
 * before trusting it.
 */
data class MessageHit(
    val peerId: String,
    val isGroup: Boolean,
    val snippet: String,
    val timestampMillis: Long,
)

/**
 * The app's one chat socket is the notification socket — both live on the
 * chat host, exactly as Android runs one `ChatSocketHelper` connection.
 */
@Suppress("TooManyFunctions") // One function per wire operation; see the interface.
class ChatRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val bus: SocketEventBus,
    private val myUserId: () -> String?,
    private val projectId: () -> String?,
    private val encrypt: (String) -> String?,
    private val decrypt: (String) -> String?,
    /** The at-rest copy; null in tests. Bodies stay cipher-hex inside it. */
    private val disk: ChatCache? = null,
) : ChatRepository, ReplyAwareChatRepository {

    private val threads = mutableMapOf<String, List<ChatMessage>>()

    /**
     * Which production the map above belongs to.
     *
     * Peer ids are unique, but a person can be on two productions, and their
     * threads are different conversations. Without this the second production
     * opened the first one's messages — the disk cache was always keyed by
     * project; this map was not.
     */
    private var scopedTo: String? = null

    /**
     * Our own sends, as the server saved them — the ack's copy, carrying the
     * server's `_id`. Merged into [incoming] so the open thread swaps the
     * optimistic bubble (whose id is still the local unique id) for one the
     * server can address. Without this a just-sent line could not be
     * reacted to or withdrawn until the thread was reopened: the emit went
     * out with an id the server had never issued and nothing came back.
     */
    private val acked = kotlinx.coroutines.flow.MutableSharedFlow<ChatMessage>(extraBufferCapacity = ACK_BUFFER)

    override val incoming: Flow<ChatMessage> =
        kotlinx.coroutines.flow.merge(
            acked,
            bus.on(PRIVATE_CHAT).mapNotNull { message ->
                message.payload?.let { readChatMessage(it, myUserId(), decrypt) }
            },
            bus.on(GROUP_CHAT).mapNotNull { message ->
                message.payload?.let { readChatMessage(it, myUserId(), decrypt, isGroup = true) }
            },
            // A reaction event IS the updated message; riding the same flow
            // means the thread upserts it with no second merge path to drift.
            bus.on(UPDATE_REACTION).mapNotNull { message ->
                message.payload?.let { readChatMessage(it, myUserId(), decrypt) }
            },
        ).mapNotNull { message ->
            // Logged like the socket's own connect line: this protocol cannot
            // be exercised from one client, so an arrival leaving a trace is
            // what makes the next report diagnosable. Ids only, never bodies.
            ZillitLog.i(TAG) { "message in from=${message.senderId} group=${message.isGroup}" }
            message.also(::remember)
        }

    override val typing: Flow<Pair<String, Boolean>> =
        bus.on(TYPING).mapNotNull { it.payload?.let(::typingFrom) }

    override val receipts: Flow<ReadReceipt> =
        bus.on(READ_UNTILL).mapNotNull { message ->
            message.payload?.let { readReceiptFrom(it, myUserId()) }
        }

    override val deletions: Flow<List<String>> =
        kotlinx.coroutines.flow.merge(
            bus.on(PRIVATE_CHAT_DELETE),
            bus.on(GROUP_CHAT_DELETE),
        ).mapNotNull { message ->
            message.payload?.let(::deletedIdsFrom)
                ?.takeIf { it.isNotEmpty() }
                // Forget before anyone renders: newestActivity and
                // lastMessageOf must already agree by the time the listing
                // recomputes.
                ?.also(::forget)
        }

    override val selfReads: Flow<String> =
        kotlinx.coroutines.flow.merge(bus.on(READ_UNTILL), bus.on(GROUP_READ_UNTILL))
            .mapNotNull { message ->
                message.payload?.let { selfReadFrom(it, myUserId()) }
            }

    override val edits: Flow<ChatMessage> =
        kotlinx.coroutines.flow.merge(
            bus.on(PRIVATE_CHAT_EDIT).mapNotNull { message ->
                message.payload?.let { readChatMessage(it, myUserId(), decrypt) }
            },
            bus.on(GROUP_CHAT_EDIT).mapNotNull { message ->
                message.payload?.let { readChatMessage(it, myUserId(), decrypt, isGroup = true) }
            },
        )

    override fun cached(otherUserId: String): List<ChatMessage>? =
        threads()[otherUserId] ?: projectId()?.let { project ->
            disk?.thread(project, otherUserId)
                ?.takeIf { it.isNotEmpty() }
                ?.map { it.toMessage() }
                ?.also { threads()[otherUserId] = it }
        }

    override fun lastMessageOf(otherUserId: String): ChatMessage? =
        cached(otherUserId)?.maxByOrNull(ChatMessage::timestampMillis)

    override suspend fun markRead(peerId: String, messageId: String, isGroup: Boolean) {
        val project = projectId() ?: return
        if (isGroup) {
            val me = myUserId() ?: return
            bus.emit(
                GROUP_READ_UNTILL,
                groupReadUntillEnvelope(peerId, me, messageId, project),
                JsonElement.serializer(),
            )
        } else {
            bus.emit(READ_UNTILL, readUntillEnvelope(peerId, messageId, project), JsonElement.serializer())
        }
    }

    override suspend fun sendTyping(receiverId: String, started: Boolean) {
        val me = myUserId() ?: return
        bus.emit(TYPING, typingEnvelope(me, receiverId, started), JsonElement.serializer())
    }

    /** The session cache, emptied whenever the open production changes. */
    private fun threads(): MutableMap<String, List<ChatMessage>> {
        val project = projectId()
        if (project != scopedTo) {
            threads.clear()
            scopedTo = project
        }
        return threads
    }

    private fun remember(message: ChatMessage) {
        val peer = when {
            message.isGroup -> message.receiverId
            message.isMine -> message.receiverId
            else -> message.senderId
        }
        if (peer.isBlank()) return
        val known = threads()[peer].orEmpty()
        threads()[peer] = (known.filterNot { it.uniqueId == message.uniqueId } + message)
            .sortedBy(ChatMessage::timestampMillis)
        projectId()?.let { project -> disk?.upsert(project, message.toRow(peer)) }
    }

    override suspend fun deleteMessages(
        messageIds: List<String>,
        isGroup: Boolean,
    ): ZillitResult<List<String>> {
        val project = projectId() ?: return ZillitResult.Failure(
            com.zillit.desktop.core.common.ZillitError.Unauthorized("no open project"),
        )
        return bus.emitForAck(
            if (isGroup) GROUP_CHAT_DELETE else PRIVATE_CHAT_DELETE,
            deleteEnvelope(messageIds, project),
            JsonElement.serializer(),
        ).flatMap { ack ->
            val complaint = ackComplaint(ack)
            if (complaint != null) {
                ZillitLog.w(TAG) { "the server refused a delete: $complaint" }
                ZillitResult.Failure(ZillitError.Unknown(complaint))
            } else {
                // The ack's detail names what was actually removed; an ack
                // that names nothing still confirmed the emit, so the asked
                // ids stand in — leaving a deleted line on screen is worse
                // than double-forgetting one.
                val deleted = deletedIdsFrom(ack).ifEmpty { messageIds }
                forget(deleted)
                ZillitResult.Success(deleted)
            }
        }
    }

    /** Drops [messageIds] from the session map and the at-rest cache. */
    private fun forget(messageIds: List<String>) {
        val gone = messageIds.toSet()
        threads().entries.forEach { (peer, messages) ->
            if (messages.any { it.id in gone }) {
                threads()[peer] = messages.filterNot { it.id in gone }
            }
        }
        projectId()?.let { disk?.deleteMessages(it, gone) }
    }

    override fun markThreadRead(peerId: String, uptoMillis: Long) {
        projectId()?.let { disk?.markReadUntil(it, peerId, uptoMillis) }
    }

    override fun unreadCounts(): Map<String, Int> {
        val project = projectId() ?: return emptyMap()
        val store = disk ?: return emptyMap()
        val marks = store.readMarks(project)
        return store.lastPerPeer(project).associate { newest ->
            val readUntil = marks[newest.peerId] ?: 0L
            newest.peerId to store.thread(project, newest.peerId)
                .count { !it.isMine && it.createdAt > readUntil }
        }.filterValues { it > 0 }
    }

    override fun newestActivity(): Map<String, Long> {
        val project = projectId() ?: return emptyMap()
        return disk?.lastPerPeer(project).orEmpty().associate { it.peerId to it.createdAt }
    }

    private fun ChatMessage.toRow(peer: String) = ChatMessageRow(
        messageId = id,
        peerId = peer,
        uniqueId = uniqueId,
        senderId = senderId,
        receiverId = receiverId,
        bodyCipher = bodyCipher,
        createdAt = timestampMillis,
        isMine = isMine,
    )

    private fun ChatMessageRow.toMessage() = ChatMessage(
        id = messageId,
        uniqueId = uniqueId,
        senderId = senderId,
        receiverId = receiverId,
        body = decrypt(bodyCipher) ?: bodyCipher,
        timestampMillis = createdAt,
        isMine = isMine,
        bodyCipher = bodyCipher,
    )

    override suspend fun join() {
        val result = bus.emit(USER_JOIN, buildJsonObject {}, JsonElement.serializer())
        ZillitLog.i(TAG) { "joined the chat room: ${result is ZillitResult.Success}" }
    }

    /**
     * Every transition into Connected. The join must ride each one: the
     * first can lose a race with startup, and a reconnect silently drops the
     * room the server put this socket in — either way, no live messages.
     */
    override val connections: Flow<Unit> =
        bus.connectionState
            .map { it.isConnected }
            .distinctUntilChanged()
            .filter { it }
            .map { }

    /**
     * `GET notification/project/all/notifications/{now}/previous` — the
     * production's notification rows, the one call the phones and the web
     * make on open before the socket takes over (Android
     * `BadgesHandler.getAllBadge`, web `getDeviceBadgesApi`). Chat rows are
     * `section=cnc_label, tool=chat_label`; a room's rows say
     * `unit=chat_group_label` and carry `reference_data.chat_room_id`, a
     * DM's carry the sender. Every row also carries `created` (Android
     * `NotificationDataModel.kt:34`) — the newest per conversation is the
     * server's activity stamp for the listing's order.
     */
    override suspend fun conversationBacklog(): ZillitResult<ConversationBacklog> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Notification)}project/all/notifications/" +
                "${kotlin.time.Clock.System.now().toEpochMilliseconds()}/previous",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            options = CallOptions(
                cacheAs = "${config.apiV2(ZillitService.Notification)}project/all/notifications/newest",
            ),
        ).map(::conversationBacklogFrom)

    override suspend fun recentPeers(): ZillitResult<List<String>> {
        val me = myUserId() ?: return ZillitResult.Success(emptyList())
        val project = projectId() ?: return ZillitResult.Success(emptyList())
        return bus.emitForAck(
            USER_LIST,
            buildJsonObject {
                put("user_id", me)
                put("project_id", project)
            },
            JsonElement.serializer(),
        ).map(::recentPeerIds)
    }

    /**
     * `GET private-chat/messages/{peer}/{timestamp}/previous` — the
     * window before [nowMillis], Android's own initial-load shape.
     */
    override suspend fun rooms(): ZillitResult<List<com.zillit.desktop.feature.chat.domain.GroupRoom>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Chat)}chat-room",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map(::roomsFrom)

    /**
     * `POST chat-room` on the chat host — Android's `CREATE_UPDATE_GROUP_URL`
     * (`ApiUrl.kt:408`, `CreateGroupVM.createRoom` `CreateGroupVM.kt:78-119`)
     * with the `ReqGroupModel` body its create page sends
     * (`CreateGroupPage.kt:393-400`). The answer nests the saved room as
     * `data.chat_room` (`GetSingleRoomDetail`, `GetRoomsModel.kt:73-83`).
     */
    /** `DELETE chat-room/{id}` — the web's `deleteChatRoom` (`cncChatApi.js:101-107`). */
    override suspend fun deleteRoom(roomId: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = "${config.apiV2(ZillitService.Chat)}chat-room/$roomId",
            module = RequestModule.ProjectUser,
        ).refuseStatusZero().map { }

    override suspend fun createRoom(
        name: String,
        memberIds: List<String>,
    ): ZillitResult<com.zillit.desktop.feature.chat.domain.GroupRoom> {
        val me = myUserId() ?: return ZillitResult.Failure(
            ZillitError.Unauthorized("no signed-in user"),
        )
        return apiClient.envelope(
            verb = HttpVerb.Post,
            url = "${config.apiV2(ZillitService.Chat)}chat-room",
            module = RequestModule.ProjectUser,
            body = createRoomBody(name, me, memberIds),
        ).refuseStatusZero().flatMap { envelope ->
            createdRoomFrom(envelope.data)
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("no chat_room in the answer"))
        }
    }

    override fun searchMessages(query: String): List<MessageHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        // Pull every disk-held thread into the session map first: the sweep
        // must reach conversations never opened this session, and cached()
        // is what decrypts and memoises them.
        projectId()?.let { project ->
            disk?.lastPerPeer(project).orEmpty().forEach { cached(it.peerId) }
        }
        return searchCachedThreads(threads(), needle)
    }

    override suspend fun history(
        otherUserId: String,
        nowMillis: Long,
        isGroup: Boolean,
    ): ZillitResult<List<ChatMessage>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Chat)}" +
                (if (isGroup) "group-chat" else "private-chat") +
                "/messages/$otherUserId/$nowMillis/previous",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            // Android sends both, the department empty for CNC; a missing
            // parameter is not the same as an empty one to this server.
            queryParameters = mapOf("tool" to "cnc_section", "department_id" to ""),
            // Always the newest window, from "now": one name per thread so
            // the read cache answers it offline (the disk cache does too;
            // this keeps the fetch itself from failing).
            options = CallOptions(
                cacheAs = "${config.apiV2(ZillitService.Chat)}" +
                    (if (isGroup) "group-chat" else "private-chat") + "/messages/$otherUserId/newest",
            ),
        ).map { body ->
            chatRows(body)
                .mapNotNull { readChatMessage(it, myUserId(), decrypt, isGroup) }
                .sortedBy(ChatMessage::timestampMillis)
                .also { rows ->
                    threads()[otherUserId] = rows
                    projectId()?.let { project ->
                        disk?.replaceThread(project, otherUserId, rows.map { it.toRow(otherUserId) })
                    }
                }
        }

    @Suppress("LongParameterList")
    override fun selfId(): String? = myUserId()

    override suspend fun sendReaction(messageId: String, emoji: String): ChatMessage? {
        val ack = bus.emitForAck(
            UPDATE_REACTION,
            reactionEnvelope(messageId, emoji),
            kotlinx.serialization.json.JsonObject.serializer(),
        )
        val payload = (ack as? com.zillit.desktop.core.common.ZillitResult.Success)?.data
            ?: return null
        ackComplaint(payload)?.let { complaint ->
            ZillitLog.w(TAG) { "reaction refused: $complaint" }
            return null
        }
        return readChatMessage(payload, myUserId(), decrypt)?.also(::remember)
    }

    /**
     * [ChatRepository.send] with the quoted parent riding the envelope —
     * Android's `Reply_chat` object under `"reply"`
     * (ChatAndGroupVM.kt:451-459). Same ack path, same idempotent
     * `unique_id`; only the payload differs.
     */
    @Suppress("LongParameterList") // Mirrors send() plus the reference.
    override suspend fun sendWithReply(
        receiverId: String,
        body: String,
        uniqueId: String,
        nowMillis: Long,
        isGroup: Boolean,
        attachment: com.zillit.desktop.feature.chat.domain.ChatAttachment?,
        replyTo: com.zillit.desktop.feature.chat.domain.ChatReplyRef,
    ): ZillitResult<Unit> = sendInternal(
        receiverId, body, uniqueId, nowMillis, isGroup, attachment,
        replyToId = replyTo.messageId,
    )

    override suspend fun send(
        receiverId: String,
        body: String,
        uniqueId: String,
        nowMillis: Long,
        isGroup: Boolean,
        attachment: com.zillit.desktop.feature.chat.domain.ChatAttachment?,
    ): ZillitResult<Unit> = sendInternal(receiverId, body, uniqueId, nowMillis, isGroup, attachment, replyToId = null)

    @Suppress("LongParameterList") // One optional reply object beyond send()'s own list.
    private suspend fun sendInternal(
        receiverId: String,
        body: String,
        uniqueId: String,
        nowMillis: Long,
        isGroup: Boolean,
        attachment: com.zillit.desktop.feature.chat.domain.ChatAttachment?,
        replyToId: String?,
    ): ZillitResult<Unit> {
        val me = myUserId() ?: return ZillitResult.Failure(
            com.zillit.desktop.core.common.ZillitError.Unauthorized("no signed-in user"),
        )
        val project = projectId() ?: return ZillitResult.Failure(
            com.zillit.desktop.core.common.ZillitError.Unauthorized("no open project"),
        )
        // A file may travel captionless; only a body needs the cipher.
        val cipher = if (body.isEmpty()) "" else encrypt(body) ?: return ZillitResult.Failure(
            com.zillit.desktop.core.common.ZillitError.Storage("message encryption failed"),
        )
        // Waits for the ack rather than firing and hoping: this server answers
        // a rejection with `{success:false, message:…}` on the callback, so an
        // emit alone cannot tell a delivered message from a discarded one —
        // which is exactly how a "sent" attachment went missing.
        return bus.emitForAck(
            if (isGroup) GROUP_CHAT else PRIVATE_CHAT,
            sendEnvelope(
                projectId = project,
                uniqueId = uniqueId,
                senderId = me,
                receiverId = receiverId,
                cipherBody = cipher,
                nowMillis = nowMillis,
                isGroup = isGroup,
                attachment = attachment,
                replyToId = replyToId,
            ),
            JsonElement.serializer(),
        ).flatMap { ack ->
            val complaint = ackComplaint(ack)
            if (complaint == null) {
                // The acked send joins the cache like any arrival. Without
                // this, our own messages existed only in the open thread's
                // state: lastMessageOf and newestActivity never learned of
                // them, so the recents shelf kept showing the *peer's* last
                // line — stale preview, stale clock — until the peer spoke
                // again.
                val local = ChatMessage(
                    id = uniqueId,
                    uniqueId = uniqueId,
                    senderId = me,
                    receiverId = receiverId,
                    body = body,
                    timestampMillis = nowMillis,
                    isMine = true,
                    sendState = ChatSendState.Sent,
                    bodyCipher = cipher,
                    isGroup = isGroup,
                    attachment = attachment,
                )
                // The ack carries the row as saved — its `_id` above all.
                // Kept with our own unique id and words (the server's copy
                // may spell neither the way we do), it is what the thread
                // needs to address the line from now on.
                val saved = readChatMessage(ack, me, decrypt, isGroup)
                    ?.takeIf { it.id.isNotBlank() && it.id != uniqueId }
                    ?.let { row ->
                        local.copy(
                            id = row.id,
                            timestampMillis = row.timestampMillis.takeIf { it > 0 } ?: nowMillis,
                        )
                    }
                remember(saved ?: local)
                saved?.let { acked.tryEmit(it) }
                ZillitResult.Success(Unit)
            } else {
                ZillitLog.w(TAG) { "the server refused a message: $complaint" }
                ZillitResult.Failure(ZillitError.Unknown(complaint))
            }
        }
    }
}

/**
 * The search itself, over the session's decrypted threads. Pure, so the case
 * folding and windowing are testable without the impl's socket and cipher
 * seams. The needle is matched case-blind ([String.indexOf]'s ignoreCase);
 * bodies never reach a log line. Attachment-only lines have empty bodies and
 * fall out on their own.
 */
internal fun searchCachedThreads(
    threads: Map<String, List<ChatMessage>>,
    needle: String,
): List<MessageHit> =
    threads.flatMap { (peer, messages) ->
        messages.mapNotNull { message ->
            val at = message.body.indexOf(needle, ignoreCase = true)
            if (at < 0) {
                null
            } else {
                MessageHit(
                    peerId = peer,
                    isGroup = message.isGroup,
                    snippet = snippetAround(message.body, at, needle.length),
                    timestampMillis = message.timestampMillis,
                )
            }
        }
    }.sortedByDescending(MessageHit::timestampMillis).take(MAX_MESSAGE_HITS)

/**
 * A readable window around the match — the matched text with a margin either
 * side, ellipsised where the body continues. The row is a finder, not a
 * reader: the full line lives in the thread it opens.
 */
internal fun snippetAround(body: String, at: Int, matchLength: Int): String {
    val start = (at - SNIPPET_MARGIN).coerceAtLeast(0)
    val end = (at + matchLength + SNIPPET_MARGIN).coerceAtMost(body.length)
    val head = if (start > 0) "…" else ""
    val tail = if (end < body.length) "…" else ""
    return head + body.substring(start, end) + tail
}

/**
 * The service says no with `status: 0` on a 200 — surface its message. The
 * same guard the module's REST-writing neighbours keep
 * (`DistributionRepositoryImpl.refuseStatusZero`).
 */
internal fun ZillitResult<com.zillit.desktop.core.network.ApiEnvelope>.refuseStatusZero():
    ZillitResult<com.zillit.desktop.core.network.ApiEnvelope> = when (this) {
    is ZillitResult.Failure -> this
    is ZillitResult.Success ->
        if (data.status == 0) {
            ZillitResult.Failure(
                ZillitError.Validation(data.message ?: "The server refused the change."),
            )
        } else {
            this
        }
}

private const val TAG = "Chat"

/** Acks arrive one per send; a small buffer covers a burst without a subscriber stalling the ack. */
private const val ACK_BUFFER = 16

/** Characters kept either side of the match in a snippet. */
private const val SNIPPET_MARGIN = 24

/** Enough rows to find the line; a chat-wide grep is not the surface. */
private const val MAX_MESSAGE_HITS = 50
