package com.zillit.desktop.feature.esignature.data

import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.BulkJob
import com.zillit.desktop.feature.esignature.domain.BulkJobRow
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeSettings
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.FieldOption
import com.zillit.desktop.feature.esignature.domain.FieldStyle
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.FieldValue
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.StoredFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * Wire shapes for the envelope service, all-optional and primitive-tolerant
 * like every module in this backend family. Lists may arrive as a bare
 * array or wrapped `{items: [...]}` — the repository handles both.
 */
@Serializable
internal data class StoredFileDto(
    val media: String? = null,
    val thumbnail: String? = null,
    val bucket: String? = null,
    val region: String? = null,
    val name: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("content_subtype") val contentSubtype: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("page_count") val pageCount: JsonPrimitive? = null,
    val size: JsonPrimitive? = null,
    @SerialName("file_size") val fileSize: JsonPrimitive? = null,
) {
    fun toDomain(): StoredFile? {
        val key = media?.takeIf { it.isNotBlank() } ?: return null
        return StoredFile(
            media = key,
            thumbnail = thumbnail?.takeIf { it.isNotBlank() } ?: key,
            bucket = bucket.orEmpty(),
            region = region.orEmpty(),
            name = name ?: fileName ?: key.substringAfterLast('/'),
            contentType = contentType ?: "document",
            contentSubtype = contentSubtype ?: if (mimeType?.contains("pdf") != false) "pdf" else "",
            pageCount = pageCount?.intOrNull ?: pageCount?.contentOrNull?.toIntOrNull() ?: 0,
            sizeBytes = (size ?: fileSize)?.longOrNull ?: 0,
        )
    }
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
    @SerialName("viewed_on") val viewedOn: JsonPrimitive? = null,
    @SerialName("signed_on") val signedOn: JsonPrimitive? = null,
    @SerialName("declined_on") val declinedOn: JsonPrimitive? = null,
    @SerialName("accepted_terms_on") val acceptedTermsOn: JsonPrimitive? = null,
    val note: String? = null,
    @SerialName("placeholder_label") val placeholderLabel: String? = null,
) {
    fun toDomain(): EnvelopeRecipient = EnvelopeRecipient(
        id = id ?: recipientId ?: "",
        userId = userId.orEmpty(),
        name = name.orEmpty(),
        email = email.orEmpty(),
        role = role ?: EnvelopeRecipient.ROLE_SIGNER,
        routingOrder = routingOrder?.intOrNull ?: routingOrder?.contentOrNull?.toIntOrNull() ?: 0,
        status = status ?: "created",
        isExternal = isExternal.asBoolean(),
        declinedReason = declinedReason.orEmpty(),
        viewedOn = viewedOn.asMillis(),
        signedOn = signedOn.asMillis(),
        declinedOn = declinedOn.asMillis(),
        acceptedTermsOn = acceptedTermsOn.asMillis(),
        note = note.orEmpty(),
        placeholderLabel = placeholderLabel.orEmpty(),
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
    @SerialName("recipient_id") val recipientId: JsonPrimitive? = null,
    @SerialName("recipient_index") val recipientIndex: JsonPrimitive? = null,
    val label: String? = null,
    @SerialName("default_value") val defaultValue: JsonPrimitive? = null,
    /** Absent means required — the backend ships it on every tab. */
    val required: JsonPrimitive? = null,
    val locked: JsonPrimitive? = null,
    @SerialName("document_index") val documentIndex: JsonPrimitive? = null,
    /** A string for typed fields; a stored-file object for marks and uploads. */
    val value: JsonElement? = null,
    @SerialName("font_family") val fontFamily: String? = null,
    @SerialName("font_size") val fontSize: JsonPrimitive? = null,
    @SerialName("font_color") val fontColor: String? = null,
    val bold: JsonPrimitive? = null,
    val italic: JsonPrimitive? = null,
    val underline: JsonPrimitive? = null,
    val options: List<JsonElement> = emptyList(),
    @SerialName("auto_initial") val autoInitial: JsonPrimitive? = null,
) {
    fun toDomain(recipientIds: List<String>): EnvelopeField? {
        val pageNo = page?.intOrNull ?: page?.contentOrNull?.toIntOrNull() ?: return null
        val type = FieldType.fromWire(this.type)
        // A legacy wire carried the owner as a bare number — an index, not an id.
        val numericOwner = recipientId?.takeIf { !it.isString }?.intOrNull
        val ownerId = if (numericOwner == null) recipientId?.contentOrNull.orEmpty() else ""
        // The web derives `recipientIndex` from the id when the wire omits it.
        val ownerIndex = recipientIndex?.intOrNull
            ?: numericOwner
            ?: recipientIds.indexOf(ownerId).takeIf { it >= 0 }
            ?: 0
        return EnvelopeField(
            id = id.orEmpty(),
            type = type,
            page = pageNo,
            x = x?.doubleOrNull ?: 0.0,
            y = y?.doubleOrNull ?: 0.0,
            width = width?.doubleOrNull ?: type.defaultWidth,
            height = height?.doubleOrNull ?: type.defaultHeight,
            recipientId = ownerId,
            recipientIndex = ownerIndex,
            label = label.orEmpty(),
            defaultValue = defaultValue?.contentOrNull.orEmpty(),
            required = required.asBoolean(default = true),
            locked = locked.asBoolean(),
            documentIndex = documentIndex?.intOrNull ?: 0,
            value = readValue(),
            style = readStyle(),
            options = readOptions(),
            autoInitial = autoInitial.asBoolean(),
        )
    }

    private fun readValue(): FieldValue = when (val raw = value) {
        null -> FieldValue.None
        is JsonPrimitive -> raw.contentOrNull?.takeIf { it.isNotBlank() }?.let { FieldValue.Text(it) }
            ?: FieldValue.None
        is JsonObject -> runCatching {
            wireJson.decodeFromJsonElement(StoredFileDto.serializer(), raw).toDomain()
        }.getOrNull()?.let { FieldValue.File(it) } ?: FieldValue.None
        else -> FieldValue.None
    }

    private fun readStyle(): FieldStyle = FieldStyle(
        fontFamily = fontFamily?.takeIf { it.isNotBlank() },
        fontSize = fontSize?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() },
        fontColor = fontColor?.takeIf { it.isNotBlank() },
        bold = bold.asBoolean(),
        italic = italic.asBoolean(),
        underline = underline.asBoolean(),
    )

    /** Choices: bare strings, or objects with an `option_id` and `label`. */
    private fun readOptions(): List<FieldOption> = options.mapNotNull { option ->
        when (option) {
            is JsonPrimitive -> option.contentOrNull?.let { FieldOption(id = it, label = it) }
            is JsonObject -> {
                val label = (option["label"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val id = ((option["option_id"] ?: option["value"] ?: option["id"]) as? JsonPrimitive)
                    ?.contentOrNull ?: label
                if (id.isBlank() && label.isBlank()) null else FieldOption(id = id.ifBlank { label }, label = label)
            }
            else -> null
        }
    }
}

@Serializable
internal data class SettingsDto(
    @SerialName("email_subject") val emailSubject: String? = null,
    @SerialName("email_body") val emailBody: String? = null,
    @SerialName("initials_on_all_pages") val initialsOnAllPages: JsonPrimitive? = null,
    @SerialName("reminder_cadence_days") val reminderCadenceDays: JsonPrimitive? = null,
    @SerialName("reminder_frequency_days") val reminderFrequencyDays: JsonPrimitive? = null,
    @SerialName("expiration_days") val expirationDays: JsonPrimitive? = null,
    @SerialName("signing_order_enabled") val signingOrderEnabled: JsonPrimitive? = null,
    @SerialName("signingOrderEnabled") val signingOrderEnabledCamel: JsonPrimitive? = null,
    @SerialName("placement_mode") val placementMode: String? = null,
) {
    fun toDomain(
        topInitials: JsonPrimitive?,
        topCadence: JsonPrimitive?,
    ): EnvelopeSettings = EnvelopeSettings(
        emailSubject = emailSubject.orEmpty(),
        emailBody = emailBody.orEmpty(),
        initialsOnAllPages = (topInitials ?: initialsOnAllPages).asBoolean(),
        reminderCadenceDays = (topCadence ?: reminderCadenceDays ?: reminderFrequencyDays)?.intOrNull,
        expirationDays = expirationDays?.intOrNull,
        signingOrderEnabled = (signingOrderEnabled ?: signingOrderEnabledCamel).asBoolean(),
        placementMode = placementMode?.takeIf { it.isNotBlank() } ?: "manual",
    )
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
    val settings: SettingsDto? = null,
    @SerialName("initials_on_all_pages") val initialsOnAllPages: JsonPrimitive? = null,
    @SerialName("reminder_cadence_days") val reminderCadenceDays: JsonPrimitive? = null,
    @SerialName("created_by") val createdBy: String? = null,
    val created: JsonPrimitive? = null,
    val updated: JsonPrimitive? = null,
    @SerialName("sent_on") val sentOn: JsonPrimitive? = null,
    @SerialName("completed_on") val completedOn: JsonPrimitive? = null,
    @SerialName("expires_on") val expiresOn: JsonPrimitive? = null,
    @SerialName("bulk_job_id") val bulkJobId: String? = null,
    @SerialName("template_id") val templateId: String? = null,
) {
    fun toDomain(): Envelope? {
        val envelopeId = id?.takeIf { it.isNotBlank() } ?: return null
        val people = recipients.orEmpty().map { it.toDomain() }
        return Envelope(
            id = envelopeId,
            title = title.orEmpty(),
            description = description.orEmpty(),
            status = EnvelopeStatus.fromWire(status),
            // Single-document envelopes carry `attachment`; multi-document
            // ones a `documents` array whose first entry is the primary.
            document = attachment?.toDomain() ?: documents?.firstOrNull()?.toDomain(),
            signedDocument = signedDocument?.toDomain(),
            recipients = people,
            fields = tabs.orEmpty().mapNotNull { it.toDomain(people.map { r -> r.id }) },
            settings = (settings ?: SettingsDto()).toDomain(initialsOnAllPages, reminderCadenceDays),
            createdBy = createdBy.orEmpty(),
            created = created.asMillis(),
            updated = updated.asMillis(),
            sentOn = sentOn.asMillis(),
            completedOn = completedOn.asMillis(),
            expiresOn = expiresOn.asMillis(),
            bulkJobId = bulkJobId.orEmpty(),
            templateId = templateId.orEmpty(),
        )
    }
}

@Serializable
internal data class TemplateDto(
    @SerialName("_id") val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val documents: List<StoredFileDto>? = null,
    val attachment: StoredFileDto? = null,
    val recipients: List<RecipientDto>? = null,
    val tabs: List<FieldDto>? = null,
    val settings: SettingsDto? = null,
    @SerialName("email_subject") val emailSubject: String? = null,
    @SerialName("email_body") val emailBody: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    val created: JsonPrimitive? = null,
    val updated: JsonPrimitive? = null,
) {
    fun toDomain(): EnvelopeTemplate? {
        val templateId = id?.takeIf { it.isNotBlank() } ?: return null
        val slots = recipients.orEmpty().map { it.toDomain() }
        val settings = (settings ?: SettingsDto()).toDomain(null, null)
        return EnvelopeTemplate(
            id = templateId,
            name = name.orEmpty(),
            description = description.orEmpty(),
            category = category.orEmpty(),
            documents = documents.orEmpty().mapNotNull { it.toDomain() }
                .ifEmpty { listOfNotNull(attachment?.toDomain()) },
            recipients = slots,
            fields = tabs.orEmpty().mapNotNull { it.toDomain(slots.map { r -> r.id }) },
            settings = settings.copy(
                emailSubject = settings.emailSubject.ifBlank { emailSubject.orEmpty() },
                emailBody = settings.emailBody.ifBlank { emailBody.orEmpty() },
            ),
            created = created.asMillis(),
            updated = updated.asMillis(),
            createdBy = createdBy.orEmpty(),
        )
    }
}

@Serializable
internal data class BulkJobRowDto(
    @SerialName("row_index") val rowIndex: JsonPrimitive? = null,
    val status: String? = null,
    @SerialName("envelope_id") val envelopeId: String? = null,
    val error: JsonElement? = null,
    val row: JsonObject? = null,
    @SerialName("recipient_name") val recipientName: String? = null,
    @SerialName("recipient_email") val recipientEmail: String? = null,
    @SerialName("envelope_status") val envelopeStatus: String? = null,
    @SerialName("signed_at") val signedAt: JsonPrimitive? = null,
    @SerialName("viewed_at") val viewedAt: JsonPrimitive? = null,
    @SerialName("declined_at") val declinedAt: JsonPrimitive? = null,
    @SerialName("last_reminded_at") val lastRemindedAt: JsonPrimitive? = null,
) {
    fun toDomain(): BulkJobRow = BulkJobRow(
        rowIndex = rowIndex?.intOrNull ?: 0,
        status = status.orEmpty(),
        envelopeId = envelopeId.orEmpty(),
        error = when (val e = error) {
            is JsonPrimitive -> e.contentOrNull.orEmpty()
            is JsonObject -> (e["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            else -> ""
        },
        name = recipientName ?: (row?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty(),
        email = recipientEmail ?: (row?.get("email") as? JsonPrimitive)?.contentOrNull.orEmpty(),
        envelopeStatus = envelopeStatus.orEmpty(),
        signedOn = signedAt.asMillis(),
        viewedOn = viewedAt.asMillis(),
        declinedOn = declinedAt.asMillis(),
        lastRemindedOn = lastRemindedAt.asMillis(),
    )
}

@Serializable
internal data class BulkJobDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("bulk_job_id") val bulkJobId: String? = null,
    val name: String? = null,
    @SerialName("template_id") val templateId: String? = null,
    @SerialName("template_name") val templateName: String? = null,
    val status: String? = null,
    @SerialName("total_rows") val totalRows: JsonPrimitive? = null,
    val processed: JsonPrimitive? = null,
    val succeeded: JsonPrimitive? = null,
    val failed: JsonPrimitive? = null,
    val created: JsonPrimitive? = null,
    val updated: JsonPrimitive? = null,
    val envelopes: List<BulkJobRowDto>? = null,
    val rows: List<BulkJobRowDto>? = null,
) {
    fun toDomain(): BulkJob? {
        val jobId = (id ?: bulkJobId)?.takeIf { it.isNotBlank() } ?: return null
        return BulkJob(
            id = jobId,
            name = name.orEmpty(),
            templateId = templateId.orEmpty(),
            templateName = templateName.orEmpty(),
            status = status.orEmpty(),
            totalRows = totalRows?.intOrNull ?: 0,
            processed = processed?.intOrNull ?: 0,
            succeeded = succeeded?.intOrNull ?: 0,
            failed = failed?.intOrNull ?: 0,
            created = created.asMillis(),
            updated = updated.asMillis(),
            rows = (envelopes ?: rows).orEmpty().map { it.toDomain() },
        )
    }
}

@Serializable
internal data class SavedSignatureDto(
    @SerialName("_id") val id: String? = null,
    val kind: String? = null,
    val image: StoredFileDto? = null,
    val created: JsonPrimitive? = null,
) {
    fun toDomain(): SavedSignature? {
        val sigId = id?.takeIf { it.isNotBlank() } ?: return null
        return SavedSignature(
            id = sigId,
            isSignature = kind != "initial",
            image = image?.toDomain(),
            created = created.asMillis(),
        )
    }
}

@Serializable
internal data class AuditEntryDto(
    val action: String? = null,
    val event: String? = null,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val actor: JsonElement? = null,
    @SerialName("actor_email") val actorEmail: String? = null,
    val email: String? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    val created: JsonPrimitive? = null,
    val timestamp: JsonPrimitive? = null,
    val at: JsonPrimitive? = null,
    val details: JsonElement? = null,
    val message: String? = null,
    @SerialName("ip_address") val ipAddress: String? = null,
    val ip: String? = null,
) {
    @Suppress("CyclomaticComplexMethod") // The web's own fallback chain, key by key.
    fun toDomain(): AuditEntry = AuditEntry(
        action = action ?: event ?: "",
        actorName = actorName ?: fullName ?: (actor as? JsonPrimitive)?.contentOrNull
            ?: ((actor as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull ?: "",
        actorEmail = actorEmail ?: email
            ?: ((actor as? JsonObject)?.get("email") as? JsonPrimitive)?.contentOrNull ?: "",
        happenedOn = (createdOn ?: created ?: timestamp ?: at).asMillis(),
        details = when (val d = details) {
            is JsonPrimitive -> d.contentOrNull.orEmpty()
            is JsonObject -> (d["message"] as? JsonPrimitive)?.contentOrNull
                ?: (d["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            else -> message.orEmpty()
        },
        ipAddress = ipAddress ?: ip ?: "",
    )
}

internal val wireJson = Json { ignoreUnknownKeys = true }

internal fun JsonPrimitive?.asBoolean(default: Boolean = false): Boolean = when (this?.content) {
    null -> default
    "true", "1" -> true
    "false", "0" -> false
    else -> default
}

/**
 * A moment as epoch millis: numbers in millis (or seconds, when they are
 * too small to be millis), quoted numbers, and ISO strings. Zero — the
 * wire's "never" — is null, so it can never render as 1970.
 */
@Suppress("ReturnCount") // Each shape answers on its own line.
internal fun JsonPrimitive?.asMillis(): Long? {
    val raw = this?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: return null
    raw.toLongOrNull()?.let { number ->
        if (number <= 0) return null
        return if (number < SECONDS_CUTOFF) number * MILLIS_PER_SECOND else number
    }
    raw.toDoubleOrNull()?.let { return it.toLong().takeIf { n -> n > 0 } }
    return runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
}

private const val SECONDS_CUTOFF = 100_000_000_000L
private const val MILLIS_PER_SECOND = 1000L

/** Decodes each row that decodes; a malformed row is dropped, not the list. */
internal inline fun <reified T> List<JsonElement>.decodeRows(
    serializer: kotlinx.serialization.KSerializer<T>,
): List<T> = mapNotNull { row ->
    runCatching { wireJson.decodeFromJsonElement(serializer, row.jsonObject) }.getOrNull()
}
