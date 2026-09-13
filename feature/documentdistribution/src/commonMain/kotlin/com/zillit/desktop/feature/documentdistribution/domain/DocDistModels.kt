package com.zillit.desktop.feature.documentdistribution.domain

/**
 * What a library document *is*, for icon and preview purposes.
 *
 * The server sends `media_type` alongside `content_type`, and the web trusts
 * `media_type` first and falls back to sniffing the MIME string
 * (`Library.jsx: fileIconFor`). Modelled as an enum with an [Other] fallback so
 * a media type shipped after this build renders as a plain file rather than
 * crashing the listing — the rule the whole repo follows for wire enums.
 */
enum class MediaKind(val wire: String) {
    Image("image"),
    Video("video"),
    Audio("audio"),
    Pdf("pdf"),
    Document("document"),
    Other("other"),
    ;

    companion object {
        /**
         * Resolves the kind from the two fields the server actually sends.
         *
         * PDF is not a `media_type` the backend issues — it arrives as
         * `document` with `application/pdf` — but it is the single most common
         * thing distributed on a production, and it gets its own icon on every
         * other client. So the MIME string is consulted even when `media_type`
         * already answered.
         */
        fun of(mediaType: String?, contentType: String?, fileName: String? = null): MediaKind {
            val mime = contentType.orEmpty().lowercase()
            if (mime.contains("pdf")) return Pdf
            entries.firstOrNull { it.wire == mediaType?.lowercase() }?.let { return it }
            return when {
                mime.startsWith("image/") -> Image
                mime.startsWith("video/") -> Video
                mime.startsWith("audio/") -> Audio
                fileName?.substringAfterLast('.', "")?.lowercase() == "pdf" -> Pdf
                mime.isNotEmpty() -> Document
                else -> Other
            }
        }
    }
}

/** A folder in the distribution library. Flat on the wire; nested by [parentId]. */
data class LibraryFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    /**
     * `YYYY-MM-DD` — the production date this folder is *for*, not when it was
     * made. Set by hand at creation, and blank on folders that never got one.
     */
    val folderDate: String = "",
    /** "What this folder contains" — free text, shown beside the name. */
    val description: String = "",
)

/**
 * One catalogued file.
 *
 * [documentDate] is the production date the document belongs to — the day a
 * call sheet is *for*, not the day it was uploaded — and it is what the library
 * groups by. It is separate from [createdAt] on purpose: re-issuing yesterday's
 * call sheet this morning must still file under yesterday.
 */
data class LibraryDocument(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val contentType: String? = null,
    val mediaKind: MediaKind = MediaKind.Other,
    val sizeBytes: Long = 0,
    /** `YYYY-MM-DD`, or blank when the server filed it undated. */
    val documentDate: String = "",
    val createdAt: Long? = null,
    /**
     * Where the bytes live, when the production stores in S3.
     *
     * Null on a LOCAL production, whose bytes are proxied by the server
     * instead. Both phones decide the same way — storage is a property of the
     * *document*, not the project: `attachment != null` means S3.
     */
    val storage: DocumentStorage? = null,
    /**
     * A one-shot composer upload rather than a catalogued document.
     *
     * Sent as `ephemeral_attachment_ids` instead of `attachment_ids`, and
     * deleted from the server when removed from the composer — unless
     * [reused], which marks a row borrowed from a past send whose bytes still
     * belong to that send's history (ZL-19495).
     */
    val isEphemeral: Boolean = false,
    val reused: Boolean = false,
) {
    /**
     * Whether the server's stamping pipeline can watermark this.
     *
     * The server rule, not a media-kind guess: PDF plus the bitmap formats
     * `sharp` handles (JPEG, PNG, WEBP). A GIF is an image but cannot be
     * stamped, and offering the toggle on it produces a send-time refusal.
     */
    val isWatermarkable: Boolean get() = canWatermark(contentType, name)
}

/**
 * The server's `isWatermarkable`, mirrored: PDF, JPG, PNG, WEBP. Falls back
 * to the extension when the record carries no MIME (a duplicated attachment).
 */
fun canWatermark(contentType: String?, fileName: String? = null): Boolean {
    val mime = contentType.orEmpty().lowercase().substringBefore(';').trim()
    if (mime.isNotEmpty()) return mime in WATERMARKABLE_MIMES
    val ext = fileName.orEmpty().substringAfterLast('.', "").lowercase()
    return ext in WATERMARKABLE_EXTENSIONS
}

private val WATERMARKABLE_MIMES = setOf("application/pdf", "image/jpeg", "image/jpg", "image/png", "image/webp")
private val WATERMARKABLE_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png", "webp")

const val WATERMARK_SUPPORTED_LABEL = "PDF, JPG, PNG, WEBP"

/** A file read off this machine — picked in a dialog or dropped on the window. */
class LocalFile(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    /** Never prints the bytes. */
    override fun toString(): String = "LocalFile(name=$name, type=$contentType, size=${bytes.size})"
}

/**
 * An S3 object, as the listing names it.
 *
 * Enough to presign a GET and no more: the desktop cannot open the server's
 * `/raw` proxy (that route needs the app's encrypted headers, which a browser
 * does not send), so an S3 document is reached by a presigned URL instead.
 */
data class DocumentStorage(
    val key: String,
    val bucket: String,
    val region: String,
)

/** Someone a distribution can go to. */
data class Recipient(
    val email: String,
    val name: String = "",
    val jobTitle: String = "",
) {
    /** "Ada Lovelace <ada@example.com>", or the bare address when unnamed. */
    val display: String get() = if (name.isBlank()) email else "$name <$email>"
}

/**
 * A saved set of recipients — the web calls these "presets", the UI calls them
 * distribution lists, and the endpoints say `/presets`. Named for the UI here,
 * with the wire word confined to the data layer.
 */
data class DistributionList(
    val id: String,
    val name: String,
    val recipients: List<Recipient> = emptyList(),
    val description: String = "",
    /** `updated` on the wire — the "Last updated on" column. */
    val updatedAt: Long? = null,
)

/** A distribution list a contact belongs to, as the address book names it. */
data class ContactListRef(val id: String, val name: String)

/** The implicit address book: everyone ever sent to on this production. */
data class Contact(
    val email: String,
    val name: String = "",
    val jobTitle: String = "",
    /** The lists this address is on — the server joins them for the address book. */
    val lists: List<ContactListRef> = emptyList(),
    /** How many sends and lists have used this address. */
    val usageCount: Int = 0,
) {
    val displayName: String get() = name.ifBlank { email }
}

/** A reusable subject + body. Body is HTML, as the composer's editor produces. */
data class EmailTemplate(
    val id: String,
    val name: String,
    val subject: String = "",
    val bodyHtml: String = "",
    val description: String = "",
)

/**
 * Whether one recipient has opened their copy.
 *
 * Open tracking is a pixel, so "not opened" genuinely means "no evidence" —
 * a recipient reading with images off never registers. [Unknown] is therefore
 * a distinct state from [NotOpened] rather than a synonym for it, because
 * telling a coordinator "they haven't read it" on the strength of a blocked
 * image is worse than saying nothing.
 */
enum class OpenState {
    Opened,
    NotOpened,
    Unknown,
}

/**
 * The mail service's word on one copy — the web's `STATUS_META` vocabulary.
 *
 * [Opened] is a subset of delivered: an opened copy was accepted first, which
 * is why the History legend counts it under "Delivered" too.
 */
enum class RecipientStatus(val wire: String, val label: String) {
    Pending("pending", "Sending"),
    Accepted("accepted", "Delivered"),
    Opened("opened", "Opened"),
    Rejected("rejected", "Rejected"),
    Bounced("bounced", "Bounced"),
    Failed("failed", "Failed"),
    ;

    val isFailure: Boolean get() = this == Rejected || this == Bounced || this == Failed

    companion object {
        fun from(wire: String?): RecipientStatus =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Pending
    }
}

/** Which address line a recipient was on. */
enum class RecipientKind(val label: String) { To("To"), Cc("Cc"), Bcc("Bcc") }

/** One row of a sent distribution's per-recipient status. */
data class DeliveryStatus(
    val recipient: Recipient,
    /** The email service's handle for this copy — what open-status is asked by. */
    val uniqueId: String? = null,
    val state: OpenState = OpenState.Unknown,
    val openedAt: Long? = null,
    val openCount: Int = 0,
    val status: RecipientStatus = RecipientStatus.Pending,
    val kind: RecipientKind = RecipientKind.To,
)

/** Whether the mail service accepted the send as a whole. */
enum class SendStatus(val wire: String, val label: String) {
    Sent("sent", "Sent"),
    Queued("queued", "Queued"),
    Failed("failed", "Failed"),
    Unknown("", ""),
    ;

    companion object {
        fun from(wire: String?): SendStatus =
            entries.firstOrNull { it.wire.isNotEmpty() && it.wire == wire?.lowercase() } ?: Unknown
    }
}

/** An attachment as a past send recorded it — enough to list, and to re-hydrate on Duplicate. */
data class SentAttachment(
    val documentId: String,
    val name: String,
    val sizeBytes: Long = 0,
    val contentType: String? = null,
    /** `ephemeral` for a device upload; anything else is a library document. */
    val source: String = "",
    val watermarked: Boolean = false,
) {
    val isEphemeral: Boolean get() = source.equals("ephemeral", ignoreCase = true)
}

/**
 * A list that fed a send, snapshotted at send time (ZL-20299) — the name
 * survives the list being renamed or deleted, and [emails] says which
 * recipients it contributed so History can tag them.
 */
data class ListUsed(val id: String, val name: String, val emails: List<String> = emptyList())

/** A send, as it appears in History. */
data class Distribution(
    val id: String,
    val subject: String,
    val sentAt: Long? = null,
    val sentByName: String = "",
    /** Who sent it, as the server keys senders — the History "Sent by" filter matches on this, not the name. */
    val senderId: String = "",
    /** Every copy, To then Cc then Bcc — see [DeliveryStatus.kind]. */
    val recipients: List<DeliveryStatus> = emptyList(),
    val attachments: List<SentAttachment> = emptyList(),
    val listsUsed: List<ListUsed> = emptyList(),
    val status: SendStatus = SendStatus.Unknown,
    val bodyHtml: String = "",
    /** The mail service's reason when the send failed, when it gave one. */
    val error: String? = null,
    /** The stamp the sender configured, restored on Duplicate. */
    val watermark: WatermarkStyle? = null,
) {
    val attachmentNames: List<String> get() = attachments.map { it.name }

    val openedCount: Int
        get() = recipients.count { it.status == RecipientStatus.Opened || it.state == OpenState.Opened }

    /** "3 of 12 opened" — the History row's headline figure. */
    val openSummary: String get() = "$openedCount of ${recipients.size} opened"

    /** Accepted plus opened — opening implies delivery. */
    val deliveredCount: Int
        get() = recipients.count { it.status == RecipientStatus.Accepted || it.status == RecipientStatus.Opened }

    val failedCount: Int get() = recipients.count { it.status.isFailure }

    fun recipientsOf(kind: RecipientKind): List<DeliveryStatus> = recipients.filter { it.kind == kind }

    /** The To / Cc / Bcc lists flattened and de-duplicated by address, for "save as list" and CSV. */
    val uniqueRecipients: List<Recipient>
        get() = recipients.map { it.recipient }.distinctBy { it.email.lowercase() }

    /** Which list added an address, by lowercased email. */
    val listNameByEmail: Map<String, String>
        get() = buildMap {
            listsUsed.forEach { used ->
                used.emails.forEach { email ->
                    val key = email.trim().lowercase()
                    if (key.isNotEmpty() && key !in this) put(key, used.name)
                }
            }
        }

    /** The status tally the History detail's donut is drawn from. */
    fun tally(): Map<RecipientStatus, Int> = recipients.groupingBy { it.status }.eachCount()
}

/** One page of History, with the server's full count so the header can say how many exist. */
data class HistoryPage(val rows: List<Distribution>, val total: Int)

/** A signature the mail service holds for this person, as the composer appends it. */
data class DocDistSignature(val id: String, val title: String, val bodyHtml: String, val useForNew: Boolean = false)

/**
 * A publishing target — where a document can be pushed *inside* the app rather
 * than emailed out (a call sheet onto the unit's chat, a production report onto
 * its tool).
 */
data class PublicationCategory(
    val identifier: String,
    val label: String,
    /**
     * Whether publishing again replaces rather than adds.
     *
     * `call_sheet_unit` and `production_report` are the two re-publishable
     * categories; for them the Publish dialog offers add / replace / resend and
     * needs [DocDistRepository.publishedFiles] first. Everything else is a
     * first publish with no options.
     */
    val republishable: Boolean = false,
)

/** Something already published under a category, so Publish can offer a replace. */
data class PublishedFile(
    val chatId: String,
    val name: String,
    val publishedAt: Long? = null,
)

/** One entry of the History "Sent by" filter: a sender the project's distributions were sent by. */
data class DistributionSender(val id: String, val name: String, val designation: String = "") {
    val initials: String get() = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }.ifBlank { "?" }
}
