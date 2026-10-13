package com.zillit.desktop.feature.talentdeals.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.JsonRead
import com.zillit.desktop.feature.talentdeals.domain.SigningDay
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentDealsRepository
import com.zillit.desktop.feature.talentdeals.domain.TalentExhibitG
import com.zillit.desktop.feature.talentdeals.domain.TalentRefresh
import com.zillit.desktop.feature.talentdeals.domain.TalentSaved
import com.zillit.desktop.feature.talentdeals.domain.TalentUnion
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `/api/v2/deal-memo/talent-contracts` and `/api/v2/deal-memo/talent-deals/unions`
 * on the Deal Memo host, and the AD dashboard's Exhibit G signing rows on its
 * own.
 *
 * Same auth chain as `/deals`: the encrypted `moduledata` header the client
 * injects carries the project, so NO endpoint takes a project id in the body or
 * the query. Do not add one. Attachments are NOT bytes: the file is uploaded
 * first and the resulting envelope is what a create sends.
 */
class TalentDealsRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : TalentDealsRepository {

    private val contractsUrl = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo/talent-contracts"
    private val unionsUrl = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo/talent-deals/unions"
    private val exhibitGUrl = "${config.baseUrl(ZillitService.AdDashboard)}/api/v2/ad-report/exhibit-g-reports"

    /** Frames that name another production are dropped — the web's cross-project gate. */
    override val refreshes: Flow<TalentRefresh> =
        bus?.onAny(TALENT_CONTRACT_EVENTS, TalentSyncEnvelope.serializer())
            ?.mapNotNull { (_, envelope) -> envelope.takeIf { it.inProject(currentProjectId()) }?.toRefresh() }
            ?: emptyFlow()

    override suspend fun contracts(): ZillitResult<List<TalentContract>> =
        read(contractsUrl).map { data -> JsonRead.objects(data).mapNotNull(TalentWire::contractOf) }

    override suspend fun contract(id: String): ZillitResult<TalentContract> =
        read("$contractsUrl/${id.encodeURLPathPart()}").flatMap { data ->
            val row = (data as? JsonObject)?.let(TalentWire::contractOf)
            if (row != null) {
                ZillitResult.Success(row)
            } else {
                ZillitResult.Failure(ZillitError.Validation(str(S.desktop_td_contract_not_found)))
            }
        }

    override suspend fun create(body: JsonObject): ZillitResult<TalentSaved> = saved(HttpVerb.Post, contractsUrl, body)

    override suspend fun update(id: String, body: JsonObject): ZillitResult<TalentSaved> =
        saved(HttpVerb.Patch, "$contractsUrl/${id.encodeURLPathPart()}", body)

    override suspend fun remove(id: String): ZillitResult<String?> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = "$contractsUrl/${id.encodeURLPathPart()}",
            module = RequestModule.ProjectUser,
        )
            .flatMap { it.refusedOrOk() }
            .map { it.message?.takeIf(String::isNotBlank) }

    override suspend fun unions(crewType: String, territory: String): ZillitResult<List<TalentUnion>> =
        read("$unionsUrl/${crewType.ifBlank { "all" }.encodeURLPathPart()}/${territory.encodeURLPathPart()}")
            .map { data -> JsonRead.objects(data).mapNotNull(TalentWire::unionOf) }

    override suspend fun exhibitG(name: String): ZillitResult<List<SigningDay>> =
        read("$exhibitGUrl/signing-rows", mapOf("username" to name)).map(TalentExhibitG::normalizeSigningDays)

    // -- plumbing ---------------------------------------------------------------------

    private suspend fun read(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<JsonElement?> =
        apiClient.envelope(verb = HttpVerb.Get, url = url, module = RequestModule.ProjectUser, queryParameters = query)
            .flatMap { it.refusedOrOk() }
            .map(ApiEnvelope::data)

    private suspend fun saved(verb: HttpVerb, url: String, body: JsonObject): ZillitResult<TalentSaved> =
        apiClient.envelope(verb = verb, url = url, module = RequestModule.ProjectUser, body = body)
            .flatMap { it.refusedOrOk() }
            .map { envelope ->
                val data = envelope.data as? JsonObject
                TalentSaved(
                    id = JsonRead.text(data, "_id") ?: JsonRead.text(data, "id"),
                    message = envelope.message?.takeIf(String::isNotBlank),
                )
            }
}
