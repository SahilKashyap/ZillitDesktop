package com.zillit.desktop.feature.purchaseorder.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoEmailReceipt
import com.zillit.desktop.feature.purchaseorder.domain.PoTemplate
import com.zillit.desktop.core.forms.CustomFieldGroup
import com.zillit.desktop.core.forms.CustomFieldValue
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.purchaseorder.domain.AssetFilters
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoDescriptionFormat
import com.zillit.desktop.feature.purchaseorder.domain.PoSplitType
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
@Suppress("TooManyFunctions") // One method per server operation; see detekt.yml.
class PurchaseOrderRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : PurchaseOrderRepository {

    private val base = "${config.baseUrl(ZillitService.PurchaseOrder)}/api/v2/purchase-orders"

    private val vendorsUrl = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/vendors"

    /** The Settings tab's routes — its own class, see [PoSettingsSource]. */
    private val settingsSource = PoSettingsSource(apiClient, config)

    /** The Templates and Delivery Addresses tabs — see [PoRegisterSource]. */
    private val registerSource = PoRegisterSource(apiClient, config)

    /**
     * See [PurchaseOrderRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project — the same cross-project
     * gate the web's account-hub wrapper applies before any handler runs.
     */
    override val refreshes: Flow<PoRefresh> =
        bus?.onAny(PO_SYNC_EVENTS, PoSyncEnvelope.serializer())
            ?.mapNotNull { (event, envelope) ->
                poRefreshFor(event, envelope.formModule)
                    ?.takeIf { envelope.inProject(currentProjectId()) }
            }
            ?: emptyFlow()

    override suspend fun orders(status: PoStatus?, departmentId: String?): ZillitResult<List<PurchaseOrder>> = list(
        base,
        buildMap {
            // Upper-case on the wire: the query is compared against the stored
            // value, which is the server's own vocabulary.
            status?.let { put("status", it.wire.uppercase()) }
            departmentId?.takeIf { it.isNotBlank() }?.let { put("department_id", it) }
        },
    )

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

    /**
     * Attachments are a column of the order, written by patching the record.
     *
     * The old web module's three dedicated routes — `/v2/list/attachments/{id}`,
     * `/v2/add/attachments/{id}`, `/v2/delete/{attachmentId}/{id}` — answer 404
     * on every verb on develop (probed 2026-09-12). The account hub's web
     * module never used them: it reads `attachments` off `GET /{id}` and sends
     * the whole array back on a PATCH, so the desktop does the same.
     */
    override suspend fun saveAttachments(id: String, files: List<PoAttachment>): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "$base/$id",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("attachments", files.attachmentsJson()) },
        ).map { }

    /** `POST /{id}/send-vendor-email` — no body; the server builds the mail. */
    override suspend fun sendVendorEmail(id: String): ZillitResult<PoEmailReceipt> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/$id/send-vendor-email",
            serializer = PoEmailDto.serializer(),
            module = RequestModule.ProjectUser,
        ).map { it.toDomain() }

    /**
     * `POST /{id}/pdf` — renders the order and answers where it was stored.
     *
     * The display names are passed rather than looked up: the service has no
     * project-info endpoint, so whatever the client holds is what prints.
     */
    override suspend fun pdf(id: String, projectName: String, companyName: String): ZillitResult<PoAttachment> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/$id/pdf",
            serializer = PoPdfDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("projectName", JsonPrimitive(projectName))
                put("companyName", JsonPrimitive(companyName))
            },
        ).flatMap { dto ->
            dto.attachment?.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Unknown("The server rendered no PDF for this order."))
        }

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
     * `PATCH /bulk` with `{po_ids, data}`.
     *
     * The 100-id cap is the server's (its Joi `bulkUpdateSchema`) and is
     * answered here rather than round-tripped into a generic toast: the person
     * selecting 140 rows can act on the answer, a 400 tells them nothing.
     */
    override suspend fun bulkSetEffectiveDate(ids: List<String>, effectiveDate: Long): ZillitResult<Unit> = when {
        ids.isEmpty() -> ZillitResult.Failure(ZillitError.Unknown("Nothing is selected."))
        ids.size > BULK_LIMIT ->
            ZillitResult.Failure(ZillitError.Unknown("Bulk changes are limited to $BULK_LIMIT orders at a time."))

        else -> apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "$base/bulk",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("po_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put("data", buildJsonObject { put("effective_date", JsonPrimitive(effectiveDate)) })
            },
        ).map { }
    }

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

    /**
     * Two fields on the record, not a verb of its own: the web's Reassign modal
     * PATCHes `assigned_to` with the reason beside it, and the reason is what
     * makes the hand-off auditable.
     */
    override suspend fun reassign(id: String, userId: String, reason: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "$base/$id",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("assigned_to", JsonPrimitive(userId))
                put("reassignment_reason", JsonPrimitive(reason))
            },
        ).map { }

    // -- templates and delivery addresses: PoRegisterSource's -----------------

    override suspend fun templates() = registerSource.templates()

    override suspend fun saveTemplate(template: PoTemplate) = registerSource.saveTemplate(template)

    override suspend fun deleteTemplate(id: String) = registerSource.deleteTemplate(id)

    override suspend fun deliveryAddresses() = registerSource.deliveryAddresses()

    override suspend fun saveDeliveryAddress(id: String?, address: PoAddress) =
        registerSource.saveDeliveryAddress(id, address)

    // -- settings: every route is PoSettingsSource's ---------------------------

    override suspend fun settings() = settingsSource.settings()

    override suspend fun saveDescriptionFormat(format: PoDescriptionFormat) =
        settingsSource.saveDescriptionFormat(format)

    override suspend fun saveRentalSplit(autoSplit: Boolean, splitType: PoSplitType) =
        settingsSource.saveRentalSplit(autoSplit, splitType)

    override suspend fun saveNumbering(prefix: String, allowAmendAfterApproval: Boolean) =
        settingsSource.saveNumbering(prefix, allowAmendAfterApproval)

    override suspend fun saveTermsDocument(document: PoAttachment) = settingsSource.saveTermsDocument(document)

    override suspend fun saveAssetFilters(filters: AssetFilters) = settingsSource.saveAssetFilters(filters)

    override suspend fun createRule(rule: PoAssignmentRule) = settingsSource.createRule(rule)

    override suspend fun updateRule(rule: PoAssignmentRule) = settingsSource.updateRule(rule)

    override suspend fun deleteRule(id: String) = settingsSource.deleteRule(id)

    override suspend fun nominalCodes() = settingsSource.nominalCodes()

    override suspend fun assetTags() = settingsSource.assetTags()

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

        /** The server's own bulk cap, from its `bulkUpdateSchema`. */
        const val BULK_LIMIT = 100
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
    putIfPresent("vat_treatment", vatTreatment)
    putIfPresent("delivery_address_id", deliveryAddressId)
    deliveryDate?.let { put("delivery_date", JsonPrimitive(it)) }
    if (deliveryAddress != null && !deliveryAddress.isEmpty) {
        put("delivery_address", deliveryAddress.toJson())
    }
    // The files ride the record; there is no attachment sub-resource. The array
    // is sent even when empty, because that is how the last one comes off —
    // omitting the key would leave it in place.
    put("attachments", attachments.attachmentsJson())
    // Only when the production has configured some: an empty array on every
    // order would be a column of nothing on the printed form.
    if (customFields.isNotEmpty()) put("custom_fields", customFields.customFieldsJson())
    put(
        "line_items",
        buildJsonArray {
            lines.forEach { line ->
                add(
                    line.body(),
                )
            }
        },
    )
}

/** The extra fields, grouped by the section a reader sees them under. */
private fun List<CustomFieldGroup>.customFieldsJson(): JsonArray = buildJsonArray {
    forEach { group ->
        add(
            buildJsonObject {
                put("section", JsonPrimitive(group.section))
                put(
                    "fields",
                    buildJsonArray {
                        group.fields.forEach { field ->
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive(field.name))
                                    put("value", JsonPrimitive(field.value))
                                },
                            )
                        }
                    },
                )
            },
        )
    }
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
    @Serializable(with = CurrencyCodeSerializer::class)
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
    @SerialName("reassigned_by") val reassignedBy: String? = null,
    @SerialName("reassigned_at") val reassignedAt: String? = null,
    @SerialName("delivery") val delivery: String? = null,
    @SerialName("delivery_address") val deliveryAddressText: String? = null,
    @SerialName("delivery_address_id") val deliveryAddressId: String? = null,
    @SerialName("delivery_date") val deliveryDate: String? = null,
    @SerialName("paid_amount") val paidAmount: JsonElement? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("vat_amount") val vatAmount: JsonElement? = null,
    @SerialName("email_at") val emailAt: String? = null,
    @SerialName("email_by") val emailBy: String? = null,
    @SerialName("closure_reason") val closureReason: String? = null,
    @SerialName("closed_by") val closedBy: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("rejected_by") val rejectedBy: String? = null,
    @SerialName("rejected_at") val rejectedAt: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("custom_fields") val customFields: JsonElement? = null,
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
            deliveryAddress = deliveryAddressText?.takeIf { it.isNotBlank() } ?: delivery,
            lines = parsedLines,
            approvals = approvals.orEmpty().map { it.toDomain() },
            attachmentCount = attachments?.size ?: 0,
            grossAmount = grossAmount.toAmountOrNull() ?: 0.0,
            paidAmount = paidAmount.toAmountOrNull() ?: 0.0,
            paidAt = paidAt.toEpochMillisOrNull(),
            emailAt = emailAt.toEpochMillisOrNull(),
            emailBy = emailBy,
            vatAmount = vatAmount.toAmountOrNull() ?: 0.0,
            deliveryDate = deliveryDate.toEpochMillisOrNull(),
            deliveryAddressId = deliveryAddressId,
            closureReason = closureReason,
            closedBy = closedBy,
            closedAt = closedAt.toEpochMillisOrNull(),
            rejectedBy = rejectedBy,
            rejectedAt = rejectedAt.toEpochMillisOrNull(),
            rejectionReason = rejectionReason,
            reassignedBy = reassignedBy,
            reassignedAt = reassignedAt.toEpochMillisOrNull(),
            updatedAt = updatedAt.toEpochMillisOrNull(),
            updatedBy = updatedBy,
            attachments = attachments.orEmpty().mapNotNull { it.asAttachment() },
            customFields = customFields.asCustomFieldGroups(),
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
    @SerialName("tax_type") val taxType: String? = null,
    @SerialName("expenditure_type") val expenditureType: String? = null,
    @SerialName("department") val department: String? = null,
    @SerialName("rental_start") val rentalStart: String? = null,
    @SerialName("rental_end") val rentalEnd: String? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("split_parent_id") val splitParentId: String? = null,
    @SerialName("is_tax") val isTax: Boolean? = null,
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
            // The stored figure wins where there is one: a split child's total
            // is its own, not its quantity times a price it never carried.
            amount = total.toAmountOrNull(),
            expenditureType = expenditureType?.takeIf { it.isNotBlank() },
            taxType = taxType?.takeIf { it.isNotBlank() },
            departmentId = department?.takeIf { it.isNotBlank() },
            rentalStart = rentalStart?.takeIf { it.isNotBlank() },
            rentalEnd = rentalEnd?.takeIf { it.isNotBlank() },
            tags = tags.orEmpty(),
            splitParentId = splitParentId?.takeIf { it.isNotBlank() },
            isTax = isTax == true,
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
    @Serializable(with = CurrencyCodeSerializer::class)
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

/** A file on an order, as the service lists it. */
@Serializable
internal data class PoAttachmentDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("media") val media: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
) {
    fun toDomain(): PoAttachment? = media?.takeIf { it.isNotBlank() }?.let { key ->
        PoAttachment(
            id = id.orEmpty(),
            media = key,
            name = name.orEmpty(),
            contentType = contentType.orEmpty(),
            bucket = bucket.orEmpty(),
            region = region.orEmpty(),
        )
    }
}

/** One line, as the server's `line_items` element. */
internal fun PoLine.body(): JsonObject = buildJsonObject {
    putIfPresent("id", id)
    put("description", JsonPrimitive(description))
    put("quantity", JsonPrimitive(quantity))
    put("unit_price", JsonPrimitive(unitPrice))
    put("total", JsonPrimitive(total))
    put("account", JsonPrimitive(nominalCode.orEmpty()))
    put("department", JsonPrimitive(departmentId.orEmpty()))
    put("expenditure_type", JsonPrimitive(expenditureType.orEmpty()))
    putIfPresent("tax_type", taxType)
    vatRate?.let { put("tax_rate", JsonPrimitive(it)) }
    putIfPresent("rental_start", rentalStart)
    putIfPresent("rental_end", rentalEnd)
    putIfPresent("split_parent_id", splitParentId)
    if (isTax) put("is_tax", JsonPrimitive(true))
    if (tags.isNotEmpty()) put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
}

/** The files on an order, as the record's `attachments` column. */
internal fun List<PoAttachment>.attachmentsJson(): JsonArray = buildJsonArray {
    forEach { file ->
        add(
            buildJsonObject {
                if (file.id.isNotBlank()) put("_id", JsonPrimitive(file.id))
                put("media", JsonPrimitive(file.media))
                put("name", JsonPrimitive(file.displayName))
                put("content_type", JsonPrimitive(file.contentType))
                put("bucket", JsonPrimitive(file.bucket))
                put("region", JsonPrimitive(file.region))
            },
        )
    }
}

/** A delivery address, as the order's `delivery_address` object. */
internal fun PoAddress.toJson(): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("email", JsonPrimitive(email))
    put("phoneCode", JsonPrimitive(phoneCode))
    put("phone", JsonPrimitive(phone))
    put("line1", JsonPrimitive(line1))
    put("line2", JsonPrimitive(line2))
    put("city", JsonPrimitive(city))
    put("state", JsonPrimitive(state))
    put("postalCode", JsonPrimitive(postalCode))
    put("country", JsonPrimitive(country))
}

/** An order's attachment column element, read leniently — the keys vary by route. */
internal fun JsonObject.asAttachment(): PoAttachment? =
    runCatching { lenientJson.decodeFromJsonElement(PoAttachmentDto.serializer(), this) }
        .getOrNull()
        ?.toDomain()

/**
 * `custom_fields` as the service stores it: a list of sections, each with its
 * own named values. Read leniently because a production with no configured
 * form sends an empty array, and one mid-migration has sent an object.
 */
internal fun JsonElement?.asCustomFieldGroups(): List<CustomFieldGroup> {
    val array = this as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        runCatching { lenientJson.decodeFromJsonElement(PoCustomFieldGroupDto.serializer(), element) }
            .getOrNull()
            ?.toDomain()
    }
}

@Serializable
internal data class PoCustomFieldGroupDto(
    @SerialName("section") val section: String? = null,
    @SerialName("fields") val fields: List<PoCustomFieldDto>? = null,
) {
    fun toDomain() = CustomFieldGroup(
        section = section.orEmpty(),
        fields = fields.orEmpty().map { CustomFieldValue(name = it.name.orEmpty(), value = it.value.orEmpty()) },
    )
}

@Serializable
internal data class PoCustomFieldDto(
    @SerialName("name") val name: String? = null,
    @SerialName("value") val value: String? = null,
)

/** `POST /{id}/send-vendor-email` → the stamp the order keeps. */
@Serializable
internal data class PoEmailDto(
    @SerialName("sent") val sent: Boolean? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("email_at") val at: String? = null,
    @SerialName("email_by") val by: String? = null,
) {
    fun toDomain() = PoEmailReceipt(
        // A 200 that says nothing about `sent` still means the server accepted
        // and queued the mail; only an explicit false is a refusal, and that
        // arrives as an error envelope rather than here.
        sent = sent != false,
        to = to.orEmpty(),
        at = at.toEpochMillisOrNull(),
        by = by,
    )
}

/** `POST /{id}/pdf` → where the rendered order was stored. */
@Serializable
internal data class PoPdfDto(
    @SerialName("attachment") val attachment: PoAttachmentDto? = null,
    @SerialName("po_number") val number: String? = null,
)
