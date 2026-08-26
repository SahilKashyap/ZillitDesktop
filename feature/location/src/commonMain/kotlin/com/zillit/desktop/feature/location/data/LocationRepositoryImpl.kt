package com.zillit.desktop.feature.location.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.feature.location.domain.LocationMessage
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationRepository
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.MediaAttachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * The location service (`locationapi`, `/api/v2/location`).
 *
 * Transcription notes, from the web's `locationApi/api.js` and
 * `createModal`: HTTP 200 + `status:0` is a failure; `location-info` is
 * camelCase (`location`, `sceneNumbers`, `lastUpdate`, `delete`) while
 * records are snake_case (`file_name`, `scene_number`, `created`,
 * `deleted`); blank query filters are dropped, not sent; the list pages by
 * `timestamp` + `nextPrevious` and returns deleted rows on `next` (filtered
 * here); the location name is title-cased word by word on create/edit
 * (`spaceCapitalizeWords`) but not on move; the move body carries the OLD
 * folder as `file_name`/`scene_number` and the new as `new_*`.
 */
class LocationRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val newUniqueId: () -> String,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
    /**
     * The discussion's cipher — the same AES chat and the boards use. The
     * seams default to identity so a host that has not wired them lists a
     * thread with unreadable bodies rather than crashing.
     */
    private val encrypt: (String) -> String? = { it },
    private val decrypt: (String) -> String? = { it },
    private val myUserId: () -> String? = { null },
) : LocationRepository {

    private val base = config.apiV2(ZillitService.Location).trimEnd('/') + "/location"

    /**
     * See [LocationRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project, as every web handler does.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(LOCATION_SYNC_EVENTS)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.map { }
            ?: emptyFlow()

    override suspend fun info(status: LocationStatus): ZillitResult<List<LocationInfo>> =
        get("$base/location-info", mapOf("status" to status.wire)).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseInfo(it as? JsonObject) }.filter { !it.deleted }
        }

    override suspend fun media(
        status: LocationStatus,
        location: String?,
        sceneNumber: String?,
        episode: String?,
        beforeMs: Long,
        next: Boolean,
    ): ZillitResult<List<LocationMedia>> {
        val query = buildMap<String, Any?> {
            put("page", "0")
            put("status", status.wire)
            put("limit", PAGE.toString())
            put("timestamp", beforeMs.toString())
            put("nextPrevious", if (next) "next" else "previous")
            location?.takeIf { it.isNotBlank() }?.let { put("file_name", it) }
            sceneNumber?.takeIf { it.isNotBlank() }?.let { put("scene_number", it) }
            episode?.takeIf { it.isNotBlank() }?.let { put("episode", it) }
        }
        return get(base, query).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseMedia(it as? JsonObject) }.filter { !it.deleted }
        }
    }

    override suspend fun create(
        draft: LocationDraft,
        attachment: MediaAttachment?,
        linkPreview: MediaAttachment?,
    ): ZillitResult<LocationMedia?> =
        apiClient.envelope(
            HttpVerb.Post,
            base,
            RequestModule.ProjectUser,
            createWire(draft, attachment, linkPreview, newUniqueId()),
        ).mapData { data ->
            // The web reads `response.data[0]`.
            val first = (data as? JsonArray)?.firstOrNull() ?: data
            parseMedia(first as? JsonObject)
        }

    override suspend fun update(id: String, draft: LocationDraft): ZillitResult<Unit> =
        write(HttpVerb.Put, base, editWire(draft, id))

    override suspend fun delete(ids: List<String>, status: LocationStatus): ZillitResult<Unit> =
        write(
            HttpVerb.Delete,
            base,
            buildJsonObject {
                put("locationIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put("status", status.wire)
            },
        )

    override suspend fun move(records: List<LocationMedia>, from: LocationStatus,
        to: LocationStatus): ZillitResult<Unit> {
        // The web moves one folder at a time; records from several folders
        // go in as many calls, each naming its own old folder.
        val groups = records.groupBy { Triple(it.location, it.sceneNumber, it.episodes.firstOrNull().orEmpty()) }
        for ((_, group) in groups) {
            val sample = group.first()
            val result = apiClient.envelope(
                HttpVerb.Post,
                "$base/move",
                RequestModule.ProjectUser,
                moveWire(group.map { it.id }, sample, to),
                queryParameters = mapOf("status" to from.wire),
            ).mapData { }
            if (result is ZillitResult.Failure) return result
        }
        return ZillitResult.Success(Unit)
    }

    /**
     * `GET /v2/location/chat/{recordId}/{before}/previous?limit=50&page=`
     * (called from `CastingChat.jsx:122-146`).
     *
     * The path parameter the web calls `unitId` is the *record's* own id, not
     * a production unit's — a name that has cost more than one reader an
     * afternoon.
     */
    override suspend fun messages(
        recordId: String,
        beforeMillis: Long,
        page: Int,
    ): ZillitResult<List<LocationMessage>> = get(
        "$base/chat/$recordId/$beforeMillis/previous",
        mapOf("limit" to MESSAGE_PAGE, "page" to page),
    ).mapData { data ->
        val me = myUserId()
        ((data as? JsonArray) ?: emptyList()).mapNotNull { row ->
            parseLocationMessage(row as? JsonObject, decrypt, me)
        }
    }

    /**
     * `POST /v2/location/chat` — the body encrypted, and the record's id
     * under `unit_id`, which is what this service calls it
     * (`utils/messageModal.js:99`).
     */
    override suspend fun sendMessage(recordId: String, body: String): ZillitResult<Unit> {
        val cipher = encrypt(body)
            ?: return ZillitResult.Failure(ZillitError.Storage("message encryption failed"))
        return apiClient.envelope(
            HttpVerb.Post,
            "$base/chat",
            RequestModule.ProjectUser,
            buildJsonObject {
                put("unit_id", recordId)
                put("message", cipher)
                put("message_translation", "")
                put("message_type", "text")
                put("unique_id", newUniqueId())
            },
        ).mapData { }
    }

    override suspend fun pdf(ids: List<String>, includeDetails: Boolean): ZillitResult<MediaAttachment> =
        apiClient.envelope(
            HttpVerb.Post,
            "$base/locationpdf",
            RequestModule.ProjectUser,
            buildJsonObject {
                put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put("include_details", includeDetails)
            },
        ).flatMapData { data ->
            parseAttachment(data as? JsonObject)
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Unknown("pdf answered no file"))
        }

    // Transport ------------------------------------------------------------

    private suspend fun get(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<ApiEnvelope> =
        apiClient.envelope(HttpVerb.Get, url, RequestModule.ProjectUser, queryParameters = query)

    private suspend fun write(verb: HttpVerb, url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(verb, url, RequestModule.ProjectUser, body).mapData { }

    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> =
        flatMapData { ZillitResult.Success(transform(it)) }

    private inline fun <T> ZillitResult<ApiEnvelope>.flatMapData(
        transform: (JsonElement?) -> ZillitResult<T>,
    ): ZillitResult<T> =
        when (this) {
            is ZillitResult.Failure -> this
            is ZillitResult.Success ->
                if (data.status == 0) {
                    ZillitResult.Failure(
                        ZillitError.Http(status = HTTP_OK, serverMessage = data.message ?: "something_went_wrong"),
                    )
                } else {
                    transform(data.data)
                }
        }

    private companion object {
        const val HTTP_OK = 200
        const val PAGE = 50
    }
}

private fun JsonArray?.items(): List<JsonElement> = this?.toList() ?: emptyList()

/** The page size the web asks for on this thread. */
private const val MESSAGE_PAGE = 50
