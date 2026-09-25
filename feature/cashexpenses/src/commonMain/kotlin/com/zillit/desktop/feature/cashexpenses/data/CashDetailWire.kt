package com.zillit.desktop.feature.cashexpenses.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.forms.CustomFieldGroup
import com.zillit.desktop.core.forms.CustomFieldValue
import com.zillit.desktop.feature.cashexpenses.domain.CashAttachment
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cashexpenses.domain.FloatDetails
import com.zillit.desktop.feature.cashexpenses.domain.FloatReturn
import com.zillit.desktop.feature.cashexpenses.domain.FloatTotals
import com.zillit.desktop.feature.cashexpenses.domain.TopUpHistoryEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

// The fields the parity port reads that the first port did not: a claim's
// uploaded file, a top-up's history, the department coordinators, a float's
// custom answers, and the float detail route. Kept apart from CashWire.kt so
// neither file carries the whole wire.

// -- attachments, top-up history, coordinators, custom answers --------------------

/**
 * A claim's `attachment` — the object the web uploads, or a string holding
 * one. A bare string that is not JSON is read as the key itself, which is
 * what the oldest rows carry. No key, no attachment.
 */
internal fun JsonElement?.readAttachment(): CashAttachment? {
    val body = readObject() ?: return (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("{") }?.let { CashAttachment(media = it) }
    fun text(key: String) = (body[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val media = text("media").ifBlank { text("url") }.ifBlank { return null }
    val (type, subtype) = if (text("content_subtype").isBlank() && text("content_type").contains('/')) {
        CashAttachment.splitMime(text("content_type"))
    } else {
        text("content_type") to text("content_subtype")
    }
    return CashAttachment(
        media = media,
        bucket = text("bucket"),
        region = text("region"),
        name = text("name").ifBlank { text("file_name") },
        contentType = type,
        contentSubtype = subtype,
        caption = text("caption"),
    )
}

/** The claim route's `attachment` object — every key, `caption` blank, as the web sends it. */
internal fun CashAttachment.toJson(): JsonObject = buildJsonObject {
    put("media", JsonPrimitive(media))
    put("bucket", JsonPrimitive(bucket))
    put("region", JsonPrimitive(region))
    put("name", JsonPrimitive(name))
    put("content_type", JsonPrimitive(contentType))
    put("content_subtype", JsonPrimitive(contentSubtype))
    put("caption", JsonPrimitive(caption))
}

@Serializable
internal data class TopUpHistoryDto(
    @SerialName("action") val action: String? = null,
    @SerialName("action_by") val actionBy: String? = null,
    @SerialName("action_at") val actionAt: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("amount") val amount: String? = null,
)

/**
 * A top-up's `history` — JSON-stringified on the cash routes, an array
 * elsewhere; anything unreadable is no history (`parseHistory`).
 */
internal fun JsonElement?.readTopUpHistory(): List<TopUpHistoryEntry> =
    readList(TopUpHistoryDto.serializer()).map { dto ->
        TopUpHistoryEntry(
            action = dto.action.orEmpty(),
            actionBy = dto.actionBy?.takeIf { it.isNotBlank() },
            actionAt = dto.actionAt.toEpochMillisOrNull(),
            reason = dto.reason?.takeIf { it.isNotBlank() },
            amount = dto.amount.toAmountOrNull(),
        )
    }

@Serializable
internal data class CoordinatorDto(
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("user_ids") val userIds: JsonElement? = null,
    @SerialName("coding_required") val codingRequired: JsonElement? = null,
    @SerialName("view_department_floats") val viewDepartmentFloats: JsonElement? = null,
) {
    fun toDomain(): DepartmentCoordinator? = departmentId?.takeIf { it.isNotBlank() }?.let {
        DepartmentCoordinator(
            departmentId = it,
            userIds = userIds.readStrings(),
            codingRequired = codingRequired.isTrue(),
            viewDepartmentFloats = viewDepartmentFloats.isTrue(),
        )
    }

    companion object {
        fun of(row: DepartmentCoordinator): JsonObject = buildJsonObject {
            put("department_id", JsonPrimitive(row.departmentId))
            put("user_ids", JsonArray(row.userIds.map(::JsonPrimitive)))
            put("coding_required", JsonPrimitive(row.codingRequired))
            put("view_department_floats", JsonPrimitive(row.viewDepartmentFloats))
        }
    }
}

/**
 * A float's `custom_fields` — `[{section, fields: [{name, label, type,
 * selection_type, value}]}]`, as the request form writes them. A value may
 * come back as a number or a flag; it is read as its text.
 */
internal fun JsonElement?.readCustomFields(): List<CustomFieldGroup> =
    readArray().filterIsInstance<JsonObject>().map { section ->
        CustomFieldGroup(
            section = (section["section"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            fields = section["fields"].readArray().filterIsInstance<JsonObject>().map { field ->
                fun text(key: String) = (field[key] as? JsonPrimitive)?.contentOrNull
                CustomFieldValue(
                    name = text("name").orEmpty(),
                    value = text("value").orEmpty(),
                    label = text("label").orEmpty(),
                    type = text("type").orEmpty(),
                    selectionType = text("selection_type"),
                )
            },
        )
    }

// -- float details --------------------------------------------------------------------

@Serializable
internal data class FloatTotalsDto(
    @SerialName("requested") val requested: String? = null,
    @SerialName("issued") val issued: String? = null,
    @SerialName("spent") val spent: String? = null,
    @SerialName("topped_up") val toppedUp: String? = null,
    @SerialName("returned") val returned: String? = null,
    @SerialName("final_balance") val finalBalance: String? = null,
) {
    fun toDomain() = FloatTotals(
        requested = requested.toAmount(),
        issued = issued.toAmount(),
        spent = spent.toAmount(),
        toppedUp = toppedUp.toAmount(),
        returned = returned.toAmount(),
        finalBalance = finalBalance.toAmount(),
    )
}

@Serializable
internal data class FloatReturnDto(
    @SerialName("id") val id: String? = null,
    @SerialName("return_amount") val returnAmount: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("return_reason") val returnReason: String? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
    @SerialName("recorded_by") val recordedBy: String? = null,
    @SerialName("received_date") val receivedDate: String? = null,
    @SerialName("notes") val notes: String? = null,
) {
    fun toDomain(index: Int) = FloatReturn(
        id = id?.takeIf { it.isNotBlank() } ?: "return-$index",
        amount = returnAmount.toAmount(),
        currency = currency,
        reason = returnReason?.takeIf { it.isNotBlank() },
        recordedAt = recordedAt.toEpochMillisOrNull(),
        receivedDate = receivedDate.toEpochMillisOrNull(),
        notes = notes?.takeIf { it.isNotBlank() },
        recordedBy = recordedBy?.takeIf { it.isNotBlank() },
    )
}

/**
 * `GET /float-requests/{id}/details` — `{float, totals, batches, topups,
 * returns}`. Each part is read on its own, so one malformed list costs that
 * list and not the dialog.
 */
internal fun JsonElement?.readFloatDetails(): FloatDetails {
    val body = readObject() ?: JsonObject(emptyMap())
    fun <T> part(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        body[key]?.let { runCatching { cashLenient.decodeFromJsonElement(serializer, it) }.getOrNull() }
    return FloatDetails(
        float = part("float", FloatDto.serializer())?.toDomain(),
        totals = part("totals", FloatTotalsDto.serializer())?.toDomain() ?: FloatTotals(),
        batches = body["batches"].readList(BatchDto.serializer()).mapNotNull { it.toDomain() },
        topUps = body["topups"].readList(TopUpDto.serializer()).mapNotNull { it.toDomain() },
        returns = body["returns"].readList(FloatReturnDto.serializer()).mapIndexed { i, dto -> dto.toDomain(i) },
    )
}
