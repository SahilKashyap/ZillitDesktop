package com.zillit.desktop.feature.scriptsupervision.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.serialization.json.JsonElement

/** A file the viewer chose from disk. Empty [bytes] never reaches the service. */
class SsPickedFile(val name: String, val bytes: ByteArray, val mime: String) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/**
 * The few things this module needs from the app around it.
 *
 * Only what the JSON client cannot do, which is three things:
 *
 * 1. **Signed byte fetches.** This service stores photos as its own storage
 *    keys and hands out a one-hour signed address for each. Fetching THAT
 *    address is a plain GET with no Zillit token on it — a presigned S3 link
 *    carries its own authorisation in the query string, and sending the
 *    project Bearer with it is what makes S3 refuse the request.
 * 2. **A file chooser**, which needs a window.
 * 3. **Multipart POSTs.** `ApiClient` only speaks JSON envelopes, and this
 *    service's import and upload routes read the file from multipart
 *    `req.files.file`. [upload] is that call, signed the way an envelope call
 *    is signed — see its own note for why that matters.
 *
 * Defaults do nothing rather than throw, so a screen renders its placeholder
 * in a test (and in the pages smoke test) with no host wired.
 */
interface SsHost {

    /** The bytes behind a signed address, or null when it will not fetch. */
    suspend fun fetch(url: String): ByteArray? = null

    /** Opens [url] in the viewer's browser — a report's PDF, a signed photo full size. */
    fun open(url: String) {}

    /**
     * Opens the file chooser, limited to [extensions] (lower case, no dot;
     * empty means any). Null is "cancelled", which is not a failure and is
     * never toasted.
     */
    suspend fun pick(extensions: Set<String>): SsPickedFile? = null

    /**
     * POSTs [file] to [url] as multipart `file`, and answers the envelope's
     * `data`.
     *
     * ⚠ Signed like any other call: a raw HTTP call that carries only the
     * legacy header gets 401 `libs_moduledata_not_accepted` from this service,
     * which is how every raw file export in this app broke once. The host
     * implementation must go through the app's own raw signer, not a bare
     * `HttpClient.post`.
     *
     * The body hash covers a JSON body, so a multipart call is signed with no
     * body — the web's own interceptor does the same for its `FormData` posts.
     *
     * [fields] are the extra multipart parts the route reads beside the file.
     * The two import routes take none; `/upload/file` takes `folder`, and
     * leaving it out puts the file in the wrong place in project storage.
     *
     * [onProgress] is called with 0..100 as the file's bytes are handed to
     * the engine — the same contract and the same meaning as chat's
     * `uploadMedia`. It is the share of the FILE that has gone, not of the
     * whole multipart envelope, because the other parts are a few hundred
     * bytes and counting them would only make the number lie slightly.
     */
    suspend fun upload(
        url: String,
        file: SsPickedFile,
        fields: Map<String, String> = emptyMap(),
        onProgress: (Int) -> Unit = {},
    ): ZillitResult<JsonElement> = noHost()

    /**
     * The bytes behind one of THIS SERVICE's own addresses — an export or a
     * report, which answer a file rather than an envelope.
     *
     * ⚠ Signed, unlike [fetch]. The two are different calls on purpose:
     * [fetch] goes to a presigned S3 link that carries its own
     * authorisation, while this goes to the service, which answers 401
     * `libs_moduledata_not_accepted` without the app's own signing. Every
     * raw file export in this app broke that way once.
     *
     * A JSON body on the wire means the service DECLINED — the export routes
     * answer the file on success — so the failure carries its message key.
     */
    suspend fun downloadBytes(url: String): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Unknown("no download host is wired"))

    /**
     * Saves [bytes] through a save dialog; false when the viewer cancelled.
     *
     * The web hands a Blob to the browser, which saves it without asking.
     * A desktop app has to ask, so a cancel is an ordinary outcome and is
     * not reported as a failure.
     */
    suspend fun save(suggestedName: String, bytes: ByteArray): Boolean = false

    companion object {
        /** No host: picks nothing, uploads nothing, fetches nothing. */
        val None: SsHost = object : SsHost {}

        internal fun noHost(): ZillitResult<JsonElement> =
            ZillitResult.Failure(ZillitError.Unknown("no file host is wired"))
    }
}
