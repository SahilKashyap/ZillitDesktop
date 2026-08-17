package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalSequence
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sequencing rule for approval levels.
 *
 * A chain saved with level 1 empty and level 2 filled routes nothing: the
 * document waits at a level with nobody in it, and nobody is notified because
 * there is nobody to notify. The rule exists to make that unsaveable.
 */
class ApprovalSequenceTest {

    private fun filled(order: Int, vararg users: String) =
        ApprovalTier(order, listOf(ApprovalRule("user", users.toList())))

    private fun empty(order: Int) = ApprovalTier(order, listOf(ApprovalRule("user", emptyList())))

    @Test
    fun `an unbroken run of filled levels is in sequence`() {
        assertTrue(ApprovalSequence.inSequence(listOf(filled(1, "u1"), filled(2, "u2"))))
    }

    /**
     * Trailing blanks are allowed.
     *
     * They are rows the user added and has not filled yet; refusing them would
     * stop the edit mid-flow, and they are dropped on save anyway.
     */
    @Test
    fun `a trailing empty level is allowed`() {
        assertTrue(ApprovalSequence.inSequence(listOf(filled(1, "u1"), empty(2))))
    }

    @Test
    fun `an empty level before a filled one is out of sequence`() {
        assertFalse(ApprovalSequence.inSequence(listOf(empty(1), filled(2, "u2"))))
    }

    @Test
    fun `a gap in the middle is out of sequence`() {
        assertFalse(
            ApprovalSequence.inSequence(listOf(filled(1, "u1"), empty(2), filled(3, "u3"))),
        )
    }

    @Test
    fun `an empty chain is trivially in sequence`() {
        assertTrue(ApprovalSequence.inSequence(emptyList()))
    }

    /**
     * Compaction renumbers, so a survivor never keeps a stale level number.
     *
     * A gap in `order` is what the rule reads as an unfilled level, so a chain
     * saved with one would fail its own validation on the next read.
     */
    @Test
    fun `compaction drops empty levels anywhere and renumbers the rest`() {
        val compacted = ApprovalSequence.compacted(
            listOf(empty(1), filled(2, "u2"), empty(3), filled(4, "u4")),
        )

        assertEquals(listOf(1, 2), compacted.map { it.order })
        assertEquals(listOf("u2"), compacted.first().rules.single().userIds)
    }

    @Test
    fun `empty levels are reported by their 1-based position`() {
        assertEquals(
            listOf(2, 4),
            ApprovalSequence.emptyLevels(listOf(filled(1, "u1"), empty(2), filled(3, "u3"), empty(4))),
        )
    }

    @Test
    fun `a chain with nobody in it is refused`() {
        val problem = ApprovalSequence.validationError(listOf(empty(1)))

        assertEquals("Add at least one approver.", problem)
    }

    /** The message names the offending level rather than saying only that something is wrong. */
    @Test
    fun `an out-of-sequence chain is refused and names the level`() {
        val problem = ApprovalSequence.validationError(
            listOf(empty(1), filled(2, "u2")),
        )

        assertNotNull(problem)
        assertTrue(problem.contains("Level 1"))
    }

    @Test
    fun `a chain with only trailing blanks passes and is cleaned on the way out`() {
        val tiers = listOf(filled(1, "u1"), empty(2))

        assertNull(ApprovalSequence.validationError(tiers))
        assertEquals(1, ApprovalSequence.compacted(tiers).size)
    }

    /** A rule with a type but no people is not an assigned level. */
    @Test
    fun `a typed rule with no users does not count as assigned`() {
        val tier = ApprovalTier(1, listOf(ApprovalRule("department_head", emptyList())))

        assertFalse(tier.isAssigned)
    }
}
