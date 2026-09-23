package com.zillit.desktop.feature.crewlist.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.crewlist.domain.CompanyDetails
import com.zillit.desktop.feature.crewlist.domain.CompanyLogo
import com.zillit.desktop.feature.crewlist.domain.CrewDocumentRequest
import com.zillit.desktop.feature.crewlist.domain.CrewListPdf
import com.zillit.desktop.feature.crewlist.domain.CrewListRepository
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.domain.OrderedPerson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * `crewlist` on the units host (web `unitApi.js`, Android `ApiUrl.kt:200-201`)
 * — the roster, the PDF, its HTML twin — plus the Info post and the
 * production record the letterhead reads (`CrewListCustom.jsx`,
 * `CompanyDetails.jsx`).
 */
@Suppress("LongParameterList") // A repository's seams: each is one thing the host owns.
class CrewListRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
    /**
     * Which production the roster is about, when that is not the one the app
     * is open on — the Crew List widget showing another production.
     */
    private val callOptions: () -> CallOptions = { CallOptions() },
    /**
     * A signed POST read raw: `crewlist/html` answers `text/html`, which the
     * envelope client cannot read.
     */
    private val rawPost: suspend (url: String, body: JsonObject) -> ZillitResult<ByteArray> =
        { _, _ -> ZillitResult.Failure(ZillitError.Storage(userMessage = str(S.desktop_cl_preview_unavailable_here))) },
    /** The Info board's unit — the `info_tool` row of the tools grid. */
    private val infoUnitId: () -> String? = { null },
    /** The boards' cipher: a post's `message` travels encrypted. */
    private val encrypt: (String) -> ZillitResult<String> = { ZillitResult.Success(it) },
    private val nowMillis: () -> Long = { 0L },
    private val newId: () -> String = { nowMillis().toString() },
) : CrewListRepository {

    private val units get() = config.apiV2(ZillitService.Units)
    private val core get() = config.apiV2(ZillitService.Core)

    /**
     * See [CrewListRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project, as the web handler does.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(CREW_LIST_SYNC_EVENTS)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.map { }
            ?: emptyFlow()

    override suspend fun roster(): ZillitResult<List<CrewUnit>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${units}crewlist/list",
            serializer = ListSerializer(UnitDto.serializer()),
            module = RequestModule.ProjectUser,
            options = callOptions(),
        ).map { rows -> rows.map { it.toModel() } }

    override suspend fun generate(request: CrewDocumentRequest): ZillitResult<CrewListPdf> =
        when (
            val outcome = apiClient.envelope(
                verb = HttpVerb.Post,
                url = "${units}crewlist",
                module = RequestModule.ProjectUser,
                options = callOptions(),
                body = request.toBody(),
            )
        ) {
            is ZillitResult.Failure -> outcome
            is ZillitResult.Success -> {
                val pdf = (outcome.data.data as? JsonObject)?.toPdf()
                if (outcome.data.status != REFUSED && pdf != null && pdf.media.isNotBlank()) {
                    ZillitResult.Success(pdf)
                } else {
                    ZillitResult.Failure(
                        ZillitError.Validation(outcome.data.refusal(str(S.desktop_cl_server_answered_no_pdf))),
                    )
                }
            }
        }

    override suspend fun previewHtml(request: CrewDocumentRequest): ZillitResult<String> =
        rawPost("${units}crewlist/html", request.toBody()).map { bytes -> bytes.decodeToString() }

    override suspend fun publishToInfo(pdf: CrewListPdf, caption: String): ZillitResult<Unit> {
        val unitId = infoUnitId()?.takeIf { it.isNotBlank() }
            ?: return ZillitResult.Failure(ZillitError.Validation(str(S.desktop_cl_info_not_switched_on)))
        val message = when (val sealed = encrypt(caption)) {
            is ZillitResult.Failure -> return sealed
            is ZillitResult.Success -> sealed.data
        }
        val now = nowMillis()
        val body = buildJsonObject {
            put("unit_id", unitId)
            put("message", message)
            put("pinned", now)
            put("message_translation", "")
            put("message_type", DOCUMENT)
            put("message_group", now)
            putJsonObject("location") {
                put("lat", 0)
                put("long", 0)
            }
            put("unique_id", newId())
            put("comments", buildJsonArray { })
            put("attachment", pdf.attachment.withoutNulls())
        }
        return apiClient.envelope(
            verb = HttpVerb.Post,
            url = "${units}info/chat",
            module = RequestModule.ProjectUser,
            options = callOptions(),
            body = body,
        ).accepted(str(S.desktop_cl_crew_list_not_published))
    }

    override suspend fun companyDetails(): ZillitResult<CompanyDetails> {
        val project = currentProjectId()?.takeIf { it.isNotBlank() }
            ?: return ZillitResult.Failure(ZillitError.Validation(str(S.desktop_no_production_is_open)))
        return apiClient.request(
            verb = HttpVerb.Get,
            url = "${core}project/$project",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { record -> ((record as? JsonObject) ?: JsonObject(emptyMap())).toCompanyDetails() }
    }

    override suspend fun saveCompanyDetails(
        details: CompanyDetails,
        newLogo: CompanyLogo?,
        removeLogo: Boolean,
    ): ZillitResult<Unit> {
        if (removeLogo && newLogo == null) {
            val removed = apiClient.envelope(
                verb = HttpVerb.Delete,
                url = "${core}project/company-logo",
                module = RequestModule.ProjectUser,
            ).accepted(str(S.desktop_cl_logo_not_removed))
            if (removed is ZillitResult.Failure) return removed
        }
        return apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "${core}project",
            module = RequestModule.ProjectUser,
            body = details.toPatchBody(newLogo),
        ).accepted(str(S.desktop_cl_company_details_not_saved))
    }

    override suspend fun departmentPeople(departmentId: String): ZillitResult<List<OrderedPerson>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${core}project/users",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("reorder" to "true", "departmentId" to departmentId),
            // An editor's read: a saved-but-stale order must never come back from disk.
            options = CallOptions(readCache = false),
        ).map { data -> (data as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toOrderedPerson() } }

    override suspend fun reorderPeople(userIds: List<String>): ZillitResult<String> =
        when (
            val outcome = apiClient.envelope(
                verb = HttpVerb.Put,
                url = "${core}user/reorder-users",
                module = RequestModule.ProjectUser,
                body = buildJsonObject {
                    put("newOrder", buildJsonArray { userIds.forEach { add(JsonPrimitive(it)) } })
                },
            )
        ) {
            is ZillitResult.Failure -> outcome
            is ZillitResult.Success ->
                if (outcome.data.status == REFUSED) {
                    ZillitResult.Failure(
                        ZillitError.Validation(outcome.data.refusal(str(S.desktop_cl_order_not_saved))),
                    )
                } else {
                    ZillitResult.Success(outcome.data.message.orEmpty())
                }
        }

    /** `{status: 0, message}` on a 200 is how these routes refuse — never a success. */
    private fun ZillitResult<ApiEnvelope>.accepted(fallback: String): ZillitResult<Unit> = when (this) {
        is ZillitResult.Failure -> this
        is ZillitResult.Success ->
            if (data.status == REFUSED) {
                ZillitResult.Failure(ZillitError.Validation(data.refusal(fallback)))
            } else {
                ZillitResult.Success(Unit)
            }
    }

    private fun ApiEnvelope.refusal(fallback: String): String = message?.takeIf { it.isNotBlank() } ?: fallback

    private companion object {
        const val REFUSED = 0
        const val DOCUMENT = "document"
    }
}

/**
 * The generate answer, forwarded as the post's attachment — without its null
 * keys, which the request signature refuses.
 */
private fun JsonObject.withoutNulls(): JsonObject = JsonObject(filterValues { it !is JsonNull })
