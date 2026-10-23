package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.scriptsupervision.data.ScriptSupervisionApi
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervisionViewer
import com.zillit.desktop.feature.scriptsupervision.domain.SsHost
import com.zillit.desktop.feature.scriptsupervision.domain.SsPickedFile
import com.zillit.desktop.feature.scriptsupervision.ui.ScriptSupervisionToolProvider
import com.zillit.desktop.feature.scriptsupervision.ui.ScriptSupervisionViewModel
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Continuity Script Supervision's host wiring.
 *
 * The service has no login of its own: the project Bearer rides every call,
 * and the production it is scoped to is the one the app has open — which the
 * service also checks against the token (it answers 403
 * `script_supervision_project_mismatch` when the id in the path is another
 * production's).
 *
 * The viewer is a lambda, not a value: view models are built once per graph,
 * before any production is open, so the rights row has to be re-read on every
 * start — see [ScriptSupervisionViewModel].
 */
internal fun AppGraph.Ready.buildScriptSupervision(
    permissions: () -> ProjectPermissions,
): ScriptSupervisionViewModel {
    val projectId = { projectContext?.context?.value?.project?.projectId }
    return ScriptSupervisionViewModel(
        api = ScriptSupervisionApi(apiClient, config, projectId),
        viewer = { ScriptSupervisionViewer.from(permissions()) },
        rights = rightsRequests,
        projectId = projectId,
        host = scriptSupervisionHost(),
    )
}

/** The tool's window. */
internal fun scriptSupervisionProvider(viewModel: ScriptSupervisionViewModel): ScriptSupervisionToolProvider =
    ScriptSupervisionToolProvider(viewModel)

/**
 * The three things the module cannot do for itself.
 *
 * `ApiClient` speaks only JSON envelopes, so the multipart imports and the
 * signed photo fetches need raw HTTP — and raw HTTP in this app has one rule
 * worth restating, because breaking it broke every file export once: it goes
 * through [signedRawResponse], never a bare `post`. Sent with the legacy
 * `moduledata` header alone, this service answers 401
 * `libs_moduledata_not_accepted`.
 */
private fun AppGraph.Ready.scriptSupervisionHost(): SsHost = object : SsHost {

    /**
     * A presigned S3 link, fetched with NO Zillit auth on it.
     *
     * Not a mistake and not an oversight: the signature is in the query
     * string, and S3 refuses a request that also carries an `Authorization`
     * header. So this one call is deliberately unsigned.
     */
    override suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val response = httpClient.get(url)
            if (response.status.isSuccess()) response.readRawBytes() else null
        }.getOrNull()
    }

    override fun open(url: String) = openInBrowser(url)

    override suspend fun pick(extensions: Set<String>): SsPickedFile? = pickScriptSupervisionFile(extensions)

    override suspend fun upload(
        url: String,
        file: SsPickedFile,
        fields: Map<String, String>,
    ): ZillitResult<JsonElement> = uploadScriptSupervisionFile(url, file, fields)
}

/**
 * The OS file chooser. Null is "cancelled", which the module never toasts.
 *
 * [FileDialog] rather than Swing's `JFileChooser` because it is the platform's
 * own dialog — the same choice Deal Memo's picker makes, and on macOS the only
 * one that looks native.
 */
private suspend fun pickScriptSupervisionFile(extensions: Set<String>): SsPickedFile? =
    withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, str(S.desktop_choose_a_file), FileDialog.LOAD)
        dialog.isMultipleMode = false
        if (extensions.isNotEmpty()) {
            dialog.setFilenameFilter { _, name ->
                extensions.any { name.endsWith(".$it", ignoreCase = true) }
            }
        }
        dialog.isVisible = true
        val chosen = dialog.files?.firstOrNull() ?: return@withContext null
        runCatching { readPickedFile(chosen) }.getOrNull()
    }

private fun readPickedFile(file: File): SsPickedFile? {
    val bytes = file.readBytes()
    // An empty file would upload and parse as nothing; the service's own
    // error for it is unhelpful, so it never leaves here.
    if (bytes.isEmpty()) return null
    return SsPickedFile(name = file.name, bytes = bytes, mime = mimeOf(file.name))
}

/**
 * The content type for the four formats this tool imports.
 *
 * Guessed here rather than read from the OS: `Files.probeContentType` answers
 * null for `.fdx` and `.fountain` on macOS, and the service reads the
 * extension anyway.
 */
private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    // Final Draft is XML; the rest are plain text.
    "fdx" -> "application/xml"
    "fountain", "txt" -> "text/plain"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    else -> "application/octet-stream"
}

/**
 * One file, multipart, to one of this service's own routes.
 *
 * ⚠ Signed with **no body**: the header signature covers a JSON body, and a
 * multipart call has none. The web's own interceptor does the same for its
 * `FormData` posts, and signing the bytes instead would make every import
 * 401.
 *
 * The answer is the envelope, so a 200 carrying `status: 0` is a FAILURE
 * here, as everywhere else on this service — the module's own client folds
 * that the same way, and this has to match or a refused import would read as
 * a successful one.
 */
private suspend fun AppGraph.Ready.uploadScriptSupervisionFile(
    url: String,
    file: SsPickedFile,
    fields: Map<String, String>,
): ZillitResult<JsonElement> = withContext(Dispatchers.IO) {
    runCatching {
        val response = signedRawResponse(RequestModule.ProjectUser, url, bodyJson = null) { headers, bearer ->
            httpClient.post(url) {
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                bearer?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            // Beside the file: `/upload/file` reads `folder`,
                            // and without it the file lands in the wrong
                            // place in project storage.
                            fields.forEach { (name, value) -> append(name, value) }
                            append(
                                "file",
                                file.bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, file.mime)
                                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                                },
                            )
                        },
                    ),
                )
            }
        }
        val bytes = response.readRawBytes()
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        if (!response.status.isSuccess()) {
            return@runCatching ZillitResult.Failure(
                ZillitError.Http(
                    status = response.status.value,
                    serverMessage = envelopeMessage(bytes),
                    technical = "script supervision upload",
                ),
            )
        }
        if (!isJson) {
            return@runCatching ZillitResult.Failure(
                ZillitError.Unknown("the import answered something other than an envelope"),
            )
        }
        val envelope = HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject
        // A 200 with `status: 0` is a refusal; its message key is the one the
        // reader is shown, in their own language.
        if (envelope["status"]?.jsonPrimitive?.content != "1") {
            ZillitResult.Failure(
                ZillitError.Http(
                    status = response.status.value,
                    serverMessage = envelope["message"]?.jsonPrimitive?.content,
                    technical = "script supervision upload refused",
                ),
            )
        } else {
            ZillitResult.Success(envelope["data"] ?: envelope)
        }
    }.getOrElse { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "the import could not be sent")) }
}

/** The `message` key of a failed envelope, for a non-2xx answer. */
private fun envelopeMessage(bytes: ByteArray): String? = runCatching {
    HttpClientFactory.json.parseToJsonElement(bytes.decodeToString())
        .jsonObject["message"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() }
