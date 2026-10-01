@file:Suppress("MaxLineLength","LongParameterList") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The service answers on the tool's own row, so the viewer reads nothing else. */
class TasksViewerTest {

    private fun permissions(row: ToolAccess?, admin: Boolean = false) =
        ProjectPermissions(listOfNotNull(row, ToolAccess(identifier = "other_tool", canView = true)), isAdmin = admin)

    private fun row(view: Boolean, post: Boolean, enabled: Boolean = true) =
        ToolAccess(identifier = TasksViewer.TOOL, enabled = enabled, canView = view, canPost = post)

    /** An empty rights list is "not answered yet" — nothing may be requested, and nothing is denied. */
    @Test
    fun `an unanswered rights list is not a denial`() {
        val viewer = TasksViewer.from(ProjectPermissions.Empty)

        assertFalse(viewer.resolved)
        assertFalse(viewer.canCall)
    }

    @Test
    fun `a production without the tool has no row, so nothing is called`() {
        val viewer = TasksViewer.from(permissions(null))

        assertTrue(viewer.resolved)
        assertFalse(viewer.enabled)
        assertFalse(viewer.canCall)
    }

    @Test
    fun `view and posting rights are read as issued`() {
        val viewer = TasksViewer.from(permissions(row(view = true, post = false)))

        assertTrue(viewer.canCall)
        assertFalse(viewer.canPost)
    }

    /** A write the service refuses with a 403 reads as "removed from the project", so admin does not stand in for the row. */
    @Test
    fun `being an admin does not grant posting`() {
        val viewer = TasksViewer.from(permissions(row(view = true, post = false), admin = true))

        assertFalse(viewer.canPost)
        assertEquals(true, viewer.canView)
    }

    @Test
    fun `a tool the production switched off cannot be called`() {
        assertFalse(TasksViewer.from(permissions(row(view = true, post = true, enabled = false))).canCall)
    }
}
