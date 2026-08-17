package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Where downloaded attachments go.
 *
 * A port so the sanitising rules below can be tested without writing to a real
 * disk, and so the mailbox does not need to know what a filesystem is.
 */
interface AttachmentStore {

    /**
     * Writes [bytes] under [fileName] and returns the path written to.
     *
     * Implementations must treat [fileName] as hostile — see [safeFileName] —
     * and must not overwrite an existing file.
     */
    suspend fun save(fileName: String, bytes: ByteArray): ZillitResult<String>
}

/**
 * Makes a server-supplied filename safe to write.
 *
 * ## This is a security boundary, not tidying
 *
 * The name comes from a mail header, which means it comes from whoever sent the
 * mail. `../../../.zshrc` or `/etc/cron.d/x` as an attachment name would, given
 * a naive join, write attacker-controlled bytes anywhere the user can write.
 * That is remote code execution by way of a filename.
 *
 * So the rules are deliberately blunt:
 *
 *  - take the last path segment only, splitting on **both** separators — a
 *    Windows-style `..\..\x` must not survive on a Unix host
 *  - reject `.` and `..` outright
 *  - drop control characters, and the characters Windows forbids, so the same
 *    download behaves on every platform we ship to
 *  - cap the length, since most filesystems stop at 255 bytes and a longer name
 *    fails the write rather than truncating
 *
 * The result never contains a separator, so it cannot escape its directory.
 */
fun safeFileName(fileName: String, fallback: String = "attachment"): String {
    val lastSegment = fileName
        .replace('\\', '/')
        .substringAfterLast('/')
        .trim()

    val cleaned = lastSegment
        // Everything printable is kept, not just ASCII: capping at 0x7F would
        // reduce "रिपोर्ट.pdf" to ".pdf", and people recognise their files by
        // name. Only control characters and the separators are dangerous.
        .filter { it.code >= MIN_PRINTABLE && it.code != DEL && it !in FORBIDDEN }
        // Trailing dots and spaces are silently stripped by Windows, which
        // turns "report.pdf." into a different file than the one we checked.
        .trimEnd('.', ' ')

    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return fallback

    return cleaned.takeLast(MAX_NAME_LENGTH)
}

/** `<` `>` `:` `"` `|` `?` `*` are illegal on Windows; `/` would escape the directory. */
private val FORBIDDEN = charArrayOf('<', '>', ':', '"', '|', '?', '*', '/', '\\')

private const val MIN_PRINTABLE = 0x20
private const val DEL = 0x7F
private const val MAX_NAME_LENGTH = 200

/**
 * Decodes the server's attachment payload.
 *
 * Sent as base64, sometimes as a bare string and sometimes as a data URI
 * (`data:application/pdf;base64,JVBERi0…`). The prefix has to go before
 * decoding, and whitespace with it: base64 in JSON is often line-wrapped, and
 * a strict decoder rejects the newlines.
 */
fun decodeAttachment(payload: String, decodeBase64: (String) -> ByteArray?): ByteArray? {
    val body = payload.substringAfterLast(BASE64_MARKER, payload)
    val compact = body.filterNot { it.isWhitespace() }

    return if (compact.isEmpty()) null else decodeBase64(compact)
}

private const val BASE64_MARKER = "base64,"

/**
 * The default base64 decoder.
 *
 * Tries the strict alphabet first and falls back to MIME, which ignores
 * characters outside the alphabet. Mail base64 is usually line-wrapped and
 * occasionally unpadded, and refusing to decode a real attachment because of a
 * missing `=` would be a poor trade for strictness nobody benefits from.
 *
 * Returns null rather than throwing: a corrupt attachment is a thing that
 * happens, and it should read as "could not download" rather than a crash.
 */
@OptIn(ExperimentalEncodingApi::class)
fun decodeBase64Default(value: String): ByteArray? =
    runCatching { Base64.decode(value) }
        .recoverCatching { Base64.Mime.decode(value) }
        .getOrNull()
