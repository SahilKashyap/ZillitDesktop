package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.headersFor
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
 */
internal suspend fun AppGraph.Ready.postForBytes(url: String, body: JsonObject): ZillitResult<ByteArray> {
    val bodyJson = HttpClientFactory.json.encodeToString(JsonElement.serializer(), body)
    val headers = headerProvider.headersFor(RequestModule.ProjectUser, bodyJson, null)
    return runCatching {
        val response = httpClient.post(url) {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
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
internal suspend fun AppGraph.Ready.getForBytes(url: String): ZillitResult<ByteArray> {
    val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null)
    return runCatching {
        val response = httpClient.get(url) { headers.forEach { (name, value) -> this.headers.append(name, value) } }
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

private class ExportDeclined(message: String) : RuntimeException(message)

private fun envelopeMessage(bytes: ByteArray): String? = runCatching {
    HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() }
