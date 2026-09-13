package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestHeaderProvider
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.core.session.ProjectContextLoader
import com.zillit.desktop.core.remoteconfig.RemoteConfigRepository
import com.zillit.desktop.feature.documentdistribution.domain.DocDistHost
import com.zillit.desktop.feature.documentdistribution.domain.DocDistSignature
import com.zillit.desktop.feature.documentdistribution.domain.DocDistTransfer
import com.zillit.desktop.feature.documentdistribution.domain.DocumentStorage
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.email.domain.SignatureRepository
import com.zillit.desktop.feature.email.domain.StorageTargetSource
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Document Distribution's byte-level seams on the app's machinery: storage
 * PUTs signed with the workspace's AWS keys, the signed S3 fetch, and the
 * doc-dist routes that answer a file rather than an envelope — which need
 * the encrypted headers on a raw HTTP call, since `ApiClient` only speaks
 * envelopes.
 */
internal class AppDocDistTransfer(
    private val storageClient: HttpClient,
    private val headerProvider: RequestHeaderProvider,
    private val credentials: suspend () -> Pair<String, String>?,
    private val storage: StorageTargetSource,
    private val noticeMedia: NoticeMediaSource,
) : DocDistTransfer {

    override suspend fun putObject(key: String, contentType: String, bytes: ByteArray): ZillitResult<DocumentStorage> {
        val uploader = S3AttachmentUploader(
            httpClient = storageClient,
            credentials = { credentials()?.let { (access, secret) -> AwsCredentials(access, secret) } },
            storage = storage,
            // The repository chose the key — `document-distribution/{uuid}/{name}`, Android's shape.
            newKey = { key },
        )
        return when (val stored = uploader.upload(key.substringAfterLast('/'), contentType, bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                DocumentStorage(key = stored.data.media, bucket = stored.data.bucket, region = stored.data.region),
            )
        }
    }

    override suspend fun fetchObject(storage: DocumentStorage): ZillitResult<ByteArray> =
        noticeMedia.fetch(
            NoticeAttachment(media = storage.key, bucket = storage.bucket, region = storage.region),
            preview = false,
        )

    override suspend fun getBytes(url: String): ZillitResult<ByteArray> = rawCall {
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null)
        storageClient.get(url) { headers.forEach { (name, value) -> this.headers.append(name, value) } }
    }

    override suspend fun postBytes(url: String, body: JsonObject): ZillitResult<ByteArray> = rawCall {
        val bodyJson = HttpClientFactory.json.encodeToString(JsonElement.serializer(), body)
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, bodyJson, null)
        storageClient.post(url) {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }
    }

    /**
     * The LOCAL-storage upload: the bytes multipart to the service. The
     * body hash covers a JSON body, so a multipart call rides without one —
     * the web's interceptor does the same for its `FormData` posts.
     */
    override suspend fun postMultipart(
        url: String,
        fields: Map<String, String>,
        file: LocalFile,
    ): ZillitResult<JsonElement> {
        val bytes = rawCall {
            val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null)
            storageClient.post(url) {
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            fields.forEach { (name, value) -> append(name, value) }
                            append(
                                "file",
                                file.bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, file.contentType)
                                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                                },
                            )
                        },
                    ),
                )
            }
        }
        return when (bytes) {
            is ZillitResult.Failure -> bytes
            is ZillitResult.Success -> runCatching {
                val envelope = HttpClientFactory.json.parseToJsonElement(bytes.data.decodeToString()).jsonObject
                if (envelope["status"]?.jsonPrimitive?.content == "0") {
                    throw Declined(envelope["message"]?.jsonPrimitive?.content ?: "The upload was refused")
                }
                envelope["data"] ?: envelope
            }.fold(
                onSuccess = { ZillitResult.Success(it) },
                onFailure = {
                    ZillitResult.Failure(ZillitError.Unknown(it.message ?: "The upload answered nothing usable"))
                },
            )
        }
    }

    /**
     * A signed call whose answer should be a file. A JSON body on the wire
     * means the service declined — "nothing to stamp", a permission key —
     * and its `message` is surfaced instead of bytes.
     */
    private suspend fun rawCall(call: suspend () -> HttpResponse): ZillitResult<ByteArray> = runCatching {
        val response = call()
        val bytes = response.readRawBytes()
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        when {
            !response.status.isSuccess() -> throw Declined(
                "The service answered ${response.status.value}" + (envelopeMessage(bytes)?.let { ": $it" } ?: ""),
            )
            isJson && envelopeMessage(bytes) != null && bytes.size < MAX_ENVELOPE_BYTES ->
                throw Declined(envelopeMessage(bytes) ?: "The service returned no file")
            else -> bytes
        }
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { failure ->
            ZillitLog.w(TAG) { "raw call failed: ${failure.message}" }
            ZillitResult.Failure(ZillitError.Unknown(failure.message ?: "The request failed"))
        },
    )

    private fun envelopeMessage(bytes: ByteArray): String? = runCatching {
        HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }?.replace('_', ' ')

    private class Declined(message: String) : RuntimeException(message)

    private companion object {
        const val TAG = "DocDist"

        /** A refusal is a few hundred bytes; a real JSON file (a vCard is not) is bigger. */
        const val MAX_ENVELOPE_BYTES = 4096
    }
}

/**
 * What Document Distribution asks the machine for: the OS file dialog,
 * PDFBox for the preview, Downloads for saved copies, the clipboard, and
 * the mail service's signatures.
 */
internal class AppDocDistHost(
    private val signatures: SignatureRepository,
) : DocDistHost {

    private val picker = AwtAttachmentPicker()
    private val pdf = PdfBoxWork()

    override suspend fun pickFiles(): List<LocalFile> =
        picker.pick(PreviewKind.Document, multiple = true, maxBytes = MAX_UPLOAD_BYTES)
            .map { LocalFile(name = it.name, contentType = it.contentType, bytes = it.bytes) }

    override fun renderPdfPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<ByteArray>> =
        when (val pages = this.pdf.renderPages(pdf, targetWidthPx)) {
            is ZillitResult.Failure -> pages
            is ZillitResult.Success -> ZillitResult.Success(pages.data.map { it.imageBytes })
        }

    override suspend fun saveToDownloads(fileName: String, bytes: ByteArray): ZillitResult<String> =
        DownloadsAttachmentStore().save(fileName, bytes)

    override fun openFile(path: String) = openSavedFile(path)

    override fun copyToClipboard(text: String) = copyTextToClipboard(text)

    override suspend fun signatures(): List<DocDistSignature> =
        (signatures.signatures() as? ZillitResult.Success)?.data.orEmpty().map {
            DocDistSignature(id = it.id, title = it.title, bodyHtml = it.body, useForNew = it.useForNew)
        }

    private companion object {
        /** Well past the 25 MB send cap; a picker that admits a 2 GB file hangs before refusing it. */
        const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024
    }
}

/** The transfer over the app's storage pieces; a function so the graph's constructor stays one line. */
internal fun docDistTransfer(
    storageClient: HttpClient,
    headerProvider: RequestHeaderProvider,
    remoteConfig: RemoteConfigRepository,
    storage: StorageTargetSource,
    noticeMedia: NoticeMediaSource,
): DocDistTransfer = AppDocDistTransfer(
    storageClient = storageClient,
    headerProvider = headerProvider,
    credentials = { awsKeyPair(remoteConfig) },
    storage = storage,
    noticeMedia = noticeMedia,
)

/** Anything but `LOCAL` stores in S3 — the phones' rule. */
internal fun ProjectContextLoader?.docDistUsesS3(): Boolean =
    this?.context?.value?.project?.storageType?.equals("LOCAL", ignoreCase = true) != true
