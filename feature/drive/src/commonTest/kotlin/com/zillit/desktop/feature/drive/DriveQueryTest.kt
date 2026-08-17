package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.domain.DriveGrouping
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveQuery
import com.zillit.desktop.feature.drive.domain.DriveQuickFilter
import com.zillit.desktop.feature.drive.domain.DriveSort
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.domain.groupedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the listing asks the server for, and how it buckets what comes back. */
class DriveQueryTest {

    @Test
    fun `the root asks for root, not a null folder id`() {
        val parameters = DriveQuery(folderId = null).toParameters()

        assertEquals("true", parameters["root"])
        // Both would make the server ignore the folder and answer with the
        // root, which presents as "clicking into a folder does nothing".
        assertFalse(parameters.containsKey("folder_id"))
    }

    @Test
    fun `a folder asks for that folder and not for root`() {
        val parameters = DriveQuery(folderId = "abc").toParameters()

        assertEquals("abc", parameters["folder_id"])
        assertFalse(parameters.containsKey("root"))
    }

    @Test
    fun `sort travels as the server's two fields`() {
        val parameters = DriveQuery(sort = DriveSort.SizeDesc).toParameters()

        assertEquals("size", parameters["sort_by"])
        assertEquals("desc", parameters["sort_order"])
    }

    @Test
    fun `an unknown sort id falls back to newest first`() {
        assertEquals(DriveSort.DateDesc, DriveSort.from(null))
        assertEquals(DriveSort.DateDesc, DriveSort.from("something_else"))
    }

    @Test
    fun `optional narrowings are omitted rather than sent empty`() {
        val bare = DriveQuery().toParameters()

        // An empty `search` matches nothing on some servers and everything on
        // others; omitting it is the only unambiguous way to mean "no filter".
        assertFalse(bare.containsKey("search"))
        assertFalse(bare.containsKey("group_by"))
        assertFalse(bare.containsKey("quick_filter"))
        assertFalse(bare.containsKey("tag_id"))
    }

    @Test
    fun `a cache key changes with every field that changes the result`() {
        val base = DriveQuery(folderId = "f1")

        val keys = setOf(
            base.cacheKey(),
            base.copy(quickFilter = DriveQuickFilter.Mine).cacheKey(),
            base.copy(sort = DriveSort.NameAsc).cacheKey(),
            base.copy(grouping = DriveGrouping.Type).cacheKey(),
            base.copy(search = "call sheet").cacheKey(),
            base.copy(tagId = "t1").cacheKey(),
            base.copy(offset = 50).cacheKey(),
        )

        // A key that omits one of these serves the wrong rows for the right
        // question — "Mine" results shown under "All", for instance.
        assertEquals(7, keys.size)
    }

    @Test
    fun `search is normalised into the cache key`() {
        val a = DriveQuery(search = "Call Sheet").cacheKey()
        val b = DriveQuery(search = "  call sheet ").cacheKey()

        assertEquals(a, b)
    }

    @Test
    fun `grouping preserves the server's order rather than sorting keys`() {
        val items = listOf(
            item("z", kind = DriveItemKind.Folder),
            item("a"),
            item("m"),
        )

        val groups = items.groupedBy(DriveGrouping.Type)

        // Folders first because that is the order given. Re-sorting the keys
        // here would silently override the sort the user chose.
        assertEquals(listOf("Folders", "Files"), groups.map { it.key })
        assertEquals(2, groups.last().items.size)
    }

    @Test
    fun `no grouping is one bucket, not none`() {
        val groups = listOf(item("a")).groupedBy(DriveGrouping.None)

        assertEquals(1, groups.size)
        assertEquals(1, groups.single().items.size)
    }

    @Test
    fun `extension grouping names a folder as such rather than blank`() {
        assertEquals("Folder", DriveGrouping.Extension.keyFor(item("f", kind = DriveItemKind.Folder)))
        assertEquals("PDF", DriveGrouping.Extension.keyFor(item("a", extension = "pdf")))
        assertEquals("No extension", DriveGrouping.Extension.keyFor(item("a")))
    }

    @Test
    fun `preview kind trusts the sniffed mime type over the extension`() {
        // The server stores a MIME type it sniffed from the bytes. A `.dat`
        // that is really a JPEG previews correctly only if that wins.
        assertEquals(PreviewKind.Image, PreviewKind.of("image/jpeg", "photo.dat"))
        assertEquals(PreviewKind.Video, PreviewKind.of(null, "dailies.mov"))
        assertEquals(PreviewKind.Audio, PreviewKind.of(null, "take-3.wav"))
        assertEquals(PreviewKind.Document, PreviewKind.of(null, "script.pdf"))
    }

    @Test
    fun `editable documents are the office formats and nothing else`() {
        assertTrue(item("a", extension = "docx").isEditableDocument)
        assertTrue(item("a", extension = "xlsx").isEditableDocument)
        // A PDF opens a viewer that cannot save — offering "Edit" on one reads
        // as a broken feature rather than an unsupported one.
        assertFalse(item("a", extension = "pdf").isEditableDocument)
        assertFalse(item("a", kind = DriveItemKind.Folder).isEditableDocument)
    }

    @Test
    fun `sizes read two decimals below ten and none above`() {
        assertEquals("—", formatBytes(0))
        assertEquals("840 B", formatBytes(840))
        assertEquals("1.00 MB", formatBytes(1024L * 1024))
    }

    @Test
    fun `a zero quota is no quota, not a full one`() {
        val usage = com.zillit.desktop.feature.drive.domain.StorageUsage(
            usedBytes = 1024,
            quotaBytes = null,
        )

        // A meter drawn against a zero quota reads as full, which is the
        // opposite of what "no allowance configured" means.
        assertNull(usage.fraction)
    }

    private fun item(
        id: String,
        kind: DriveItemKind = DriveItemKind.File,
        extension: String = "",
    ) = DriveItem(id = id, kind = kind, name = id, extension = extension)
}
