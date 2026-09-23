package com.zillit.desktop.feature.esignature.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Who is in the E-Signature tool.
 *
 * The web's `useDocuSignAccess`, transcribed: entry needs view **or**
 * posting; posting is authorship (composing, deleting drafts, reminding,
 * templates, bulk sends); download is its own strict flag (the signed PDF
 * and the audit trail); a member with neither posting nor admin sees only
 * the receiver's side.
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
enum class EnvelopeStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.txt_draft),
    Sent("sent", S.txt_sent),
    Delivered("delivered", S.docusign_status_delivered),
    Signed("signed", S.docusign_status_signed),
    Completed("completed", S.completed),
    Declined("declined", S.docusign_status_declined),
    Voided("voided", S.desktop_ds_voided),
    Expired("expired", S.docusign_status_expired),
    Rejected("rejected", S.rejected),
    Unknown("", ""),
    ;

    val label: String get() = if (labelKey.isEmpty()) "" else str(labelKey)

    /**
     * Whether the envelope has gone out and nothing has finished it.
     *
     * Only these can be cancelled. A draft is deleted instead, and one that is
     * completed, declined, already void or expired has an outcome that
     * cancelling would not change.
     */
    val isCancellable: Boolean get() = this == Sent || this == Delivered || this == Signed

    /** Still waiting on somebody — a reminder has someone to reach. */
    val isInFlight: Boolean get() = this == Sent || this == Delivered || this == Signed

    val isTerminal: Boolean get() =
        this == Completed || this == Declined || this == Voided || this == Expired || this == Rejected

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
    val role: String = ROLE_SIGNER,
    val routingOrder: Int = 0,
    /** Wire: created, sent, delivered (viewed), signed, completed, declined. */
    val status: String = "created",
    val isExternal: Boolean = false,
    val declinedReason: String = "",
    /** Epoch millis, when the wire carries them. */
    val viewedOn: Long? = null,
    val signedOn: Long? = null,
    val declinedOn: Long? = null,
    val acceptedTermsOn: Long? = null,
    /** A per-recipient note (the web's individual note mode). */
    val note: String = "",
    /** A template's role slot — carries no person yet. */
    val placeholderLabel: String = "",
) {
    val isSigner: Boolean get() = role == ROLE_SIGNER || role == "in_person_signer"
    val isCc: Boolean get() = role == ROLE_CC
    val signed: Boolean get() = status == "signed" || status == "completed"
    val declined: Boolean get() = status == "declined"

    /** Opened the document, signed it, or finished it — the web's "seen" set. */
    val seen: Boolean get() = status == "delivered" || signed || viewedOn != null

    /** Waiting on this person: out, and neither signed nor declined. */
    val outstanding: Boolean get() = !signed && !declined && status != "created"

    val isPlaceholder: Boolean get() = userId.isBlank() && email.isBlank()

    /** The web's recipient-status label map (`RST`). */
    val statusLabel: String
        get() = when (status) {
            "created" -> str(S.drive_created)
            "sent" -> str(S.txt_sent)
            "delivered" -> str(S.docusign_bulk_stage_viewed)
            "signed", "completed" -> str(S.completed)
            "declined" -> str(S.docusign_status_declined)
            else -> status
        }

    companion object {
        const val ROLE_SIGNER = "signer"
        const val ROLE_CC = "cc"

        /** CCs sit after every signer; the web's `CC_ROUTING_ORDER`. */
        const val CC_ROUTING_ORDER = 99
    }
}

/**
 * The field vocabulary — the web's `FIELD_TYPES` table, with each type's
 * default box in PDF points, whether the toolbar offers it, and whether it
 * carries options or a default value.
 */
@Suppress("MagicNumber") // The web's `FIELD_TYPES` box sizes, in PDF points.
enum class FieldType(
    val wire: String,
    private val labelKey: String,
    val defaultWidth: Double,
    val defaultHeight: Double,
    /** Offered on the placement toolbar. `dateSigned` is stamped by the service. */
    val onToolbar: Boolean = true,
    /** Radio and dropdown carry `options[]`. */
    val hasOptions: Boolean = false,
    /** Can be pre-filled by the sender. */
    val supportsDefault: Boolean = false,
) {
    SignHere("signHere", S.txt_signature, 180.0, 36.0),
    InitialHere("initialHere", S.docusign_place_field_initial, 120.0, 36.0),
    Checkbox("checkbox", S.desktop_ds_checkbox, 180.0, 28.0, supportsDefault = true),
    Radio("radioGroup", S.docusign_field_radio, 180.0, 56.0, hasOptions = true, supportsDefault = true),
    Dropdown("dropdown", S.select, 160.0, 32.0, hasOptions = true, supportsDefault = true),
    Text("text", S.docusign_field_text, 200.0, 32.0),
    Date("date", S.date, 130.0, 32.0),
    DateSigned("dateSigned", S.docusign_signing_date_signed, 120.0, 30.0, onToolbar = false),
    FullName("fullName", S.name, 180.0, 32.0),
    Email("email", S.email, 180.0, 32.0),
    Phone("phone", S.docusign_field_phone, 150.0, 32.0),
    Number("number", S.desktop_ds_numeric_only, 100.0, 32.0),
    Url("url", S.docusign_field_url, 180.0, 32.0),
    Image("image", S.docusign_field_image, 100.0, 100.0),
    Attachment("attachment", S.docusign_field_attachment, 150.0, 56.0),
    Other("", S.desktop_ds_field, 160.0, 32.0, onToolbar = false),
    ;

    val label: String get() = str(labelKey)

    /** Signature and initials — answered with a stored mark. */
    val isMark: Boolean get() = this == SignHere || this == InitialHere

    /** Answered with an uploaded file rather than a mark. */
    val isUpload: Boolean get() = this == Image || this == Attachment

    /** Answered with text the signer types. */
    val isTyped: Boolean get() =
        this == Text || this == FullName || this == Email || this == Phone || this == Number || this == Url

    /** Answered by picking one of the field's options. */
    val isChoice: Boolean get() = hasOptions

    /** Stamped by the service at sign time; never asked of the signer. */
    val isAutoStamped: Boolean get() = this == DateSigned

    /** The property panel offers a Label input — signatures and initials speak for themselves. */
    val hasLabel: Boolean get() = !isMark && this != DateSigned

    companion object {
        const val DEFAULT_WIDTH = 160.0
        const val DEFAULT_HEIGHT = 48.0

        fun fromWire(raw: String?): FieldType =
            entries.firstOrNull { it.wire == raw && it != Other } ?: Other

        /** The toolbar's order, as the web lists it. */
        val toolbar: List<FieldType> get() = entries.filter { it.onToolbar }
    }
}

/** One choice on a radio or dropdown field. */
data class FieldOption(val id: String, val label: String)

/** What a tab holds once it is filled — text, a stored file, or nothing yet. */
sealed interface FieldValue {
    data object None : FieldValue
    data class Text(val text: String) : FieldValue
    data class File(val file: StoredFile) : FieldValue

    val isSet: Boolean get() = this !is None
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
    val width: Double = type.defaultWidth,
    val height: Double = type.defaultHeight,
    /** The owning recipient's id, 24-hex — present on read. */
    val recipientId: String = "",
    /** The owner's position in the recipients array — what compose sends, and what reads resolve. */
    val recipientIndex: Int = 0,
    val label: String = "",
    val defaultValue: String = "",
    /**
     * Whether the signer must fill this before the envelope can be completed.
     *
     * **Defaults to true, as the wire does** — every tab the backend ships
     * carries `required` and Android declares it `= true`. A field is
     * mandatory unless it says otherwise.
     */
    val required: Boolean = true,
    /** Pre-filled and read-only to the signer. Needs a default value. */
    val locked: Boolean = false,
    val documentIndex: Int = 0,
    /** Filled value after signing, when the server echoes one. */
    val value: FieldValue = FieldValue.None,
    /** How typed text is drawn — the phones' font/bold/italic/underline/colour tab keys. */
    val style: FieldStyle = FieldStyle(),
    /** A radio or dropdown's choices, in order. */
    val options: List<FieldOption> = emptyList(),
    /** Generated by "initials on every page" — collapsible to one pad at sign time. */
    val autoInitial: Boolean = false,
) {
    /** A signer's answer is settled before they arrive: locked, or optional and untouched. */
    val prefilledText: String get() = defaultValue
}

/** The envelope-level options the editor sets; round-tripped through `settings`. */
data class EnvelopeSettings(
    val emailSubject: String = "",
    val emailBody: String = "",
    val initialsOnAllPages: Boolean = false,
    /** Days between reminders; null leaves the server's default. */
    val reminderCadenceDays: Int? = null,
    val expirationDays: Int? = null,
    /** Signers are notified one at a time, in routing order. */
    val signingOrderEnabled: Boolean = false,
    /** `manual` or `fastPlace` — how the placer was driven; carried into templates. */
    val placementMode: String = "manual",
)

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
    val settings: EnvelopeSettings = EnvelopeSettings(),
    val createdBy: String = "",
    val created: Long? = null,
    val updated: Long? = null,
    val sentOn: Long? = null,
    val completedOn: Long? = null,
    val expiresOn: Long? = null,
    /** Set on envelopes a bulk send produced; hidden from the Sent list. */
    val bulkJobId: String = "",
    val templateId: String = "",
) {
    fun recipientFor(userId: String, email: String = ""): EnvelopeRecipient? =
        recipients.firstOrNull {
            it.isSigner && (it.userId == userId || (email.isNotBlank() && it.email.equals(email, true)))
        }

    fun fieldsFor(recipient: EnvelopeRecipient): List<EnvelopeField> {
        val index = recipients.indexOf(recipient)
        return fields.filter { field ->
            (field.recipientId.isNotBlank() && field.recipientId == recipient.id) ||
                (field.recipientId.isBlank() && field.recipientIndex == index)
        }
    }

    val signers: List<EnvelopeRecipient> get() = recipients.filter { it.isSigner }
    val ccs: List<EnvelopeRecipient> get() = recipients.filter { it.isCc }
    val signedCount: Int get() = signers.count { it.signed }
    val seenCount: Int get() = signers.count { it.seen }

    val signerSummary: String
        get() = if (signers.isEmpty()) {
            str(S.desktop_ds_no_signers)
        } else {
            str(S.desktop_ds_n_of_m_signed, signedCount, signers.size)
        }

    /** The moment the list sorts and labels by — the web's `lastActivityTs`. */
    val lastActivity: Long? get() = updated ?: completedOn ?: sentOn ?: created

    /** A bulk send's child — the Sent list hides these behind a banner. */
    val fromBulkSend: Boolean get() = bulkJobId.isNotBlank()
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
    val sizeBytes: Long = 0,
) {
    val isPdf: Boolean get() =
        name.endsWith(".pdf", ignoreCase = true) || contentSubtype.equals("pdf", ignoreCase = true)
}

/** A saved e-sign mark. Its own store, separate from the documents tool's. */
data class SavedSignature(
    val id: String,
    /** Wire `kind`: `signature` or `initial`. */
    val isSignature: Boolean = true,
    val image: StoredFile? = null,
    val created: Long? = null,
)

/** One line of the audit trail, parsed tolerantly. */
data class AuditEntry(
    val action: String = "",
    val actorName: String = "",
    val actorEmail: String = "",
    val happenedOn: Long? = null,
    val details: String = "",
    val ipAddress: String = "",
) {
    /** `recipient_signed` → "Recipient signed". */
    val actionLabel: String
        get() = action.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** The two list scopes, with the web's bucket vocabulary per scope. */
enum class EnvelopeScope(val wire: String) { Sent("sent"), Received("received") }

/** What the signer fills, keyed by field id. */
sealed interface FieldAnswer {
    /** A stored image's descriptor — a mark for signHere/initialHere, an upload for image/attachment. */
    data class Mark(val image: StoredFile) : FieldAnswer
    data class Typed(val text: String) : FieldAnswer
    data class Ticked(val checked: Boolean) : FieldAnswer
    /** The chosen option's id, for radio and dropdown. */
    data class Chosen(val optionId: String) : FieldAnswer
}

/** A reusable envelope design — documents, role slots, fields, email copy. */
data class EnvelopeTemplate(
    val id: String,
    val name: String = "",
    val description: String = "",
    val category: String = "",
    val documents: List<StoredFile> = emptyList(),
    /** Role slots, not people: `role`, `routing_order`, `placeholder_label`. */
    val recipients: List<EnvelopeRecipient> = emptyList(),
    val fields: List<EnvelopeField> = emptyList(),
    val settings: EnvelopeSettings = EnvelopeSettings(),
    val created: Long? = null,
    val updated: Long? = null,
    val createdBy: String = "",
) {
    val document: StoredFile? get() = documents.firstOrNull()
    val signerSlots: Int get() = recipients.count { it.isSigner }
    val pageCount: Int get() = document?.pageCount ?: 0
}

/** One CSV-driven send: a template, N rows, N envelopes. */
data class BulkJob(
    val id: String,
    val name: String = "",
    val templateId: String = "",
    val templateName: String = "",
    /** `pending`, `running`, `completed`, `failed`, `cancelled`. */
    val status: String = "",
    val totalRows: Int = 0,
    val processed: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val created: Long? = null,
    val updated: Long? = null,
    val rows: List<BulkJobRow> = emptyList(),
) {
    val isRunning: Boolean get() = status == "running" || status == "pending"
    val isTerminal: Boolean get() = !isRunning && status.isNotBlank()
    val progressFraction: Float get() = if (totalRows == 0) 0f else processed.toFloat() / totalRows
    val displayName: String get() = name.ifBlank { templateName.ifBlank { str(S.docusign_bulk_send_title) } }
}

/** One CSV row's outcome inside a bulk job. */
data class BulkJobRow(
    val rowIndex: Int = 0,
    /** `pending`, `sent`, `failed`, `skipped`, or the envelope's own status once known. */
    val status: String = "",
    val envelopeId: String = "",
    val error: String = "",
    val name: String = "",
    val email: String = "",
    val envelopeStatus: String = "",
    val signedOn: Long? = null,
    val viewedOn: Long? = null,
    val declinedOn: Long? = null,
    val lastRemindedOn: Long? = null,
) {
    val failed: Boolean get() = status == "failed"

    /** Furthest stage reached — the web's `deriveStage`, timestamps first. */
    val stage: String
        get() = when {
            declinedOn != null || envelopeStatus == "declined" -> "declined"
            signedOn != null || envelopeStatus == "completed" || envelopeStatus == "signed" -> "signed"
            viewedOn != null || envelopeStatus == "delivered" -> "viewed"
            failed -> "failed"
            status == "pending" -> "pending"
            else -> "sent"
        }
}

/** Page rendering and stroke rasterising, injected from the host. */
interface EsignPdf {
    fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<EsignPage>>
    fun rasterizeStrokes(
        strokes: List<List<Pair<Float, Float>>>,
        width: Int,
        height: Int,
    ): ZillitResult<ByteArray>

    /**
     * A name set in a script face, as a transparent PNG — the web's "Type"
     * signature style. [fontKey] is one of [SignatureFont]'s keys; the host
     * picks the nearest installed face.
     */
    fun rasterizeText(text: String, fontKey: String, width: Int, height: Int): ZillitResult<ByteArray>
}

/** The typed-signature styles the web offers; the host maps each key to a face it has. */
enum class SignatureFont(val key: String, private val labelKey: String) {
    Formal("formal", S.docusign_sig_style_formal),
    Flowing("flowing", S.docusign_sig_style_flowing),
    Casual("casual", S.docusign_sig_style_casual),
    Slim("slim", S.docusign_sig_style_slim),
    Bold("bold", S.bold),
    Natural("natural", S.docusign_sig_style_natural),
    ;

    val label: String get() = str(labelKey)
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

    /** Writes a finished file where the user keeps downloads and opens it. */
    suspend fun land(fileName: String, bytes: ByteArray): ZillitResult<Unit>
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
