@file:Suppress("TooManyFunctions") // One suspend fun per server operation.

package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.invoices.domain.Accrual
import com.zillit.desktop.feature.invoices.domain.PostcodeMatch
import com.zillit.desktop.feature.invoices.domain.ClientCountry
import com.zillit.desktop.feature.invoices.domain.TrackingSet
import com.zillit.desktop.feature.invoices.domain.VendorPo
import com.zillit.desktop.feature.invoices.domain.AccrualDetail
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CatalogueCurrency
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteWrite
import com.zillit.desktop.feature.invoices.domain.DuplicateFlag
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.EntryWrite
import com.zillit.desktop.feature.invoices.domain.InvoiceProjectSettings
import com.zillit.desktop.feature.invoices.domain.PeriodLock
import com.zillit.desktop.feature.invoices.domain.QueryThread
import com.zillit.desktop.feature.invoices.domain.QuickEntry
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.HoldReason
import com.zillit.desktop.feature.invoices.domain.InboxAccept
import com.zillit.desktop.feature.invoices.domain.ServerBatch
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAnalytics
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
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
import com.zillit.desktop.feature.invoices.domain.PurchaseOrderRecord
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunDetail
import com.zillit.desktop.feature.invoices.domain.PostedLedger
import com.zillit.desktop.feature.invoices.domain.RunCreated
import com.zillit.desktop.feature.invoices.domain.WireAttachmentsChange
import io.ktor.http.encodeURLParameter
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceWrite
import com.zillit.desktop.feature.invoices.domain.Vendor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock

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
    private val queries = "${hub}account-hub/queries"

    /** The purchase-order service — read lazily, so a host without it configured still builds this. */
    private val purchaseOrders by lazy { config.apiV2(ZillitService.PurchaseOrder).trimEnd('/') + "/purchase-orders" }

    /** The core API's presets — the countries and the postcode lookup the sales address reads. */
    private val preset = config.apiV2(ZillitService.Core).trimEnd('/') + "/preset"

    /** The core API's currency catalogue — every code, with the country it belongs to. */
    private val presetCurrencies by lazy { config.apiV2(ZillitService.Core).trimEnd('/') + "/preset/currencies" }

    /** The cost report's close boundary — the lock route on the cost-report service. */
    private val lockUrl = config.apiV2(ZillitService.CostReport).trimEnd('/') + "/cost-reports/lock-period"

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

    /** See [InvoicesRepository.queryUpdates] — this production's invoice threads only. */
    override val queryUpdates: Flow<String> =
        bus?.onAny(QUERY_SYNC_EVENTS, QuerySyncEnvelope.serializer())
            ?.mapNotNull { (_, envelope) -> envelope.invoiceId(currentProjectId()) }
            ?: emptyFlow()

    override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = get(
        base,
        buildMap {
            if (query.statuses.isNotEmpty()) put("status", query.statuses.joinToString(",") { it.wire })
            query.departmentId?.takeIf { it.isNotBlank() }?.let { put("department_id", it) }
            query.search?.trim()?.takeIf { it.isNotEmpty() }?.let { put("search", it) }
            query.perPage?.let { put("perPage", it.toString()) }
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

    override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> =
        mutate(HttpVerb.Post, base, enteredInvoiceBody(entered)).mapData { parseInvoice(it as? JsonObject) }

    override suspend fun delete(id: String): ZillitResult<Unit> = mutate(HttpVerb.Delete, "$base/$id", null).unit()

    override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> =
        mutate(HttpVerb.Post, "$base/$id/approve", approveBody(tierNumber, totalTiers)).mapData {
            parseInvoice(it as? JsonObject)
        }

    override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> =
        mutate(HttpVerb.Post, "$base/$id/reject", rejectBody(reason)).mapData { parseInvoice(it as? JsonObject) }

    /** No body, as the web's `invoicesApi.chase` sends none. */
    override suspend fun chase(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/chase", null).unit()

    override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = get("$base/$id/history").mapData(
        ::parseHistory,
    )

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

    override suspend fun purchaseOrder(id: String): ZillitResult<PurchaseOrderRecord> =
        when (val parsed = get("$purchaseOrders/$id").mapData(::parsePurchaseOrder)) {
            is ZillitResult.Failure -> parsed
            is ZillitResult.Success -> parsed.data?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization(technical = "purchase order $id: no record in data"))
        }

    override suspend fun purchaseOrderPdf(id: String): ZillitResult<InvoiceAttachment> {
        val parsed = mutate(HttpVerb.Post, "$purchaseOrders/$id/pdf", poPdfBody()).mapData(::parsePoPdfAttachment)
        return when (parsed) {
            is ZillitResult.Failure -> parsed
            is ZillitResult.Success -> parsed.data?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization(technical = "purchase order $id: no pdf attachment"))
        }
    }

    override suspend fun overrideWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/$id/override", buildJsonObject {}).message()

    override suspend fun sendToApprovalWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/$id/send-to-approval", null).message()

    override suspend fun releaseWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/$id/release", null).message()

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
            // The fixed English value, never the translated label.
            put("holdReason", JsonPrimitive(reason.wire))
            put("notes", JsonPrimitive(notes.trim()))
        },
    ).unit()

    override suspend fun release(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/release", null).unit()

    /** No payload: the server derives everything from the URL and the caller (`invoices.js`). */
    override suspend fun override(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/override", buildJsonObject {}).unit()

    /** `{}`, as the web's `invoicesApi.unmatch` sends. */
    override suspend fun unmatch(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/unmatch", buildJsonObject {}).unit()

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

    override suspend fun createCreditNote(write: CreditNoteWrite): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/credit-notes", creditNoteBody(write)).unit()

    override suspend fun updateCreditNote(id: String, write: CreditNoteWrite): ZillitResult<Unit> =
        mutate(HttpVerb.Patch, "$base/credit-notes/$id", creditNoteBody(write)).unit()

    override suspend fun deleteCreditNote(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/credit-notes/$id", null).unit()

    override suspend fun creditNoteHistory(id: String): ZillitResult<List<HistoryEntry>> =
        get("$base/credit-notes/$id/history").mapData(::parseHistory)

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

    override suspend fun paymentRun(id: String): ZillitResult<PaymentRunDetail> =
        get("$base/active-runs/$id").mapData(::parseRunDetail)

    override suspend fun approvePaymentRun(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/active-runs/$id/approve", runApproveBody(tierNumber, totalTiers)).unit()

    override suspend fun rejectPaymentRun(id: String, reason: String): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/active-runs/$id/reject",
        buildJsonObject { put("reason", JsonPrimitive(reason.trim())) },
    ).unit()

    override suspend fun deletePaymentRun(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/active-runs/$id", null).unit()

    // The decisions again, keeping the envelope's `message` for the toast.

    override suspend fun approveWithMessage(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/$id/approve", approveBody(tierNumber, totalTiers)).message()

    override suspend fun rejectWithMessage(id: String, reason: String): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/$id/reject", rejectBody(reason)).message()

    override suspend fun deleteWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Delete, "$base/$id", null).message()

    override suspend fun approvePaymentRunWithMessage(
        id: String,
        tierNumber: Int,
        totalTiers: Int,
    ): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/active-runs/$id/approve", runApproveBody(tierNumber, totalTiers)).message()

    override suspend fun rejectPaymentRunWithMessage(id: String, reason: String): ZillitResult<String?> = mutate(
        HttpVerb.Post,
        "$base/active-runs/$id/reject",
        buildJsonObject { put("reason", JsonPrimitive(reason.trim())) },
    ).message()

    override suspend fun markPaid(ids: List<String>): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/bulk-update", bulkUpdateBody(ids, "status", InvoiceStatus.Paid.wire)).unit()

    // -- Payment Runs, answering the envelope's `message` (PaymentsPage.jsx) ---

    /** The new run's id is `json.data.id` — what the page lands on once the runs are made. */
    override suspend fun createPaymentRunWithMessage(
        name: String,
        number: String,
        payMethod: PayMethod,
        invoiceIds: List<String>,
    ): ZillitResult<RunCreated> =
        mutate(HttpVerb.Post, "$base/active-runs", runBody(name, number, payMethod, invoiceIds)).mapEnvelope {
            RunCreated(
                id = (it.data as? JsonObject)?.text("id", "_id").orEmpty(),
                message = it.message?.takeIf(String::isNotBlank),
            )
        }

    override suspend fun deletePaymentRunWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Delete, "$base/active-runs/$id", null).message()

    override suspend fun markPaidWithMessage(ids: List<String>): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/bulk-update", bulkUpdateBody(ids, "status", InvoiceStatus.Paid.wire)).message()

    /** The answer is the invoice, whose `wire_attachments` is the list as it now stands. */
    override suspend fun uploadWireAttachment(
        id: String,
        attachment: InvoiceAttachment,
        mimeType: String,
        size: Long,
    ): ZillitResult<WireAttachmentsChange> = mutate(
        HttpVerb.Post,
        "$base/$id/wire-attachments",
        wireAttachmentBody(attachment, mimeType, size),
    ).mapEnvelope(::wireAttachmentsChange)

    /** Removed by its S3 key, URL-encoded as the web's `encodeURIComponent`. */
    override suspend fun removeWireAttachment(id: String, media: String): ZillitResult<WireAttachmentsChange> =
        mutate(HttpVerb.Delete, "$base/$id/wire-attachments/${media.encodeURLParameter()}", null)
            .mapEnvelope(::wireAttachmentsChange)

    /** `total` rides beside `data` — the web's `Number(json.total) || invoices.length`. */
    override suspend fun postedLedger(): ZillitResult<PostedLedger> =
        get("$base/posted", mapOf("perPage" to POSTED_PAGE.toString())).mapEnvelope { envelope ->
            val rows = rowsOf(envelope.data).mapNotNull(::parseInvoice)
            val total = (envelope.total ?: (envelope.data as? JsonObject)?.get("total"))
                ?.let { (it as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.toInt() }
                ?.takeIf { it > 0 }
            PostedLedger(rows = rows, total = total ?: rows.size)
        }

    // -- sales invoices ------------------------------------------------------

    /** `perPage`, as every other list here sends it — the web's `list({ perPage: 200 })`. */
    override suspend fun salesInvoices(): ZillitResult<List<SalesInvoice>> =
        get("$base/sales-invoices", mapOf("perPage" to SALES_PAGE.toString())).mapData(::parseSalesInvoices)

    override suspend fun createSalesInvoice(invoice: SalesInvoiceWrite): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/sales-invoices", salesInvoiceBody(invoice)).unit()

    override suspend fun sendSalesInvoice(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/sales-invoices/$id/send", null).unit()

    override suspend fun markSalesInvoicePaid(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/sales-invoices/$id/paid", null).unit()

    override suspend fun deleteSalesInvoice(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/sales-invoices/$id", null).unit()

    override suspend fun salesInvoice(id: String): ZillitResult<SalesInvoice> =
        when (val parsed = get("$base/sales-invoices/$id").mapData(::parseSalesInvoice)) {
            is ZillitResult.Failure -> parsed
            is ZillitResult.Success -> parsed.data?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization(technical = "sales invoice $id: no record in data"))
        }

    override suspend fun updateSalesInvoice(id: String, invoice: SalesInvoiceWrite): ZillitResult<Unit> =
        mutate(HttpVerb.Patch, "$base/sales-invoices/$id", salesInvoiceBody(invoice)).unit()

    override suspend fun salesInvoiceHistory(id: String): ZillitResult<List<HistoryEntry>> =
        get("$base/sales-invoices/$id/history").mapData(::parseHistory)

    override suspend fun createSalesInvoiceWithMessage(invoice: SalesInvoiceWrite): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/sales-invoices", salesInvoiceBody(invoice)).message()

    override suspend fun updateSalesInvoiceWithMessage(id: String, invoice: SalesInvoiceWrite): ZillitResult<String?> =
        mutate(HttpVerb.Patch, "$base/sales-invoices/$id", salesInvoiceBody(invoice)).message()

    override suspend fun sendSalesInvoiceWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/sales-invoices/$id/send", null).message()

    override suspend fun deleteSalesInvoiceWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Delete, "$base/sales-invoices/$id", null).message()

    // -- credit notes, settings, accruals, vendors: the server's own word -----

    override suspend fun createCreditNoteWithMessage(write: CreditNoteWrite): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/credit-notes", creditNoteBody(write)).message()

    override suspend fun updateCreditNoteWithMessage(id: String, write: CreditNoteWrite): ZillitResult<String?> =
        mutate(HttpVerb.Patch, "$base/credit-notes/$id", creditNoteBody(write)).message()

    override suspend fun deleteCreditNoteWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Delete, "$base/credit-notes/$id", null).message()

    override suspend fun applyCreditNoteWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/credit-notes/$id/apply", null).message()

    override suspend fun saveTeamWithMessage(rows: List<InvoiceTeamRow>): ZillitResult<String?> =
        mutate(HttpVerb.Patch, "$base/settings", teamBody(rows)).message()

    override suspend fun saveAlertsWithMessage(alerts: Set<String>): ZillitResult<String?> =
        mutate(HttpVerb.Patch, "$base/settings", alertsBody(alerts)).message()

    override suspend fun saveRunAuthorisationWithMessage(levels: List<RunAuthLevel>): ZillitResult<String?> =
        mutate(HttpVerb.Patch, "$base/settings", runAuthBody(levels)).message()

    override suspend fun accrualDetail(id: String): ZillitResult<AccrualDetail> =
        when (val parsed = get("$base/accruals/$id").mapData(::parseAccrualDetail)) {
            is ZillitResult.Failure -> parsed
            is ZillitResult.Success -> parsed.data?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization(technical = "accrual $id: not found"))
        }

    override suspend fun vendorPurchaseOrders(): ZillitResult<List<VendorPo>> =
        get(purchaseOrders, mapOf("per_page" to VENDOR_PO_PAGE.toString())).mapData(::parseVendorPos)

    override suspend fun trackingSets(): ZillitResult<List<TrackingSet>> = get(
        "${hub}account-hub/tracking-sets",
        mapOf("active_only" to "true", "include_nodes" to "true"),
    ).mapData(::parseTrackingSets)

    override suspend fun countries(): ZillitResult<List<ClientCountry>> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = "$preset/isd-codes",
        module = RequestModule.Device,
    ).mapData(::parseCountries)

    override suspend fun postcodePlace(countryCode: String, postcode: String): ZillitResult<PostcodeMatch> =
        apiClient.envelope(
            verb = HttpVerb.Get,
            url = "$preset/geonames/postalcode/${countryCode.pathSegment()}/${postcode.pathSegment()}",
            module = RequestModule.Device,
        ).mapData(::parsePostcodeMatch)

    // -- the entry stage -----------------------------------------------------

    override suspend fun postInvoice(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/post", null).unit()

    override suspend fun returnToApproval(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/return-to-approval", null).unit()

    /** The web's bulk Submit for Review sends when it happened, too. */
    override suspend fun markUnderReview(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Patch, "$base/$id", underReviewBody(Clock.System.now().toEpochMilliseconds())).unit()

    // -- the ledger view -----------------------------------------------------

    override suspend fun saveEntry(id: String, write: EntryWrite): ZillitResult<Unit> =
        saveEntryWithMessage(id, write).map { }

    override suspend fun saveEntryWithMessage(id: String, write: EntryWrite): ZillitResult<String?> = mutate(
        HttpVerb.Patch,
        "$base/$id",
        entryUpdateBody(
            header = write.header,
            lines = write.lines,
            tax = write.taxLine,
            taxAmount = write.taxAmount,
            savedLinesJson = write.savedLinesJson,
            chart = write.chart,
            status = write.status,
            amounts = write.amounts,
        ),
    ).message()

    override suspend fun quickEntry(entry: QuickEntry): ZillitResult<Unit> = quickEntryWithMessage(entry).map { }

    override suspend fun quickEntryWithMessage(entry: QuickEntry): ZillitResult<String?> = mutate(
        HttpVerb.Post,
        base,
        quickEntryBody(entry),
    ).message()

    override suspend fun postInvoiceWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/$id/post", null).message()

    override suspend fun returnToApprovalWithMessage(id: String): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/$id/return-to-approval", null).message()

    override suspend fun projectSettings(): ZillitResult<InvoiceProjectSettings> =
        get("${hub}account-hub/project-settings").mapData(::parseProjectSettings)

    /**
     * Two readings of one row, as the web's `useCrLock` takes them: the lock
     * route and the settings document. The later date wins because the lock
     * only moves forward; the settings reading stands in when the route fails.
     */
    override suspend fun periodLock(): ZillitResult<PeriodLock> = coroutineScope {
        val route = async { get(lockUrl).mapData(::parsePeriodLock) }
        val settings = async { projectSettings() }
        val answered = route.await()
        val merged = PeriodLock.later(
            (answered as? ZillitResult.Success)?.data,
            (settings.await() as? ZillitResult.Success)?.data?.lock,
        )
        when {
            merged != null -> ZillitResult.Success(merged)
            answered is ZillitResult.Failure -> answered
            else -> ZillitResult.Success(PeriodLock())
        }
    }

    override suspend fun chartCodes(): ZillitResult<Set<String>> =
        get("${hub}account-hub/chart-of-accounts", mapOf("active_only" to "true")).mapData(::parseChartCodes)

    // -- the inbox --------------------------------------------------------------

    override suspend fun process(ids: List<String>, accept: InboxAccept?): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/process", processBody(ids, accept)).unit()

    override suspend fun matchNote(id: String, poId: String, note: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/$id/match", matchNoteBody(poId, note)).unit()

    override suspend fun bulkUpload(
        batchId: String,
        attachment: InvoiceAttachment,
        size: Long,
        paid: Boolean,
    ): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/bulk-upload", bulkUploadBody(batchId, attachment, size, paid)).unit()

    override suspend fun bulkBatches(): ZillitResult<List<ServerBatch>> =
        get("$base/bulk-upload/batches").mapData(::parseBulkBatches)

    /** See [InvoicesRepository.bulkProgress]; another production's frame is dropped, as [refreshes] drops it. */
    override val bulkProgress: Flow<ServerBatch> =
        bus?.on(BULK_PROGRESS_EVENT)
            ?.mapNotNull { message ->
                val project = frameProject(message.payload)
                val here = currentProjectId()
                if (project != null && here != null && project != here) null else parseBulkProgress(message.payload)
            }
            ?: emptyFlow()

    override suspend fun processWithMessage(ids: List<String>, accept: InboxAccept?): ZillitResult<String?> =
        mutate(HttpVerb.Post, "$base/process", processBody(ids, accept)).message()

    override suspend fun createEnteredWithMessage(entered: EnteredInvoice): ZillitResult<String?> =
        mutate(HttpVerb.Post, base, enteredInvoiceBody(entered)).message()

    override suspend fun createVendor(name: String): ZillitResult<Vendor> {
        val created = mutate(HttpVerb.Post, "${hub}vendors", buildJsonObject { put("name", JsonPrimitive(name.trim())) })
            .mapData(::parseCreatedVendor)
        return when (created) {
            is ZillitResult.Failure -> created
            is ZillitResult.Success -> created.data?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization(technical = "vendor create: no record in data"))
        }
    }

    override suspend fun currencyCatalogue(): ZillitResult<List<CatalogueCurrency>> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = presetCurrencies,
        module = RequestModule.Device,
    ).mapData(::parseCurrencyCatalogue)

    // -- queries ---------------------------------------------------------------

    override suspend fun queryThread(invoiceId: String): ZillitResult<QueryThread> =
        get("$queries/entity/$QUERY_ENTITY/$invoiceId").mapData(::parseQueryThread)

    override suspend fun openQuery(invoiceId: String, text: String): ZillitResult<QueryThread> =
        mutate(HttpVerb.Post, queries, queryOpenBody(invoiceId, text)).mapData(::parseQueryThread)

    override suspend fun addQuery(threadId: String, text: String): ZillitResult<QueryThread> =
        mutate(HttpVerb.Post, "$queries/$threadId/add", queryAddBody(text)).mapData(::parseQueryThread)

    override suspend fun assign(id: String, userId: String, reason: String): ZillitResult<Unit> = mutate(
        HttpVerb.Patch,
        "$base/$id",
        assignBody(userId, reason, Clock.System.now().toEpochMilliseconds()),
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

    /** As [mapData], over the whole envelope — for a write whose `message` and `data` both matter. */
    private inline fun <T> ZillitResult<ApiEnvelope>.mapEnvelope(transform: (ApiEnvelope) -> T): ZillitResult<T> =
        when (this) {
            is ZillitResult.Failure -> ZillitResult.Failure(error)
            is ZillitResult.Success -> if (data.status == 1) {
                ZillitResult.Success(transform(data))
            } else {
                ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = data.message))
            }
        }

    /** `result.data || result` — the updated invoice — and its `wire_attachments`. */
    private fun wireAttachmentsChange(envelope: ApiEnvelope): WireAttachmentsChange {
        val invoice = (envelope.data as? JsonObject)?.let { data -> (data["invoice"] as? JsonObject) ?: data }
        return WireAttachmentsChange(
            attachments = invoice?.let(::parseWireAttachments).orEmpty(),
            message = envelope.message?.takeIf(String::isNotBlank),
        )
    }

    /** A successful write's `message` key, blank as null; a refusal is a failure as everywhere else. */
    private fun ZillitResult<ApiEnvelope>.message(): ZillitResult<String?> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> if (data.status == 1) {
            ZillitResult.Success(data.message?.takeIf { it.isNotBlank() })
        } else {
            ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = data.message))
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val VENDOR_PAGE = 500
        const val POSTED_PAGE = 500
        const val CREDIT_NOTE_PAGE = 200
        const val SALES_PAGE = 200

        /** The web's `purchase-orders?per_page=200` for the vendor history. */
        const val VENDOR_PO_PAGE = 200

        /** What the hub files this module's assignment rules under. */
        const val RULE_MODULE = "invoices"
    }
}
