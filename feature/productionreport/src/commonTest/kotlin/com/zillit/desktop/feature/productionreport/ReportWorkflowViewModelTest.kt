package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.productionreport.domain.ApprovalDecision
import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.ReportChatOpener
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportQuery
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReviewAssignee
import com.zillit.desktop.feature.productionreport.domain.SharedHeader
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.ui.ConfirmAction
import com.zillit.desktop.feature.productionreport.ui.DialogEvent
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.PublishDestination
import com.zillit.desktop.feature.productionreport.ui.ReportDialog
import com.zillit.desktop.feature.productionreport.ui.Workspace
import com.zillit.desktop.feature.productionreport.ui.WorkflowEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lists and the review workflow, driven through the view model the way
 * the screen drives it: what reaches the service, and what the author sees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportWorkflowViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = ReportHarness(dispatcher)
    private val repository = harness.repository
    private val toasts = harness.toasts

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun drafts(vararg ids: String): (ReportQuery) -> List<ReportSummary> =
        { query -> if (ReportStatus.Draft in query.statuses) ids.map { Samples.row(it, "Day $it") } else emptyList() }

    private fun awaitingMySignature() = Samples.row(
        id = "r1",
        name = "Day 1",
        status = ReportStatus.PendingApproval,
        createdById = "u2",
        approvals = listOf(ApprovalRequest("q1", "me", "Author", "2nd AD")),
    )

    @Test
    fun `opening lists the project's drafts of this tool only, with templates and metadata`() = runTest(dispatcher) {
        repository.metadata = SheetMetadata(currentShootDay = 4, finalApproverIds = listOf("u2"))
        repository.stock = listOf(Samples.stock("feature", "Feature Film"))
        repository.rows = { query ->
            if (ReportStatus.Draft in query.statuses) {
                listOf(Samples.row("r1", "Day 1"), Samples.row("ad1", "AD Day 1").copy(reportType = "ad"))
            } else {
                emptyList()
            }
        }
        val state = harness.start(this).currentState

        assertEquals(Workspace.Manage, state.workspace, "with no chat to show, the manager opens")
        assertEquals(listOf("r1"), state.lists.drafts.rows.map { it.id }, "an AD report is not a production report")
        assertTrue(state.lists.drafts.loaded)
        assertEquals(listOf("u2"), state.metadata.finalApproverIds)
        assertEquals(1, state.stockTemplates.size)
        assertTrue(state.canDistribute)
        assertEquals(
            ReportQuery(projectId = Samples.PROJECT, statuses = ReportStatus.DRAFT_TAB),
            repository.queries.single { ReportStatus.Draft in it.statuses },
            "an author's Drafts is the whole project",
        )
    }

    @Test
    fun `with a unit chat the tool opens on Chat, and the lists load once Manage is picked`() = runTest(dispatcher) {
        repository.rows = drafts("r1")
        val vm = harness.start(this, hasChat = true)
        assertEquals(Workspace.Chat, vm.currentState.workspace, "the web's tool lands on its unit chat")
        assertTrue(repository.queries.none { ReportStatus.Draft in it.statuses }, "nothing loads behind the chat")

        vm.onEvent(ListEvent.SetWorkspace(Workspace.Manage))
        settle()
        assertEquals(listOf("r1"), vm.currentState.lists.drafts.rows.map { it.id })
    }

    @Test
    fun `someone with nothing to manage stays on the chat`() = runTest(dispatcher) {
        val vm = harness.start(this, Samples.author.copy(canPost = false), hasChat = true)
        assertTrue(vm.currentState.manageTabs.isEmpty())
        assertEquals(Workspace.Chat, vm.currentState.workspace)
    }

    @Test
    fun `delete asks first, then the row leaves every list`() = runTest(dispatcher) {
        repository.rows = drafts("r1", "r2")
        val vm = harness.start(this)

        vm.onEvent(ListEvent.Delete(Samples.row("r1", "Day r1")))
        assertIs<ConfirmAction.DeleteReport>(assertIs<ReportDialog.Confirm>(vm.currentState.dialog).action)
        settle()
        assertTrue(repository.deleted.isEmpty(), "nothing is deleted before the confirm")

        vm.onEvent(DialogEvent.Confirm)
        settle()
        assertEquals(listOf("r1"), repository.deleted)
        assertEquals(listOf("r2"), vm.currentState.lists.drafts.rows.map { it.id })
        assertNull(vm.currentState.dialog)
        assertEquals("Deleted!", toasts.last().message)
    }

    @Test
    fun `a refused delete keeps the row and says why`() = runTest(dispatcher) {
        repository.rows = drafts("r1")
        repository.deleteAnswer = ZillitResult.Failure(ZillitError.Http(400, "Report is locked"))
        val vm = harness.start(this)

        vm.onEvent(ListEvent.Delete(Samples.row("r1", "Day r1")))
        vm.onEvent(DialogEvent.Confirm)
        settle()
        assertEquals(listOf("r1"), vm.currentState.lists.drafts.rows.map { it.id })
        assertFalse(vm.currentState.busy)
        assertTrue(toasts.last().isError)
        assertTrue(toasts.last().message.startsWith("Delete failed: "), toasts.last().message)
    }

    @Test
    fun `send for signature with no approvers anywhere prompts and sends nothing`() = runTest(dispatcher) {
        val report = Samples.row("r1", "Day 1")
        repository.details["r1"] = ReportDetail(report, SheetPayload())
        val vm = harness.start(this)

        vm.onEvent(WorkflowEvent.SendForSignature(report))
        settle()
        val prompt = assertIs<ReportDialog.Confirm>(vm.currentState.dialog)
        assertEquals(ConfirmAction.NoApprovers, prompt.action)
        assertEquals("No Approvers", prompt.title)
        assertTrue(repository.signatureSends.isEmpty())
        assertFalse(vm.currentState.busy)
    }

    @Test
    fun `send for signature names the report's own approvers and leaves Drafts`() = runTest(dispatcher) {
        val report = Samples.row("r1", "Day 1")
        repository.rows = drafts("r1")
        repository.metadata = SheetMetadata(finalApproverIds = listOf("u3"))
        repository.details["r1"] = ReportDetail(
            report,
            SheetPayload(shared = SharedHeader(approverIds = listOf("u2", "x9"))),
        )
        val vm = harness.start(this)

        vm.onEvent(WorkflowEvent.SendForSignature(report))
        settle()
        val (id, approvers) = repository.signatureSends.single()
        assertEquals("r1", id)
        assertEquals(
            listOf(ReviewAssignee("u2", "Uma", "Producer"), ReviewAssignee("x9", "x9", "Approver")),
            approvers,
            "the report's own list wins over the project default; an unknown id keeps its id",
        )
        assertTrue(vm.currentState.lists.drafts.rows.none { it.id == "r1" })
        assertEquals("Sent for approval!", toasts.last().message)
    }

    @Test
    fun `without posting rights the author's actions do nothing, whatever the screen sent`() = runTest(dispatcher) {
        val report = Samples.row("r1", "Day 1")
        val vm = harness.start(this, Samples.author.copy(canPost = false))
        repository.detailRequests.clear()

        vm.onEvent(WorkflowEvent.SendForSignature(report))
        vm.onEvent(WorkflowEvent.SendForComments(report))
        vm.onEvent(WorkflowEvent.OpenPublish(report))
        vm.onEvent(ListEvent.Edit(report))
        vm.onEvent(DialogEvent.CreateTemplate)
        settle()
        assertNull(vm.currentState.dialog)
        assertNull(vm.currentState.editor)
        assertTrue(repository.detailRequests.isEmpty())
        assertTrue(repository.signatureSends.isEmpty())
    }

    @Test
    fun `send for comments asks before dropping last time's receivers and adds the sender`() = runTest(dispatcher) {
        repository.metadata = SheetMetadata(internalReceiverIds = listOf("u3", "me"))
        val vm = harness.start(this)

        vm.onEvent(WorkflowEvent.SendForComments(Samples.row("r1", "Day 1")))
        assertEquals(setOf("u3"), assertIs<ReportDialog.SendPicker>(vm.currentState.dialog).selected)
        vm.onEvent(WorkflowEvent.ToggleRecipient("u3"))
        vm.onEvent(WorkflowEvent.ToggleRecipient("u2"))
        vm.onEvent(WorkflowEvent.SendRecipients)
        val removal = assertIs<ReportDialog.SendPicker>(vm.currentState.dialog)
        assertEquals(listOf("Vic"), removal.pendingRemoval?.map { it.fullName })
        settle()
        assertTrue(repository.commentSends.isEmpty(), "nothing goes out while the prompt is up")

        vm.onEvent(WorkflowEvent.FinishSend(revokeAccess = true))
        settle()
        assertEquals(
            MetadataUpdate(internalReceiverIds = listOf("u2", "me"), revokeAccessOnRemoval = true),
            repository.metadataWrites.single(),
        )
        val (id, receivers) = repository.commentSends.single()
        assertEquals("r1", id)
        assertEquals(listOf("u2", "me"), receivers.map { it.assigneeId })
        assertEquals("Sent for comments!", toasts.last().message)
    }

    @Test
    fun `an approver approves from Received without a signature, on the request id`() = runTest(dispatcher) {
        val report = awaitingMySignature()
        repository.rows = { query ->
            val mine = query.approverId == "me" && ReportStatus.PendingApproval in query.statuses
            if (mine) listOf(report) else emptyList()
        }
        val vm = harness.start(this, Samples.author.copy(canPost = false))
        vm.onEvent(ListEvent.OpenTab(ManageTab.Approvals))
        settle()
        assertEquals(listOf("r1"), vm.currentState.lists.received.rows.map { it.id }, "the probe revealed Approvals")
        repository.rows = { emptyList() }

        vm.onEvent(WorkflowEvent.OpenApprove(report))
        assertIs<ReportDialog.Approve>(vm.currentState.dialog)
        vm.onEvent(WorkflowEvent.ApproveWithoutSignature)
        settle()
        assertEquals(
            listOf<Pair<String,
            ApprovalDecision>>("q1" to ApprovalDecision.WithoutSignature),
            repository.approvals,
        )
        assertNull(vm.currentState.dialog)
        assertTrue(vm.currentState.lists.received.rows.isEmpty())
        assertEquals("Approved!", toasts.last().message)
    }

    @Test
    fun `a drawn signature is stored first, and a failed upload keeps the dialog to retry`() = runTest(dispatcher) {
        val vm = harness.start(this)
        harness.publishing.signatureAnswer = ZillitResult.Failure(ZillitError.NoConnection())

        vm.onEvent(WorkflowEvent.OpenApprove(awaitingMySignature()))
        vm.onEvent(WorkflowEvent.ApproveWithSignature(byteArrayOf(9)))
        assertEquals(true, (vm.currentState.dialog as? ReportDialog.Approve)?.uploading)
        settle()
        assertFalse(assertIs<ReportDialog.Approve>(vm.currentState.dialog).uploading)
        assertTrue(repository.approvals.isEmpty())
        assertTrue(toasts.last().isError)

        harness.publishing.signatureAnswer =
            ZillitResult.Success(
                ApprovalDecision.Signature(media = "p/sig.png", thumbnail = "", bucket = "b", region = "eu"),
            )
        vm.onEvent(WorkflowEvent.ApproveWithSignature(byteArrayOf(9)))
        settle()
        val (requestId, decision) = repository.approvals.single()
        assertEquals("q1", requestId)
        assertEquals("p/sig.png", assertIs<ApprovalDecision.Signature>(decision).media)
        assertNull(vm.currentState.dialog)
    }

    @Test
    fun `approve and reject never open on a comments-round request`() = runTest(dispatcher) {
        val report = Samples.row(
            id = "r1",
            name = "Day 1",
            status = ReportStatus.PendingInternalApproval,
            approvals = listOf(ApprovalRequest("q1", "me", "Author", "", stage = "INTERNAL")),
        )
        val vm = harness.start(this)
        vm.onEvent(WorkflowEvent.OpenApprove(report))
        vm.onEvent(WorkflowEvent.OpenReject(report))
        assertNull(vm.currentState.dialog)
    }

    @Test
    fun `a reminder goes to the current round's pending approvers with the default message`() = runTest(dispatcher) {
        val report = Samples.row(
            id = "r1",
            name = "Day 1",
            status = ReportStatus.PendingApproval,
            approvals = listOf(
                ApprovalRequest("q1", "u2", "Uma", "Producer"),
                ApprovalRequest("q2", "u3", "Vic", "Director", status = "APPROVED"),
            ),
        )
        val vm = harness.start(this)
        vm.onEvent(WorkflowEvent.OpenReminder(report))
        vm.onEvent(WorkflowEvent.ConfirmReminder)
        settle()
        val (id, reminder) = repository.reminders.single()
        assertEquals("r1", id)
        assertEquals(listOf("u2"), reminder.assigneeIds)
        assertEquals("Author", reminder.sentBy)
        assertEquals("2nd AD", reminder.sentByRole)
        assertEquals("Please review and approve this production report.", reminder.message)
        assertNull(vm.currentState.dialog)
    }

    @Test
    fun `publishing on Both publishes, files the PDF in the library and appends in chat`() = runTest(dispatcher) {
        val report = Samples.row("r1", "Day 1", ReportStatus.ApprovedForPublish)
        repository.details["r1"] = ReportDetail(report, SheetPayload(shared = SharedHeader(dateYmd = "2026-09-13")))
        val vm = harness.start(this)

        vm.onEvent(WorkflowEvent.OpenPublish(report))
        vm.onEvent(WorkflowEvent.PickDestination(PublishDestination.Both))
        vm.onEvent(WorkflowEvent.ContinuePublish)
        vm.onEvent(WorkflowEvent.ConfirmPublish)
        settle()
        assertTrue(repository.publishes.isEmpty(), "New or Continuation is picked first")

        vm.onEvent(WorkflowEvent.PickContinuation(true))
        vm.onEvent(WorkflowEvent.ConfirmPublish)
        settle()
        assertEquals(listOf("r1" to true), repository.publishes)
        val filed = Triple<String, Boolean, String?>("Day 1.pdf", false, "2026-09-13")
        assertEquals(listOf(filed), harness.publishing.library)
        assertEquals(
            listOf("ProductionReport_Day 1.pdf" to false),
            harness.publishing.chatPosts,
            "a continuation appends",
        )
        assertEquals(ReportDialog.DocDistDone("Day 1.pdf"), vm.currentState.dialog)
        assertTrue(toasts.any { it.message == "Published!" })
    }

    @Test
    fun `a New publish in app replaces the earlier chat posts and skips the library`() = runTest(dispatcher) {
        val report = Samples.row("r1", "Day 1", ReportStatus.ApprovedForPublish)
        val vm = harness.start(this)

        vm.onEvent(WorkflowEvent.OpenPublish(report))
        vm.onEvent(WorkflowEvent.ContinuePublish)
        vm.onEvent(WorkflowEvent.PickContinuation(false))
        vm.onEvent(WorkflowEvent.ConfirmPublish)
        settle()
        assertEquals(listOf("r1" to false), repository.publishes)
        assertTrue(harness.publishing.library.isEmpty())
        assertEquals(listOf("ProductionReport_Day 1.pdf" to true), harness.publishing.chatPosts)
        assertNull(vm.currentState.dialog)
    }

    @Test
    fun `without Document Distribution rights the library stays closed`() = runTest(dispatcher) {
        harness.publishing.distributes = false
        val report = Samples.row("r1", "Day 1", ReportStatus.ApprovedForPublish)
        val vm = harness.start(this)

        vm.onEvent(WorkflowEvent.OpenPublish(report))
        vm.onEvent(WorkflowEvent.PickDestination(PublishDestination.DocDist))
        assertEquals(PublishDestination.InApp, assertIs<ReportDialog.Publish>(vm.currentState.dialog).destination)
        vm.onEvent(DialogEvent.Dismiss)
        vm.onEvent(WorkflowEvent.SendToDocDist(report, fromDraft = true))
        settle()
        assertNull(vm.currentState.dialog)
        assertTrue(harness.publishing.library.isEmpty())
        assertTrue(toasts.last().isError)
    }

    @Test
    fun `chat with the creator opens through the attached window while there is one`() = runTest(dispatcher) {
        val vm = harness.start(this)

        vm.onEvent(ListEvent.ChatWithCreator(Samples.row("r1", "Day 1", createdById = "u2")))
        assertEquals(ReportDialog.ChatPicker("Chat with Creator", listOf("u2")), vm.currentState.dialog)
        vm.onEvent(ListEvent.ChatWith("u2"))
        assertEquals(listOf("u2" to "Uma"), harness.chats)

        val routed = mutableListOf<String>()
        vm.attachChat(
            ReportChatOpener { userId, _ ->
                routed += userId
                true
            },
        )
        vm.onEvent(ListEvent.ChatWith("u3"))
        assertEquals(listOf("u3"), routed)
        assertEquals(1, harness.chats.size, "the host's opener is bypassed while a window is attached")
    }
}
