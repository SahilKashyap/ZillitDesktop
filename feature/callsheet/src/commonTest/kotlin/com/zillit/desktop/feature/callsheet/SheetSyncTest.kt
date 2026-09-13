package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.data.matchesProject
import com.zillit.desktop.feature.callsheet.data.syncEventOf
import com.zillit.desktop.feature.callsheet.domain.SheetComment
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.SheetSyncEvent
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.SheetDialog
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
 * Live `callsheet:*` frames: what a frame names, which production it
 * belongs to, and the targeted reload each one causes — the web's
 * `handleSocketReportUpdate` (`ProductionReportApp.jsx:1538-1662`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetSyncTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = SheetHarness(dispatcher)
    private val repository = harness.repository

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun draftLoads() = repository.queries.count { CallSheetStatus.Draft in it.statuses }

    @Test
    fun `a frame names its report and status however the server wraps it`() {
        val wrapped = Json.parseToJsonElement(
            """{"message":"ok","data":{"call_sheet":{"_id":"r1","status":"PENDING_APPROVAL"}}}""",
        )
        assertEquals(
            SheetSyncEvent("callsheet:approval:created", "r1", CallSheetStatus.PendingApproval),
            syncEventOf("callsheet:approval:created", wrapped),
        )
        val comment = Json.parseToJsonElement("""{"comment":{"_id":"c9","call_sheet_id":"r2"}}""")
        assertEquals("r2", syncEventOf("callsheet:comment:created", comment).sheetId)
        assertTrue(syncEventOf("callsheet:comment:created", comment).isComment)
        assertNull(syncEventOf("callsheet:approval:approved", null).sheetId)
    }

    @Test
    fun `only a frame naming another production is dropped`() {
        assertTrue(Json.parseToJsonElement("""{"project_id":"p1"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"project_id":"p2"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"data":{"projectId":"p2"}}""").matchesProject("p1"))
        assertTrue(null.matchesProject("p1"), "a payload-less frame must pass")
        assertTrue(
            Json.parseToJsonElement("""{"call_sheet":{}}""").matchesProject("p1"),
            "a frame naming no project must pass — the web handler never filters",
        )
    }

    @Test
    fun `a frame reloads only its list, and reopening the window never stacks a second listener`() =
        runTest(dispatcher) {
            val vm = harness.start(this)
            assertEquals(1, draftLoads(), "opening loads Drafts once")

            repository.liveEvents.emit(
                SheetSyncEvent("callsheet:submit:for:approval", "r1", CallSheetStatus.Draft),
            )
            settle()
            assertEquals(2, draftLoads(), "a draft-status frame reloads Drafts")

            vm.start()
            settle()
            assertEquals(3, draftLoads(), "reopening reloads the tab")
            repository.liveEvents.emit(
                SheetSyncEvent("callsheet:submit:for:approval", "r1", CallSheetStatus.Draft),
            )
            settle()
            assertEquals(4, draftLoads(), "the frame reloads once, not once per start")
        }

    @Test
    fun `a comment frame re-reads the open thread quietly, and only for that report`() = runTest(dispatcher) {
        repository.thread += SheetComment("c1", text = "First")
        val vm = harness.start(this)
        vm.onEvent(ListEvent.OpenComments(Samples.row("r1", "Day 1"), readOnly = false))
        settle()
        val readsBefore = repository.commentReads

        repository.liveEvents.emit(SheetSyncEvent("callsheet:comment:created", sheetId = "r2"))
        settle()
        assertEquals(readsBefore, repository.commentReads, "another report's thread is left alone")

        repository.thread += SheetComment("c2", text = "Second, from the phone")
        repository.liveEvents.emit(SheetSyncEvent("callsheet:comment:created", sheetId = "r1"))
        settle()
        assertEquals(readsBefore + 1, repository.commentReads)
        assertEquals(listOf("c1", "c2"), (vm.currentState.dialog as? SheetDialog.Comments)?.comments?.map { it.id })
        assertTrue(harness.toasts.none { it.isError })
    }
}
