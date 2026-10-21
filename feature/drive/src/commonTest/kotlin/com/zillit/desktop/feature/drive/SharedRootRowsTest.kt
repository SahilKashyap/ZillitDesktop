package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.data.decodeDriveItem
import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DriveView
import com.zillit.desktop.feature.drive.domain.combinedRows
import com.zillit.desktop.feature.drive.domain.innerTabTotals
import com.zillit.desktop.feature.drive.domain.isDriveRootRow
import com.zillit.desktop.feature.drive.domain.isSharedFolderAccessible
import com.zillit.desktop.feature.drive.domain.sharedAccessibleFolderIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The root of "Shared with me" — REL-BUG-05.
 *
 * `GET /folders?quick_filter=shared` answers with the shared folders **and
 * their ancestors**, so a folder shared three levels down still has a path to
 * walk. An ancestor is not the recipient's to open (it answers 403), and its
 * `_userPermissions` default to `can_view: true`, so the flag cannot tell the
 * two apart. Before this, the root listed the ancestor and hid what was
 * actually shared: one row, and opening it was refused.
 */
class SharedRootRowsTest {

    private val me = "u-me"
    private val owner = "u-owner"

    private fun folder(
        id: String,
        parent: String? = null,
        accessUsers: List<String>? = null,
        sharedAt: Long? = null,
        creator: String = owner,
    ) = DriveItem(
        id = id,
        kind = DriveItemKind.Folder,
        name = id,
        parentFolderId = parent,
        createdById = creator,
        accessUserIds = accessUsers.orEmpty(),
        hasAccessList = accessUsers != null,
        sharedAt = sharedAt,
    )

    private fun file(id: String, parent: String? = null) =
        DriveItem(id = id, kind = DriveItemKind.File, name = "$id.pdf", parentFolderId = parent, createdById = owner)

    // Team (an ancestor: listed so the path can be walked, with an access
    // list that does not hold this person) › Headshots (shared with me).
    private val team = folder("team", accessUsers = emptyList())
    private val headshots = folder("headshots", parent = "team", accessUsers = listOf(me))
    private val listing = DriveListing(
        folders = listOf(team, headshots),
        files = listOf(file("f1", parent = "headshots")),
    )

    @Test
    fun `an own access row opens a folder, a bare ancestor does not`() {
        assertTrue(isSharedFolderAccessible(headshots, me))
        assertFalse(isSharedFolderAccessible(team, me))
        assertEquals(setOf("headshots"), sharedAccessibleFolderIds(listing.folders, me))
    }

    @Test
    fun `a share time counts as access even when the list leaves the person out`() {
        val byStamp = folder("stills", accessUsers = emptyList(), sharedAt = 1_754_000_000_000)
        assertTrue(isSharedFolderAccessible(byStamp, me))
    }

    @Test
    fun `an older server that sends no access list is trusted, as the web trusts it`() {
        assertTrue(isSharedFolderAccessible(folder("old"), me))
    }

    @Test
    fun `can_view false is refused whatever else the row carries`() {
        val hidden = folder("hidden", accessUsers = listOf(me)).copy(
            permissions = DrivePermissions(canView = false),
            hasExplicitPermissions = true,
        )
        assertFalse(isSharedFolderAccessible(hidden, me))
    }

    @Test
    fun `the person's own folder is theirs to open`() {
        assertTrue(isSharedFolderAccessible(folder("mine", accessUsers = emptyList(), creator = me), me))
    }

    @Test
    fun `the shared root lists what was shared, not the ancestor carrying the path`() {
        val accessible = sharedAccessibleFolderIds(listing.folders, me)
        val rows = combinedRows(
            listing,
            DriveView(folderId = null, innerTab = DriveInnerTab.Folders, accessibleIds = accessible),
        )
        assertEquals(listOf("headshots"), rows.map { it.id })
    }

    @Test
    fun `the tab counts agree with the rows at the shared root`() {
        val view = DriveView(folderId = null, accessibleIds = sharedAccessibleFolderIds(listing.folders, me))
        // One folder (Headshots) and no root files: f1 sits inside a folder
        // the person can open, so it is not a top row.
        assertEquals(1 to 0, innerTabTotals(listing, view))
    }

    @Test
    fun `a file shared on its own is a top row, since its folder is not openable`() {
        val loose = file("f2", parent = "team")
        val view = DriveView(folderId = null, accessibleIds = setOf("headshots"))
        assertTrue(isDriveRootRow(loose, view.accessibleIds))
        assertFalse(isDriveRootRow(file("f1", parent = "headshots"), view.accessibleIds))
    }

    @Test
    fun `My Drive keeps the plain test - a root row is one with no parent`() {
        assertTrue(isDriveRootRow(folder("a"), accessible = null))
        assertFalse(isDriveRootRow(folder("b", parent = "a"), accessible = null))
    }

    // -- what the wire carries ---------------------------------------------

    @Test
    fun `an absent access list and an empty one are told apart off the wire`() {
        val absent = assertNotNull(decodeDriveItem("""{"_id":"f1","folder_name":"Team"}""", DriveItemKind.Folder))
        val empty = assertNotNull(
            decodeDriveItem("""{"_id":"f2","folder_name":"Stills","_accessUserIds":[]}""", DriveItemKind.Folder),
        )

        assertFalse(absent.hasAccessList, "no field means no list")
        assertTrue(empty.hasAccessList, "an empty array is still a list")
        // Which is exactly the difference the root row test turns on.
        assertTrue(isSharedFolderAccessible(absent, me))
        assertFalse(isSharedFolderAccessible(empty, me))
    }

    @Test
    fun `the share stamp is read off _sharedAt`() {
        val row = assertNotNull(
            decodeDriveItem(
                """{"_id":"f3","folder_name":"Stills","_accessUserIds":[],"_sharedAt":1754000000000}""",
                DriveItemKind.Folder,
            ),
        )
        assertEquals(1_754_000_000_000, row.sharedAt)
        assertTrue(isSharedFolderAccessible(row, me))
    }
}
