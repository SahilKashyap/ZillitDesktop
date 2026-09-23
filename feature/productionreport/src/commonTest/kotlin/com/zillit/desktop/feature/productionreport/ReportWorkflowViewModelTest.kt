package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.productionreport.domain.ApprovalDecision
import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.BadgeLeaf
import com.zillit.desktop.feature.productionreport.domain.BadgeSurface
import com.zillit.desktop.feature.productionreport.domain.CellRow
import com.zillit.desktop.feature.productionreport.domain.CellValue
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.ReplaceTarget
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
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
import com.zillit.desktop.feature.productionreport.ui.PublishType
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

    /** A payload whose Call Times section names the two lines ZL-21398 checks. */
    private fun callTimes(crewCall: String, unitWrap: String) = SheetPayload(
        shared = SharedHeader(approverIds = listOf("u2")),
        rows = listOf(
            PageRow(
                0,
                cells = listOf(
                    PageCell(
                        order = 0,
                        title = "Call Times",
                        columns = listOf(ColumnSpec(label = "Field"), ColumnSpec(label = "Value")),
                        rows = listOf(
                            CellRow(0, listOf(CellValue("Crew Call"), CellValue(crewCall))),
                            CellRow(1, listOf(CellValue("Unit Wrap"), CellValue(unitWrap))),
                        ),
                    ),
                ),
            ),
        ),
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
        assertTrue(state.metadataSettled)
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
    fun `a viewer's tabs come from the two project lists, and a failed metadata read fails open`() =
        runTest(dispatcher) {
            val nobody = harness.start(this, Samples.viewer, hasChat = true)
            assertTrue(nobody.currentState.manageTabs.isEmpty(), "named by neither list: chat only")
            assertEquals(Workspace.Chat, nobody.currentState.workspace)
            assertTrue(repository.queries.none { it.approverId == "me" }, "the old involvement probe is gone")

            repository.metadata = SheetMetadata(internalReceiverIds = listOf("me"))
            val commenter = ReportHarness(dispatcher).also { it.repository.metadata = repository.metadata }
                .start(this, Samples.viewer)
            assertEquals(listOf(ManageTab.Drafts), commenter.currentState.manageTabs)
            assertTrue(commenter.currentState.isInternalReceiver)

            val approverHarness = ReportHarness(dispatcher)
            approverHarness.repository.metadata = SheetMetadata(finalApproverIds = listOf(" me "))
            val approver = approverHarness.start(this, Samples.viewer)
            assertEquals(listOf(ManageTab.Approvals), approver.currentState.manageTabs)
            assertEquals(ManageTab.Approvals, approver.currentState.tab, "the tab clamps into the set")

            val failing = ReportHarness(dispatcher).also { it.repository.metadataFails = true }
            val failed = failing.start(this, Samples.viewer)
            assertTrue(failed.currentState.metadataFailed)
            assertEquals(listOf(ManageTab.Drafts, ManageTab.Approvals), failed.currentState.manageTabs)
        }

    @Test
    fun `delete asks first, holds the confirm while it runs, then the row leaves every list`() = runTest(dispatcher) {
        repository.rows = drafts("r1", "r2")
        val vm = harness.start(this)

        vm.onEvent(ListEvent.Delete(Samples.row("r1", "Day r1")))
        val confirm = assertIs<ReportDialog.Confirm>(vm.currentState.dialog)
        assertIs<ConfirmAction.DeleteReport>(confirm.action)
        assertEquals("r1", confirm.reportId)
        settle()
        assertTrue(repository.deleted.isEmpty(), "nothing is deleted before the confirm")

        vm.onEvent(DialogEvent.Confirm)
        assertTrue(assertIs<ReportDialog.Confirm>(vm.currentState.dialog).busy, "the dialog stays up, spinning")
        vm.onEvent(DialogEvent.Dismiss)
        assertIs<ReportDialog.Confirm>(vm.currentState.dialog, "a busy confirm does not close under its request")
        settle()
        assertEquals(listOf("r1"), repository.deleted)
        assertEquals(listOf("r2"), vm.currentState.lists.drafts.rows.map { it.id })
        assertNull(vm.currentState.dialog)
        assertEquals("Deleted!", toasts.last().message)
    }

    @Test
    fun `a refused delete keeps the confirm up and says why`() = runTest(dispatcher) {
        repository.rows = drafts("r1")
        repository.deleteAnswer = ZillitResult.Failure(ZillitError.Http(400, "Report is locked"))
        val vm = harness.start(this)

        vm.onEvent(ListEvent.Delete(Samples.row("r1", "Day r1")))
        vm.onEvent(DialogEvent.Confirm)
        settle()
        assertEquals(listOf("r1"), vm.currentState.lists.drafts.rows.map { it.id })
        assertFalse(vm.currentState.busy)
        assertFalse(assertIs<ReportDialog.Confirm>(vm.currentState.dialog).busy, "the answer sits under the question")
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
    fun `send for signature is blocked while a crew call or unit wrap line is empty`() = runTest(dispatcher) {
        val report = Samples.row("r1", "Day 1")
        repository.details["r1"] = ReportDetail(report, callTimes(crewCall = "06:30", unitWrap = ""))
        val vm = harness.start(this)

        vm.onEvent(WorkflowEvent.SendForSignature(report))
        settle()
        val prompt = assertIs<ReportDialog.Confirm>(vm.currentState.dialog)
        assertEquals(ConfirmAction.MissingCallTimes, prompt.action)
        assertEquals("Missing Call Times", prompt.title)
        assertTrue(repository.signatureSends.isEmpty())
        assertFalse(vm.currentState.busy)

        vm.onEvent(DialogEvent.Confirm)
        repository.details["r1"] = ReportDetail(report, callTimes(crewCall = "06:30", unitWrap = "19:00"))
        vm.onEvent(WorkflowEvent.SendForSignature(report))
        settle()
        assertEquals("r1", repository.signatureSends.single().first)
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
        val vm = harness.start(this, Samples.viewer)
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
    fun `Sent is the project's signature phase, never scoped to the creator`() = runTest(dispatcher) {
        val vm = harness.start(this)
        vm.onEvent(ListEvent.OpenTab(ManageTab.Approvals))
        settle()
        val sent = repository.queries.single { it.statuses == ReportStatus.SIGNATURE_PHASE && it.approverId == null }
        assertEquals(Samples.PROJECT, sent.projectId)
        assertNull(sent.createdById, "a second author sees a colleague's sent report")
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
    fun `approve asks first, and without a signature approves on the request id`() = runTest(dispatcher) {
        val report = awaitingMySignature()
        repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
        repository.rows = { query ->
            val mine = query.approverId == "me" && ReportStatus.PendingApproval in query.statuses
            if (mine) listOf(report) else emptyList()
        }
        val vm = harness.start(this, Samples.viewer)
        vm.onEvent(ListEvent.OpenTab(ManageTab.Approvals))
        settle()
        assertEquals(listOf("r1"), vm.currentState.lists.received.rows.map { it.id })
        repository.rows = { emptyList() }

        vm.onEvent(WorkflowEvent.OpenApprove(report))
        val chooser = assertIs<ReportDialog.Approve>(vm.currentState.dialog)
        assertFalse(chooser.sign, "the chooser comes before any signing screen")
        vm.onEvent(WorkflowEvent.ApproveWithSignature(byteArrayOf(9)))
        settle()
        assertTrue(repository.approvals.isEmpty(), "the chooser owns no signature")

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
    fun `a drawn signature is stored first, and a failed upload or approve keeps the dialog to retry`() =
        runTest(dispatcher) {
            val vm = harness.start(this)
            harness.publishing.signatureAnswer = ZillitResult.Failure(ZillitError.NoConnection())

            vm.onEvent(WorkflowEvent.OpenApprove(awaitingMySignature()))
            vm.onEvent(WorkflowEvent.ChooseSignedApproval)
            assertTrue(assertIs<ReportDialog.Approve>(vm.currentState.dialog).sign)
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
            repository.approveAnswer = ZillitResult.Failure(ZillitError.Http(409, "Request superseded"))
            vm.onEvent(WorkflowEvent.ApproveWithSignature(byteArrayOf(9)))
            settle()
            assertTrue(assertIs<ReportDialog.Approve>(vm.currentState.dialog).sign, "closes only on success")
            assertTrue(toasts.last().message.startsWith("Approve failed: "))

            repository.approveAnswer = ZillitResult.Success(Unit)
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
    fun `a reminder goes to the pending approvers, signed with the sender's id and designation key`() =
        runTest(dispatcher) {
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
            assertEquals("me", reminder.sentBy, "the member id, never the name — the reader resolves it")
            assertEquals("me", reminder.sentById)
            assertEquals("2nd_assistant_director_label", reminder.sentByRole, "the designation KEY (ZL-20648)")
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
        assertTrue(repository.publishes.isEmpty(), "New, Continuation or Replace is picked first")

        vm.onEvent(WorkflowEvent.PickPublishType(PublishType.Continuation))
        vm.onEvent(WorkflowEvent.ConfirmPublish)
        settle()
        assertEquals(listOf("r1" to true), repository.publishes)
        val filed = Triple<String, Boolean, String?>("Day 1.pdf", false, "2026-09-13")
        assertEquals(listOf(filed), harness.publishing.library)
        assertEquals(
            listOf(Triple<String, Boolean, String?>("ProductionReport_Day 1.pdf", false, null)),
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
        vm.onEvent(WorkflowEvent.PickPublishType(PublishType.Replace))
        assertNull(
            assertIs<ReportDialog.Publish>(vm.currentState.dialog).type,
            "Replace is not offered while the chat holds no document",
        )
        vm.onEvent(WorkflowEvent.PickPublishType(PublishType.New))
        vm.onEvent(WorkflowEvent.ConfirmPublish)
        settle()
        assertEquals(listOf("r1" to false), repository.publishes)
        assertTrue(harness.publishing.library.isEmpty())
        assertEquals(
            listOf(Triple<String, Boolean, String?>("ProductionReport_Day 1.pdf", true, null)),
            harness.publishing.chatPosts,
        )
        assertNull(vm.currentState.dialog)
    }

    @Test
    fun `Replace swaps one live document, travels as NEW without wiping, and reports a vanished target`() =
        runTest(dispatcher) {
            val report = Samples.row("r1", "Day 1", ReportStatus.ApprovedForPublish)
            harness.publishing.replaceable = listOf(ReplaceTarget("c9", "Day0.pdf"), ReplaceTarget("c8", "Old.pdf"))
            val vm = harness.start(this)

            vm.onEvent(WorkflowEvent.OpenPublish(report))
            settle()
            val dialog = assertIs<ReportDialog.Publish>(vm.currentState.dialog)
            assertEquals(listOf("c9", "c8"), dialog.replaceOptions.map { it.chatId })
            assertEquals("c9", dialog.replaceChatId, "the newest is pre-picked")
            vm.onEvent(WorkflowEvent.ContinuePublish)
            vm.onEvent(WorkflowEvent.PickPublishType(PublishType.Replace))
            vm.onEvent(WorkflowEvent.PickReplaceTarget("c8"))
            vm.onEvent(WorkflowEvent.PickReplaceTarget("nope"))
            assertEquals("c8", assertIs<ReportDialog.Publish>(vm.currentState.dialog).replaceChatId)
            harness.publishing.chatPostAnswer =
                ZillitResult.Failure(ZillitError.Http(200, "unit_chat_replace_target_not_found"))
            vm.onEvent(WorkflowEvent.ConfirmPublish)
            settle()
            assertEquals(listOf("r1" to false), repository.publishes, "the publish API knows NEW, not REPLACE")
            assertEquals(
                listOf(Triple<String, Boolean, String?>("ProductionReport_Day 1.pdf", false, "c8")),
                harness.publishing.chatPosts,
                "replacePreviousChats follows the CHOICE: a Replace never wipes the unit",
            )
            assertTrue(toasts.last().isError)
            assertTrue(toasts.last().message.startsWith("That document is no longer in the chat"))
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
    fun `send for chat picks one crew member, shares the PDF, and stays open on a failure`() = runTest(dispatcher) {
        val vm = harness.start(this, Samples.viewer)
        vm.onEvent(ListEvent.SendForChat(Samples.row("r1", "Day 1", ReportStatus.Published)))
        assertNull(vm.currentState.dialog, "never once final-approved or published")

        vm.onEvent(ListEvent.SendForChat(Samples.row("r1", "Day 1", ReportStatus.PendingApproval)))
        vm.onEvent(ListEvent.ConfirmSendForChat)
        settle()
        assertTrue(harness.publishing.directChats.isEmpty(), "nobody picked, nothing sent")
        vm.onEvent(ListEvent.PickChatRecipient("u2"))
        harness.publishing.directChatAnswer = ZillitResult.Failure(ZillitError.NoConnection())
        vm.onEvent(ListEvent.ConfirmSendForChat)
        settle()
        val kept = assertIs<ReportDialog.SendForChat>(vm.currentState.dialog)
        assertFalse(kept.sending)
        assertTrue(toasts.last().isError)

        harness.publishing.directChatAnswer = ZillitResult.Success(Unit)
        vm.onEvent(ListEvent.ConfirmSendForChat)
        settle()
        assertEquals(listOf("u2" to "ProductionReport_Day 1.pdf"), harness.publishing.directChats)
        assertNull(vm.currentState.dialog)
        assertEquals("Production report PDF sent to chat.", toasts.last().message)
    }

    @Test
    fun `opening a row reads its report badge on the list it is on, never the unit`() = runTest(dispatcher) {
        repository.rows = drafts("r1")
        val vm = harness.start(this)
        harness.badges.live.emit(
            listOf(
                BadgeLeaf(ReportBadges.UNIT_DRAFTS, level2 = "report", level3 = "r1"),
                BadgeLeaf(ReportBadges.UNIT_DRAFTS, level2 = "comment", level3 = "r1"),
                BadgeLeaf(ReportBadges.UNIT_APPROVAL, level1 = "received", level2 = "report", level3 = "r1"),
            ),
        )
        settle()
        assertEquals(2, vm.currentState.badges.drafts)
        vm.onEvent(ListEvent.View(Samples.row("r1", "Day 1")))
        settle()
        assertEquals(listOf(BadgeRead(BadgeSurface.Drafts, BadgeKind.Report, "r1")), harness.badges.reads)

        vm.onEvent(ListEvent.OpenTab(ManageTab.Approvals))
        vm.onEvent(ListEvent.View(Samples.row("r1", "Day 1")))
        settle()
        assertEquals(
            BadgeRead(BadgeSurface.Received, BadgeKind.Report, "r1"),
            harness.badges.reads.last(),
            "a poster off final_approver_ids opens from Sent and clears the received leaf too",
        )
    }

    @Test
    fun `Published clears on entry, leaf by leaf, and drains for a user who has no Published tab`() =
        runTest(dispatcher) {
            val vm = harness.start(this)
            harness.badges.live.emit(listOf(BadgeLeaf(ReportBadges.UNIT_PUBLISHED, level2 = "report", level3 = "r9")))
            settle()
            assertTrue(harness.badges.reads.isEmpty(), "not while Drafts is on screen")
            vm.onEvent(ListEvent.OpenTab(ManageTab.Published))
            settle()
            assertEquals(listOf(BadgeRead(BadgeSurface.Published, BadgeKind.Report, "r9")), harness.badges.reads)
            harness.badges.live.emit(listOf(BadgeLeaf(ReportBadges.UNIT_PUBLISHED, level2 = "report", level3 = "r9")))
            settle()
            assertEquals(1, harness.badges.reads.size, "an unchanged leaf is not read again")

            val viewer = ReportHarness(dispatcher)
            viewer.repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
            viewer.start(this, Samples.viewer)
            viewer.badges.live.emit(listOf(BadgeLeaf(ReportBadges.UNIT_PUBLISHED, level2 = "comment", level3 = "r9")))
            settle()
            assertEquals(
                listOf(BadgeRead(BadgeSurface.Published, BadgeKind.Comment, "r9")),
                viewer.badges.reads,
                "nothing else could ever clear a hidden tab's rows",
            )
        }
}
