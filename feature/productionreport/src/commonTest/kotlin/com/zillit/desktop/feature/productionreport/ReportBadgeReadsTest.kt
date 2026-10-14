package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.data.ReportWire
import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.BadgeLeaf
import com.zillit.desktop.feature.productionreport.domain.BadgeSurface
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import com.zillit.desktop.feature.productionreport.domain.ReportQuery
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.WorkflowEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iOS badge reads the report tool now makes (`ApprovalsTabView.swift`,
 * `AppViewModel.swift`): both id forms, the clear a decided report leaves
 * behind at Received, and the orphan sweep after the approval lists land.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportBadgeReadsTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = ReportHarness(dispatcher)
    private val repository = harness.repository

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun awaitingMe(id: String = "r1", uuid: String = "") = Samples.row(
        id = id,
        name = "Day 1",
        status = ReportStatus.PendingApproval,
        createdById = "u2",
        approvals = listOf(ApprovalRequest("q1", "me", "Author", "2nd AD")),
    ).copy(uuid = uuid)

    @Test
    fun `the wire's id rides as the report's second id form when it differs from _id`() {
        val both = ReportWire.summary(
            buildJsonObject {
                put("_id", "m1")
                put("id", "u-1")
            },
        )
        assertEquals(listOf("m1", "u-1"), both?.badgeIds)
        val same = ReportWire.summary(
            buildJsonObject {
                put("_id", "m1")
                put("id", "m1")
            },
        )
        assertEquals(listOf("m1"), same?.badgeIds)
    }

    @Test
    fun `a row counts and reads its badges under both id forms`() = runTest(dispatcher) {
        val row = Samples.row("r1", "Day 1").copy(uuid = "u-r1")
        repository.rows = { query -> if (ReportStatus.Draft in query.statuses) listOf(row) else emptyList() }
        val vm = harness.start(this)
        harness.badges.live.emit(
            listOf(
                BadgeLeaf(ReportBadges.UNIT_DRAFTS, level2 = "report", level3 = "r1"),
                BadgeLeaf(ReportBadges.UNIT_DRAFTS, level2 = "report", level3 = "u-r1"),
            ),
        )
        settle()
        assertEquals(2, vm.currentState.rowBadge(row, BadgeKind.Report))

        vm.onEvent(ListEvent.View(row))
        settle()
        assertEquals(
            listOf(
                BadgeRead(BadgeSurface.Drafts, BadgeKind.Report, "r1"),
                BadgeRead(BadgeSurface.Drafts, BadgeKind.Report, "u-r1"),
            ),
            harness.badges.reads,
        )
    }

    @Test
    fun `an approved report clears its report and comment rows at Received under both ids`() = runTest(dispatcher) {
        val report = awaitingMe(uuid = "u-r1")
        repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
        repository.rows = { query -> if (query.approverId == "me") listOf(report) else emptyList() }
        val vm = harness.start(this, Samples.viewer)
        vm.onEvent(ListEvent.OpenTab(ManageTab.Approvals))
        settle()
        vm.onEvent(WorkflowEvent.OpenApprove(report))
        harness.badges.reads.clear()

        vm.onEvent(WorkflowEvent.ApproveWithoutSignature)
        settle()
        assertEquals(leftReceived("r1", "u-r1"), harness.badges.reads.toSet())
    }

    @Test
    fun `a rejected report clears the same, and a refused one clears nothing`() = runTest(dispatcher) {
        val report = awaitingMe()
        val vm = harness.start(this, Samples.viewer)
        vm.onEvent(WorkflowEvent.OpenReject(report))
        vm.onEvent(WorkflowEvent.EditRejectReason("Wrong day"))
        harness.badges.reads.clear()

        vm.onEvent(WorkflowEvent.ConfirmReject)
        settle()
        assertEquals(leftReceived("r1"), harness.badges.reads.toSet())

        harness.badges.reads.clear()
        repository.approveAnswer = com.zillit.desktop.core.common.ZillitResult.Failure(
            com.zillit.desktop.core.common.ZillitError.Http(500, "boom"),
        )
        vm.onEvent(WorkflowEvent.OpenApprove(report))
        harness.badges.reads.clear()
        vm.onEvent(WorkflowEvent.ApproveWithoutSignature)
        settle()
        assertTrue(harness.badges.reads.isEmpty(), "the report has not left Received")
    }

    @Test
    fun `the approval lists sweep orphans once every sub-tab has answered, keeping every report type`() =
        runTest(dispatcher) {
            repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
            repository.rows = approvalLists()
            val vm = harness.start(this)
            assertTrue(harness.badges.sweeps.isEmpty(), "Drafts holds no approval rows")

            vm.onEvent(ListEvent.OpenTab(ManageTab.Approvals))
            settle()
            assertEquals(ApprovalSection.Sent, vm.currentState.activeSection)
            assertTrue(repository.queries.any { it.approverId == "me" }, "Received is fetched for the sweep")
            assertTrue(repository.queries.any { ReportStatus.ApprovedForPublish in it.statuses })
            assertEquals(
                listOf(setOf("s1", "u-s1", "ad1", "r1", "f1")),
                harness.badges.sweeps,
                "one sweep, against all three lists — the AD report shares the badge tool",
            )
        }

    @Test
    fun `a list that failed holds the sweep back until it answers`() = runTest(dispatcher) {
        repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
        repository.rows = approvalLists()
        repository.refuses = { ReportStatus.ApprovedForPublish in it.statuses }
        val vm = harness.start(this)
        vm.onEvent(ListEvent.OpenTab(ManageTab.Approvals))
        settle()
        assertTrue(harness.badges.sweeps.isEmpty(), "rows are never judged against a list that did not load")

        repository.refuses = { false }
        vm.onEvent(ListEvent.OpenSection(ApprovalSection.Finalized))
        settle()
        assertEquals(1, harness.badges.sweeps.size)
    }

    @Test
    fun `an approval row is an orphan when no listed report names it, and old enough to judge`() {
        val keep = setOf("r1", "u-r1")
        val cutoff = 1_000_000L
        fun orphan(
            level3: String = "",
            referenceId: String = "",
            created: Long = 10L,
            unit: String = ReportBadges.UNIT_APPROVAL,
        ) = ReportBadges.isOrphanApproval(unit, level3, referenceId, created, keep, cutoff)

        assertTrue(orphan(level3 = "gone"))
        assertFalse(orphan(level3 = "r1"))
        assertFalse(orphan(level3 = "u-r1"), "either id form keeps a row")
        assertFalse(orphan(referenceId = "r1"), "an approval push files the report in reference_id")
        assertFalse(orphan(level3 = "null", referenceId = "r1"), "a stringified missing level_3 is no id")
        assertTrue(orphan(level3 = "gone", referenceId = "r1"), "level_3 wins when it names a report")
        assertTrue(orphan(), "a row naming nothing is attributable to nothing")
        assertFalse(orphan(level3 = "gone", created = cutoff + 1), "inside the grace")
        assertFalse(orphan(level3 = "gone", created = 0L), "no created, nothing to compare")
        assertFalse(orphan(level3 = "gone", unit = ReportBadges.UNIT_DRAFTS), "only the approval unit is swept")
    }

    private fun leftReceived(vararg ids: String): Set<BadgeRead> = ids.flatMap { id ->
        listOf(
            BadgeRead(BadgeSurface.Received, BadgeKind.Report, id),
            BadgeRead(BadgeSurface.Received, BadgeKind.Comment, id),
        )
    }.toSet()

    /** Sent (with an AD report), Received and Finalized, each answering its own query. */
    private fun approvalLists(): (ReportQuery) -> List<ReportSummary> = { query ->
        when {
            query.approverId == "me" && ReportStatus.PendingApproval in query.statuses -> listOf(awaitingMe())
            ReportStatus.PendingApproval in query.statuses -> listOf(
                Samples.row("s1", "Sent", status = ReportStatus.PendingApproval).copy(uuid = "u-s1"),
                Samples.row("ad1", "AD", status = ReportStatus.PendingApproval).copy(reportType = "ad"),
            )
            ReportStatus.ApprovedForPublish in query.statuses ->
                listOf(Samples.row("f1", "Final", status = ReportStatus.ApprovedForPublish))
            else -> emptyList()
        }
    }
}
