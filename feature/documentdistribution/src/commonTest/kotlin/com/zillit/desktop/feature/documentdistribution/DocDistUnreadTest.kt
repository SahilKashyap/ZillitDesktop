package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.documentdistribution.data.docDistDeletedIds
import com.zillit.desktop.feature.documentdistribution.domain.DocDistBadgeLeaf
import com.zillit.desktop.feature.documentdistribution.domain.DocDistBadges
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRowRef
import com.zillit.desktop.feature.documentdistribution.domain.DocDistUnread
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocDistUnreadTest {

    private val unread = DocDistUnread(
        listOf(
            // A file two folders deep counts on both folders above it.
            DocDistBadgeLeaf(DocDistBadges.UNIT_DOCUMENT, "file-1", setOf("f-a", "f-b"), 1),
            // A folder's own event counts on itself and its parent.
            DocDistBadgeLeaf(DocDistBadges.UNIT_FOLDER, "f-b", setOf("f-a", "f-b"), 1),
            DocDistBadgeLeaf(DocDistBadges.UNIT_TEMPLATE, "t-1", emptySet(), 2),
            DocDistBadgeLeaf(DocDistBadges.UNIT_DISTRIBUTION, "d-1", emptySet(), 1),
            DocDistBadgeLeaf(DocDistBadges.UNIT_PUBLICATION, "p-1", emptySet(), 1),
            DocDistBadgeLeaf(DocDistBadges.UNIT_PRESET, "l-1", emptySet(), 1),
            // A preset filed with a folder in level_1 — iOS's 11-vs-9 double count.
            DocDistBadgeLeaf(DocDistBadges.UNIT_PRESET, "l-2", setOf("f-a"), 1),
        ),
    )

    @Test
    fun `a folder's bubble is every folder or document row filed under it`() {
        assertEquals(2, unread.folder("f-a"), "the preset naming f-a stays off the bubble")
        assertEquals(2, unread.folder("f-b"))
        assertEquals(0, unread.folder("f-c"))
    }

    @Test
    fun `a file's dot is its own events only`() {
        assertEquals(1, unread.file("file-1"))
        assertEquals(0, unread.file("f-b"))
    }

    @Test
    fun `History is sends and publications, Lists the presets alone`() {
        assertEquals(2, unread.unit(*DocDistDestination.History.badgeUnits.toTypedArray()))
        assertEquals(2, unread.unit(*DocDistDestination.Lists.badgeUnits.toTypedArray()))
        assertEquals(2, unread.unit(*DocDistDestination.Templates.badgeUnits.toTypedArray()))
        assertEquals(0, unread.unit(*DocDistDestination.Library.badgeUnits.toTypedArray()))
    }
}

/** The wire rules the host applies to ledger rows — iOS's aggregate, entity read and delete sweep. */
class DocDistRowRulesTest {

    private val deepDocument = DocDistRowRef(
        unit = DocDistBadges.UNIT_DOCUMENT,
        referenceId = "doc-9",
        level1 = "f-1",
        level2 = "f-2",
        level3 = "f-3",
        deeper = listOf("level_4:f-4", "f-5"),
    )

    @Test
    fun `a row badges its whole folder path, and a folder event its own folder too`() {
        assertEquals(setOf("f-1", "f-2", "f-3", "f-4", "f-5"), deepDocument.ancestry())
        val folderEvent = DocDistRowRef(unit = DocDistBadges.UNIT_FOLDER, referenceId = "f-2", level1 = "f-1")
        assertEquals(setOf("f-1", "f-2"), folderEvent.ancestry())
    }

    @Test
    fun `the root marker and the shared-event units badge no folder`() {
        assertEquals(emptySet(), DocDistRowRef(unit = DocDistBadges.UNIT_DOCUMENT, level1 = "root").ancestry())
        assertEquals(emptySet(), DocDistRowRef(unit = DocDistBadges.UNIT_PRESET, level1 = "f-1").ancestry())
    }

    @Test
    fun `entering a folder reads everything beneath it, levels tail included`() {
        assertTrue(deepDocument.isUnderFolder("f-1"))
        assertTrue(deepDocument.isUnderFolder("f-3"))
        assertTrue(deepDocument.isUnderFolder("f-4"), "iOS misses the levels tail; a depth-four folder must not")
        assertTrue(DocDistRowRef(referenceId = "f-7").isUnderFolder("f-7"), "the folder's own events")
        assertFalse(deepDocument.isUnderFolder("f-elsewhere"))
        assertFalse(DocDistRowRef(level1 = "root").isUnderFolder("root"))
    }

    @Test
    fun `a delete retires every row that names the ids anywhere`() {
        assertTrue(deepDocument.isRetiredBy(setOf("f-5")))
        assertTrue(deepDocument.isRetiredBy(setOf("doc-9")))
        assertTrue(DocDistRowRef(folderId = "f-x").isRetiredBy(setOf("f-x")), "reference_data.folder_id")
        assertTrue(DocDistRowRef(parentId = "f-x").isRetiredBy(setOf("f-x")), "reference_data.parent_id")
        assertTrue(DocDistRowRef(documentId = "doc-x").isRetiredBy(setOf("doc-x")), "reference_data.document_id")
        assertFalse(deepDocument.isRetiredBy(setOf("f-other")))
    }

    @Test
    fun `an ignored row is left alone by a delete`() {
        assertFalse(deepDocument.copy(ignored = true).isRetiredBy(setOf("f-1")))
    }

    @Test
    fun `a delete frame names its entity under folder or document`() {
        val folder = frame("""{"project_id":"p","folder":{"_id":"f-1"},"device_id":"d"}""")
        val document = frame("""{"project_id":"p","document":{"_id":"doc-1"}}""")

        assertEquals(setOf("f-1"), docDistDeletedIds(FOLDER_DELETED, folder))
        assertEquals(setOf("doc-1"), docDistDeletedIds(DOCUMENT_DELETED, document))
        assertEquals(emptySet(), docDistDeletedIds(FOLDER_DELETED, document), "a document frame names no folder")
        assertEquals(setOf("a", "b"), docDistDeletedIds(FOLDER_DELETED, frame("""{"folder":[{"_id":"a"},"b"]}""")))
        assertEquals(emptySet(), docDistDeletedIds(SocketEventName("document_distribution:folder:added"), folder))
    }

    private fun frame(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private companion object {
        val FOLDER_DELETED = SocketEventName("document_distribution:folder:deleted")
        val DOCUMENT_DELETED = SocketEventName("document_distribution:document:deleted")
    }
}
