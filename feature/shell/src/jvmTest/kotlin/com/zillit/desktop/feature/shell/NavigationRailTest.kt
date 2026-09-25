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
        // Android's bottom bar order (`BottomNavigationActivity.kt:555-563`).
        assertEquals(
            listOf("home", "email", "tools", "cnc", "settings"),
            DefaultRailItems.map { it.id },
        )
    }

    @Test
    fun `administration is a tab of Settings, not a rail entry`() {
        // As on the web: one Settings page, Profile Settings and Admin Settings
        // as its two tabs. The rail is the same list whoever is reading it.
        assertTrue(railItemsFor(isAdmin = true).none { it.id == "admin" })
        assertEquals(
            DefaultRailItems.map { it.id } + AppRailItems.map { it.id },
            railItemsFor(isAdmin = true).map { it.id },
        )
        assertEquals(railItemsFor(isAdmin = true), railItemsFor(isAdmin = false))
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

    /**
     * SOS and Zillit Help are the app's own pages, and they follow Settings in
     * the run rather than sitting at the foot. Pin to Start is gone: the web
     * page behind it exists to install the app, and a desktop build is the
     * installed app.
     */
    @Test
    fun `SOS and Help follow Settings, with Pin to Start gone`() {
        assertEquals(listOf("sos", "help"), AppRailItems.map { it.id })
        assertEquals(listOf("settings", "sos", "help"), railItemsFor(isAdmin = true).map { it.id }.takeLast(3))
        assertTrue(railItemsFor(isAdmin = true).none { it.id == "pin" })
    }

    /**
     * Nothing sits below the spacer but Logout, and Logout is not an item:
     * the rail draws it, and the frame asks before it fires.
     */
    @Test
    fun `only Logout is left for the rail's foot`() {
        assertTrue(railItemsFor(isAdmin = true).none { it.id == "logout" })
        assertTrue(railItemsFor(isAdmin = true).none { it.id == "notifications" })
    }

    @Test
    fun `the app's pages are told apart from the sections above them`() {
        // The rail sits collapsed most of the time, so an entry is its icon.
        // Two entries sharing a glyph are two rows nobody can tell apart.
        val items = railItemsFor(isAdmin = true)

        assertEquals(items.size, items.map { it.id }.toSet().size)
        assertEquals(items.size, items.map { it.icon }.toSet().size)
    }

    @Test
    fun `each app page route resolves to a provider path that exists`() {
        // `/sos` is its own tool; Zillit Help is a page under Settings. An
        // entry that resolves to nothing opens a dead window.
        val servedPrefixes = listOf("/sos", "/settings")

        AppRailItems.forEach { item ->
            val path = (item.route as? WorkspaceRoute.Tool)?.path ?: "/home"
            assertTrue(
                servedPrefixes.any { path.startsWith(it) },
                "no provider serves ${item.id} -> $path",
            )
        }
    }
}
