package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
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
}

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
) : ChatRepository {

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

    override val incoming: Flow<ChatMessage> =
        kotlinx.coroutines.flow.merge(
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

    override suspend fun send(
        receiverId: String,
        body: String,
        uniqueId: String,
        nowMillis: Long,
        isGroup: Boolean,
        attachment: com.zillit.desktop.feature.chat.domain.ChatAttachment?,
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
                // again. The server's later echo, if any, replaces this row
                // by unique id.
                remember(
                    ChatMessage(
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
                    ),
                )
                ZillitResult.Success(Unit)
            } else {
                ZillitLog.w(TAG) { "the server refused a message: $complaint" }
                ZillitResult.Failure(ZillitError.Unknown(complaint))
            }
        }
    }
}

private const val TAG = "Chat"
