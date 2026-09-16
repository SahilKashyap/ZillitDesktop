package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveListQuery
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DriveScope
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveSortColumn
import com.zillit.desktop.feature.drive.domain.DriveSortSpec
import com.zillit.desktop.feature.drive.domain.DriveView
import com.zillit.desktop.feature.drive.domain.MyDriveFilter
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.breadcrumbTo
import com.zillit.desktop.feature.drive.domain.combinedRows
import com.zillit.desktop.feature.drive.domain.descendantsOf
import com.zillit.desktop.feature.drive.domain.folderTree
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.domain.innerTabTotals
import com.zillit.desktop.feature.drive.domain.relativeTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The listing as the web computes it — `DriveManagement.combinedData` and
 * its neighbours, ported as pure functions and pinned here.
 */
class DriveListingTest {

    private fun folder(id: String, name: String, parent: String? = null) =
        DriveItem(id = id, kind = DriveItemKind.Folder, name = name, parentFolderId = parent)

    private fun file(id: String, name: String, parent: String? = null, size: Long = 0, modified: Long? = null) =
        DriveItem(
            id = id,
            kind = DriveItemKind.File,
            name = name,
            parentFolderId = parent,
            sizeBytes = size,
            updatedAt = modified,
        )

    private val listing = DriveListing(
        folders = listOf(folder("a", "Art"), folder("b", "Boards", parent = "a"), folder("c", "Camera")),
        files = listOf(
            file("1", "cover.png", size = 100),
            file("2", "sketch.png", parent = "a", size = 10),
            file("3", "board.pdf", parent = "b", size = 5),
        ),
    )

    // -- scope -------------------------------------------------------------

    @Test
    fun `the section and the My Drive filter resolve to the server's quick filter`() {
        assertEquals(DriveScope.Shared, DriveScope.of(DriveSection.SharedWithMe, MyDriveFilter.All))
        assertEquals(DriveScope.Shared, DriveScope.of(DriveSection.SharedWithMe, MyDriveFilter.SharedByMe))
        assertEquals(DriveScope.SharedByMe, DriveScope.of(DriveSection.MyDrive, MyDriveFilter.SharedByMe))
        assertEquals(DriveScope.Mine, DriveScope.of(DriveSection.MyDrive, MyDriveFilter.All))
    }

    @Test
    fun `a listing query sends the quick filter and only a non-blank search`() {
        assertEquals(mapOf("quick_filter" to "mine"), DriveListQuery(DriveScope.Mine).toParameters())
        assertEquals(
            mapOf("quick_filter" to "shared", "search" to "cover"),
            DriveListQuery(DriveScope.Shared, search = "  cover ").toParameters(),
        )
    }

    // -- rows --------------------------------------------------------------

    @Test
    fun `the root shows folders under the Folders tab and loose files under Files`() {
        val folders = combinedRows(listing, DriveView(innerTab = DriveInnerTab.Folders))
        val files = combinedRows(listing, DriveView(innerTab = DriveInnerTab.Files))

        assertEquals(listOf("Art", "Camera"), folders.map { it.name })
        assertEquals(listOf("cover.png"), files.map { it.name })
    }

    @Test
    fun `inside a folder both kinds share one list, folders first`() {
        val rows = combinedRows(listing, DriveView(folderId = "a", innerTab = DriveInnerTab.Files))

        assertEquals(listOf("Boards", "sketch.png"), rows.map { it.name })
    }

    @Test
    fun `a search shows every match regardless of folder`() {
        val rows = combinedRows(listing, DriveView(isSearching = true, folderId = "c"))

        assertEquals(6, rows.size)
    }

    @Test
    fun `a folder's size is everything beneath it and its count is its own files`() {
        val art = combinedRows(listing, DriveView()).first { it.id == "a" }

        assertEquals(15L, art.sizeBytes, "sketch.png plus board.pdf, bubbled up from Boards")
        assertEquals(1, art.itemCount)
    }

    @Test
    fun `the tag and favourites filters narrow what is shown and what the tabs count`() {
        val view = DriveView(taggedIds = setOf("1", "c"), innerTab = DriveInnerTab.Files)

        assertEquals(listOf("cover.png"), combinedRows(listing, view).map { it.name })
        assertEquals(1 to 1, innerTabTotals(listing, view))

        val starred = DriveView(showFavouritesOnly = true, favouriteIds = setOf("a"))
        assertEquals(listOf("Art"), combinedRows(listing, starred).map { it.name })
    }

    @Test
    fun `the tab totals count the folder's own children`() {
        assertEquals(2 to 1, innerTabTotals(listing, DriveView()))
        assertEquals(1 to 1, innerTabTotals(listing, DriveView(folderId = "a")))
    }

    // -- sort --------------------------------------------------------------

    @Test
    fun `a header click cycles ascending, descending, off`() {
        val start = DriveSortSpec()
        val asc = start.toggled(DriveSortColumn.Name)
        val desc = asc.toggled(DriveSortColumn.Name)
        val off = desc.toggled(DriveSortColumn.Name)

        assertEquals(DriveSortSpec(DriveSortColumn.Name, ascending = true), asc)
        assertEquals(DriveSortSpec(DriveSortColumn.Name, ascending = false), desc)
        assertEquals(DriveSortSpec(), off)
        assertEquals(DriveSortSpec(DriveSortColumn.Size, ascending = true), desc.toggled(DriveSortColumn.Size))
    }

    @Test
    fun `sorting keeps folders ahead of files whatever the column`() {
        val bySize = combinedRows(
            listing,
            DriveView(folderId = "a", sort = DriveSortSpec(DriveSortColumn.Size, ascending = false)),
        )
        assertEquals(listOf("Boards", "sketch.png"), bySize.map { it.name })

        val rows = DriveListing(files = listOf(file("x", "b.txt", size = 2), file("y", "a.txt", size = 9)))
        val byName = combinedRows(
            rows,
            DriveView(innerTab = DriveInnerTab.Files, sort = DriveSortSpec(DriveSortColumn.Name)),
        )
        assertEquals(listOf("a.txt", "b.txt"), byName.map { it.name })
        val bySizeDesc = combinedRows(
            rows,
            DriveView(innerTab = DriveInnerTab.Files, sort = DriveSortSpec(DriveSortColumn.Size, ascending = false)),
        )
        assertEquals(listOf("a.txt", "b.txt"), bySizeDesc.map { it.name })
    }

    // -- trails and trees --------------------------------------------------

    @Test
    fun `the breadcrumb is the folder's ancestry, and stops where the scope cannot see`() {
        assertEquals(listOf("Art", "Boards"), breadcrumbTo("b", listing.folders).map { it.name })
        assertEquals(emptyList(), breadcrumbTo(null, listing.folders))

        val orphan = listOf(folder("z", "Zebra", parent = "missing"))
        assertEquals(listOf("Zebra"), breadcrumbTo("z", orphan).map { it.name })
    }

    @Test
    fun `the folder tree nests by parent and leaves out what is being moved`() {
        val tree = folderTree(listing.folders)
        assertEquals(listOf("Art", "Camera"), tree.map { it.folder.name })
        assertEquals(listOf("Boards"), tree.first().children.map { it.folder.name })

        val without = folderTree(listing.folders, excluded = descendantsOf("a", listing.folders))
        assertEquals(listOf("Camera"), without.map { it.folder.name })
    }

    @Test
    fun `a folder whose parent is outside the scope still roots the tree`() {
        val shared = listOf(folder("s", "Shared sub", parent = "not-mine"))
        assertEquals(listOf("Shared sub"), folderTree(shared).map { it.folder.name })
    }

    // -- odds and ends -----------------------------------------------------

    @Test
    fun `preview kind trusts the sniffed mime type over the extension`() {
        assertEquals(PreviewKind.Image, PreviewKind.of("image/jpeg", "scan.dat"))
        assertEquals(PreviewKind.Video, PreviewKind.of(null, "clip.mov"))
        assertEquals(PreviewKind.Pdf, PreviewKind.of("application/pdf", "sides.bin"))
        assertEquals(PreviewKind.Text, PreviewKind.of(null, "notes.md"))
        assertEquals(PreviewKind.Document, PreviewKind.of(null, "deck.pptx"))
    }

    @Test
    fun `editable documents are the office formats and nothing else`() {
        assertTrue(file("d", "deck.pptx").copy(extension = "pptx").isEditableDocument)
        assertTrue(file("t", "notes.txt").copy(extension = "txt").isEditableDocument)
        assertFalse(file("p", "sides.pdf").copy(extension = "pdf").isEditableDocument)
        assertFalse(folder("f", "Folder").isEditableDocument)
    }

    @Test
    fun `sizes read two decimals below ten and none above`() {
        assertEquals("—", formatBytes(0))
        assertEquals("840 B", formatBytes(840))
        assertEquals("1.20 MB", formatBytes(1_258_300))
        assertEquals("12 MB", formatBytes(12_582_912))
    }

    @Test
    fun `relative time reads like dayjs`() {
        val now = 1_000_000_000_000L
        assertEquals("just now", relativeTime(now - 10_000, now))
        assertEquals("a minute ago", relativeTime(now - 90_000, now))
        assertEquals("5 minutes ago", relativeTime(now - 5 * 60_000, now))
        assertEquals("an hour ago", relativeTime(now - 3_600_000, now))
        assertEquals("2 days ago", relativeTime(now - 2 * 86_400_000, now))
    }
}
