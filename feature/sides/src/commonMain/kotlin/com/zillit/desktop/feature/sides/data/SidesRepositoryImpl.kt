package com.zillit.desktop.feature.sides.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.sides.domain.GeneratePlan
import com.zillit.desktop.feature.sides.domain.SceneInfo
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesRepository
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import com.zillit.desktop.core.socket.SocketEventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The sides service (`sidesapi`).
 *
 * Transcription notes, verified against the web client and the dev host:
 *
 *  - routes are `/api/v2/...` — the web's env base carries the `/api`
 *    segment and the client appends `/v2`;
 *  - uploads are S3-first, JSON-second: the file goes to project storage and
 *    the JSON body carries the stored descriptor — never multipart;
 *  - `GET /versions/{id}/scenes` answers RAW JSON with no envelope, the one
 *    such route on this service — it rides [rawScenes] instead of the
 *    enveloped client;
 *  - a Download passes `count=true` so the backend bumps `downloadCount`;
 *    a View does not;
 *  - every generation starts `publish:false` and lands in review.
 */
class SidesRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Raw authed GET returning the body text — for the envelope-less route. */
    private val rawScenes: suspend (url: String) -> ZillitResult<String>,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
) : SidesRepository {

    /** See [SidesRepository.refreshes]. */
    override val refreshes: Flow<Unit> =
        bus?.onAny(SIDES_SYNC_EVENTS)?.map { }?.conflate() ?: emptyFlow()

    private val base = config.apiV2(ZillitService.Sides).trimEnd('/')

    override suspend fun scripts(limit: Int): ZillitResult<List<Script>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/scripts",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("limit" to limit),
    ).map { element ->
        ((element as? JsonObject)?.get("scripts") as? JsonArray)
            .items().mapNotNull { parseScript(it as? JsonObject) }
    }

    override suspend fun createScript(
        title: String,
        attachment: StoredAttachment?,
    ): ZillitResult<Script?> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/scripts",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("title", title)
            attachment?.let { put("attachment", it.toWire()) }
        },
    ).map { element ->
        parseScript((element as? JsonObject)?.get("script") as? JsonObject)
    }

    override suspend fun deleteScript(id: String): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Delete,
        url = "$base/scripts/$id",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun versions(scriptId: String): ZillitResult<List<ScriptVersion>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/scripts/$scriptId/versions",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { element ->
            ((element as? JsonObject)?.get("versions") as? JsonArray)
                .items().mapNotNull { parseVersion(it as? JsonObject) }
        }

    override suspend fun addVersion(
        scriptId: String,
        attachment: StoredAttachment,
        versionLabel: String,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/scripts/$scriptId/versions",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("attachment", attachment.toWire())
            put("versionLabel", versionLabel)
        },
    ).map { }

    override suspend fun scenes(versionId: String): ZillitResult<List<SceneInfo>> =
        when (val raw = rawScenes("$base/versions/$versionId/scenes")) {
            is ZillitResult.Failure -> raw
            is ZillitResult.Success -> ZillitResult.Success(parseSceneBody(raw.data))
        }

    override suspend fun sides(history: Boolean, limit: Int): ZillitResult<List<SidesRecord>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/sides",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = buildMap {
                put("limit", limit)
                if (history) put("history", "true")
            },
        ).map { element ->
            ((element as? JsonObject)?.get("sides") as? JsonArray)
                .items().mapNotNull { parseSides(it as? JsonObject) }
        }

    override suspend fun sidesById(id: String): ZillitResult<SidesRecord> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/sides/$id",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { element ->
        // The detail nests under the SINGULAR key `sides`.
        parseSides((element as? JsonObject)?.get("sides") as? JsonObject)
            ?: parseSides(element as? JsonObject)
            ?: SidesRecord(
                id = id, title = "", status = SidesStatus.Unknown, rawStatus = "",
                error = "", sceneNumbers = emptyList(), totalScenes = 0,
                scriptTitle = "", versionLabel = "", generatedByName = "",
                generatedById = "", downloadCount = 0, createdAt = "", attachmentName = "",
            )
    }

    override suspend fun generate(plan: GeneratePlan): ZillitResult<SidesRecord> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/sides",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = generateWire(plan),
    ).map { element ->
        parseSides((element as? JsonObject)?.get("sides") as? JsonObject)
            ?: parseSides(element as? JsonObject)
            ?: SidesRecord(
                id = "", title = plan.title, status = SidesStatus.Error, rawStatus = "error",
                error = "The service did not return the new sides", sceneNumbers = emptyList(),
                totalScenes = 0, scriptTitle = "", versionLabel = "", generatedByName = "",
                generatedById = "", downloadCount = 0, createdAt = "", attachmentName = "",
            )
    }

    override suspend fun downloadUrl(id: String, countDownload: Boolean): ZillitResult<String> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/sides/$id/download",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = if (countDownload) mapOf("count" to "true") else emptyMap(),
        ).map { element ->
            (element as? JsonObject)?.text("downloadUrl", "download_url").orEmpty()
        }

    override suspend fun publish(id: String): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/sides/$id/publish",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { },
    ).map { }

    override suspend fun deleteSides(id: String): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Delete,
        url = "$base/sides/$id",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { }

    // Parsers --------------------------------------------------------------

    private fun parseScript(obj: JsonObject?): Script? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        val current = obj["currentVersion"] as? JsonObject
        return Script(
            id = id,
            title = obj.text("title"),
            description = obj.text("description"),
            status = obj.text("status"),
            format = obj.text("format"),
            updatedAt = obj.text("updatedAt", "updated_at"),
            currentVersionId = current?.text("_id", "id").orEmpty(),
            currentVersionLabel = current?.text("versionLabel", "version_label").orEmpty(),
            pageCount = current?.long("pageCount", "page_count")?.toInt() ?: 0,
        )
    }

    private fun parseVersion(obj: JsonObject?): ScriptVersion? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        return ScriptVersion(
            id = id,
            versionNumber = obj.long("versionNumber", "version_number")?.toInt() ?: 0,
            versionLabel = obj.text("versionLabel", "version_label"),
            pageCount = obj.long("pageCount", "page_count")?.toInt() ?: 0,
            createdAt = obj.text("createdAt", "created_at"),
            fileName = (obj["attachment"] as? JsonObject)?.text("name").orEmpty(),
        )
    }

    private fun parseSides(obj: JsonObject?): SidesRecord? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        val rawStatus = obj.text("status")
        return SidesRecord(
            id = id,
            title = obj.text("title"),
            status = SidesStatus.fromWire(rawStatus),
            rawStatus = rawStatus,
            error = obj.text("error"),
            sceneNumbers = ((obj["sceneNumbers"] as? JsonArray))
                .items().mapNotNull { (it as? JsonPrimitive)?.content },
            totalScenes = obj.long("totalScenes", "total_scenes")?.toInt() ?: 0,
            scriptTitle = (obj["script"] as? JsonObject)?.text("title").orEmpty(),
            versionLabel = (obj["scriptVersion"] as? JsonObject)
                ?.text("versionLabel", "version_label").orEmpty(),
            generatedByName = (obj["generatedBy"] as? JsonObject)?.text("name").orEmpty(),
            generatedById = (obj["generatedBy"] as? JsonObject)?.text("_id", "id").orEmpty(),
            downloadCount = obj.long("downloadCount", "download_count")?.toInt() ?: 0,
            createdAt = obj.text("createdAt", "created_at"),
            attachmentName = (obj["attachment"] as? JsonObject)?.text("name").orEmpty(),
        )
    }

    private fun StoredAttachment.toWire(): JsonObject = buildJsonObject {
        put("media", media)
        put("name", name)
        put("content_type", "document")
        put("content_subtype", "pdf")
        put("bucket", bucket)
        put("region", region)
        put("file_size", fileSizeBytes)
    }

}

// Lenient readers ----------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

private fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

private fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.longOrNull

private fun JsonArray?.items(): List<JsonElement> = this?.toList() ?: emptyList()

/** The exact manual-mode generation body — a wire contract, pinned by test. */
internal fun generateWire(plan: GeneratePlan): JsonObject = buildJsonObject {
    put("scriptId", plan.scriptId)
    if (plan.title.isNotBlank()) put("title", plan.title)
    put("mode", "manual")
    put(
        "versionScenes",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("versionId", plan.versionId)
                    put(
                        "sceneNumbers",
                        buildJsonArray { plan.sceneNumbers.forEach { add(JsonPrimitive(it)) } },
                    )
                },
            )
        },
    )
    put("sceneDisplayMode", plan.displayMode)
    put("publish", false)
    if (plan.sceneOrder.isNotEmpty()) {
        put("orderedScenes", true)
        put(
            "sceneOrder",
            buildJsonArray { plan.sceneOrder.forEach { add(JsonPrimitive(it)) } },
        )
    }
}

/**
 * The scene list arrives as RAW JSON — enveloped `{scenes:[...]}` or a bare
 * array; snake and camel spellings both occur. Unreadable bodies parse to
 * nothing rather than an error: the picker shows an empty list, the tool
 * stays up.
 */
internal fun parseSceneBody(body: String): List<SceneInfo> {
    val element = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(body)
    }.getOrNull()
    val scenes = ((element as? JsonObject)?.get("scenes") as? JsonArray)
        ?: (element as? JsonArray)
    return (scenes?.toList() ?: emptyList()).mapNotNull { scene ->
        val obj = scene as? JsonObject ?: return@mapNotNull null
        SceneInfo(
            sceneNumber = obj.text("sceneNumber", "scene_number"),
            heading = obj.text("heading"),
            intExt = obj.text("intExt", "int_ext"),
            timeOfDay = obj.text("timeOfDay", "time_of_day"),
            pageStart = obj.long("pageStart", "page_start")?.toInt() ?: 0,
            pageEnd = obj.long("pageEnd", "page_end")?.toInt() ?: 0,
        ).takeIf { it.sceneNumber.isNotBlank() }
    }
}
