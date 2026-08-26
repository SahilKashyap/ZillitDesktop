package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.productionreport.data.matchesProject
import com.zillit.desktop.feature.productionreport.domain.InternalApprover
import com.zillit.desktop.feature.productionreport.domain.PublishedCallSheetLookup
import com.zillit.desktop.feature.productionreport.domain.ReportDelivery
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportRepository
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.SheetPdfPage
import com.zillit.desktop.feature.productionreport.ui.ReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `productionreport:*` workflow frame reloads the open list exactly once
 * — the web's `handleSocketReportUpdate` (`ProductionReportApp.jsx:958-
 * 1048`) — and a frame naming another production is ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(override val refreshes: Flow<Unit>) : ReportRepository {
        var listCalls = 0

        override suspend fun metadata(projectId: String) = ZillitResult.Success(SheetMetadata())
        override suspend fun saveMetadata(
            projectId: String, totalDays: String?, currentShootDay: Int?,
            finalApproverIds: List<String>?, internalReceiverIds: List<String>?,
        ) = ZillitResult.Success(Unit)
        override suspend fun defaultTemplate(): ZillitResult<SheetPayload?> =
            ZillitResult.Success(null)
        override suspend fun sheets(
            projectId: String?, statuses: List<ReportStatus>,
            createdById: String?, approverId: String?,
        ): ZillitResult<List<ReportSummary>> {
            listCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun sheet(id: String): ZillitResult<ReportDetail> =
            ZillitResult.Failure(ZillitError.Validation("unused"))
        override suspend fun create(
            projectId: String, name: String, payload: SheetPayload,
            createdBy: String, createdById: String,
        ): ZillitResult<ReportSummary> = ZillitResult.Failure(ZillitError.Validation("unused"))
        override suspend fun saveRevision(
            id: String, name: String, payload: SheetPayload, createdBy: String, createdById: String,
        ) = ZillitResult.Success(Unit)
        override suspend fun delete(id: String) = ZillitResult.Success(Unit)
        override suspend fun submitForApproval(
            id: String, approvers: List<InternalApprover>, createdBy: String,
        ) = ZillitResult.Success(Unit)
        override suspend fun submitForInternalApproval(
            id: String, approvers: List<InternalApprover>, createdBy: String,
        ) = ZillitResult.Success(Unit)
        override suspend fun approve(requestId: String, withoutSignature: Boolean) =
            ZillitResult.Success(Unit)
        override suspend fun reject(requestId: String, reason: String) = ZillitResult.Success(Unit)
        override suspend fun publish(
            id: String, publishedBy: String, publishedById: String, continuation: Boolean, notes: String,
        ) = ZillitResult.Success(Unit)
    }

    private object NoDelivery : ReportDelivery {
        override suspend fun pdf(sheetId: String) = ZillitResult.Success(ByteArray(0))
        override fun renderPages(pdf: ByteArray, targetWidthPx: Int) =
            ZillitResult.Success(emptyList<SheetPdfPage>())
        override suspend fun distribute(
            sheetId: String, serialNo: String, pdf: ByteArray, replacePrevious: Boolean,
        ) = ZillitResult.Success(Unit)
    }

    @Test
    fun `an emitted event reloads the open list once, and a second start does not stack`() =
        runTest(dispatcher) {
            val events = MutableSharedFlow<Unit>()
            val repository = FakeRepository(events)
            val model = ReportViewModel(
                repository = repository,
                delivery = NoDelivery,
                callSheets = PublishedCallSheetLookup { null },
                resolveViewer = { ReportViewer(userId = "u1", canPost = true, ready = true) },
                projectId = { "p1" },
                membersProvider = { emptyList() },
                todayYmd = { "2026-01-01" },
            )

            model.start()
            runCurrent()
            assertEquals(1, repository.listCalls, "start loads once")

            events.emit(Unit)
            runCurrent()
            assertEquals(2, repository.listCalls, "the event reloads")

            model.start() // the window reopening must not add a second collector
            runCurrent()
            events.emit(Unit)
            runCurrent()
            assertEquals(4, repository.listCalls, "start reloads, the event reloads ONCE")
        }

    @Test
    fun `only a frame naming another production is dropped`() {
        assertTrue(Json.parseToJsonElement("""{"project_id":"p1"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"project_id":"p2"}""").matchesProject("p1"))
        assertTrue(null.matchesProject("p1"), "a payload-less frame must pass")
        assertTrue(
            Json.parseToJsonElement("""{"production_report":{}}""").matchesProject("p1"),
            "a frame naming no project must pass — the web handler never filters",
        )
    }
}
