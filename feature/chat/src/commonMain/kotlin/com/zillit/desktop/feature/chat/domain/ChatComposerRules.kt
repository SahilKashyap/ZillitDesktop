package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.media.AttachmentPicker
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
     * 8000 characters, counted as the web counts them. Raised from the
     * web's 2000 (`cnc_latest/components/ChatFooterCnc.jsx:202,264` on send,
     * and `personal/MyMessage.jsx:242,347` on edit), so the web still
     * refuses to send — or to save an edit of — anything past 2000. Android
     * has no limit of its own.
     */
    const val MAX_BODY_CHARS = 8000

    /**
     * About 2 GB — no product cap, only the most a file can weigh and still
     * be read whole (`AttachmentPicker.MAX_READABLE_BYTES`).
     *
     * The web and Android stop at 70 MB, and the desktop used to follow
     * them. It no longer does: a video over 100 MB is compressed before it
     * uploads (`VideoCompressor`), and anything else goes as it is.
     */
    const val MAX_ATTACHMENT_BYTES = AttachmentPicker.MAX_READABLE_BYTES

    /**
     * The one extension the web names outright
     * (`ChatFooterCnc.jsx:604-609`). Case-insensitive: `.EXE` is the same
     * refusal, which the web's own check would miss.
     */
    private val REFUSED_EXTENSIONS = setOf("exe")

    /** The web's `exceeding_text_limit` (`utils/language/en.js:5649`). */
    val BODY_TOO_LONG: String get() = str(S.desktop_chat_text_limit_exceeded)

    /** Said when a file is past [MAX_ATTACHMENT_BYTES]. */
    val ATTACHMENT_TOO_LARGE: String get() = str(S.desktop_chat_media_above_2gb)

    /** The web's `invalid_file_type` (`utils/language/en.js:5651`). */
    val ATTACHMENT_REFUSED_TYPE: String get() = str(S.desktop_select_a_valid_file_type)

    /** Longer than every client will carry. Counted in characters, as they count. */
    fun bodyTooLong(body: String): Boolean = body.length > MAX_BODY_CHARS

    /**
     * Two hours — how long after posting a message its author may still
     * rewrite or withdraw it (`cncUtil.js` `isWithin2HoursRange`, ZL-17418).
     * The web shows Edit and Delete regardless and refuses on the click with
     * [REWRITE_WINDOW_CLOSED]; this client does the same.
     */
    const val REWRITE_WINDOW_MILLIS = 2L * 60 * 60 * 1000

    /** The web's `message_modal_to_stop_edit_after_two_hour` (`utils/language/en.js:8071`). */
    val REWRITE_WINDOW_CLOSED: String get() = str(S.desktop_chat_rewrite_window_closed)

    /**
     * Whether a message posted at [createdMillis] may still be edited or
     * deleted at [nowMillis]. An admin's clock never runs out — the web's
     * edit path (`MyMessage.jsx:426`); its delete path lifts the clock for
     * admins on personal productions only (`commonUtils.js:119`), a
     * distinction this client cannot draw and so lifts for admins everywhere.
     */
    fun canRewrite(createdMillis: Long, nowMillis: Long, isAdmin: Boolean): Boolean =
        isAdmin || nowMillis - createdMillis <= REWRITE_WINDOW_MILLIS

    /**
     * Why this file cannot be sent, or null when it can.
     *
     * Size first: a 3 GB `.exe` is refused for being an executable either
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

    /**
     * A file on its way to the preview — and [more] when several were chosen
     * at once, WhatsApp's multi-select, each to go as its own message.
     */
    data class Ready(val upload: PendingChatUpload, val more: List<PendingChatUpload> = emptyList()) : ChatPick
}
