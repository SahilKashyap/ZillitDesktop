package com.zillit.desktop.feature.formsignature.data

import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.HistoryEntry
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignSpot
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.SignedCopy
import com.zillit.desktop.feature.formsignature.domain.SignerOption
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.domain.StandardFormType
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Wire shapes for the documents service, transcribed from the web's V2 tool.
 *
 * Everything optional, and every numeric or boolean field typed as
 * [JsonPrimitive]: this backend family has been seen sending booleans as
 * strings and numbers both quoted and bare, and a decode that dies on
 * `"1"` where it expected `1` takes the whole list with it.
 */
@Serializable
internal data class StoredDocumentDto(
    val media: String? = null,
    val thumbnail: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("content_subtype") val contentSubtype: String? = null,
    val bucket: String? = null,
    val region: String? = null,
    val name: String? = null,
) {
    fun toDomain(): StoredDocument? {
        val key = media?.takeIf { it.isNotBlank() } ?: return null
        return StoredDocument(
            media = key,
            thumbnail = thumbnail?.takeIf { it.isNotBlank() } ?: key,
            bucket = bucket.orEmpty(),
            region = region.orEmpty(),
            name = name.orEmpty(),
            contentType = contentType ?: StoredDocument.CONTENT_TYPE_DOCUMENT,
            contentSubtype = contentSubtype ?: StoredDocument.SUBTYPE_PDF,
        )
    }
}

/**
 * The full write shape.
 *
 * `caption`, `duration`, `height` and `width` are meaningless for documents
 * but the web sends them on every write; a payload without them is a
 * different payload, and this backend family has silently dropped writes it
 * did not recognise before.
 */
internal fun StoredDocument.toWire(): JsonObject = buildJsonObject {
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
}

/** The signature block's image descriptor — no content types on this one. */
internal fun StoredDocument.toSignatureWire(): JsonObject = buildJsonObject {
    put("media", media)
    put("thumbnail", thumbnail)
    put("bucket", bucket)
    put("region", region)
    put("name", name)
}

@Serializable
internal data class SignedCopyDto(
    @SerialName("signed_by") val signedBy: String? = null,
    val document: StoredDocumentDto? = null,
) {
    fun toDomain() = SignedCopy(signedBy.orEmpty(), document?.toDomain())
}

/**
 * One row of either standard-forms tab. The two tabs spell their fields
 * differently — `document`/`user_id` on `all-forms`, `sender_documents`/
 * `sender_id` on `your-forms` — and this DTO carries both spellings so one
 * decode serves both lists.
 */
@Serializable
internal data class StandardFormDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("document_serial_no") val serialNo: JsonPrimitive? = null,
    val document: StoredDocumentDto? = null,
    @SerialName("sender_documents") val senderDocuments: StoredDocumentDto? = null,
    @SerialName("document_type") val documentType: String? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val documents: List<SignedCopyDto>? = null,
) {
    fun toDomain(): StandardForm? {
        val rowId = id?.takeIf { it.isNotBlank() } ?: return null
        return StandardForm(
            id = rowId,
            serialNo = serialNo?.content.orEmpty(),
            document = (document ?: senderDocuments)?.toDomain(),
            type = StandardFormType.fromWire(documentType),
            createdOn = createdOn?.content.orEmpty(),
            uploaderId = userId ?: senderId ?: "",
            uploaderName = fullName.orEmpty(),
            signedCopies = documents.orEmpty().map { it.toDomain() },
        )
    }
}

@Serializable
internal data class SignSpotDto(
    val type: String? = null,
    @SerialName("page_number") val page: JsonPrimitive? = null,
    @SerialName("x_coordinate") val x: JsonPrimitive? = null,
    @SerialName("y_coordinate") val y: JsonPrimitive? = null,
    val width: JsonPrimitive? = null,
    val height: JsonPrimitive? = null,
) {
    fun toDomain(): SignSpot? {
        val pageNo = page?.intOrNull ?: return null
        return SignSpot(
            kind = SignSpotKind.fromWire(type),
            page = pageNo,
            x = x?.doubleOrNull ?: 0.0,
            y = y?.doubleOrNull ?: 0.0,
            width = width?.doubleOrNull ?: DEFAULT_SPOT_WIDTH,
            height = height?.doubleOrNull ?: DEFAULT_SPOT_HEIGHT,
        )
    }

    companion object {
        // The fallback box, in PDF points — roughly the web's default field.
        const val DEFAULT_SPOT_WIDTH = 160.0
        const val DEFAULT_SPOT_HEIGHT = 56.0
    }
}

internal fun SignSpot.toWire(): JsonObject = buildJsonObject {
    put("type", kind.wire)
    put("page_number", page)
    put("x_coordinate", x)
    put("y_coordinate", y)
    put("width", width)
    put("height", height)
}

@Serializable
internal data class DocumentSignerDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_email") val email: String? = null,
    @SerialName("user_fullname") val fullName: String? = null,
    val status: String? = null,
    @SerialName("order_of_signing") val order: JsonPrimitive? = null,
    @SerialName("is_external") val isExternal: JsonPrimitive? = null,
    @SerialName("document_coordinates") val spots: List<SignSpotDto>? = null,
) {
    fun toDomain(): DocumentSigner? {
        val signer = userId?.takeIf { it.isNotBlank() } ?: return null
        return DocumentSigner(
            userId = signer,
            email = email.orEmpty(),
            fullName = fullName.orEmpty(),
            signed = status == STATUS_SIGNED,
            order = order?.intOrNull ?: 0,
            isExternal = isExternal.asBoolean(),
            spots = spots.orEmpty().mapNotNull { it.toDomain() },
        )
    }
}

@Serializable
internal data class SignDocumentDto(
    @SerialName("_id") val id: String? = null,
    val document: StoredDocumentDto? = null,
    @SerialName("signing_document") val signingDocument: StoredDocumentDto? = null,
    val users: List<DocumentSignerDto>? = null,
    @SerialName("uploaded_by") val uploadedBy: String? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("only_signature_required") val onlySignatureRequired: JsonPrimitive? = null,
    @SerialName("user_signature_required") val userSignatureRequired: JsonPrimitive? = null,
    @SerialName("document_finalized") val finalized: JsonPrimitive? = null,
    @SerialName("user_status") val userStatus: String? = null,
    @SerialName("document_coordinates") val spots: List<SignSpotDto>? = null,
) {
    fun toDomain(): SignDocument? {
        val docId = id?.takeIf { it.isNotBlank() } ?: return null
        return SignDocument(
            id = docId,
            document = document?.toDomain(),
            signingDocument = signingDocument?.toDomain(),
            signers = users.orEmpty().mapNotNull { it.toDomain() },
            uploadedBy = uploadedBy.orEmpty(),
            createdOn = createdOn?.content.orEmpty(),
            onlySignatureRequired = onlySignatureRequired.asBoolean(default = true),
            userSignatureRequired = userSignatureRequired.asBoolean(),
            finalized = finalized.asBoolean() || userStatus == STATUS_SIGNED,
            spots = spots.orEmpty().mapNotNull { it.toDomain() },
        )
    }
}

@Serializable
internal data class SignatureBlockDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("signature_id") val signatureId: String? = null,
    val signature: StoredDocumentDto? = null,
    @SerialName("is_signature") val isSignature: JsonPrimitive? = null,
) {
    fun toDomain(): SignatureBlock? {
        val blockId = (id ?: signatureId)?.takeIf { it.isNotBlank() } ?: return null
        return SignatureBlock(
            id = blockId,
            isSignature = isSignature.asBoolean(default = true),
            image = signature?.toDomain(),
            name = signature?.name.orEmpty(),
        )
    }
}

@Serializable
internal data class SignerOptionDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("user_fullname") val userFullname: String? = null,
    val name: String? = null,
    @SerialName("user_email") val userEmail: String? = null,
    val email: String? = null,
    @SerialName("posting_access") val postingAccess: JsonPrimitive? = null,
    @SerialName("view_access") val viewAccess: JsonPrimitive? = null,
) {
    fun toDomain(): SignerOption? {
        val id = userId?.takeIf { it.isNotBlank() } ?: return null
        // Only view-access users are offered — the same pool the web sends
        // documents to. A row with neither flag is a row the tool cannot use.
        if (!viewAccess.asBoolean() && !postingAccess.asBoolean()) return null
        return SignerOption(
            userId = id,
            fullName = fullName ?: userFullname ?: name ?: "",
            email = userEmail ?: email ?: "",
            canPost = postingAccess.asBoolean(),
        )
    }
}

@Serializable
internal data class HistoryEntryDto(
    val action: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("signed_by") val signedBy: String? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
) {
    fun toDomain(): HistoryEntry = HistoryEntry(
        action = action.orEmpty(),
        actorName = fullName ?: userName ?: signedBy ?: "",
        happenedOn = createdOn?.content.orEmpty(),
    )
}

/**
 * String-tolerant boolean. `true`, `"true"` and `1` all count — all three
 * have been observed from this backend family.
 */
internal fun JsonPrimitive?.asBoolean(default: Boolean = false): Boolean = when (this?.content) {
    null -> default
    "true", "1" -> true
    "false", "0" -> false
    else -> default
}

internal const val STATUS_SIGNED = "signed"
