package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.ReadReceipt
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.domain.forDisplay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The decryption the notice board needs, narrowed to one method.
 *
 * Mirrors `HeaderCrypto`: this module can decrypt a payload and cannot reach the
 * keychain behind it.
 */
interface NoticeDecryptor {
    fun decryptFromHex(cipherHex: String): ZillitResult<String>

    /** Bodies are encrypted on the way up as well (`uploadMessageOnServer`). */
    fun encryptToHex(plaintext: String): ZillitResult<String>
}

/**
 * Home's units and their notice boards.
 *
 * On the **unit** host, not the core one — `UNIT_BASE_URL` on Android.
 *
 * ## One board engine, several boards
 *
 * The Info and Confidential Info tools are this exact board — same encrypted
 * bodies, comments, receipts, uploads — under a different path segment
 * (`info/`, `confidentialinfo/`) and with a single unit resolved from the
 * production's tool list instead of `home/unit`. [board] and [units] are the
 * two seams; the defaults are Home's.
 */
class HomeFeedRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val decrypt: NoticeDecryptor,
    private val isAdmin: () -> Boolean,
    private val nowMillis: () -> Long = { 0L },
    /** The board's path segment under `/api/v2/` — `home`, `info`, `confidentialinfo`. */
    private val board: String = "home",
    /**
     * Where the tab strip comes from. Null means Home's own `unit/{admin|user}`
     * route; a provider replaces it — Info hands over its one tool unit.
     */
    private val units: (suspend () -> ZillitResult<List<HomeUnit>>)? = null,
    /**
     * The host the board lives on. Home, Info and Confidential Info are on
     * the unit service; the Camera & Sound Report boards are the same routes
     * on the script-notes service (`reports/chat/...`), which is where the
     * web's `reportsApi` points them.
     */
    private val service: ZillitService = ZillitService.Units,
) : HomeFeedRepository {

    private val home get() = "${config.apiV2(service)}$board/"

    /** Home's `home/unit/...` routes stay on the `home` segment for other boards. */
    private val homeUnits get() = "${config.apiV2(ZillitService.Units)}home/"

    /**
     * The tab strip.
     *
     * Admins get `/admin`, which returns units they can manage as well as the
     * ones they are on — the web picks the path the same way.
     */
    override suspend fun loadUnits(): ZillitResult<List<HomeUnit>> =
        units?.invoke() ?: apiClient.request(
            verb = HttpVerb.Get,
            url = "${homeUnits}unit/${if (isAdmin()) "admin" else "user"}",
            serializer = ListSerializer(HomeUnitDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { dtos -> dtos.mapNotNull { it.toDomain() } }

    /**
     * A page of the board, oldest-ward from [beforeMillis].
     *
     * The endpoint pages by timestamp rather than offset: a board people are
     * posting to shifts under an offset, and the desktop scrolls back through
     * long shoots.
     */
    override suspend fun loadNotices(
        unitId: String,
        beforeMillis: Long,
    ): ZillitResult<List<Notice>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${home}chat/$unitId/$beforeMillis/previous",
            serializer = ListSerializer(JsonElement.serializer()),
            module = RequestModule.ProjectUser,
            // The only page ever asked for is the newest one, from "now" — a
            // URL that differs on every load. Kept under one name so the board
            // still shows offline what it showed last time.
            options = CallOptions(cacheAs = "${home}chat/$unitId/newest"),
        ).map { rows ->
            rows.mapNotNull { readNotice(it, ::decryptBody) }.forDisplay()
        }


    /**
     * Decrypts one notice body.
     *
     * A failure yields a placeholder rather than the ciphertext. Android returns
     * the input unchanged on failure, which puts a hex blob on screen where a
     * message should be — indistinguishable, to the reader, from a corrupted
     * post.
     */
    private fun decryptBody(cipherHex: String): String {
        if (cipherHex.isBlank()) return ""

        return when (val result = decrypt.decryptFromHex(cipherHex)) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> {
                // Never the ciphertext, and never the id — just that it happened.
                ZillitLog.w(TAG) { "a notice body did not decrypt: ${result.error.technical}" }
                UNREADABLE
            }
        }
    }

    /**
     * Posts a notice.
     *
     * The body is encrypted before it leaves, matching the read path — a
     * plaintext post would be readable by anyone with database access and would
     * come back as gibberish to every other client, which decrypts on read.
     *
     * [localId] travels as `unique_id` so the server's copy can be matched to
     * the one already on screen.
     */
    override suspend fun postNotice(
        unitId: String,
        text: String,
        localId: String,
        attachment: UploadedNoticeMedia?,
        location: GeoPoint?,
    ): ZillitResult<Notice> = postNotice(unitId, text, localId, attachment, location, replacePrevious = null)

    @Suppress("LongParameterList") // The wire body's fields, one each; a holder would rename, not reduce.
    override suspend fun postNotice(
        unitId: String,
        text: String,
        localId: String,
        attachment: UploadedNoticeMedia?,
        location: GeoPoint?,
        replacePrevious: Boolean?,
    ): ZillitResult<Notice> {
        // Captions are encrypted exactly like message bodies — the web's
        // `generate-message-payload` runs both through `encryptMessage`.
        val encrypted = when (val result = decrypt.encryptToHex(text)) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        return apiClient.request(
            verb = HttpVerb.Post,
            url = "${home}chat",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(
                NewNoticeDto(
                    unitId = unitId,
                    message = encrypted,
                    // Android sends the original alongside a translated body.
                    // With no translation layer here the two are the same text.
                    messageTranslation = encrypted,
                    // A location outranks whatever the attachment's bytes are:
                    // the map image is a PNG, but the post is a place.
                    messageType = when {
                        location != null -> NoticeKind.Location.wire
                        else -> (attachment?.kind ?: NoticeKind.Text).wire
                    },
                    uniqueId = localId,
                    messageGroup = nowMillis(),
                    attachment = attachment?.toDto(),
                    location = location?.let { LocationDto(it.lat, it.long) },
                    replacePreviousChats = replacePrevious,
                ),
            ),
        ).flatMap { row ->
            readNotice(row, ::decryptBody)
                ?.let { ZillitResult.Success(it.copy(localId = localId)) }
                ?: ZillitResult.Failure(
                    ZillitError.Serialization("the notice was posted but the response had no id"),
                )
        }
    }

    /**
     * The same `POST home/chat`, aimed at another unit.
     *
     * Both live clients forward this way — the web's `ForwardMsgModal` calls
     * `createChatData` with a payload rebuilt from the source message, iOS
     * posts through its ordinary send path after the unit picker. The
     * attachment travels by reference: every unit in a production reads the
     * same storage, so the keys stay valid and nothing is re-uploaded.
     */
    override suspend fun forwardNotice(
        unitId: String,
        notice: Notice,
        localId: String,
    ): ZillitResult<Unit> {
        val encrypted = when (val result = decrypt.encryptToHex(notice.body)) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        return apiClient.request(
            verb = HttpVerb.Post,
            url = "${home}chat",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(
                NewNoticeDto(
                    unitId = unitId,
                    message = encrypted,
                    messageTranslation = encrypted,
                    messageType = notice.kind.wire,
                    uniqueId = localId,
                    messageGroup = nowMillis(),
                    attachment = notice.attachment?.toForwardDto(notice.kind),
                    location = notice.location?.let { LocationDto(it.lat, it.long) },
                ),
            ),
        ).map { }
    }

    /**
     * `GET home/chat/watermark/{id}` — `{data: <attachment>}`, the same object
     * shape a post carries (Android `WatermarkResponse.data: AttachmentModel`).
     * The keys point at a per-reader stamped copy in the same bucket, so the
     * ordinary media fetch reads it.
     */
    override suspend fun watermarkedAttachment(noticeId: String): ZillitResult<NoticeAttachment> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${home}chat/watermark/$noticeId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).flatMap { row ->
            (row as? JsonObject)?.toAttachment()
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(
                    ZillitError.Serialization("the watermark response carried no attachment"),
                )
        }

    /** `GET home/chat/readby/{id}` — read receipts live server-side only. */
    override suspend fun readBy(noticeId: String): ZillitResult<ReadBy> = readBy(noticeId, commentId = null)

    /**
     * The same route with `?commentId=` for one reply (Android
     * `ChatAndGroupVM.redaByUserResponseWithUrl`, `ReadByUserPage.kt:130-141`).
     */
    override suspend fun readBy(noticeId: String, commentId: String?): ZillitResult<ReadBy> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${home}chat/readby/$noticeId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = commentId?.let { mapOf("commentId" to it) }.orEmpty(),
        ).map(::readReadBy)

    /**
     * `PUT home/chat/{id}` — the same edit route the text edit uses, with
     * `pinned` alongside. The route validates the full edit body
     * (`unit_chat_message_required` without it — found live), so the post's
     * own words travel too, re-encrypted exactly as an edit re-encrypts them.
     * 1/0 rather than true/false: that is how the rows carry the flag.
     */
    override suspend fun setPinned(
        notice: Notice,
        unitId: String,
        pinned: Boolean,
    ): ZillitResult<Unit> {
        val encrypted = when (val result = decrypt.encryptToHex(notice.body)) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        return apiClient.request(
            verb = HttpVerb.Put,
            url = "${home}chat/${notice.id}",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(
                buildJsonObject {
                    put("message", encrypted)
                    put("message_translation", encrypted)
                    put("unit_id", unitId)
                    put("pinned", if (pinned) 1 else 0)
                },
            ),
        ).map { }
    }

    /**
     * `POST home/unit/{unitId}` `{messageId}` — the web's `notifyUsers`. On the
     * `home` segment for every board: the web's read-by modal calls this route
     * from Info and Confidential Info too.
     */
    override suspend fun notifyUnread(unitId: String, noticeId: String): ZillitResult<Unit> =
        notifyUnread(unitId, noticeId, commentId = null)

    /** iOS passes the reply's id as `commentId` beside `messageId`; so does this. */
    override suspend fun notifyUnread(unitId: String, noticeId: String, commentId: String?): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "${homeUnits}unit/$unitId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(NotifyUnreadDto(messageId = noticeId, commentId = commentId)),
        ).map { }

    /**
     * `POST home/chat/comments/{noticeId}`.
     *
     * The body is message-shaped and encrypted like any post — the web's
     * `generateReplyMessagePayload` runs replies through the same
     * `encryptMessage`. No optimistic copy: the web waits for the response and
     * merges `data.comments`, and so does the caller of this.
     */
    override suspend fun postComment(
        noticeId: String,
        unitId: String,
        text: String,
    ): ZillitResult<List<NoticeComment>> {
        val encrypted = when (val result = decrypt.encryptToHex(text)) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        return apiClient.request(
            verb = HttpVerb.Post,
            url = "${home}chat/comments/$noticeId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(
                NewCommentDto(
                    unitId = unitId,
                    message = encrypted,
                    messageTranslation = encrypted,
                ),
            ),
        ).map { row ->
            (row as? JsonObject)?.readComments(::decryptBody).orEmpty()
        }
    }

    /**
     * `PUT home/chat/comments/{noticeId}/{commentId}`.
     *
     * The body is the web's `EditMessageModal` payload — both fields the same
     * encrypted text here, because this client has no translation layer. The
     * response carries the updated reply in a `comments` array; the web takes
     * its first entry and so does this, preferring an id match when one exists.
     */
    override suspend fun editComment(
        noticeId: String,
        commentId: String,
        text: String,
    ): ZillitResult<NoticeComment?> {
        val encrypted = when (val result = decrypt.encryptToHex(text)) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        return apiClient.request(
            verb = HttpVerb.Put,
            url = "${home}chat/comments/$noticeId/$commentId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(EditCommentDto(message = encrypted, messageTranslation = encrypted)),
        ).map { row ->
            val returned = (row as? JsonObject)?.readComments(::decryptBody).orEmpty()
            returned.firstOrNull { it.id == commentId } ?: returned.firstOrNull()
        }
    }

    override suspend fun deleteComment(
        noticeId: String,
        commentId: String,
    ): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = "${home}chat/comments/$noticeId/$commentId",
            module = RequestModule.ProjectUser,
        ).map { }

    /**
     * `PUT home/chat/{noticeId}` — the same encrypted pair the comment edit
     * sends. The response is the updated message; a response that will not
     * read as one leaves the caller's local copy standing.
     */
    override suspend fun editNotice(noticeId: String, text: String): ZillitResult<Notice?> {
        val encrypted = when (val result = decrypt.encryptToHex(text)) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        return apiClient.request(
            verb = HttpVerb.Put,
            url = "${home}chat/$noticeId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(EditCommentDto(message = encrypted, messageTranslation = encrypted)),
        ).map { row -> readNotice(row, ::decryptBody) }
    }

    /**
     * `PUT home/chat/delete/chats` with a one-entry list.
     *
     * The endpoint is bulk by design — the web's selection mode deletes many at
     * once — but this client deletes one post per gesture, so it sends one.
     */
    override suspend fun deleteNotice(noticeId: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = "${home}chat/delete/chats",
            module = RequestModule.ProjectUser,
            body = jsonBody(DeleteNoticesDto(chatIds = listOf(noticeId))),
        ).map { }

    private companion object {
        const val TAG = "Home"
        const val UNREADABLE = "[This message could not be decrypted]"
    }
}

/** The bulk soft-delete body — `{chatIds: [...]}` on the wire. */
@Serializable
internal data class DeleteNoticesDto(
    @SerialName("chatIds") val chatIds: List<String>,
)

/**
 * The edit body. `message` and `message_translation` both travel because the
 * server stores both; with no translation layer they are the same text.
 */
@Serializable
internal data class EditCommentDto(
    @SerialName("message") val message: String,
    @SerialName("message_translation") val messageTranslation: String,
)

/** A reply's body — `generateReplyMessagePayload` on the web. */
@Serializable
internal data class NewCommentDto(
    @SerialName("unit_id") val unitId: String,
    @SerialName("message") val message: String,
    @SerialName("message_translation") val messageTranslation: String,
    @SerialName("message_type") val messageType: String = "text",
)

/** `HomeChatRequest` — a text post, or a file post with its caption. */
@Serializable
internal data class NewNoticeDto(
    @SerialName("unit_id") val unitId: String,
    @SerialName("message") val message: String,
    @SerialName("message_translation") val messageTranslation: String,
    @SerialName("message_type") val messageType: String = "text",
    @SerialName("unique_id") val uniqueId: String,
    @SerialName("message_group") val messageGroup: Long,
    /** Omitted for text posts — absent and null differ on this endpoint. */
    @SerialName("attachment") val attachment: AttachmentDto? = null,
    /** `{lat, long}` — the wire's spelling, as the web sends it. */
    @SerialName("location") val location: LocationDto? = null,
    /**
     * The call sheet's "New" (true) or "Continuation" (false); omitted
     * elsewhere. camelCase on purpose — Android serialises the Kotlin name
     * (`HomeChatRequest.kt:43`) and iOS spells it the same (`ChatAPIModel.swift:426`).
     */
    @SerialName("replacePreviousChats") val replacePreviousChats: Boolean? = null,
)

@Serializable
internal data class LocationDto(
    @SerialName("lat") val lat: Double,
    @SerialName("long") val long: Double,
)

/** The notify-unread body — the web's `notifyUsers` payload, one key. */
@Serializable
internal data class NotifyUnreadDto(
    @SerialName("messageId") val messageId: String,
    /** Set for a reply's unread list — omitted for a post's (`explicitNulls = false`). */
    @SerialName("commentId") val commentId: String? = null,
)

/**
 * `formatFileStructureForServer` on the web, field for field.
 *
 * `isUploaded: true` looks like client state leaking onto the wire — it is,
 * but both other clients send it and the read path checks it, so omitting it
 * would make our posts the odd ones out.
 */
@Serializable
internal data class AttachmentDto(
    @SerialName("media") val media: String,
    @SerialName("name") val name: String,
    /** Always on the wire — the server rejects an absent key as `required`. */
    @SerialName("thumbnail") val thumbnail: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("content_subtype") val contentSubtype: String,
    @SerialName("caption") val caption: String = "",
    @SerialName("width") val width: Int = 0,
    @SerialName("height") val height: Int = 0,
    @SerialName("duration") val duration: Long = 0,
    @SerialName("bucket") val bucket: String,
    @SerialName("region") val region: String,
    /** A string on the wire — both live clients stringify it, and the server's
     *  type check reads a JSON number as absent. */
    @SerialName("file_size") val fileSize: String,
    @SerialName("isUploaded") val isUploaded: Boolean = true,
)

internal fun UploadedNoticeMedia.toDto() = AttachmentDto(
    media = media,
    name = fileName,
    // The category word — "image", "video", "audio", "document" — NOT the MIME
    // type. Both live clients send it this way (web: `content_type: fileType`;
    // iOS: `assetType.rawValue`), and the server rejects anything else with
    // `unit_chat_attachment_content_type_required`. The MIME type stays on the
    // S3 PUT, where it is an actual Content-Type header.
    contentType = kind.wire,
    // The web sends the extension here; derive it the same way.
    contentSubtype = fileName.substringAfterLast('.', "").lowercase(),
    thumbnail = wireThumbnail(thumbnail, kind, media),
    width = widthPx,
    height = heightPx,
    duration = durationMillis,
    bucket = bucket,
    region = region,
    fileSize = wireFileSize(sizeBytes),
)

/**
 * A forwarded post's attachment: the source's stored object, re-described.
 *
 * The keys are carried as they are — the web spreads `chat.attachment` into the
 * forward payload — but the *describing* fields are re-derived rather than
 * copied, because rows written by earlier builds of this client hold a MIME
 * type in `content_type`, and forwarding one must not replay the rejection the
 * original send would get today.
 */
internal fun NoticeAttachment.toForwardDto(kind: NoticeKind) = AttachmentDto(
    media = media,
    name = fileName,
    // A location post's map image is typed "image" — iOS spells the rule out:
    // `assetType == .location ? .image : assetType`.
    contentType = (if (kind == NoticeKind.Location) NoticeKind.Image else kind).wire,
    contentSubtype = contentSubtype?.takeIf { it.isNotBlank() }
        ?: fileName.substringAfterLast('.', "").lowercase(),
    thumbnail = wireThumbnail(thumbnail, kind, media),
    width = widthPx,
    height = heightPx,
    duration = durationMillis,
    bucket = bucket.orEmpty(),
    region = region.orEmpty(),
    fileSize = wireFileSize(sizeBytes),
)

/**
 * Never absent — the server requires the key. When no separate thumb was
 * uploaded, the web falls back to the media key itself for images, audio and
 * video, and sends "" for documents (`postData.thumbnail` in
 * uploadedFilesOnAWS.js). Same rule here.
 */
private fun wireThumbnail(thumbnail: String?, kind: NoticeKind, media: String): String = when {
    !thumbnail.isNullOrBlank() -> thumbnail
    kind == NoticeKind.Document -> ""
    else -> media
}

/**
 * Stringified, with the web's own fallback for an unknown size — its literal
 * is '1000000' (uploadedFilesOnAWS.js).
 */
private fun wireFileSize(sizeBytes: Long): String =
    (sizeBytes.takeIf { it > 0 } ?: FALLBACK_FILE_SIZE).toString()

private const val FALLBACK_FILE_SIZE = 1_000_000L

/** Below this a timestamp can only be seconds — 10^11 ms is still 1973. */
private const val MILLIS_FLOOR = 100_000_000_000L
private const val MILLIS_PER_SECOND = 1_000L

@Serializable
internal data class HomeUnitDto(
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("view_access") val viewAccess: Boolean? = null,
    @SerialName("posting_access") val postingAccess: Boolean? = null,
    @SerialName("download_access") val downloadAccess: Boolean? = null,
) {
    fun toDomain(): HomeUnit? {
        val resolvedId = unitId ?: id ?: return null
        val name = unitName?.takeIf { it.isNotBlank() } ?: return null
        return HomeUnit(
            id = resolvedId,
            identifier = identifier.orEmpty(),
            unitName = name,
            // Absent means denied, as everywhere else rights are read.
            canView = viewAccess == true,
            canPost = postingAccess == true,
            canDownload = downloadAccess == true,
            enabled = enabled ?: true,
        )
    }
}

/**
 * Reads one post, tolerating fields whose type is not what we expect.
 *
 * Typed decoding of this feed failed against QA on an HTTP 200. A notice board
 * carries posts written by several client versions over the life of a
 * production, so a single row with an unexpected `created` or `pinned` should
 * cost that row, not the entire board — which is what a `List<NoticeDto>`
 * decode does.
 *
 * Top-level and internal so tests exercise the real reader rather than a copy.
 */
internal fun readNotice(row: JsonElement, decryptBody: (String) -> String): Notice? {
    if (row !is JsonObject) return null
    if (row.bool("deleted")) return null

    val id = row.string("_id")?.takeIf { it.isNotBlank() } ?: return null

    return Notice(
        id = id,
        body = decryptBody(row.string("message").orEmpty()),
        authorName = row.string("name")?.takeIf { it.isNotBlank() } ?: "Unknown",
        authorId = row.string("sender"),
        createdAtMillis = row.epochMillis("created"),
        updatedAtMillis = row.epochMillis("updated"),
        isEdited = row.bool("edited"),
        // A timestamp on the wire, not a flag: any non-zero value is pinned.
        isPinned = row.epochMillis("pinned") > 0,
        kind = NoticeKind.of(row.string("message_type")),
        attachment = row.attachmentObject()?.toAttachment(),
        location = (row["location"] as? JsonObject)?.toGeoPoint(),
        comments = row.readComments(decryptBody),
    )
}

/**
 * `{lat, long}` — read leniently because coordinates arrive as numbers from
 * one client and quoted strings from another, and a post pinning the crew
 * park must not lose its pin to a type.
 */
internal fun JsonObject.toGeoPoint(): GeoPoint? {
    val lat = number("lat") ?: return null
    val long = number("long") ?: number("lng") ?: return null
    return GeoPoint(lat, long)
}

private fun JsonObject.number(key: String): Double? =
    primitive(key)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

/**
 * The replies under a post.
 *
 * Deleted rows are dropped here — `deleted` is a timestamp, and the web filters
 * `Number(comment.deleted) > 0` the same way. A reply that will not decode
 * costs itself, not its siblings.
 */
internal fun JsonObject.readComments(decryptBody: (String) -> String): List<NoticeComment> =
    (this["comments"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .filterNot { it.epochMillis("deleted") > 0 }
        .mapNotNull { row -> row.readComment(decryptBody) }

private fun JsonObject.readComment(decryptBody: (String) -> String): NoticeComment? {
    val id = string("_id")?.takeIf { it.isNotBlank() } ?: return null

    return NoticeComment(
        id = id,
        body = decryptBody(string("message").orEmpty()),
        authorId = string("sender"),
        createdAtMillis = epochMillis("created"),
        kind = NoticeKind.of(string("message_type")),
        attachment = attachmentObject()?.toAttachment(),
        location = (this["location"] as? JsonObject)?.toGeoPoint(),
        isEdited = bool("edited"),
    )
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/**
 * Reads the wire's `attachment` object.
 *
 * The earlier reader counted a plural `attachments` array — a field this feed
 * has never carried — which is why every post with media showed as bare text.
 * The singular object below is what the web reads (`msg.attachment`) and what
 * `formatFileStructureForServer` writes.
 */
/**
 * The attachment object, under either of the wire's two spellings.
 *
 * The web writes `attachment`; Android reads and writes `attachments` —
 * plural key, single object — in the home chats. Reading only the singular
 * meant every Android-posted photo in a unit chat arrived as bare text,
 * which surfaced as "thumbnails show in DMs but not in home chats".
 */
internal fun JsonObject.attachmentObject(): JsonObject? =
    (this["attachment"] as? JsonObject) ?: (this["attachments"] as? JsonObject)

internal fun JsonObject.toAttachment(): NoticeAttachment? {
    val media = string("media")?.takeIf { it.isNotBlank() } ?: return null

    return NoticeAttachment(
        media = media,
        fileName = string("name").orEmpty(),
        thumbnail = string("thumbnail"),
        contentType = string("content_type"),
        contentSubtype = string("content_subtype"),
        widthPx = epochMillis("width").toInt(),
        heightPx = epochMillis("height").toInt(),
        durationMillis = epochMillis("duration"),
        bucket = string("bucket"),
        region = string("region"),
        sizeBytes = epochMillis("file_size"),
    )
}

/**
 * `{message_read_by: [...], message_unread_by: [...]}` — each row at least a
 * `userId`, sometimes a name, designation and `read_time` alongside (iOS's
 * `ReadUsers` decodes all four; the web keeps only the id and time and
 * resolves the rest against the crew list).
 */
internal fun readReadBy(row: JsonElement): ReadBy {
    val obj = row as? JsonObject ?: return ReadBy()

    fun receipts(key: String): List<ReadReceipt> =
        (obj[key] as? JsonArray).orEmpty().mapNotNull { element ->
            val receipt = element as? JsonObject ?: return@mapNotNull null
            val userId = receipt.string("userId") ?: return@mapNotNull null
            // Some rows carry seconds where the rest of this API is millis;
            // anything below the floor cannot be a millisecond timestamp.
            val rawTime = receipt.epochMillis("read_time")
            ReadReceipt(
                userId = userId,
                userName = receipt.string("user_name"),
                designation = receipt.string("designation_name"),
                readTimeMillis = if (rawTime in 1 until MILLIS_FLOOR) {
                    rawTime * MILLIS_PER_SECOND
                } else {
                    rawTime
                },
            )
        }

    return ReadBy(read = receipts("message_read_by"), unread = receipts("message_unread_by"))
}

// -- tolerant readers -----------------------------------------------------
//
// The feed is written by several client versions over the life of a
// production, so a field's type is a hope rather than a guarantee.

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun JsonObject.string(key: String): String? =
    primitive(key)?.takeIf { it.isString }?.content

private fun JsonObject.bool(key: String): Boolean =
    primitive(key)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

/** Accepts a number or a numeric string; anything else is 0. */
private fun JsonObject.epochMillis(key: String): Long =
    primitive(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0

