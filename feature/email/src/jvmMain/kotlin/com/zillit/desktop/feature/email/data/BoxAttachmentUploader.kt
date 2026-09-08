package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.domain.BoxSettings
import com.zillit.desktop.feature.email.domain.StoredFile
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads attachments to Box.
 *
 * Some productions are on Box rather than S3 — chosen per production and only
 * known once one is open, which is why [com.zillit.desktop.feature.email.domain.RoutingAttachmentUploader]
 * exists.
 *
 * The wire format is Box's own: a multipart `POST` where `attributes` is a JSON
 * blob naming the file and its parent folder, and `file_data` carries the bytes.
 */
class BoxAttachmentUploader(
    private val httpClient: HttpClient,
    private val settings: () -> BoxSettings?,
    private val tokens: BoxTokenSource,
    private val newFileName: (String) -> String = ::boxFileName,
) : AttachmentUploader {

    override suspend fun upload(
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: (Int) -> Unit,
    ): ZillitResult<StoredFile> = withContext(Dispatchers.IO) {
        val box = settings()?.takeIf { it.isUsable } ?: return@withContext ZillitResult.Failure(
            ZillitError.Storage(
                technical = "no Box enterprise id on the open project",
                userMessage = "Attachments are unavailable — this project has no file storage configured.",
            ),
        )

        // Fetched per upload: Box tokens expire in about an hour and the
        // response does not say when, so a cached one fails at the worst moment.
        val token = when (val minted = tokens.token(box.enterpriseClientId)) {
            is ZillitResult.Failure -> return@withContext minted
            is ZillitResult.Success -> minted.data
        }

        onProgress(0)
        send(token, box, newFileName(fileName), fileName, contentType, bytes)
            .also { if (it is ZillitResult.Success) onProgress(PERCENT) }
    }

    private suspend fun send(
        token: String,
        box: BoxSettings,
        storedName: String,
        originalName: String,
        contentType: String,
        bytes: ByteArray,
    ): ZillitResult<StoredFile> = try {
        val response = httpClient.post(UPLOAD_URL) {
            header(HttpHeaders.Authorization, "Bearer $token")
            // No per-byte progress here yet: Ktor's `onUpload` observer hangs
            // the request on this engine (see S3AttachmentUploader, which
            // streams its own body instead — harder for a multipart POST), so
            // Box senders get the honest jump rather than a frozen request.
            setBody(
                MultiPartFormDataContent(
                    formData {
                        // Box reads the name and destination from this JSON
                        // part, not from the file part's headers.
                        append(
                            "attributes",
                            """{"name":"$storedName","parent":{"id":"${box.folderId}"}}""",
                        )
                        append(
                            "file_data",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, contentType)
                                append(
                                    HttpHeaders.ContentDisposition,
                                    """filename="$storedName"""",
                                )
                            },
                        )
                    },
                ),
            )
        }

        if (!response.status.isSuccess()) {
            ZillitLog.w(TAG) { "box upload rejected: ${response.status.value}" }
            ZillitResult.Failure(
                ZillitError.Http(response.status.value, "Could not upload $originalName."),
            )
        } else {
            ZillitResult.Success(
                StoredFile(
                    media = storedName,
                    // Box has no bucket or region. Left blank rather than
                    // invented: the mail server keys off the storage type, and
                    // a made-up bucket would be a plausible-looking lie.
                    bucket = "",
                    region = "",
                    fileName = originalName,
                    contentType = contentType,
                    sizeBytes = bytes.size.toLong(),
                ),
            )
        }
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        // The file name is not logged: it is the sender's business.
        ZillitLog.e(TAG, throwable) { "box upload failed" }
        ZillitResult.Failure(ZillitError.NoConnection(throwable::class.simpleName))
    }

    private companion object {
        const val TAG = "Email"
        const val PERCENT = 100
        const val UPLOAD_URL = "https://upload.box.com/api/2.0/files/content"
    }
}

/**
 * The stored name.
 *
 * `file_zillit_<uuid>.<ext>`, matching the web exactly — the extension is what
 * tells Box and the recipient's client what the file is, and a name collision
 * inside a shared folder would otherwise reject the upload.
 */
private fun boxFileName(original: String): String {
    val extension = original.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= MAX_EXT }
    return "file_zillit_${java.util.UUID.randomUUID()}" + extension?.let { ".$it" }.orEmpty()
}

private const val MAX_EXT = 10
