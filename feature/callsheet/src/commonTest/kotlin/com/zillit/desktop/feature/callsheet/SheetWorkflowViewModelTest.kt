package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.callsheet.domain.BadgeLeaf
import com.zillit.desktop.feature.callsheet.domain.ApprovalDecision
import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.MetadataUpdate
import com.zillit.desktop.feature.callsheet.domain.SharedHeader
import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetQuery
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.ui.ConfirmAction
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.PublishDestination
import com.zillit.desktop.feature.callsheet.ui.PublishStep
import com.zillit.desktop.feature.callsheet.ui.SheetDialog
import com.zillit.desktop.feature.callsheet.ui.WorkflowEvent
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
class SheetWorkflowViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = SheetHarness(dispatcher)
    private val repository = harness.repository
    private val toasts = harness.toasts

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun drafts(vararg ids: String): (SheetQuery) -> List<CallSheetSummary> =
        { query ->
            if (CallSheetStatus.Draft in query.statuses) ids.map { Samples.row(it, "Day $it") } else emptyList()
        }

    private fun awaitingMySignature() = Samples.row(
        id = "r1",
        name = "Day 1",
        status = CallSheetStatus.PendingApproval,
        createdById = "u2",
        approvals = listOf(ApprovalRequest("q1", "me", "Author", "2nd AD")),
    )

    @Test
    fun `opening lists the project's drafts with the templates and metadata, on the poster's tabs`() =
        runTest(dispatcher) {
            repository.metadata = SheetMetadata(currentShootDay = 4, finalApproverIds = listOf("u2"))
            repository.stock = listOf(Samples.stock("standard", "Create your own template", createYourOwn = true))
            repository.rows = drafts("r1")
            val state = harness.start(this).currentState

            assertEquals(SheetTab.Drafts, state.activeTab)
            assertEquals(listOf(SheetTab.Drafts, SheetTab.Approvals, SheetTab.Published), state.tabs)
            assertEquals("Call Sheet Creation", state.toolTitle)
            assertEquals(listOf("r1"), state.lists.drafts.rows.map { it.id })
            assertTrue(state.lists.drafts.loaded)
            assertEquals(listOf("u2"), state.metadata.finalApproverIds)
            assertEquals(1, state.stockTemplates.size)
            assertTrue(state.canDistribute)
            assertEquals(
                SheetQuery(projectId = Samples.PROJECT, statuses = CallSheetStatus.DRAFT_TAB),
                repository.queries.single { CallSheetStatus.Draft in it.statuses },
                "a poster's Drafts is the whole project",
            )
        }

    @Test
    fun `a view-only approver lands on Approvals → Received, with drafts scoped to them`() = runTest(dispatcher) {
        repository.rows = { query ->
            if (query.approverId == "me" && CallSheetStatus.PendingApproval in query.statuses) {
                listOf(awaitingMySignature())
            } else {
                emptyList()
            }
        }
        val vm = harness.start(this, Samples.author.copy(canPost = false))
        val state = vm.currentState
        assertEquals("Drafts Call Sheet", state.toolTitle)
        assertEquals(listOf(SheetTab.Drafts, SheetTab.Approvals, SheetTab.Published), state.tabs)
        assertEquals(SheetTab.Approvals, state.activeTab)
        assertEquals(ApprovalSection.Received, state.activeSection)
        assertEquals(listOf("r1"), state.lists.received.rows.map { it.id })

        vm.onEvent(ListEvent.OpenTab(SheetTab.Drafts))
        settle()
        val draftsQuery = repository.queries.last { CallSheetStatus.Draft in it.statuses }
        assertEquals("me", draftsQuery.approverId, "a viewer's Drafts is approver-scoped")
    }

    @Test
    fun `a viewer nobody asked to sign has no Approvals tab`() = runTest(dispatcher) {
        val vm = harness.start(this, Samples.author.copy(canPost = false))
        assertEquals(listOf(SheetTab.Drafts, SheetTab.Published), vm.currentState.tabs)
        assertEquals(SheetTab.Drafts, vm.currentState.activeTab)
    }

    @Test
    fun `delete asks first, then the row leaves every list`() = runTest(dispatcher) {
        repository.rows = drafts("r1", "r2")
        val vm = harness.start(this)

        vm.onEvent(ListEvent.Delete(Samples.row("r1", "Day r1")))
        assertIs<ConfirmAction.DeleteSheet>(assertIs<SheetDialog.Confirm>(vm.currentState.dialog).action)
        settle()
        assertTrue(repository.deleted.isEmpty(), "nothing is deleted before the confirm")

        // The server's list no longer carries it once the delete lands — the reload must not resurrect it.
        repository.rows = drafts("r2")
        vm.onEvent(DialogEvent.Confirm)
        settle()
        assertEquals(listOf("r1"), repository.deleted)
        assertEquals(listOf("r2"), vm.currentState.lists.drafts.rows.map { it.id })
        assertNull(vm.currentState.dialog)
        assertEquals("Deleted!", toasts.last().message)
    }

    @Test
    fun `a failed delete keeps the row and says why`() = runTest(dispatcher) {
        repository.rows = drafts("r1")
        repository.deleteAnswer = ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = "locked"))
        val vm = harness.start(this)
        vm.onEvent(ListEvent.Delete(Samples.row("r1", "Day r1")))
        vm.onEvent(DialogEvent.Confirm)
        settle()
        assertEquals(listOf("r1"), vm.currentState.lists.drafts.rows.map { it.id })
        assertTrue(toasts.last().isError)
        assertFalse(vm.currentState.busy)
    }

    @Test
    fun `send for signature is an empty body, the sheet's own approvers first, none prompts`() = runTest(dispatcher) {
        repository.metadata = SheetMetadata(finalApproverIds = listOf("u2"))
        repository.rows = drafts("r1")
        val vm = harness.start(this)

        // The sheet states nobody signs it: the project default is NOT consulted.
        val noOne = Samples.row("r1", "Day 1").copy(shared = SharedHeader(approverIds = emptyList()))
        vm.onEvent(WorkflowEvent.SendForSignature(noOne))
        settle()
        assertEquals("No Approvers", assertIs<SheetDialog.Confirm>(vm.currentState.dialog).title)
        assertTrue(repository.signatureSends.isEmpty())
        vm.onEvent(DialogEvent.Confirm)

        // No list at all: the project's approvers decide.
        val undecided = Samples.row("r1", "Day 1").copy(shared = SharedHeader(approverIdsStated = false))
        repository.rows = drafts()
        vm.onEvent(WorkflowEvent.SendForSignature(undecided))
        settle()
        assertEquals(listOf("r1"), repository.signatureSends)
        assertEquals("Sent for approval!", toasts.last().message)
        assertTrue(vm.currentState.lists.drafts.rows.none { it.id == "r1" }, "a sent sheet leaves Drafts at once")
    }

    @Test
    fun `send for comments writes the receivers, then opens the round with the sender carried along`() =
        runTest(dispatcher) {
            repository.metadata = SheetMetadata(internalReceiverIds = listOf("u2"))
            repository.rows = drafts("r1")
            val vm = harness.start(this)

            vm.onEvent(WorkflowEvent.SendForComments(Samples.row("r1", "Day 1")))
            val picker = assertIs<SheetDialog.SendPicker>(vm.currentState.dialog)
            assertEquals(setOf("u2"), picker.selected, "last time's receivers are pre-selected")

            vm.onEvent(WorkflowEvent.ToggleRecipient("u3"))
            vm.onEvent(WorkflowEvent.SendRecipients)
            settle()
            assertNull(vm.currentState.dialog)
            val write = repository.metadataWrites.last()
            assertEquals(listOf("u2", "u3", "me"), write.internalReceiverIds, "the sender rides along")
            assertFalse(write.revokeAccessOnRemoval)
            val (sheetId, assignees) = repository.commentSends.single()
            assertEquals("r1", sheetId)
            assertEquals(listOf("u2", "u3", "me"), assignees.map { it.assigneeId })
            assertEquals("Producer", assignees[0].role)
            assertEquals("Sent for internal distribution!", toasts.last().message)
        }

    @Test
    fun `unticking a previous receiver asks whether to revoke their access, and the answer travels`() =
        runTest(dispatcher) {
            repository.metadata = SheetMetadata(internalReceiverIds = listOf("u2", "u3"))
            repository.rows = drafts("r1")
            val vm = harness.start(this)

            vm.onEvent(WorkflowEvent.SendForComments(Samples.row("r1", "Day 1")))
            vm.onEvent(WorkflowEvent.ToggleRecipient("u3"))
            vm.onEvent(WorkflowEvent.SendRecipients)
            val picker = assertIs<SheetDialog.SendPicker>(vm.currentState.dialog)
            assertEquals(listOf("u3"), picker.pendingRemoval?.map { it.userId })
            assertTrue(repository.commentSends.isEmpty(), "nothing is sent until the removal is answered")

            vm.onEvent(WorkflowEvent.FinishSend(revokeAccess = true))
            settle()
            assertTrue(repository.metadataWrites.last().revokeAccessOnRemoval)
            assertEquals(listOf("u2", "me"), repository.commentSends.single().second.map { it.assigneeId })
        }

    @Test
    fun `send for chat renders the PDF and posts it one-to-one`() = runTest(dispatcher) {
        repository.rows = drafts("r1")
        val vm = harness.start(this)
        vm.onEvent(WorkflowEvent.OpenSendForChat(Samples.row("r1", "Day 1")))
        vm.onEvent(WorkflowEvent.PickChatRecipient("u3"))
        vm.onEvent(WorkflowEvent.ConfirmSendForChat)
        settle()
        assertEquals(listOf("CallSheet_r1.pdf" to "u3"), harness.publishing.chatSends)
        assertNull(vm.currentState.dialog)
        assertEquals("Call sheet PDF sent to chat.", toasts.last().message)
    }

    @Test
    fun `approving without a signature acts on my request id, then the row leaves Received`() =
        runTest(dispatcher) {
            repository.rows = { query -> if (query.approverId == "me") listOf(awaitingMySignature()) else emptyList() }
            val vm = harness.start(this, Samples.author.copy(canPost = false))
            assertEquals(listOf("r1"), vm.currentState.lists.received.rows.map { it.id })

            vm.onEvent(WorkflowEvent.OpenApprove(awaitingMySignature()))
            assertIs<SheetDialog.Approve>(vm.currentState.dialog)
            vm.onEvent(WorkflowEvent.ApproveWithoutSignature)
            settle()
            val expected: List<Pair<String, ApprovalDecision>> = listOf("q1" to ApprovalDecision.WithoutSignature)
            assertEquals(expected, repository.approvals)
            assertEquals("Approved!", toasts.last().message)
            assertNull(vm.currentState.dialog)
        }

    @Test
    fun `a drawn signature is stored first, and a storage failure keeps the dialog`() = runTest(dispatcher) {
        repository.rows = { query -> if (query.approverId == "me") listOf(awaitingMySignature()) else emptyList() }
        harness.publishing.signatureAnswer =
            ZillitResult.Failure(ZillitError.Http(status = 500, serverMessage = "storage down"))
        val vm = harness.start(this, Samples.author.copy(canPost = false))

        vm.onEvent(WorkflowEvent.OpenApprove(awaitingMySignature()))
        vm.onEvent(WorkflowEvent.UseSignature(byteArrayOf(9)))
        vm.onEvent(WorkflowEvent.ApproveWithSignature)
        settle()
        assertTrue(repository.approvals.isEmpty(), "no approval without the stored image")
        assertIs<SheetDialog.Approve>(vm.currentState.dialog)
        assertTrue(toasts.last().isError)

        harness.publishing.signatureAnswer = ZillitResult.Success(
            ApprovalDecision.Signature(media = "p/sig.png", thumbnail = "", bucket = "b", region = "eu"),
        )
        vm.onEvent(WorkflowEvent.ApproveWithSignature)
        settle()
        assertEquals("p/sig.png", (repository.approvals.single().second as ApprovalDecision.Signature).media)
    }

    @Test
    fun `a reminder goes to the current round's pending approvers, without a role`() = runTest(dispatcher) {
        repository.rows = drafts()
        val vm = harness.start(this)
        val sheet = Samples.row(
            "r1",
            "Day 1",
            status = CallSheetStatus.PendingApproval,
            approvals = listOf(
                ApprovalRequest("q1", "u2", "Uma", "Producer", round = 1),
                ApprovalRequest("q2", "u3", "Vic", "Director", round = 2),
            ),
        )
        vm.onEvent(WorkflowEvent.OpenReminder(sheet))
        vm.onEvent(WorkflowEvent.EditReminder("  "))
        vm.onEvent(WorkflowEvent.ConfirmReminder)
        settle()
        val (id, request) = repository.reminders.single()
        assertEquals("r1", id)
        assertEquals(listOf("u3"), request.assigneeIds, "only the newest round is reminded")
        assertEquals("Please review and approve this call sheet.", request.message)
        assertEquals("me", request.sentById)
        assertEquals("Reminder sent!", toasts.last().message)
    }

    @Test
    fun `publishing in app posts the PDF to Home — New replaces, Continuation appends with notes`() =
        runTest(dispatcher) {
            repository.rows = drafts()
            val vm = harness.start(this)
            val approved = Samples.row("r1", "Day 1", status = CallSheetStatus.ApprovedForPublish)

            vm.onEvent(WorkflowEvent.OpenPublish(approved))
            val publish = assertIs<SheetDialog.Publish>(vm.currentState.dialog)
            assertEquals(PublishStep.Destination, publish.step)
            vm.onEvent(WorkflowEvent.ContinuePublish)
            assertEquals(PublishStep.Type, assertIs<SheetDialog.Publish>(vm.currentState.dialog).step)
            vm.onEvent(WorkflowEvent.PickContinuation(true))
            vm.onEvent(WorkflowEvent.EditPublishNotes(" Call time moved "))
            vm.onEvent(WorkflowEvent.ConfirmPublish)
            settle()

            assertEquals(listOf("r1" to true), repository.publishes)
            assertEquals(listOf("Call time moved"), repository.publishNotes)
            assertEquals(listOf("CallSheet_r1.pdf" to false), harness.publishing.unitPosts)
            assertNull(vm.currentState.dialog)
            assertEquals("Published!", toasts.last().message)

            vm.onEvent(WorkflowEvent.OpenPublish(approved))
            vm.onEvent(WorkflowEvent.ContinuePublish)
            vm.onEvent(WorkflowEvent.PickContinuation(false))
            vm.onEvent(WorkflowEvent.ConfirmPublish)
            settle()
            assertEquals("CallSheet_r1.pdf" to true, harness.publishing.unitPosts.last(), "New replaces")
        }

    @Test
    fun `publishing to Document Distribution alone never calls publish`() = runTest(dispatcher) {
        repository.rows = drafts()
        repository.details["r1"] = CallSheetDetail(
            Samples.row("r1", "Day 1", status = CallSheetStatus.ApprovedForPublish),
            SheetPayload(shared = SharedHeader(dateMs = 1_755_129_600_000L)),
        )
        val vm = harness.start(this)
        val approved = Samples.row("r1", "Day 1", status = CallSheetStatus.ApprovedForPublish)

        vm.onEvent(WorkflowEvent.OpenPublish(approved))
        vm.onEvent(WorkflowEvent.PickDestination(PublishDestination.DocDist))
        vm.onEvent(WorkflowEvent.ContinuePublish)
        settle()
        val confirm = assertIs<SheetDialog.DocDistConfirm>(vm.currentState.dialog)
        assertFalse(confirm.fromDraft)
        vm.onEvent(WorkflowEvent.ConfirmDocDist)
        settle()
        assertTrue(repository.publishes.isEmpty())
        val (fileName, fromDraft, date) = harness.publishing.library.single()
        assertEquals("Day 1.pdf", fileName)
        assertFalse(fromDraft)
        assertEquals(1_755_129_600_000L, date, "filed under the shoot day, read from the detail")
        assertIs<SheetDialog.DocDistDone>(vm.currentState.dialog)
    }

    @Test
    fun `a Drafts row goes to the draft root of Document Distribution`() = runTest(dispatcher) {
        repository.rows = drafts("r1")
        val vm = harness.start(this)
        vm.onEvent(WorkflowEvent.SendToDocDist(Samples.row("r1", "Day 1"), fromDraft = true))
        vm.onEvent(WorkflowEvent.ConfirmDocDist)
        settle()
        assertEquals(true, harness.publishing.library.single().second)
    }

    @Test
    fun `without library rights the destination cannot be picked`() = runTest(dispatcher) {
        harness.publishing.distributes = false
        repository.rows = drafts()
        val vm = harness.start(this)
        vm.onEvent(WorkflowEvent.OpenPublish(Samples.row("r1", "Day 1", status = CallSheetStatus.ApprovedForPublish)))
        vm.onEvent(WorkflowEvent.PickDestination(PublishDestination.Both))
        assertEquals(PublishDestination.InApp, assertIs<SheetDialog.Publish>(vm.currentState.dialog).destination)
    }

    @Test
    fun `opening a thread reads its badge, and the approvals sections clear their units on entry`() =
        runTest(dispatcher) {
            repository.rows = drafts("r1")
            harness.badges.leaves.value = listOf(BadgeLeaf(SheetBadges.UNIT_COMMENT, level1 = "drafts", level3 = "r1"))
            val vm = harness.start(this)
            settle()
            assertEquals(1, vm.currentState.unreadComments("r1"))
            vm.onEvent(ListEvent.OpenComments(Samples.row("r1", "Day 1"), readOnly = false))
            settle()
            assertEquals(listOf("r1"), harness.badges.threadsRead)
            assertTrue(harness.badges.unitsRead.isEmpty())
            assertEquals(SheetBadges.TOOL, "call_sheet_label")
        }

    @Test
    fun `chat with the creator opens their thread through the host`() = runTest(dispatcher) {
        repository.rows = drafts()
        val vm = harness.start(this)
        vm.onEvent(ListEvent.ChatWithCreator(awaitingMySignature()))
        val picker = assertIs<SheetDialog.ChatPicker>(vm.currentState.dialog)
        assertEquals(listOf("u2"), picker.userIds)
        vm.onEvent(ListEvent.ChatWith("u2"))
        assertEquals(listOf("u2" to "Uma"), harness.chats)
        assertNull(vm.currentState.dialog)
    }

    @Test
    fun `a busy dialog ignores a second confirm and cannot be dismissed`() = runTest(dispatcher) {
        repository.rows = drafts()
        val vm = harness.start(this)
        vm.onEvent(WorkflowEvent.OpenPublish(Samples.row("r1", "Day 1", status = CallSheetStatus.ApprovedForPublish)))
        vm.onEvent(WorkflowEvent.ContinuePublish)
        vm.onEvent(WorkflowEvent.PickContinuation(false))
        vm.onEvent(WorkflowEvent.ConfirmPublish)
        vm.onEvent(WorkflowEvent.ConfirmPublish)
        vm.onEvent(DialogEvent.Dismiss)
        assertTrue(vm.currentState.busy)
        settle()
        assertEquals(1, repository.publishes.size)
        assertEquals(emptyList(), repository.metadataWrites.map { it.currentShootDay }.filterNotNull())
    }

    @Test
    fun `a metadata write that never happened is not counted`() {
        assertTrue(MetadataUpdate().isEmpty)
        assertFalse(MetadataUpdate(dayTypeAdd = "NIGHT").isEmpty)
    }
}
