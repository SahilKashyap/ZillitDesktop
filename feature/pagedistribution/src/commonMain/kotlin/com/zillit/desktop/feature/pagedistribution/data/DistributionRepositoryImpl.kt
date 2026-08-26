package com.zillit.desktop.feature.pagedistribution.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.pagedistribution.domain.CountRow
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionRepository
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.ReadAction
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft
import com.zillit.desktop.core.socket.SocketEventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The distribution services (`/api/v2/{segment}/…`).
 *
 * Transcription notes, from the web's `scheduleApiCalls.js`,
 * `scriptdistribution/api.js` and `dodApi/dod.js`:
 *
 *  - HTTP 200 + `status:0` is a failure, checked on every call;
 *  - the history switch is a FLAG named for the tab's failure mode —
 *    `?replaced=<now>` on single lists, `?deleted=<now>` on folders — its
 *    value is a cache-buster the server ignores;
 *  - a single read is `?{scheduleId|scriptId|pageId}=…&action=…`; the action
 *    is what the per-user view/download tallies count;
 *  - the folder list and its documents share ONE route: the folder key
 *    (`sceneNumber` / `name`) plus `timestamp` and `nextPrevious` switch the
 *    answer from folder headers to documents;
 *  - search sends the colour hex WITHOUT its `#`;
 *  - the web's api files sniff `window.location.pathname` for history and
 *    file-cabinet — here the mode is an explicit parameter.
 */
@Suppress("TooManyFunctions") // One function per REST route.
class DistributionRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val newUniqueId: () -> String,
    private val nowMillis: () -> Long,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
) : DistributionRepository {

    /**
     * See [DistributionRepository.refreshes]. Conflated: an upload emits a
     * folder event and a page event back to back and one refetch answers
     * both.
     */
    override fun refreshes(tool: DistributionTool): Flow<Unit> =
        bus?.onAny(distributionSyncEvents(tool))?.map { }?.conflate() ?: emptyFlow()

    private fun base(tool: DistributionTool) = config.apiV2(tool.service).trimEnd('/') + "/" + tool.segment

    private val docDist get() = "${config.baseUrl(ZillitService.DocDistribution)}/api/v2/document-distribution"

    override suspend fun documents(tab: DistributionTab, mode: ListMode): ZillitResult<List<DistDocument>> {
        val kind = tab.kind as? TabKind.Single
            ?: return ZillitResult.Failure(ZillitError.Unknown("${tab.key} is not a single-document list"))
        return get(url(tab, kind.route), historyFlag(tab, mode)).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseDocument(it as? JsonObject) }
                .filter { mode == ListMode.History || (!it.deleted && !it.replaced) }
        }
    }

    override suspend fun folders(tab: DistributionTab, mode: ListMode): ZillitResult<List<DistFolder>> {
        val kind = tab.kind as? TabKind.Folders
            ?: return ZillitResult.Failure(ZillitError.Unknown("${tab.key} has no folders"))
        return get(url(tab, kind.folderRoute), historyFlag(tab, mode)).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseFolder(it as? JsonObject, kind.folderKey) }
                .filter { mode == ListMode.History || !it.deleted }
        }
    }

    override suspend fun folderDocuments(
        tab: DistributionTab,
        folderKey: String,
        beforeMs: Long,
        next: Boolean,
        mode: ListMode,
    ): ZillitResult<List<DistDocument>> {
        val kind = tab.kind as? TabKind.Folders
            ?: return ZillitResult.Failure(ZillitError.Unknown("${tab.key} has no folders"))
        val query = mapOf(
            kind.folderKey.query to folderKey,
            "timestamp" to beforeMs,
            "nextPrevious" to if (next) "next" else "previous",
        ) + historyFlag(tab, mode)
        return get(url(tab, kind.folderRoute), query).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseDocument(it as? JsonObject) }
                .filter { mode == ListMode.History || !it.deleted }
        }
    }

    override suspend fun search(
        tab: DistributionTab,
        sceneNumber: String?,
        episode: String?,
        colour: PageColour?,
        mode: ListMode,
    ): ZillitResult<List<DistDocument>> {
        val kind = tab.kind as? TabKind.Folders
            ?: return ZillitResult.Failure(ZillitError.Unknown("${tab.key} has no folders"))
        val scene = sceneNumber?.trim()?.takeIf { it.isNotEmpty() }
        val hex = colour?.hex?.removePrefix("#")
        val query = buildMap<String, Any?> {
            // The web's exclusions: a colour drops the scene, a scene drops the colour.
            if (hex == null && scene != null) put("sceneNumber", scene)
            if (scene == null && hex != null) put("pageColourCode", hex)
            episode?.trim()?.takeIf { it.isNotEmpty() }?.let { put("episode", it) }
            putAll(historyFlag(tab, mode))
        }
        return get(url(tab, kind.folderRoute), query).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseDocument(it as? JsonObject) }
        }
    }

    override suspend fun document(
        tab: DistributionTab,
        id: String,
        action: ReadAction,
        mode: ListMode,
    ): ZillitResult<DistDocument> {
        val idQuery = when (val kind = tab.kind) {
            is TabKind.Single -> kind.idQuery
            is TabKind.Folders -> "pageId"
        }
        val query = mapOf(idQuery to id, "action" to action.wire) + historyFlag(tab, mode)
        return get(url(tab, tab.kind.itemRoute), query).flatMapData { data ->
            parseDocument(data as? JsonObject)
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Unknown("document $id answered no record"))
        }
    }

    override suspend fun upload(
        tab: DistributionTab,
        draft: UploadDraft,
        stored: StoredPdf,
        nowMs: Long,
    ): ZillitResult<DistDocument?> {
        val tool = tab.tool()
        val body = uploadWire(tool, tab, draft, stored, newUniqueId(), nowMs)
        return apiClient.envelope(HttpVerb.Post, url(tab, tab.kind.itemRoute), RequestModule.ProjectUser, body)
            .mapData { data -> parseDocument(data as? JsonObject) }
    }

    override suspend fun delete(tab: DistributionTab, id: String): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Delete, "${url(tab, tab.kind.itemRoute)}/$id", RequestModule.ProjectUser)
            .mapData { }

    override suspend fun move(tab: DistributionTab, id: String, folderName: String): ZillitResult<Unit> =
        apiClient.envelope(
            HttpVerb.Put,
            "${url(tab, tab.kind.itemRoute)}/move/$id",
            RequestModule.ProjectUser,
            buildJsonObject { put("name", folderName) },
        ).mapData { }

    override suspend fun counts(tab: DistributionTab, id: String): ZillitResult<List<CountRow>> =
        get("${base(tab.tool())}/${tab.kind.countType}/count/$id").mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseCount(it as? JsonObject) }
        }

    override suspend fun publish(
        tool: DistributionTool,
        tab: DistributionTab,
        document: DistDocument,
        todayYmd: String,
    ): ZillitResult<Unit> =
        apiClient.envelope(
            HttpVerb.Post,
            "$docDist/documents/from-tool",
            RequestModule.ProjectUser,
            publishWire(tool, tab, document, todayYmd),
        ).mapData { }

    // Transport ------------------------------------------------------------

    private fun url(tab: DistributionTab, route: String) = "${base(tab.tool())}/$route"

    /** The history switch — named for how the list's rows leave: replaced or deleted. */
    private fun historyFlag(tab: DistributionTab, mode: ListMode): Map<String, Any?> =
        if (mode != ListMode.History) {
            emptyMap()
        } else {
            val name = if (tab.kind is TabKind.Single) "replaced" else "deleted"
            mapOf(name to nowMillis())
        }

    private suspend fun get(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<ApiEnvelope> =
        apiClient.envelope(HttpVerb.Get, url, RequestModule.ProjectUser, queryParameters = query)

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
    }
}

/**
 * A tab knows its tool by identity — the three tools' tabs are distinct
 * objects, so the lookup is exact.
 */
internal fun DistributionTab.tool(): DistributionTool =
    listOf(DistributionTool.ScheduleDistribution, DistributionTool.ScriptDistribution, DistributionTool.ScheduleDod)
        .first { tool -> tool.tabs.any { it === this } }

private fun JsonArray?.items(): List<JsonElement> = this?.toList() ?: emptyList()
