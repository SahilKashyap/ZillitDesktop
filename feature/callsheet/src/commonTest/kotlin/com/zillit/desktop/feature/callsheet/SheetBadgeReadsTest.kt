package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.BadgeSurface
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetQuery
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.ui.ListEvent
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
import kotlin.test.assertTrue

/**
 * The iOS badge reads the call sheet now makes (`ProjectDetailView.swift`):
 * an Approvals sub-tab read when it appears, and the orphan sweep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetBadgeReadsTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = SheetHarness(dispatcher)
    private val repository = harness.repository

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an Approvals sub-tab reads its report rows once each time it appears`() = runTest(dispatcher) {
        repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
        val vm = harness.start(this)
        assertTrue(harness.badges.sectionReads.isEmpty(), "Drafts reads no sub-tab")

        vm.onEvent(ListEvent.OpenTab(SheetTab.Approvals))
        vm.onEvent(ListEvent.OpenSection(ApprovalSection.Received))
        vm.onEvent(ListEvent.Retry)
        settle()
        assertEquals(
            listOf(BadgeSurface.Sent, BadgeSurface.Received),
            harness.badges.sectionReads,
            "a reload of the sub-tab on screen is not a new appearance",
        )

        vm.onEvent(ListEvent.OpenTab(SheetTab.Drafts))
        vm.onEvent(ListEvent.OpenTab(SheetTab.Approvals))
        settle()
        assertEquals(BadgeSurface.Received, harness.badges.sectionReads.last(), "coming back is")
        assertEquals(3, harness.badges.sectionReads.size)
        assertTrue(harness.badges.reads.isEmpty(), "the sub-tab read names no sheet")
    }

    @Test
    fun `a viewer landing on Received reads it`() = runTest(dispatcher) {
        repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
        harness.start(this, Samples.author.copy(canPost = false))
        assertEquals(listOf(BadgeSurface.Received), harness.badges.sectionReads)
    }

    @Test
    fun `a unit is swept only once every list that shows it has answered, against every loaded sheet`() =
        runTest(dispatcher) {
            repository.metadata = SheetMetadata(finalApproverIds = listOf("me"))
            repository.rows = lists()
            val vm = harness.start(this)
            assertEquals(
                setOf(setOf(SheetBadges.UNIT_DRAFTS) to setOf("d1")),
                harness.badges.sweeps.toSet(),
                "Drafts answered; Approvals and Published have not",
            )

            vm.onEvent(ListEvent.OpenTab(SheetTab.Approvals))
            vm.onEvent(ListEvent.OpenSection(ApprovalSection.Received))
            vm.onEvent(ListEvent.OpenSection(ApprovalSection.Finalized))
            settle()
            val (units, keep) = harness.badges.sweeps.last()
            assertEquals(setOf(SheetBadges.UNIT_DRAFTS, SheetBadges.UNIT_APPROVAL), units)
            assertEquals(setOf("d1", "s1", "r1", "f1"), keep)

            vm.onEvent(ListEvent.OpenTab(SheetTab.Published))
            settle()
            assertEquals(SheetBadges.UNITS, harness.badges.sweeps.last().first)
            assertTrue("p1" in harness.badges.sweeps.last().second)
        }

    @Test
    fun `a viewer's hidden tabs are swept with the lists they have, never before the tabs are known`() =
        runTest(dispatcher) {
            repository.metadata = SheetMetadata(internalReceiverIds = listOf("me"))
            repository.rows = { query ->
                if (CallSheetStatus.Draft in query.statuses) listOf(Samples.row("d1", "Day 1")) else emptyList()
            }
            harness.start(this, Samples.author.copy(canPost = false))
            assertEquals(
                setOf(SheetBadges.UNITS to setOf("d1")),
                harness.badges.sweeps.toSet(),
                "Approvals and Published are tabs this viewer lacks: nothing could ever clear their rows",
            )
        }

    @Test
    fun `nothing is swept while no list holds a sheet`() = runTest(dispatcher) {
        harness.start(this)
        assertTrue(harness.badges.sweeps.isEmpty())
    }

    @Test
    fun `a row is an orphan when no loaded sheet names it and it is a week old`() {
        val keep = setOf("s1")
        val cutoff = 1_000_000L
        val all = SheetBadges.UNITS
        fun orphan(
            level3: String = "",
            referenceId: String = "",
            created: Long = 10L,
            unit: String = SheetBadges.UNIT_APPROVAL,
        ) = SheetBadges.isOrphan(unit, level3, referenceId, created, all, keep, cutoff)

        assertTrue(orphan(level3 = "gone"))
        assertFalse(orphan(level3 = "s1"))
        assertFalse(orphan(referenceId = "s1"), "reference_id stands in for a missing level_3")
        assertFalse(orphan(level3 = "undefined", referenceId = "s1"))
        assertTrue(orphan(), "a row naming nothing")
        assertFalse(orphan(level3 = "gone", created = cutoff + 1), "inside the week")
        assertFalse(orphan(level3 = "gone", created = 0L))
        assertFalse(orphan(level3 = "gone", unit = "call_sheet_comment_label"), "legacy units are not judged")
        val approvalOnly = setOf(SheetBadges.UNIT_APPROVAL)
        assertFalse(
            SheetBadges.isOrphan(SheetBadges.UNIT_DRAFTS, "gone", "", 10L, approvalOnly, keep, cutoff),
            "a unit not yet ready is not judged",
        )
    }

    /** One sheet per list, each answering its own query. */
    private fun lists(): (SheetQuery) -> List<CallSheetSummary> = { query ->
        when {
            CallSheetStatus.Draft in query.statuses -> listOf(Samples.row("d1", "Draft"))
            query.approverId == "me" && CallSheetStatus.PendingApproval in query.statuses -> listOf(
                Samples.row(
                    "r1",
                    "Received",
                    status = CallSheetStatus.PendingApproval,
                    createdById = "u2",
                    approvals = listOf(ApprovalRequest("q1", "me", "Author", "2nd AD")),
                ),
            )
            CallSheetStatus.PendingApproval in query.statuses ->
                listOf(Samples.row("s1", "Sent", status = CallSheetStatus.PendingApproval))
            CallSheetStatus.ApprovedForPublish in query.statuses ->
                listOf(Samples.row("f1", "Final", status = CallSheetStatus.ApprovedForPublish))
            CallSheetStatus.Published in query.statuses ->
                listOf(Samples.row("p1", "Published", status = CallSheetStatus.Published))
            else -> emptyList()
        }
    }
}
