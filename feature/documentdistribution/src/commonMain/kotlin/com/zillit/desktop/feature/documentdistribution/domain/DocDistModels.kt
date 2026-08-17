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
    /** Object key, when the production stores in S3. Absent on LOCAL projects. */
    val storageKey: String? = null,
) {
    /** Whether the watermark pipeline can stamp this — PDFs and raster images. */
    val isWatermarkable: Boolean
        get() = mediaKind == MediaKind.Pdf || mediaKind == MediaKind.Image
}

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
)

/** The implicit address book: everyone ever sent to on this production. */
data class Contact(
    val email: String,
    val name: String = "",
    val jobTitle: String = "",
)

/** A reusable subject + body. Body is HTML, as the composer's editor produces. */
data class EmailTemplate(
    val id: String,
    val name: String,
    val subject: String = "",
    val bodyHtml: String = "",
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

/** One row of a sent distribution's per-recipient status. */
data class DeliveryStatus(
    val recipient: Recipient,
    /** The email service's handle for this copy — what open-status is asked by. */
    val uniqueId: String? = null,
    val state: OpenState = OpenState.Unknown,
    val openedAt: Long? = null,
    val openCount: Int = 0,
)

/** A send, as it appears in History. */
data class Distribution(
    val id: String,
    val subject: String,
    val sentAt: Long? = null,
    val sentByName: String = "",
    val recipients: List<DeliveryStatus> = emptyList(),
    val attachmentNames: List<String> = emptyList(),
    /** Names of the lists that fed this send, for the History row's subtitle. */
    val listsUsed: List<String> = emptyList(),
) {
    val openedCount: Int get() = recipients.count { it.state == OpenState.Opened }

    /** "3 of 12 opened" — the History row's headline figure. */
    val openSummary: String get() = "$openedCount of ${recipients.size} opened"
}

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
