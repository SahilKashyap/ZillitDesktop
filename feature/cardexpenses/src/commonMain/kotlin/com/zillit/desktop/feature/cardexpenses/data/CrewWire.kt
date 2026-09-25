package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.feature.cardexpenses.domain.AttachmentChange
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachment
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptEdit
import com.zillit.desktop.feature.cardexpenses.domain.TopUpTrailStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The crew pages' wire shapes: the receipt attachment model, the receipt
 * edit's PATCH body and the top-up row's embedded trail.
 */

private val crewJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * The web's `AttachmentModel` (`attachmentUpload.js:53-61`).
 *
 * The receipt column stores this object; a bare key string reads back on the
 * web as no attachment at all, which is how every desktop-uploaded receipt
 * showed "No receipt" to the accounts team.
 */
internal fun CardAttachment.model(): JsonObject = buildJsonObject {
    put("media", JsonPrimitive(key))
    put("bucket", JsonPrimitive(bucket))
    put("region", JsonPrimitive(region))
    put("name", JsonPrimitive(fileName.ifBlank { key }))
    put("content_type", JsonPrimitive(contentType))
    put("content_subtype", JsonPrimitive(contentSubtype))
    put("caption", JsonPrimitive(""))
}

/** The stored file's display name — the model's `name` — when the column holds an object. */
internal fun JsonElement?.readAttachmentName(): String? {
    val obj = asObject() ?: return null
    return (obj["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}

/**
 * The PATCH `/receipts/:id` body the Edit Receipt dialog sends
 * (`UserReceiptsPage.jsx:1451-1475`).
 *
 * The coding keys are left out when blank — the web sends `undefined` — and
 * the attachment key only when the file changed: a new model, or an explicit
 * null for a removed one.
 */
internal fun ReceiptEdit.body(): JsonObject = buildJsonObject {
    put("description", JsonPrimitive(description))
    put("amount", amount?.let(::JsonPrimitive) ?: JsonNull)
    put("date", date?.let(::JsonPrimitive) ?: JsonNull)
    put("card_id", cardId?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    putIfPresent("currency", currency)
    putIfPresent("nominal_code", nominalCode)
    putIfPresent("episode", episode)
    putIfPresent("code_description", codeDescription)
    put("category", JsonPrimitive(category.wire))
    put("is_urgent", JsonPrimitive(urgent))
    put("request_top_up", JsonPrimitive(requestTopUp))
    when (val change = attachment) {
        AttachmentChange.Keep -> Unit
        AttachmentChange.Remove -> put("receipt_attachment", JsonNull)
        is AttachmentChange.Replace -> put("receipt_attachment", change.file.model())
    }
}

/** The row's `history`, a JSON-stringified array on the wire, read leniently. */
internal fun JsonElement?.readTopUpTrail(): List<TopUpTrailStep> {
    val array = when (this) {
        is JsonArray -> this
        is JsonPrimitive -> if (isString) runCatching { crewJson.parseToJsonElement(content) }.getOrNull() else null
        else -> null
    } as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val step = element as? JsonObject ?: return@mapNotNull null
        TopUpTrailStep(
            action = step.text("action").orEmpty(),
            userId = step.text("action_by"),
            at = step.text("action_at").toEpochMillisOrNull(),
            reason = step.text("reason"),
            amount = step.text("amount").toAmountOrNull(),
        )
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonElement?.asObject(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> if (isString) {
        runCatching { crewJson.parseToJsonElement(content) }.getOrNull() as? JsonObject
    } else {
        null
    }

    else -> null
}
