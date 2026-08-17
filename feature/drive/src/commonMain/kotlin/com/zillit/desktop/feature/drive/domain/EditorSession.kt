package com.zillit.desktop.feature.drive.domain

/**
 * What the server hands back to open one document in Collabora.
 *
 * **Not a URL.** `GET /drive/editor/{id}/config` returns the pieces and the
 * client assembles them — the editor's own base, the WOPI source the editor
 * should fetch the file from, and the session token authorising it. The web
 * does the same assembly in `CollaboraEditor.jsx`; there is no endpoint that
 * returns a ready-made address.
 *
 * ## This one response is camelCase
 *
 * Every other route on this service is snake_case. This one is not
 * (`collaboraUrl`, `wopiSrc`, `accessToken`), because it is passed through to
 * a WOPI host rather than shaped by the same serialiser. Reading it as
 * snake_case yields every field null and an editor that silently never opens.
 */
data class EditorSession(
    /**
     * The editor page itself.
     *
     * `editorUrl` when the server's discovery call resolved one, and the
     * conventional path under `collaboraUrl` otherwise — the same fallback the
     * web applies, because discovery is cached for an hour and is empty on a
     * cold server.
     */
    val baseUrl: String,
    /** Where the editor fetches and saves the file — a URL back to our WOPI host. */
    val wopiSrc: String,
    /** The signed session token. Scoped to one file and one user, 8 hours. */
    val accessToken: String,
    val fileName: String = "",
) {

    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && wopiSrc.isNotBlank() && accessToken.isNotBlank()

    /**
     * The address to load.
     *
     * `NotWOPIButIframe` is Collabora's own flag for being hosted in a frame
     * rather than reached directly; without it the editor renders its own
     * chrome and its close button tries to navigate the host away.
     *
     * Both values are percent-encoded: `wopiSrc` is a full URL and its `?`,
     * `:` and `/` would otherwise be read as part of *this* query string, and
     * the token is base64 — whose `+` and `=` decode to a space and a
     * delimiter, producing an "invalid token" from a token that was fine.
     */
    fun address(): String = buildString {
        append(baseUrl)
        append(if ('?' in baseUrl) '&' else '?')
        append("WOPISrc=").append(wopiSrc.percentEncoded())
        append("&access_token=").append(accessToken.percentEncoded())
        append("&NotWOPIButIframe=true")
    }
}

/**
 * Percent-encodes everything outside RFC 3986's unreserved set.
 *
 * Hand-rolled because this module is common code with no URL type in it, and
 * pulling a networking dependency into a feature module for one query
 * parameter is the worse trade. Deliberately encodes `~` conservatively — some
 * WOPI hosts reject it unencoded, and over-encoding an unreserved character is
 * always safe to decode.
 */
internal fun String.percentEncoded(): String = buildString {
    this@percentEncoded.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and BYTE_MASK
        val char = value.toChar()
        if (char.isLetterOrDigit() && value < ASCII_LIMIT || char in UNRESERVED) {
            append(char)
        } else {
            append('%')
            append(HEX[value shr NIBBLE])
            append(HEX[value and LOW_NIBBLE])
        }
    }
}

private const val UNRESERVED = "-._"
private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 0x80
private const val NIBBLE = 4
private const val LOW_NIBBLE = 0x0F
private const val HEX = "0123456789ABCDEF"
