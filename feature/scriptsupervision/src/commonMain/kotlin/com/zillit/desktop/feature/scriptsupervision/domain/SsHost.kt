package com.zillit.desktop.feature.scriptsupervision.domain

/**
 * The few things this module needs from the app around it.
 *
 * Only what the JSON client cannot do: this service stores photos as its own
 * storage keys and hands out a one-hour signed address for each, and fetching
 * THAT address is a plain GET with no Zillit token on it — a presigned S3 link
 * carries its own authorisation in the query string, and sending the project
 * Bearer with it is what makes S3 refuse the request.
 *
 * Defaults do nothing rather than throw, so a screen renders its placeholder
 * in a test (and in the pages smoke test) with no host wired.
 */
interface SsHost {

    /** The bytes behind a signed address, or null when it will not fetch. */
    suspend fun fetch(url: String): ByteArray? = null

    /** Opens [url] in the viewer's browser — a report's PDF, a signed photo full size. */
    fun open(url: String) {}

    companion object {
        /** No host: every screen draws its placeholder. */
        val None: SsHost = object : SsHost {}
    }
}
