package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.domain.Approval
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.TierLevel
import com.zillit.desktop.feature.invoices.domain.TierRule
import com.zillit.desktop.feature.invoices.domain.TierScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalChainTest {

    private val everyone = ApprovalTierConfig(
        id = "all",
        scope = TierScope.All,
        tiers = listOf(
            TierLevel(1, listOf(TierRule("default", userIds = listOf("hod")))),
            TierLevel(2, listOf(TierRule("default", userIds = listOf("pm")))),
        ),
    )

    private val camera = ApprovalTierConfig(
        id = "cam",
        scope = TierScope.Department,
        departmentId = "d-cam",
        tiers = listOf(
            // Written out of order on purpose: the server's `order` wins over array position.
            TierLevel(3, listOf(TierRule("default", userIds = listOf("fc")))),
            TierLevel(1, listOf(TierRule("default", userIds = listOf("cam-hod")))),
            TierLevel(
                2,
                listOf(
                    TierRule("default", userIds = listOf("line-producer")),
                    TierRule("amount", amountThreshold = 5_000.0, userIds = listOf("upm")),
                ),
            ),
        ),
    )

    private fun invoice(dept: String, gross: Double, approvals: List<Approval> = emptyList()) = Invoice(
        id = "i1",
        departmentId = dept,
        grossAmount = gross,
        status = InvoiceStatus.Approval,
        approvals = approvals,
    )

    @Test
    fun `a department config beats the production-wide one, which is the fallback`() {
        val configs = listOf(everyone, camera)
        assertEquals("cam", ApprovalChain.resolveConfigForDepartment(configs, "d-cam")?.id)
        assertEquals("all", ApprovalChain.resolveConfigForDepartment(configs, "d-sound")?.id)
        assertEquals("all", ApprovalChain.resolveConfigForDepartment(configs, null)?.id)
        assertNull(ApprovalChain.resolveConfigForDepartment(listOf(camera), "d-sound"))
    }

    @Test
    fun `amount rules at or above their threshold replace the defaults for that tier`() {
        val small = ApprovalChain.tiersFor(listOf(everyone, camera), invoice("d-cam", 4_999.99))
        assertEquals(listOf("cam-hod"), small[0].userIds)
        assertEquals(listOf("line-producer"), small[1].userIds)
        assertEquals(listOf("fc"), small[2].userIds)

        val large = ApprovalChain.tiersFor(listOf(everyone, camera), invoice("d-cam", 5_000.0))
        assertEquals(listOf("upm"), large[1].userIds)
        assertEquals(listOf(1, 2, 3), large.map { it.number })
    }

    @Test
    fun `tiers that resolve to nobody are dropped and the rest renumbered`() {
        val gappy = ApprovalTierConfig(
            scope = TierScope.All,
            tiers = listOf(
                TierLevel(1, listOf(TierRule("default", userIds = emptyList()))),
                TierLevel(2, listOf(TierRule("amount", amountThreshold = 10_000.0, userIds = listOf("big")))),
                TierLevel(3, listOf(TierRule("default", userIds = listOf("fc", "fc", "")))),
            ),
        )
        val tiers = ApprovalChain.resolveTiers(gappy, 100.0)
        assertEquals(1, tiers.size)
        assertEquals(1, tiers.single().number)
        assertEquals(listOf("fc"), tiers.single().userIds)
        assertEquals(1, ApprovalChain.totalTiers(tiers))
    }

    @Test
    fun `nextTier is the first unsigned tier and null once the chain is complete`() {
        val tiers = ApprovalChain.resolveTiers(everyone, 10.0)
        assertEquals(1, ApprovalChain.nextTier(tiers, emptyList()))
        assertEquals(2, ApprovalChain.nextTier(tiers, listOf(Approval("hod", 1, null))))
        assertNull(ApprovalChain.nextTier(tiers, listOf(Approval("hod", 1, null), Approval("pm", 2, null))))
        // A signature on tier 2 alone leaves tier 1 next.
        assertEquals(1, ApprovalChain.nextTier(tiers, listOf(Approval("pm", 2, null))))
    }

    @Test
    fun `canApprove needs approval status and the user on the tier that is up next`() {
        val tiers = ApprovalChain.resolveTiers(everyone, 10.0)
        val fresh = invoice("d-sound", 10.0)
        assertTrue(ApprovalChain.canApprove(fresh, tiers, "hod"))
        assertFalse(ApprovalChain.canApprove(fresh, tiers, "pm"), "tier 2 is not up yet")
        assertTrue(ApprovalChain.canApprove(fresh.copy(approvals = listOf(Approval("hod", 1, null))), tiers, "pm"))
        assertFalse(ApprovalChain.canApprove(fresh.copy(status = InvoiceStatus.Approved), tiers, "hod"))
        assertFalse(ApprovalChain.canApprove(fresh, tiers, ""))
        assertTrue(ApprovalChain.isApprover(tiers, "pm"))
        assertFalse(ApprovalChain.isApprover(tiers, "nobody"))
    }
    /**
     * Owning the production is not the same as running its ledger.
     *
     * The web's invoice entry page grants posting on `ADMIN_DESIGNATIONS` —
     * Production Accountant and Financial Controller — not on the
     * project-owner flag. This port read `permissions.isAdmin`, so a project
     * admin with no invoice rights could both open the tool and post to it.
     */
    @Test
    fun `a project admin does not inherit invoice rights`() {
        val admin = InvoiceViewer(
            userId = "u1",
            departmentIdentifier = "department_art",
            designationIdentifier = "designation_art_director_art",
            canView = false,
            canPost = false,
            isAdmin = true,
            ready = true,
        )

        assertTrue(admin.isBlocked, "an admin without view rights still opened the tool")
        assertFalse(admin.mayPost, "an admin without posting rights could still post")
    }

    /** The senior designations keep it, which is what the web actually checks. */
    @Test
    fun `a production accountant may post without an explicit right`() {
        val senior = InvoiceViewer(
            userId = "u1",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_production_accountant_accounts",
            canPost = false,
            ready = true,
        )

        assertTrue(senior.mayPost)
    }

}
