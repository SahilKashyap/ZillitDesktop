package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.domain.SignFileTransfer
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.domain.UploadPurpose
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import java.awt.FileDialog
import java.awt.Frame
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Documents & Signature's storage seam, assembled from what the app already
 * owns: the email module's S3 uploader for writes, the notice reader for
 * reads. This tool never learns where the bytes live.
 *
 * Two key shapes, per the web:
 *
 *  - documents go under a fresh `sign-document/{uuid}/…` key — the server
 *    stores the descriptor verbatim, so the shape only has to be collision
 *    free;
 *  - signature PNGs go under `{projectId}/sign/…`, the exact prefix the web
 *    writes, because a signature is long-lived reference data other clients
 *    may resolve by convention.
 *
 * AWS only, like the drive's uploader: productions on Box storage will
 * refuse the upload with the credentials error rather than misroute it.
 */
internal fun AppGraph.Ready.formSignatureTransfer(): SignFileTransfer {
    val credentials: suspend () -> AwsCredentials? = {
        val remote = remoteConfigRepository.current()
        val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
        val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
        if (access != null && secret != null) AwsCredentials(access, secret) else null
    }

    val documentUploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName -> "sign-document/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    val signatureUploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName ->
            val project = projectContext?.context?.value?.project?.projectId.orEmpty()
                .ifBlank { "unknown-project" }
            "$project/sign/${fileName.safeKeyPart()}"
        },
    )

    return object : SignFileTransfer {
        override suspend fun store(
            purpose: UploadPurpose,
            fileName: String,
            contentType: String,
            bytes: ByteArray,
        ): ZillitResult<StoredDocument> {
            val uploader = when (purpose) {
                UploadPurpose.Document -> documentUploader
                UploadPurpose.SignatureImage -> signatureUploader
            }
            return when (val stored = uploader.upload(fileName, contentType, bytes)) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> ZillitResult.Success(
                    StoredDocument(
                        media = stored.data.media,
                        thumbnail = stored.data.media,
                        bucket = stored.data.bucket,
                        region = stored.data.region,
                        name = fileName,
                    ),
                )
            }
        }

        override suspend fun fetch(document: StoredDocument): ZillitResult<ByteArray> =
            noticeMedia.fetch(
                NoticeAttachment(
                    media = document.media,
                    fileName = document.name,
                    bucket = document.bucket.takeIf { it.isNotBlank() },
                    region = document.region.takeIf { it.isNotBlank() },
                ),
                preview = false,
            )
    }
}

/** A single-PDF picker for the send and library-upload flows. */
internal suspend fun pickPdf(): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, "Choose a PDF", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".pdf", ignoreCase = true) }
    dialog.isVisible = true
    val file = dialog.files.orEmpty().firstOrNull { it.isFile } ?: return@withContext null
    if (file.length() > MAX_PDF_BYTES) {
        // Refused here rather than after a doomed upload: the web caps its
        // documents well below this, and a 200 MB "contract" is a mistake.
        return@withContext null
    }
    runCatching { file.name to file.readBytes() }.getOrNull()
}

private fun String.safeKeyPart(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_KEY_NAME)

private const val MAX_KEY_NAME = 120
private const val MAX_PDF_BYTES = 100L * 1024 * 1024
