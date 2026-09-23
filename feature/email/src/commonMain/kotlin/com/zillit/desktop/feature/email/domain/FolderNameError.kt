package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** Why a folder name was refused. */
enum class FolderNameError {
    Blank,
    Duplicate,

    /** Reserved by the mail server — Inbox, Sent, Drafts and friends. */
    Reserved,

    /** Contains a character IMAP uses structurally, or a control character. */
    IllegalCharacter,

    TooLong,
}

/**
 * Checks a folder name before it is sent.
 *
 * Client-side because the server's rejection is a 400 with a message written
 * for a developer, and because two of these are worth catching before the user
 * has finished typing. The server remains the authority; this is the courtesy
 * layer.
 *
 * [renamingFrom] is the folder's current name when renaming, so a folder does
 * not collide with itself — renaming `Vendors` to `vendors` to fix its
 * capitalisation must be allowed.
 */
fun validateFolderName(
    name: String,
    existing: List<EmailFolder>,
    renamingFrom: String? = null,
): FolderNameError? {
    val trimmed = name.trim()

    return when {
        trimmed.isEmpty() -> FolderNameError.Blank

        trimmed.length > MAX_LENGTH -> FolderNameError.TooLong

        // `/` and `.` are hierarchy delimiters depending on the server, and a
        // name containing one either creates a nested folder by accident or is
        // rejected outright. Control characters break the IMAP protocol itself.
        trimmed.any { it in ILLEGAL || it.code < MIN_PRINTABLE } -> FolderNameError.IllegalCharacter

        EmailFolder.SYSTEM_ORDER.any { it.equals(trimmed, ignoreCase = true) } ->
            FolderNameError.Reserved

        existing.any {
            it.name.equals(trimmed, ignoreCase = true) &&
                !it.name.equals(renamingFrom, ignoreCase = true)
        } -> FolderNameError.Duplicate

        else -> null
    }
}

/**
 * Whether this folder can be renamed at all.
 *
 * System folders cannot: the server addresses them by name, and every client
 * including this one looks for `INBOX` by that exact string.
 */
val EmailFolder.isRenameable: Boolean
    get() = !isSystem && EmailFolder.SYSTEM_ORDER.none { it.equals(name, ignoreCase = true) }

/**
 * Whether this folder can be deleted.
 *
 * The same rule as renaming, and for the same reason: a mailbox without an
 * Inbox is not a mailbox, and the server would refuse anyway.
 */
val EmailFolder.isDeletable: Boolean get() = isRenameable

/** What to show the user. */
val FolderNameError.message: String
    get() = when (this) {
        FolderNameError.Blank -> str(S.desktop_email_folder_name_required)
        FolderNameError.Duplicate -> str(S.desktop_email_folder_name_duplicate)
        FolderNameError.Reserved -> str(S.desktop_email_folder_name_reserved)
        FolderNameError.IllegalCharacter -> str(S.desktop_email_folder_name_illegal_chars)
        FolderNameError.TooLong -> str(S.desktop_email_folder_name_too_long)
    }

private val ILLEGAL = charArrayOf('/', '\\', '.', '"')

private const val MIN_PRINTABLE = 0x20
private const val MAX_LENGTH = 100
