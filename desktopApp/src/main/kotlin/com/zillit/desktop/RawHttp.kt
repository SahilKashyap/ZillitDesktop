package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestHeaderProvider
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.core.network.tokenauth.TokenSessionManager
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A signed POST whose answer is a file, not an envelope — the export routes
 * (`/export/{pdf|xlsx|csv}`) stream bytes. `ApiClient` only speaks envelopes,
 * so this builds the same headers over the same serialised body and reads the
 * response raw. A JSON body on the wire means the service declined ("nothing
 * to export", a permission key): its `message` is surfaced instead of bytes.
 *
 * Token mode is decided exactly as `ApiClient.envelope` decides it — see
 * [signedRawResponse] — so a raw export is no more likely to 401 on
 * `moduledata` than an envelope call next to it.
 */
internal suspend fun AppGraph.Ready.postForBytes(
    url: String,
    body: JsonObject,
    module: RequestModule = RequestModule.ProjectUser,
): ZillitResult<ByteArray> {
    val bodyJson = HttpClientFactory.json.encodeToString(JsonElement.serializer(), body)
    return runCatching {
        val response = signedRawResponse(module, url, bodyJson) { headers, bearer ->
            httpClient.post(url) {
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                bearer?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
        }
        response.declineOrBytes()
    }.toRawResult()
}

/**
 * A signed GET whose answer is a file — the sales invoice PDF
 * (`GET /invoices/sales-invoices/:id/pdf`). The same rules as [postForBytes]:
 * a JSON body is the service declining, and its `message` is surfaced.
 */
internal suspend fun AppGraph.Ready.getForBytes(
    url: String,
    module: RequestModule = RequestModule.ProjectUser,
): ZillitResult<ByteArray> {
    return runCatching {
        val response = signedRawResponse(module, url, bodyJson = null) { headers, bearer ->
            httpClient.get(url) {
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                bearer?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
            }
        }
        response.declineOrBytes()
    }.toRawResult()
}

/** Bytes on success; the service's own refusal otherwise — shared by [postForBytes] and [getForBytes]. */
private suspend fun HttpResponse.declineOrBytes(): ByteArray {
    val bytes = readRawBytes()
    val isJson = contentType()?.match(ContentType.Application.Json) == true
    return when {
        !status.isSuccess() -> throw ExportDeclined(status.value, envelopeMessage(bytes))
        // A refusal wrapped in an otherwise-successful response — "nothing to
        // export", a permission key — so there is no real status to blame.
        isJson -> throw ExportDeclined(httpStatus = null, envelopeMessage(bytes))
        else -> bytes
    }
}

private fun Result<ByteArray>.toRawResult(): ZillitResult<ByteArray> = fold(
    onSuccess = { ZillitResult.Success(it) },
    onFailure = { failure ->
        val declined = failure as? ExportDeclined
        ZillitResult.Failure(rawExportError(declined?.httpStatus, declined?.serverMessage, failure.message))
    },
)

/**
 * The [ZillitError] a raw (non-envelope) failure maps to — the same rule
 * `ApiClient.envelope` applies to every envelope failure (401/403 get the
 * app's fixed, generic copy; anything else keeps the server's own message,
 * translatable via `ZillitError.localised()`). Before this, every raw
 * failure — a genuine 401, a real 403 permission refusal, a decode error —
 * collapsed into the same [ZillitError.Unknown] with its hardcoded
 * "Something went wrong.", because `Unknown.userMessage` deliberately
 * ignores `technical`. Shared with [PayrollWiring.toPayrollExportError],
 * whose export routes hit the same three shapes over their own exception type.
 *
 * [httpStatus] null means the failure carries no real HTTP error code to
 * show — a soft decline on an otherwise-successful response (see
 * [declineOrBytes]) — so it is never rendered as if it were one.
 */
internal fun rawExportError(httpStatus: Int?, serverMessage: String?, fallbackMessage: String?): ZillitError =
    when (httpStatus) {
        HTTP_UNAUTHORIZED -> ZillitError.Unauthorized(serverMessage)
        HTTP_FORBIDDEN -> ZillitError.Forbidden(serverMessage)
        else -> when {
            serverMessage != null -> ZillitError.Http(httpStatus ?: 0, serverMessage)
            httpStatus != null -> ZillitError.Http(httpStatus, null)
            else -> ZillitError.Unknown(fallbackMessage)
        }
    }

/**
 * Performs a raw (non-envelope) request with the same auth decision
 * `ApiClient.envelope` makes, so a raw export or download rides a Bearer
 * token exactly when a normal API call would, `moduledata` exactly when one
 * would fall back to it, and heals a single expired-token 401 the same way.
 *
 * [perform] builds and sends the actual request given the resolved plain
 * headers and the Bearer token (null in legacy mode) — the one thing that
 * differs between a POST with a body and a bodyless GET, so it stays with
 * the caller rather than becoming a third near-duplicate of [postForBytes]
 * and [getForBytes]'s own request-building.
 *
 * Kept in this file rather than folded into [com.zillit.desktop.core.network.ApiClient]:
 * that class speaks envelopes only, and every caller here reads raw bytes.
 *
 * Logs status + URL only, at the same level `ApiClient` logs a failure —
 * never headers or bytes. [httpClient] is the lean, unlogged storage client
 * on purpose (it also carries S3 uploads and the token session's own calls,
 * whose answers hold live credentials), but every URL that reaches here is
 * the app's own export/download endpoint, never one of those, so naming it
 * costs nothing. Without this line, a raw call's failure was invisible in
 * `~/.zillit/logs` — diagnosing one meant a temporary debug log and a
 * rebuild, twice, before this existed.
 */
internal suspend fun AppGraph.Ready.signedRawResponse(
    module: RequestModule,
    url: String,
    bodyJson: String?,
    perform: suspend (headers: Map<String, String>, bearer: String?) -> HttpResponse,
): HttpResponse = signRawResponse(
    tokenSession = tokenSession,
    headerProvider = headerProvider,
    projectForAuth = projectContext?.context?.value?.project?.projectId,
    module = module,
    url = url,
    bodyJson = bodyJson,
    perform = perform,
)

/**
 * [signedRawResponse] without the graph, for a transfer built while the graph is
 * still being assembled (Document Distribution's) — one decision, not two copies
 * of it that could drift apart.
 */
internal suspend fun signRawResponse(
    tokenSession: TokenSessionManager,
    headerProvider: RequestHeaderProvider,
    projectForAuth: String?,
    module: RequestModule,
    url: String,
    bodyJson: String?,
    perform: suspend (headers: Map<String, String>, bearer: String?) -> HttpResponse,
): HttpResponse {
    val token = tokenSession.bearerFor(module, projectForAuth, url)
    val resolvedHeaders = if (token == null) {
        headerProvider.headersFor(module, bodyJson, null)
    } else {
        headerProvider.plainHeaders()
    }
    var response = perform(resolvedHeaders, token)
    if (token != null && response.status.value == HTTP_UNAUTHORIZED) {
        // An expired token heals here, once, and the retry's answer is the
        // one reported — same rule as ApiClient.send. A second 401 means the
        // session is really gone, and the raw caller's own error surfaces it.
        val renewed = tokenSession.recoverFromUnauthorized(module, projectForAuth, url, token)
        if (renewed != null && renewed != token) {
            response = perform(resolvedHeaders, renewed)
        }
    }
    if (!response.status.isSuccess()) {
        ZillitLog.w(RAW_HTTP_TAG) { "${response.status.value} $url" }
    }
    return response
}

private const val RAW_HTTP_TAG = "RawHttp"

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

/** [httpStatus] null means a soft decline on an otherwise-successful response — see [declineOrBytes]. */
private class ExportDeclined(
    val httpStatus: Int?,
    val serverMessage: String?,
) : RuntimeException(serverMessage ?: "declined${httpStatus?.let { " ($it)" } ?: ""}")

private fun envelopeMessage(bytes: ByteArray): String? = runCatching {
    HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() }
