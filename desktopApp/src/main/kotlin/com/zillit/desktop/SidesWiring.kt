package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.S3Presigner
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.sides.domain.SidesPdfPage
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.domain.SidesTransfer
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import com.zillit.desktop.feature.sides.ui.PickedDoc
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

/**
 * Sides' host seams: uploads on the app's S3 machinery, signed-URL fetches on
 * the bare client (a presigned URL must NOT carry the API's encrypted
 * headers), call-sheet previews presigned client-side (the sides service has
 * no call-sheet download route), rendering on the documents tool's PDFBox.
 */
internal fun AppGraph.Ready.sidesTransfer(): SidesTransfer = object : SidesTransfer {

    private val work = PdfBoxWork()

    private val presigner = S3Presigner(credentials = { awsKeyPair(remoteConfigRepository) })

    private val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            awsKeyPair(remoteConfigRepository)?.let { (access, secret) -> AwsCredentials(access, secret) }
        },
        storage = storageTarget,
        newKey = { fileName ->
            "sides/${UUID.randomUUID()}/${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        },
    )

    override suspend fun upload(fileName: String, bytes: ByteArray): ZillitResult<StoredAttachment> {
        val subtype = SidesRules.contentSubtype(fileName)
        val contentType = if (subtype == "fdx") "application/xml" else "application/pdf"
        return when (val stored = uploader.upload(fileName, contentType, bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                StoredAttachment(
                    media = stored.data.media,
                    name = fileName,
                    bucket = stored.data.bucket,
                    region = stored.data.region,
                    fileSizeBytes = bytes.size.toLong(),
                    contentSubtype = subtype,
                ),
            )
        }
    }

    override suspend fun fetch(url: String): ZillitResult<ByteArray> = runCatching {
        val response = httpClient.get(url)
        check(response.status.isSuccess()) { "signed fetch answered ${response.status}" }
        response.readRawBytes()
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "fetch failed")) },
    )

    override suspend fun presign(attachment: StoredAttachment): ZillitResult<String> {
        if (attachment.isBlank) return ZillitResult.Failure(ZillitError.Unknown("This call sheet has no file"))
        // A row without its own bucket/region lives in the production's storage.
        val fallback = (storageTarget.target() as? ZillitResult.Success)?.data
        val bucket = attachment.bucket.ifBlank { fallback?.bucket.orEmpty() }
        val region = attachment.region.ifBlank { fallback?.region.orEmpty() }
        val url = presigner.presignedGet(bucket = bucket, region = region, key = attachment.media)
            ?: return ZillitResult.Failure(ZillitError.Unknown("No file storage is configured for this production"))
        return ZillitResult.Success(url)
    }

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
 * The raw authed GET the scenes routes need: they answer bare JSON, which the
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

/** A PDF, or PDF/Final Draft, picker for the sides forms. */
internal suspend fun pickSidesDocument(pdfOnly: Boolean): PickedDoc? = withContext(Dispatchers.IO) {
    val title = if (pdfOnly) str(S.desktop_choose_pdf) else str(S.desktop_choose_pdf_or_fdx)
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> if (pdfOnly) SidesRules.isPdf(name) else SidesRules.isPdfOrFdx(name) }
    dialog.isVisible = true
    val file = dialog.files.orEmpty().firstOrNull { it.isFile } ?: return@withContext null
    if (file.length() > MAX_SIDES_DOC_BYTES) return@withContext null
    runCatching { PickedDoc(name = file.name, bytes = file.readBytes()) }.getOrNull()
}

/** The save-to-disk half of a download: a native save dialog seeded with the suggested name. */
internal suspend fun saveSidesFile(fileName: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, str(S.desktop_save_sides), FileDialog.SAVE)
    dialog.file = fileName
    dialog.isVisible = true
    val directory = dialog.directory ?: return@withContext
    val chosen = dialog.file ?: return@withContext
    runCatching { File(directory, chosen).writeBytes(bytes) }
}

private const val MAX_SIDES_DOC_BYTES = 100L * 1024 * 1024
