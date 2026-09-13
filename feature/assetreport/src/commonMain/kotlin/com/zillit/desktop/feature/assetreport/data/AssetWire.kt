package com.zillit.desktop.feature.assetreport.data

import com.zillit.desktop.core.common.currencyCode
import com.zillit.desktop.feature.assetreport.domain.AssetAttachment
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetExportFormat
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.AssetRecord
import com.zillit.desktop.feature.assetreport.domain.ExpenditureType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

/*
 * The register's wire, read loosely on purpose.
 *
 * Every figure is a JsonElement: a quoted amount or an object where a code was
 * expected must cost one field, never the whole feed — a DTO typed `Double`
 * empties the register the day one line arrives as "12.50".
 */

@Serializable
internal data class LineDto(
    @SerialName("id") val id: JsonElement? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("quantity") val quantity: JsonElement? = null,
    @SerialName("unit_price") val unitPrice: JsonElement? = null,
    @SerialName("total") val total: JsonElement? = null,
    @SerialName("account") val account: JsonElement? = null,
    @SerialName("department") val department: JsonElement? = null,
    @SerialName("vendor") val vendor: JsonElement? = null,
    @SerialName("currency") val currency: JsonElement? = null,
    @SerialName("expenditure_type") val expenditureType: String? = null,
    @SerialName("is_tax") val isTax: JsonElement? = null,
    @SerialName("rental_start") val rentalStart: JsonElement? = null,
    @SerialName("rental_end") val rentalEnd: JsonElement? = null,
    @SerialName("po_id") val poId: JsonElement? = null,
    @SerialName("po_number") val poNumber: JsonElement? = null,
    @SerialName("po_vendor_id") val poVendorId: JsonElement? = null,
    @SerialName("po_department_id") val poDepartmentId: JsonElement? = null,
    @SerialName("po_currency") val poCurrency: JsonElement? = null,
    @SerialName("asset_id") val assetId: JsonElement? = null,
    @SerialName("category") val category: String? = null,
) {
    fun toModel(): AssetLine? {
        val lineId = id.idText() ?: return null
        return AssetLine(
            lineItemId = lineId,
            description = description.orEmpty(),
            quantity = quantity.number() ?: 0.0,
            unitPrice = unitPrice.number() ?: 0.0,
            total = total.number() ?: 0.0,
            account = account.text(),
            // The load-bearing alias: the feed says `vendor`, everything
            // downstream reads `po_vendor_id` — both clients coalesce, po_* first.
            vendorId = poVendorId.idText() ?: vendor.idText().orEmpty(),
            departmentId = poDepartmentId.idText() ?: department.idText().orEmpty(),
            currency = poCurrency.currencyCode() ?: currency.currencyCode().orEmpty(),
            expenditureType = ExpenditureType.fromWire(expenditureType),
            isTax = isTax.flag(),
            rentalStartMillis = rentalStart.epochMillis(),
            rentalEndMillis = rentalEnd.epochMillis(),
            poNumber = poNumber.text(),
            poId = poId.idText().orEmpty(),
            assetId = assetId.idText(),
            category = AssetCategory.fromWire(category),
        )
    }
}

@Serializable
internal data class RecordDto(
    @SerialName("id") val id: JsonElement? = null,
    @SerialName("_id") val underscoreId: JsonElement? = null,
    @SerialName("line_item_id") val lineItemId: JsonElement? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("comments") val comments: String? = null,
    @SerialName("comment_by") val commentBy: JsonElement? = null,
    @SerialName("comment_at") val commentAt: JsonElement? = null,
    @SerialName("attachments") val attachments: JsonElement? = null,
) {
    fun toModel() = AssetRecord(
        id = (id.idText() ?: underscoreId.idText()).orEmpty(),
        lineItemId = lineItemId.idText().orEmpty(),
        category = AssetCategory.fromWire(category),
        comments = comments.orEmpty(),
        commentBy = commentBy.idText().orEmpty(),
        commentAtMillis = commentAt.epochMillis(),
        attachments = (attachments as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toAttachment() },
    )
}

@Serializable
internal data class VendorDto(
    @SerialName("_id") val underscoreId: JsonElement? = null,
    @SerialName("id") val plainId: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("company_name") val companyName: String? = null,
) {
    val id: String? get() = plainId.idText() ?: underscoreId.idText()
}

/** The canonical model out of one stored object; a row with no key names no file and is skipped. */
internal fun JsonObject.toAttachment(): AssetAttachment? {
    val media = this["media"].text().ifBlank { return null }
    return AssetAttachment(
        media = media,
        bucket = this["bucket"].text(),
        region = this["region"].text(),
        name = this["name"].text(),
        contentType = this["content_type"].text(),
        contentSubtype = this["content_subtype"].text(),
        caption = this["caption"].text(),
    )
}

/**
 * An attachment as the write sends it.
 *
 * A saved attachment goes back as the object the server gave — [stored] — so
 * any field this client does not model survives the PATCH that re-sends the
 * list; only a fresh upload is built from the model's own fields.
 */
internal fun AssetAttachment.toJson(stored: JsonObject?): JsonObject = stored ?: buildJsonObject {
    put("media", media)
    put("bucket", bucket)
    put("region", region)
    put("name", name)
    put("content_type", contentType)
    put("content_subtype", contentSubtype)
    put("caption", caption)
}

/** `department_ids` is OMITTED when empty — never `[]`, never null. */
fun exportBody(format: AssetExportFormat, departmentIds: List<String>): JsonObject = buildJsonObject {
    put("format", format.wire)
    val ids = departmentIds.filter { it.isNotBlank() }
    if (ids.isNotEmpty()) put("department_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
}

// -- loose readers -------------------------------------------------------------

/** An id: a bare string or number, or an object's `_id` / `id`; never "null". */
internal fun JsonElement?.idText(): String? = when (this) {
    is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    is JsonObject -> (this["_id"] ?: this["id"]).idText()
    else -> null
}

internal fun JsonElement?.text(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty().trim()
    else -> ""
}

internal fun JsonElement?.number(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.replace(",", "")?.trim()?.toDoubleOrNull()
}

internal fun JsonElement?.flag(): Boolean {
    val primitive = this as? JsonPrimitive ?: return false
    return primitive.booleanOrNull ?: (primitive.contentOrNull?.trim()?.lowercase() in setOf("true", "1", "yes"))
}

/**
 * Epoch ms OR an ISO string — the register sends both, and the web reads both
 * with `new Date(v)`: a date-only string is UTC midnight, a date-time without
 * an offset is local. Anything unreadable is no date rather than a crash or 1970.
 */
internal fun JsonElement?.epochMillis(zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    val raw = primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    val millis = primitive.longOrNull
        ?: raw.toLongOrNull()
        ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        ?: parseDay(raw, zone)
    return millis?.takeIf { it > 0 }
}

private fun parseDay(raw: String, zone: TimeZone): Long? =
    runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
        ?: runCatching { LocalDate.parse(raw).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(raw).toInstant(zone).toEpochMilliseconds() }.getOrNull()
