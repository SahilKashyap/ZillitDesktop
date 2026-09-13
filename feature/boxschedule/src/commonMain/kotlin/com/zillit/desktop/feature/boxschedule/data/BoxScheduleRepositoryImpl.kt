package com.zillit.desktop.feature.boxschedule.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitRealtimeEndpoint
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
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdf
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfOptions
import com.zillit.desktop.feature.boxschedule.domain.DiaryRevision
import com.zillit.desktop.feature.boxschedule.domain.HistoryEntry
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import com.zillit.desktop.feature.boxschedule.domain.PERSONAL_NOTE_LABEL
import com.zillit.desktop.feature.boxschedule.domain.PERSONAL_NOTE_TYPE
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType
import com.zillit.desktop.feature.boxschedule.domain.UserPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 *    scope `all` omits the query params entirely on an update, and sends
 *    `delete_type=all` alone on a delete.
 */
@Suppress("TooManyFunctions") // One function per REST route; the service has twenty-five.
class BoxScheduleRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the diary socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : BoxScheduleRepository {

    private val api = config.apiV2(ZillitService.PreAndProduction).trimEnd('/')
    private val base = "$api/box-schedule"
    private val presetBase = "$api/user-preset"

    /** The web app's origin — a share link is a path on it. */
    private val webOrigin = config.realtime[ZillitRealtimeEndpoint.Call]?.trimEnd('/').orEmpty()

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

    override suspend fun createType(title: String, color: String): ZillitResult<String?> =
        when (
            val envelope = apiClient.envelope(
                HttpVerb.Post,
                "$base/types",
                RequestModule.ProjectUser,
                buildJsonObject { put("title", title); put("color", color) },
            )
        ) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success ->
                if (envelope.data.status == 0) {
                    ZillitResult.Failure(rejected(envelope.data))
                } else {
                    val id = (envelope.data.data as? JsonObject)?.text("_id", "id")
                    ZillitResult.Success(id?.takeIf { it.isNotBlank() })
                }
        }

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
                // A 409's body is not carried this far; the caller names the
                // colliding dates from the schedule it already holds.
                val error = envelope.error
                if (error is ZillitError.Http && error.status == HTTP_CONFLICT) {
                    ZillitResult.Success(BlockWrite.Conflicts(emptyList()))
                } else {
                    envelope
                }
            }
        }
    }

    override suspend fun renameBlock(id: String, title: String): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/days/$id", buildJsonObject { put("title", title) })

    override suspend fun changeSingleDay(
        id: String,
        date: Long,
        typeId: String,
        action: ConflictAction,
    ): ZillitResult<Unit> = write(
        HttpVerb.Put,
        "$base/days/$id/single-date",
        buildJsonObject {
            put("date", date)
            put("typeId", typeId)
            put("action", action.wire)
        },
    )

    override suspend fun deleteBlock(id: String): ZillitResult<String?> =
        writeWithMessage(HttpVerb.Delete, "$base/days/$id", null)

    override suspend fun removeDates(entries: Map<String, List<Long>>): ZillitResult<String?> =
        writeWithMessage(
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

    /**
     * The note kinds, with the Personal Note's label forced — the web's
     * `normalizeNoteTypeOptions`: the server may still send "Crew Start".
     */
    override suspend fun noteTypes(): ZillitResult<List<NoteType>> =
        get("$base/events/note-types").mapData { data ->
            val rows = (data as? JsonArray) ?: ((data as? JsonObject)?.get("data") as? JsonArray)
            rows.items().mapNotNull { row ->
                val obj = row as? JsonObject ?: return@mapNotNull null
                val value = obj.text("value")
                NoteType(
                    value = value,
                    label = if (value == PERSONAL_NOTE_TYPE) PERSONAL_NOTE_LABEL else obj.text("label"),
                    hideDistribution = obj.text("hide_distribution").equals("true", ignoreCase = true),
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
        query = updateQuery(scope, occurrenceDate),
    )

    override suspend fun deleteEvent(
        id: String,
        scope: RecurrenceScope?,
        occurrenceDate: Long?,
    ): ZillitResult<Unit> = write(
        HttpVerb.Delete,
        "$base/events/$id",
        null,
        query = deleteQuery(scope, occurrenceDate),
    )

    override suspend fun history(): ZillitResult<List<HistoryEntry>> =
        apiClient.envelope(
            HttpVerb.Get,
            "$base/activity-log",
            RequestModule.ProjectUser,
            queryParameters = mapOf("limit" to HISTORY_PAGE, "page" to 0),
            options = CallOptions(readCache = false),
        ).mapData(::parseHistory)

    override suspend fun revisions(): ZillitResult<List<DiaryRevision>> =
        apiClient.envelope(
            HttpVerb.Get,
            "$base/revisions",
            RequestModule.ProjectUser,
            options = CallOptions(readCache = false),
        ).mapData(::parseRevisions)

    override suspend fun presets(): ZillitResult<List<UserPreset>> =
        get(presetBase).mapData(::parsePresets)

    override suspend fun savePreset(presetId: String?, name: String, userIds: List<String>): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            presetBase,
            buildJsonObject {
                put("preset_name", name.trim())
                put("user_ids", buildJsonArray { userIds.forEach { add(JsonPrimitive(it)) } })
                presetId?.takeIf { it.isNotBlank() }?.let { put("preset_id", it) }
            },
        )

    override suspend fun deletePreset(presetId: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$presetBase/$presetId", null)

    override suspend fun shareLink(): ZillitResult<String> =
        when (
            val envelope = apiClient.envelope(
                HttpVerb.Post,
                "$base/share/generate-link",
                RequestModule.ProjectUser,
                buildJsonObject { },
            )
        ) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success -> {
                val path = (envelope.data.data as? JsonObject)?.text("shareUrl").orEmpty()
                when {
                    envelope.data.status == 0 -> ZillitResult.Failure(rejected(envelope.data))
                    path.isBlank() -> ZillitResult.Failure(ZillitError.Serialization("the share answer named no link"))
                    path.startsWith("http") -> ZillitResult.Success(path)
                    else -> ZillitResult.Success("$webOrigin/${path.trimStart('/')}")
                }
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
    ): ZillitResult<Unit> = when (val result = writeWithMessage(verb, url, body, query)) {
        is ZillitResult.Failure -> result
        is ZillitResult.Success -> ZillitResult.Success(Unit)
    }

    /** A write whose success message the page shows — the web's `apiMessage`. */
    private suspend fun writeWithMessage(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<String?> =
        when (val envelope = apiClient.envelope(verb, url, RequestModule.ProjectUser, body, query)) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success ->
                if (envelope.data.status == 0) {
                    ZillitResult.Failure(rejected(envelope.data))
                } else {
                    val nested = (envelope.data.data as? JsonObject)?.text("message")
                    ZillitResult.Success(nested?.takeIf { it.isNotBlank() } ?: envelope.data.message)
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
        ZillitError.Http(
            status = HTTP_OK,
            serverMessage = envelope.message ?: "something_went_wrong",
            messageElements = envelope.messageElements.orEmpty(),
        )

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CONFLICT = 409
        const val HISTORY_PAGE = 200
    }
}

/** An update: `update_type` + `occurrence_date` for one occurrence or the tail; nothing for the series. */
internal fun updateQuery(scope: RecurrenceScope, occurrenceDate: Long?): Map<String, Any?> = buildMap {
    if (scope != RecurrenceScope.All) {
        put("update_type", scope.wire)
        occurrenceDate?.let { put("occurrence_date", it) }
    }
}

/** A delete: nothing for a plain row; `delete_type`, plus the occurrence for anything short of the series. */
internal fun deleteQuery(scope: RecurrenceScope?, occurrenceDate: Long?): Map<String, Any?> = buildMap {
    if (scope != null) {
        put("delete_type", scope.wire)
        if (scope != RecurrenceScope.All) occurrenceDate?.let { put("occurrence_date", it) }
    }
}
