package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.feature.chat.domain.ChatExport
import com.zillit.desktop.feature.chat.domain.ChatExportDownload
import com.zillit.desktop.feature.chat.domain.ChatExportStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `/v2/chat-export` as the contract spells it.
 *
 * Read tolerantly, like the rest of this module's wire layer: ids arrive as
 * `export_id` or `_id`, numbers as numbers or as strings, and a flag this
 * backend sends is sometimes the string `"true"` — a `Boolean`-typed field
 * would fail the **whole** response to decode, which is how one changed
 * record empties a screen.
 */
@Serializable
internal data class ChatExportDto(
    @SerialName("export_id") val exportId: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("id") val otherId: String? = null,
    val status: String? = null,
    @SerialName("include_media") val includeMedia: JsonPrimitive? = null,
    @SerialName("total_conversations") val totalConversations: JsonPrimitive? = null,
    @SerialName("exported_conversations") val exportedConversations: JsonPrimitive? = null,
    @SerialName("exported_messages") val exportedMessages: JsonPrimitive? = null,
    @SerialName("exported_media") val exportedMedia: JsonPrimitive? = null,
    @SerialName("skipped_media") val skippedMedia: JsonPrimitive? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("size_bytes") val sizeBytes: JsonPrimitive? = null,
    val error: String? = null,
    @SerialName("requested_on") val requestedOn: JsonPrimitive? = null,
    @SerialName("completed_on") val completedOn: JsonPrimitive? = null,
    @SerialName("expires_on") val expiresOn: JsonPrimitive? = null,
) {
    fun toDomain(): ChatExport? {
        val key = listOf(exportId, altId, otherId).firstOrNull { !it.isNullOrBlank() } ?: return null
        return ChatExport(
            exportId = key,
            status = ChatExportStatus.fromWire(status),
            // Absent means true: the contract's own default, and the one an
            // export made by another client most likely ran with.
            includeMedia = includeMedia.flag(default = true),
            totalConversations = totalConversations.int(),
            exportedConversations = exportedConversations.int(),
            exportedMessages = exportedMessages.int(),
            exportedMedia = exportedMedia.int(),
            skippedMedia = skippedMedia.int(),
            fileName = fileName.orEmpty(),
            sizeBytes = sizeBytes.long(),
            error = error.orEmpty(),
            requestedOnMillis = requestedOn.long(),
            completedOnMillis = completedOn.long(),
            expiresOnMillis = expiresOn.long(),
        )
    }
}

/**
 * The download answer: the link and what it fetches.
 *
 * Its own shape rather than the export's — the record carries no `status`,
 * and the one field that matters is the `url` the export object never has.
 */
@Serializable
internal data class ChatExportDownloadDto(
    @SerialName("export_id") val exportId: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("size_bytes") val sizeBytes: JsonPrimitive? = null,
    @SerialName("expires_in") val expiresIn: JsonPrimitive? = null,
    val url: String? = null,
) {
    /** Null when there is no link: a Download that opens nothing is worse than a refusal. */
    fun toDomain(fallbackId: String): ChatExportDownload? {
        val link = url?.takeIf { it.isNotBlank() } ?: return null
        return ChatExportDownload(
            exportId = exportId?.takeIf { it.isNotBlank() } ?: fallbackId,
            fileName = fileName.orEmpty(),
            sizeBytes = sizeBytes.long(),
            expiresInSeconds = expiresIn.long(),
            url = link,
        )
    }
}

/**
 * The export object in a single-record answer: under `export`, under `data`,
 * or the thing itself.
 *
 * A JSON *string* is unwrapped too — the socket sends `data` that way often
 * enough that every reader in this module tolerates it.
 */
internal fun JsonElement?.chatExportRecord(): JsonObject? = when (this) {
    is JsonObject -> (this["export"] as? JsonObject) ?: this["data"]?.chatExportRecord() ?: this
    is JsonArray -> firstOrNull()?.chatExportRecord()
    // JsonNull is a JsonPrimitive, so this covers every remaining case.
    is JsonPrimitive ->
        if (!isString) null
        else runCatching { Json.parseToJsonElement(content) }.getOrNull()?.chatExportRecord()
    else -> null
}

/** The rows of the list answer: bare, under `exports`, or one level deeper under `data`. */
internal fun JsonElement?.chatExportRows(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> this["exports"]?.chatExportRows() ?: this["data"]?.chatExportRows() ?: emptyList()
    else -> emptyList()
}

/**
 * A flag this backend may send as a boolean *or* as the string `"true"`.
 *
 * `booleanOrNull` reads a quoted `"true"` as true as well — wrong for a
 * strictly-typed flag, exactly right here, where both spellings mean the
 * same thing.
 */
private fun JsonPrimitive?.flag(default: Boolean): Boolean = this?.booleanOrNull ?: default

private fun JsonPrimitive?.int(): Int = this?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0

private fun JsonPrimitive?.long(): Long = this?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L
