package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.callsheet.domain.CallSheetRepository
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.productionreport.domain.PublishedCallSheetLookup
import com.zillit.desktop.feature.productionreport.domain.ReportDelivery
import com.zillit.desktop.feature.productionreport.domain.SheetPdfPage
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess

/**
 * Production Report's host seams.
 *
 * The PDF path mirrors the call sheet's; the chat fan-out does not exist here
 * yet — the report's unit chat rides its own legacy resource, and a report is
 * fully published without it — so [ReportDelivery.distribute] is a recorded
 * no-op rather than a half-port.
 */
internal fun AppGraph.Ready.productionReportDelivery(): ReportDelivery = object : ReportDelivery {

    private val work = PdfBoxWork()
    private val base = config.apiV2(ZillitService.ProductionReport).trimEnd('/')

    override suspend fun pdf(sheetId: String): ZillitResult<ByteArray> = runCatching {
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null)
        val response = httpClient.get("$base/production-reports/$sheetId/pdf") {
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
    ): ZillitResult<Unit> = ZillitResult.Success(Unit)
}

/**
 * The report's seed source: the newest PUBLISHED call sheet, read through the
 * call-sheet module and re-parsed into the report module's payload type via
 * the wire shape both share.
 */
internal fun productionReportCallSheets(
    callSheets: CallSheetRepository,
): PublishedCallSheetLookup = PublishedCallSheetLookup { projectId ->
    val published = when (val sheets = callSheets.sheets(projectId, listOf(CallSheetStatus.Published))) {
        is ZillitResult.Failure -> return@PublishedCallSheetLookup null
        is ZillitResult.Success -> sheets.data
    }
    val newest = published.maxByOrNull { it.publishedAt.ifBlank { it.updatedAt } }
        ?: return@PublishedCallSheetLookup null
    when (val detail = callSheets.sheet(newest.id)) {
        is ZillitResult.Failure -> null
        is ZillitResult.Success ->
            com.zillit.desktop.feature.productionreport.data.PayloadWire.parse(
                com.zillit.desktop.feature.callsheet.data.PayloadWire.emit(detail.data.payload),
            )
    }
}
