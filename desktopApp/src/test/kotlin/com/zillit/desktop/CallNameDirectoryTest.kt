package com.zillit.desktop

import com.zillit.desktop.core.database.ProfileSnapshot
import com.zillit.desktop.core.database.UserSnapshot
import com.zillit.desktop.core.session.ProjectContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The in-call name book.
 *
 * Line 1 group rosters arrive nameless, so the stage falls back to this map.
 * That makes it the one place a name can reach a call surface without the
 * server having sent it, which is why the keep-name-private case is tested
 * here rather than left to the caller.
 */
class CallNameDirectoryTest {

    private fun user(
        id: String,
        name: String,
        email: String? = "$id@zillit.com",
        keepNamePrivate: Boolean = false,
    ) = UserSnapshot(
        userId = id,
        fullName = name,
        email = email,
        department = null,
        designation = null,
        avatarUrl = null,
        isAdmin = false,
        keepNamePrivate = keepNamePrivate,
    )

    private fun context(vararg users: UserSnapshot, selfId: String = "me") = ProjectContext(
        profile = ProfileSnapshot(
            userId = selfId,
            fullName = "Vivek Mishra",
            email = "vivek@zillit.com",
            phone = null,
            avatarUrl = null,
            isAdmin = false,
        ),
        users = users.toList(),
    )

    @Test
    fun `a crew member is named by their user id`() {
        val directory = context(user("a", "Priya Nair")).callNameDirectory()

        assertEquals("Priya Nair", directory["a"])
    }

    @Test
    fun `a keep-name-private crew member is left out of the in-call name book`() {
        val directory = context(
            user("p", "Priya Nair", keepNamePrivate = true),
            user("r", "Rahul Verma"),
        ).callNameDirectory()

        assertNull(directory["p"], "a private name must never be reachable by lookup")
        assertFalse(directory.values.any { it.contains("Priya") })
        assertEquals("Rahul Verma", directory["r"], "the rest of the crew still resolves")
    }

    @Test
    fun `a crew member with no name is not painted as their email address`() {
        // `fullName` degrades to the email, then to "Unknown". A call stage is
        // screen-shared and recorded; neither belongs on it.
        val directory = context(
            user("x", "sam@zillit.com", email = "sam@zillit.com"),
            user("y", "Unknown"),
        ).callNameDirectory()

        assertNull(directory["x"])
        assertNull(directory["y"])
    }

    @Test
    fun `our own row is never named from the directory`() {
        // The self tile is built separately and says "You".
        val directory = context(user("me", "Vivek Mishra")).callNameDirectory()

        assertNull(directory["me"])
    }
}
