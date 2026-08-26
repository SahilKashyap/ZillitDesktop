package com.zillit.desktop.feature.purchaseorder.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoApproval
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoRefresh
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Every `/api/v2/purchase-orders` route.
 *
 * Vendors are served by the account hub rather than by this service — the same
 * vendor list backs invoices and cash — so the two hosts are held separately.
 * Asking the purchase-order host for them answers `route_not_found`.
 */
class PurchaseOrderRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : PurchaseOrderRepository {

    private val base = "${config.baseUrl(ZillitService.PurchaseOrder)}/api/v2/purchase-orders"
    private val vendorsUrl = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/vendors"

    /**
     * See [PurchaseOrderRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project — the same cross-project
     * gate the web's account-hub wrapper applies before any handler runs.
     */
    override val refreshes: Flow<PoRefresh> =
        bus?.onAny(PO_SYNC_EVENTS, PoSyncEnvelope.serializer())
            ?.mapNotNull { (event, envelope) ->
                poRefreshFor(event).takeIf { envelope.inProject(currentProjectId()) }
            }
            ?: emptyFlow()

    override suspend fun orders(status: PoStatus?): ZillitResult<List<PurchaseOrder>> =
        list(base, status?.let { mapOf("status" to it.wire) }.orEmpty())

    override suspend fun approvalQueue(): ZillitResult<List<PurchaseOrder>> = list("$base/approval")

    override suspend fun myOrders(): ZillitResult<List<PurchaseOrder>> = list("$base/my")

    override suspend fun order(id: String): ZillitResult<PurchaseOrder> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/$id",
            serializer = PoDto.serializer(),
            module = RequestModule.ProjectUser,
        ).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("order $id came back without an id"))
        }

    override suspend fun history(id: String): ZillitResult<List<PoHistoryEntry>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/$id/history",
            serializer = ListSerializer(PoHistoryDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.map { it.toDomain() } }

    override suspend fun create(order: NewPurchaseOrder): ZillitResult<Unit> =
        post(base, order.body())

    override suspend fun update(id: String, order: NewPurchaseOrder): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Patch, "$base/$id", RequestModule.ProjectUser, order.body()).map { }

    override suspend fun delete(id: String): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Delete, "$base/$id", RequestModule.ProjectUser).map { }

    override suspend fun approve(id: String, note: String?): ZillitResult<Unit> =
        post("$base/$id/approve", noteBody(note))

    override suspend fun reject(id: String, reason: String): ZillitResult<Unit> =
        post("$base/$id/reject", buildJsonObject { put("reason", JsonPrimitive(reason)) })

    override suspend fun post(id: String, note: String?): ZillitResult<Unit> =
        post("$base/$id/post", noteBody(note))

    override suspend fun close(id: String, note: String?): ZillitResult<Unit> =
        post("$base/$id/close", noteBody(note))

    /**
     * Closes many orders at once, into one accounting period.
     *
     * The body carries an **effective date**, not a note: closing a purchase
     * order writes off its remaining commitment, and which period that lands in
     * is the whole decision. It is sent explicitly — null where the caller has
     * no date, so the server never has to tell "unset" from "absent".
     *
     * No client-side count cap: an accounting period can hold more orders than
     * the legacy bulk PATCH allowed, so they all go and the server validates.
     */
    override suspend fun closeAll(ids: List<String>, effectiveDate: Long?): ZillitResult<Unit> = post(
        "$base/bulk-close",
        buildJsonObject {
            put("po_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            put("effective_date", effectiveDate?.let { JsonPrimitive(it) } ?: JsonNull)
        },
    )

    override suspend fun vendors(): ZillitResult<List<Vendor>> = apiClient.request(
        verb = HttpVerb.Get,
        url = vendorsUrl,
        serializer = ListSerializer(VendorDto.serializer()),
        module = RequestModule.ProjectUser,
        // The list is offered in a dropdown, so the whole of it is wanted at
        // once rather than a page at a time.
        queryParameters = mapOf("limit" to VENDOR_LIMIT),
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    private suspend fun list(url: String, query: Map<String, Any?> = emptyMap()) =
        apiClient.request(
            verb = HttpVerb.Get,
            url = url,
            serializer = ListSerializer(PoDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = query,
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    private suspend fun post(url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Post, url, RequestModule.ProjectUser, body).map { }

    private fun noteBody(note: String?): JsonObject? =
        note?.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("note", JsonPrimitive(it)) } }

    private companion object {
        const val VENDOR_LIMIT = 500
    }
}

/**
 * The create/update body, field for field as Android's `CreatePORequest`
 * and the web's `POForm` send it. Note what the server does **not** take:
 * a vendor name (it keys on `vendor_id` and the client resolves the name),
 * a header `total` (it is `net_amount`, and each line carries its own
 * `total`), or a `lines` array (`line_items`). Sending the wrong keys is
 * accepted with a 200 and stored as an order with no vendor, no total and
 * no status — seen live 2026-08-18.
 */
internal fun NewPurchaseOrder.body(): JsonObject = buildJsonObject {
    putIfPresent("vendor_id", vendorId)
    put("description", JsonPrimitive(description))
    put("currency", JsonPrimitive(currency?.takeIf { it.isNotBlank() } ?: DEFAULT_PO_CURRENCY))
    putIfPresent("department_id", departmentId)
    putIfPresent("company_id", companyId)
    putIfPresent("nominal_code", nominalCode)
    putIfPresent("episode", episode)
    putIfPresent("notes", notes)
    effectiveDate?.let { put("effective_date", JsonPrimitive(it)) }
    put("net_amount", JsonPrimitive(total))
    putIfPresent("status", status)
    put(
        "line_items",
        buildJsonArray {
            lines.forEach { line ->
                add(
                    buildJsonObject {
                        putIfPresent("id", line.id)
                        put("description", JsonPrimitive(line.description))
                        put("quantity", JsonPrimitive(line.quantity))
                        put("unit_price", JsonPrimitive(line.unitPrice))
                        put("total", JsonPrimitive(line.total))
                        put("account", JsonPrimitive(line.nominalCode.orEmpty()))
                        put("department", JsonPrimitive(""))
                        put("expenditure_type", JsonPrimitive(""))
                        line.vatRate?.let { put("tax_rate", JsonPrimitive(it)) }
                    },
                )
            }
        },
    )
}

private const val DEFAULT_PO_CURRENCY = "GBP"

@Serializable
internal data class PoDto(
    @SerialName("id") val id: String? = null,
    @SerialName("po_number") val number: String? = null,
    @SerialName("vendor_id") val vendorId: String? = null,
    @SerialName("vendor_name") val vendorName: String? = null,
    @SerialName("vendor") val vendor: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("currency") val currency: String? = null,
    // The server's amounts, in order of preference — see [total] below.
    @SerialName("gross_amount") val grossAmount: JsonElement? = null,
    @SerialName("net_total") val netTotal: JsonElement? = null,
    @SerialName("net_amount") val netAmount: JsonElement? = null,
    @SerialName("gross_total") val grossTotal: JsonElement? = null,
    @SerialName("total") val total: JsonElement? = null,
    @SerialName("vat_treatment") val vatTreatment: String? = null,
    @SerialName("nominal") val nominal: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("episode") val episode: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("effective_date") val effectiveDate: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("raised_by") val raisedBy: String? = null,
    @SerialName("assigned") val assigned: String? = null,
    @SerialName("assigned_to") val assignedTo: String? = null,
    @SerialName("reassignment_reason") val reassignmentReason: String? = null,
    @SerialName("delivery") val delivery: String? = null,
    // An array on most endpoints, a JSON *string* holding an array on some —
    // Android's `parseLineItems` handles both, so this does too.
    @SerialName("line_items") val lineItems: JsonElement? = null,
    @SerialName("approvals") val approvals: List<PoApprovalDto>? = null,
    @SerialName("attachments") val attachments: List<JsonObject>? = null,
) {
    fun toDomain(): PurchaseOrder? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return PurchaseOrder(
            id = identifier,
            number = number.orEmpty(),
            vendorId = vendorId,
            vendorName = vendorName?.takeIf { it.isNotBlank() } ?: vendor.orEmpty(),
            description = description.orEmpty(),
            departmentId = departmentId,
            companyId = companyId,
            status = PoStatus.from(status),
            currency = currency,
            total = headerTotal(parsedLines),
            vatTreatment = vatTreatment,
            nominalCode = nominalCode ?: nominal,
            episode = episode,
            notes = notes,
            effectiveDate = effectiveDate.toEpochMillisOrNull(),
            createdAt = createdAt.toEpochMillisOrNull(),
            raisedBy = raisedBy ?: createdBy,
            assignedTo = assignedTo ?: assigned,
            reassignmentReason = reassignmentReason,
            deliveryAddress = delivery,
            lines = parsedLines,
            approvals = approvals.orEmpty().map { it.toDomain() },
            attachmentCount = attachments?.size ?: 0,
        )
    }

    private val parsedLines: List<PoLine> get() = lineItems.asLineItems().map { it.toDomain() }

    /**
     * The order's total, the way Android's `POMapper` and the web's `mapApiPO`
     * settle it: the server-maintained gross first, then the lines' own
     * gross, then whatever legacy field is present.
     */
    private fun headerTotal(lines: List<PoLine>): Double {
        grossAmount.toAmountOrNull()?.let { return it }
        if (lines.isNotEmpty()) return lines.sumOf { it.total * (1 + (it.vatRate ?: 0.0) / PERCENT) }
        return netTotal.toAmountOrNull() ?: netAmount.toAmountOrNull() ?: grossTotal.toAmountOrNull()
            ?: total.toAmountOrNull() ?: 0.0
    }

    companion object {
        private const val PERCENT = 100.0
    }
}

/** A number the server may send as a number, a numeric string, or not at all. */
internal fun JsonElement?.toAmountOrNull(): Double? =
    (this as? JsonPrimitive)?.contentOrNull?.toAmountOrNull()

/** `line_items` as the server sends it: an array, or a string containing one. */
internal fun JsonElement?.asLineItems(): List<PoLineDto> {
    val array = when (this) {
        is JsonArray -> this
        is JsonPrimitive -> runCatching { Json.parseToJsonElement(content) as? JsonArray }.getOrNull()
        else -> null
    } ?: return emptyList()
    return array.mapNotNull { element ->
        runCatching { lenientJson.decodeFromJsonElement(PoLineDto.serializer(), element) }.getOrNull()
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

@Serializable
internal data class PoLineDto(
    @SerialName("id") val id: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("quantity") val quantity: JsonElement? = null,
    @SerialName("unit_price") val unitPrice: JsonElement? = null,
    @SerialName("total") val total: JsonElement? = null,
    // The server's name for the cost code on a line; `nominal_code` is the
    // older spelling some endpoints still echo.
    @SerialName("account") val account: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("tax_rate") val taxRate: JsonElement? = null,
    @SerialName("vat_rate") val vatRate: JsonElement? = null,
) {
    fun toDomain(): PoLine {
        // A line with no quantity is one item, not none: the wire omits the
        // field for single-item lines and reading it as zero silently zeroes
        // the order's total. A line with a total but no unit price is priced
        // from its total.
        val qty = quantity.toAmountOrNull() ?: 1.0
        val price = unitPrice.toAmountOrNull() ?: total.toAmountOrNull()?.let { if (qty > 0) it / qty else it } ?: 0.0
        return PoLine(
            id = id,
            description = description.orEmpty(),
            quantity = qty,
            unitPrice = price,
            nominalCode = account?.takeIf { it.isNotBlank() } ?: nominalCode,
            vatRate = taxRate.toAmountOrNull() ?: vatRate.toAmountOrNull(),
        )
    }
}

@Serializable
internal data class PoApprovalDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("level") val level: Int? = null,
    @SerialName("decision") val decision: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
) {
    fun toDomain() = PoApproval(
        userId = userId,
        name = name?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
        level = level ?: 1,
        decision = decision ?: status,
        note = note,
        at = decidedAt.toEpochMillisOrNull(),
    )
}

@Serializable
internal data class PoHistoryDto(
    @SerialName("action") val action: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain() = PoHistoryEntry(
        action = action ?: status.orEmpty(),
        userId = userId,
        note = note,
        at = createdAt.toEpochMillisOrNull(),
    )
}

@Serializable
internal data class VendorDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
) {
    fun toDomain(): Vendor? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return Vendor(
            id = identifier,
            name = name?.takeIf { it.isNotBlank() } ?: identifier,
            currency = currency,
            defaultNominalCode = nominalCode,
        )
    }
}

/** Adds [key] only when [value] has something in it. */
internal fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    val trimmed = value?.trim()
    if (!trimmed.isNullOrEmpty()) put(key, JsonPrimitive(trimmed))
}
