@file:Suppress("TooManyFunctions") // One suspend fun per server operation.

package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.invoices.domain.Accrual
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.DepartmentUpload
import com.zillit.desktop.feature.invoices.domain.DuplicateFlag
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.HoldReason
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAnalytics
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceExtraction
import com.zillit.desktop.feature.invoices.domain.InvoiceOverview
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceRefresh
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignmentRule
import com.zillit.desktop.feature.invoices.domain.InvoiceNominal
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceSetupBundle
import com.zillit.desktop.feature.invoices.domain.InvoiceTeamRow
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.LinkedPoDetail
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PoSuggestion
import com.zillit.desktop.feature.invoices.domain.PoSuggestions
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.Vendor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `api/v2/invoices` on the invoices service, plus the three Account Hub reads
 * (approval tiers, vendors, bank accounts) the screens need alongside it.
 *
 * Every call answers the standard envelope; `status:0` inside a 200 is a
 * failure and its `message` is a translation key (`override_permission_required`,
 * `cannot_override_held_invoice`…). Empty lists may arrive as `data: {}`.
 */
class InvoicesRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : InvoicesRepository {

    private val base = config.apiV2(ZillitService.Invoices).trimEnd('/') + "/invoices"
    private val hub = config.apiV2(ZillitService.AccountHub).trimEnd('/') + "/"

    /**
     * See [InvoicesRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project — the same cross-project
     * gate the web's account-hub wrapper applies before any handler runs.
     */
    override val refreshes: Flow<InvoiceRefresh> =
        bus?.onAny(INVOICE_SYNC_EVENTS, InvoiceSyncEnvelope.serializer())
            ?.mapNotNull { (event, envelope) ->
                invoiceRefreshFor(event).takeIf { envelope.inProject(currentProjectId()) }
            }
            ?: emptyFlow()

    override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = get(
        base,
        buildMap {
            if (query.statuses.isNotEmpty()) put("status", query.statuses.joinToString(",") { it.wire })
            query.departmentId?.takeIf { it.isNotBlank() }?.let { put("department_id", it) }
            query.search?.trim()?.takeIf { it.isNotEmpty() }?.let { put("search", it) }
            put("perPage", query.perPage.toString())
        },
    ).mapData { rowsOf(it).mapNotNull(::parseInvoice) }

    override suspend fun approvalQueue(): ZillitResult<List<Invoice>> =
        get("$base/approval").mapData { rowsOf(it).mapNotNull(::parseInvoice) }

    override suspend fun mine(): ZillitResult<List<Invoice>> =
        get("$base/my").mapData { rowsOf(it).mapNotNull(::parseInvoice) }

    override suspend fun invoice(id: String): ZillitResult<Invoice> {
        val parsed = get("$base/$id").mapData { data ->
            val obj = data as? JsonObject
            parseInvoice(obj) ?: parseInvoice(obj?.get("invoice") as? JsonObject)
        }
        return when (parsed) {
            is ZillitResult.Failure -> parsed
            is ZillitResult.Success -> parsed.data?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization(technical = "invoice $id: no record in data"))
        }
    }

    override suspend fun createFromUpload(upload: DepartmentUpload): ZillitResult<Invoice?> =
        mutate(HttpVerb.Post, base, departmentUploadBody(upload)).mapData { parseInvoice(it as? JsonObject) }

    override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> =
        mutate(HttpVerb.Post, base, enteredInvoiceBody(entered)).mapData { parseInvoice(it as? JsonObject) }

    override suspend fun patchStatus(
        id: String,
        status: InvoiceStatus,
        approvalStatus: ApprovalStatus,
    ): ZillitResult<Invoice?> =
        mutate(HttpVerb.Patch, "$base/$id", statusPatchBody(status, approvalStatus)).mapData {
            parseInvoice(it as? JsonObject)
        }

    override suspend fun delete(id: String): ZillitResult<Unit> = mutate(HttpVerb.Delete, "$base/$id", null).unit()

    override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> =
        mutate(HttpVerb.Post, "$base/$id/approve", approveBody(tierNumber, totalTiers)).mapData {
            parseInvoice(it as? JsonObject)
        }

    override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> =
        mutate(HttpVerb.Post, "$base/$id/reject", rejectBody(reason)).mapData { parseInvoice(it as? JsonObject) }

    override suspend fun chase(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/chase", buildJsonObject {}).unit()

    override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = get("$base/$id/history").mapData(
        ::parseHistory,
    )

    override suspend fun extract(attachment: InvoiceAttachment): ZillitResult<InvoiceExtraction> = mutate(
        HttpVerb.Post,
        "$base/upload",
        buildJsonObject { put("attachment", attachmentWire(attachment)) },
    ).mapData(::parseExtraction)

    override suspend fun settings(): ZillitResult<InvoiceSettings> = get("$base/settings").mapData(::parseSettings)

    // -- the Settings page ---------------------------------------------------

    override suspend fun setup(): ZillitResult<InvoiceSetupBundle> = get("$base/settings").mapData(::parseSetup)

    override suspend fun saveTeam(rows: List<InvoiceTeamRow>): ZillitResult<Unit> =
        mutate(HttpVerb.Patch, "$base/settings", teamBody(rows)).unit()

    override suspend fun saveAlerts(alerts: Set<String>): ZillitResult<Unit> =
        mutate(HttpVerb.Patch, "$base/settings", alertsBody(alerts)).unit()

    override suspend fun saveRunAuthorisation(levels: List<RunAuthLevel>): ZillitResult<Unit> =
        mutate(HttpVerb.Patch, "$base/settings", runAuthBody(levels)).unit()

    /** The module rides in the body on create; on update the rule's id already says which. */
    override suspend fun createRule(rule: InvoiceAssignmentRule): ZillitResult<InvoiceAssignmentRule> =
        mutate(HttpVerb.Post, "${hub}account-hub/assignment-rules", ruleBody(rule, RULE_MODULE))
            .mapData { parseRule(it) ?: rule.copy(persisted = true) }

    override suspend fun updateRule(rule: InvoiceAssignmentRule): ZillitResult<InvoiceAssignmentRule> =
        mutate(HttpVerb.Patch, "${hub}account-hub/assignment-rules/${'$'}{rule.id}", ruleBody(rule))
            .mapData { parseRule(it) ?: rule }

    override suspend fun deleteRule(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "${hub}account-hub/assignment-rules/$id", null).unit()

    override suspend fun nominalCodes(): ZillitResult<List<InvoiceNominal>> =
        get("${hub}account-hub/chart-of-accounts", mapOf("active_only" to "true")).mapData(::parseNominals)

    // -- PO matching ---------------------------------------------------------

    override suspend fun poSuggestions(id: String, vendorId: String?): ZillitResult<PoSuggestions> = get(
        "$base/$id/po-suggestions",
        vendorId?.takeIf { it.isNotBlank() }?.let { mapOf("vendor_id" to it) }.orEmpty(),
    ).mapData(::parsePoSuggestions)

    override suspend fun match(id: String, suggestion: PoSuggestion): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/match", matchBody(suggestion)).unit()

    override suspend fun linkedPos(id: String): ZillitResult<List<LinkedPoDetail>> =
        get("$base/$id/linked-pos").mapData(::parseLinkedPos)

    override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> =
        get("${hub}account-hub/approval-tiers", mapOf("module" to "invoices")).mapData(::parseTierConfigs)

    override suspend fun vendors(): ZillitResult<List<Vendor>> =
        get("${hub}vendors", mapOf("perPage" to VENDOR_PAGE.toString())).mapData(::parseVendors)

    override suspend fun sendToApproval(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/send-to-approval", null).unit()

    /** The body is camelCase here, alone among this service's writes — see the wire note. */
    override suspend fun hold(id: String, reason: HoldReason, notes: String): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/$id/hold",
        buildJsonObject {
            put("holdReason", JsonPrimitive(reason.label))
            put("notes", JsonPrimitive(notes.trim()))
        },
    ).unit()

    override suspend fun release(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/release", null).unit()

    override suspend fun override(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/override", null).unit()

    override suspend fun unmatch(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/unmatch", null).unit()

    override suspend fun postedInvoices(): ZillitResult<List<Invoice>> =
        get("$base/posted", mapOf("perPage" to POSTED_PAGE.toString()))
            .mapData { rowsOf(it).mapNotNull(::parseInvoice) }

    override suspend fun accruals(): ZillitResult<List<Accrual>> =
        get("$base/accruals").mapData(::parseAccruals)

    override suspend fun regenerateAccruals(): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/accruals/regenerate", null).unit()

    override suspend fun creditNotes(): ZillitResult<List<CreditNote>> =
        get("$base/credit-notes", mapOf("perPage" to CREDIT_NOTE_PAGE.toString())).mapData(::parseCreditNotes)

    override suspend fun applyCreditNote(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/credit-notes/$id/apply", null).unit()

    override suspend fun disputeCreditNote(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/credit-notes/$id/dispute", null).unit()

    override suspend fun analytics(): ZillitResult<InvoiceAnalytics> =
        get("$base/analytics").mapData(::parseAnalytics)

    override suspend fun overview(): ZillitResult<InvoiceOverview> =
        get("$base/analytics/overview").mapData(::parseOverview)

    override suspend fun duplicates(): ZillitResult<List<DuplicateFlag>> =
        get("$base/duplicates").mapData(::parseDuplicates)

    override suspend fun dismissDuplicate(flagId: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/duplicates/$flagId/dismiss", null).unit()

    override suspend fun confirmDuplicate(flagId: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/duplicates/$flagId/confirm", null).unit()

    override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> =
        get("${hub}account-hub/bank-accounts", mapOf("entity_type" to "production")).mapData(::parseBankAccounts)

    // -- payment runs --------------------------------------------------------

    override suspend fun paymentRuns(): ZillitResult<List<PaymentRun>> =
        get("$base/active-runs").mapData(::parseRuns)

    override suspend fun createPaymentRun(
        name: String,
        number: String,
        payMethod: PayMethod,
        invoiceIds: List<String>,
    ): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/active-runs", runBody(name, number, payMethod, invoiceIds)).unit()

    override suspend fun approvePaymentRun(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/active-runs/$id/approve", buildJsonObject {}).unit()

    override suspend fun rejectPaymentRun(id: String, reason: String): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/active-runs/$id/reject",
        buildJsonObject { put("reason", JsonPrimitive(reason.trim())) },
    ).unit()

    override suspend fun deletePaymentRun(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/active-runs/$id", null).unit()

    override suspend fun markPaid(ids: List<String>): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/bulk-update", bulkUpdateBody(ids, "status", InvoiceStatus.Paid.wire)).unit()

    // -- sales invoices ------------------------------------------------------

    override suspend fun salesInvoices(): ZillitResult<List<SalesInvoice>> =
        get("$base/sales-invoices", mapOf("per_page" to SALES_PAGE)).mapData(::parseSalesInvoices)

    override suspend fun createSalesInvoice(invoice: SalesInvoice): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/sales-invoices", salesInvoiceBody(invoice)).unit()

    override suspend fun sendSalesInvoice(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/sales-invoices/$id/send", null).unit()

    override suspend fun markSalesInvoicePaid(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/sales-invoices/$id/paid", null).unit()

    override suspend fun deleteSalesInvoice(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/sales-invoices/$id", null).unit()

    // -- the entry stage -----------------------------------------------------

    override suspend fun postInvoice(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/post", null).unit()

    override suspend fun returnToApproval(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/return-to-approval", null).unit()

    override suspend fun markUnderReview(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Patch, "$base/$id", buildJsonObject { put("status", JsonPrimitive("under_review")) }).unit()

    override suspend fun assign(id: String, userId: String, reason: String): ZillitResult<Unit> = mutate(
        HttpVerb.Patch,
        "$base/$id",
        buildJsonObject {
            put("assigned_to", JsonPrimitive(userId))
            put("assignment_reason", JsonPrimitive(reason))
        },
    ).unit()

    // -- plumbing ------------------------------------------------------------

    private suspend fun get(url: String, query: Map<String, Any?> = emptyMap()) = apiClient.envelope(
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

    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> =
        when (this) {
            is ZillitResult.Failure -> ZillitResult.Failure(error)
            is ZillitResult.Success -> if (data.status == 1) {
                ZillitResult.Success(transform(data.data))
            } else {
                ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = data.message))
            }
        }

    private fun ZillitResult<ApiEnvelope>.unit(): ZillitResult<Unit> = mapData { }

    private companion object {
        const val HTTP_OK = 200
        const val VENDOR_PAGE = 500
        const val POSTED_PAGE = 500
        const val CREDIT_NOTE_PAGE = 200
        const val SALES_PAGE = 200

        /** What the hub files this module's assignment rules under. */
        const val RULE_MODULE = "invoices"
    }
}
