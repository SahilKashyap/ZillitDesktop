package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
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

    @Test
    fun `an absent posting limit means no ceiling`() {
        assertTrue(viewer().canPost(1_000_000.0))
        assertTrue(viewer(metadata = CashMetadata(postingLimit = 500.0)).canPost(500.0))
        assertFalse(viewer(metadata = CashMetadata(postingLimit = 500.0)).canPost(500.01))
    }

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
