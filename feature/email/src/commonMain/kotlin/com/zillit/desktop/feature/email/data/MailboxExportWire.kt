package com.zillit.desktop.feature.email.data

import com.zillit.desktop.feature.email.domain.MailboxExport
import com.zillit.desktop.feature.email.domain.MailboxExportDownload
import com.zillit.desktop.feature.email.domain.MailboxExportPart
import com.zillit.desktop.feature.email.domain.MailboxExportStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `/v2/email-export` as the contract spells it.
 *
 * Read tolerantly, like the rest of this module's wire layer: ids arrive as
 * `export_id` or `_id`, numbers as numbers or strings, and the booleans this
 * backend sends are sometimes the strings `"true"` / `"false"` — a
 * `Boolean`-typed field would fail the **whole** response to decode, which is
 * how one changed record empties a screen.
 */
@Serializable
internal data class MailboxExportDto(
    @SerialName("export_id") val exportId: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("id") val otherId: String? = null,
    val status: String? = null,
    val format: String? = null,
    @SerialName("mailbox_email") val mailboxEmail: String? = null,
    @SerialName("is_accounts_mailbox") val isAccountsMailbox: JsonPrimitive? = null,
    @SerialName("total_folders") val totalFolders: JsonPrimitive? = null,
    @SerialName("total_messages") val totalMessages: JsonPrimitive? = null,
    @SerialName("exported_messages") val exportedMessages: JsonPrimitive? = null,
    @SerialName("skipped_messages") val skippedMessages: JsonPrimitive? = null,
    @SerialName("total_size_bytes") val totalSizeBytes: JsonPrimitive? = null,
    val parts: List<MailboxExportPartDto> = emptyList(),
    val error: String? = null,
    @SerialName("requested_on") val requestedOn: JsonPrimitive? = null,
    @SerialName("completed_on") val completedOn: JsonPrimitive? = null,
    @SerialName("expires_on") val expiresOn: JsonPrimitive? = null,
) {
    fun toDomain(): MailboxExport? {
        val key = listOf(exportId, altId, otherId).firstOrNull { !it.isNullOrBlank() } ?: return null
        return MailboxExport(
            exportId = key,
            status = MailboxExportStatus.fromWire(status),
            format = format?.takeIf { it.isNotBlank() } ?: MailboxExport.MBOX,
            mailboxEmail = mailboxEmail.orEmpty(),
            isAccountsMailbox = isAccountsMailbox.flag(),
            totalFolders = totalFolders.int(),
            totalMessages = totalMessages.int(),
            exportedMessages = exportedMessages.int(),
            skippedMessages = skippedMessages.int(),
            totalSizeBytes = totalSizeBytes.long(),
            parts = parts.toDomain(),
            error = error.orEmpty(),
            requestedOnMillis = requestedOn.long(),
            completedOnMillis = completedOn.long(),
            expiresOnMillis = expiresOn.long(),
        )
    }
}

@Serializable
internal data class MailboxExportPartDto(
    @SerialName("part_no") val partNo: JsonPrimitive? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("size_bytes") val sizeBytes: JsonPrimitive? = null,
    @SerialName("message_count") val messageCount: JsonPrimitive? = null,
    val url: String? = null,
) {
    /**
     * [positionNo] stands in when the record carries no `part_no`.
     *
     * A part must have a number the UI can name: `0` is not one, and a part
     * numbered `0` used to collide with the "every part" sentinel and turn
     * one row's Download into a download of the whole export. The server
     * lists parts in order, and the file names say so too
     * (`…-part-1-of-2.zip`), so its position is the right stand-in.
     */
    fun toDomain(positionNo: Int): MailboxExportPart? {
        val number = partNo.int()
        if (number <= 0 && fileName.isNullOrBlank()) return null
        return MailboxExportPart(
            partNo = if (number > 0) number else positionNo,
            fileName = fileName.orEmpty(),
            sizeBytes = sizeBytes.long(),
            messageCount = messageCount.int(),
            url = url.orEmpty(),
        )
    }
}

/** The parts of one export, each numbered and in order — see [MailboxExportPartDto.toDomain]. */
internal fun List<MailboxExportPartDto>.toDomain(): List<MailboxExportPart> =
    mapIndexedNotNull { index, dto -> dto.toDomain(positionNo = index + 1) }.sortedBy { it.partNo }

@Serializable
internal data class MailboxExportDownloadDto(
    @SerialName("export_id") val exportId: String? = null,
    @SerialName("expires_in") val expiresIn: JsonPrimitive? = null,
    val parts: List<MailboxExportPartDto> = emptyList(),
) {
    fun toDomain(fallbackId: String): MailboxExportDownload = MailboxExportDownload(
        exportId = exportId?.takeIf { it.isNotBlank() } ?: fallbackId,
        expiresInSeconds = expiresIn.long(),
        // Numbered from their position before anything is dropped, so the
        // numbers still match the ones the export's own parts carry; a part
        // with no link is left out, since a button that opens nothing is
        // worse than one button fewer.
        parts = parts.toDomain().filter { it.url.isNotBlank() },
    )
}

/** The export object in a single-record answer: under `export`, or the `data` itself. */
internal fun JsonElement?.exportRecord(): JsonObject? = when (this) {
    is JsonObject -> (this["export"] as? JsonObject) ?: this["data"]?.exportRecord() ?: this
    is JsonArray -> firstOrNull()?.exportRecord()
    else -> null
}

/** The rows of the list answer: bare, under `exports`, or one level deeper under `data`. */
internal fun JsonElement?.exportRows(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> this["exports"]?.exportRows() ?: this["data"]?.exportRows() ?: emptyList()
    else -> emptyList()
}

/**
 * A flag this backend may send as a boolean *or* as the string `"true"`.
 *
 * `booleanOrNull` reads a quoted `"true"` as true as well — wrong for a
 * strictly-typed flag, exactly right here, where both spellings mean the
 * shared mailbox.
 */
private fun JsonPrimitive?.flag(): Boolean = this?.booleanOrNull == true

private fun JsonPrimitive?.int(): Int = this?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0

private fun JsonPrimitive?.long(): Long = this?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L
