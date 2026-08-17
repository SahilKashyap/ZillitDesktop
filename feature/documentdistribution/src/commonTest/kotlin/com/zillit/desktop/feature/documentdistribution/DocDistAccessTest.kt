package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rights table.
 *
 * Every rule in [DocDistDestination.visibleTo] and [DocDistViewer] is here,
 * because rights are the one thing in this module that cannot be checked by
 * looking at the screen: the failure mode is a tab that *should not* be there,
 * and nothing renders differently when it is.
 */
class DocDistAccessTest {

    @Test
    fun `an empty permission set is unresolved rather than a denial`() {
        val viewer = DocDistViewer.from(ProjectPermissions.Empty, userId = "u1", userEmail = "")

        // The window between opening a production and the tools call answering.
        // Denying here is what would flash "no access" at every user on every
        // open, so an empty set must resolve to the soft default.
        assertFalse(viewer.ready)
        assertFalse(viewer.isBlocked)
        assertTrue(viewer.canView)
    }

    @Test
    fun `a production that withholds view blocks the whole tool`() {
        val viewer = viewerWith(canView = false, canPost = false, canDownload = false)

        assertTrue(viewer.isBlocked)
        assertTrue(DocDistDestination.entries.none { it.visibleTo(viewer) })
    }

    @Test
    fun `history needs posting rights, the library does not`() {
        val readOnly = viewerWith(canView = true, canPost = false, canDownload = true)

        assertTrue(DocDistDestination.Library.visibleTo(readOnly))
        // History names every recipient of every send. A viewer who may only
        // read the library has not been given the production's mailing list.
        assertFalse(DocDistDestination.History.visibleTo(readOnly))
        assertEquals(DocDistDestination.Library, DocDistDestination.landing(readOnly))
    }

    @Test
    fun `a full viewer sees every page`() {
        val full = viewerWith(canView = true, canPost = true, canDownload = true)

        assertEquals(DocDistDestination.entries.size, DocDistDestination.entries.count { it.visibleTo(full) })
        assertFalse(full.isRestricted)
    }

    @Test
    fun `missing download rights alone still restricts`() {
        val viewer = viewerWith(canView = true, canPost = true, canDownload = false)

        // The banner exists so someone whose Download button is absent learns
        // it is a rights question rather than a bug.
        assertTrue(viewer.isRestricted)
        assertFalse(viewer.isBlocked)
    }

    @Test
    fun `an admin passes every gate`() {
        val permissions = ProjectPermissions(
            tools = listOf(
                ToolAccess(
                    identifier = DocDistViewer.TOOL_IDENTIFIER,
                    canView = false,
                    canPost = false,
                    canDownload = false,
                ),
            ),
            isAdmin = true,
        )

        val viewer = DocDistViewer.from(permissions, userId = "admin", userEmail = "a@b.co")

        assertTrue(viewer.canView)
        assertTrue(viewer.canPost)
        assertTrue(viewer.canDownload)
    }

    private fun viewerWith(
        canView: Boolean,
        canPost: Boolean,
        canDownload: Boolean,
    ): DocDistViewer = DocDistViewer.from(
        permissions = ProjectPermissions(
            tools = listOf(
                ToolAccess(
                    identifier = DocDistViewer.TOOL_IDENTIFIER,
                    canView = canView,
                    canPost = canPost,
                    canDownload = canDownload,
                ),
            ),
        ),
        userId = "u1",
        userEmail = "u1@example.com",
    )
}
