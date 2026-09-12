package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.ApprovalCandidates
import com.zillit.desktop.feature.accounthub.domain.HubUser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who the approver picker offers — the web's `pickerUsers`.
 *
 * The web fails closed: until the view-rights lookup answers, or when it
 * fails, only the accounts team is offered. The desktop used to offer the
 * whole roster in both cases, which let a chain route documents to people who
 * cannot open them.
 */
class ApprovalCandidatesTest {

    private val roster = listOf(
        HubUser("u1", "Asha Rao", departmentIdentifier = "department_accounts", department = "Accounts"),
        HubUser("u2", "Ben Cole", departmentIdentifier = "department_camera", department = "Camera"),
        HubUser("u3", "Cara Diaz", departmentIdentifier = "department_camera", department = "Camera"),
        HubUser("u4", "Dev Patel", department = "Art", status = "pending"),
    )

    @Test
    fun `before the rights answer only the accounts team is offered`() {
        assertEquals(listOf("u1"), ApprovalCandidates.pick(roster, null).map { it.id })
    }

    @Test
    fun `a failed or empty rights answer still offers the accounts team`() {
        assertEquals(listOf("u1"), ApprovalCandidates.pick(roster, emptySet()).map { it.id })
    }

    @Test
    fun `people with view rights join the accounts team, accepted crew only`() {
        assertEquals(listOf("u1", "u2"), ApprovalCandidates.pick(roster, setOf("u2", "u4")).map { it.id })
    }
}
