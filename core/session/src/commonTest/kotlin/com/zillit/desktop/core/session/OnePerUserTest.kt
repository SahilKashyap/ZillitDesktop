package com.zillit.desktop.core.session

import com.zillit.desktop.core.database.UserSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The production's people, one each. Every list drawn from them is keyed by
 * user id, and a person the server listed twice stopped the app from the
 * Contacts tab ("Key … was already used", 2026-09-23).
 */
class OnePerUserTest {

    private fun user(id: String, status: String? = null, name: String = id) = UserSnapshot(
        userId = id,
        fullName = name,
        email = null,
        department = null,
        designation = null,
        avatarUrl = null,
        isAdmin = false,
        status = status,
    )

    @Test
    fun `a person listed twice is kept once, in the server's order`() {
        val folded = listOf(user("a"), user("b"), user("a"), user("c")).onePerUser()
        assertEquals(listOf("a", "b", "c"), folded.map { it.userId })
    }

    @Test
    fun `the membership that still counts wins over an old one`() {
        val folded = listOf(
            user("a", status = "removed", name = "Old"),
            user("a", status = "approved", name = "Current"),
            user("b", status = "pending", name = "Invited"),
            user("b", status = "left", name = "Left"),
        ).onePerUser()
        assertEquals(listOf("Current", "Left"), folded.map { it.fullName })
    }
}
