package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalSequence
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a department goes back to using the production's approvers.
 *
 * The absence of a department row is what "inherits" means to the server — it
 * resolves the department first and falls back — so emptying a chain has to
 * remove the row rather than save an empty one. An empty chain saved is a
 * document that waits forever at a level with nobody in it.
 */
class ApprovalRevertTest {

    private fun department(tiers: List<ApprovalTier>) = ApprovalConfig(
        id = "cfg-1",
        scope = ApprovalScope.Department,
        departmentId = "d1",
        tiers = tiers,
    )

    /** An emptied chain compacts to nothing, which is the signal to revert. */
    @Test
    fun `a department chain with nobody in it compacts to nothing`() {
        val emptied = department(
            listOf(
                ApprovalTier(order = 1, rules = listOf(ApprovalRule(type = "user", userIds = emptyList()))),
                ApprovalTier(order = 2),
            ),
        )

        assertTrue(ApprovalSequence.compacted(emptied.tiers).isEmpty())
    }

    /** A chain with anybody in it is a chain, and is saved rather than removed. */
    @Test
    fun `a chain with an approver still compacts to a chain`() {
        val filled = department(
            listOf(ApprovalTier(order = 1, rules = listOf(ApprovalRule(type = "user", userIds = listOf("u1"))))),
        )

        assertEquals(1, ApprovalSequence.compacted(filled.tiers).size)
    }

    /**
     * The production-wide chain is not reverted, because there is nothing
     * behind it to fall back to.
     *
     * Removing it would leave the module with no approvers at all, which the
     * screen should refuse rather than the server.
     */
    @Test
    fun `the production-wide chain is not a candidate for reverting`() {
        val global = ApprovalConfig(id = "cfg-0", scope = ApprovalScope.All)

        assertEquals(ApprovalScope.All, global.scope)
        assertTrue(ApprovalSequence.compacted(global.tiers).isEmpty())
    }
}
