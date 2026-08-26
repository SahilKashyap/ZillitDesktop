package com.zillit.desktop.feature.continuity.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityRepository
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.continuity.domain.TalentInfo
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * `/api/v2/continuity` on the continuity service.
 *
 * The one endpoint with two shapes: `GET scenes` without `sceneNumber` lists
 * the scene FOLDERS (numbers); with it, the cards. Cards page by `timestamp`
 * (epoch ms) + `nextPrevious=previous` (older). Every call answers the
 * standard envelope; `status:0` inside a 200 is a failure and its `message`
 * is a translation key (`continuity_action_not_allowed`…).
 */
class ContinuityRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Department names arrive as label keys (`camera_department_label`); the host translates. */
    private val localise: (String) -> String = { it },
    /** Null keeps the board socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
) : ContinuityRepository {

    /**
     * See [ContinuityRepository.refreshes]. Conflated: a multi-file upload
     * lands as one `scenes:created` per file and one refetch answers all.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(CONTINUITY_SYNC_EVENTS)?.map { }?.conflate() ?: emptyFlow()

    private val base = config.apiV2(ZillitService.Continuity).trimEnd('/') + "/continuity"

    override suspend fun folders(tab: ContinuityTab): ZillitResult<List<String>> =
        get("$base/scenes", if (tab == ContinuityTab.AllDepartments) mapOf("showall" to "true") else emptyMap())
            .mapData { data ->
                (data as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
            }

    override suspend fun scenes(
        tab: ContinuityTab,
        sceneFolder: String,
        departmentId: String?,
        beforeMs: Long,
    ): ZillitResult<List<ContinuityScene>> {
        val query = buildMap<String, Any?> {
            put("sceneNumber", sceneFolder)
            when {
                departmentId != null -> put("departmentId", departmentId)
                tab == ContinuityTab.AllDepartments -> put("showall", "true")
            }
            put("timestamp", beforeMs.toString())
            put("nextPrevious", "previous")
        }
        return get("$base/scenes", query).mapData { data ->
            (data as? JsonArray).orEmpty().mapNotNull { parseScene(it as? JsonObject) }
        }
    }

    override suspend fun departments(sceneFolder: String): ZillitResult<List<ContinuityDepartment>> =
        get("$base/departments", mapOf("sceneNumber" to sceneFolder, "show" to "all")).mapData { data ->
            (data as? JsonArray).orEmpty().mapNotNull { row ->
                val obj = row as? JsonObject ?: return@mapNotNull null
                val id = obj.text("department_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ContinuityDepartment(id, obj.text("department_name").let { if (it.isBlank()) it else localise(it) })
            }
        }

    override suspend fun create(
        draft: SceneDraft,
        attachment: ContinuityAttachment,
        uniqueId: String,
    ): ZillitResult<Unit> = mutate(HttpVerb.Post, "$base/scene", createWire(draft, attachment, uniqueId)).map { }

    override suspend fun update(id: String, draft: SceneDraft): ZillitResult<ContinuityScene?> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = "$base/scene/$id",
            module = RequestModule.ProjectUser,
            body = editWire(draft),
        ).mapData { data -> parseScene(data as? JsonObject) }

    override suspend fun share(ids: List<String>, sceneFolder: String): ZillitResult<Unit> = mutate(
        HttpVerb.Put,
        "$base/share/scenes",
        buildJsonObject {
            put("scene_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            put("visibility", JsonPrimitive("all"))
            put("scene_number", sceneFolder.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(sceneFolder))
        },
    ).map { }

    override suspend fun delete(tab: ContinuityTab, id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/scene/${tab.wireLabel}/$id", null).map { }

    // -- plumbing ------------------------------------------------------------

    private suspend fun get(url: String, query: Map<String, Any?>) = apiClient.envelope(
        verb = HttpVerb.Get,
        url = url,
        module = RequestModule.ProjectUser,
        queryParameters = query,
    )

    private suspend fun mutate(verb: HttpVerb, url: String, body: JsonObject?) = apiClient.envelope(
        verb = verb,
        url = url,
        module = RequestModule.ProjectUser,
        body = body,
    )

    private inline fun <T> ZillitResult<com.zillit.desktop.core.network.ApiEnvelope>.mapData(
        transform: (JsonElement?) -> T,
    ): ZillitResult<T> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> if (data.status == 1) {
            ZillitResult.Success(transform(data.data))
        } else {
            ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = data.message))
        }
    }

    private fun <T> ZillitResult<com.zillit.desktop.core.network.ApiEnvelope>.map(
        transform: (Unit) -> T,
    ): ZillitResult<T> = mapData { transform(Unit) }
}

// -- wire --------------------------------------------------------------------

internal fun createWire(draft: SceneDraft, attachment: ContinuityAttachment, uniqueId: String): JsonObject =
    buildJsonObject {
        put("scene_number", JsonPrimitive(draft.sceneNumber.trim().trimStart('0').ifBlank { "0" }))
        put("scene_notes", JsonPrimitive(draft.notes.trim()))
        put("unique_id", JsonPrimitive(uniqueId))
        put("attachment", attachmentWire(attachment))
        put("talent_info", talentWire(draft.talentInfo))
        // A string even though it is digits — ZL-12885.
        if (draft.episode.isNotBlank()) put("episode", JsonPrimitive(draft.episode.trim()))
    }

internal fun editWire(draft: SceneDraft): JsonObject = buildJsonObject {
    put("scene_number", JsonPrimitive(draft.sceneNumber.trim()))
    put("scene_notes", JsonPrimitive(draft.notes.trim()))
    put("talent_info", talentWire(draft.talentInfo))
    if (draft.episode.isNotBlank()) put("episode", JsonPrimitive(draft.episode.trim()))
}

/** Every key, always: the server rejects an attachment missing any of them. */
internal fun attachmentWire(a: ContinuityAttachment): JsonObject = buildJsonObject {
    put("media", JsonPrimitive(a.media))
    put("thumbnail", JsonPrimitive(a.thumbnail))
    put("content_type", JsonPrimitive(a.contentType))
    put("content_subtype", JsonPrimitive(a.contentSubtype))
    put("caption", JsonPrimitive(a.caption))
    put("height", JsonPrimitive(a.height))
    put("width", JsonPrimitive(a.width))
    put("duration", JsonPrimitive(a.duration))
    put("bucket", JsonPrimitive(a.bucket))
    put("region", JsonPrimitive(a.region))
    put("name", JsonPrimitive(a.name))
    put("file_size", JsonPrimitive(a.fileSize))
}

private fun talentWire(rows: List<TalentInfo>): JsonArray = buildJsonArray {
    rows.filter { it.label.isNotBlank() || it.value.isNotBlank() }.forEach {
        add(
            buildJsonObject {
                put("label", JsonPrimitive(it.label.trim()))
                put("value", JsonPrimitive(it.value.trim()))
            },
        )
    }
}

internal fun parseScene(obj: JsonObject?): ContinuityScene? {
    if (obj == null) return null
    val id = obj.text("_id", "id").takeIf { it.isNotBlank() } ?: return null
    val visibility = obj["visibility"] as? JsonObject
    val deleted = obj["deleted"] as? JsonObject
    return ContinuityScene(
        id = id,
        uniqueId = obj.text("unique_id"),
        sceneNumber = obj.text("scene_number", "sceneNumber"),
        episode = obj.text("episode"),
        notes = obj.text("scene_notes", "sceneNotes"),
        actorName = obj.text("actor_name", "actorName"),
        talentInfo = (obj["talent_info"] ?: obj["talentInfo"]).let { it as? JsonArray }.orEmpty().mapNotNull { row ->
            (row as? JsonObject)?.let { TalentInfo(it.text("label"), it.text("value")) }
        },
        attachment = parseAttachment(obj["attachment"] as? JsonObject),
        departmentId = obj.text("department_id", "departmentId"),
        uploadedBy = obj.text("uploaded_by", "uploadedBy"),
        // Absent visibility means visible: older rows predate the flags.
        visibleIntra = visibility?.flag("intra") ?: true,
        visibleAll = visibility?.flag("all") ?: false,
        deletedIntra = deleted?.flag("intra") ?: false,
        deletedAll = deleted?.flag("all") ?: false,
        createdMs = obj.long("created", "createdAt") ?: 0L,
        updatedMs = obj.long("updated", "updatedAt") ?: 0L,
    )
}

internal fun parseAttachment(obj: JsonObject?): ContinuityAttachment? {
    if (obj == null) return null
    val media = obj.text("media").takeIf { it.isNotBlank() } ?: return null
    return ContinuityAttachment(
        media = media,
        thumbnail = obj.text("thumbnail"),
        contentType = obj.text("content_type", "contentType"),
        contentSubtype = obj.text("content_subtype", "contentSubtype").lowercase(),
        name = obj.text("name"),
        bucket = obj.text("bucket"),
        region = obj.text("region"),
        fileSize = obj.text("file_size", "fileSize").ifBlank { "0" },
        caption = obj.text("caption"),
        height = obj.long("height")?.toInt() ?: 0,
        width = obj.long("width")?.toInt() ?: 0,
        duration = obj.long("duration")?.toInt() ?: 0,
    )
}

// -- lenient readers ---------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String = when (val e = firstOf(*names)) {
    is JsonPrimitive -> e.content
    else -> ""
}

private fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

/** `1`, `true`, `"1"`, `"true"` are all set. */
private fun JsonObject.flag(name: String): Boolean? {
    val e = this[name] as? JsonPrimitive ?: return null
    return e.booleanOrNull ?: e.longOrNull?.let { it != 0L } ?: e.content.let { it == "1" || it == "true" }
}

@Suppress("unused") // Kept for callers that need the raw array/object views.
private val JsonElement.array: JsonArray get() = jsonArray

@Suppress("unused")
private val JsonElement.obj: JsonObject get() = jsonObject
