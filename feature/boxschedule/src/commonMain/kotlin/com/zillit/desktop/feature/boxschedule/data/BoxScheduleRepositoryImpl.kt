package com.zillit.desktop.feature.boxschedule.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.boxschedule.domain.BlockDraft
import com.zillit.desktop.feature.boxschedule.domain.BlockWrite
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleRepository
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DateConflict
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdf
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfOptions
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The production diary service (`productionapi`, `/api/v2/box-schedule`).
 *
 * Transcription notes, verified against the web client and the dev host:
 *
 *  - **HTTP 200 + `status:0` is a FAILURE** on this service; a business
 *    rejection never comes as a 4xx. Every write here checks the envelope
 *    status, and the two conflict-prone block writes read `data.conflicts`
 *    off a `status:0` body as well as off a 409;
 *  - all timestamps are epoch MILLISECONDS, never ISO;
 *  - `repeatEndDate` is `0` when there is no repeat, not null;
 *  - `scheduleDayId` is OMITTED (never null) when an event stands alone;
 *  - a recurring edit's `occurrence_date` is the occurrence's `startDateTime`;
 *    scope `all` omits the query params entirely.
 */
@Suppress("TooManyFunctions") // One function per REST route; the service has twenty-one.
class BoxScheduleRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the diary socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : BoxScheduleRepository {

    private val base = "${config.apiV2(ZillitService.PreAndProduction).trimEnd('/')}/box-schedule"

    /**
     * See [BoxScheduleRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project — the web's `sameProject`
     * guard.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(BOX_SCHEDULE_SYNC_EVENTS)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.map { }
            ?: emptyFlow()

    override suspend fun types(): ZillitResult<List<ScheduleType>> =
        get("$base/types").mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseType(it as? JsonObject) }
        }

    override suspend fun createType(title: String, color: String): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/types", buildJsonObject { put("title", title); put("color", color) })

    override suspend fun updateType(id: String, title: String?, color: String?): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/types/$id",
            buildJsonObject {
                title?.let { put("title", it) }
                color?.let { put("color", it) }
            },
        )

    override suspend fun deleteType(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/types/$id", null)

    override suspend fun blocks(): ZillitResult<List<ScheduleBlock>> =
        get("$base/days").mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseBlock(it as? JsonObject) }
        }

    override suspend fun createBlock(draft: BlockDraft, resolve: ConflictAction?): ZillitResult<BlockWrite> =
        blockWrite(HttpVerb.Post, "$base/days", draft, resolve)

    override suspend fun updateBlock(
        id: String,
        draft: BlockDraft,
        resolve: ConflictAction?,
    ): ZillitResult<BlockWrite> = blockWrite(HttpVerb.Put, "$base/days/$id", draft, resolve)

    private suspend fun blockWrite(
        verb: HttpVerb,
        url: String,
        draft: BlockDraft,
        resolve: ConflictAction?,
    ): ZillitResult<BlockWrite> {
        val body = buildJsonObject {
            put("typeId", draft.typeId)
            put("title", draft.title)
            put("calendarDays", buildJsonArray { draft.calendarDays.forEach { add(JsonPrimitive(it)) } })
            put("startDate", draft.startDate)
            put("endDate", draft.endDate)
            put("numberOfDays", draft.numberOfDays)
            put("dateRangeType", if (draft.byDates) "by_dates" else "by_days")
            resolve?.let { put("conflictAction", it.wire) }
        }
        return when (val envelope = apiClient.envelope(verb, url, RequestModule.ProjectUser, body)) {
            is ZillitResult.Success -> {
                val conflicts = parseConflicts(envelope.data.data)
                when {
                    envelope.data.status == 0 && conflicts.isNotEmpty() ->
                        ZillitResult.Success(BlockWrite.Conflicts(conflicts))
                    envelope.data.status == 0 -> ZillitResult.Failure(rejected(envelope.data))
                    else -> ZillitResult.Success(BlockWrite.Saved)
                }
            }
            is ZillitResult.Failure -> {
                // A 409 carries the same `conflicts` list in its body.
                val error = envelope.error
                if (error is ZillitError.Http && error.status == HTTP_CONFLICT) {
                    ZillitResult.Success(BlockWrite.Conflicts(emptyList()))
                } else {
                    envelope
                }
            }
        }
    }

    override suspend fun deleteBlock(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/days/$id", null)

    override suspend fun removeDates(entries: Map<String, List<Long>>): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            "$base/days/remove-dates",
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        entries.forEach { (id, dates) ->
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("dates", buildJsonArray { dates.forEach { add(JsonPrimitive(it)) } })
                                },
                            )
                        }
                    },
                )
            },
        )

    override suspend fun duplicateBlock(sourceId: String, newStartDate: Long): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            "$base/days/duplicate",
            buildJsonObject {
                put("sourceDayId", sourceId)
                put("newStartDate", newStartDate)
            },
        )

    override suspend fun events(scheduleDayId: String?): ZillitResult<List<DiaryEvent>> =
        get("$base/events", scheduleDayId?.let { mapOf("scheduleDayId" to it) }.orEmpty())
            .mapData { data ->
                (data as? JsonArray).items().mapNotNull { parseEvent(it as? JsonObject) }
            }

    override suspend fun noteTypes(): ZillitResult<List<NoteType>> =
        get("$base/events/note-types").mapData { data ->
            (data as? JsonArray).items().mapNotNull { row ->
                val obj = row as? JsonObject ?: return@mapNotNull null
                NoteType(
                    value = obj.text("value"),
                    label = obj.text("label"),
                    hideDistribution = obj.bool("hide_distribution"),
                ).takeIf { it.value.isNotBlank() }
            }
        }

    override suspend fun createEvent(draft: DiaryDraft): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/events", eventWire(draft, create = true))

    override suspend fun updateEvent(
        id: String,
        draft: DiaryDraft,
        scope: RecurrenceScope,
        occurrenceDate: Long?,
    ): ZillitResult<Unit> = write(
        HttpVerb.Put,
        "$base/events/$id",
        eventWire(draft, create = false),
        query = recurrenceQuery("update_type", scope, occurrenceDate),
    )

    override suspend fun deleteEvent(
        id: String,
        scope: RecurrenceScope,
        occurrenceDate: Long?,
    ): ZillitResult<Unit> = write(
        HttpVerb.Delete,
        "$base/events/$id",
        null,
        query = recurrenceQuery("delete_type", scope, occurrenceDate),
    )

    private fun recurrenceQuery(key: String, scope: RecurrenceScope, occurrenceDate: Long?): Map<String, Any?> =
        buildMap {
            scope.wire?.let { wire ->
                put(key, wire)
                occurrenceDate?.let { put("occurrence_date", it) }
            }
        }

    // Transport ------------------------------------------------------------

    override suspend fun pdf(
        options: DiaryPdfOptions,
        action: DiaryPdfAction,
        watermark: String,
    ): ZillitResult<DiaryPdf> {
        // Never from the read cache: the server stages a fresh file per ask,
        // and a kept answer would name a file that may since have gone.
        val envelope = apiClient.envelope(
            HttpVerb.Get,
            "$base/pdf",
            RequestModule.ProjectUser,
            queryParameters = diaryPdfQuery(options, action, watermark),
            options = CallOptions(readCache = false),
        )
        return when (val parsed = envelope.mapData { data -> parseDiaryPdf(data as? JsonObject) }) {
            is ZillitResult.Failure -> parsed
            is ZillitResult.Success -> parsed.data?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("the diary PDF answer named no file"))
        }
    }

    private suspend fun get(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<ApiEnvelope> =
        apiClient.envelope(HttpVerb.Get, url, RequestModule.ProjectUser, queryParameters = query)

    private suspend fun write(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<Unit> =
        when (val envelope = apiClient.envelope(verb, url, RequestModule.ProjectUser, body, query)) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success ->
                if (envelope.data.status == 0) {
                    ZillitResult.Failure(rejected(envelope.data))
                } else {
                    ZillitResult.Success(Unit)
                }
        }

    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> =
        when (this) {
            is ZillitResult.Failure -> this
            is ZillitResult.Success ->
                if (data.status == 0) {
                    ZillitResult.Failure(rejected(data))
                } else {
                    ZillitResult.Success(transform(data.data))
                }
        }

    private fun rejected(envelope: ApiEnvelope): ZillitError =
        ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message ?: "something_went_wrong")

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CONFLICT = 409
    }
}


// Parsers --------------------------------------------------------------

private fun parseType(obj: JsonObject?): ScheduleType? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    return ScheduleType(
        id = id,
        title = obj.text("title"),
        color = obj.text("color"),
        systemDefined = obj.bool("systemDefined"),
    )
}

private fun parseBlock(obj: JsonObject?): ScheduleBlock? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    val typeField = obj["typeId"]
    val typeId = (typeField as? JsonObject)?.text("_id", "id") ?: (typeField as? JsonPrimitive)?.content.orEmpty()
    return ScheduleBlock(
        id = id,
        title = obj.text("title"),
        typeId = typeId,
        typeName = obj.text("typeName").ifBlank { (typeField as? JsonObject)?.text("title").orEmpty() },
        color = obj.text("color").ifBlank { (typeField as? JsonObject)?.text("color").orEmpty() },
        calendarDays = (obj["calendarDays"] as? JsonArray).items().mapNotNull { it.epochMs() },
        startDate = obj.long("startDate") ?: 0,
        endDate = obj.long("endDate") ?: 0,
        numberOfDays = obj.long("numberOfDays")?.toInt() ?: 0,
        dateRangeType = obj.text("dateRangeType"),
    )
}

private fun parseEvent(obj: JsonObject?): DiaryEvent? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    val kind = DiaryKind.fromWire(obj.text("eventType"))
    return DiaryEvent(
        id = id,
        kind = kind,
        title = obj.text("title"),
        body = if (kind == DiaryKind.Note) obj.text("notes") else obj.text("description"),
        date = obj.long("date") ?: 0,
        startDateTime = obj.long("startDateTime") ?: 0,
        endDateTime = obj.long("endDateTime") ?: 0,
        fullDay = obj.bool("fullDay"),
        location = obj.text("location"),
        locationLat = obj.double("locationLat"),
        locationLng = obj.double("locationLng"),
        color = obj.text("color"),
        scheduleDayId = obj.text("scheduleDayId"),
        noteType = obj.text("noteType"),
        repeatStatus = obj.text("repeatStatus"),
        occurrenceId = obj.text("occurrenceId"),
        masterEventId = obj.text("masterEventId"),
        isRecurringInstance = obj.bool("isRecurringInstance"),
        calendarEventId = obj.text("calendarEventId"),
        callType = obj.text("callType"),
        cncCallGroupId = obj.text("cncCallGroupId"),
    )
}

private fun parseConflicts(data: JsonElement?): List<DateConflict> =
    ((data as? JsonObject)?.get("conflicts") as? JsonArray).items().mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        DateConflict(
            date = obj.long("date") ?: return@mapNotNull null,
            existingType = obj.text("existingType"),
            existingColor = obj.text("existingColor"),
            existingTitle = obj.text("existingTitle"),
        )
    }

/** The event/note body — the exact contract, pinned by test. */
internal fun eventWire(draft: DiaryDraft, create: Boolean): JsonObject = buildJsonObject {
    // OMITTED, never null, when unlinked.
    if (draft.scheduleDayId.isNotBlank()) put("scheduleDayId", draft.scheduleDayId)
    put("date", draft.date)
    put("eventType", draft.kind.wire)
    put("title", draft.title.trim())
    put("color", draft.color)
    if (draft.kind == DiaryKind.Note) {
        put("notes", draft.body)
        put("noteType", draft.noteType)
    } else {
        put("description", draft.body)
        put("startDateTime", draft.startDateTime)
        put("endDateTime", draft.endDateTime)
        put("fullDay", draft.fullDay)
        put("location", draft.location)
        // Beside the text, as the web sends them (`CreateEventModal.jsx:540`):
        // null when the place was typed rather than picked, never omitted.
        put("locationLat", draft.locationLat)
        put("locationLng", draft.locationLng)
        put("repeatStatus", draft.repeatStatus)
        put("repeatEndDate", draft.repeatEndDate)
        if (draft.timezone.isNotBlank()) put("timezone", draft.timezone)
        put("reminder", "none")
        // What the event already had, not a blank: the write sends the whole
        // event, so hardcoding "" here erased a call set on another client.
        put("callType", draft.callType)
        if (create) put("createEventInCalendar", draft.createInCalendar)
    }
    // Distribution is deliberately unset: the desktop offers no audience picker
    // yet, and the wire wants empty arrays rather than nulls when it is.
    put("distributeTo", "")
    put("distributeUserIds", buildJsonArray { })
    put("distributeDepartmentIds", buildJsonArray { })
}

// Lenient readers ----------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

private fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

private fun JsonObject.long(vararg names: String): Long? =
    firstOf(*names)?.epochMs()

/** Coordinates arrive as numbers or numeric strings, like every other value here. */
private fun JsonObject.double(vararg names: String): Double? =
    (firstOf(*names) as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonObject.bool(vararg names: String): Boolean =
    (firstOf(*names) as? JsonPrimitive)?.content.equals("true", ignoreCase = true)

/**
 * The web's `parseEpochMs`: numbers or numeric strings; values at or below
 * 1e12 are SECONDS and are scaled up.
 */
private fun JsonElement.epochMs(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    val value = primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: return null
    return if (value in 1..SECONDS_CEILING) value * MS_PER_SECOND else value
}

private fun JsonArray?.items(): List<JsonElement> = this?.toList() ?: emptyList()

private const val SECONDS_CEILING = 1_000_000_000_000L
private const val MS_PER_SECOND = 1_000L

/**
 * The `/pdf` query, as the phones send it: `watermark`, `action` and
 * `format` always, and `includePersonalNotes=false` only when the user
 * chose Without. Omitting it is the server's include-everything default, so
 * the default request is byte-identical to the one sent before the option
 * existed (box-schedule-personal-notes.md §2.1).
 */
internal fun diaryPdfQuery(options: DiaryPdfOptions, action: DiaryPdfAction, watermark: String): Map<String, Any?> =
    buildMap {
        put("watermark", watermark)
        put("action", action.wire)
        put("format", options.layout.wire)
        if (!options.includePersonalNotes) put("includePersonalNotes", "false")
    }

/** The staged file off the attachment shape, or null when the answer names no storage key. */
internal fun parseDiaryPdf(obj: JsonObject?): DiaryPdf? {
    val media = obj?.text("media")?.takeIf { it.isNotBlank() } ?: return null
    return DiaryPdf(
        media = media,
        name = obj.text("name").ifBlank { DEFAULT_PDF_NAME },
        bucket = obj.text("bucket"),
        region = obj.text("region"),
        contentType = obj.text("content_type"),
        contentSubtype = obj.text("content_subtype"),
        thumbnail = obj.text("thumbnail"),
        caption = obj.text("caption"),
        fileSizeBytes = obj.text("file_size").toLongOrNull() ?: 0L,
    )
}

private const val DEFAULT_PDF_NAME = "Box Schedule.pdf"
