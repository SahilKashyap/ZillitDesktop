package com.zillit.desktop.feature.assetreport.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.AssetRecord
import com.zillit.desktop.feature.assetreport.domain.AssetRepository
import com.zillit.desktop.feature.assetreport.domain.ExpenditureType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `purchase-orders/line-items` and `purchase-orders/asset-register` on the
 * PO host; vendors on the account hub (its own host — the path misleads).
 *
 * Wire rules that cost the phones bugs, kept here on purpose: the feed's
 * `vendor`/`department`/`currency` alias `po_*` and must coalesce; a PATCH to
 * `/:id` silently drops `comments`; dates arrive as epoch ms OR ISO strings.
 */
class AssetRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : AssetRepository {

    override suspend fun lines(): ZillitResult<List<AssetLine>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${po()}line-items",
            serializer = ListSerializer(LineDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.mapNotNull { it.toModel() } }

    override suspend fun recordForLine(line: AssetLine): ZillitResult<AssetRecord?> {
        val assetId = line.assetId
        return if (assetId != null) {
            apiClient.envelope(
                verb = HttpVerb.Get,
                url = "${po()}asset-register/$assetId",
                module = RequestModule.ProjectUser,
            ).map { envelope -> envelope.decodeRecord()?.toModel() }
        } else {
            apiClient.request(
                verb = HttpVerb.Get,
                url = "${po()}asset-register",
                serializer = ListSerializer(RecordDto.serializer()),
                module = RequestModule.ProjectUser,
                queryParameters = mapOf("line_item_id" to line.lineItemId),
            ).map { rows -> rows.firstOrNull()?.toModel() }
        }
    }

    override suspend fun create(
        poId: String,
        lineItemId: String,
        category: AssetCategory,
        comments: String,
    ): ZillitResult<AssetRecord> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "${po()}asset-register",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("entity", "purchase_order")
                put("po_id", poId)
                put("line_item_id", lineItemId)
                put("category", category.wire)
                if (comments.isNotBlank()) put("comments", comments)
            },
        ).requireRecord()

    override suspend fun updateCategory(
        assetId: String,
        category: AssetCategory,
    ): ZillitResult<AssetRecord> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "${po()}asset-register/$assetId",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("category", category.wire) },
        ).requireRecord()

    override suspend fun updateComment(assetId: String, comments: String): ZillitResult<AssetRecord> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "${po()}asset-register/$assetId/comment",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("comments", comments) },
        ).requireRecord()

    override suspend fun vendors(): ZillitResult<Map<String, String>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.AccountHub)}vendors",
            serializer = ListSerializer(VendorDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows ->
            rows.mapNotNull { vendor ->
                val id = vendor.id ?: return@mapNotNull null
                id to (vendor.name ?: vendor.companyName ?: id)
            }.toMap()
        }

    private fun po() = "${config.apiV2(ZillitService.PurchaseOrder)}purchase-orders/"

    private fun ZillitResult<ApiEnvelope>.requireRecord(): ZillitResult<AssetRecord> =
        when (this) {
            is ZillitResult.Failure -> this
            is ZillitResult.Success -> {
                val record = data.decodeRecord()?.toModel()
                if (data.status != 0 && record != null) {
                    ZillitResult.Success(record)
                } else {
                    ZillitResult.Failure(
                        ZillitError.Validation(data.message ?: "The register answered no record."),
                    )
                }
            }
        }

    private companion object {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        fun ApiEnvelope.decodeRecord(): RecordDto? =
            (data as? kotlinx.serialization.json.JsonObject)
                ?.let { json.decodeFromJsonElement(RecordDto.serializer(), it) }
    }
}

/** `department_ids` is OMITTED when empty — never `[]`, never null. */
fun exportBody(format: String, departmentIds: List<String>): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        put("format", normaliseFormat(format))
        if (departmentIds.isNotEmpty()) {
            put(
                "department_ids",
                kotlinx.serialization.json.buildJsonArray { departmentIds.forEach { add(JsonPrimitive(it)) } },
            )
        }
    }

/** The web's normaliser: excel→xlsx, csv→csv, everything else pdf. */
fun normaliseFormat(format: String): String = when (format.lowercase()) {
    "excel", "xlsx" -> "xlsx"
    "csv" -> "csv"
    else -> "pdf"
}

@Serializable
internal data class LineDto(
    @SerialName("id") val id: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("quantity") val quantity: Double? = null,
    @SerialName("unit_price") val unitPrice: Double? = null,
    @SerialName("total") val total: Double? = null,
    @SerialName("account") val account: String? = null,
    @SerialName("department") val department: String? = null,
    @SerialName("vendor") val vendor: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("expenditure_type") val expenditureType: String? = null,
    @SerialName("is_tax") val isTax: Boolean? = null,
    @SerialName("rental_start") val rentalStart: JsonElement? = null,
    @SerialName("rental_end") val rentalEnd: JsonElement? = null,
    @SerialName("po_id") val poId: String? = null,
    @SerialName("po_number") val poNumber: String? = null,
    @SerialName("po_vendor_id") val poVendorId: String? = null,
    @SerialName("po_department_id") val poDepartmentId: String? = null,
    @SerialName("po_currency") val poCurrency: String? = null,
    @SerialName("asset_id") val assetId: String? = null,
    @SerialName("category") val category: String? = null,
) {
    fun toModel(): AssetLine? {
        val lineId = id ?: return null
        return AssetLine(
            lineItemId = lineId,
            description = description.orEmpty(),
            quantity = quantity ?: 0.0,
            unitPrice = unitPrice ?: 0.0,
            total = total ?: 0.0,
            account = account.orEmpty(),
            // The load-bearing alias: the feed says `vendor`, everything
            // downstream reads `po_vendor_id` — both clients coalesce.
            vendorId = poVendorId ?: vendor.orEmpty(),
            departmentId = poDepartmentId ?: department.orEmpty(),
            currency = poCurrency ?: currency.orEmpty(),
            expenditureType = ExpenditureType.fromWire(expenditureType),
            isTax = isTax == true,
            rentalStartMillis = rentalStart.toEpochMillis(),
            rentalEndMillis = rentalEnd.toEpochMillis(),
            poNumber = poNumber.orEmpty(),
            poId = poId.orEmpty(),
            assetId = assetId?.takeIf { it.isNotBlank() },
            category = AssetCategory.fromWire(category),
        )
    }
}

@Serializable
internal data class RecordDto(
    @SerialName("id") val id: String? = null,
    @SerialName("line_item_id") val lineItemId: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("comments") val comments: String? = null,
    @SerialName("comment_by") val commentBy: String? = null,
    @SerialName("comment_at") val commentAt: JsonElement? = null,
    @SerialName("attachments") val attachments: List<AttachmentDto>? = null,
) {
    fun toModel() = AssetRecord(
        id = id.orEmpty(),
        lineItemId = lineItemId.orEmpty(),
        category = AssetCategory.fromWire(category),
        comments = comments.orEmpty(),
        commentBy = commentBy.orEmpty(),
        commentAtMillis = commentAt.toEpochMillis(),
        attachmentNames = attachments.orEmpty().mapNotNull { it.name },
    )
}

@Serializable
internal data class AttachmentDto(
    @SerialName("name") val name: String? = null,
)

@Serializable
internal data class VendorDto(
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("id") val plainId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("company_name") val companyName: String? = null,
) {
    val id: String? get() = underscoreId ?: plainId
}

/**
 * The register's dates are epoch ms OR ISO strings — Android branches on
 * `longOrNull`; ISO strings are answered with 0 rather than a parse guess,
 * and the label code treats 0 as "no date".
 */
internal fun JsonElement?.toEpochMillis(): Long {
    val primitive = this as? JsonPrimitive
        ?: (this as? kotlinx.serialization.json.JsonObject)?.let { return 0L }
        ?: return 0L
    return primitive.longOrNull ?: primitive.content.toLongOrNull() ?: 0L
}
