package com.zillit.desktop.core.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The window model is pure, so all of it is testable here — including the two
 * bugs the web implementation had to fix in production.
 */
class WorkspaceReducerTest {

    private val budget = WorkspaceRoute.Tool("/film-tools/budget")
    private val callsheet = WorkspaceRoute.Tool("/film-tools/callsheet")

    @Test
    fun `opening a route adds a window and makes it active`() {
        val state = open(WorkspaceState(), "w1", budget)

        assertEquals(1, state.windows.size)
        assertEquals(WindowId("w1"), state.activeId)
        assertEquals(budget, state.activeWindow?.route)
    }

    @Test
    fun `opening the same path twice reuses the window instead of duplicating`() {
        val state = open(open(WorkspaceState(), "w1", budget), "w2", budget)

        assertEquals(1, state.windows.size)
        assertEquals(WindowId("w1"), state.windows.single().id)
    }

    @Test
    fun `reopening re-navigates a window that has since navigated away`() {
        // Web ZL-20516: reuse only raised the stale window, so clicking the
        // sidebar item again showed the wrong page.
        var state = open(WorkspaceState(), "w1", budget)
        state = WorkspaceLayoutReducer.navigate(state, WindowId("w1"), callsheet)
        assertEquals(callsheet, state.window(WindowId("w1"))?.route)

        state = open(state, "w2", budget)

        assertEquals(budget, state.window(WindowId("w1"))?.route)
    }

    @Test
    fun `reopening restores a minimized window`() {
        var state = open(WorkspaceState(), "w1", budget)
        state = WorkspaceReducer.minimize(state, WindowId("w1"))
        assertEquals(WindowState.Minimized, state.window(WindowId("w1"))?.state)

        state = open(state, "w2", budget)

        assertEquals(WindowState.Normal, state.window(WindowId("w1"))?.state)
    }

    @Test
    fun `focusing an already-frontmost window returns the identical state`() {
        // Web ZL-20352: rebuilding the window array on every mousedown
        // re-rendered all window content and wiped in-progress form input. In
        // Compose that would invalidate every window's composition, so the
        // no-op must be identity, not just equality.
        var state = open(WorkspaceState(), "w1", budget)
        state = open(state, "w2", callsheet)

        val refocused = WorkspaceReducer.focus(state, WindowId("w2"))

        assertSame(state, refocused, "re-focusing the top window must not produce a new state")
    }

    @Test
    fun `focusing a background window raises it above the others`() {
        var state = open(WorkspaceState(), "w1", budget)
        state = open(state, "w2", callsheet)
        assertEquals(WindowId("w2"), state.activeId)

        state = WorkspaceReducer.focus(state, WindowId("w1"))

        assertEquals(WindowId("w1"), state.activeId)
    }

    @Test
    fun `z-indices stay compact rather than growing without bound`() {
        var state = WorkspaceState()
        repeat(5) { state = open(state, "w$it", WorkspaceRoute.Tool("/tool/$it")) }
        repeat(20) { state = WorkspaceReducer.focus(state, WindowId("w${it % 5}")) }

        assertEquals((0..4).toList(), state.windows.map { it.zIndex }.sorted())
    }

    @Test
    fun `minimize then restore returns to the prior state not just Normal`() {
        var state = open(WorkspaceState(), "w1", budget)
        state = WorkspaceReducer.toggleMaximize(state, WindowId("w1"))
        state = WorkspaceReducer.minimize(state, WindowId("w1"))
        state = WorkspaceReducer.restore(state, WindowId("w1"))

        assertEquals(WindowState.Maximized, state.window(WindowId("w1"))?.state)
    }

    @Test
    fun `un-maximizing clears moved so restore returns to the compact default`() {
        var state = open(WorkspaceState(), "w1", budget)
        state = WorkspaceLayoutReducer.updateRect(state, WindowId("w1"), WindowRect(0, 0, 1600, 1000))
        assertTrue(state.window(WindowId("w1"))!!.moved)

        state = WorkspaceReducer.toggleMaximize(state, WindowId("w1"))
        state = WorkspaceReducer.toggleMaximize(state, WindowId("w1"))

        assertFalse(state.window(WindowId("w1"))!!.moved)
    }

    @Test
    fun `a maximized window ignores rect updates`() {
        var state = open(WorkspaceState(), "w1", budget, openMode = OpenMode.Maximized)
        state = WorkspaceLayoutReducer.updateRect(state, WindowId("w1"), WindowRect(10, 10, 400, 300))

        assertNull(state.window(WindowId("w1"))?.rect)
    }

    @Test
    fun `each window keeps an independent history`() {
        var state = open(WorkspaceState(), "w1", budget)
        state = open(state, "w2", callsheet)

        state = WorkspaceLayoutReducer.navigate(state, WindowId("w1"), WorkspaceRoute.Tool("/film-tools/budget/detail"))

        assertEquals(2, state.window(WindowId("w1"))!!.history.size)
        assertEquals(1, state.window(WindowId("w2"))!!.history.size)
        assertEquals(callsheet, state.window(WindowId("w2"))!!.route)
    }

    @Test
    fun `back pops only the target window and stops at the root`() {
        var state = open(WorkspaceState(), "w1", budget)
        state = WorkspaceLayoutReducer.navigate(state, WindowId("w1"), callsheet)

        state = WorkspaceLayoutReducer.back(state, WindowId("w1"))
        assertEquals(budget, state.window(WindowId("w1"))?.route)

        state = WorkspaceLayoutReducer.back(state, WindowId("w1"))
        assertEquals(budget, state.window(WindowId("w1"))?.route, "must not pop past the root route")
    }

    @Test
    fun `navigating to the current route is a no-op`() {
        val state = open(WorkspaceState(), "w1", budget)

        assertSame(state, WorkspaceLayoutReducer.navigate(state, WindowId("w1"), budget))
    }

    @Test
    fun `switching layout mode un-maximizes every window`() {
        var state = open(WorkspaceState(), "w1", budget, openMode = OpenMode.Maximized)
        state = open(state, "w2", callsheet, openMode = OpenMode.Maximized)

        state = WorkspaceLayoutReducer.toggleLayoutMode(state)

        assertEquals(LayoutMode.Cascade, state.layoutMode)
        assertTrue(state.windows.none { it.state == WindowState.Maximized })
    }

    @Test
    fun `close all keeps pinned windows`() {
        var state = open(WorkspaceState(), "w1", budget)
        state = open(state, "w2", callsheet)
        state = WorkspaceReducer.togglePin(state, WindowId("w1"))

        state = WorkspaceReducer.closeAll(state)

        assertEquals(listOf(WindowId("w1")), state.windows.map { it.id })
    }

    @Test
    fun `closed routes are recorded so they can be reopened`() {
        var state = open(WorkspaceState(), "w1", budget)
        state = WorkspaceReducer.close(state, WindowId("w1"))

        assertEquals(listOf(budget), state.recentlyClosed)
    }

    @Test
    fun `pinned windows sort ahead of the rest`() {
        var state = open(WorkspaceState(), "w1", budget)
        state = open(state, "w2", callsheet)
        state = WorkspaceReducer.togglePin(state, WindowId("w2"))

        assertEquals(WindowId("w2"), state.orderedWindows.first().id)
    }

    @Test
    fun `opening is a no-op when MDI is disabled for the project`() {
        // Mirrors the web Remote Config gate: the app falls back to
        // single-window navigation rather than opening anything.
        val state = open(WorkspaceState(mdiEnabled = false), "w1", budget)

        assertTrue(state.windows.isEmpty())
    }

    @Test
    fun `dirty windows are reported so close can prompt`() {
        var state = open(WorkspaceState(), "w1", budget)
        assertFalse(state.hasDirtyWindows)

        state = WorkspaceReducer.setDirty(state, WindowId("w1"), dirty = true)

        assertTrue(state.hasDirtyWindows)
    }

    @Test
    fun `detaching and re-docking round-trips the prior state`() {
        var state = open(WorkspaceState(), "w1", budget, openMode = OpenMode.Maximized)

        state = WorkspaceLayoutReducer.setDetached(state, WindowId("w1"), detached = true)
        assertEquals(WindowState.Detached, state.window(WindowId("w1"))?.state)

        state = WorkspaceLayoutReducer.setDetached(state, WindowId("w1"), detached = false)
        assertEquals(WindowState.Maximized, state.window(WindowId("w1"))?.state)
    }

    private fun open(
        state: WorkspaceState,
        id: String,
        route: WorkspaceRoute,
        openMode: OpenMode = OpenMode.Window,
    ) = WorkspaceReducer.open(
        state = state,
        id = WindowId(id),
        route = route,
        title = route.path.substringAfterLast('/'),
        iconKey = "tool",
        openMode = openMode,
    )
}
