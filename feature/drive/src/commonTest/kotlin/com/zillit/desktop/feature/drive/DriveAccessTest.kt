package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.eligible
import com.zillit.desktop.feature.drive.ui.DriveDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two-level rights model.
 *
 * Drive is the only tool with a tool-level *and* an item-level gate, and
 * conflating them is the mistake this file exists to prevent. The failure mode
 * is silent both ways: an action offered that then 403s, or one hidden from
 * someone who was entitled to it.
 */
class DriveAccessTest {

    private fun file(
        id: String = "f1",
        permissions: DrivePermissions = DrivePermissions.ViewOnly,
        kind: DriveItemKind = DriveItemKind.File,
    ) = DriveItem(id = id, kind = kind, name = "$id.pdf", permissions = permissions)

    private fun viewer(
        canView: Boolean = true,
        canPost: Boolean = true,
        canDownload: Boolean = true,
        isAdmin: Boolean = false,
    ) = DriveViewer.from(
        permissions = ProjectPermissions(
            tools = listOf(
                ToolAccess(
                    identifier = DriveViewer.TOOL_IDENTIFIER,
                    canView = canView,
                    canPost = canPost,
                    canDownload = canDownload,
                ),
            ),
            isAdmin = isAdmin,
        ),
        userId = "u1",
        displayName = "Ada",
    )

    @Test
    fun `an empty permission set is unresolved rather than a denial`() {
        val unresolved = DriveViewer.from(ProjectPermissions.Empty, "u1", "Ada")

        assertFalse(unresolved.ready)
        assertFalse(unresolved.isBlocked)
    }

    @Test
    fun `tool posting rights do not grant per-item edit`() {
        // The trap: someone who may upload to the drive still cannot edit a
        // file they only have view access to.
        val poster = viewer(canPost = true)

        assertTrue(poster.canCreate)
        assertFalse(poster.may(DriveAction.Edit, file()))
        assertFalse(poster.may(DriveAction.Delete, file()))
    }

    @Test
    fun `per-item rights do not survive a missing tool right`() {
        // The other direction, and the subtler one: the server's middleware
        // refuses a download before the per-item check ever runs, so offering
        // the button on the strength of the item alone produces a 403.
        val noDownload = viewer(canDownload = false)
        val downloadable = file(permissions = DrivePermissions.Owner)

        assertFalse(noDownload.may(DriveAction.Download, downloadable))
        assertTrue(noDownload.may(DriveAction.Edit, downloadable))
    }

    @Test
    fun `a file's own uploader holds owner rights on it`() {
        // FR-05.7, and not cosmetic: the listing routes send no per-item flags
        // at all, so without this every row is view-only and someone cannot
        // delete a file they uploaded a moment earlier.
        val actor = viewer()
        val mine = file(id = "mine").copy(uploadedById = "u1")

        assertTrue(actor.may(DriveAction.Delete, mine))
        assertTrue(actor.may(DriveAction.Download, mine))
        assertTrue(actor.may(DriveAction.Share, mine))
    }

    @Test
    fun `someone else's file with no explicit grant stays view-only`() {
        val actor = viewer()
        val theirs = file(id = "theirs").copy(uploadedById = "u2")

        assertFalse(actor.may(DriveAction.Delete, theirs))
        assertTrue(actor.may(DriveAction.View, theirs))
    }

    @Test
    fun `an explicit grant still wins over the ownership fallback`() {
        // A row the server *did* describe is described correctly — ownership is
        // only consulted where it said nothing.
        val actor = viewer()
        val described = file(id = "d", permissions = DriveRole.Editor.permissions)
            .copy(uploadedById = "u1")

        assertTrue(actor.may(DriveAction.Edit, described))
        assertFalse(actor.may(DriveAction.Delete, described))
    }

    @Test
    fun `an anonymous viewer never inherits ownership`() {
        // A blank user id must not match a blank `uploaded_by` and hand the
        // whole drive to someone whose session has not resolved yet.
        val unresolved = DriveViewer(userId = "", canView = true, canPost = true, ready = true)

        assertFalse(unresolved.may(DriveAction.Delete, file()))
    }

    @Test
    fun `an admin passes both gates`() {
        val admin = viewer(canView = false, canPost = false, canDownload = false, isAdmin = true)

        assertTrue(admin.may(DriveAction.Delete, file()))
        assertTrue(admin.may(DriveAction.Download, file()))
        assertTrue(admin.canCreate)
    }

    @Test
    fun `sharing is an owner's act`() {
        val editor = viewer()
        val edited = file(permissions = DriveRole.Editor.permissions)
        val owned = file(permissions = DriveRole.Owner.permissions)

        // Sharing rewrites who else can reach the file; the server models that
        // as delete rights, which only owners and creators hold.
        assertFalse(editor.may(DriveAction.Share, edited))
        assertTrue(editor.may(DriveAction.Share, owned))
    }

    @Test
    fun `roles resolve to the permission set the server would send`() {
        assertEquals(DrivePermissions.Owner, DriveRole.Owner.permissions)

        val editor = DriveRole.Editor.permissions
        assertTrue(editor.canEdit && editor.canDownload)
        assertFalse(editor.canDelete)

        val plainViewer = DriveRole.Viewer.permissions
        assertTrue(plainViewer.canView)
        assertFalse(plainViewer.canDownload)
    }

    @Test
    fun `a bulk selection reports only what will actually be deleted`() {
        val actor = viewer()
        val items = listOf(
            file("a", DrivePermissions.Owner),
            file("b", DrivePermissions.ViewOnly),
            file("c", DrivePermissions.Owner),
        )

        // The server checks each item, so a mixed selection partially succeeds.
        // Knowing the number up front is what lets the toolbar say "Delete 2 of
        // 3" instead of reporting the shortfall afterwards.
        assertEquals(listOf("a", "c"), actor.eligible(DriveAction.Delete, items).map { it.id })
    }

    @Test
    fun `a production that withholds view blocks the whole tool`() {
        val blocked = viewer(canView = false, canPost = false, canDownload = false)

        assertTrue(blocked.isBlocked)
        assertTrue(DriveDestination.entries.none { it.visibleTo(blocked) })
    }

    @Test
    fun `trash is visible to everyone who can see the drive`() {
        // The server shows a regular user only their own deleted items, which
        // is what makes this safe. Hiding it from non-posters would strand
        // anyone who deleted something by accident.
        val readOnly = viewer(canPost = false)

        assertTrue(DriveDestination.Trash.visibleTo(readOnly))
        assertFalse(DriveDestination.Storage.visibleTo(readOnly))
        assertEquals(DriveDestination.Browse, DriveDestination.landing(readOnly))
    }
}
