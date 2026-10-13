package com.zillit.desktop.feature.castboard.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.BoardCast
import com.zillit.desktop.feature.castboard.domain.BoardMessage
import com.zillit.desktop.feature.castboard.domain.BoardRecordDraft
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingRepository
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A board's service — casting or wardrobe, whichever [board] names.
 *
 * Every route takes a **unit id**, not a tool identifier: a board's two tiles
 * are two units of one service, which is why one repository serves both.
 */
class CastingRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val board: BoardTool = BoardTool.Casting,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /**
     * The discussion's cipher — the same AES chat and the boards use. Default
     * identity so a host that has not wired it lists a thread with unreadable
     * bodies rather than crashing.
     */
    private val encrypt: (String) -> String? = { it },
    private val decrypt: (String) -> String? = { it },
    private val myUserId: () -> String? = { null },
    /**
     * The File Cabinet's reading of the list. The web sends nothing extra —
     * `casting-info` already carries the deleted rows — and instead stops
     * filtering them out (`CommonCasting.jsx:642`, `CommonWardrobe.jsx:583`),
     * so the cabinet is the list with its tombstones kept.
     */
    private val fileCabinet: Boolean = false,
) : CastingRepository {

    private val base = config.apiV2(board.service).trimEnd('/')

    /**
     * `GET /v2/{segment}/{segment}-info/{unitId}?status=` — casting's
     * (`castingApi/api.js:17-24`) and wardrobe's
     * (`wardrobeApi/api.js:205-210`) are the same route under two nouns.
     */
    override suspend fun entries(
        unitId: String,
        status: CastingStatus,
    ): ZillitResult<List<CastingEntry>> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = "$base/${board.infoPath}/$unitId",
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("status" to status.wire),
        // Worth keeping for a tunnel: a caster on set reads this list far
        // more often than it changes.
        options = CallOptions(cacheAs = "$base/${board.infoPath}/$unitId/${status.wire}"),
    ).mapData { data ->
        data?.let {
            runCatching {
                json.decodeFromJsonElement(ListSerializer(CastingEntryDto.serializer()), it)
            }.getOrNull()
        }.orEmpty().mapNotNull { it.toEntry(keepDeleted = fileCabinet) }
    }

    /**
     * `PUT /v2/{segment}/{unitId}?castId=&status=`.
     *
     * The body is the row the web sends back with its new status; this client
     * sends the status alone, which is the field the move is actually about.
     */
    override suspend fun moveTo(
        unitId: String,
        entryId: String,
        status: CastingStatus,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Put,
        url = "$base/${board.segment}/$unitId",
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("castId" to entryId, "status" to status.wire),
        body = buildJsonObject { put("status", JsonPrimitive(status.wire)) },
    ).mapData { }

    /**
     * `POST /v2/{segment}/{unitId}` — `createCasting` and `createWardrobe`
     * are one route under two nouns, and the body is each board's own
     * ([createWire]).
     */
    override suspend fun create(unitId: String, draft: BoardRecordDraft): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/${board.segment}/$unitId",
        module = RequestModule.ProjectUser,
        body = createWire(draft, wardrobe = board.showsScenes),
    ).mapData { }

    /**
     * `GET /v2/{segment}/get-casts/{unitId}?page=0&limit=1000` — the
     * characters a costume can file under (`wardrobeApi/api.js:16-23`). The
     * limit is the web's: the picker searches the whole list in the browser.
     */
    override suspend fun casts(unitId: String): ZillitResult<List<BoardCast>> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = "$base/${board.segment}/get-casts/$unitId",
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("page" to 0, "limit" to CAST_PAGE),
    ).mapData { data ->
        (data as? JsonArray).orEmpty().mapNotNull { parseCast(it as? JsonObject) }
    }

    /** `GET /v2/{segment}/chat/{entryId}/{before}/previous?limit=&page=`. */
    override suspend fun messages(
        entryId: String,
        beforeMillis: Long,
        page: Int,
    ): ZillitResult<List<BoardMessage>> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = "$base/${board.segment}/chat/$entryId/$beforeMillis/previous",
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("limit" to MESSAGE_PAGE, "page" to page),
    ).mapData { data ->
        val me = myUserId()
        (data as? JsonArray).orEmpty().mapNotNull { row ->
            parseBoardMessage(row as? JsonObject, decrypt, me)
        }
    }

    /**
     * `POST /v2/{segment}/chat` — the entry's id under `unit_id`, which is
     * what this service calls it (`utils/messageModal.js:99`).
     */
    override suspend fun sendMessage(entryId: String, body: String): ZillitResult<Unit> {
        val cipher = encrypt(body)
            ?: return ZillitResult.Failure(ZillitError.Storage("message encryption failed"))
        return apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/${board.segment}/chat",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("unit_id", JsonPrimitive(entryId))
                put("message", JsonPrimitive(cipher))
                put("message_translation", JsonPrimitive(""))
                put("message_type", JsonPrimitive("text"))
            },
        ).mapData { }
    }

    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(
        transform: (JsonElement?) -> T,
    ): ZillitResult<T> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> if (data.status == 1) {
            ZillitResult.Success(transform(data.data))
        } else {
            ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = data.message))
        }
    }
}

/** The page size the web asks for on this thread. */
private const val MESSAGE_PAGE = 50

/** The cast list is read whole, as the web reads it. */
private const val CAST_PAGE = 1000
