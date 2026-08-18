package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.feature.callsheet.domain.CallSheetDelivery
import com.zillit.desktop.feature.callsheet.domain.SheetPdfPage
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.domain.HomeUnitKind
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import java.util.UUID

/**
 * Call Sheet's host seams.
 *
 * The PDF is fetched raw — the service streams `application/pdf`, which the
 * enveloped [com.zillit.desktop.core.network.ApiClient] would try to parse as
 * JSON — and rendering rides the documents tool's PDFBox. Publish fan-out
 * posts the rendered PDF into the call-sheet home unit, which is the web's
 * behaviour minus its `replacePreviousChats` flag: the desktop appends, it
 * never deletes what a phone pinned.
 */
internal fun AppGraph.Ready.callSheetDelivery(): CallSheetDelivery = object : CallSheetDelivery {

    private val work = PdfBoxWork()
    private val base = config.apiV2(ZillitService.CallSheet).trimEnd('/')

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
            "callsheet/${UUID.randomUUID()}/${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        },
    )

    override suspend fun pdf(sheetId: String): ZillitResult<ByteArray> = runCatching {
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null)
        val response = httpClient.get("$base/call-sheets/$sheetId/pdf") {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
        }
        check(response.status.isSuccess()) { "PDF fetch answered ${response.status}" }
        response.readRawBytes()
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "PDF fetch failed")) },
    )

    override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SheetPdfPage>> =
        when (val pages = work.renderPages(pdf, targetWidthPx)) {
            is ZillitResult.Failure -> pages
            is ZillitResult.Success -> ZillitResult.Success(
                pages.data.map { page ->
                    SheetPdfPage(
                        page = page.page,
                        imageBytes = page.imageBytes,
                        widthPx = page.widthPx,
                        heightPx = page.heightPx,
                    )
                },
            )
        }

    override suspend fun distribute(
        sheetId: String,
        serialNo: String,
        pdf: ByteArray,
        replacePrevious: Boolean,
    ): ZillitResult<Unit> {
        val fileName = "CallSheet_${serialNo.ifBlank { sheetId }}.pdf"
        val stored = when (val upload = uploader.upload(fileName, "application/pdf", pdf)) {
            is ZillitResult.Failure -> return upload
            is ZillitResult.Success -> upload.data
        }
        val unit = when (val units = homeFeedRepository.loadUnits()) {
            is ZillitResult.Failure -> return units
            is ZillitResult.Success -> units.data.firstOrNull { it.kind == HomeUnitKind.CallSheet }
        } ?: return ZillitResult.Success(Unit) // No call-sheet unit: nothing to fan out to.

        return homeFeedRepository.postNotice(
            unitId = unit.id,
            text = "",
            localId = UUID.randomUUID().toString(),
            attachment = UploadedNoticeMedia(
                kind = NoticeKind.Document,
                media = stored.media,
                bucket = stored.bucket,
                region = stored.region,
                fileName = fileName,
                contentType = "application/pdf",
                sizeBytes = pdf.size.toLong(),
            ),
        ).let { posted ->
            when (posted) {
                is ZillitResult.Failure -> posted
                is ZillitResult.Success -> ZillitResult.Success(Unit)
            }
        }
    }
}
