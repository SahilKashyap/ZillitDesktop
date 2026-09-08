package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.latestRound
import com.zillit.desktop.feature.productionreport.domain.namesInCurrentRound
import com.zillit.desktop.feature.productionreport.domain.pendingFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Approval rounds — a re-send supersedes the previous round's approvers (web `latestRoundRequests`). */
class ApprovalRoundsTest {
    private fun req(id: String, who: String, stage: String = "FINAL", round: Int = 1, status: String = "PENDING") =
        ApprovalRequest(
            id = id, assigneeId = who, assigneeName = who, role = "", stage = stage,
            status = status, reason = "", round = round,
        )

    @Test
    fun `keeps only the newest round for the stage, and a payload with no round is round 1`() {
        val reqs = listOf(req("a", "u1", round = 1), req("b", "u2", round = 2), req("c", "u3", round = 2))
        assertEquals(listOf("b", "c"), reqs.latestRound("FINAL").map { it.id })
        assertEquals(listOf("x"), listOf(req("x", "u1")).latestRound().map { it.id })
    }

    @Test
    fun `stages are independent`() {
        val reqs = listOf(req("f1", "u1", round = 3), req("i1", "u2", stage = "INTERNAL", round = 1))
        assertEquals(listOf("f1"), reqs.latestRound("FINAL").map { it.id })
        assertEquals(listOf("i1"), reqs.latestRound("INTERNAL").map { it.id })
    }

    @Test
    fun `an approver removed by a later round is no longer one`() {
        val reqs = listOf(req("a", "u1", round = 1), req("b", "u2", round = 2))
        assertFalse(reqs.namesInCurrentRound("u1"))
        assertTrue(reqs.namesInCurrentRound("u2"))
        assertNull(reqs.pendingFor("u1"))
        assertEquals("b", reqs.pendingFor("u2")?.id)
        assertNull(reqs.pendingFor(null))
    }

    @Test
    fun `only a pending request of the newest round waits on me`() {
        val reqs = listOf(req("a", "u1", round = 2, status = "APPROVED"), req("b", "u1", stage = "INTERNAL", round = 1))
        assertEquals("b", reqs.pendingFor("u1")?.id)
    }
}
