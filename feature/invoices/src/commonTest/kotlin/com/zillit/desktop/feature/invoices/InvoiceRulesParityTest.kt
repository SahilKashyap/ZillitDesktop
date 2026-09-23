package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.domain.RunApprovalDecision
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.RunSignOff
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web's permission and routing rules, rule for rule. */
class InvoiceRulesParityTest {

    // -- seniority (utils/po-permissions.js) --------------------------------

    @Test
    fun `the two senior designations are senior`() {
        assertTrue(viewer("designation_production_accountant_accounts").hasSeniorDesignation)
        assertTrue(viewer("designation_financial_controller_accounts").hasSeniorDesignation)
        // Some endpoints hand the client the translated name instead.
        assertTrue(viewer("Production Accountant").hasSeniorDesignation)
        assertTrue(viewer("Financial Controller").hasSeniorDesignation)
    }

    /** The web's own test: an assistant is not senior, though the words are in its identifier. */
    @Test
    fun `an assistant production accountant is not senior`() {
        val assistant = viewer("designation_assistant_production_accountant_accounts")
        assertFalse(assistant.hasSeniorDesignation)
        assertFalse(assistant.isSenior)
        assertFalse(assistant.canOverride)
        assertFalse(viewer("Assistant Production Accountant").hasSeniorDesignation)
        assertFalse(viewer("designation_assistant_financial_controller_accounts").hasSeniorDesignation)
        assertFalse(viewer("").hasSeniorDesignation)
    }

    @Test
    fun `the settings flag still makes an assistant senior`() {
        assertTrue(viewer("designation_assistant_production_accountant_accounts").copy(seniorFlag = true).isSenior)
    }

    @Test
    fun `run access is a senior designation or the team's run access, not the sign-off chain`() {
        assertTrue(viewer("designation_financial_controller_accounts").canOperateRuns)
        assertFalse(viewer("designation_junior_accountant_accounts").copy(isRunApprover = true).canOperateRuns)
        assertTrue(viewer("designation_junior_accountant_accounts").copy(runAccessFlag = true).canOperateRuns)
    }

    // -- run approval (lib/paymentRunApproval.js) ---------------------------

    /** Two tiers, u1 on both — the ZL-20472 set-up; deliberately out of order. */
    private val chain = listOf(RunAuthLevel(2, listOf("u1", "u3")), RunAuthLevel(1, listOf("u1", "u2")))

    @Test
    fun `a tier-one approver may sign a fresh run`() {
        assertEquals(
            RunApprovalDecision(canApprove = true, nextTier = 1, totalTiers = 2),
            PaymentRuns.resolveApproval(chain, emptyList(), PaymentRunStatus.Pending, "u1"),
        )
    }

    @Test
    fun `whoever signed a tier may not sign the next, even when listed on it`() {
        val decision = PaymentRuns.resolveApproval(
            chain,
            listOf(RunSignOff(1, "u1")),
            PaymentRunStatus.Pending,
            "u1",
        )
        assertEquals(2, decision.nextTier)
        assertFalse(decision.canApprove)
    }

    @Test
    fun `somebody else may sign the next tier`() {
        val decision = PaymentRuns.resolveApproval(chain, listOf(RunSignOff(1, "u2")), PaymentRunStatus.Pending, "u3")
        assertTrue(decision.canApprove)
        assertEquals(2, decision.nextTier)
    }

    @Test
    fun `somebody on a later tier waits for the earlier one`() {
        assertFalse(PaymentRuns.resolveApproval(chain, emptyList(), PaymentRunStatus.Pending, "u3").canApprove)
    }

    @Test
    fun `only a pending run can be signed — never a draft`() {
        assertFalse(PaymentRuns.resolveApproval(chain, emptyList(), PaymentRunStatus.Approved, "u1").canApprove)
        assertFalse(PaymentRuns.resolveApproval(chain, emptyList(), PaymentRunStatus.Draft, "u1").canApprove)
    }

    @Test
    fun `a fully signed run has no next tier`() {
        assertEquals(
            RunApprovalDecision(canApprove = false, nextTier = null, totalTiers = 2),
            PaymentRuns.resolveApproval(
                chain,
                listOf(RunSignOff(1, "u2"), RunSignOff(2, "u3")),
                PaymentRunStatus.Pending,
                "u1",
            ),
        )
    }

    @Test
    fun `a run with no chain cannot be signed by anyone`() {
        assertFalse(PaymentRuns.resolveApproval(emptyList(), emptyList(), PaymentRunStatus.Pending, "u1").canApprove)
    }

    // -- routes (InvoicesModule.jsx) -----------------------------------------

    @Test
    fun `a route names its page, and the bare path or a stranger lands on overview`() {
        assertEquals(AccountantPage.Overview, AccountantPage.forRoute("/film-tools/invoices"))
        assertEquals(AccountantPage.Overview, AccountantPage.forRoute("/film-tools/invoices/"))
        assertEquals(AccountantPage.Register, AccountantPage.forRoute("/film-tools/invoices/register"))
        assertEquals(AccountantPage.Payments, AccountantPage.forRoute("/film-tools/invoices/payments/r1"))
        assertEquals(AccountantPage.Vendors, AccountantPage.forRoute("/film-tools/invoices/suppliers"))
        assertEquals(AccountantPage.Overview, AccountantPage.forRoute("/film-tools/invoices/cash-close"))
        assertEquals(AccountantPage.Overview, AccountantPage.forRoute("/film-tools/invoices/nothing"))
    }

    /** The row is `process`, the web's URL is `/entry`; both open Entry. */
    @Test
    fun `entry answers to its web route and to its row id`() {
        assertEquals(AccountantPage.Entry, AccountantPage.forRoute("/film-tools/invoices/entry"))
        assertEquals(AccountantPage.Entry, AccountantPage.forRoute("/film-tools/invoices/process"))
        assertEquals(AccountantPage.Entry, AccountantPage.forHref("/film-tools/account-hub/invoices/entry"))
    }

    private fun viewer(designation: String) = InvoiceViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = designation,
        ready = true,
    )
}
