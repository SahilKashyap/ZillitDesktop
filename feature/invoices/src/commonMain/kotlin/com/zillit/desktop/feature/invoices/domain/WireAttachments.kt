package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlin.math.roundToLong

/**
 * A bank confirmation filed on a paid wire — one row of `wire_attachments`
 * (`PaymentsPage.jsx:704-867`).
 *
 * The service keeps these in a reduced shape — `{stored_filename, filename,
 * mime_type, size}` with no bucket or region — so [attachment] carries the
 * S3 key as `media` and leaves the storage location blank for the host to
 * fill from the project's region, as the web's `normalizeWireAttachment` does.
 */
data class WireAttachment(
    val attachment: InvoiceAttachment,
    /** `mime_type` as stored; blank for a full attachment model. */
    val mimeType: String = "",
    /** `file_size` / `size`, bytes; null when not recorded. */
    val sizeBytes: Long? = null,
) {
    /** What the remove route takes — `media || stored_filename`. */
    val key: String get() = attachment.media

    val name: String get() = attachment.name.ifBlank { attachment.media }

    /**
     * The row's sub-line: `content_type/content_subtype` when both are known,
     * else the MIME type, then ` · 12.3 KB` when a size was recorded.
     */
    val detail: String
        get() {
            val kind = if (attachment.contentType.isNotBlank() && attachment.contentSubtype.isNotBlank()) {
                "${attachment.contentType}/${attachment.contentSubtype}"
            } else {
                mimeType
            }
            val size = sizeBytes?.takeIf { it > 0 }?.let { str(S.desktop_size_kb, kilobytes(it)) }
            return listOfNotNull(kind.takeIf { it.isNotBlank() }, size).joinToString(" · ")
        }

    private companion object {
        const val KB = 1024.0
        const val TENTHS = 10.0

        /** One decimal place, as the web's `(size / 1024).toFixed(1)`. */
        fun kilobytes(bytes: Long): String {
            val tenths = (bytes / KB * TENTHS).roundToLong()
            return "${tenths / TENTHS.toLong()}.${tenths % TENTHS.toLong()}"
        }
    }
}

/** A wire's confirmations as the server answered an upload or a removal, and what it said. */
data class WireAttachmentsChange(val attachments: List<WireAttachment>, val message: String? = null)
