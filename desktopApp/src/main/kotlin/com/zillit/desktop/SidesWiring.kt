package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.sides.domain.SidesPdfPage
import com.zillit.desktop.feature.sides.domain.SidesTransfer
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import java.util.UUID

/**
 * Sides' host seams: uploads on the app's S3 machinery, signed-URL fetches on
 * the bare client (a presigned URL must NOT carry the API's encrypted
 * headers), rendering on the documents tool's PDFBox.
 */
internal fun AppGraph.Ready.sidesTransfer(): SidesTransfer = object : SidesTransfer {

    private val work = PdfBoxWork()

    private val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            val remote = remoteConfigRepository.current()
            val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
            val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
            if (access != null && secret != null) AwsCredentials(access, secret) else null
        },
        storage = storageTarget,
        newKey = { fileName ->
            "sides/${UUID.randomUUID()}/${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        },
    )

    override suspend fun upload(fileName: String, bytes: ByteArray): ZillitResult<StoredAttachment> =
        when (val stored = uploader.upload(fileName, "application/pdf", bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                StoredAttachment(
                    media = stored.data.media,
                    name = fileName,
                    bucket = stored.data.bucket,
                    region = stored.data.region,
                    fileSizeBytes = bytes.size.toLong(),
                ),
            )
        }

    override suspend fun fetch(url: String): ZillitResult<ByteArray> = runCatching {
        val response = httpClient.get(url)
        check(response.status.isSuccess()) { "signed fetch answered ${response.status}" }
        response.readRawBytes()
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "fetch failed")) },
    )

    override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SidesPdfPage>> =
        when (val pages = work.renderPages(pdf, targetWidthPx)) {
            is ZillitResult.Failure -> pages
            is ZillitResult.Success -> ZillitResult.Success(
                pages.data.map { page ->
                    SidesPdfPage(
                        page = page.page,
                        imageBytes = page.imageBytes,
                        widthPx = page.widthPx,
                        heightPx = page.heightPx,
                    )
                },
            )
        }
}

/**
 * The raw authed GET the scenes route needs: it answers bare JSON, which the
 * enveloped ApiClient would refuse as a missing `data` field.
 */
internal fun AppGraph.Ready.sidesRawGet(): suspend (String) -> ZillitResult<String> = { url ->
    runCatching {
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null)
        val response = httpClient.get(url) {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
        }
        check(response.status.isSuccess()) { "scene fetch answered ${response.status}" }
        response.bodyAsText()
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "scene fetch failed")) },
    )
}
