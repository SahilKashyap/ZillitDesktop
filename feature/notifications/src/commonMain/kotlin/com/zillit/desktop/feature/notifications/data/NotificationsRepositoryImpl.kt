package com.zillit.desktop.feature.notifications.data

import com.zillit.desktop.core.common.MessageElement
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.notifications.domain.ActivityBanner
import com.zillit.desktop.feature.notifications.domain.NotificationDecoder
import com.zillit.desktop.feature.notifications.domain.NotificationTarget
import com.zillit.desktop.feature.notifications.domain.NotificationWireText
import com.zillit.desktop.feature.notifications.domain.NotificationsRepository
import com.zillit.desktop.feature.notifications.domain.ProjectNotification
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The routes, as functions of the two hosts — pinned by test so the wire
 * cannot drift under a refactor.
 *
 * Every one is on the notification service (`NOTIFICATION_BASE_URL` on
 * Android, `config.notificationBase` on the web), under `/api/v2/`.
 */
object NotificationsEndpoints {

    /** The list segment: Android `Constants.GLOBAL_LABEL` (`Constants.kt:1050`). */
    const val GLOBAL_SEGMENT = "global_label"

    /**
     * `GET project/notifications/{cursor}/previous` — Android
     * `ApiUrl.GET_NOTIFICATION_URL` + cursor + `Constants.PREVIOUS_PARAM`
     * (`NotificationVM.kt:46-48`); the web's `DrawerNotification` is the same call.
     */
    fun page(base: String, cursorMillis: Long): String = "${base}project/notifications/$cursorMillis/previous"

    /** The one name the newest page is cached under, whatever "now" was. */
    fun newestPageName(base: String): String = "${base}project/notifications/newest"

    /**
     * `PUT levelmarkread/{segment}/{timestamp}` — Android's REST fallback for
     * `BadgesHandler.markRead(segment, ts, BadgeOptions.Read)` when the
     * socket is down (`BadgesHandler.kt:291-292`, `ApiUrl.MARK_READ_URL`).
     *
     * Two things worth knowing about this route. Android's live path is the
     * socket event `notification:read` (the web's too); this URL is only ever
     * sent when the socket is disconnected. And iOS, which reads over REST
     * every time, uses `markread/{segment}/{timestamp}` for the same act
     * (`NotificationRequest.swift:76-92`) — the `levelmarkread` prefix names
     * the web's `{tool}/{unit}` level-read route. If the dev host answers this
     * with a 404 or `route_not_found`, `markread` is the next thing to try.
     */
    fun markRead(base: String, segment: String, timestampMillis: Long): String =
        "${base}$MARK_READ_ROUTE/$segment/$timestampMillis"

    /**
     * `DELETE markdelete/{segment}/{timestamp}` — Android's REST fallback for
     * `BadgesHandler.markRead(id, ts, BadgeOptions.Delete)`
     * (`BadgesHandler.kt:266-268`, `ApiUrl.DELETE_SINGLE`). The segment *is*
     * the row's `_id`: `NotificationVM.deleteNotification` passes the id where
     * a label normally goes (`NotificationVM.kt:93-96`), and the web's
     * `notification:delete` emit does the same (`DrawerNotification.jsx:282-290`).
     * iOS spells it `markdelete/global_label/{ts}?referenceId={id}` instead.
     */
    fun delete(base: String, notificationId: String, timestampMillis: Long): String =
        "${base}markdelete/$notificationId/$timestampMillis"

    /**
     * `DELETE delete-all` — Android `ApiUrl.DELETE_ALL_URL` (`ApiUrl.kt:647`),
     * the verb from iOS, which is the client that calls it for its "delete all"
     * button (`NotificationRequest.swift:52-54`).
     *
     * Android itself sends the socket event `notification:delete:global` for
     * this (`BadgeOptions.GlobalRead`, `CommonBadgesHandler.kt:8945`); its
     * REST fallback would put `levelmarkread/global_label/{ts}` — a mark-read,
     * which deletes nothing — so that fallback is not the route ported here.
     */
    fun deleteAll(base: String): String = "${base}delete-all"

    private const val MARK_READ_ROUTE = "levelmarkread"
}

/**
 * The notification service.
 *
 * Every call carries the project-user header set (`MODELDATA.WITH_PROJECT_USER_ID`
 * on Android). Reads decode into [ProjectNotification] here, with the
 * dictionary and the cipher resolved through [decoder], so a row on screen is
 * already words.
 */
class NotificationsRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val decoder: NotificationDecoder,
) : NotificationsRepository {

    private val base = config.apiV2(ZillitService.Notification)

    override suspend fun page(beforeMillis: Long, newest: Boolean): ZillitResult<List<ProjectNotification>> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = NotificationsEndpoints.page(base, beforeMillis),
            serializer = ListSerializer(JsonElement.serializer()),
            module = RequestModule.ProjectUser,
            // The first page is asked for from "now" — a different URL every
            // time, the same question every time. Named, so the list still
            // shows offline what it showed last time.
            options = if (newest) CallOptions(cacheAs = NotificationsEndpoints.newestPageName(base)) else CallOptions(),
        ).map { rows -> rows.orEmpty().mapNotNull { readNotification(it as? JsonObject, decoder) } }

    override suspend fun markRead(segment: String, timestampMillis: Long): ZillitResult<Unit> =
        write(HttpVerb.Put, NotificationsEndpoints.markRead(base, segment, timestampMillis))

    override suspend fun delete(notificationId: String, timestampMillis: Long): ZillitResult<Unit> =
        write(HttpVerb.Delete, NotificationsEndpoints.delete(base, notificationId, timestampMillis))

    override suspend fun deleteAll(): ZillitResult<Unit> =
        write(HttpVerb.Delete, NotificationsEndpoints.deleteAll(base))

    /** A bodiless write; the envelope's `status:0` is a refusal even on a 200. */
    private suspend fun write(verb: HttpVerb, url: String): ZillitResult<Unit> =
        when (val envelope = apiClient.envelope(verb, url, RequestModule.ProjectUser)) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success ->
                if (envelope.data.status == 0) {
                    ZillitResult.Failure(rejected(envelope.data))
                } else {
                    ZillitResult.Success(Unit)
                }
        }

    override fun banner(payload: JsonElement?): ActivityBanner? = bannerFrom(payload, decoder)

    private fun rejected(envelope: ApiEnvelope): ZillitError =
        ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message ?: "something_went_wrong")

    private companion object {
        const val HTTP_OK = 200
    }
}

/**
 * One `notification:save` frame to the banner it asks for, or null.
 *
 * Top-level like [readNotification], and for the same reason: the wire
 * contract is the part worth pinning in tests, and it needs no transport.
 *
 * The record's own reasons to stay quiet come first. iOS's socket handler
 * drops `ignore` and `self` (ProjectObserver.swift:6787-6791); the `silent`
 * drop lives on its banner paths instead (AppDelegate.swift:1620 and the
 * notification extension's shouldCountForBadge), and banners are what this
 * feeds — so all three apply. `is_global` is deliberately not consulted: it
 * places a row on the global bell page, while the phones banner every
 * non-silent push regardless of it.
 */
internal fun bannerFrom(payload: JsonElement?, decoder: NotificationDecoder): ActivityBanner? {
    val row = payload?.unwrapRecord() ?: return dropped("no record in the envelope", section = null)
    val section = row.text("section")
    quietReason(row)?.let { return dropped(it, section) }

    val record = readNotification(row, decoder) ?: return dropped("no _id", section)
    return if (record.text.isBlank()) {
        dropped("no words after decoding", section)
    } else {
        ActivityBanner(
            id = record.id,
            section = record.target.section,
            area = record.pathLabel,
            body = record.text,
        )
    }
}

/**
 * Why a record stays quiet, or null to banner it. Every drop is named,
 * because a suppressed banner and a broken pipeline look identical from the
 * outside — that ambiguity has already cost a debugging day. The section is
 * a label key and safe to log; the payload is not (plan §8.4).
 */
private fun quietReason(row: JsonObject): String? {
    if (row.bool("silent")) return "silent"
    val reference = row["reference_data"] as? JsonObject
    if (reference?.bool("ignore") == true) return "flagged ignore"
    if (reference?.bool("self") == true) return "own action"
    return null
}

private fun dropped(reason: String, section: String?): ActivityBanner? {
    val where = section?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    ZillitLog.d("Notifications") { "notification:save stays quiet: $reason$where" }
    return null
}

/**
 * One wire row (`NotificationDataModel`, `model/NotificationDataModel.kt:17-54`)
 * to a [ProjectNotification]. Rows without an `_id` are dropped: there is
 * nothing to delete them by. `unit` is read leniently because it arrives as a
 * string or a number (Android's `StringOrIntAsStringSerializer` on that field).
 */
internal fun readNotification(row: JsonObject?, decoder: NotificationDecoder): ProjectNotification? {
    if (row == null) return null
    val id = row.text("_id")
    if (id.isBlank()) return null
    val reference = row["reference_data"] as? JsonObject
    val path = row.text("path")
    val wire = NotificationWireText(
        message = row.text("message"),
        action = row.text("action"),
        path = path,
        elements = reference.messageElements(),
        encrypted = reference?.bool("encrypted") ?: false,
    )
    return ProjectNotification(
        id = id,
        uuid = row.text("notification_uuid"),
        text = decoder.text(wire),
        pathLabel = decoder.pathLabel(path),
        target = NotificationTarget(
            path = path,
            section = row.text("section"),
            tool = row.text("tool"),
            unit = row.text("unit"),
            action = row.text("action"),
            referenceId = row.text("reference_id"),
        ),
        createdMillis = row.long("created") ?: 0L,
        updatedMillis = row.long("updated") ?: 0L,
        read = row.bool("message_read"),
        // Strictly `true`, as Android reads it: the route answers every
        // section's rows (the web asks it for `?segment=global_label`
        // instead) and only the ones the server flags belong on this list.
        isGlobal = row.bool("is_global"),
    )
}

/**
 * `reference_data.messageElements` — `[{search, replacer}]`, the replacer a
 * string or a number (`PathElements`, `NotificationDataModel.kt:119-123`).
 */
internal fun JsonObject?.messageElements(): List<MessageElement> =
    ((this?.get("messageElements") as? JsonArray)?.toList() ?: emptyList()).mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val search = obj.text("search").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        MessageElement(search = search, replacer = obj.text("replacer"))
    }

// Lenient readers ----------------------------------------------------------

private fun JsonObject.field(name: String): JsonElement? = this[name]?.takeIf { it !is JsonNull }

internal fun JsonObject.text(name: String): String = (field(name) as? JsonPrimitive)?.content.orEmpty()

internal fun JsonObject.long(name: String): Long? =
    (field(name) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonObject.bool(name: String): Boolean =
    (field(name) as? JsonPrimitive)?.content.equals("true", ignoreCase = true)

/**
 * Digs the record out of the socket envelope.
 *
 * The same tolerance as Home's `unwrapData` (`HomeRealtimeSource.kt`), which
 * cannot be shared across the module boundary: the record itself, `{"data":
 * {...}}`, the data as a JSON **string** (the server does send that), or an
 * array's first record. Generosity here is cheaper than a banner that simply
 * never appears.
 */
private fun JsonElement.unwrapRecord(): JsonObject? {
    // A record that arrived bare wins outright — before preferring "data",
    // which a record is free to carry as an ordinary field. (Home's helper
    // has the opposite order; for board events the envelope always wraps.)
    if (this is JsonObject && containsKey("_id")) return this

    val inner = (this as? JsonObject)?.get("data") ?: this
    return when (inner) {
        is JsonObject -> if (inner.containsKey("_id")) inner else inner["data"]?.unwrapRecord()
        is JsonArray -> inner.firstOrNull()?.unwrapRecord()
        is JsonPrimitive ->
            if (!inner.isString) null
            else runCatching { Json.parseToJsonElement(inner.content) }.getOrNull()?.unwrapRecord()
        else -> null
    }
}
