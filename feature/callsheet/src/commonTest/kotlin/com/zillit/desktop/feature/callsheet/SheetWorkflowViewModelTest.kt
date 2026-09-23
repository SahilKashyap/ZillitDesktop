package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.callsheet.domain.BadgeLeaf
import com.zillit.desktop.feature.callsheet.domain.ApprovalDecision
import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.BadgeKind
import com.zillit.desktop.feature.callsheet.domain.BadgeSurface
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
import com.zillit.desktop.feature.callsheet.domain.UnitMessage
import com.zillit.desktop.feature.callsheet.ui.ConfirmAction
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.PublishChoice
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
        repository.metadata = SheetMetadata(finalApproverIds = listOf("me"), internalReceiverIds = listOf("me"))
        val vm = harness.start(this, Samples.author.copy(canPost = false))
        val state = vm.currentState
        assertEquals("Drafts Call Sheet", state.toolTitle)
        assertEquals(listOf(SheetTab.Drafts, SheetTab.Approvals), state.tabs, "never Published for a viewer")
        assertEquals(SheetTab.Approvals, state.activeTab)
        assertEquals(ApprovalSection.Received, state.activeSection)
        assertEquals(listOf("r1"), state.lists.received.rows.map { it.id })

        vm.onEvent(ListEvent.OpenTab(SheetTab.Drafts))
        settle()
        val draftsQuery = repository.queries.last { CallSheetStatus.Draft in it.statuses }
        assertEquals("me", draftsQuery.approverId, "a viewer's Drafts is approver-scoped")
    }

    @Test
    fun `a viewer gets only the tabs the metadata names them on, and none when it names nobody`() =
        runTest(dispatcher) {
            val nobody = harness.start(this, Samples.author.copy(canPost = false))
            assertEquals(emptyList<SheetTab>(), nobody.currentState.tabs, "named by neither list → no tabs")
            assertTrue(
                repository.queries.none { CallSheetStatus.Draft in it.statuses },
                "nothing is fetched for a hidden tab",
            )

            val receiverHarness = SheetHarness(dispatcher)
            receiverHarness.repository.metadata = SheetMetadata(internalReceiverIds = listOf("me"))
            val receiver = receiverHarness.start(this, Samples.author.copy(canPost = false))
            assertEquals(listOf(SheetTab.Drafts), receiver.currentState.tabs)
            assertEquals(SheetTab.Drafts, receiver.currentState.activeTab, "lands on the first tab they have")
        }

    @Test
    fun `a FAILED metadata read fails a viewer's tabs open`() = runTest(dispatcher) {
        repository.metadataAnswer = ZillitResult.Failure(ZillitError.Http(status = 500, serverMessage = "down"))
        val vm = harness.start(this, Samples.author.copy(canPost = false))
        assertTrue(vm.currentState.metadataFailed)
        assertEquals(listOf(SheetTab.Drafts, SheetTab.Approvals), vm.currentState.tabs)
        assertEquals(SheetTab.Approvals, vm.currentState.activeTab)
    }

    @Test
    fun `Sent is the project's signature phase, never scoped by creator`() = runTest(dispatcher) {
        repository.rows = drafts()
        val vm = harness.start(this)
        vm.onEvent(ListEvent.OpenTab(SheetTab.Approvals))
        vm.onEvent(ListEvent.OpenSection(ApprovalSection.Sent))
        settle()
        val sent = repository.queries.single { CallSheetStatus.PendingApproval in it.statuses }
        assertEquals(Samples.PROJECT, sent.projectId)
        assertNull(sent.createdById, "a second author must see a colleague's sent sheet")
        assertNull(sent.approverId)
    }

    @Test
    fun `the delete confirm stays open, busy, until the request settles`() = runTest(dispatcher) {
        repository.rows = drafts("r1")
        val vm = harness.start(this)
        vm.onEvent(ListEvent.Delete(Samples.row("r1", "Day 1")))
        vm.onEvent(DialogEvent.Confirm)
        assertIs<SheetDialog.Confirm>(vm.currentState.dialog, "held open through the request")
        assertTrue(vm.currentState.busy)
        vm.onEvent(DialogEvent.Confirm)
        vm.onEvent(DialogEvent.Dismiss)
        settle()
        assertEquals(listOf("r1"), repository.deleted, "one request, whatever the clicks")
        assertNull(vm.currentState.dialog)
        assertFalse(vm.currentState.busy)
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
            repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
            val vm = harness.start(this, Samples.author.copy(canPost = false))
            assertEquals(listOf("r1"), vm.currentState.lists.received.rows.map { it.id })

            vm.onEvent(WorkflowEvent.OpenApprove(awaitingMySignature()))
            assertFalse(assertIs<SheetDialog.Approve>(vm.currentState.dialog).sign, "ZL-21512: the chooser comes first")
            vm.onEvent(WorkflowEvent.ApproveWithoutSignature)
            settle()
            val expected: List<Pair<String, ApprovalDecision>> = listOf("q1" to ApprovalDecision.WithoutSignature)
            assertEquals(expected, repository.approvals)
            assertEquals("Approved!", toasts.last().message)
            assertNull(vm.currentState.dialog)
        }

    @Test
    fun `once signing was chosen the unsigned outcome is no longer on offer, and the pad needs a signature`() =
        runTest(dispatcher) {
            repository.rows = { query -> if (query.approverId == "me") listOf(awaitingMySignature()) else emptyList() }
            val vm = harness.start(this, Samples.author.copy(canPost = false))
            vm.onEvent(WorkflowEvent.OpenApprove(awaitingMySignature()))
            vm.onEvent(WorkflowEvent.ChooseSignature)
            assertTrue(assertIs<SheetDialog.Approve>(vm.currentState.dialog).sign)
            vm.onEvent(WorkflowEvent.ApproveWithoutSignature)
            vm.onEvent(WorkflowEvent.ApproveWithSignature)
            settle()
            assertTrue(repository.approvals.isEmpty(), "locked to the choice, and nothing drawn yet")
            assertIs<SheetDialog.Approve>(vm.currentState.dialog)
        }

    @Test
    fun `a drawn signature is stored first, and a storage failure keeps the dialog`() = runTest(dispatcher) {
        repository.rows = { query -> if (query.approverId == "me") listOf(awaitingMySignature()) else emptyList() }
        harness.publishing.signatureAnswer =
            ZillitResult.Failure(ZillitError.Http(status = 500, serverMessage = "storage down"))
        val vm = harness.start(this, Samples.author.copy(canPost = false))

        vm.onEvent(WorkflowEvent.OpenApprove(awaitingMySignature()))
        vm.onEvent(WorkflowEvent.ChooseSignature)
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
    fun `a reminder goes to the current round's pending approvers, signed with my id and designation key`() =
        runTest(dispatcher) {
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
        assertEquals("me", request.sentBy, "sent_by is the member ID, never the name")
        assertEquals("2nd AD", request.sentByRole, "the designation key, the text when the crew row has none")
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
            vm.onEvent(WorkflowEvent.PickPublishChoice(PublishChoice.Replace))
            assertNull(assertIs<SheetDialog.Publish>(vm.currentState.dialog).choice, "nothing to swap: no Replace")
            vm.onEvent(WorkflowEvent.PickPublishChoice(PublishChoice.Continuation))
            vm.onEvent(WorkflowEvent.EditPublishNotes(" Call time moved "))
            vm.onEvent(WorkflowEvent.ConfirmPublish)
            settle()

            assertEquals(listOf("r1" to true), repository.publishes)
            assertEquals(listOf("Call time moved"), repository.publishNotes)
            assertEquals(listOf("CallSheet_r1.pdf" to false), harness.publishing.unitPosts)
            assertEquals(listOf<String?>(null), harness.publishing.replaceChatIds)
            assertNull(vm.currentState.dialog)
            assertEquals("Published!", toasts.last().message)

            vm.onEvent(WorkflowEvent.OpenPublish(approved))
            vm.onEvent(WorkflowEvent.ContinuePublish)
            vm.onEvent(WorkflowEvent.PickPublishChoice(PublishChoice.New))
            vm.onEvent(WorkflowEvent.ConfirmPublish)
            settle()
            assertEquals("CallSheet_r1.pdf" to true, harness.publishing.unitPosts.last(), "New replaces")
        }

    @Test
    fun `Replace offers the unit's live documents, newest first, and retires only the one picked`() =
        runTest(dispatcher) {
            repository.rows = drafts()
            harness.publishing.unitMessages = ZillitResult.Success(
                listOf(
                    UnitMessage("m1", isDocument = true, hasMedia = true, name = "Day 1.pdf", createdMs = 10),
                    UnitMessage("m2", isDocument = true, hasMedia = true, name = "Day 2.pdf", createdMs = 20),
                    UnitMessage("m3", isDocument = true, hasMedia = true, archived = true, createdMs = 30),
                ),
            )
            val vm = harness.start(this)
            val approved = Samples.row("r1", "Day 1", status = CallSheetStatus.ApprovedForPublish)

            vm.onEvent(WorkflowEvent.OpenPublish(approved))
            settle()
            val publish = assertIs<SheetDialog.Publish>(vm.currentState.dialog)
            assertEquals(listOf("m2", "m1"), publish.replaceTargets.map { it.chatId }, "read on OPEN, archived out")
            assertEquals("m2", publish.replaceChatId, "seeded with the newest")
            vm.onEvent(WorkflowEvent.ContinuePublish)
            vm.onEvent(WorkflowEvent.PickPublishChoice(PublishChoice.Replace))
            vm.onEvent(WorkflowEvent.PickReplaceTarget("m9"))
            val picked = assertIs<SheetDialog.Publish>(vm.currentState.dialog).replaceChatId
            assertEquals("m2", picked, "unknown ids are ignored")
            vm.onEvent(WorkflowEvent.PickReplaceTarget("m1"))
            vm.onEvent(WorkflowEvent.ConfirmPublish)
            settle()

            assertEquals(listOf("r1" to false), repository.publishes, "the publish call knows CONTINUATION or NEW")
            val posts = harness.publishing.unitPosts
            assertEquals(listOf("CallSheet_r1.pdf" to false), posts, "the wipe flag follows the choice")
            assertEquals(listOf<String?>("m1"), harness.publishing.replaceChatIds)
        }

    @Test
    fun `a Replace whose target vanished is said, and the sheet stays published`() = runTest(dispatcher) {
        repository.rows = drafts()
        harness.publishing.unitMessages = ZillitResult.Success(
            listOf(UnitMessage("m1", isDocument = true, hasMedia = true, name = "Day 1.pdf", createdMs = 10)),
        )
        harness.publishing.unitPostAnswer =
            ZillitResult.Failure(ZillitError.Http(status = 400, serverMessage = "unit_chat_replace_target_not_found"))
        val vm = harness.start(this)
        vm.onEvent(WorkflowEvent.OpenPublish(Samples.row("r1", "Day 1", status = CallSheetStatus.ApprovedForPublish)))
        settle()
        vm.onEvent(WorkflowEvent.ContinuePublish)
        vm.onEvent(WorkflowEvent.PickPublishChoice(PublishChoice.Replace))
        vm.onEvent(WorkflowEvent.ConfirmPublish)
        settle()
        assertEquals(listOf("r1" to false), repository.publishes)
        assertEquals("That document no longer exists — nothing was replaced.", toasts.last().message)
        assertTrue(toasts.last().isError)
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
    fun `badges are read per sheet and kind on the list on screen, never unit-wide`() = runTest(dispatcher) {
        repository.rows = drafts("r1")
        harness.badges.leaves.value = listOf(
            BadgeLeaf(SheetBadges.UNIT_DRAFTS, level2 = "comment", level3 = "r1"),
            BadgeLeaf(SheetBadges.UNIT_DRAFTS, level2 = "report", level3 = "r1", unread = 2),
            BadgeLeaf(SheetBadges.UNIT_APPROVAL, level1 = "sent", level2 = "report", level3 = "r2"),
        )
        val vm = harness.start(this)
        settle()
        assertEquals(1, vm.currentState.unreadComments("r1"))
        assertEquals(2, vm.currentState.unreadReports("r1"))
        assertEquals(0, vm.currentState.unreadReports("r2"), "another surface's rows do not show here")
        assertEquals(3, vm.currentState.tabBadge(SheetTab.Drafts))
        assertEquals(1, vm.currentState.tabBadge(SheetTab.Approvals))

        vm.onEvent(ListEvent.OpenComments(Samples.row("r1", "Day 1"), readOnly = false))
        settle()
        assertEquals(listOf(BadgeRead(BadgeSurface.Drafts, BadgeKind.Comment, "r1")), harness.badges.reads)
        vm.onEvent(DialogEvent.Dismiss)
        vm.onEvent(ListEvent.View(Samples.row("r1", "Day 1")))
        settle()
        assertEquals(BadgeRead(BadgeSurface.Drafts, BadgeKind.Report, "r1"), harness.badges.reads.last())
        vm.onEvent(ListEvent.View(Samples.row("r3", "Day 3")))
        settle()
        assertEquals(2, harness.badges.reads.size, "a row without a badge sends no read")
    }

    @Test
    fun `opening a sheet from Sent also clears its Received leaves when Received is hidden`() = runTest(dispatcher) {
        repository.rows = drafts()
        harness.badges.leaves.value = listOf(
            BadgeLeaf(SheetBadges.UNIT_APPROVAL, level1 = "sent", level2 = "report", level3 = "r1"),
            BadgeLeaf(SheetBadges.UNIT_APPROVAL, level1 = "received", level2 = "report", level3 = "r1"),
        )
        val vm = harness.start(this)
        vm.onEvent(ListEvent.OpenTab(SheetTab.Approvals))
        vm.onEvent(ListEvent.OpenSection(ApprovalSection.Sent))
        settle()
        assertEquals(listOf(ApprovalSection.Sent, ApprovalSection.Finalized), vm.currentState.sections)
        vm.onEvent(ListEvent.View(Samples.row("r1", "Day 1")))
        settle()
        assertEquals(
            listOf(
                BadgeRead(BadgeSurface.Sent, BadgeKind.Report, "r1"),
                BadgeRead(BadgeSurface.Received, BadgeKind.Report, "r1"),
            ),
            harness.badges.reads,
        )
    }

    @Test
    fun `Published drains on entry per leaf, and at app level for a viewer who has no Published tab`() =
        runTest(dispatcher) {
            repository.rows = drafts()
            harness.badges.leaves.value = listOf(
                BadgeLeaf(SheetBadges.UNIT_PUBLISHED, level2 = "report", level3 = "p1"),
                BadgeLeaf(SheetBadges.UNIT_PUBLISHED, level2 = "comment", level3 = "p2"),
            )
            val poster = harness.start(this)
            assertTrue(harness.badges.reads.isEmpty(), "a poster's tab is not drained before it is drawn")
            poster.onEvent(ListEvent.OpenTab(SheetTab.Published))
            settle()
            assertEquals(
                setOf(
                    BadgeRead(BadgeSurface.Published, BadgeKind.Report, "p1"),
                    BadgeRead(BadgeSurface.Published, BadgeKind.Comment, "p2"),
                ),
                harness.badges.reads.toSet(),
            )
            harness.badges.reads.clear()
            val landed = BadgeLeaf(SheetBadges.UNIT_PUBLISHED, level2 = "report", level3 = "p3")
            harness.badges.leaves.value = listOf(landed)
            settle()
            val p3 = BadgeRead(BadgeSurface.Published, BadgeKind.Report, "p3")
            assertEquals(listOf(p3), harness.badges.reads, "a badge landing while open is read too")

            val viewerHarness = SheetHarness(dispatcher)
            viewerHarness.repository.metadata = SheetMetadata(internalReceiverIds = listOf("me"))
            viewerHarness.badges.leaves.value =
                listOf(BadgeLeaf(SheetBadges.UNIT_PUBLISHED, level2 = "report", level3 = "p1"))
            val viewer = viewerHarness.start(this, Samples.author.copy(canPost = false))
            assertEquals(listOf(SheetTab.Drafts), viewer.currentState.tabs)
            val p1 = BadgeRead(BadgeSurface.Published, BadgeKind.Report, "p1")
            assertEquals(listOf(p1), viewerHarness.badges.reads, "drained at app level, tab or not")
        }

    @Test
    fun `anyone who sees a row may send it for chat until it locks, poster or not`() = runTest(dispatcher) {
        repository.rows = { query -> if (query.approverId == "me") listOf(awaitingMySignature()) else emptyList() }
        repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
        val vm = harness.start(this, Samples.author.copy(canPost = false))
        vm.onEvent(WorkflowEvent.OpenSendForChat(awaitingMySignature()))
        assertIs<SheetDialog.ChatSend>(vm.currentState.dialog)
        vm.onEvent(DialogEvent.Dismiss)
        val locked = Samples.row("r2", "Day 2", status = CallSheetStatus.ApprovedForPublish)
        vm.onEvent(WorkflowEvent.OpenSendForChat(locked))
        assertNull(vm.currentState.dialog, "not once final-approved")
    }

    @Test
    fun `everyone reads a thread, only the creator and the internal recipients may post`() = runTest(dispatcher) {
        repository.rows = { query -> if (query.approverId == "me") listOf(awaitingMySignature()) else emptyList() }
        repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
        val vm = harness.start(this, Samples.author.copy(canPost = false))
        vm.onEvent(ListEvent.OpenComments(awaitingMySignature(), readOnly = false))
        settle()
        assertTrue(assertIs<SheetDialog.Comments>(vm.currentState.dialog).readOnly, "u2's sheet, and I am no recipient")
        vm.onEvent(DialogEvent.EditCommentDraft("Not mine"))
        vm.onEvent(DialogEvent.SendComment)
        settle()
        assertTrue(repository.commentAdds.isEmpty())

        vm.onEvent(DialogEvent.Dismiss)
        vm.onEvent(ListEvent.OpenComments(Samples.row("r5", "Mine", createdById = "me"), readOnly = false))
        settle()
        assertFalse(assertIs<SheetDialog.Comments>(vm.currentState.dialog).readOnly, "the creator writes")

        val recipientHarness = SheetHarness(dispatcher)
        recipientHarness.repository.metadata =
            SheetMetadata(finalApproverIds = listOf("me"), internalReceiverIds = listOf("me"))
        val recipient = recipientHarness.start(this, Samples.author.copy(canPost = false))
        recipient.onEvent(ListEvent.OpenComments(awaitingMySignature(), readOnly = false))
        settle()
        assertFalse(assertIs<SheetDialog.Comments>(recipient.currentState.dialog).readOnly, "a recipient writes")
    }

    @Test
    fun `a busy dialog ignores a second confirm and cannot be dismissed`() = runTest(dispatcher) {
        repository.rows = drafts()
        val vm = harness.start(this)
        vm.onEvent(WorkflowEvent.OpenPublish(Samples.row("r1", "Day 1", status = CallSheetStatus.ApprovedForPublish)))
        vm.onEvent(WorkflowEvent.ContinuePublish)
        vm.onEvent(WorkflowEvent.PickPublishChoice(PublishChoice.New))
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
