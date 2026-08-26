package com.zillit.desktop.feature.chat.domain

/**
 * What the composer refuses, and why — the other clients' rules in one place.
 *
 * The phones and the web each guard the chat composer before anything reaches
 * the socket, and a desktop that guards differently is a desktop that lets a
 * message through where a phone would not (or refuses one it would send). The
 * wordings are the web's own, so a crew member who has seen the refusal in a
 * browser reads the same sentence here.
 */
object ChatComposerRules {

    /**
     * 2000 characters, as the web counts them
     * (`cnc_latest/components/ChatFooterCnc.jsx:202,264` on send, and
     * `personal/MyMessage.jsx:242,347` on edit). Android has no limit of its
     * own — it is the web that speaks for the server here, so the desktop
     * follows the client that actually validates.
     */
    const val MAX_BODY_CHARS = 2000

    /**
     * 70 MB. Both other clients draw the line here and neither is quiet about
     * it: the web filters an oversize video out of the selection with a
     * notice (`ChatFooterCnc.jsx:509-527`), and Android splits or compresses
     * a video down to fit (`mediaHandler/video_compressor.kt:34,74`).
     *
     * Until now the desktop stopped at mail's 25 MB — and stopped *silently*,
     * because the shared picker drops an oversize file without a word.
     */
    const val MAX_ATTACHMENT_BYTES = 70L * 1024 * 1024

    /**
     * The one extension the web names outright
     * (`ChatFooterCnc.jsx:604-609`). Case-insensitive: `.EXE` is the same
     * refusal, which the web's own check would miss.
     */
    private val REFUSED_EXTENSIONS = setOf("exe")

    /** The web's `exceeding_text_limit` (`utils/language/en.js:5649`). */
    const val BODY_TOO_LONG = "The text limit exceeds 2000 characters."

    /**
     * The web's `media_above_70mb` (`utils/language/en.js:6532`), shortened:
     * its tail explains that the file was dropped from a multi-file
     * selection, and this picker takes one file at a time.
     */
    const val ATTACHMENT_TOO_LARGE = "The media above 70MB is not permitted to send."

    /** The web's `invalid_file_type` (`utils/language/en.js:5651`). */
    const val ATTACHMENT_REFUSED_TYPE = "Please select a valid file type"

    /** Longer than every client will carry. Counted in characters, as they count. */
    fun bodyTooLong(body: String): Boolean = body.length > MAX_BODY_CHARS

    /**
     * Why this file cannot be sent, or null when it can.
     *
     * Size first: a 200 MB `.exe` is refused for being an executable either
     * way, but the size is the thing the sender can do something about.
     */
    fun refuse(name: String, sizeBytes: Long): String? = when {
        sizeBytes > MAX_ATTACHMENT_BYTES -> ATTACHMENT_TOO_LARGE
        name.substringAfterLast('.', "").lowercase() in REFUSED_EXTENSIONS -> ATTACHMENT_REFUSED_TYPE
        else -> null
    }
}

/**
 * What came back from the paperclip.
 *
 * A picker that answers only "here is a file, or nothing" cannot say *why*
 * nothing: the file the user chose may have been too big to send, and they
 * are owed that sentence rather than a dialog that closes and does nothing.
 */
sealed interface ChatPick {

    /** The dialog was dismissed, or the file could not be read. */
    data object Cancelled : ChatPick

    /** Refused before reading — the reason is already the user's to see. */
    data class Refused(val reason: String) : ChatPick

    /** A file, on its way to the preview. */
    data class Ready(val upload: PendingChatUpload) : ChatPick
}
