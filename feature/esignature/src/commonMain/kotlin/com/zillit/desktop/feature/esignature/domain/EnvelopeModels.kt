package com.zillit.desktop.feature.esignature.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is in the E-Signature tool.
 *
 * The web's `useDocuSignAccess`, transcribed: entry needs view **or**
 * posting; posting is authorship (composing, deleting drafts, reminding);
 * download is its own strict flag (the signed PDF and the audit trail);
 * a member with neither posting nor admin sees only the receiver's side.
 */
data class EsignViewer(
    val canView: Boolean = true,
    val canPost: Boolean = true,
    val canDownload: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !canPost

    /** The web's receiver-only branch: no manager surfaces at all. */
    val receiverOnly: Boolean get() = ready && !isAdmin && !canPost

    companion object {
        const val TOOL_IDENTIFIER = "e_signature_tool"

        fun from(permissions: ProjectPermissions): EsignViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return EsignViewer(ready = false)
            }
            return EsignViewer(
                canView = access.enabled && access.canView,
                canPost = access.enabled && access.canPost,
                canDownload = access.enabled && access.canDownload,
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}

/**
 * Envelope status, as the wire spells it. The label column is the web's
 * `STATUS_TAG`; anything unrecognised keeps its wire string as its label.
 */
enum class EnvelopeStatus(val wire: String, val label: String) {
    Draft("draft", "Draft"),
    Sent("sent", "Sent"),
    Delivered("delivered", "Delivered"),
    Signed("signed", "Signed"),
    Completed("completed", "Completed"),
    Declined("declined", "Declined"),
    Voided("voided", "Voided"),
    Expired("expired", "Expired"),
    Rejected("rejected", "Rejected"),
    Unknown("", ""),
    ;

    companion object {
        fun fromWire(raw: String?): EnvelopeStatus =
            entries.firstOrNull { it.wire == raw } ?: Unknown
    }
}

/** One signer (or CC) on an envelope. */
data class EnvelopeRecipient(
    /** The backend's recipient id — what tabs point at. */
    val id: String = "",
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    /** `signer` or `cc`. */
    val role: String = "signer",
    val routingOrder: Int = 0,
    /** Wire: created, sent, delivered (viewed), signed, completed, declined. */
    val status: String = "created",
    val isExternal: Boolean = false,
    val declinedReason: String = "",
) {
    val signed: Boolean get() = status == "signed" || status == "completed"
    val declined: Boolean get() = status == "declined"

    /** The web's recipient-status label map (`RST`). */
    val statusLabel: String
        get() = when (status) {
            "created" -> "Created"
            "sent" -> "Sent"
            "delivered" -> "Viewed"
            "signed", "completed" -> "Completed"
            "declined" -> "Declined"
            else -> status
        }
}

/**
 * A field placed on the document.
 *
 * ## Coordinates are PDF points, **top-left** origin
 *
 * The opposite vertical convention from the documents tool: this service's
 * placer measures y *down* from the page's top edge, and the backend
 * flattens with the same reading. Nothing in this module ever flips.
 */
data class EnvelopeField(
    val id: String = "",
    val type: FieldType = FieldType.SignHere,
    val page: Int = 1,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = FieldType.DEFAULT_WIDTH,
    val height: Double = FieldType.DEFAULT_HEIGHT,
    /** The owning recipient's id, 24-hex — present on read. */
    val recipientId: String = "",
    val label: String = "",
    val defaultValue: String = "",
    /**
     * Whether the signer must fill this before the envelope can be completed.
     *
     * **Defaults to true, as the wire does** — every tab the backend ships
     * carries `required` and Android declares it `= true` (`DocuSignDtos`).
     * A field is mandatory unless it says otherwise, so a value this port
     * failed to read must not read as optional.
     */
    val required: Boolean = true,
    val documentIndex: Int = 0,
    /** Filled value after signing, when the server echoes one. */
    val value: String = "",
    /** How typed text is drawn — the phones' font/bold/italic/underline/colour tab keys. */
    val style: FieldStyle = FieldStyle(),
    /** A dropdown's choices, in order. */
    val options: List<String> = emptyList(),
)

/**
 * The field vocabulary this port fills. The service knows more types
 * (radio, dropdown, image, attachment); an envelope carrying one of those
 * is signed with the field's default rather than refused — the same thing
 * the web sends when a signer never touches a pre-filled field.
 */
enum class FieldType(val wire: String, val label: String) {
    SignHere("signHere", "Signature"),
    InitialHere("initialHere", "Initials"),
    DateSigned("dateSigned", "Date signed"),
    Text("text", "Text"),
    FullName("fullName", "Full name"),
    Email("email", "Email"),
    Checkbox("checkbox", "Checkbox"),
    // The phones' newer data fields (Android `DocuSignFieldType`, 2026-09).
    Phone("phone", "Phone"),
    Number("number", "Number"),
    Url("url", "URL"),
    Dropdown("dropdown", "Dropdown"),
    Attachment("attachment", "Attachment"),
    Other("", "Field"),
    ;

    val isMark: Boolean get() = this == SignHere || this == InitialHere
    val isTyped: Boolean get() =
        this == Text || this == FullName || this == Email || this == Phone || this == Number || this == Url

    companion object {
        const val DEFAULT_WIDTH = 160.0
        const val DEFAULT_HEIGHT = 48.0

        fun fromWire(raw: String?): FieldType =
            entries.firstOrNull { it.wire == raw && it != Other } ?: Other
    }
}

data class Envelope(
    val id: String,
    val title: String = "",
    val description: String = "",
    val status: EnvelopeStatus = EnvelopeStatus.Unknown,
    val document: StoredFile? = null,
    /** The finished, server-flattened PDF — present once completed. */
    val signedDocument: StoredFile? = null,
    val recipients: List<EnvelopeRecipient> = emptyList(),
    val fields: List<EnvelopeField> = emptyList(),
    val createdBy: String = "",
    val sentOn: String = "",
    val completedOn: String = "",
) {
    fun recipientFor(userId: String): EnvelopeRecipient? =
        recipients.firstOrNull { it.userId == userId && it.role == "signer" }

    fun fieldsFor(recipient: EnvelopeRecipient): List<EnvelopeField> =
        fields.filter { it.recipientId == recipient.id }

    val signerSummary: String
        get() {
            val signers = recipients.filter { it.role == "signer" }
            val done = signers.count { it.signed }
            return if (signers.isEmpty()) "No signers" else "$done of ${signers.size} signed"
        }
}

/** An S3 descriptor, as this service stores files. */
data class StoredFile(
    val media: String,
    val thumbnail: String = media,
    val bucket: String = "",
    val region: String = "",
    val name: String = "",
    val contentType: String = "document",
    val contentSubtype: String = "pdf",
    val pageCount: Int = 0,
)

/** A saved e-sign mark. Its own store, separate from the documents tool's. */
data class SavedSignature(
    val id: String,
    /** Wire `kind`: `signature` or `initial`. */
    val isSignature: Boolean = true,
    val image: StoredFile? = null,
)

/** One line of the audit trail, parsed tolerantly. */
data class AuditEntry(
    val action: String = "",
    val actorName: String = "",
    val happenedOn: String = "",
)

/** The two list scopes, with the web's bucket vocabulary per scope. */
enum class EnvelopeScope(val wire: String) { Sent("sent"), Received("received") }

/** What the signer fills, keyed by field id. */
sealed interface FieldAnswer {
    /** A saved mark's descriptor, for signHere/initialHere. */
    data class Mark(val image: StoredFile) : FieldAnswer
    data class Typed(val text: String) : FieldAnswer
    data class Ticked(val checked: Boolean) : FieldAnswer
}

/** Page rendering and stroke rasterising, injected from the host. */
interface EsignPdf {
    fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<EsignPage>>
    fun rasterizeStrokes(
        strokes: List<List<Pair<Float, Float>>>,
        width: Int,
        height: Int,
    ): ZillitResult<ByteArray>
}

/** A rendered page; the tap conversion here is top-left to top-left — no flip. */
data class EsignPage(
    val page: Int,
    val imageBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val widthPt: Double,
    val heightPt: Double,
) {
    /** Screen pixels → field anchor in page points (top-left both sides). */
    fun pointFromTap(xPx: Float, yPx: Float): Pair<Double, Double> {
        val scale = widthPt / widthPx
        return (xPx * scale) to (yPx * scale)
    }

    /** A field's rectangle in this image's pixels: x, y, w, h. */
    fun pixelRect(field: EnvelopeField): List<Float> {
        val scale = widthPx / widthPt
        return listOf(
            (field.x * scale).toFloat(),
            (field.y * scale).toFloat(),
            (field.width * scale).toFloat(),
            (field.height * scale).toFloat(),
        )
    }

    override fun equals(other: Any?): Boolean = other is EsignPage &&
        other.page == page && other.widthPx == widthPx && other.heightPx == heightPx &&
        other.imageBytes.contentEquals(imageBytes)

    override fun hashCode(): Int = page * 31 + imageBytes.size
}

/** File transfer, adapted from the host's uploader and reader. */
interface EsignFileTransfer {
    suspend fun store(fileName: String, contentType: String, bytes: ByteArray): ZillitResult<StoredFile>
    suspend fun fetch(file: StoredFile): ZillitResult<ByteArray>
}

/** A crew member offered as a signer — resolved by the host from the session. */
data class SignerOptionLike(
    val userId: String,
    val fullName: String = "",
    val email: String = "",
)

/**
 * Text styling on a tab — `font_family`, `font_size`, `font_color`, `bold`,
 * `italic`, `underline` — as Android sends and the backend stores them.
 * Absent keys mean "the document's default"; only what is set goes on the wire.
 */
data class FieldStyle(
    val fontFamily: String? = null,
    val fontSize: Int? = null,
    val fontColor: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
) {
    val isDefault: Boolean get() = this == FieldStyle()

    companion object {
        val FONT_SIZES = listOf(8, 10, 12, 14, 16, 18, 24)
    }
}
