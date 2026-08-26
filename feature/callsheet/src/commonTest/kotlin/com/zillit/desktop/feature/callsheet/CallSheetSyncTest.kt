package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.callsheet.data.matchesProject
import com.zillit.desktop.feature.callsheet.domain.CallSheetDelivery
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.CallSheetRepository
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.domain.CompanySeed
import com.zillit.desktop.feature.callsheet.domain.InternalApprover
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetPdfPage
import com.zillit.desktop.feature.callsheet.ui.CallSheetViewModel
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
 * A `callsheet:*` workflow frame reloads the open list exactly once — the
 * web's `handleSocketSheetUpdate` (`CallSheetApp.jsx:1631-1694`) — and a
 * frame naming another production is ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSheetSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(override val refreshes: Flow<Unit>) : CallSheetRepository {
        var listCalls = 0

        override suspend fun metadata(projectId: String) = ZillitResult.Success(SheetMetadata())
        override suspend fun saveMetadata(
            projectId: String, totalDays: String?, currentShootDay: Int?,
            finalApproverIds: List<String>?, internalReceiverIds: List<String>?,
        ) = ZillitResult.Success(Unit)
        override suspend fun defaultTemplate(): ZillitResult<SheetPayload?> =
            ZillitResult.Success(null)
        override suspend fun sheets(
            projectId: String?, statuses: List<CallSheetStatus>,
            createdById: String?, approverId: String?,
        ): ZillitResult<List<CallSheetSummary>> {
            listCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun sheet(id: String): ZillitResult<CallSheetDetail> =
            ZillitResult.Failure(ZillitError.Validation("unused"))
        override suspend fun create(
            projectId: String, name: String, payload: SheetPayload,
            createdBy: String, createdById: String,
        ): ZillitResult<CallSheetSummary> = ZillitResult.Failure(ZillitError.Validation("unused"))
        override suspend fun saveRevision(
            id: String, name: String, payload: SheetPayload, createdBy: String, createdById: String,
        ) = ZillitResult.Success(Unit)
        override suspend fun delete(id: String) = ZillitResult.Success(Unit)
        override suspend fun submitForApproval(id: String) = ZillitResult.Success(Unit)
        override suspend fun submitForInternalApproval(id: String, approvers: List<InternalApprover>) =
            ZillitResult.Success(Unit)
        override suspend fun approve(requestId: String, withoutSignature: Boolean) =
            ZillitResult.Success(Unit)
        override suspend fun reject(requestId: String, reason: String) = ZillitResult.Success(Unit)
        override suspend fun publish(
            id: String, publishedBy: String, publishedById: String, continuation: Boolean, notes: String,
        ) = ZillitResult.Success(Unit)
    }

    private object NoDelivery : CallSheetDelivery {
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
            val model = CallSheetViewModel(
                repository = repository,
                delivery = NoDelivery,
                resolveViewer = { CallSheetViewer(userId = "u1", canPost = true, ready = true) },
                projectId = { "p1" },
                membersProvider = { emptyList() },
                companySeed = { CompanySeed() },
                todayMs = { 0L },
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
        assertTrue(Json.parseToJsonElement("""{"project_id":"p1","status":"draft"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"project_id":"p2"}""").matchesProject("p1"))
        assertTrue(null.matchesProject("p1"), "a payload-less frame must pass")
        assertTrue(
            Json.parseToJsonElement("""{"call_sheet":{}}""").matchesProject("p1"),
            "a frame naming no project must pass — the web handler never filters",
        )
    }
}
