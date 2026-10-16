package com.zillit.desktop.feature.drive.domain

/**
 * What the server hands back to open one document in a live editor.
 *
 * **Not a URL.** `GET /drive/editor/{id}/config` returns the pieces and the
 * client assembles them — the editor page itself, the WOPI source the editor
 * fetches the file from, and the session token authorising it. There is no
 * endpoint that returns a ready-made address.
 *
 * ## Zillit Sheets / Docs / Slides, and the old editor beneath them
 *
 * The office formats now open in Zillit's own editors, which the config names
 * as `editorUrl` (`liveEditorUrl` on the web's spelling) and tags with
 * [kind]. That page reads the token out of its own URL, removes it from the
 * visible address, loads and saves through Drive by itself and joins the live
 * session on its own — so a desktop window only has to load the address
 * top-level. No bridge, no `postMessage`.
 *
 * A deployment whose config still answers only `collaboraUrl` is served by
 * the old assembly, [legacyFrame] and all, so this client keeps working
 * against a server that has not been updated.
 *
 * ## This one response is camelCase
 *
 * Every other route on this service is snake_case. This one is not
 * (`editorUrl`, `wopiSrc`, `accessToken`), because it is passed through to a
 * WOPI host rather than shaped by the same serialiser. Reading it as
 * snake_case yields every field null and an editor that silently never opens.
 */
data class EditorSession(
    /**
     * The editor page itself.
     *
     * `editorUrl` when the server named one, and the conventional path under
     * `collaboraUrl` otherwise — the same fallback the web applies, because
     * discovery is cached for an hour and is empty on a cold server.
     */
    val baseUrl: String,
    /** Where the editor fetches and saves the file — a URL back to our WOPI host. */
    val wopiSrc: String,
    /** The signed session token. Scoped to one file and one user. */
    val accessToken: String,
    val fileName: String = "",
    /** Sheets, Docs or Slides — what the config's `editor` said. */
    val kind: DriveEditorKind? = null,
    /** Opened read-only: either `mode=view` was asked for, or this person may not edit. */
    val viewOnly: Boolean = false,
    /**
     * `_permissions.canSwitchToEdit` — a read-only session belonging to
     * someone who may edit. The editor page draws no Edit button of its own
     * outside a desktop browser, so the app's own toolbar offers it.
     */
    val canSwitchToEdit: Boolean = false,
    /**
     * The address came from `collaboraUrl`, not from a Zillit editor: it is
     * framed, and needs Collabora's own flag for that.
     */
    val legacyFrame: Boolean = false,
) {

    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && wopiSrc.isNotBlank() && accessToken.isNotBlank()

    /**
     * The address to load.
     *
     * Both values are percent-encoded: `wopiSrc` is a full URL and its `?`,
     * `:` and `/` would otherwise be read as part of *this* query string, and
     * the token is base64 — whose `+` and `=` decode to a space and a
     * delimiter, producing an "invalid token" from a token that was fine.
     *
     * `NotWOPIButIframe` is Collabora's own flag for being hosted in a frame
     * rather than reached directly; without it the old editor renders its own
     * chrome and its close button tries to navigate the host away. Zillit's
     * editors are loaded top-level and take no such flag.
     */
    fun address(): String = buildString {
        append(baseUrl)
        append(if ('?' in baseUrl) '&' else '?')
        append("WOPISrc=").append(wopiSrc.percentEncoded())
        append("&access_token=").append(accessToken.percentEncoded())
        if (legacyFrame) append("&NotWOPIButIframe=true")
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
