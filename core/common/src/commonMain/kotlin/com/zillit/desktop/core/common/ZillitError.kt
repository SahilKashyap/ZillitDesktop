package com.zillit.desktop.core.common

/**
 * Every failure the app can surface, as a closed set.
 *
 * `userMessage` is what the UI shows; `technical` is for logs only and must
 * never reach the screen. Nothing here carries a token, header or payload —
 * errors are logged, and logs are redacted (plan §8.4).
 */
sealed interface ZillitError {

    /** Message safe to show a user. Feature layers may override with their own copy. */
    val userMessage: String

    /** Detail for logs and crash reports. Never rendered. */
    val technical: String?

    // -- transport ---------------------------------------------------------

    data class NoConnection(
        override val technical: String? = null,
    ) : ZillitError {
        override val userMessage = "No internet connection."
    }

    data class Timeout(
        override val technical: String? = null,
    ) : ZillitError {
        override val userMessage = "The server took too long to respond."
    }

    /**
     * TLS validation failed. Deliberately its own case, and deliberately not
     * recoverable: the Android client's answer to this was to disable
     * certificate checking entirely (plan §8.1). Here it is a hard stop.
     */
    data class TlsFailure(
        override val technical: String? = null,
    ) : ZillitError {
        override val userMessage = "Could not establish a secure connection to Zillit."
    }

    // -- protocol ----------------------------------------------------------

    data class Http(
        val status: Int,
        val serverMessage: String? = null,
        override val technical: String? = null,
        /**
         * Values for the placeholders in [serverMessage].
         *
         * Carried this far because the substitution cannot happen earlier: the
         * placeholders live in the *translated* text, and translation happens
         * where the message is shown. See `ZillitError.localised()`.
         */
        val messageElements: List<MessageElement> = emptyList(),
    ) : ZillitError {
        /**
         * The raw server message, placeholders and all.
         *
         * Prefer `ZillitError.localised()` for anything a person will read —
         * this is the untranslated key, which is right for a log line and wrong
         * for a toast.
         */
        override val userMessage: String = serverMessage ?: "Something went wrong ($status)."
    }

    data class Unauthorized(
        override val technical: String? = null,
    ) : ZillitError {
        override val userMessage = "Your session has expired. Please sign in again."
    }

    data class Forbidden(
        override val technical: String? = null,
    ) : ZillitError {
        override val userMessage = "You do not have access to this."
    }

    /** The device was revoked server-side; the app must wipe local state. */
    data class DeviceRevoked(
        override val technical: String? = null,
    ) : ZillitError {
        override val userMessage = "This device is no longer authorised."
    }

    data class Serialization(
        override val technical: String? = null,
    ) : ZillitError {
        override val userMessage = "The server sent something unexpected."
    }

    // -- local -------------------------------------------------------------

    /**
     * The local disk would not do what was asked.
     *
     * [userMessage] is overridable because "Could not read local data" is wrong
     * for half the things that reach here — saving a download is a write, and
     * telling someone a read failed sends them looking in the wrong place.
     */
    data class Storage(
        override val technical: String? = null,
        override val userMessage: String = "Could not read local data.",
    ) : ZillitError

    data class Crypto(
        override val technical: String? = null,
    ) : ZillitError {
        override val userMessage = "A security check failed."
    }

    data class Validation(
        override val userMessage: String,
        override val technical: String? = null,
    ) : ZillitError

    data class Unknown(
        override val technical: String? = null,
    ) : ZillitError {
        override val userMessage = "Something went wrong."
    }
}
