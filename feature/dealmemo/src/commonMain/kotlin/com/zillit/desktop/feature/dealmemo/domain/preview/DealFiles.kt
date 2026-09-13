package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.DocRead
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * An `AttachmentModel` — `{media, bucket, region, name, content_type,
 * content_subtype, caption?, file_size?}` — kept as sent, because a signed copy
 * is posted back with every key it arrived with.
 */
data class DealAttachment(val json: JsonObject) {
    val media: String? get() = DocRead.text(json, "media")
    val bucket: String? get() = DocRead.text(json, "bucket")
    val region: String? get() = DocRead.text(json, "region")
    val name: String? get() = DocRead.text(json, "name")
    val title: String? get() = DocRead.text(json, "title")
    val contentType: String? get() = DocRead.text(json, "content_type")
    val contentSubtype: String? get() = DocRead.text(json, "content_subtype")

    /** `attachmentIntact`: the three keys a presign needs. The server folds a broken one to null. */
    val intact: Boolean get() = !media.isNullOrEmpty() && !bucket.isNullOrEmpty() && !region.isNullOrEmpty()

    /** `content_subtype || ext(name)`, lower-cased. */
    val extension: String
        get() = (contentSubtype ?: name?.takeIf { '.' in it }?.substringAfterLast('.')).orEmpty().lowercase()

    val isPdf: Boolean
        get() = contentSubtype?.lowercase() == PDF || name?.lowercase()?.endsWith(".pdf") == true

    /** `mimeFromAttachment`: what the viewer decides to draw. */
    val mime: String get() = mimeOf(extension)

    val isImage: Boolean get() = mime.startsWith("image/") || contentType == "image"

    companion object {
        private const val PDF = "pdf"

        fun of(element: JsonElement?): DealAttachment? = (element as? JsonObject)?.let(::DealAttachment)

        /** The mime a file extension implies — `pdf`, the image family, then a few document types. */
        @Suppress("CyclomaticComplexMethod")
        fun mimeOf(extension: String): String = when (extension.lowercase()) {
            PDF -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "mp4", "webm", "mov" -> "video/$extension"
            "mp3", "wav", "ogg" -> "audio/$extension"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            else -> "application/octet-stream"
        }
    }
}

/** One row of a signed document's `signers[]`: `{user_id, email_id, signed_at, user_type}`. */
data class DealSigner(
    val userId: String?,
    val emailId: String?,
    val signedAt: Long?,
    /** `crew` or `approver` — the server derives it from the caller. */
    val userType: String?,
) {
    companion object {
        const val CREW = "crew"
        const val APPROVER = "approver"

        /** The backend sends an empty list as `{}` — anything but an array is no signers. */
        fun listOf(element: JsonElement?): List<DealSigner> = DocRead.objects(element).map { row ->
            DealSigner(
                userId = DocRead.text(row, "user_id"),
                emailId = DocRead.text(row, "email_id"),
                signedAt = DocRead.epoch(row, "signed_at"),
                userType = DocRead.text(row, "user_type"),
            )
        }

        /**
         * `hasSignedAs`: this user's row in this role. The role matters — a
         * self-approver's crew signature must not count as their approver one.
         */
        fun hasSignedAs(signers: List<DealSigner>, userId: String?, userType: String?): Boolean =
            !userId.isNullOrEmpty() && signers.any { row ->
                row.userId == userId && (userType.isNullOrEmpty() || row.userType == userType)
            }
    }
}

/** `deal_pdf` / `crew_start_form_pdf`: the stored (signed) copy and who signed it. */
data class SignedPdf(val attachment: DealAttachment?, val signers: List<DealSigner>) {
    /** A stored copy only ever comes from a sign POST, so an intact one is the signed document. */
    val hasStoredCopy: Boolean get() = attachment?.intact == true

    companion object {
        fun of(json: JsonObject?): SignedPdf = SignedPdf(
            attachment = DealAttachment.of(json?.get("attachment")),
            signers = DealSigner.listOf(json?.get("signers")),
        )
    }
}

/**
 * One of `additional_documents.docs[]` — an attachment the accountant added,
 * with its own signature rule and, once signed, its own signers.
 */
data class AdditionalDoc(val json: JsonObject, val index: Int) {

    /** `_id ?? id` — the sign endpoint's `:docId`. */
    val id: String? get() = DocRead.text(json, "_id") ?: DocRead.text(json, "id")

    /** The signer's key: the id, or `sfdoc-<i>` for an id-less row. */
    val signKey: String get() = id ?: "sfdoc-$index"

    /** The viewer's key — `_id || document._id || sfdoc-i`, which can differ from [signKey] (web trap 69). */
    val tabKey: String
        get() = DocRead.text(json, "_id") ?: DocRead.text(DocRead.obj(json, "document"), "_id") ?: "sfdoc-$index"

    val document: DealAttachment? get() = DealAttachment.of(json["document"])

    /** The signed copy, when it carries bytes — shown before the original. */
    val signedDocument: DealAttachment?
        get() = DealAttachment.of(json["signed_document"])?.takeIf { !it.media.isNullOrEmpty() }

    val signers: List<DealSigner> get() = DealSigner.listOf(json["signers"])

    /** `additionalDocLabel`: the accountant's title, else the file name, else "Document". */
    val label: String
        get() = DocRead.text(json, "title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: document?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Document"

    val description: String get() = DocRead.text(json, "description")?.trim().orEmpty()

    /** `additionalDocFilename`: the real file name for a download, else `<label>.pdf`. */
    val filename: String get() = document?.name?.trim()?.takeIf { it.isNotEmpty() } ?: "$label.pdf"

    /**
     * `docRequiresSignature`: `(signRequired ?? sign_required) !== false` — an
     * absent flag is true, so a legacy document blocks Send until toggled off.
     */
    val signRequired: Boolean
        get() {
            val flag = json["signRequired"]?.takeUnless { it is JsonNull } ?: json["sign_required"]
            val primitive = flag as? JsonPrimitive ?: return true
            return !(primitive !is JsonNull && !primitive.isString && primitive.booleanOrNull == false)
        }

    /** PDF by its subtype or name — the only kind a signature can be placed on. */
    val isPdf: Boolean get() = document?.isPdf == true

    /** Technically signable: an id, a PDF, and bytes to sign. */
    val signable: Boolean get() = id != null && isPdf && document?.intact == true

    /** `isChecklistDocument`: sign-required, a PDF, with bytes. */
    val isChecklistDocument: Boolean
        get() {
            val attachment = DealAttachment.of(json["attachment"]) ?: document ?: DealAttachment(json)
            return signRequired && attachment.isPdf && attachment.intact
        }

    /** Signed by anyone in the crew role — the checklist's "done", whoever is looking. */
    val signedByCrew: Boolean get() = signers.any { it.userType == DealSigner.CREW }

    /** What the viewer opens: the signed copy first. */
    val shown: DealAttachment? get() = signedDocument ?: document

    companion object {
        fun listOf(additionalDocuments: JsonObject?): List<AdditionalDoc> =
            DocRead.objects(additionalDocuments?.get("docs")).mapIndexed { index, row -> AdditionalDoc(row, index) }
    }
}

/**
 * `asPassportList`: the passport field was one attachment before it became a
 * list of up to two — read both shapes.
 */
fun passportList(element: JsonElement?): List<DealAttachment> = when (element) {
    is JsonObject -> listOf(DealAttachment(element))
    else -> DocRead.objects(element).map(::DealAttachment)
}
