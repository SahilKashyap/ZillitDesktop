package com.zillit.desktop.feature.esignature.data

import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.StoredFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Wire shapes for the envelope service, all-optional and primitive-tolerant
 * like every module in this backend family. The envelope list may arrive as
 * a bare array or wrapped `{items: [...]}` — the repository handles both.
 */
@Serializable
internal data class StoredFileDto(
    val media: String? = null,
    val thumbnail: String? = null,
    val bucket: String? = null,
    val region: String? = null,
    val name: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("content_subtype") val contentSubtype: String? = null,
    @SerialName("page_count") val pageCount: JsonPrimitive? = null,
) {
    fun toDomain(): StoredFile? {
        val key = media?.takeIf { it.isNotBlank() } ?: return null
        return StoredFile(
            media = key,
            thumbnail = thumbnail?.takeIf { it.isNotBlank() } ?: key,
            bucket = bucket.orEmpty(),
            region = region.orEmpty(),
            name = name.orEmpty(),
            contentType = contentType ?: "document",
            contentSubtype = contentSubtype ?: "pdf",
            pageCount = pageCount?.intOrNull ?: 0,
        )
    }
}

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
}

@Serializable
internal data class RecipientDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("recipient_id") val recipientId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val role: String? = null,
    @SerialName("routing_order") val routingOrder: JsonPrimitive? = null,
    val status: String? = null,
    @SerialName("is_external") val isExternal: JsonPrimitive? = null,
    @SerialName("declined_reason") val declinedReason: String? = null,
) {
    fun toDomain(): EnvelopeRecipient = EnvelopeRecipient(
        id = id ?: recipientId ?: "",
        userId = userId.orEmpty(),
        name = name.orEmpty(),
        email = email.orEmpty(),
        role = role ?: "signer",
        routingOrder = routingOrder?.intOrNull ?: 0,
        status = status ?: "created",
        isExternal = isExternal.asBoolean(),
        declinedReason = declinedReason.orEmpty(),
    )
}

@Serializable
internal data class FieldDto(
    @SerialName("_id") val id: String? = null,
    val type: String? = null,
    val page: JsonPrimitive? = null,
    val x: JsonPrimitive? = null,
    val y: JsonPrimitive? = null,
    val width: JsonPrimitive? = null,
    val height: JsonPrimitive? = null,
    @SerialName("recipient_id") val recipientId: String? = null,
    val label: String? = null,
    @SerialName("default_value") val defaultValue: String? = null,
    /** Absent means required — the backend ships it on every tab. */
    val required: Boolean? = null,
    @SerialName("document_index") val documentIndex: JsonPrimitive? = null,
    val value: String? = null,
) {
    fun toDomain(): EnvelopeField? {
        val pageNo = page?.intOrNull ?: return null
        return EnvelopeField(
            id = id.orEmpty(),
            type = FieldType.fromWire(type),
            page = pageNo,
            x = x?.doubleOrNull ?: 0.0,
            y = y?.doubleOrNull ?: 0.0,
            width = width?.doubleOrNull ?: FieldType.DEFAULT_WIDTH,
            height = height?.doubleOrNull ?: FieldType.DEFAULT_HEIGHT,
            recipientId = recipientId.orEmpty(),
            label = label.orEmpty(),
            defaultValue = defaultValue.orEmpty(),
            required = required ?: true,
            documentIndex = documentIndex?.intOrNull ?: 0,
            value = value.orEmpty(),
        )
    }
}

@Serializable
internal data class EnvelopeDto(
    @SerialName("_id") val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val attachment: StoredFileDto? = null,
    val documents: List<StoredFileDto>? = null,
    @SerialName("signed_document") val signedDocument: StoredFileDto? = null,
    val recipients: List<RecipientDto>? = null,
    val tabs: List<FieldDto>? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("sent_on") val sentOn: JsonPrimitive? = null,
    @SerialName("completed_on") val completedOn: JsonPrimitive? = null,
) {
    fun toDomain(): Envelope? {
        val envelopeId = id?.takeIf { it.isNotBlank() } ?: return null
        return Envelope(
            id = envelopeId,
            title = title.orEmpty(),
            description = description.orEmpty(),
            status = EnvelopeStatus.fromWire(status),
            // Single-document envelopes carry `attachment`; multi-document
            // ones a `documents` array whose first entry is the primary.
            document = attachment?.toDomain() ?: documents?.firstOrNull()?.toDomain(),
            signedDocument = signedDocument?.toDomain(),
            recipients = recipients.orEmpty().map { it.toDomain() },
            fields = tabs.orEmpty().mapNotNull { it.toDomain() },
            createdBy = createdBy.orEmpty(),
            sentOn = sentOn?.content.orEmpty(),
            completedOn = completedOn?.content.orEmpty(),
        )
    }
}

@Serializable
internal data class SavedSignatureDto(
    @SerialName("_id") val id: String? = null,
    val kind: String? = null,
    val image: StoredFileDto? = null,
) {
    fun toDomain(): SavedSignature? {
        val sigId = id?.takeIf { it.isNotBlank() } ?: return null
        return SavedSignature(
            id = sigId,
            isSignature = kind != "initial",
            image = image?.toDomain(),
        )
    }
}

@Serializable
internal data class AuditEntryDto(
    val action: String? = null,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val actor: String? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    val timestamp: JsonPrimitive? = null,
) {
    fun toDomain(): AuditEntry = AuditEntry(
        action = action.orEmpty(),
        actorName = actorName ?: fullName ?: actor ?: "",
        happenedOn = (createdOn ?: timestamp)?.content.orEmpty(),
    )
}

internal fun JsonPrimitive?.asBoolean(default: Boolean = false): Boolean = when (this?.content) {
    null -> default
    "true", "1" -> true
    "false", "0" -> false
    else -> default
}

/**
 * One `signed_fields` entry — the web's `buildSignedFields`, transcribed:
 * marks send their stored image descriptor whole, checkboxes the strings
 * `true`/`false`, an unanswered date today's label, everything else text.
 */
internal fun signedFieldWire(
    field: com.zillit.desktop.feature.esignature.domain.SignedField,
    today: String,
): JsonObject = buildJsonObject {
    put("tab_id", field.tabId)
    put("type", field.type.wire)
    put("document_index", field.documentIndex)
    when (val filled = field.answer) {
        is com.zillit.desktop.feature.esignature.domain.FieldAnswer.Mark ->
            put("value", filled.image.toWire())
        is com.zillit.desktop.feature.esignature.domain.FieldAnswer.Ticked ->
            put("value", if (filled.checked) "true" else "false")
        is com.zillit.desktop.feature.esignature.domain.FieldAnswer.Typed ->
            put("value", filled.text)
        null -> when (field.type) {
            FieldType.DateSigned -> put("value", today)
            FieldType.Checkbox -> put("value", "false")
            else -> put("value", "")
        }
    }
}
