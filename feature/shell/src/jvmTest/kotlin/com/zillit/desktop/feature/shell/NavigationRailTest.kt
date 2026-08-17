package com.zillit.desktop.feature.shell

import com.zillit.desktop.core.workspace.WorkspaceRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rail is the app's own navigation, not the production's tool list.
 *
 * `GET project/tools` returns Film Tools — accounting, catering, forms and so
 * on — and never mentions Home, Chat, Email or Settings. Deriving the rail from
 * it therefore produced an empty rail and Email vanished from the sidebar.
 */
class NavigationRailTest {

    @Test
    fun `every app section is on the rail, in order`() {
        assertEquals(
            listOf("home", "tools", "cnc", "email", "settings"),
            DefaultRailItems.map { it.id },
        )
    }

    @Test
    fun `administration is on the rail only for coordinators`() {
        // It used to be a row inside Settings. Out here it must still be absent
        // for everyone else — the rail is the app's statement of what exists,
        // and advertising a room someone may not enter is worse than silence.
        assertTrue(railItemsFor(isAdmin = true).any { it.id == AdminRailItem.id })
        assertTrue(railItemsFor(isAdmin = false).none { it.id == AdminRailItem.id })
    }

    @Test
    fun `admin does not displace anything that was already on the rail`() {
        assertEquals(
            DefaultRailItems.map { it.id } + AdminRailItem.id,
            railItemsFor(isAdmin = true).map { it.id },
        )
        assertEquals(DefaultRailItems.map { it.id }, railItemsFor(isAdmin = false).map { it.id })
    }

    @Test
    fun `admin and settings are told apart at a glance`() {
        // Collapsed is how the rail sits most of the time, and collapsed it is
        // only icons. Two entries sharing a glyph are two entries nobody can
        // tell apart.
        val settings = DefaultRailItems.first { it.id == "settings" }

        assertTrue(AdminRailItem.icon != settings.icon)
        assertTrue(AdminRailItem.label != settings.label)
    }

    @Test
    fun `the rail does not depend on permissions or on a loaded production`() {
        // No project, no rights, no network: the rail is still whole. This is
        // the regression — a permission-derived rail is empty at exactly the
        // moment the user needs somewhere to go.
        assertTrue(DefaultRailItems.isNotEmpty())
        assertTrue(DefaultRailItems.all { it.label.isNotBlank() })
    }

    @Test
    fun `each rail route resolves to a provider path that exists`() {
        // Longest-prefix matching means `/home/tools` resolves to the `/home`
        // provider. A rail entry that resolves to nothing opens a dead window.
        val servedPrefixes = listOf("/home", "/cnc", "/email", "/settings")

        DefaultRailItems.forEach { item ->
            val path = (item.route as? WorkspaceRoute.Tool)?.path ?: "/home"
            assertTrue(
                servedPrefixes.any { path.startsWith(it) },
                "no provider serves ${item.id} -> $path",
            )
        }
    }
}
