package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.DraftChip
import com.zillit.desktop.feature.callsheet.domain.ReminderSender
import com.zillit.desktop.feature.callsheet.domain.ReplaceTarget
import com.zillit.desktop.feature.callsheet.domain.SharedHeader
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetReminder
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.UnitMessage
import com.zillit.desktop.feature.callsheet.domain.actionableRequest
import com.zillit.desktop.feature.callsheet.domain.approvalCount
import com.zillit.desktop.feature.callsheet.domain.approvalSections
import com.zillit.desktop.feature.callsheet.domain.approvalStatusEntries
import com.zillit.desktop.feature.callsheet.domain.approverCandidates
import com.zillit.desktop.feature.callsheet.domain.approverIdsFromSheet
import com.zillit.desktop.feature.callsheet.domain.canApproveReject
import com.zillit.desktop.feature.callsheet.domain.canPostComments
import com.zillit.desktop.feature.callsheet.domain.canPublish
import com.zillit.desktop.feature.callsheet.domain.commentAllowed
import com.zillit.desktop.feature.callsheet.domain.draftsQuery
import com.zillit.desktop.feature.callsheet.domain.filterDrafts
import com.zillit.desktop.feature.callsheet.domain.formatDateTime
import com.zillit.desktop.feature.callsheet.domain.initialLanding
import com.zillit.desktop.feature.callsheet.domain.isInternalOnly
import com.zillit.desktop.feature.callsheet.domain.isListedMember
import com.zillit.desktop.feature.callsheet.domain.latestReminder
import com.zillit.desktop.feature.callsheet.domain.mergeApprovalRequests
import com.zillit.desktop.feature.callsheet.domain.pendingFinalRequest
import com.zillit.desktop.feature.callsheet.domain.receivedRows
import com.zillit.desktop.feature.callsheet.domain.relativeLong
import com.zillit.desktop.feature.callsheet.domain.relativeShort
import com.zillit.desktop.feature.callsheet.domain.reminderAssigneeIds
import com.zillit.desktop.feature.callsheet.domain.reminderSender
import com.zillit.desktop.feature.callsheet.domain.replaceTargets
import com.zillit.desktop.feature.callsheet.domain.resolveSection
import com.zillit.desktop.feature.callsheet.domain.resolveSharedForTemplate
import com.zillit.desktop.feature.callsheet.domain.sanitizeUserIds
import com.zillit.desktop.feature.callsheet.domain.sendActions
import com.zillit.desktop.feature.callsheet.domain.sendForChatAllowed
import com.zillit.desktop.feature.callsheet.domain.sheetTabs
import com.zillit.desktop.feature.callsheet.domain.shouldWriteApproverMeta
import com.zillit.desktop.feature.callsheet.domain.sheetToolName
import com.zillit.desktop.feature.callsheet.domain.shootDayLabel
import com.zillit.desktop.feature.callsheet.domain.shouldShowReminderBell
import com.zillit.desktop.feature.callsheet.domain.stageForStatus
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's `callsheetUtils` and tab rules, as the desktop decides them. */
class SheetRulesTest {

    private fun request(
        id: String,
        who: String,
        stage: String = "FINAL",
        round: Int = 1,
        status: String = "PENDING",
    ) = ApprovalRequest(id = id, assigneeId = who, assigneeName = who, stage = stage, status = status, round = round)

    private fun row(
        status: CallSheetStatus,
        approvals: List<ApprovalRequest> = emptyList(),
        createdById: String = "author",
        reminders: List<SheetReminder> = emptyList(),
        shared: SharedHeader? = null,
        approvalsIncluded: Boolean = true,
    ) = CallSheetSummary(
        id = "r1",
        serialNo = "1",
        name = "Day 3",
        status = status,
        createdBy = "Author",
        createdById = createdById,
        approvals = approvals,
        approvalsIncluded = approvalsIncluded,
        reminders = reminders,
        shared = shared,
    )

    @Test
    fun `tabs by rights - posters get every list, a viewer only the tabs the metadata lists them on`() {
        fun tabs(poster: Boolean, grid: Boolean = false, approver: Boolean = false, receiver: Boolean = false) =
            sheetTabs(poster, grid, isFinalApprover = approver, isInternalReceiver = receiver)
        assertEquals(SheetTab.entries, tabs(poster = true, grid = true))
        assertEquals(listOf(SheetTab.Drafts, SheetTab.Approvals, SheetTab.Published), tabs(poster = true))
        assertEquals(listOf(SheetTab.Approvals), tabs(poster = false, grid = true, approver = true))
        assertEquals(listOf(SheetTab.Drafts), tabs(poster = false, receiver = true))
        assertEquals(
            listOf(SheetTab.Drafts, SheetTab.Approvals),
            tabs(poster = false, approver = true, receiver = true),
        )
        assertEquals(emptyList(), tabs(poster = false), "named by neither list → no tabs, never Published")
        assertEquals(
            listOf(SheetTab.Drafts, SheetTab.Approvals),
            sheetTabs(false, false, isFinalApprover = false, isInternalReceiver = false, metadataUnavailable = true),
            "a FAILED metadata read fails open",
        )
        assertEquals("Call Sheet Creation", sheetToolName(isPoster = true))
        assertEquals("Drafts Call Sheet", sheetToolName(isPoster = false))
    }

    @Test
    fun `list membership compares trimmed strings, and a blank id is on no list`() {
        assertTrue(isListedMember(listOf(" 42 ", "u2"), "42"))
        assertFalse(isListedMember(listOf("u2"), ""))
        assertFalse(isListedMember(listOf("u2"), null))
        assertFalse(isListedMember(emptyList(), "u2"))
    }

    @Test
    fun `the first landing stays inside the visible tabs`() {
        assertNull(initialLanding(isPoster = true, tabs = SheetTab.entries), "a poster needs no move")
        assertEquals(SheetTab.Approvals, initialLanding(false, listOf(SheetTab.Drafts, SheetTab.Approvals)))
        assertEquals(SheetTab.Drafts, initialLanding(false, listOf(SheetTab.Drafts)))
        assertNull(initialLanding(false, emptyList()), "nowhere to go")
    }

    @Test
    fun `only the creator and the internal-distribution recipients may post in a thread`() {
        val mine = row(CallSheetStatus.PendingApproval, createdById = "me")
        val theirs = row(CallSheetStatus.PendingApproval, createdById = "u2")
        assertTrue(canPostComments(mine, "me"))
        assertFalse(canPostComments(theirs, "me"))
        assertTrue(canPostComments(theirs, "me", isInternalReceiver = true))
        assertTrue(canPostComments(theirs.copy(createdBy = "Author"), null, userName = "Author"), "no id → the name")
        assertFalse(canPostComments(theirs, null, userName = ""))
    }

    @Test
    fun `an emptied approver list is written only when the editor opened with approvers`() {
        assertTrue(shouldWriteApproverMeta(listOf("u2"), emptyList()))
        assertTrue(shouldWriteApproverMeta(emptyList(), listOf("u2")), "a removal must reach the merging PUT")
        assertFalse(shouldWriteApproverMeta(emptyList(), emptyList()), "a fresh document never clears the default")
    }

    @Test
    fun `a reminder's sender resolves by id to who they are today, else reads verbatim`() {
        val crew = Samples.crew
        val reminder = SheetReminder("m1", sentBy = "u2", sentById = "u2", sentByRole = "producer_label")
        assertEquals(ReminderSender("Uma", "Producer"), reminderSender(crew, reminder))
        val roleless = crew.map { if (it.userId == "u2") it.copy(designation = "") else it }
        assertEquals("producer_label", reminderSender(roleless, reminder).role, "falls back to the recorded role")
        val old = SheetReminder("m0", sentBy = "Ankit Lava", sentById = "", sentByRole = "1st AD")
        assertEquals(ReminderSender("Ankit Lava", "1st AD"), reminderSender(crew, old), "a name matches nobody")
        val byId = SheetReminder("m2", sentBy = "u3", sentById = "")
        assertEquals("Vic", reminderSender(crew, byId).name, "sent_by alone carries the id")
    }

    @Test
    fun `replace targets are live document messages keyed on their id, newest first`() {
        val targets = replaceTargets(
            listOf(
                UnitMessage("m1", isDocument = true, hasMedia = true, name = "Day 1.pdf", createdMs = 10),
                UnitMessage("m2", isDocument = true, hasMedia = true, name = "Day 2.pdf", createdMs = 30),
                UnitMessage("m3", isDocument = true, hasMedia = true, deleted = true, createdMs = 40),
                UnitMessage("m4", isDocument = true, hasMedia = true, archived = true, createdMs = 50),
                UnitMessage("m5", isDocument = false, hasMedia = true, name = "photo.jpg", createdMs = 60),
                UnitMessage("", isDocument = true, hasMedia = true, name = "pending.pdf", createdMs = 70),
                UnitMessage("m7", isDocument = true, hasMedia = true, createdMs = 20),
            ),
        )
        assertEquals(
            listOf(
                ReplaceTarget("m2", "Day 2.pdf"),
                ReplaceTarget("m7", "Untitled document"),
                ReplaceTarget("m1", "Day 1.pdf"),
            ),
            targets,
        )
    }

    @Test
    fun `received shows for a poster only when they are a project final approver`() {
        assertEquals(ApprovalSection.entries, approvalSections(isPoster = true, isFinalApprover = true))
        assertEquals(
            listOf(ApprovalSection.Sent, ApprovalSection.Finalized),
            approvalSections(isPoster = true, isFinalApprover = false),
        )
        assertEquals(
            listOf(ApprovalSection.Received, ApprovalSection.Finalized),
            approvalSections(isPoster = false, isFinalApprover = false),
        )
        val sections = listOf(ApprovalSection.Received, ApprovalSection.Finalized)
        assertEquals(ApprovalSection.Finalized, resolveSection(sections, ApprovalSection.Finalized))
        assertEquals(ApprovalSection.Received, resolveSection(sections, ApprovalSection.Sent))
    }

    @Test
    fun `drafts are project-wide for posters, approver-scoped for viewers, and nothing without an id`() {
        val poster = draftsQuery("p1", isPoster = true, memberId = "me")!!
        assertEquals(CallSheetStatus.DRAFT_TAB, poster.statuses)
        assertNull(poster.approverId)
        assertEquals("me", draftsQuery("p1", isPoster = false, memberId = "me")!!.approverId)
        assertNull(draftsQuery("p1", isPoster = false, memberId = ""))
    }

    @Test
    fun `received keeps signature-phase sheets where my current-round FINAL request names me`() {
        val rows = listOf(
            row(CallSheetStatus.PendingApproval, listOf(request("a", "me"))),
            row(CallSheetStatus.PendingApproval, listOf(request("b", "me", round = 1), request("c", "x", round = 2))),
            row(CallSheetStatus.PendingApproval, listOf(request("d", "me", stage = "INTERNAL"))),
            row(CallSheetStatus.PendingInternalApproval, listOf(request("e", "me"))),
        )
        assertEquals(listOf("a"), receivedRows(rows, "me").map { it.approvals.first().id })
        assertTrue(receivedRows(rows, null).isEmpty())
    }

    @Test
    fun `a Sent row without requests takes them from the approver listing, never overwriting its own`() {
        val bare = row(CallSheetStatus.PendingApproval, approvalsIncluded = false)
        val own = row(CallSheetStatus.PendingApproval, listOf(request("own", "me"))).copy(id = "r2")
        val fromApprover = listOf(row(CallSheetStatus.PendingApproval, listOf(request("x", "me"))))
        val merged = mergeApprovalRequests(listOf(bare, own), fromApprover)
        assertEquals(listOf("x"), merged[0].approvals.map { it.id })
        assertTrue(merged[0].approvalsIncluded)
        assertEquals(listOf("own"), merged[1].approvals.map { it.id })
    }

    @Test
    fun `approver candidates are accepted members not yet chosen and not me`() {
        val members = listOf(
            SheetMember("me", "Me", status = "accepted"),
            SheetMember("a", "Ann", status = "accepted"),
            SheetMember("b", "Bob", status = "accepted"),
            SheetMember("c", "Cal", status = "left"),
            SheetMember("", "Nobody", status = "accepted"),
        )
        assertEquals(listOf("b"), approverCandidates(members, listOf(" a "), "me").map { it.userId })
    }

    @Test
    fun `a sheet that states its approvers decides, an absent list defers to the project`() {
        assertEquals(listOf("a"), approverIdsFromSheet(SharedHeader(approverIds = listOf("a"))))
        assertEquals(emptyList(), approverIdsFromSheet(SharedHeader(approverIds = emptyList())))
        assertNull(approverIdsFromSheet(SharedHeader(approverIdsStated = false)))
        assertNull(approverIdsFromSheet(null))
    }

    @Test
    fun `reminders go to the pending approvers of the current round of the sheet's stage`() {
        val approvals = listOf(
            request("a", "u1", round = 1),
            request("b", "u2", round = 2),
            request("c", "u3", round = 2, status = "APPROVED"),
            request("d", "u4", stage = "INTERNAL"),
        )
        assertEquals(listOf("u2"), reminderAssigneeIds(CallSheetStatus.PendingApproval, approvals))
        assertEquals(listOf("u4"), reminderAssigneeIds(CallSheetStatus.PendingInternalApproval, approvals))
        assertEquals(
            listOf("u4"),
            reminderAssigneeIds(
                CallSheetStatus.PendingInternalApproval,
                listOf(request("d", "u4", stage = "INTERNAL")),
            ),
        )
        assertTrue(reminderAssigneeIds(CallSheetStatus.PendingApproval, emptyList()).isEmpty())
    }

    @Test
    fun `approval count reads the current round of the stage the status names`() {
        val approvals = listOf(
            request("a", "u1", round = 1, status = "APPROVED"),
            request("b", "u2", round = 2, status = "APPROVED"),
            request("c", "u3", round = 2),
            request("d", "u4", stage = "INTERNAL", status = "APPROVED"),
        )
        assertEquals(1 to 2, approvalCount(CallSheetStatus.PendingApproval, approvals))
        assertEquals(1 to 1, approvalCount(CallSheetStatus.PendingInternalApproval, approvals))
        assertEquals("INTERNAL", stageForStatus(CallSheetStatus.PendingInternalApproval))
        assertEquals("FINAL", stageForStatus(CallSheetStatus.ApprovalRejected))
    }

    @Test
    fun `approval status entries come pending first, named from the crew`() {
        val members = listOf(SheetMember("u2", "Uma", designation = "Producer"))
        val entries = approvalStatusEntries(
            listOf(
                request("a", "u1", status = "APPROVED"),
                request("b", "u2"),
                request("c", "u3", status = "REJECTED"),
                request("old", "u9", round = 0),
            ),
            "FINAL",
            members,
        )
        assertEquals(listOf("b", "a", "c"), entries.map { it.id })
        assertEquals("Uma", entries[0].name)
        assertEquals("Producer", entries[0].role)
        assertEquals("Unknown", entries[1].role)
        assertEquals("REJECTED", entries[2].status)
    }

    @Test
    fun `my actionable request prefers FINAL, and Approve and Reject need one`() {
        val both = row(
            CallSheetStatus.PendingApproval,
            listOf(request("i", "me", stage = "INTERNAL"), request("f", "me")),
        )
        assertEquals("f", actionableRequest(both, "me")?.id)
        assertTrue(canApproveReject(both, "me"))
        val internalOnly = row(CallSheetStatus.PendingApproval, listOf(request("i", "me", stage = "INTERNAL")))
        assertEquals("i", actionableRequest(internalOnly, "me")?.id)
        assertFalse(canApproveReject(internalOnly, "me"))
        assertTrue(isInternalOnly(internalOnly, "me"))
        assertFalse(isInternalOnly(both, "me"))
        assertNull(pendingFinalRequest(both.copy(status = CallSheetStatus.Draft), "me"))
        assertEquals("f", pendingFinalRequest(both, "me")?.id)
    }

    @Test
    fun `the reminder bell rings for a pending approver who was reminded, never for the creator`() {
        val reminder = SheetReminder(id = "m1", approvalRequestId = "f", createdOn = 5L)
        val later = SheetReminder(id = "m2", approvalRequestId = "f", createdOn = 9L)
        val mine = row(CallSheetStatus.PendingApproval, listOf(request("f", "me")), reminders = listOf(reminder, later))
        assertTrue(shouldShowReminderBell(mine, "me"))
        assertEquals("m2", latestReminder(mine, "me")?.id)
        assertFalse(shouldShowReminderBell(mine.copy(createdById = "me"), "me"))
        assertFalse(shouldShowReminderBell(mine.copy(reminders = emptyList()), "me"))
        val done = mine.copy(approvals = listOf(request("f", "me", status = "APPROVED")))
        assertFalse(shouldShowReminderBell(done, "me"))
    }

    @Test
    fun `publish is the creator's on a finally approved sheet`() {
        assertTrue(canPublish(row(CallSheetStatus.ApprovedForPublish, createdById = "me"), "me"))
        assertFalse(canPublish(row(CallSheetStatus.ApprovedForPublish, createdById = "you"), "me"))
        assertFalse(canPublish(row(CallSheetStatus.PendingApproval, createdById = "me"), "me"))
    }

    @Test
    fun `send actions follow the status, and unread comments keep Comment on a draft`() {
        val draft = sendActions(CallSheetStatus.Draft)
        assertTrue(draft.sendForSignature && draft.sendForComments && !draft.readComments)
        assertTrue(sendActions(CallSheetStatus.Draft, unreadComments = 1).readComments)
        val out = sendActions(CallSheetStatus.PendingInternalApproval)
        assertTrue(out.sendForSignature && !out.sendForComments && out.readComments)
        val locked = sendActions(CallSheetStatus.Published)
        assertFalse(locked.sendForSignature || locked.sendForComments || locked.readComments)
        assertTrue(sendForChatAllowed(CallSheetStatus.Draft) && sendForChatAllowed(CallSheetStatus.PendingApproval))
        assertFalse(sendForChatAllowed(CallSheetStatus.ApprovedForPublish))
        assertFalse(sendForChatAllowed(CallSheetStatus.Published))
        assertFalse(commentAllowed(CallSheetStatus.Published, 0))
        assertTrue(commentAllowed(CallSheetStatus.Published, 2))
    }

    @Test
    fun `the drafts chips slice one loaded list`() {
        val rows = listOf(
            row(CallSheetStatus.Draft),
            row(CallSheetStatus.PendingInternalApproval),
            row(CallSheetStatus.InternalApproved),
        )
        assertEquals(3, filterDrafts(rows, DraftChip.All).size)
        assertEquals(1, filterDrafts(rows, DraftChip.Drafts).size)
        assertEquals(2, filterDrafts(rows, DraftChip.Comments).size)
    }

    @Test
    fun `a stock template gets today and the next day, a saved one only its blanks filled`() {
        val meta = SheetMetadata(currentShootDay = 2, totalDays = "20", finalApproverIds = listOf("m1"))
        val (stock, handed) = resolveSharedForTemplate(SharedHeader(), meta, fromSavedTemplate = false, todayMs = 7L)
        assertEquals(3, handed)
        assertEquals("3", stock.shootDayNumber)
        assertEquals(7L, stock.dateMs)
        assertEquals(listOf("m1"), stock.approverIds)
        assertEquals("20", stock.totalDays)

        val saved = SharedHeader(shootDayNumber = "9", dateMs = 3L, totalDays = "", approverIds = listOf("s1"))
        val (kept, none) = resolveSharedForTemplate(saved, meta, fromSavedTemplate = true, todayMs = 7L)
        assertEquals(0, none)
        assertEquals("9", kept.shootDayNumber)
        assertEquals(3L, kept.dateMs)
        assertEquals("20", kept.totalDays)
        assertEquals(listOf("s1"), kept.approverIds)
    }

    @Test
    fun `labels and formats match the web`() {
        assertEquals("3 of 50", shootDayLabel(SharedHeader(shootDayNumber = "3", totalDays = "50")))
        assertEquals("3 of 40", shootDayLabel(SharedHeader(shootDayNumber = "3"), fallbackTotalDays = "40"))
        assertEquals("3", shootDayLabel(SharedHeader(shootDayNumber = "3")))
        assertEquals("-", shootDayLabel(null))
        assertEquals("03 SEPT 2026 | 02:05 PM", formatDateTime(1_788_444_300_000L, TimeZone.UTC))
        assertEquals("-", formatDateTime(null))
        assertEquals("just now", relativeShort(1000L, 2000L))
        assertEquals("2h ago", relativeShort(1_000L, 1_000L + 2L * 3_600_000L))
        assertEquals("1 day ago", relativeLong(1_000L, 1_000L + 86_400_000L))
        assertEquals("—", relativeShort(0L, 1L), "an unset stamp is a dash, never the epoch")
        assertEquals("a,b", sanitizeUserIds("a, x ,b", listOf(SheetMember("a", ""), SheetMember("b", ""))))
        assertEquals("a,x", sanitizeUserIds("a, x", emptyList()), "nothing is wiped before the crew loads")
    }
}
