package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who gets into Documents & Signature.
 *
 * View access is the door; posting access is authorship. Signing needs no
 * posting rights — a view-only crew member must be able to sign what is sent
 * to them, which is most of what the tool exists for.
 */
class FormSignatureAccessTest {

    private fun permissions(
        canView: Boolean = true,
        canPost: Boolean = true,
        isAdmin: Boolean = false,
    ) = ProjectPermissions(
        listOf(
            ToolAccess(
                identifier = FormSignatureViewer.TOOL_IDENTIFIER,
                enabled = true,
                canView = canView,
                canPost = canPost,
            ),
        ),
        isAdmin = isAdmin,
    )

    @Test
    fun `an unresolved viewer is not blocked`() {
        val viewer = FormSignatureViewer.from(ProjectPermissions.Empty)

        assertFalse(viewer.ready)
        assertFalse(viewer.isBlocked)
    }

    @Test
    fun `a resolved denial blocks`() {
        val viewer = FormSignatureViewer.from(permissions(canView = false, canPost = false))

        assertTrue(viewer.ready)
        assertTrue(viewer.isBlocked)
    }

    @Test
    fun `a view-only member enters without authorship`() {
        val viewer = FormSignatureViewer.from(permissions(canPost = false))

        assertTrue(viewer.canView)
        assertFalse(viewer.canPost)
        assertFalse(viewer.isBlocked)
    }

    @Test
    fun `posting access alone admits, as the web computes it`() {
        val viewer = FormSignatureViewer.from(permissions(canView = false, canPost = true))

        assertTrue(viewer.canView)
        assertTrue(viewer.canPost)
    }
}
