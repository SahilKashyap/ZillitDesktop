package com.zillit.desktop.core.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Leaving a production must take its windows with it.
 *
 * Plan M3: "unit & project switching (wired into the workspace: closes
 * project-scoped windows)". A pinned window surviving the switch is not a
 * convenience — it is the previous production's content sitting in the new
 * one's workspace.
 */
class ProjectSwitchCloseTest {

    private fun stateWithPinned(): WorkspaceState {
        var state = WorkspaceState()
        state = open(state, "w0", WorkspaceRoute.Home)
        state = open(state, "w1", WorkspaceRoute.Tool("/drive"))
        return WorkspaceReducer.togglePin(state, WindowId("w1"))
    }

    private fun open(state: WorkspaceState, id: String, route: WorkspaceRoute) =
        WorkspaceReducer.open(
            state = state,
            id = WindowId(id),
            route = route,
            title = route.path.substringAfterLast('/'),
            iconKey = "tool",
        )

    @Test
    fun `an ordinary close-all spares pinned windows`() {
        val closed = WorkspaceReducer.closeAll(stateWithPinned())

        assertEquals(1, closed.windows.size, "the user asked to tidy up, not to lose their pin")
        assertTrue(closed.windows.single().isPinned)
    }

    @Test
    fun `switching production closes pinned windows too`() {
        val closed = WorkspaceReducer.closeAll(stateWithPinned(), includePinned = true)

        assertTrue(closed.windows.isEmpty(), "a pinned window survived into another project")
    }
}
