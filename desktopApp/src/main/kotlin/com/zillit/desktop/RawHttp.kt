package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.network.headersFor
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
        val bytes = response.readRawBytes()
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        when {
            !response.status.isSuccess() -> throw ExportDeclined(
                "Export answered ${response.status.value}" + (envelopeMessage(bytes)?.let { ": $it" } ?: ""),
            )
            isJson -> throw ExportDeclined(envelopeMessage(bytes) ?: "The service returned no file")
            else -> bytes
        }
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "Export failed")) },
    )
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
        val bytes = response.readRawBytes()
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        when {
            !response.status.isSuccess() -> throw ExportDeclined(
                "Request answered ${response.status.value}" + (envelopeMessage(bytes)?.let { ": $it" } ?: ""),
            )
            isJson -> throw ExportDeclined(envelopeMessage(bytes) ?: "The service returned no file")
            else -> bytes
        }
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "Download failed")) },
    )
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
 */
internal suspend fun AppGraph.Ready.signedRawResponse(
    module: RequestModule,
    url: String,
    bodyJson: String?,
    perform: suspend (headers: Map<String, String>, bearer: String?) -> HttpResponse,
): HttpResponse {
    val projectForAuth = projectContext?.context?.value?.project?.projectId
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
    return response
}

private const val HTTP_UNAUTHORIZED = 401

private class ExportDeclined(message: String) : RuntimeException(message)

private fun envelopeMessage(bytes: ByteArray): String? = runCatching {
    HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() }
