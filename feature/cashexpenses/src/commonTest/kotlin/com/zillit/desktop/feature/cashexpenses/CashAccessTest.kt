package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.Lifecycle
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who sees what.
 *
 * Every case here corresponds to a way the web got it wrong at least once —
 * an exact department match demoting a whole accounts team, a Financial
 * Controller losing sign-off because nobody added them to a list, an
 * accountant opening the tool from the grid and being shown the processing
 * queues instead of their own receipts.
 */
class CashAccessTest {

    private fun viewer(
        department: String? = "department_accounts",
        designation: String? = null,
        metadata: CashMetadata = CashMetadata(),
        enteredAsTool: Boolean = false,
    ) = CashViewer(
        userId = "user-1",
        departmentIdentifier = department,
        designationIdentifier = designation,
        metadata = metadata,
        enteredAsTool = enteredAsTool,
    )

    @Test
    fun `a department containing accounts is an accountant`() {
        assertTrue(viewer(department = "department_accounts").isAccountant)
        // Productions name it differently; an equality check has demoted whole
        // accounts teams to the crew view before.
        assertTrue(viewer(department = "department_accounts_uk").isAccountant)
        assertFalse(viewer(department = "department_art").isAccountant)
        assertFalse(viewer(department = null).isAccountant)
    }

    @Test
    fun `entering from the tools grid gives an accountant the crew view`() {
        val fromGrid = viewer(enteredAsTool = true)

        assertFalse(fromGrid.isAccountant)
        assertTrue(CashDestination.SubmitReceipts.visibleTo(fromGrid))
        assertFalse(CashDestination.AuditQueue.visibleTo(fromGrid))
    }

    @Test
    fun `production accountants and financial controllers are senior by role`() {
        assertTrue(viewer(designation = "designation_production_accountant_accounts").isSenior)
        assertTrue(viewer(designation = "designation_financial_controller_accounts").isSenior)
        assertFalse(viewer(designation = "designation_assistant_accountant_accounts").isSenior)
    }

    @Test
    fun `the team flag also confers seniority`() {
        assertTrue(viewer(metadata = CashMetadata(isSenior = true)).isSenior)
    }

    @Test
    fun `sign-off needs the production switch, an accountant, and seniority`() {
        val senior = CashMetadata(isSenior = true, requireSeniorSignOff = true)

        assertTrue(viewer(metadata = senior).canSeeSignOff)
        // Switched off for the production: nobody signs off.
        assertFalse(viewer(metadata = senior.copy(requireSeniorSignOff = false)).canSeeSignOff)
        // Senior, but not in accounts — the queue is other people's batches.
        assertFalse(viewer(department = "department_art", metadata = senior).canSeeSignOff)
        // In accounts, but not senior.
        assertFalse(viewer(metadata = CashMetadata(requireSeniorSignOff = true)).canSeeSignOff)
    }

    @Test
    fun `the coding queue is offered only where coding is used`() {
        val coordinator = CashMetadata(isCoordinator = true, codingRequired = true)

        assertTrue(CashDestination.CodingQueue.visibleTo(viewer(metadata = coordinator)))
        assertFalse(
            CashDestination.CodingQueue.visibleTo(
                viewer(metadata = coordinator.copy(codingRequired = false)),
            ),
        )
    }

    @Test
    fun `the approval queue is visible to approvers and to every accountant`() {
        assertTrue(CashDestination.ApprovalQueue.visibleTo(viewer()))
        assertTrue(
            CashDestination.ApprovalQueue.visibleTo(
                viewer(department = "department_art", metadata = CashMetadata(isApprover = true)),
            ),
        )
        assertFalse(CashDestination.ApprovalQueue.visibleTo(viewer(department = "department_art")))
    }

    @Test
    fun `override needs both the grant and the specific right`() {
        val partial = CashMetadata(canOverride = true)
        assertFalse(viewer(metadata = partial).canOverrideBatch())

        val full = CashMetadata(canOverride = true, overrideReceiptBatch = true)
        assertTrue(viewer(metadata = full).canOverrideBatch())
        // The two rights are independent — batch override is not float override.
        assertFalse(viewer(metadata = full).canOverrideFloat())
    }

    /**
     * The web's `canPostBatch`: a senior posts by role; anyone else needs to be
     * on the team with an unlimited or non-zero limit. The amount is not
     * compared with the limit — the old rule was — and an absent limit is no
     * grant at all, where `null` is unlimited.
     */
    @Test
    fun `posting follows seniority and the team grant, not the amount`() {
        assertTrue(CashRules.canPost(viewer(designation = "designation_financial_controller_accounts"), emptyList()))
        assertFalse(CashRules.canPost(viewer(), emptyList()), "not on the team, not senior")
        assertTrue(
            CashRules.canPost(viewer(metadata = CashMetadata(isTeamMember = true, postingLimit = 500.0)), emptyList()),
        )
        assertTrue(
            CashRules.canPost(
                viewer(metadata = CashMetadata(isTeamMember = true, postingLimitUnlimited = true)),
                emptyList(),
            ),
        )
        assertFalse(
            CashRules.canPost(viewer(metadata = CashMetadata(isTeamMember = true, postingLimit = 0.0)), emptyList()),
        )
        assertFalse(CashRules.canPost(viewer(metadata = CashMetadata(isTeamMember = true)), emptyList()))
    }

    /** A review or query flag stops a non-senior — only where there is a sign-off flow to route into. */
    @Test
    fun `a flagged receipt blocks a non-senior post only under sign-off`() {
        val flagged = listOf(claim(flags = listOf("review")))
        val member = CashMetadata(isTeamMember = true, postingLimitUnlimited = true)
        assertTrue(CashRules.canPost(viewer(metadata = member), flagged))
        assertFalse(CashRules.canPost(viewer(metadata = member.copy(requireSeniorSignOff = true)), flagged))
        assertTrue(
            CashRules.canPost(
                viewer(designation = "Production Accountant", metadata = member.copy(requireSeniorSignOff = true)),
                flagged,
            ),
        )
    }

    /** Escalate and Submit for Review exist only where a senior signs off, and never for a senior. */
    @Test
    fun `the senior routes are offered by the web's own predicates`() {
        val signOff = CashMetadata(requireSeniorSignOff = true)
        val batch = batch(BatchStatus.ReadyToPost)
        assertTrue(CashRules.canEscalate(viewer(metadata = signOff), batch))
        assertFalse(CashRules.canEscalate(viewer(), batch), "no sign-off, nowhere to escalate to")
        assertFalse(CashRules.canEscalate(viewer(metadata = signOff), batch(BatchStatus.UnderReview)))
        assertFalse(CashRules.canEscalate(viewer(designation = "Financial Controller", metadata = signOff), batch))

        assertTrue(CashRules.canSubmitForReview(viewer(metadata = signOff), batch, emptyList()))
        val canPost = signOff.copy(isTeamMember = true, postingLimit = 100.0)
        assertFalse(CashRules.canSubmitForReview(viewer(metadata = canPost), batch, emptyList()))
    }

    /** Post & Ledger rows: unassigned is a senior's, assigned is the assignee's and a senior's. */
    @Test
    fun `a post and ledger row is locked to its assignee and the seniors`() {
        val mine = batch(BatchStatus.ReadyToPost).copy(assignedTo = "user-1")
        val theirs = batch(BatchStatus.ReadyToPost).copy(assignedTo = "user-9")
        val nobody = batch(BatchStatus.ReadyToPost)
        assertTrue(CashRules.canOpenPostRow(viewer(), mine))
        assertFalse(CashRules.canOpenPostRow(viewer(), theirs))
        assertFalse(CashRules.canOpenPostRow(viewer(), nobody))
        val senior = viewer(designation = "Production Accountant")
        assertTrue(CashRules.canOpenPostRow(senior, theirs))
        assertTrue(CashRules.canOpenPostRow(senior, nobody))
    }

    /** The web never draws Claim Review — approvers work the shared Approval Queue. */
    @Test
    fun `claim review is never offered`() {
        val approver = viewer(department = "department_art", metadata = CashMetadata(isApprover = true))
        assertFalse(CashDestination.ClaimReview.visibleTo(approver))
        assertFalse(CashDestination.ClaimReview.visibleTo(viewer()))
    }

    /** New Float opens the float request for an accountant, whose tabs never list it. */
    @Test
    fun `an accountant can open the float request without a tab for it`() {
        assertFalse(CashDestination.FloatRequest.visibleTo(viewer()))
        assertTrue(CashDestination.FloatRequest.openableBy(viewer()))
        assertFalse(CashDestination.Settings.openableBy(viewer(department = "department_art")))
    }

    /** Tool entry takes seniority with it: a PA opening the tile gets no Settings or Sign-off. */
    @Test
    fun `a senior who entered from the grid is crew here`() {
        val fromGrid = viewer(designation = "Production Accountant", enteredAsTool = true)
        assertFalse(fromGrid.isSeniorAccountant)
        assertFalse(CashDestination.Settings.visibleTo(fromGrid))
    }

    private fun batch(status: BatchStatus) = ClaimBatch(
        id = "b1", reference = "PC-1", userId = "u2", holderName = "", departmentId = null, status = status,
        expenseType = ExpenseType.PettyCash, claimCount = 0, totalGross = 10.0, reimbursementAmount = 0.0,
        currency = "GBP", settlementType = null, paymentMethod = null, notes = null, assignedTo = null,
        assignedBy = null, assignmentReason = null, createdAt = null,
    )

    private fun claim(flags: List<String> = emptyList()) = Claim(
        id = "c1", batchId = "b1", description = "Tape", supplier = null, category = null, costCode = "5010",
        codedDescription = null, episode = null, receiptDate = null, grossAmount = 10.0, netAmount = 10.0,
        vatAmount = 0.0, taxRate = null, taxType = null, settlementType = null, status = BatchStatus.ReadyToPost,
        receiptUrl = null, processingFlags = flags,
    )

    @Test
    fun `an accountant lands on the dashboard and crew land on the form`() {
        assertEquals(
            CashDestination.PettyCashOverview,
            CashDestination.landing(viewer(), ExpenseType.PettyCash),
        )
        assertEquals(
            CashDestination.SubmitReceipts,
            CashDestination.landing(viewer(department = "department_art"), ExpenseType.PettyCash),
        )
        assertEquals(
            CashDestination.OutOfPocketSubmit,
            CashDestination.landing(viewer(department = "department_art"), ExpenseType.OutOfPocket),
        )
    }

    @Test
    fun `every landing page is one the viewer can actually open`() {
        val viewers = listOf(
            viewer(),
            viewer(department = "department_art"),
            viewer(department = "department_art", metadata = CashMetadata(isApprover = true)),
            viewer(enteredAsTool = true),
        )
        viewers.forEach { person ->
            ExpenseType.entries.forEach { type ->
                val landing = CashDestination.landing(person, type)
                assertTrue(
                    landing.visibleTo(person),
                    "landing $landing is hidden from ${person.departmentIdentifier}",
                )
            }
        }
    }
}

/** The status vocabularies, read back off the wire. */
class CashStatusTest {

    @Test
    fun `unknown statuses degrade rather than throw`() {
        assertEquals(BatchStatus.Unknown, BatchStatus.from("SOMETHING_NEW"))
        assertEquals(FloatStatus.Unknown, FloatStatus.from(null))
        // And still render as something sensible.
        assertEquals("Pending", BatchStatus.Unknown.label())
    }

    @Test
    fun `wire values are matched case-insensitively`() {
        assertEquals(BatchStatus.ReadyToPost, BatchStatus.from("ready_to_post"))
        assertEquals(FloatStatus.Collected, FloatStatus.from(" collected "))
    }

    @Test
    fun `an override reads differently to an accountant`() {
        assertEquals("Override", BatchStatus.AcctOverride.label(accountant = true))
        assertEquals("Ready to Post", BatchStatus.AcctOverride.label(accountant = false))
    }

    @Test
    fun `receipts may only be submitted while cash is in hand`() {
        val submittable = listOf(FloatStatus.Collected, FloatStatus.Active, FloatStatus.Spending)
        FloatStatus.entries.forEach { status ->
            assertEquals(
                status in submittable,
                status.isSubmittable,
                "$status submittability is wrong",
            )
        }
    }

    @Test
    fun `a queried batch is out of the pipeline rather than partway along it`() {
        assertTrue(Lifecycle.of(BatchStatus.Queried) is Lifecycle.NeedsAction)
        assertTrue(Lifecycle.of(BatchStatus.Rejected) is Lifecycle.NeedsAction)
        assertEquals(Lifecycle.Complete, Lifecycle.of(BatchStatus.Posted))
        assertEquals(Lifecycle.At(2), Lifecycle.of(BatchStatus.InAudit))
    }
}
