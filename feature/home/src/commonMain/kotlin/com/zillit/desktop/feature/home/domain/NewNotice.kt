package com.zillit.desktop.feature.home.domain

/**
 * Where a post is in its journey to the server.
 *
 * Modelled explicitly so the board can show a post the instant it is written.
 * Android keeps the same three states as an int on the row (`0` pending, `2`
 * sent, `-1` failed); naming them stops `status == 2` appearing in UI code.
 */
enum class NoticeSendState {
    /** On the server. */
    Sent,

    /** Written, in flight. Shown immediately so typing feels answered. */
    Sending,

    /** The post exists locally and the server has not taken it. Retryable. */
    Failed,
}

/**
 * A post being written.
 *
 * The character limit is the backend's (`Constants.TEXT_LIMIT`), enforced here
 * so the user sees the boundary while typing rather than on rejection.
 */
data class NoticeDraft(
    val text: String = "",
    /**
     * At most one, matching the wire — with a file attached the text becomes
     * its caption.
     */
    val media: PickedMedia? = null,
    /** A shared location; [media] is then its map image, when one was made. */
    val location: GeoPoint? = null,
) {

    val trimmed: String get() = text.trim()

    val length: Int get() = text.length

    val isOverLimit: Boolean get() = length > MAX_LENGTH

    val remaining: Int get() = MAX_LENGTH - length

    /**
     * Whether this can be sent.
     *
     * Whitespace alone is not a notice: the board is read by a whole crew, and
     * a blank card is noise nobody can act on.
     */
    val canSend: Boolean
        get() = (trimmed.isNotEmpty() || media != null || location != null) && !isOverLimit

    /** Show the counter only when it starts to matter. */
    val showsCounter: Boolean get() = remaining <= COUNTER_THRESHOLD

    companion object {
        /** `Constants.TEXT_LIMIT` on Android. */
        const val MAX_LENGTH = 2000

        /** How close to the limit before the count is worth screen space. */
        const val COUNTER_THRESHOLD = 200
    }
}
