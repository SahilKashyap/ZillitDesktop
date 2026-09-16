@file:Suppress("TooManyFunctions") // One function per wire shape; the file IS the wire.

package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatScope
import com.zillit.desktop.feature.chat.domain.ChatSendState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The CNC wire, as Android speaks it (`ChatSocketHelper`): messages ride the
 * chat socket as `private_chat` emits, history rides REST, and bodies travel
 * AES-encrypted exactly like the notice board's.
 */
val PRIVATE_CHAT = SocketEventName("private_chat")
val GROUP_CHAT = SocketEventName("group_chat")
val USER_JOIN = SocketEventName("user:join")
val USER_LIST = SocketEventName("user:list")
val READ_UNTILL = SocketEventName("private_chat_message_read_untill")
val GROUP_READ_UNTILL = SocketEventName("group-chat:read-untill")
val UPDATE_REACTION = SocketEventName("update_reaction")
val TYPING = SocketEventName("private-chat:typing")

/**
 * A group's typing, which is a different name from a DM's.
 *
 * Android picks the event by the conversation on both halves —
 * `ChatSocketHelper.emitFotChatTyping` chooses the name from `isGroupChat`,
 * and it listens on both. The desktop used the private name for everything,
 * so a group's composer neither told anybody nor heard anyone.
 */
val GROUP_TYPING = SocketEventName("group-chat:typing")

/** An already-delivered message whose text changed. Same row shape as a send. */
val PRIVATE_CHAT_EDIT = SocketEventName("private-chat:edit")
val GROUP_CHAT_EDIT = SocketEventName("group-chat:edit")

// Deletion emits and broadcasts share one name per flavour — the web's
// `cncEmit.deletePrivateChat` sends it, its `listenerSocket` receives it.
val PRIVATE_CHAT_DELETE = SocketEventName("private-chat:delete-messages")
val GROUP_CHAT_DELETE = SocketEventName("group-chat:delete-messages")

/**
 * Marks everything from [peerId] up to [messageId] read — Android's status 3.
 *
 * [status] is the ladder's rung: 3 when the thread is open on screen, 2 when
 * the message merely reached this computer. Every other client sends the 2 —
 * the web on each arrival (`CncObserevers.jsx`), Android likewise — and a
 * client that never does leaves its senders on one tick forever.
 */
fun readUntillEnvelope(
    peerId: String,
    messageId: String,
    projectId: String,
    status: Int = READ_STATUS,
    tool: String = ChatScope.CNC,
): JsonObject =
    buildJsonObject {
        put("user_id", peerId)
        put("_id", messageId)
        put("status", status)
        put("project_id", projectId)
        put("chat_tool", tool)
    }

/**
 * The group counterpart: [roomId] is the conversation, the reader names
 * themselves in `user_id` — Android's `emitForChatRead` group branch exactly.
 * This emit is what clears a room's badge server-side; a client that skips it
 * leaves the room's count standing forever on every other device.
 */
fun groupReadUntillEnvelope(
    roomId: String,
    myUserId: String,
    messageId: String,
    projectId: String,
    status: Int = READ_STATUS,
    tool: String = ChatScope.CNC,
): JsonObject =
    buildJsonObject {
        put("room_id", roomId)
        put("user_id", myUserId)
        put("_id", messageId)
        put("status", status)
        put("project_id", projectId)
        put("chat_tool", tool)
    }

/**
 * A read of MY unread, made on another of my devices — the phone read a
 * thread, this machine's badge for it must fall. The mirror image of
 * [readReceiptFrom]: there the peer read what I sent; here I read, elsewhere.
 * Answers the conversation key (peer id for DMs, room id for groups), null
 * for anything that is not my own read at status 3.
 */
fun selfReadFrom(payload: JsonElement, myUserId: String?): String? {
    val obj = payload as? JsonObject ?: return null
    val detail = (obj["detail"] as? JsonObject) ?: obj
    val status = (detail["status"] as? JsonPrimitive)?.intOrNull
    if (myUserId == null || status != READ_STATUS) return null

    val roomId = detail.str("room_id")
    return when {
        // Group: the reader is named in user_id.
        roomId != null -> roomId.takeIf { detail.str("user_id") == myUserId }
        // DM: the rebroadcast frames the reader as receiver, the read-from
        // peer as sender — receiver == me is my own read from elsewhere.
        else -> detail.str("sender").takeIf { detail.str("receiver") == myUserId }
    }
}

fun typingEnvelope(
    senderId: String,
    receiverId: String,
    started: Boolean,
    tool: String = ChatScope.CNC,
): JsonObject =
    buildJsonObject {
        put("sender", senderId)
        put("receiver", receiverId)
        put("status", if (started) "start" else "end")
        put("chat_tool", tool)
    }

/**
 * A read-untill event: how far the other end has got with *our* messages.
 *
 * Only meaningful when we are the sender — the server tells both ends, and
 * applying the receiver's own copy would tick our screen for their reading.
 */
fun readReceiptFrom(payload: JsonElement, myUserId: String?): ReadReceipt? {
    val obj = payload as? JsonObject ?: return null
    val detail = (obj["detail"] as? JsonObject) ?: obj
    val isOurs = myUserId != null && detail.str("sender") == myUserId
    val peer = detail.str("receiver")
    return if (isOurs && peer != null) {
        ReadReceipt(peer, ChatSendState.ofWire((detail["status"] as? JsonPrimitive)?.intOrNull))
    } else {
        null
    }
}

/**
 * What the server said about an emit.
 *
 * Its acks are `{success, message}` — and a refusal comes back that way
 * rather than as a transport error, so an emit that "worked" can still have
 * been thrown away. `cnc_room_tool_validation` hid here for a whole session.
 */
fun ackComplaint(ack: JsonElement): String? {
    val obj = ack as? JsonObject ?: return null
    val ok = (obj["success"] as? JsonPrimitive)?.booleanOrNull ?: true
    return if (ok) null else obj.str("message") ?: "the server refused the message"
}

/** Everything sent to [peerId] has reached [state]. */
data class ReadReceipt(val peerId: String, val state: ChatSendState)

/**
 * A typing event's conversation and whether it started.
 *
 * The conversation is what the screen matches on, and the two flavours name
 * it in different fields: a DM's is the person who typed, a group's is the
 * room they typed into. A group also broadcasts back to its own author, so
 * our own keystrokes are dropped here rather than shown as somebody else's.
 */
fun typingFrom(payload: JsonElement, isGroup: Boolean = false, myUserId: String? = null): Pair<String, Boolean>? {
    val detail = ((payload as? JsonObject)?.get("detail") as? JsonObject) ?: payload as? JsonObject
    val sender = detail?.str("sender")
    // Our own keystrokes come back from the room we sent them to.
    if (sender == null || (isGroup && sender == myUserId)) return null
    val conversation = if (isGroup) detail.str("receiver") else sender
    return conversation?.let { it to (detail.str("status") == "start") }
}

/**
 * `GET chat-room` rows — `{data:{chat_rooms:[{_id, room_name…}]}}`.
 *
 * Disabled rooms and the calling feature's throwaway `is_random_call_group`
 * rooms are dropped here, as every Android tab drops them before showing a
 * list (`GroupsVM.searchList`, `GroupsVM.kt:171-172`). Absent flags read as
 * Android's defaults: enabled true, random-call false.
 */
fun roomsFrom(body: JsonElement): List<com.zillit.desktop.feature.chat.domain.GroupRoom> {
    val obj = body as? JsonObject ?: return emptyList()
    val rows = ((obj["data"] as? JsonObject)?.get("chat_rooms") ?: obj["chat_rooms"])
        as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return rows.mapNotNull { row ->
        (row as? JsonObject)
            ?.takeIf { (it["enabled"] as? JsonPrimitive)?.booleanOrNull != false }
            ?.takeIf { (it["is_random_call_group"] as? JsonPrimitive)?.booleanOrNull != true }
            ?.let(::roomFrom)
    }
}

private const val READ_STATUS = 3

/** "On this computer" — the rung below read, which nothing here used to send. */
const val DELIVERED_STATUS = 2

/**
 * The `user:list` ack — `{detail:{usersList:[userId…]}}` — read tolerantly:
 * the ids of everyone this user has a DM thread with.
 *
 * Ids only. Android's `getUserListOfChattedUser` (`ChatSocketHelper.kt:832-855`)
 * reads the same array and learns nothing but membership from it — the
 * ordering stamp its list sorts by (`sorting_activity`) comes from elsewhere,
 * which is why the listing's activity is fetched separately here.
 */
fun recentPeerIds(ack: JsonElement): List<String> {
    val obj = ack as? JsonObject ?: return emptyList()
    val list = ((obj["detail"] as? JsonObject)?.get("usersList") ?: obj["usersList"])
        as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
}

/**
 * One message row, from history or the live socket, read tolerantly — a chat
 * accumulates rows written by years of client versions, and one odd row must
 * not blank the thread. The body decrypts when it is cipher-hex and passes
 * through when a legacy row carried plaintext.
 */
fun readChatMessage(
    row: JsonElement,
    myUserId: String?,
    decrypt: (String) -> String?,
    isGroup: Boolean = false,
): ChatMessage? {
    // History rows arrive bare; every socket event wraps the same row in
    // `{success, detail}` (Android reads `chatData.detail.sender`). Reading
    // only the top level dropped every live message silently — found the
    // moment a phone sent one and nothing appeared.
    val outer = row as? JsonObject ?: return null
    val obj = (outer["detail"] as? JsonObject) ?: outer
    val id = obj.str("_id") ?: obj.str("unique_id") ?: return null
    val sender = obj.str("sender") ?: return null
    val receiver = obj.str("receiver").orEmpty()
    val raw = obj.str("message").orEmpty()

    return ChatMessage(
        id = id,
        uniqueId = obj.str("unique_id") ?: id,
        senderId = sender,
        receiverId = receiver,
        body = decrypt(raw) ?: raw,
        replyTo = readReplyRef(obj, decrypt),
        timestampMillis = obj.long("created") ?: obj.long("timestamp") ?: 0L,
        isMine = myUserId != null && sender == myUserId,
        bodyCipher = raw,
        isGroup = isGroup,
        sendState = ChatSendState.ofWire((obj["status"] as? JsonPrimitive)?.intOrNull),
        attachment = readAttachment(obj),
        location = readLocation(obj),
        reactions = readReactions(obj),
        // A timestamp on the wire (Android's `edited: Long?`), tolerated as a
        // literal flag from any client that sends one.
        isEdited = (obj.long("edited") ?: 0L) > 0L ||
            (obj["edited"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

/** The `reactions` rows: one person, one emoji. Blank rows are removals. */
private fun readReactions(obj: JsonObject): List<com.zillit.desktop.feature.chat.domain.ChatReaction> =
    (obj["reactions"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { entry ->
        val row = entry as? JsonObject ?: return@mapNotNull null
        val userId = row.str("user_id") ?: return@mapNotNull null
        val emoji = row.str("reaction")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        com.zillit.desktop.feature.chat.domain.ChatReaction(userId, emoji)
    }

/**
 * One person's reaction on one message; an empty [emoji] takes it back —
 * Android's removal spelling, so every platform agrees what "none" is.
 */
/**
 * The `private-chat:edit` / `group-chat:edit` emit — the web's
 * `editPrivateChat` payload (`MyMessage.jsx:272-276`): the row's `_id` and
 * the new words twice over, `message` and `message_translation`, both
 * encrypted. The web puts a machine translation in `message` when the
 * device and production languages differ; this client writes the same
 * cipher to both, as it does on a send.
 *
 * A group edit also re-derives `message_elements` from the tags in the new
 * body (`cncEmit.js:190-200`); the desktop keeps tags as `@{{id}}` in the
 * body, which every reader resolves itself, so nothing rides beside it.
 */
fun editEnvelope(messageId: String, cipherBody: String): JsonObject = buildJsonObject {
    put("_id", messageId)
    put("message", cipherBody)
    put("message_translation", cipherBody)
}

/**
 * `GET group-chat/readby/{id}`'s answer: `data.message_read_by` rows with
 * `userId`, `read_time`, `delivered`; `data.message_unread_by` rows with
 * `userId`, `delivered` (`ReadByUsers.jsx:60-99`). Read tolerantly — an
 * answer without the wrapper still yields its lists.
 */
fun readByFrom(body: JsonElement): com.zillit.desktop.feature.chat.domain.ReadByReport {
    val outer = body as? JsonObject ?: return com.zillit.desktop.feature.chat.domain.ReadByReport()
    val data = (outer["data"] as? JsonObject) ?: outer
    fun rows(key: String) = (data[key] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val userId = obj.str("userId") ?: obj.str("user_id") ?: return@mapNotNull null
        val delivered = obj["delivered"] as? JsonPrimitive
        com.zillit.desktop.feature.chat.domain.ReadByRow(
            userId = userId,
            readAtMillis = obj.long("read_time"),
            deliveredAtMillis = delivered?.longOrNull,
            // A stamp, a literal `true`, or a "1" — anything the web's `if`
            // would take as delivered.
            isDelivered = (delivered?.longOrNull ?: 0L) != 0L || delivered?.booleanOrNull == true,
        )
    }
    return com.zillit.desktop.feature.chat.domain.ReadByReport(
        read = rows("message_read_by"),
        unread = rows("message_unread_by"),
    )
}

/** What a deletion sends: the rows to drop, scoped to the production. */
fun deleteEnvelope(messageIds: List<String>, projectId: String): JsonObject = buildJsonObject {
    put("message_ids", kotlinx.serialization.json.buildJsonArray {
        messageIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    })
    put("project_id", projectId)
}

/**
 * The ids a deletion answer names, wherever it keeps them.
 *
 * The ack wraps the deleted rows in `detail`; the broadcast to other devices
 * carries the same array, sometimes bare. Each row's `_id` is the message the
 * thread must drop — reading both shapes here means the emitter and the
 * listener cannot disagree about what was deleted.
 */
fun deletedIdsFrom(payload: JsonElement): List<String> {
    val rows = when (payload) {
        is kotlinx.serialization.json.JsonArray -> payload
        is JsonObject -> payload["detail"] as? kotlinx.serialization.json.JsonArray
            ?: payload["data"] as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        else -> return emptyList()
    }
    return rows.mapNotNull { row ->
        (row as? JsonObject)?.let { it.str("_id") ?: it.str("message_id") }
    }
}

fun reactionEnvelope(messageId: String, emoji: String): JsonObject = buildJsonObject {
    put("_id", messageId)
    put("reaction", emoji)
    put("platform", "web")
}

/**
 * The rows, whichever body shape carried them. The live server nests them as
 * `data.chat_records` (Android's `chat_records`); the fallbacks keep older
 * shapes readable.
 */
/**
 * The message's attachment, under any of the wire's three shapes: the CNC's
 * own singular `attachment`, and Android's `attachments` — seen both as a
 * single object and as a one-element array.
 */
private fun readAttachment(
    message: JsonObject,
): com.zillit.desktop.feature.chat.domain.ChatAttachment? {
    val node = message["attachment"]
        ?: (message["attachments"] as? JsonObject)
        ?: (message["attachments"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
    val file = node as? JsonObject ?: return null
    val media = file.str("media") ?: return null
    return com.zillit.desktop.feature.chat.domain.ChatAttachment(
        media = media,
        name = file.str("name") ?: "file",
        contentType = file.str("content_type") ?: "",
        bucket = file.str("bucket") ?: "",
        region = file.str("region") ?: "",
        thumbnail = file.str("thumbnail") ?: "",
        widthPx = file.long("width") ?: 0,
        heightPx = file.long("height") ?: 0,
        durationMillis = file.long("duration") ?: 0,
    )
}

fun chatRows(body: JsonElement): List<JsonElement> = when (body) {
    is kotlinx.serialization.json.JsonArray -> body
    is JsonObject -> {
        val data = body["data"]
        val candidates = listOfNotNull(
            (data as? JsonObject)?.get("chat_records"),
            body["chat_records"],
            data,
            body["messages"],
            body["items"],
        )
        candidates.firstNotNullOfOrNull { it as? kotlinx.serialization.json.JsonArray }
            ?: emptyList()
    }
    else -> emptyList()
}

/**
 * The `private_chat` emit, field for field what Android sends for a plain
 * text DM — `type` "private" (its `isGroupMessage(false)`), `chat_tool`
 * "cnc_section", `deleted` 0, `messageUniqueId` carrying the receiver, the body
 * already encrypted.
 */
@Suppress("LongParameterList")
fun sendEnvelope(
    projectId: String,
    uniqueId: String,
    senderId: String,
    receiverId: String,
    cipherBody: String,
    nowMillis: Long,
    isGroup: Boolean = false,
    attachment: com.zillit.desktop.feature.chat.domain.ChatAttachment? = null,
    /**
     * The quoted parent's server id. A STRING on the way out — the web sends
     * `reply: parentChatId` (`cncUtil.js:197`) and the server expands it into
     * the object every client renders; sending the object back is refused
     * with `cnc_invalid_message_id` (found live, 2026-08-22).
     */
    replyToId: String? = null,
    /**
     * A shared place. Its presence is what makes the message a location, and
     * it wins over any attachment — Android decides the same way and in the
     * same order (`baseUtils/CommonApis.kt:1886`:
     * `if (location != null) "".messageTypeOrContentTypeProvider(true) else …`,
     * whose `true` branch returns the literal `LOCATION`, `"location"` —
     * `mediaHandler/imageeditor/utils/Extension.kt:602-609` and
     * `utils/Constants.kt:713`). See [LOCATION_KIND].
     */
    location: com.zillit.desktop.feature.chat.domain.ChatLocation? = null,
    /** Which surface this message belongs to — C&C unless a tool says otherwise. */
    tool: String = ChatScope.CNC,
    /** The budget tools scope their rooms by department; C&C sends nothing. */
    departmentId: String = "",
    /**
     * Which budget document is being discussed. The web's budget payload
     * always carries the key, empty when there is none
     * (`cnc/cncUtil.js:172`), and this server refuses a budget message
     * without it — the complaint is `cnc_invalid_chat_tool`, which names the
     * wrong field entirely (live, 2026-08-26).
     */
    budgetDocumentId: String = "",
): JsonObject = buildJsonObject {
    put("project_id", projectId)
    put("unique_id", uniqueId)
    put("type", if (isGroup) "group" else "private")
    put("deleted", 0)
    put("messageUniqueId", receiverId)
    put("message_type", if (location != null) LOCATION_KIND else attachment?.kind ?: "text")
    put("chat_tool", tool)
    if (departmentId.isNotBlank()) {
        put("department_id", departmentId)
        put("budget_document_id", budgetDocumentId)
    }
    put("sender", senderId)
    put("receiver", receiverId)
    put("message", cipherBody)
    if (location != null) {
        // `LocationInfo`'s three place fields, top level beside `attachment`.
        // The longitude is `long`, not `lng` (HomeChatRequest.kt:231-232, web
        // `cncUtil.js:314`). Its `imageLink/height/width` describe the map
        // SCREENSHOT the phones upload with the message; this client takes no
        // raster, so it sends the place and leaves those out rather than
        // inventing empties for a picture that does not exist.
        put(
            "location",
            buildJsonObject {
                put("lat", location.lat)
                put("long", location.lng)
                put("address", location.address)
            },
        )
    }
    // The parent's id under "reply" — the server builds the Reply_chat
    // object itself and returns it expanded (web `cncUtil.js:197`).
    if (replyToId != null) put("reply", replyToId)
    if (attachment != null) {
        // Android's `AttachmentModel` in full. The notice board taught this
        // server's habit the hard way: absent is not the same as empty, and it
        // rejects a partial object rather than filling the gaps itself. So
        // every field it declares travels, empty where we have nothing.
        put(
            "attachment",
            buildJsonObject {
                put("media", attachment.media)
                put("name", attachment.name)
                put("content_type", attachment.kind)
                put("content_subtype", attachment.name.substringAfterLast('.', ""))
                put("thumbnail", attachment.thumbnail)
                put("bucket", attachment.bucket)
                put("region", attachment.region)
                put("height", attachment.heightPx)
                put("width", attachment.widthPx)
                put("duration", attachment.durationMillis)
                put("unique_id", uniqueId)
                put("caption", cipherBody)
            },
        )
    }
    put("status", 1)
    put("created", nowMillis)
    put("platform", "web")
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
