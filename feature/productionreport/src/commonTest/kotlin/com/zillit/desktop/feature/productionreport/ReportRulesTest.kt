package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.CellValue
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.CellRow
import com.zillit.desktop.feature.productionreport.domain.DraftChip
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.RenderKind
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReminderSender
import com.zillit.desktop.feature.productionreport.domain.ReportReminder
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.SharedHeader
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.ViewOnlyTabs
import com.zillit.desktop.feature.productionreport.domain.actionableRequest
import com.zillit.desktop.feature.productionreport.domain.approvalCount
import com.zillit.desktop.feature.productionreport.domain.approvalSections
import com.zillit.desktop.feature.productionreport.domain.approvalStatusEntries
import com.zillit.desktop.feature.productionreport.domain.approverCandidates
import com.zillit.desktop.feature.productionreport.domain.approverIdsFromReport
import com.zillit.desktop.feature.productionreport.domain.canApproveReject
import com.zillit.desktop.feature.productionreport.domain.canPostComments
import com.zillit.desktop.feature.productionreport.domain.canPublish
import com.zillit.desktop.feature.productionreport.domain.crewCallFromCallSheet
import com.zillit.desktop.feature.productionreport.domain.deleteQuestion
import com.zillit.desktop.feature.productionreport.domain.filterDrafts
import com.zillit.desktop.feature.productionreport.domain.formatDateTime
import com.zillit.desktop.feature.productionreport.domain.hasRequiredCallTimes
import com.zillit.desktop.feature.productionreport.domain.isListedMember
import com.zillit.desktop.feature.productionreport.domain.manageTabs
import com.zillit.desktop.feature.productionreport.domain.receivedRows
import com.zillit.desktop.feature.productionreport.domain.relativeLong
import com.zillit.desktop.feature.productionreport.domain.relativeShort
import com.zillit.desktop.feature.productionreport.domain.reminderAssigneeIds
import com.zillit.desktop.feature.productionreport.domain.reminderSender
import com.zillit.desktop.feature.productionreport.domain.resolveApproverIdsForSend
import com.zillit.desktop.feature.productionreport.domain.resolveSection
import com.zillit.desktop.feature.productionreport.domain.resolveSharedForTemplate
import com.zillit.desktop.feature.productionreport.domain.sendActions
import com.zillit.desktop.feature.productionreport.domain.shootDayLabel
import com.zillit.desktop.feature.productionreport.domain.shouldRegenerateEmployeeRows
import com.zillit.desktop.feature.productionreport.domain.shouldShowReminderBell
import com.zillit.desktop.feature.productionreport.domain.shouldWriteApproverMeta
import com.zillit.desktop.feature.productionreport.domain.templateSectionTitles
import com.zillit.desktop.feature.productionreport.domain.userReminders
import com.zillit.desktop.feature.productionreport.domain.viewOnlyTabAccess
import com.zillit.desktop.feature.productionreport.domain.visibleBadgeCount
import com.zillit.desktop.feature.productionreport.domain.withCrewCall
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's `productionReportUtils` rules, as the desktop decides them. */
class ReportRulesTest {

    private fun request(
        id: String,
        who: String,
        stage: String = "FINAL",
        round: Int = 1,
        status: String = "PENDING",
    ) = ApprovalRequest(
        id = id,
        assigneeId = who,
        assigneeName = who,
        role = "",
        stage = stage,
        status = status,
        round = round,
    )

    private fun row(
        status: ReportStatus,
        approvals: List<ApprovalRequest> = emptyList(),
        createdById: String = "author",
        reminders: List<ReportReminder> = emptyList(),
        shared: SharedHeader? = null,
        name: String = "Day 3",
    ) = ReportSummary(
        id = "r1",
        serialNo = "1",
        name = name,
        status = status,
        createdBy = "Author",
        createdById = createdById,
        approvals = approvals,
        reminders = reminders,
        shared = shared,
    )

    @Test
    fun `posting rights open every tab, and a viewer's tabs come from the two project lists alone`() {
        assertEquals(ManageTab.entries, manageTabs(isPoster = true))
        assertEquals(listOf(ManageTab.Drafts), manageTabs(isPoster = false, isInternalReceiver = true))
        assertEquals(listOf(ManageTab.Approvals), manageTabs(isPoster = false, isFinalApprover = true))
        assertEquals(
            listOf(ManageTab.Drafts, ManageTab.Approvals),
            manageTabs(isPoster = false, isFinalApprover = true, isInternalReceiver = true),
            "Published is the authors' archive — never a viewer's",
        )
        assertTrue(manageTabs(isPoster = false).isEmpty(), "named by neither list, nothing to do here")
        assertEquals(
            ViewOnlyTabs(drafts = true, approvals = true),
            viewOnlyTabAccess(isFinalApprover = false, isInternalReceiver = false, metadataUnavailable = true),
            "a FAILED metadata read fails open",
        )
        assertEquals(
            ViewOnlyTabs(drafts = false, approvals = true),
            viewOnlyTabAccess(isFinalApprover = true, isInternalReceiver = false, metadataUnavailable = false),
        )
    }

    @Test
    fun `list membership is text-compared and trimmed, and a blank id is on no list`() {
        assertTrue(isListedMember(listOf(" 42 ", "x"), "42"))
        assertFalse(isListedMember(listOf("42"), "4"))
        assertFalse(isListedMember(listOf("", " "), ""))
        assertFalse(isListedMember(emptyList(), null))
    }

    @Test
    fun `only the creator and a comment recipient may post in a thread, by id never by name`() {
        val mine = row(ReportStatus.PendingApproval, createdById = "me")
        assertTrue(canPostComments(mine, "me", isInternalReceiver = false))
        assertFalse(canPostComments(mine, "other", isInternalReceiver = false), "an approver reads, never writes")
        assertTrue(canPostComments(mine, "other", isInternalReceiver = true))
        assertFalse(canPostComments(mine, null, isInternalReceiver = false), "no id, no name fallback")
    }

    @Test
    fun `an emptied approver list is written only when the editor opened with approvers`() {
        assertTrue(shouldWriteApproverMeta(listOf("a"), emptyList()))
        assertTrue(shouldWriteApproverMeta(emptyList(), listOf("a")), "a removal must reach the merging PUT")
        assertFalse(shouldWriteApproverMeta(emptyList(), emptyList()), "a fresh template's [] is no statement")
    }

    @Test
    fun `a reminder's sender is resolved by member id, else shown verbatim`() {
        val members = listOf(SheetMember("u1", "Uma Now", designation = "Producer"), SheetMember("u2", "Vic", ""))
        val byId = ReportReminder("m1", sentBy = "u1", sentByRole = "old_role_label")
        assertEquals(ReminderSender("Uma Now", "Producer"), reminderSender(members, byId))
        val noTitle = ReportReminder("m2", sentBy = "u2", sentByRole = "director_label")
        assertEquals(ReminderSender("Vic", "director_label"), reminderSender(members, noTitle), "role falls back")
        val legacy = ReportReminder("m3", sentBy = "Ankit Lava", sentByRole = "1st AD")
        assertEquals(ReminderSender("Ankit Lava", "1st AD"), reminderSender(members, legacy), "a NAME matches nobody")
    }

    private fun times(vararg lines: Pair<String, String>) = SheetPayload(
        rows = listOf(
            PageRow(
                0,
                cells = listOf(
                    PageCell(
                        order = 0,
                        title = "Call Times",
                        columns = listOf(ColumnSpec(label = "Field"), ColumnSpec(label = "Value")),
                        rows = lines.mapIndexed { i, (label, value) ->
                            CellRow(i, listOf(CellValue(label), CellValue(value)))
                        },
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `send for signature needs every crew call and unit wrap line filled, when the layout has them`() {
        assertTrue(hasRequiredCallTimes(times("Crew Call" to "06:30", "Unit Wrap" to "19:00")))
        assertFalse(hasRequiredCallTimes(times("crew  call" to "", "Unit Wrap" to "19:00")))
        assertFalse(hasRequiredCallTimes(times("Crew Call" to "06:30", "UNIT WRAP" to "  ")))
        assertTrue(hasRequiredCallTimes(times("Shooting Call" to "")), "a layout without the lines is not held back")
        assertTrue(hasRequiredCallTimes(SheetPayload()))
    }

    @Test
    fun `the day's crew call is the call sheet's unit call, else its shooting call, into empty crew call lines`() {
        assertEquals("07:30", crewCallFromCallSheet(times("Shooting Call" to "08:00", "Unit Call" to "7:30")))
        assertEquals("08:00", crewCallFromCallSheet(times("Unit Call" to "", "Shooting Call" to "08:00")))
        assertEquals("on set", crewCallFromCallSheet(times("Unit Call" to "on set")), "free text is kept as typed")
        assertEquals("", crewCallFromCallSheet(times("Lunch" to "13:00")))
        val filled = times("Crew Call" to "", "Crew Call" to "05:00", "Unit Wrap" to "").withCrewCall("07:30")
        assertEquals(
            listOf("07:30", "05:00", ""),
            filled.rows.single().cells.single().rows.map { it.values[1].value },
            "only an EMPTY crew call line takes the seed",
        )
        assertEquals(times("Crew Call" to ""), times("Crew Call" to "").withCrewCall(""), "no seed, no change")
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
    }

    @Test
    fun `a stored section survives only while it is offered`() {
        val sections = listOf(ApprovalSection.Received, ApprovalSection.Finalized)
        assertEquals(ApprovalSection.Finalized, resolveSection(sections, ApprovalSection.Finalized))
        assertEquals(ApprovalSection.Received, resolveSection(sections, ApprovalSection.Sent))
    }

    @Test
    fun `a badge on a tab known to be empty is hidden, an unfetched tab trusts the server`() {
        assertEquals(3, visibleBadgeCount(3, rowCount = 0, loaded = false))
        assertEquals(0, visibleBadgeCount(3, rowCount = 0, loaded = true))
        assertEquals(3, visibleBadgeCount(3, rowCount = 2, loaded = true))
        assertEquals(0, visibleBadgeCount(-1, rowCount = 2, loaded = true))
    }

    @Test
    fun `received keeps signature-phase rows whose current final round names me`() {
        val mine = row(ReportStatus.PendingApproval, listOf(request("a", "me")))
        val internalOnly = row(ReportStatus.PendingApproval, listOf(request("b", "me", stage = "INTERNAL")))
        val removed = row(
            ReportStatus.ApprovalRejected,
            listOf(request("c", "me", round = 1), request("d", "x", round = 2)),
        )
        val published = row(ReportStatus.Published, listOf(request("e", "me")))
        assertEquals(listOf(mine), receivedRows(listOf(mine, internalOnly, removed, published), "me"))
    }

    @Test
    fun `approver candidates are accepted members, minus the chosen and minus me`() {
        val members = listOf(
            SheetMember("me", "Me", status = "accepted"),
            SheetMember("a", "Alice", status = "accepted"),
            SheetMember("b", "Bob", status = "pending"),
            SheetMember("c", "Cleo", status = null),
            SheetMember("d", "Dev", status = "left"),
            SheetMember("", "Nobody", status = "accepted"),
        )
        assertEquals(
            listOf("c"),
            approverCandidates(members, approverIds = listOf("a"), currentUserId = "me").map { it.userId },
        )
    }

    @Test
    fun `send resolution prefers the payload list, then an approvers block, then the project`() {
        val own = ReportDetail(row(ReportStatus.Draft), SheetPayload(shared = SharedHeader(approverIds = listOf("p1"))))
        assertEquals(listOf("p1"), resolveApproverIdsForSend(own, listOf("meta")))

        val block = PageCell(
            order = 0,
            title = "Approvers",
            columns = listOf(ColumnSpec(label = "Name")),
            rows = listOf(CellRow(0, listOf(CellValue("b1"))), CellRow(1, listOf(CellValue(" ")))),
        )
        val fromBlock = ReportDetail(
            row(ReportStatus.Draft),
            SheetPayload(rows = listOf(PageRow(0, cells = listOf(block)))),
        )
        assertEquals(listOf("b1"), approverIdsFromReport(fromBlock))

        val empty = ReportDetail(row(ReportStatus.Draft), SheetPayload())
        assertEquals(emptyList(), resolveApproverIdsForSend(empty, listOf("meta")), "a payload stating none means none")
        assertEquals(listOf("meta"), resolveApproverIdsForSend(null, listOf("meta")))
        assertNull(approverIdsFromReport(ReportDetail(row(ReportStatus.Draft), SheetPayload(), hasPayload = false)))
    }

    @Test
    fun `reminders go to the pending approvers of the stage the report is in`() {
        val approvals = listOf(
            request("f1", "fa", round = 1),
            request("i1", "ia", stage = "INTERNAL", round = 1),
            request("i2", "ib", stage = "INTERNAL", round = 2),
            request("i3", "ic", stage = "INTERNAL", round = 2, status = "APPROVED"),
        )
        assertEquals(listOf("ib"), reminderAssigneeIds(ReportStatus.PendingInternalApproval, approvals))
        assertEquals(listOf("fa"), reminderAssigneeIds(ReportStatus.PendingApproval, approvals))
        assertEquals(emptyList(), reminderAssigneeIds(ReportStatus.PendingApproval, emptyList()))
    }

    @Test
    fun `approval counts read the current round of the status's stage`() {
        val approvals = listOf(
            request("a", "x", round = 1, status = "APPROVED"),
            request("b", "y", round = 2, status = "APPROVED"),
            request("c", "z", round = 2),
            request("d", "w", stage = "INTERNAL", status = "APPROVED"),
        )
        assertEquals(1 to 2, approvalCount(ReportStatus.PendingApproval, approvals))
        assertEquals(1 to 1, approvalCount(ReportStatus.PendingInternalApproval, approvals))
        assertEquals(0 to 0, approvalCount(ReportStatus.Draft, emptyList()))
    }

    @Test
    fun `the status popover lists pending first and a blank status as pending`() {
        val entries = approvalStatusEntries(
            listOf(
                request("a", "x", status = "REJECTED"),
                request("b", "y", status = ""),
                request("c", "z", status = "APPROVED"),
            ),
            "FINAL",
        )
        assertEquals(listOf("PENDING", "APPROVED", "REJECTED"), entries.map { it.status })
    }

    @Test
    fun `approve and reject need my pending final request`() {
        val finalRow = row(ReportStatus.PendingApproval, listOf(request("a", "me")))
        val internalRow = row(ReportStatus.PendingInternalApproval, listOf(request("b", "me", stage = "INTERNAL")))
        assertEquals("a", actionableRequest(finalRow, "me")?.id)
        assertTrue(canApproveReject(finalRow, "me"))
        assertFalse(canApproveReject(internalRow, "me"))
        assertFalse(canApproveReject(finalRow, "someone"))
    }

    @Test
    fun `the reminder bell needs a reminder on my newest pending request and never rings the author`() {
        val reminder = ReportReminder(id = "rm", approvalRequestId = "a2", createdOn = 5)
        val reminded = row(
            ReportStatus.PendingApproval,
            listOf(request("a1", "me", round = 1), request("a2", "me", round = 2)),
            reminders = listOf(reminder, ReportReminder(id = "old", approvalRequestId = "a1", createdOn = 9)),
        )
        assertTrue(shouldShowReminderBell(reminded, "me"))
        assertFalse(shouldShowReminderBell(reminded.copy(createdById = "me"), "me"))
        assertEquals(listOf("old", "rm"), userReminders(reminded, "me").map { it.id }, "newest first")
    }

    @Test
    fun `only the author with posting rights publishes a finally approved report`() {
        val approved = row(ReportStatus.ApprovedForPublish, createdById = "me")
        assertTrue(canPublish(approved, isPoster = true, userId = "me"))
        assertFalse(canPublish(approved, isPoster = false, userId = "me"))
        assertFalse(canPublish(approved, isPoster = true, userId = "other"))
        assertFalse(canPublish(approved.copy(status = ReportStatus.PendingApproval), isPoster = true, userId = "me"))
    }

    @Test
    fun `send actions follow the status, and unread comments stay readable when locked`() {
        val draft = sendActions(ReportStatus.Draft)
        assertTrue(draft.sendForSignature && draft.sendForComments && !draft.readComments)
        val commenting = sendActions(ReportStatus.PendingInternalApproval)
        assertTrue(commenting.sendForSignature && !commenting.sendForComments && commenting.readComments)
        val locked = sendActions(ReportStatus.ApprovedForPublish, unreadComments = 2)
        assertTrue(!locked.sendForSignature && !locked.sendForComments && locked.readComments)
    }

    @Test
    fun `the drafts chips split drafts from reports out for comments`() {
        val rows = listOf(
            row(ReportStatus.Draft),
            row(ReportStatus.PendingInternalApproval),
            row(ReportStatus.InternalApproved),
        )
        assertEquals(3, filterDrafts(rows, DraftChip.All).size)
        assertEquals(1, filterDrafts(rows, DraftChip.Drafts).size)
        assertEquals(2, filterDrafts(rows, DraftChip.Comments).size)
    }

    @Test
    fun `shoot day labels and the delete question`() {
        assertEquals("3 of 50", shootDayLabel(SharedHeader(shootDayNumber = "3", totalDays = "50")))
        assertEquals("3 of 40", shootDayLabel(SharedHeader(shootDayNumber = "3"), fallbackTotalDays = "40"))
        assertEquals("-", shootDayLabel(null))
        assertEquals(
            "Are you sure you want to delete \"Day 3\" (Day 3 of 50)?",
            deleteQuestion(row(ReportStatus.Draft, shared = SharedHeader(shootDayNumber = "3", totalDays = "50"))),
        )
        assertEquals(
            "Are you sure you want to delete this production report?",
            deleteQuestion(row(ReportStatus.Draft, name = "  ")),
        )
    }

    @Test
    fun `a stock template takes today and the next day, a saved one only fills blanks`() {
        val meta = SheetMetadata(currentShootDay = 11, totalDays = "40", finalApproverIds = listOf("m1"))
        val (stock, day) = resolveSharedForTemplate(
            SharedHeader(dateYmd = "2020-01-01"),
            meta,
            fromSavedTemplate = false,
            todayYmd = "2026-09-13",
        )
        assertEquals(12, day)
        assertEquals("2026-09-13", stock.dateYmd)
        assertEquals("12", stock.shootDayNumber)
        assertEquals(listOf("m1"), stock.approverIds)
        assertEquals("40", stock.totalDays)

        val saved = SharedHeader(dateYmd = "2026-01-02", shootDayNumber = "7", approverIds = listOf("s1"))
        val (kept, none) = resolveSharedForTemplate(saved, meta, fromSavedTemplate = true, todayYmd = "2026-09-13")
        assertEquals(0, none)
        assertEquals("2026-01-02", kept.dateYmd)
        assertEquals("7", kept.shootDayNumber)
        assertEquals(listOf("s1"), kept.approverIds)
        assertEquals("40", kept.totalDays)
    }

    @Test
    fun `crew is regenerated for stock templates, and for saved ones only when they carry none`() {
        val crew = PageCell(order = 0, renderAs = RenderKind.Employee, title = "Camera")
        val withCrew = SheetPayload(rows = listOf(PageRow(0, cells = listOf(crew))))
        assertTrue(shouldRegenerateEmployeeRows(withCrew, memberCount = 3, fromSavedTemplate = false))
        assertFalse(shouldRegenerateEmployeeRows(withCrew, memberCount = 3, fromSavedTemplate = true))
        assertTrue(shouldRegenerateEmployeeRows(SheetPayload(), memberCount = 3, fromSavedTemplate = true))
        assertFalse(shouldRegenerateEmployeeRows(SheetPayload(), memberCount = 0, fromSavedTemplate = false))
    }

    @Test
    fun `section titles are unique ignoring case`() {
        val payload = SheetPayload(
            rows = listOf(
                PageRow(
                    0,
                    cells = listOf(
                        PageCell(0, title = " Notes "),
                        PageCell(1, title = "notes"),
                        PageCell(2, title = ""),
                    ),
                ),
                PageRow(1, cells = listOf(PageCell(0, title = "Cast"))),
            ),
        )
        assertEquals(listOf("Notes", "Cast"), templateSectionTitles(payload))
    }

    @Test
    fun `dates print the web's way and relative times round down`() {
        val utc = TimeZone.UTC
        // 2026-09-03T14:05:00Z
        assertEquals("03 SEPT 2026 | 02:05 PM", formatDateTime(1_788_444_300_000L, utc))
        assertEquals("-", formatDateTime(null, utc))
        val now = 1_000_000_000_000L
        assertEquals("just now", relativeShort(now - 30_000, now))
        assertEquals("5m ago", relativeShort(now - 300_000, now))
        assertEquals("2 hours ago", relativeLong(now - 7_200_000, now))
        assertEquals("1 day ago", relativeLong(now - 86_400_000, now))
    }
}
