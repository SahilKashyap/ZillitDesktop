package com.zillit.desktop.feature.email.data

import com.zillit.desktop.feature.email.domain.ContactSource
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.toSnippet
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Turning mail JSON into domain types.
 *
 * Tolerant on purpose, and more so than the rest of the app: mail headers are
 * written by every client ever shipped, so `to` arrives as an array here and a
 * comma-joined string there, and `has_attachments` is the string `"false"`
 * rather than a boolean. One eccentric message must not blank an inbox.
 *
 * Top-level and `internal` so tests exercise these readers rather than a copy.
 */
internal fun readFolder(row: JsonElement): EmailFolder? {
    if (row !is JsonObject) return null
    val name = row.str("folder_name") ?: return null

    // `deleted` is a flag rather than a removal: the server keeps tombstones so
    // other devices can drop the folder too. Showing them would resurrect
    // folders the user deleted somewhere else.
    if (row.int("deleted") != 0) return null

    return EmailFolder(
        name = name,
        isSystem = row.bool("system_defined"),
        unreadCount = row.int("unread"),
    )
}

/** `get-folder-uids` returns `[{uid: 12}, …]`, occasionally bare numbers. */
internal fun readUid(row: JsonElement): Int? = when (row) {
    is JsonObject -> row.int("uid").takeIf { it > 0 }
    is JsonPrimitive -> (row.longOrNull ?: row.contentOrNull?.toLongOrNull())?.toInt()?.takeIf { it > 0 }
    else -> null
}

internal fun readSummary(row: JsonElement): EmailSummary? {
    if (row !is JsonObject) return null
    val id = row.str("id") ?: row.str("_id") ?: row.str("message_id") ?: return null

    val body = row.str("text") ?: row.str("body").orEmpty()

    return EmailSummary(
        id = id,
        // Threading falls back to the message itself: a mail with no thread is a
        // thread of one, and a blank id would collapse them all together.
        threadId = row.str("thread_id") ?: row.str("trail_reference_id") ?: id,
        subject = row.str("subject") ?: "(no subject)",
        from = row.str("from").orEmpty(),
        to = row.addresses("to"),
        snippet = body.toSnippet(),
        receivedAtMillis = row.millis("created_at"),
        isRead = row.bool("read"),
        hasAttachments = row.bool("has_attachments"),
        attachmentCount = row.int("attachment_count"),
        uid = row.int("uid"),
        folderName = row.str("folder_name").orEmpty(),
    )
}

internal fun readMessage(row: JsonElement): EmailMessage? {
    if (row !is JsonObject) return null
    val id = row.str("id") ?: row.str("_id") ?: row.str("message_id") ?: return null

    val raw = row.str("body") ?: row.str("text").orEmpty()

    return EmailMessage(
        id = id,
        threadId = row.str("thread_id") ?: row.str("trail_reference_id") ?: id,
        subject = row.str("subject") ?: "(no subject)",
        from = row.str("from").orEmpty(),
        to = row.addresses("to"),
        cc = row.addresses("cc"),
        body = raw,
        isHtml = row.bool("html_email") || raw.looksLikeHtml(),
        receivedAtMillis = row.millis("created_at"),
        // `ingrained_attachment` holds inline images — part of the body, not
        // things to list as files the reader can download.
        attachments = (row["attachments"] as? JsonArray).orEmpty().mapNotNull(::readAttachment),
    )
}

/**
 * A saved draft.
 *
 * `updated_at` rather than `created_at` for ordering: a draft edited this
 * morning belongs above one started last week, which is what a Drafts list is
 * for. Falls back to `created_at` for drafts never edited since.
 */
internal fun readDraft(row: JsonElement): EmailDraft? {
    if (row !is JsonObject) return null
    val id = row.str("_id") ?: row.str("id") ?: return null

    return EmailDraft(
        id = id,
        to = row.addresses("to"),
        cc = row.addresses("cc"),
        bcc = row.addresses("bcc"),
        subject = row.str("subject").orEmpty(),
        body = row.str("body") ?: row.str("text").orEmpty(),
        updatedAtMillis = row.millis("updated_at").takeIf { it > 0 } ?: row.millis("created_at"),
    )
}

/**
 * A saved contact.
 *
 * The name is assembled from whichever fields the row actually has: some rows
 * carry `contact_name`, older ones only first and last. A contact with an
 * address and no name is still useful, so a blank name is not a reason to drop
 * the row — a blank address is.
 */
internal fun readContact(row: JsonElement): EmailContact? {
    if (row !is JsonObject) return null
    val address = row.str("email_address") ?: row.str("email") ?: return null

    val name = row.str("contact_name")
        ?: listOfNotNull(row.str("first_name"), row.str("last_name"))
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    return EmailContact(
        address = address,
        name = name.orEmpty(),
        source = ContactSource.Saved,
        subtitle = row.str("company_name").orEmpty(),
    )
}

/**
 * A saved signature.
 *
 * `deleted` is a tombstone timestamp rather than a flag — the server keeps
 * removed signatures so other devices drop them too, and showing one would
 * resurrect something the user deleted elsewhere.
 */
internal fun readSignature(row: JsonElement): EmailSignature? {
    if (row !is JsonObject) return null
    val id = row.str("_id") ?: row.str("id") ?: return null
    if (row.millis("deleted") > 0) return null

    return EmailSignature(
        id = id,
        title = row.str("signature_title") ?: "Untitled",
        body = row.str("signature_body").orEmpty(),
        useForNew = row.bool("use_for_new_email"),
        useForReply = row.bool("use_for_reply_and_forward"),
    )
}

private fun readAttachment(row: JsonElement): EmailAttachment? {
    if (row !is JsonObject) return null
    val id = row.str("attachment_id") ?: row.str("id") ?: row.str("_id") ?: return null

    return EmailAttachment(
        id = id,
        // A nameless attachment is still downloadable; "file" beats an invisible
        // chip.
        fileName = row.str("name") ?: row.str("file_name") ?: "file",
        contentType = row.str("content_type"),
        sizeBytes = row.millis("content_length").takeIf { it > 0 } ?: row.millis("size"),
    )
}

/**
 * Some messages carry markup with `html_email` unset.
 *
 * Rendering those raw shows the tags to the reader, which is worse than
 * over-converting: the converter leaves plain text untouched anyway.
 */
private fun String.looksLikeHtml(): Boolean = HTML_HINT.containsMatchIn(this)

private val HTML_HINT = Regex(
    // No `\b` here: in a Kotlin string literal that is the backspace character,
    // not a word boundary, so the pattern silently matches nothing. An optional
    // attribute group does the same job.
    "<(p|br|div|table|a|span|b|i|strong|em)( [^>]*)?/?>",
    RegexOption.IGNORE_CASE,
)

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun JsonObject.prim(key: String) = this[key] as? JsonPrimitive

internal fun JsonObject.str(key: String): String? =
    prim(key)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/**
 * `has_attachments` ships as the *string* `"false"`, which is truthy in most
 * languages and was truthy here until this function stopped guessing.
 */
internal fun JsonObject.bool(key: String): Boolean =
    prim(key)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

internal fun JsonObject.int(key: String): Int =
    prim(key)?.let { it.longOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() } ?: 0

internal fun JsonObject.millis(key: String): Long =
    prim(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0

/** Recipients arrive as an array or, on older mail, a comma-separated string. */
internal fun JsonObject.addresses(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { element ->
        when (element) {
            // Send and draft payloads wrap each address in an object; the same
            // shape comes back on some rows.
            is JsonObject -> element.str("email_address") ?: element.str("address")
            is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)
            else -> null
        }
    }
    is JsonPrimitive -> value.contentOrNull.orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
    else -> emptyList()
}
