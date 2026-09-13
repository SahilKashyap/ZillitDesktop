package com.zillit.desktop.feature.dealmemo.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.dealmemo.domain.ApprovalTier
import com.zillit.desktop.feature.dealmemo.domain.ApprovalTierConfig
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealExport
import com.zillit.desktop.feature.dealmemo.domain.DealHistoryEntry
import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRepository
import com.zillit.desktop.feature.dealmemo.domain.DealOverview
import com.zillit.desktop.feature.dealmemo.domain.DealPdfKind
import com.zillit.desktop.feature.dealmemo.domain.DealRefresh
import com.zillit.desktop.feature.dealmemo.domain.DealSignTarget
import com.zillit.desktop.feature.dealmemo.domain.DealTemplate
import com.zillit.desktop.feature.dealmemo.domain.DealTemplateSummary
import com.zillit.desktop.feature.dealmemo.domain.DealWrite
import com.zillit.desktop.feature.dealmemo.domain.DepartmentCount
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.ProjectSection
import com.zillit.desktop.feature.dealmemo.domain.SavedRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Posts a request and hands back the file it answers with — the register
 * exports. The host owns it: bytes do not travel through the JSON client, and
 * a JSON envelope where a file was expected must come back as a failure.
 */
fun interface DealFileFetcher {
    suspend fun post(url: String, body: JsonObject?): ZillitResult<ByteArray>
}

/** Every `/api/v2/deal-memo` route the tool calls, and the notice template on the hub. */
@Suppress("TooManyFunctions") // One function per server operation.
class DealMemoRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
    private val files: DealFileFetcher = DealFileFetcher { _, _ ->
        ZillitResult.Failure(ZillitError.Unknown("Exports are not available here."))
    },
) : DealMemoRepository {

    private val base = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo"
    private val projectSettingsUrl = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub/project-settings"
    private val noticeTemplateUrl = "$projectSettingsUrl/notice-template"
    private val bankAccountsUrl = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub/bank-accounts"

    /** Frames that name another production are dropped — the web's cross-project gate. */
    override val refreshes: Flow<DealRefresh> =
        bus?.onAny(DEAL_EVENT_KEYS.keys, DealSyncEnvelope.serializer())
            ?.mapNotNull { (event, envelope) ->
                envelope.takeIf { it.inProject(currentProjectId()) }?.toRefresh(event)
            }
            ?: emptyFlow()

    // -- reads ---------------------------------------------------------------

    override suspend fun deals(): ZillitResult<List<DealDoc>> =
        read("$base/deals").map { data -> DocRead.objects(data).map(::DealDoc) }

    override suspend fun overview(): ZillitResult<DealOverview> = read("$base/deals/overview").map { data ->
        val body = data as? JsonObject
        val stats = DocRead.obj(body, "stats")
        fun stat(key: String) = DocRead.number(stats, key) ?: 0.0
        DealOverview(
            total = stat("total"),
            approved = stat("approved"),
            awaitingApproval = stat("awaiting_approval"),
            totalValue = stat("total_value"),
            active = stat("active"),
            issued = stat("issued"),
            draft = stat("draft"),
            recent = DocRead.objects(body?.get("recent")).map(::DealDoc),
            departmentBreakdown = DocRead.objects(body?.get("department_breakdown")).mapNotNull { row ->
                DocRead.text(row, "department")?.let { DepartmentCount(it, DocRead.number(row, "count") ?: 0.0) }
            },
        )
    }

    /** The page reads a flat list; anything else is nothing waiting. */
    override suspend fun approvalQueue(): ZillitResult<List<DealDoc>> =
        read("$base/deals/approval").map { data -> DocRead.objects(data).map(::DealDoc) }

    override suspend fun metadata(): ZillitResult<DealMemoMetadata> = read("$base/metadata").flatMap { data ->
        val body = data as? JsonObject
            ?: return@flatMap ZillitResult.Failure(ZillitError.Serialization("metadata response empty"))
        ZillitResult.Success(
            DealMemoMetadata(
                isApprover = DocRead.flag(body, "is_approver"),
                approverDepartmentIds = DocRead.array(body["approver_department_ids"])
                    .mapNotNull { (it as? JsonPrimitive)?.content },
                approvalTierConfigs = DocRead.objects(body["approval_tier_configs"]).map(::tierConfigOf),
                loaded = true,
            ),
        )
    }

    override suspend fun myDeal(): ZillitResult<DealDoc?> =
        read("$base/deal").map { data -> (data as? JsonObject)?.let(::DealDoc) }

    override suspend fun deal(id: String): ZillitResult<DealDoc> = read("$base/deals/$id").flatMap { data ->
        (data as? JsonObject)?.let { ZillitResult.Success(DealDoc(it)) }
            ?: ZillitResult.Failure(ZillitError.Serialization("deal $id came back empty"))
    }

    override suspend fun history(id: String): ZillitResult<List<DealHistoryEntry>> =
        read("$base/deals/$id/history").map { data ->
            val rows = (data as? JsonArray) ?: (data as? JsonObject)?.get("data")
            DocRead.objects(rows).map { row ->
                DealHistoryEntry(
                    action = DocRead.text(row, "action").orEmpty(),
                    actionBy = DocRead.text(row, "action_by"),
                    actionAt = DocRead.epoch(row, "action_at"),
                    note = DocRead.text(row, "note"),
                )
            }
        }

    override suspend fun templates(): ZillitResult<List<DealTemplateSummary>> =
        read("$base/templates").map { data ->
            DocRead.objects(data).mapNotNull { row ->
                val id = DocRead.text(row, "_id") ?: return@mapNotNull null
                DealTemplateSummary(
                    id = id,
                    name = DocRead.text(row, "name").orEmpty(),
                    createdBy = DocRead.text(row, "created_by"),
                    createdAt = DocRead.epoch(row, "created_at") ?: DocRead.epoch(row, "createdAt"),
                )
            }
        }

    override suspend fun template(id: String): ZillitResult<DealTemplate> =
        read("$base/templates/$id").flatMap { data ->
            val row = data as? JsonObject
                ?: return@flatMap ZillitResult.Failure(ZillitError.Serialization("setup $id came back empty"))
            ZillitResult.Success(
                DealTemplate(
                    id = DocRead.text(row, "_id") ?: id,
                    name = DocRead.text(row, "name").orEmpty(),
                    form = templateForm(row),
                    createdBy = DocRead.text(row, "created_by"),
                    createdAt = DocRead.epoch(row, "created_at") ?: DocRead.epoch(row, "createdAt"),
                ),
            )
        }

    // -- list actions ------------------------------------------------------------

    override suspend fun activate(id: String): ZillitResult<String?> = write(HttpVerb.Post, "$base/deals/$id/activate")

    override suspend fun chase(id: String): ZillitResult<String?> = write(HttpVerb.Post, "$base/deals/$id/chase")

    override suspend fun delete(id: String): ZillitResult<String?> = write(HttpVerb.Delete, "$base/deals/$id")

    override suspend fun sendNotice(id: String, lastPayDay: Long, content: String): ZillitResult<String?> =
        write(
            HttpVerb.Post,
            "$base/deals/$id/send-notice",
            buildJsonObject {
                put("last_pay_day", lastPayDay)
                put("content", content)
            },
        )

    override suspend fun deactivate(id: String, lastPayDate: Long): ZillitResult<String?> =
        write(HttpVerb.Post, "$base/deals/$id/deactivate", buildJsonObject { put("last_pay_date", lastPayDate) })

    override suspend fun export(kind: DealExport): ZillitResult<ByteArray> = when (kind) {
        // Filters never travel: the server exports the whole register, drafts excluded.
        DealExport.RegisterPdf, DealExport.RegisterExcel ->
            files.post("$base/deals/export", buildJsonObject { put("format", kind.wire) })
        DealExport.StartForms -> files.post("$base/deals/export/start-forms", null)
    }

    // -- notice template ------------------------------------------------------------

    override suspend fun noticeTemplate(): ZillitResult<String?> = read(noticeTemplateUrl).map { data ->
        DocRead.text(data as? JsonObject, "value")
    }

    /** Wrapped as `{value}`, never a bare string. */
    override suspend fun saveNoticeTemplate(value: String): ZillitResult<String?> =
        write(HttpVerb.Patch, noticeTemplateUrl, buildJsonObject { put("value", value) })

    override suspend fun projectSettings(): ZillitResult<JsonObject> = read(projectSettingsUrl).map { data ->
        DocRead.obj(data as? JsonObject, "settings") ?: JsonObject(emptyMap())
    }

    // -- the deal page ---------------------------------------------------------------------

    override suspend fun portalLink(id: String): ZillitResult<String?> =
        read("$base/deals/$id/portal-link").map { data -> DocRead.text(data as? JsonObject, "token") }

    /** `{signature: undefined, comment: undefined}` serialises to `{}`. */
    override suspend fun approve(id: String): ZillitResult<String?> =
        write(HttpVerb.Post, "$base/deals/$id/approve", JsonObject(emptyMap()))

    override suspend fun rejectAsCrew(reason: String): ZillitResult<String?> =
        write(HttpVerb.Post, "$base/deal/reject-crew", buildJsonObject { put("reason", reason) })

    override suspend fun sendForApproval(id: String): ZillitResult<String?> =
        write(HttpVerb.Post, "$base/deals/$id/send-for-approval")

    override suspend fun acknowledgeAmendment(): ZillitResult<String?> =
        write(HttpVerb.Post, "$base/deal/acknowledge-amendment", JsonObject(emptyMap()))

    override suspend fun saveCrewDetails(body: JsonObject): ZillitResult<DealWrite> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "$base/deal/crew-details",
            module = RequestModule.ProjectUser,
            body = body,
        )
            .flatMap { it.refusedOrOk() }
            .map { envelope ->
                DealWrite(envelope.message?.takeIf(String::isNotBlank), (envelope.data as? JsonObject)?.let(::DealDoc))
            }

    override suspend fun updateDealRules(id: String, body: JsonObject): ZillitResult<String?> =
        write(HttpVerb.Patch, "$base/deals/$id/deal-rules", body)

    override suspend fun updateNominalCodes(id: String, body: JsonObject): ZillitResult<String?> =
        write(HttpVerb.Patch, "$base/deals/$id/nominal-codes", body)

    /** `data.attachment`, or the data itself; one without media, bucket and region is a failure. */
    override suspend fun generatePdf(id: String, kind: DealPdfKind, context: JsonObject): ZillitResult<JsonObject> {
        val path = when (kind) {
            DealPdfKind.DealMemo -> "pdf"
            DealPdfKind.StartForm -> "crew-start-form/pdf"
        }
        val missing = when (kind) {
            DealPdfKind.DealMemo -> "pdf_attachment_missing"
            DealPdfKind.StartForm -> "crew_start_form_pdf_attachment_missing"
        }
        return apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/deals/$id/$path",
            module = RequestModule.ProjectUser,
            body = context,
        )
            .flatMap { it.refusedOrOk() }
            .flatMap { envelope ->
                val data = envelope.data as? JsonObject
                val attachment = (DocRead.obj(data, "attachment") ?: data)
                    ?.takeIf { att -> ATTACHMENT_KEYS.all { DocRead.text(att, it) != null } }
                attachment?.let { ZillitResult.Success(it) }
                    ?: ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = missing))
            }
    }

    override suspend fun sign(id: String, target: DealSignTarget, attachment: JsonObject): ZillitResult<String?> {
        val path = when (target) {
            DealSignTarget.DealMemo -> "deal-pdf/sign"
            DealSignTarget.StartForm -> "crew-start-form-pdf/sign"
            is DealSignTarget.Document -> "additional-documents/${target.docId}/sign"
        }
        return write(HttpVerb.Post, "$base/deals/$id/$path", buildJsonObject { put("attachment", attachment) })
    }

    // -- authoring --------------------------------------------------------------------

    /** `notify` rides the query string; the server ignores it in a body. */
    override suspend fun createDeal(body: JsonObject, notify: Boolean): ZillitResult<SavedRecord> =
        saved(HttpVerb.Post, "$base/deals", body, mapOf("notify" to notify))

    override suspend fun updateDeal(id: String, body: JsonObject, notify: Boolean): ZillitResult<SavedRecord> =
        saved(HttpVerb.Patch, "$base/deals/$id", body, mapOf("notify" to notify))

    override suspend fun submitDeal(id: String): ZillitResult<String?> = write(HttpVerb.Post, "$base/deals/$id/submit")

    override suspend fun createTemplate(body: JsonObject): ZillitResult<SavedRecord> =
        saved(HttpVerb.Post, "$base/templates", body)

    override suspend fun updateTemplate(id: String, body: JsonObject): ZillitResult<SavedRecord> =
        saved(HttpVerb.Patch, "$base/templates/$id", body)

    override suspend fun deleteTemplate(id: String): ZillitResult<String?> =
        write(HttpVerb.Delete, "$base/templates/$id")

    override suspend fun writeProjectSection(section: ProjectSection, body: JsonElement): ZillitResult<String?> =
        apiClient.envelope(
            verb = if (section.post) HttpVerb.Post else HttpVerb.Patch,
            url = "$projectSettingsUrl/${section.path}",
            module = RequestModule.ProjectUser,
            body = body,
        ).flatMap { it.refusedOrOk() }.map { it.message?.takeIf(String::isNotBlank) }

    override suspend fun deleteAgreementDocument(id: String): ZillitResult<String?> =
        write(HttpVerb.Delete, "$projectSettingsUrl/${ProjectSection.AgreementsDocuments.path}/$id")

    override suspend fun bankAccount(id: String): ZillitResult<JsonObject> =
        read("$bankAccountsUrl/$id").flatMap { data ->
            (data as? JsonObject)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("bank account $id came back empty"))
        }

    override suspend fun updateBankAccount(id: String, bank: JsonObject): ZillitResult<String?> =
        write(HttpVerb.Patch, "$bankAccountsUrl/$id", bank)

    /** A create or update that answers the record — its `_id` and `deal_reference` adopted by the caller. */
    private suspend fun saved(
        verb: HttpVerb,
        url: String,
        body: JsonObject,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<SavedRecord> =
        apiClient.envelope(
            verb = verb,
            url = url,
            module = RequestModule.ProjectUser,
            body = body,
            queryParameters = query,
        )
            .flatMap { it.refusedOrOk() }
            .map { envelope ->
                val data = envelope.data as? JsonObject
                SavedRecord(
                    id = DocRead.text(data, "_id") ?: DocRead.text(data, "id"),
                    reference = DocRead.text(data, "deal_reference"),
                    message = envelope.message?.takeIf(String::isNotBlank),
                    data = data,
                )
            }

    // -- plumbing ---------------------------------------------------------------------

    private suspend fun read(url: String): ZillitResult<JsonElement?> =
        apiClient.envelope(verb = HttpVerb.Get, url = url, module = RequestModule.ProjectUser)
            .flatMap { it.refusedOrOk() }
            .map(ApiEnvelope::data)

    /** A write whose answer is its message; a body-less POST goes out with no body at all. */
    private suspend fun write(verb: HttpVerb, url: String, body: JsonObject? = null): ZillitResult<String?> =
        apiClient.envelope(verb = verb, url = url, module = RequestModule.ProjectUser, body = body)
            .flatMap { it.refusedOrOk() }
            .map { it.message?.takeIf(String::isNotBlank) }
}

private const val HTTP_OK = 200
private val ATTACHMENT_KEYS = listOf("media", "bucket", "region")

private fun tierConfigOf(json: JsonObject): ApprovalTierConfig = ApprovalTierConfig(
    scope = DocRead.text(json, "scope").orEmpty(),
    departmentId = DocRead.text(json, "department_id"),
    tiers = DocRead.objects(json["tiers"]).map { tier ->
        ApprovalTier(
            order = DocRead.number(tier, "order")?.toInt() ?: 0,
            rules = DocRead.objects(tier["rules"]).map { rule ->
                DocRead.array(rule["user_ids"]).mapNotNull { (it as? JsonPrimitive)?.content }
            },
        )
    },
)

/** `template_data ?? form ?? row` — an object, or a JSON string of one. */
internal fun templateForm(row: JsonObject): JsonObject? {
    val candidate = row["template_data"]?.takeUnless { it is JsonNull }
        ?: row["form"]?.takeUnless { it is JsonNull }
        ?: row
    return when (candidate) {
        is JsonObject -> candidate
        is JsonPrimitive -> candidate.takeIf { it.isString }?.content
            ?.let { runCatching { HttpClientFactory.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        else -> null
    }
}
