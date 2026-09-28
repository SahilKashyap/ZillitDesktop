package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.WireAttachment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `wire_attachments` — an array, the same array JSON-encoded into a string,
 * or `{}` for none, all floored to a list (`parseWireAttachments`,
 * `PaymentsPage.jsx:695-702`, ZL-20536).
 *
 * The stored shape is reduced — `{stored_filename, filename, mime_type,
 * size}` — so each row is normalised the web's way (`normalizeWireAttachment`):
 * the key from `media || stored_filename`, the name from `name || filename`,
 * the category and extension from the MIME type or the file name.
 */
internal fun parseWireAttachments(obj: JsonObject): List<WireAttachment> =
    obj.arrayField("wire_attachments").mapNotNull { (it as? JsonObject)?.let(::parseWireAttachment) }

internal fun parseWireAttachment(row: JsonObject): WireAttachment? {
    val media = row.text("media", "stored_filename").ifBlank { return null }
    val name = row.text("name", "filename")
    val mime = row.text("mime_type").lowercase()
    val extension = name.substringAfterLast('.', "").lowercase()
    return WireAttachment(
        attachment = InvoiceAttachment(
            media = media,
            bucket = row.text("bucket"),
            region = row.text("region"),
            name = name.ifBlank { media },
            contentType = row.text("content_type", "contentType").ifBlank { mime.substringBefore('/', "") },
            contentSubtype = row.text("content_subtype", "contentSubtype")
                .ifBlank { extension }
                .ifBlank { mime.substringAfter('/', "") },
            caption = row.text("caption"),
        ),
        mimeType = mime,
        sizeBytes = row.number("file_size", "size")?.toLong(),
    )
}

/**
 * `POST /invoices/:id/wire-attachments` — `{attachment: {...AttachmentModel,
 * filename, stored_filename, mime_type, size}}`: the stored file plus the
 * metadata the service records (`PaymentsPage.jsx:725-734`, `invoices.js:252`).
 */
internal fun wireAttachmentBody(attachment: InvoiceAttachment, mimeType: String, size: Long): JsonObject =
    buildJsonObject {
        put(
            "attachment",
            buildJsonObject {
                attachmentWire(attachment).forEach { (key, value) -> put(key, value) }
                put("filename", JsonPrimitive(attachment.name))
                put("stored_filename", JsonPrimitive(attachment.media))
                put("mime_type", JsonPrimitive(mimeType))
                put("size", JsonPrimitive(size))
            },
        )
    }
