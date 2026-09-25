package com.zillit.desktop

import com.zillit.desktop.core.database.ProfileSnapshot
import com.zillit.desktop.core.database.UserSnapshot
import com.zillit.desktop.core.session.ProjectContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who the call's Users section offers: the Contacts tab's people. It used to
 * be everyone with a device, so crew who had left, been removed or never
 * accepted their invite were offered to be rung (2026-09-23).
 */
class CallableCrewTest {

    private fun user(id: String, status: String?, name: String = "Crew $id", private: Boolean = false) =
        UserSnapshot(
            userId = id,
            fullName = name,
            email = "$id@zillit.com",
            department = null,
            designation = null,
            avatarUrl = null,
            isAdmin = false,
            deviceId = "dev-$id",
            keepNamePrivate = private,
            status = status,
        )

    @Test
    fun `only active crew with a name are offered, never ourselves`() {
        val context = ProjectContext(
            profile = ProfileSnapshot(
                userId = "me",
                fullName = "Me",
                email = "me@zillit.com",
                phone = null,
                avatarUrl = null,
                isAdmin = false,
            ),
            users = listOf(
                user("me", "approved"),
                user("a", "approved"),
                user("b", "accepted"),
                user("c", null),
                user("left", "left"),
                user("removed", "removed"),
                user("pending", "pending"),
                user("rejected", "rejected"),
                user("nameless", "approved", name = " "),
                user("private", "approved", private = true),
            ),
        )

        assertEquals(listOf("a", "b", "c"), context.callableCrew().map { it.userId })
    }

    @Test
    fun `a call in another production asks that production for its people`() {
        // Named, and not the open one: that production's crew.
        assertEquals("p2", crewProductionFor(callProjectId = "p2", openProjectId = "p1"))
        // The open production's call, or one that names none: the open crew.
        assertEquals(null, crewProductionFor(callProjectId = "p1", openProjectId = "p1"))
        assertEquals(null, crewProductionFor(callProjectId = "", openProjectId = "p1"))
        assertEquals(null, crewProductionFor(callProjectId = null, openProjectId = "p1"))
    }

    @Test
    fun `another production's crew is filtered by our id there, as the open one is`() {
        val crew = listOf(user("me-there", "approved"), user("x", "approved"), user("y", "left"))
        assertEquals(listOf("x"), crew.callableCrew(self = "me-there").map { it.userId })
    }
}
