package com.zillit.desktop.feature.home.calendar

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The production calendar. */
interface CalendarRepository {
    /** Events overlapping a window, in epoch millis. */
    suspend fun events(fromMillis: Long, toMillis: Long): ZillitResult<List<CalendarEvent>>

    /** One page of this user's received invitations in [status]; pass the
     * previous page's cursor to continue. */
    suspend fun invitations(
        status: InvitationStatus,
        cursor: String? = null,
    ): ZillitResult<InvitationPage>

    /** Accepts an invitation. */
    suspend fun accept(eventId: String, startMillis: Long): ZillitResult<Unit>

    /** Declines an invitation, with the reason the organiser will read. */
    suspend fun decline(
        eventId: String,
        startMillis: Long,
        reason: String? = null,
    ): ZillitResult<Unit>

    /** Deletes an event. Only its creator may. */
    suspend fun delete(eventId: String): ZillitResult<Unit>

    /** Creates an event, or updates [EventDraft.id] when it has one. */
    suspend fun save(draft: EventDraft, times: EventTimes, zone: TimeZone): ZillitResult<Unit>

    /** The server's timezone list, for the form's picker. */
    suspend fun timezones(): ZillitResult<List<TimezoneOption>>
}

/**
 * `GET calendar/events` on the calendar host.
 *
 * Read tolerantly, like the notice board: a calendar accumulates events written
 * by several client versions over a production's life, and one odd row must not
 * blank the month.
 */
class CalendarRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : CalendarRepository {

    /**
     * The web's two shapes for this endpoint, tried in order:
     *
     *  1. `startDateTime`/`endDateTime` — what `RecivedEvents.jsx` sends.
     *  2. **No parameters at all** — what the web's main calendar sends
     *     (`useGetEventsQuery({})`), windowed client-side here as FullCalendar
     *     windows it there.
     *
     * The fallback exists because the windowed form has answered 500 on some
     * environments — `Cast to Number failed for value "NaN" at path
     * "end_datetime"` — which reads as the server casting a query value it
     * failed to parse, not as corrupt rows: the bare fetch on the same
     * environment returns the same events fine. The earlier
     * `epochStartDate`/`epochEndDate` pair (Android's spelling) is gone: this
     * server never recognised it.
     */
    override suspend fun events(fromMillis: Long, toMillis: Long): ZillitResult<List<CalendarEvent>> {
        // iOS's spelling first — `CalendarRequest.fetchEvents` sends
        // snake_case, and it is the one shape verified working against this
        // server. Every camelCase variant (the web API layer's spellings) and
        // the bare call all answered the same 500: the route reads
        // `req.query.start_date`, and anything else leaves it
        // Number(undefined) = NaN — the "Cast to Number failed" of record.
        // The others stay as fallbacks for environments running older routes.
        val variants = listOf(
            mapOf(
                "start_date" to fromMillis,
                "end_date" to toMillis,
                "hide_rejected" to false,
                "showCalendarEventsOnly" to false,
            ),
            mapOf("startDateTime" to fromMillis, "endDateTime" to toMillis),
            emptyMap(),
        )

        var lastFailure: ZillitResult<List<CalendarEvent>>? = null
        for (window in variants) {
            when (val result = fetchEvents(window)) {
                is ZillitResult.Success -> return result.map { events ->
                    events.filter { it.startMillis <= toMillis && it.endMillis >= fromMillis }
                }
                is ZillitResult.Failure -> {
                    ZillitLog.w(TAG) { "events fetch failed with params ${window.keys}" }
                    lastFailure = result
                }
            }
        }
        return checkNotNull(lastFailure)
    }

    private suspend fun fetchEvents(
        window: Map<String, Any?>,
    ): ZillitResult<List<CalendarEvent>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Calendar)}calendar/events",
            serializer = ListSerializer(JsonElement.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = window,
        ).map { rows ->
            rows.mapNotNull(::readEvent).also { events ->
                if (events.size < rows.size) {
                    ZillitLog.w(TAG) { "dropped ${rows.size - events.size} unreadable events" }
                }
            }
        }

    /**
     * `GET calendar/invite?status=…&limit=…` — iOS's `listInvitations`
     * spellings, the ones this server verifiably reads. One page of fifty,
     * the web's own page size; the envelope's cursor rides back so the panel
     * can ask for the next page.
     */
    override suspend fun invitations(
        status: InvitationStatus,
        cursor: String?,
    ): ZillitResult<InvitationPage> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Calendar)}calendar/invite",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = buildMap {
                put("status", status.wire)
                put("limit", INVITE_PAGE)
                if (cursor != null) put("cursor", cursor)
            },
        ).map { body ->
            InvitationPage(
                rows = invitationRows(body).mapNotNull { readInvitation(it, status) },
                nextCursor = invitationCursor(body),
            )
        }

    /** `GET calendar/timezones` — iOS's `getTimezones`, row for row. */
    override suspend fun timezones(): ZillitResult<List<TimezoneOption>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Calendar)}calendar/timezones",
            serializer = ListSerializer(JsonElement.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows ->
            rows.mapNotNull { row ->
                val identifier = (row as? JsonObject)?.str("identifier") ?: return@mapNotNull null
                TimezoneOption(identifier, row.str("value") ?: identifier)
            }
        }

    override suspend fun accept(eventId: String, startMillis: Long) =
        respond("accept", eventId, startMillis)

    override suspend fun decline(eventId: String, startMillis: Long, reason: String?) =
        respond("reject", eventId, startMillis, reason)

    /**
     * `PUT calendar/{accept|reject}/{id}`.
     *
     * The start time goes in the body because a recurring event's invitation is
     * answered per occurrence — the id alone would not say which one.
     */
    private suspend fun respond(
        action: String,
        eventId: String,
        startMillis: Long,
        reason: String? = null,
    ): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = "${config.apiV2(ZillitService.Calendar)}calendar/$action/$eventId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(
                buildJsonObject {
                    put("start_datetime", startMillis)
                    // Only when given — iOS omits the key for an empty reason.
                    reason?.takeIf { it.isNotBlank() }?.let { put("rejection_reason", it) }
                },
            ),
        ).map { }

    override suspend fun delete(eventId: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "${config.apiV2(ZillitService.Calendar)}calendar/$eventId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            // Without the scope the route answers 200 and deletes nothing —
            // found live. iOS always sends it; "all" is the whole event.
            queryParameters = mapOf("delete_type" to "all"),
        ).map { }

    /**
     * `POST calendar` to create, `PUT calendar/edit/{id}` to update.
     *
     * `dayStartDate` repeats the start: the server indexes events by day and
     * derives the bucket from it rather than from `start_datetime`. Both other
     * clients send both, and omitting it files the event under the epoch.
     */
    override suspend fun save(
        draft: EventDraft,
        times: EventTimes,
        zone: TimeZone,
    ): ZillitResult<Unit> {
        val base = config.apiV2(ZillitService.Calendar)

        return apiClient.request(
            verb = if (draft.isEdit) HttpVerb.Put else HttpVerb.Post,
            url = if (draft.isEdit) "${base}calendar/edit/${draft.id}" else "${base}calendar",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(eventBody(draft, times, zone)),
        ).map { }
    }

    private companion object {
        const val TAG = "Calendar"

        /** The web's `pageLimit: 50` in `RecivedEvents.jsx`. */
        const val INVITE_PAGE = 50
    }
}

/** One page of invitations, and the way to the next when the server has more. */
data class InvitationPage(
    val rows: List<EventInvitation>,
    val nextCursor: String? = null,
)

/**
 * The next-page cursor — only when the envelope says there is a next page.
 * A bare-array body has no paging at all.
 */
internal fun invitationCursor(body: JsonElement): String? {
    val envelope = body as? JsonObject ?: return null
    val hasMore = (envelope["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: false
    return if (hasMore) envelope.str("cursor") else null
}

/**
 * The invite list's rows, whichever body shape carried them.
 *
 * Historically a bare array; since the server's paging change, an
 * `{items, cursor, hasMore}` envelope. The server flipped once already, so a
 * reader pinned to either shape breaks on the other — read both.
 */
internal fun invitationRows(body: JsonElement): List<JsonElement> = when (body) {
    is JsonArray -> body
    is JsonObject -> body["items"] as? JsonArray ?: emptyList()
    else -> emptyList()
}

/**
 * Reads one invitation row — iOS's `InvitationWithEvent`.
 *
 * The listed [status] is the fallback when a row omits its own: the server
 * was just asked for rows of exactly that status.
 */
internal fun readInvitation(row: JsonElement, listed: InvitationStatus): EventInvitation? {
    if (row !is JsonObject) return null
    val id = row.str("_id") ?: return null
    val eventId = row.str("event_id") ?: return null

    return EventInvitation(
        id = id,
        eventId = eventId,
        status = row.str("status")?.let(InvitationStatus::of) ?: listed,
        invitedById = row.str("invited_by"),
        rejectionReason = row.str("rejection_reason"),
        event = (row["event"] as? JsonObject)?.let(::readEvent),
    )
}

/**
 * Reads one event.
 *
 * Internal and top-level so tests exercise the real reader — the same reason
 * `readNotice` is.
 */
internal fun readEvent(row: JsonElement): CalendarEvent? {
    if (row !is JsonObject || row.isRemoved()) return null

    val id = row.str("_id") ?: row.str("event_id") ?: return null

    // Without a start there is no cell to draw it in.
    val start = row.millis("start_datetime").takeIf { it > 0 } ?: return null

    return CalendarEvent(
        id = id,
        title = row.str("title")?.takeIf { it.isNotBlank() } ?: "Untitled event",
        startMillis = start,
        endMillis = row.millis("end_datetime"),
        isAllDay = row.bool("full_day"),
        // `location` is a STRING on older rows and the web's `{lat, long}`
        // object on rows written with a picked place — `str` yields null for
        // the object, so the description is the label either way.
        location = row.str("location") ?: row.str("location_description"),
        locationLat = row.point("location")?.first,
        locationLng = row.point("location")?.second,
        description = row.str("description"),
        colorHex = row.str("color"),
        timezone = row.str("timezone"),
        status = row.str("status") ?: row.str("invitation_status"),
        creatorName = row.str("created_by_name") ?: row.str("creator_name"),
        creatorId = row.str("created_by") ?: row.str("creator_id") ?: row.str("user_id"),
        reminderMinutes = row.int("notify"),
        hasCall = row.hasCall(),
        isRecurring = row.isRecurring(),
        recurrence = row.readRecurrence(),
        invitedCount = (row["invited_users"] as? JsonArray)?.size ?: 0,
        audience = row.readAudience(),
        callType = CallType.of(row.str("call_type")),
        cncGroupId = row.str("cnc_group_id").orEmpty(),
        inviteeIds = row.inviteeIds(),
        externalEmails = row.externalEmails(),
    )
}

internal fun JsonObject.prim(key: String) = this[key] as? JsonPrimitive

/** The web's `location: {lat, long}` — note the wire's `long`, not `lng`. */
internal fun JsonObject.point(key: String): Pair<Double, Double>? {
    val obj = this[key] as? JsonObject ?: return null
    val lat = (obj["lat"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
    val lng = (obj["long"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
    return lat to lng
}

internal fun JsonObject.str(key: String): String? =
    prim(key)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull?.toInt() ?: it.content.toIntOrNull() } ?: 0

private fun JsonObject.bool(key: String): Boolean =
    prim(key)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

/** Epoch millis, whether the server sent a number or a numeric string. */
/**
 * Removal wears three spellings across this API's lifetime: a boolean
 * `deleted`, an epoch `deleted`/`deleted_at`, and `_isCancelled` — iOS treats
 * `deletedAt != nil || isCancelled` as gone (`isCancelledEvent`). Any of them
 * means the row must not render (found live: a cancelled event survived
 * every reload showing only `deleted_at`).
 */
private fun JsonObject.isRemoved(): Boolean =
    bool("deleted") || millis("deleted") > 0 || millis("deleted_at") > 0 || bool("_isCancelled")

private fun JsonObject.millis(key: String): Long =
    prim(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0

/**
 * The repeat rule, in the shape the current server reads.
 *
 * Nested under `recurrence_rule`, not the flat `repeat_end_date` /
 * `selectedDays` pair Android sends: the server's own rejection message names
 * `recurrence_rule.selectedDays`, so that is the field it looks at. The web —
 * which is a generation newer than Android here — sends the same.
 */
internal fun Recurrence.toPayload(zone: TimeZone): JsonObject = buildJsonObject {
    put("frequency", frequency.wireValue)
    put(
        "selectedDays",
        buildJsonArray { selectedDays.sorted().forEach { add(JsonPrimitive(it)) } },
    )
    // Null rather than absent for a non-repeating event: the field is part of
    // the object's shape, and omitting it leaves whatever was there before on
    // an edit.
    put("recurrence_end", endMillis(zone)?.let { JsonPrimitive(it) } ?: JsonNull)
}

/**
 * Whether a video call is attached.
 *
 * The web reads this as a `call_type` other than the string "none" — the field
 * is present and set to that when there is no call, rather than absent.
 */
private fun JsonObject.hasCall(): Boolean =
    str("call_type")?.lowercase()?.let { it != "none" && it.isNotBlank() } == true

/** Part of a repeating series, under either of the two names the server uses. */
private fun JsonObject.isRecurring(): Boolean =
    bool("repeat_status") ||
        str("repeat_type")?.lowercase()?.let { it != "none" && it.isNotBlank() } == true

/**
 * The stored repeat rule, so editing an event shows what it actually does.
 *
 * The end date is rendered back to ISO text because that is what the form's
 * field holds — a reopened rule has to read the same as one just typed.
 */
private fun JsonObject.readRecurrence(): Recurrence {
    val rule = this["recurrence_rule"] as? JsonObject ?: return Recurrence()

    return Recurrence(
        frequency = RecurrenceFrequency.of(rule.int("frequency")),
        selectedDays = (rule["selectedDays"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }
            .toSet(),
        endDateText = rule.millis("recurrence_end")
            .takeIf { it > 0 }
            ?.let { millis ->
                Instant.fromEpochMilliseconds(millis)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date
                    .isoText()
            }
            .orEmpty(),
    )
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
