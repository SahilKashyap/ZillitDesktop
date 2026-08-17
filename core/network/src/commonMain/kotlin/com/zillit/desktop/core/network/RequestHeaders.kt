package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.ZillitError

/**
 * Which header set a call needs.
 *
 * Port of the Android `MODELDATA` enum, which selects between encrypted-header
 * variants per backend module. Kept as a closed set so a call site cannot
 * invent a header shape the server will reject.
 */
enum class RequestModule {
    Default,
    Device,
    Project,
    Chat,
    /**
     * `MODELDATA.SCANNER_DEVICE_ID` — this device plus the one that scanned it.
     *
     * Required by `POST device/link-scanned`; sending the plain device header
     * there returns 406.
     */
    ScannerDevice,
    NotificationAcknowledge,
    Media,
    MapRoute,
    LiveKit,

    /**
     * `GET api/v2/configuration` — the third-party credential bundle.
     *
     * Same payload as [Chat] (device + project + user). Named separately so the
     * call site says what it is rather than borrowing an unrelated module's
     * name for its header shape.
     */
    Configuration,

    /**
     * Device + project + user (`MODELDATA.WITH_PROJECT_USER_ID`).
     *
     * The generic name for this shape. [Chat], [Media] and [Configuration] send
     * the same payload; this exists so a call that is none of those does not
     * have to borrow one of their names to get the right header.
     */
    ProjectUser,

    /**
     * The Socket.IO handshake: `{device_id}` and nothing else.
     *
     * Deliberately not [Device], which also carries `time_stamp`. The socket
     * payload is `ReqHeaderForSocket` on Android — one field — and a handshake
     * that sends an extra key is a handshake the server may reject.
     */
    SocketHandshake,
    ;

    /** Calls that must not carry session headers (pre-auth endpoints). */
    val isPreAuth: Boolean get() = this == Device
}

/**
 * Builds request headers.
 *
 * An interface rather than a concrete builder because the header scheme is
 * scheduled to change: the current static-key AES encryption is a compatibility
 * shim, and per-device request signing replaces it (plan §8.3). Swapping the
 * implementation should not touch a single call site.
 */
fun interface RequestHeaderProvider {
    /**
     * @param bodyJson the serialised request body, or null for GET/DELETE.
     *   Required because `bodyhash` is a digest **over the body**, so headers
     *   cannot be built before the body exists.
     * @param projectId the production this one call is about, when that is not
     *   the open one. Joining is the case that needs it: the request must be
     *   scoped to a production the user is asking to enter, and switching the
     *   whole app to it first would clear caches and open a socket for a
     *   production they have not been admitted to.
     */
    suspend fun headersFor(
        module: RequestModule,
        bodyJson: String?,
        projectId: String?,
    ): Map<String, String>
}

/**
 * Header names, matching the Android client's `ApiConstants` **exactly**.
 *
 * Case matters here in practice even though HTTP header names are officially
 * case-insensitive: some server frameworks match exactly, and there is nothing
 * to gain from differing. `deviceInfo` is camelCase on the wire;
 * `moduledata` is not.
 */
object ZillitHeaders {
    const val MODULE_DATA = "moduledata"
    const val DEVICE_INFO = "deviceInfo"

    /**
     * SHA-256 over `{"payload":<body>,"moduledata":<encrypted>}` + the IV as
     * salt. Both the web and Android clients send it on every request; a
     * request without it does not carry a body-integrity proof.
     */
    const val BODY_HASH = "bodyhash"

    /** IANA zone name, e.g. `Europe/London`. Sent by the web client. */
    const val TIMEZONE = "timezone"
    const val AUTHORIZATION = "Authorization"
    const val ACCEPT = "Accept"
    const val CONTENT_TYPE = "Content-Type"
}

/**
 * Maps a platform exception onto a typed error.
 *
 * Implemented per platform because the exception hierarchies differ. The
 * important case is TLS: a handshake or certificate failure must surface as
 * [ZillitError.TlsFailure] and stop there. The Android client's response to
 * this class of error was to disable verification (plan §8.1).
 */
expect fun Throwable.toZillitError(): ZillitError
