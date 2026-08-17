package com.zillit.desktop.feature.purchaseorder.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoApproval
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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
) : PurchaseOrderRepository {

    private val base = "${config.baseUrl(ZillitService.PurchaseOrder)}/api/v2/purchase-orders"
    private val vendorsUrl = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/vendors"

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

    private fun NewPurchaseOrder.body(): JsonObject = buildJsonObject {
        putIfPresent("vendor_id", vendorId)
        put("vendor_name", JsonPrimitive(vendorName))
        put("description", JsonPrimitive(description))
        put("total", JsonPrimitive(total))
        putIfPresent("department_id", departmentId)
        putIfPresent("company_id", companyId)
        putIfPresent("currency", currency)
        putIfPresent("nominal_code", nominalCode)
        putIfPresent("episode", episode)
        putIfPresent("notes", notes)
        effectiveDate?.let { put("effective_date", JsonPrimitive(it)) }
        put(
            "lines",
            buildJsonArray {
                lines.forEach { line ->
                    add(
                        buildJsonObject {
                            putIfPresent("id", line.id)
                            put("description", JsonPrimitive(line.description))
                            put("quantity", JsonPrimitive(line.quantity))
                            put("unit_price", JsonPrimitive(line.unitPrice))
                            putIfPresent("nominal_code", line.nominalCode)
                            line.vatRate?.let { put("vat_rate", JsonPrimitive(it)) }
                        },
                    )
                }
            },
        )
    }

    private companion object {
        const val VENDOR_LIMIT = 500
    }
}

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
    @SerialName("total") val total: String? = null,
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
    @SerialName("lines") val lines: List<PoLineDto>? = null,
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
            total = total.toAmount(),
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
            lines = lines.orEmpty().map { it.toDomain() },
            approvals = approvals.orEmpty().map { it.toDomain() },
            attachmentCount = attachments?.size ?: 0,
        )
    }
}

@Serializable
internal data class PoLineDto(
    @SerialName("id") val id: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("quantity") val quantity: String? = null,
    @SerialName("unit_price") val unitPrice: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("vat_rate") val vatRate: String? = null,
) {
    fun toDomain() = PoLine(
        id = id,
        description = description.orEmpty(),
        // A line with no quantity is one item, not none: the wire omits the
        // field for single-item lines and reading it as zero silently zeroes
        // the order's total.
        quantity = quantity.toAmountOrNull() ?: 1.0,
        unitPrice = unitPrice.toAmount(),
        nominalCode = nominalCode,
        vatRate = vatRate.toAmountOrNull(),
    )
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
