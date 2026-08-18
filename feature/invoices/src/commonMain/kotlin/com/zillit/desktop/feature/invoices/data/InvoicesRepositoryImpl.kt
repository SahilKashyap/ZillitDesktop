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
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.DepartmentUpload
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceExtraction
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.Vendor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
) : InvoicesRepository {

    private val base = config.apiV2(ZillitService.Invoices).trimEnd('/') + "/invoices"
    private val hub = config.apiV2(ZillitService.AccountHub).trimEnd('/') + "/"

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

    override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> =
        get("${hub}account-hub/approval-tiers", mapOf("module" to "invoices")).mapData(::parseTierConfigs)

    override suspend fun vendors(): ZillitResult<List<Vendor>> =
        get("${hub}vendors", mapOf("perPage" to VENDOR_PAGE.toString())).mapData(::parseVendors)

    override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> =
        get("${hub}account-hub/bank-accounts", mapOf("entity_type" to "production")).mapData(::parseBankAccounts)

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
    }
}
