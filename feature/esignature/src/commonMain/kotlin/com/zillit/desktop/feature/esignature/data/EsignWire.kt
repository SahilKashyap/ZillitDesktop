package com.zillit.desktop.feature.esignature.data

import com.zillit.desktop.feature.esignature.domain.EnvelopeDraft
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeSettings
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SignedField
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.domain.TemplateDraft
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * The write shapes — the web's `envelopeShape.js` serialisers
 * (`normalizeEnvelopeWritePayload`, `toWireTab`, `toWireRecipient`) and the
 * template payload its `TEMPLATE_CREATE_PAYLOAD.md` documents, transcribed.
 */

internal fun StoredFile.toWire(includePageCount: Boolean = false): JsonObject = buildJsonObject {
    put("media", media)
    put("thumbnail", thumbnail)
    put("content_type", contentType)
    put("content_subtype", contentSubtype)
    put("caption", "")
    put("duration", 1)
    put("height", 1)
    put("width", 1)
    put("bucket", bucket)
    put("region", region)
    put("name", name)
    if (includePageCount) put("page_count", pageCount)
    if (sizeBytes > 0) put("size", sizeBytes)
}

/** A template's document: the same descriptor, plus the multi-document keys. */
internal fun StoredFile.toTemplateWire(index: Int): JsonObject = buildJsonObject {
    toWire(includePageCount = true).forEach { (key, value) -> put(key, value) }
    put("document_index", index)
    put("order", index)
    put("mime_type", "application/pdf")
}

/**
 * A recipient as create/update sends it. Read-side stamps (`signed_on`,
 * `viewed_on`, `accepted_terms_on`) are never echoed back — the web's
 * `toWireRecipient` strips them for the same reason.
 */
internal fun EnvelopeRecipient.toWire(): JsonObject = buildJsonObject {
    put("name", name)
    put("email", email)
    put("role", role)
    put("routing_order", routingOrder)
    if (userId.isNotBlank()) put("user_id", userId)
    put("status", status.ifBlank { "created" })
    put("is_external", isExternal)
    if (note.isNotBlank()) put("note", note)
}

/** A template's role slot — no person on it. */
internal fun EnvelopeRecipient.toSlotWire(index: Int): JsonObject = buildJsonObject {
    put("role", role)
    put("routing_order", if (routingOrder > 0) routingOrder else index + 1)
    put(
        "placeholder_label",
        placeholderLabel.ifBlank { name.ifBlank { email.ifBlank { "Signer ${index + 1}" } } },
    )
}

/**
 * A tab on the wire. `recipient_index` names the owner; a `recipient_id`
 * that is not a 24-hex ObjectId is rejected, so ids only travel when they
 * came from the server. Only a set font size or colour goes out — the
 * backend refuses an empty one.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One JSON body, spelled out key by key.
internal fun EnvelopeField.toWire(clearIdAndValue: Boolean = false): JsonObject = buildJsonObject {
    if (!clearIdAndValue && OBJECT_ID.matches(id)) put("_id", id)
    put("type", type.wire)
    put("page", page)
    put("x", x)
    put("y", y)
    put("width", width)
    put("height", height)
    put("recipient_index", recipientIndex)
    if (!clearIdAndValue && OBJECT_ID.matches(recipientId)) put("recipient_id", recipientId)
    put("placement_mode", "coordinate")
    put("document_index", documentIndex)
    put("label", label)
    put("required", required)
    put("locked", locked)
    if (defaultValue.isNotBlank()) put("default_value", defaultValue)
    put("value", "")
    put("anchor_string", "")
    if (autoInitial) put("auto_initial", true)
    style.fontFamily?.let { put("font_family", it) }
    style.fontSize?.takeIf { it > 0 }?.let { put("font_size", it) }
    style.fontColor?.takeIf { it.isNotBlank() }?.let { put("font_color", it) }
    if (style.bold) put("bold", true)
    if (style.italic) put("italic", true)
    if (style.underline) put("underline", true)
    if (type.hasOptions) {
        put(
            "options",
            buildJsonArray {
                options.forEach { option ->
                    add(
                        buildJsonObject {
                            put("option_id", option.id)
                            put("label", option.label)
                        },
                    )
                }
            },
        )
    }
}

internal fun EnvelopeSettings.toWire(title: String, description: String): JsonObject = buildJsonObject {
    put("email_subject", emailSubject.ifBlank { title })
    put("email_body", emailBody.ifBlank { description })
    put("enable_reminders", true)
    put("reminder_delay_days", 1)
    expirationDays?.let { put("expiration_days", it) }
    put("signing_order_enabled", signingOrderEnabled)
    put("signingOrderEnabled", signingOrderEnabled)
    put("placement_mode", placementMode)
}

/** `POST /envelopes` and `PUT /envelopes/{id}` share one body. */
internal fun EnvelopeDraft.toWire(): JsonObject = buildJsonObject {
    put("title", title)
    put("description", description)
    document?.let { put("attachment", it.toWire(includePageCount = true)) }
    put("initials_on_all_pages", settings.initialsOnAllPages)
    put("reminder_cadence_days", settings.reminderCadenceDays ?: DEFAULT_REMINDER_CADENCE_DAYS)
    put("recipients", buildJsonArray { recipients.forEach { add(it.toWire()) } })
    put("tabs", buildJsonArray { fields.forEach { add(it.toWire()) } })
    put("settings", settings.toWire(title, description))
    put("send_now", false)
}

internal fun TemplateDraft.toWire(): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    put("category", category)
    put("documents", buildJsonArray { documents.forEachIndexed { index, doc -> add(doc.toTemplateWire(index)) } })
    put("recipients", buildJsonArray { slots.forEachIndexed { index, slot -> add(slot.toSlotWire(index)) } })
    // Ids are per envelope; a template's tabs are minted fresh on use.
    put("tabs", buildJsonArray { fields.forEach { add(it.toWire(clearIdAndValue = true)) } })
    put(
        "settings",
        buildJsonObject {
            put("initials_on_all_pages", settings.initialsOnAllPages)
            put("email_subject", settings.emailSubject)
            put("email_body", settings.emailBody)
            put("placement_mode", settings.placementMode)
            put("signing_order_enabled", settings.signingOrderEnabled)
        },
    )
    put("email_subject", settings.emailSubject)
    put("email_body", settings.emailBody)
    fromEnvelopeId?.takeIf { it.isNotBlank() }?.let { put("from_envelope_id", it) }
}

/**
 * One `signed_fields` entry — the web's `buildSignedFields`, transcribed:
 * marks and uploads send their stored descriptor whole, checkboxes the
 * strings `true`/`false`, an unanswered date-signed today's label, choices
 * the option id, and everything else text — the field's own default when
 * the signer never touched it.
 */
internal fun signedFieldWire(field: SignedField, today: String): JsonObject = buildJsonObject {
    put("tab_id", field.tabId)
    put("type", field.type.wire)
    put("document_index", field.documentIndex)
    when (val filled = field.answer) {
        is FieldAnswer.Mark -> put("value", filled.image.toWire())
        is FieldAnswer.Ticked -> put("value", if (filled.checked) "true" else "false")
        is FieldAnswer.Typed -> put("value", filled.text)
        is FieldAnswer.Chosen -> put("value", filled.optionId)
        null -> when (field.type) {
            FieldType.DateSigned -> put("value", today)
            FieldType.Checkbox -> put("value", if (field.defaultValue == "true") "true" else "false")
            else -> put("value", field.defaultValue)
        }
    }
}

/**
 * A list answer, whichever way it is wrapped: a bare array, `{items: []}`,
 * or `{<key>: []}` for the keys a caller names.
 */
internal fun JsonElement?.rows(vararg keys: String): JsonArray = when (this) {
    is JsonArray -> this
    is JsonObject -> (listOf("items") + keys)
        .firstNotNullOfOrNull { key -> this[key] as? JsonArray }
        ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}

internal fun JsonElement?.rowsOf(key: String): JsonArray = (this as? JsonObject)?.get(key)?.jsonArray
    ?: rows()

private val OBJECT_ID = Regex("^[a-fA-F0-9]{24}$")

/** The web's `reminderFrequencyDays: 2`, which its serialiser sends as the cadence. */
internal const val DEFAULT_REMINDER_CADENCE_DAYS = 2
