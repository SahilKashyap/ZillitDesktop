package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Handing a claim batch to someone on Post & Ledger.
 *
 * Three rules, each ported from the web's `lib/assignBatch.js` because each
 * has a consequence: the label tells the clicker whether they are taking work
 * or giving it away, the picker must not offer a no-op, and a reassignment
 * without a reason leaves no record of why the work moved.
 */
class BatchAssignmentTest {

    private fun batch(assignedTo: String? = null) = ClaimBatch(
        id = "b1",
        reference = "PC-001",
        userId = "u2",
        holderName = "Sam Grip",
        departmentId = "grip",
        status = BatchStatus.Coding,
        expenseType = ExpenseType.PettyCash,
        claimCount = 1,
        totalGross = 100.0,
        reimbursementAmount = 0.0,
        currency = "GBP",
        settlementType = null,
        paymentMethod = null,
        notes = null,
        assignedTo = assignedTo,
        assignedBy = null,
        assignmentReason = null,
        createdAt = null,
    )

    private val crew = listOf(
        AssigneeOption("u1", "Ada Lovelace", "Production Accountant"),
        AssigneeOption("u2", "Sam Grip", "Key Grip"),
        AssigneeOption("u3", "Ravi Sound", "Sound Mixer"),
    )

    @Test
    fun `a batch with nobody on it is a first assignment`() {
        assertTrue(BatchAssignment.isUnassigned(batch()))
        assertEquals("Assign", BatchAssignment.actionLabel(batch()))
    }

    @Test
    fun `a batch already owned is a reassignment`() {
        assertFalse(BatchAssignment.isUnassigned(batch(assignedTo = "u1")))
        assertEquals("Reassign", BatchAssignment.actionLabel(batch(assignedTo = "u1")))
    }

    /** A blank owner is no owner — the wire sends "" as often as null. */
    @Test
    fun `a blank assignee reads as unassigned`() {
        assertTrue(BatchAssignment.isUnassigned(batch(assignedTo = "")))
        assertEquals("Assign", BatchAssignment.actionLabel(batch(assignedTo = "   ")))
    }

    @Test
    fun `with no batch at all the label is the safe one`() {
        assertEquals("Assign", BatchAssignment.actionLabel(null))
        assertTrue(BatchAssignment.isUnassigned(null))
    }

    // -- the picker -----------------------------------------------------------

    @Test
    fun `everyone is offered on a first assignment`() {
        assertEquals(crew, BatchAssignment.eligible(crew, batch()))
    }

    /** Re-picking the current owner is a no-op, so they are dropped. */
    @Test
    fun `the current owner is not offered`() {
        val offered = BatchAssignment.eligible(crew, batch(assignedTo = "u1"))

        assertEquals(listOf("u2", "u3"), offered.map { it.userId })
    }

    /**
     * The viewer is *not* dropped: a senior self-assigning an unassigned
     * batch is the ordinary case, not an edge one.
     */
    @Test
    fun `self-assignment is possible`() {
        val offered = BatchAssignment.eligible(crew, batch())

        assertTrue(offered.any { it.userId == "u1" }, "the viewer may take it themselves")
    }

    @Test
    fun `an empty crew offers nobody rather than failing`() {
        assertTrue(BatchAssignment.eligible(emptyList(), batch()).isEmpty())
    }

    // -- the gate -------------------------------------------------------------

    @Test
    fun `nobody chosen means nothing to submit`() {
        assertFalse(BatchAssignment.canSubmit(batch(), selectedUserId = "", reason = ""))
        assertFalse(BatchAssignment.canSubmit(batch(assignedTo = "u1"), "", "Because"))
    }

    @Test
    fun `a first assignment needs no reason`() {
        assertTrue(BatchAssignment.canSubmit(batch(), selectedUserId = "u2", reason = ""))
    }

    @Test
    fun `a reassignment does need one`() {
        val owned = batch(assignedTo = "u1")

        assertFalse(BatchAssignment.canSubmit(owned, selectedUserId = "u2", reason = ""))
        assertTrue(BatchAssignment.canSubmit(owned, selectedUserId = "u2", reason = "On leave"))
    }

    /**
     * A first assignment sends no reason at all rather than an empty one:
     * there is nothing to explain, and a stray reason records a handover
     * that never happened.
     */
    @Test
    fun `a first assignment carries no reason on the wire`() {
        assertNull(BatchAssignment.reasonFor(batch(), "typed anyway"))
    }

    @Test
    fun `a reassignment's reason is trimmed, and blank becomes absent`() {
        val owned = batch(assignedTo = "u1")

        assertEquals("On leave", BatchAssignment.reasonFor(owned, "  On leave  "))
        assertNull(BatchAssignment.reasonFor(owned, "   "))
    }

    // -- what happens to the queue --------------------------------------------

    /**
     * The batch stays in the queue afterwards: its status has not changed,
     * only its owner. Dropping the row made it vanish until the next refetch,
     * which is the bug this rule exists to prevent.
     */
    @Test
    fun `an assigned batch stays in the queue with its new owner`() {
        val queue = listOf(batch(), batch().copy(id = "b2", reference = "PC-002"))

        val after = BatchAssignment.applied(queue, batchId = "b1", assignedTo = "u3")

        assertEquals(2, after.size, "the row is patched, not removed")
        assertEquals("u3", after.first { it.id == "b1" }.assignedTo)
        assertNull(after.first { it.id == "b2" }.assignedTo, "the others are untouched")
    }

    @Test
    fun `nothing changes when the id is not in the queue`() {
        val queue = listOf(batch())

        assertEquals(queue, BatchAssignment.applied(queue, batchId = "nope", assignedTo = "u3"))
    }

    @Test
    fun `reassigning an owned batch replaces the owner rather than adding one`() {
        val queue = listOf(batch(assignedTo = "u1"))

        val after = BatchAssignment.applied(queue, batchId = "b1", assignedTo = "u3")

        assertEquals("u3", after.single().assignedTo)
    }
}
