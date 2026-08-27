package com.zillit.desktop.feature.cashexpenses.domain

/** Someone a batch can be handed to, as the host resolves them from the crew. */
data class AssigneeOption(val userId: String, val fullName: String, val designation: String = "")

/**
 * Assigning and reassigning a claim batch on Post & Ledger.
 *
 * Ported from the web's `lib/assignBatch.js`, which mirrors the purchase-order
 * reassign modal. Three rules, each with a reason:
 *
 *  - A batch with no assignee is a first-time **Assign**; once it has one it
 *    is a **Reassign**, and the word on the button has to match or the person
 *    clicking it cannot tell whether they are taking work or handing it on.
 *  - The picker drops only the *current* assignee — re-picking them is a
 *    no-op. The viewer is **not** dropped: a senior self-assigning an
 *    unassigned batch is the ordinary case.
 *  - A reassignment needs a reason; a first assignment does not. The backend
 *    allows an empty `assignment_reason` (Joi `.allow('', null)`), so this is
 *    a client rule about accountability, not a server refusal.
 */
object BatchAssignment {

    fun isUnassigned(batch: ClaimBatch?): Boolean = batch?.assignedTo.isNullOrBlank()

    fun actionLabel(batch: ClaimBatch?): String = if (isUnassigned(batch)) "Assign" else "Reassign"

    fun eligible(people: List<AssigneeOption>, batch: ClaimBatch?): List<AssigneeOption> =
        people.filterNot { it.userId == batch?.assignedTo }

    fun canSubmit(batch: ClaimBatch?, selectedUserId: String, reason: String): Boolean {
        if (selectedUserId.isBlank()) return false
        return isUnassigned(batch) || reason.isNotBlank()
    }

    /**
     * The reason as it should be sent: absent on a first assignment.
     *
     * Not merely optional — a first assignment has nothing to explain, and
     * sending a stray reason there records a handover that never happened.
     */
    fun reasonFor(batch: ClaimBatch?, reason: String): String? =
        if (isUnassigned(batch)) null else reason.trim().ifBlank { null }

    /**
     * The batch **stays** in the Post & Ledger queue after an assignment.
     *
     * Its status has not changed — only its owner — so the row is updated in
     * place. Dropping it made the batch vanish until the next refetch, which
     * is the bug this function exists to prevent.
     */
    fun applied(
        batches: List<ClaimBatch>,
        batchId: String,
        assignedTo: String,
    ): List<ClaimBatch> = batches.map { batch ->
        if (batch.id == batchId) batch.copy(assignedTo = assignedTo) else batch
    }
}
