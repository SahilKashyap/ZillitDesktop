@file:Suppress("TooManyFunctions") // One function per server operation — the contract's shape.

package com.zillit.desktop.feature.sides.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.sides.domain.AutoPlan
import com.zillit.desktop.feature.sides.domain.CallSheetRef
import com.zillit.desktop.feature.sides.domain.ManualPlan
import com.zillit.desktop.feature.sides.domain.SceneInfo
import com.zillit.desktop.feature.sides.domain.ScenePage
import com.zillit.desktop.feature.sides.domain.ScenePageDraft
import com.zillit.desktop.feature.sides.domain.ScheduleRef
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesRepository
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import com.zillit.desktop.feature.sides.domain.UploadedCallSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The sides service.
 *
 * Transcription notes, verified against the web client and the dev host:
 *
 *  - routes are `/api/v2/...` — the web's env base carries the `/api`
 *    segment and the client appends `/v2`;
 *  - uploads are S3-first, JSON-second: the file goes to project storage and
 *    the JSON body carries the stored descriptor — never multipart;
 *  - `GET /versions/{id}/scenes` answers RAW JSON with no envelope — it rides
 *    [rawGet] instead of the enveloped client, and the page-scenes route
 *    takes the same path because its shape is not pinned;
 *  - a Download passes `count=true` so the backend bumps `downloadCount`;
 *    a View does not;
 *  - every generation starts `publish:false` and lands in review;
 *  - `GET /sides/{id}` nests the record under the SINGULAR key `sides`, and
 *    `GET /callsheets/{id}` answers the sheet directly in `data`.
 */
class SidesRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Raw authed GET returning the body text — for the envelope-less routes. */
    private val rawGet: suspend (url: String) -> ZillitResult<String>,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
) : SidesRepository {

    override val refreshes: Flow<Unit> =
        bus?.onAny(SIDES_SYNC_EVENTS)?.map { }?.conflate() ?: emptyFlow()

    private val base = config.apiV2(ZillitService.Sides).trimEnd('/')

    private suspend fun call(
        verb: HttpVerb,
        path: String,
        body: JsonObject? = null,
        query: Map<String, Any> = emptyMap(),
    ): ZillitResult<JsonElement> = apiClient.request(
        verb = verb,
        url = "$base$path",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = body,
        queryParameters = query,
    )

    private suspend fun get(path: String, query: Map<String, Any> = emptyMap()) =
        call(HttpVerb.Get, path, query = query)
    private suspend fun post(path: String, body: JsonObject = buildJsonObject { }) = call(HttpVerb.Post, path, body)
    private suspend fun put(path: String, body: JsonObject) = call(HttpVerb.Put, path, body)
    private suspend fun delete(path: String): ZillitResult<Unit> = call(HttpVerb.Delete, path).map { }

    private fun ZillitResult<JsonElement>.url(): ZillitResult<String> =
        map { (it as? JsonObject)?.text("downloadUrl", "download_url").orEmpty() }

    // ── Scripts ─────────────────────────────────────────────────────────

    override suspend fun scripts(limit: Int): ZillitResult<List<Script>> =
        get("/scripts", mapOf("limit" to limit)).map { element ->
            (element as? JsonObject)?.get("scripts").objects().mapNotNull(::parseScript)
        }

    // No active script answers an empty `data`, which the enveloped request
    // would refuse — so this one tolerates a null payload.
    override suspend fun activeScript(): ZillitResult<Script?> = apiClient.requestOrNull(
        verb = HttpVerb.Get,
        url = "$base/scripts/active",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { element -> parseScript((element as? JsonObject)?.obj("script")) }

    override suspend fun scriptsHistory(limit: Int): ZillitResult<List<Script>> =
        get("/scripts/history", mapOf("limit" to limit)).map { element ->
            (element as? JsonObject)?.get("scripts").objects().mapNotNull(::parseScript)
        }

    override suspend fun createScript(title: String, attachment: StoredAttachment?): ZillitResult<Script?> =
        post(
            "/scripts",
            buildJsonObject {
                put("title", title)
                attachment?.let { put("attachment", it.toWire()) }
            },
        ).map { element -> parseScript((element as? JsonObject)?.obj("script")) }

    override suspend fun deleteScript(id: String): ZillitResult<Unit> = delete("/scripts/$id")

    override suspend fun versions(scriptId: String): ZillitResult<List<ScriptVersion>> =
        get("/scripts/$scriptId/versions").map { element ->
            (element as? JsonObject)?.get("versions").objects().mapNotNull(::parseVersion)
        }

    override suspend fun addVersion(
        scriptId: String,
        attachment: StoredAttachment,
        versionLabel: String,
    ): ZillitResult<Unit> = post(
        "/scripts/$scriptId/versions",
        buildJsonObject {
            put("attachment", attachment.toWire())
            put("versionLabel", versionLabel)
        },
    ).map { }

    override suspend fun versionDownloadUrl(versionId: String): ZillitResult<String> =
        get("/versions/$versionId/download").url()

    override suspend fun scenes(versionId: String): ZillitResult<List<SceneInfo>> =
        rawScenes("$base/versions/$versionId/scenes")

    private suspend fun rawScenes(url: String): ZillitResult<List<SceneInfo>> =
        when (val raw = rawGet(url)) {
            is ZillitResult.Failure -> raw
            is ZillitResult.Success -> ZillitResult.Success(parseSceneBody(raw.data))
        }

    // ── Pages ──────────────────────────────────────────────────────────

    override suspend fun scenePages(scriptId: String): ZillitResult<List<ScenePage>> =
        get("/scripts/$scriptId/pages").map { element ->
            (element as? JsonObject)?.firstOf("scenePages", "pages").objects().mapNotNull(::parseScenePage)
        }

    override suspend fun createScenePage(scriptId: String, draft: ScenePageDraft): ZillitResult<Unit> =
        post("/scripts/$scriptId/pages", scenePageWire(draft)).map { }

    override suspend fun updateScenePage(pageId: String, draft: ScenePageDraft): ZillitResult<Unit> =
        put("/pages/$pageId", scenePageWire(draft)).map { }

    override suspend fun deleteScenePage(pageId: String): ZillitResult<Unit> = delete("/pages/$pageId")

    override suspend fun scenePageDownloadUrl(pageId: String): ZillitResult<String> =
        get("/pages/$pageId/download").url()

    override suspend fun scenePageScenes(pageId: String): ZillitResult<List<SceneInfo>> =
        rawScenes("$base/pages/$pageId/scenes")

    // ── Call sheets and schedules ──────────────────────────────────────

    override suspend fun callSheets(limit: Int): ZillitResult<List<CallSheetRef>> =
        get("/callsheets", mapOf("limit" to limit)).map { element ->
            (element as? JsonObject)?.get("callSheets").objects().mapNotNull(::parseCallSheet)
        }

    override suspend fun callSheet(id: String): ZillitResult<CallSheetRef?> =
        get("/callsheets/$id").map { element ->
            val root = element as? JsonObject
            parseCallSheet(root?.obj("callSheet")) ?: parseCallSheet(root)
        }

    override suspend fun uploadCallSheet(
        scriptId: String,
        title: String,
        attachment: StoredAttachment,
    ): ZillitResult<UploadedCallSheet> =
        post("/callsheets", uploadDocWire(scriptId, title, attachment)).map { element ->
            val root = element as? JsonObject
            UploadedCallSheet(
                callSheet = parseCallSheet(root?.obj("callSheet")),
                sceneCount = root?.int("sceneCount", "scene_count") ?: 0,
            )
        }

    override suspend fun deleteCallSheet(id: String): ZillitResult<Unit> = delete("/callsheets/$id")

    override suspend fun schedules(limit: Int): ZillitResult<List<ScheduleRef>> =
        get("/schedules", mapOf("limit" to limit)).map { element ->
            (element as? JsonObject)?.get("schedules").objects().mapNotNull(::parseSchedule)
        }

    override suspend fun uploadSchedule(
        scriptId: String,
        title: String,
        attachment: StoredAttachment,
    ): ZillitResult<ScheduleRef?> =
        post("/schedules", uploadDocWire(scriptId, title, attachment)).map { element ->
            parseSchedule((element as? JsonObject)?.obj("schedule"))
        }

    override suspend fun deleteSchedule(id: String): ZillitResult<Unit> = delete("/schedules/$id")

    override suspend fun scheduleDownloadUrl(id: String): ZillitResult<String> =
        get("/schedules/$id/download").url()

    // ── Sides ──────────────────────────────────────────────────────────

    override suspend fun sides(history: Boolean, limit: Int): ZillitResult<List<SidesRecord>> =
        get(
            "/sides",
            buildMap {
                put("limit", limit)
                if (history) put("history", "true")
            },
        ).map { element -> (element as? JsonObject)?.get("sides").objects().mapNotNull(::parseSides) }

    override suspend fun sidesById(id: String): ZillitResult<SidesRecord> =
        get("/sides/$id").map { element -> element.sidesRecord() ?: SidesRecord(id, "", SidesStatus.Unknown) }

    override suspend fun generate(plan: ManualPlan): ZillitResult<SidesRecord> =
        post("/sides", generateWire(plan)).map { it.sidesRecord() ?: missingRecord(plan.title) }

    override suspend fun autoGenerate(plan: AutoPlan): ZillitResult<SidesRecord> =
        post("/sides", autoGenerateWire(plan)).map { it.sidesRecord() ?: missingRecord(plan.title) }

    /** The detail and the generate answer both nest under the singular `sides`. */
    private fun JsonElement.sidesRecord(): SidesRecord? =
        parseSides((this as? JsonObject)?.obj("sides")) ?: parseSides(this as? JsonObject)

    private fun missingRecord(title: String) = SidesRecord(
        id = "",
        title = title,
        status = SidesStatus.Error,
        error = "The service did not return the new sides",
    )

    override suspend fun downloadUrl(id: String, countDownload: Boolean): ZillitResult<String> =
        get("/sides/$id/download", if (countDownload) mapOf("count" to "true") else emptyMap()).url()

    override suspend fun publish(id: String): ZillitResult<Unit> = post("/sides/$id/publish").map { }

    override suspend fun deleteSides(id: String): ZillitResult<Unit> = delete("/sides/$id")
}
