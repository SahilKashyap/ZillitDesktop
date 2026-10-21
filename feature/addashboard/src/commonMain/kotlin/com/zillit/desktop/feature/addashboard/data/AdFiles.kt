package com.zillit.desktop.feature.addashboard.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The file-level odds and ends an upload needs, kept out of the repository. */

private val SPREADSHEET_TYPES = mapOf(
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "xls" to "application/vnd.ms-excel",
    "csv" to "text/csv",
    "pdf" to "application/pdf",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
)

/** The agency list's three accepted extensions, as the import offers them. */
val AGENCY_LIST_EXTENSIONS: List<String> = listOf("xlsx", "xls", "csv")

/** What a name's extension says it is; an unknown one goes up as plain bytes. */
internal fun contentTypeOf(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return SPREADSHEET_TYPES[extension] ?: "application/octet-stream"
}

/**
 * A name safe to put in a storage key.
 *
 * Only what a key cannot hold is dropped, so a name from a file dialog can
 * never become a path of its own.
 */
internal fun safeName(fileName: String): String = fileName
    .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    .trim('.')
    .ifBlank { "file" }

/**
 * A drawn signature as the inline data URL the submit route takes.
 *
 * The one place this module sends bytes inline rather than as an upload: the
 * day's own sign-off carries the image in its body, while an Exhibit G
 * signature is uploaded first and referenced.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun ByteArray.asPngDataUrl(): String = "data:image/png;base64," + Base64.encode(this)

/** The same, named for the one caller outside this package. */
internal fun signatureDataUrl(bytes: ByteArray): String = bytes.asPngDataUrl()
