package com.zillit.desktop.feature.esignature

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The web's `useDocuSignAccess`, pinned: view or posting admits; a member
 * with neither posting nor admin is receiver-only; download is strict.
 */
class EsignAccessTest {

    private fun permissions(
        canView: Boolean = true,
        canPost: Boolean = true,
        canDownload: Boolean = false,
        isAdmin: Boolean = false,
    ) = ProjectPermissions(
        listOf(
            ToolAccess(
                identifier = EsignViewer.TOOL_IDENTIFIER,
                enabled = true,
                canView = canView,
                canPost = canPost,
                canDownload = canDownload,
            ),
        ),
        isAdmin = isAdmin,
    )

    @Test
    fun `an unresolved viewer is not blocked`() {
        val viewer = EsignViewer.from(ProjectPermissions.Empty)

        assertFalse(viewer.ready)
        assertFalse(viewer.isBlocked)
    }

    @Test
    fun `neither right blocks once resolved`() {
        val viewer = EsignViewer.from(permissions(canView = false, canPost = false))

        assertTrue(viewer.isBlocked)
    }

    @Test
    fun `view without posting is receiver-only`() {
        val viewer = EsignViewer.from(permissions(canPost = false))

        assertFalse(viewer.isBlocked)
        assertTrue(viewer.receiverOnly)
    }

    @Test
    fun `posting admits the manager surfaces`() {
        val viewer = EsignViewer.from(permissions())

        assertFalse(viewer.receiverOnly)
        assertTrue(viewer.canPost)
    }

    @Test
    fun `download stays strict even for posting users`() {
        val viewer = EsignViewer.from(permissions(canDownload = false))

        assertFalse(viewer.canDownload)
    }
}
