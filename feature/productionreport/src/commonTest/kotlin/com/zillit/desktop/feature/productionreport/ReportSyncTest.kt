package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.data.matchesProject
import com.zillit.desktop.feature.productionreport.data.syncEventOf
import com.zillit.desktop.feature.productionreport.domain.ReportComment
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSyncEvent
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.ReportDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live `productionreport:*` frames: what a frame names, which production it
 * belongs to, and the targeted reload each one causes — the web's
 * `handleSocketReportUpdate` (`ProductionReportApp.jsx:1538-1662`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportSyncTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = ReportHarness(dispatcher)
    private val repository = harness.repository

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun draftLoads() = repository.queries.count { ReportStatus.Draft in it.statuses }

    @Test
    fun `a frame names its report and status however the server wraps it`() {
        val wrapped = Json.parseToJsonElement(
            """{"message":"ok","data":{"production_report":{"_id":"r1","status":"PENDING_APPROVAL"}}}""",
        )
        assertEquals(
            ReportSyncEvent("productionreport:approval:created", "r1", ReportStatus.PendingApproval),
            syncEventOf("productionreport:approval:created", wrapped),
        )
        val comment = Json.parseToJsonElement("""{"comment":{"_id":"c9","production_report_id":"r2"}}""")
        assertEquals("r2", syncEventOf("productionreport:comment:created", comment).reportId)
        assertTrue(syncEventOf("productionreport:comment:created", comment).isComment)
        assertNull(syncEventOf("productionreport:approval:approved", null).reportId)
    }

    @Test
    fun `only a frame naming another production is dropped`() {
        assertTrue(Json.parseToJsonElement("""{"project_id":"p1"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"project_id":"p2"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"data":{"projectId":"p2"}}""").matchesProject("p1"))
        assertTrue(null.matchesProject("p1"), "a payload-less frame must pass")
        assertTrue(
            Json.parseToJsonElement("""{"production_report":{}}""").matchesProject("p1"),
            "a frame naming no project must pass — the web handler never filters",
        )
    }

    @Test
    fun `a frame reloads only its list, and reopening the window never stacks a second listener`() =
        runTest(dispatcher) {
            val vm = harness.start(this)
            assertEquals(1, draftLoads(), "opening loads Drafts once")

            repository.liveEvents.emit(
                ReportSyncEvent("productionreport:submit:for:approval", "r1", ReportStatus.Draft),
            )
            settle()
            assertEquals(2, draftLoads(), "a draft-status frame reloads Drafts")

            vm.start()
            settle()
            assertEquals(3, draftLoads(), "reopening reloads the tab")
            repository.liveEvents.emit(
                ReportSyncEvent("productionreport:submit:for:approval", "r1", ReportStatus.Draft),
            )
            settle()
            assertEquals(4, draftLoads(), "the frame reloads once, not once per start")
        }

    @Test
    fun `a comment frame re-reads the open thread quietly, and only for that report`() = runTest(dispatcher) {
        repository.thread += ReportComment("c1", text = "First")
        val vm = harness.start(this)
        vm.onEvent(ListEvent.OpenComments(Samples.row("r1", "Day 1"), readOnly = false))
        settle()
        val readsBefore = repository.commentReads

        repository.liveEvents.emit(ReportSyncEvent("productionreport:comment:created", reportId = "r2"))
        settle()
        assertEquals(readsBefore, repository.commentReads, "another report's thread is left alone")

        repository.thread += ReportComment("c2", text = "Second, from the phone")
        repository.liveEvents.emit(ReportSyncEvent("productionreport:comment:created", reportId = "r1"))
        settle()
        assertEquals(readsBefore + 1, repository.commentReads)
        assertEquals(listOf("c1", "c2"), (vm.currentState.dialog as? ReportDialog.Comments)?.comments?.map { it.id })
        assertTrue(harness.toasts.none { it.isError })
    }
}
