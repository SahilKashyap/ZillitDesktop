package com.zillit.desktop.feature.tasks

import com.zillit.desktop.feature.tasks.domain.AttachmentKind
import com.zillit.desktop.feature.tasks.domain.AttachmentRefusal
import com.zillit.desktop.feature.tasks.domain.HistoryLine
import com.zillit.desktop.feature.tasks.domain.MAX_ATTACHMENTS
import com.zillit.desktop.feature.tasks.domain.PrepareOutcome
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.TaskHistoryEntry
import com.zillit.desktop.feature.tasks.domain.attachmentIds
import com.zillit.desktop.feature.tasks.domain.canRemoveAttachment
import com.zillit.desktop.feature.tasks.domain.docBadge
import com.zillit.desktop.feature.tasks.domain.formatAttachmentSize
import com.zillit.desktop.feature.tasks.domain.historyLines
import com.zillit.desktop.feature.tasks.domain.prepareAttachment
import com.zillit.desktop.feature.tasks.domain.roomForAttachments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules about images and files in a description — the cases from the web's
 * `lib/attachments.js` and its tests.
 *
 * The checks here are the **service's** rules checked first, so a refusal is
 * worded before anything is sent. Nothing in this file touches an image
 * decoder: shrinking is exercised where a real bitmap exists (jvmTest).
 */
class TaskAttachmentsTest {

    private fun bytes(size: Int): ByteArray = ByteArray(size) { 7 }

    private fun prepare(name: String, mime: String, size: Int) =
        prepareAttachment(name = name, mimeType = mime, bytes = bytes(size), key = "k1")

    @Test
    fun `a document goes exactly as it is`() {
        val outcome = prepare("Budget.xlsx", "application/vnd.ms-excel", 2_000)

        val item = (outcome as PrepareOutcome.Ready).item
        assertEquals(AttachmentKind.File, item.kind)
        assertEquals("Budget.xlsx", item.name)
        assertEquals(2_000L, item.sizeBytes)
    }

    @Test
    fun `only the types the service takes are attachable`() {
        assertTrue(prepare("notes.txt", "text/plain", 10) is PrepareOutcome.Ready)
        assertTrue(prepare("crew.csv", "text/csv", 10) is PrepareOutcome.Ready)
        assertTrue(prepare("deck.pptx", "application/vnd.ms-powerpoint", 10) is PrepareOutcome.Ready)

        val refused = prepare("archive.zip", "application/zip", 10)
        assertTrue((refused as PrepareOutcome.Refused).why is AttachmentRefusal.WrongType)
        // No extension at all is the same answer, not a crash.
        assertTrue((prepare("README", "", 10) as PrepareOutcome.Refused).why is AttachmentRefusal.WrongType)
    }

    @Test
    fun `a file over 10 MB, and an empty one, are refused before anything is sent`() {
        val tooLarge = prepare("Reel.pdf", "application/pdf", 11 * 1024 * 1024)
        assertTrue((tooLarge as PrepareOutcome.Refused).why is AttachmentRefusal.FileTooLarge)

        val empty = prepare("Reel.pdf", "application/pdf", 0)
        assertTrue((empty as PrepareOutcome.Refused).why is AttachmentRefusal.Empty)
    }

    /** An image bigger than this is not worth decoding to shrink. */
    @Test
    fun `an image over 25 MB is refused without being decoded`() {
        val outcome = prepare("Set.jpg", "image/jpeg", 26 * 1024 * 1024)

        assertTrue((outcome as PrepareOutcome.Refused).why is AttachmentRefusal.InputTooLarge)
    }

    @Test
    fun `bytes that are not an image at all are refused as unreadable`() {
        // The right name and type, nothing an image decoder can read.
        val outcome = prepare("Set.jpg", "image/jpeg", 64)

        assertTrue((outcome as PrepareOutcome.Refused).why is AttachmentRefusal.Unreadable)
    }

    /** An image named `.pdf` is a document: the extension wins, as on the web. */
    @Test
    fun `an extension that names a document wins over an image type`() {
        val outcome = prepare("Scan.pdf", "image/jpeg", 1_000)

        assertEquals(AttachmentKind.File, (outcome as PrepareOutcome.Ready).item.kind)
    }

    @Test
    fun `a badge is the type's own three letters, and an unknown one is a plain file`() {
        assertEquals("PDF", docBadge("Script.PDF").label)
        assertEquals("DOC", docBadge("memo.docx").label)
        assertEquals("XLS", docBadge("budget.xlsx").label)
        assertEquals("PPT", docBadge("pitch.ppt").label)
        assertEquals("CSV", docBadge("crew.csv").label)
        assertEquals("FILE", docBadge("mystery.bin").label)
        // Word and Excel keep their own names; a text file's is a word.
        assertEquals("Word", docBadge("memo.doc").typeName)
        assertEquals("Text file", docBadge("notes.txt").typeName)
    }

    @Test
    fun `a size reads the way the web prints it`() {
        assertEquals("820 B", formatAttachmentSize(820))
        assertEquals("23 KB", formatAttachmentSize(23 * 1024))
        assertEquals("4.2 MB", formatAttachmentSize((4.2 * 1024 * 1024).toLong()))
        assertEquals("0 B", formatAttachmentSize(0))
    }

    @Test
    fun `a task takes twenty, counting what waits and what is going up`() {
        assertEquals(MAX_ATTACHMENTS, roomForAttachments(has = 0, pending = 0, uploading = 0))
        assertEquals(5, roomForAttachments(has = 10, pending = 3, uploading = 2))
        assertEquals(0, roomForAttachments(has = 19, pending = 1, uploading = 1))
    }

    private val mine = TaskAttachment(id = "a1", name = "one.jpg", uploadedBy = "me")
    private val theirs = TaskAttachment(id = "a2", name = "two.pdf", uploadedBy = "u2")

    @Test
    fun `whoever added it may remove it, and so may anyone who can delete the task`() {
        val task = Task(id = "t1", title = "T")

        assertTrue(canRemoveAttachment(mine, task, "me"))
        assertFalse(canRemoveAttachment(theirs, task, "me"))
        assertTrue(canRemoveAttachment(theirs, task.copy(canDelete = true), "me"))
        // Nobody signed in: nothing is removable.
        assertFalse(canRemoveAttachment(mine, task.copy(canDelete = true), null))
    }

    /**
     * Adding or removing one leaves `updated` alone, so the panel notices
     * someone else's by comparing the ids — this is that comparison.
     */
    @Test
    fun `the ids are what tells two copies of a task apart`() {
        val task = Task(id = "t1", title = "T", attachments = listOf(mine, theirs))

        assertEquals("a1,a2", attachmentIds(task))
        assertEquals("", attachmentIds(Task(id = "t1", title = "T")))
        assertEquals("", attachmentIds(null))
        assertTrue(attachmentIds(task) != attachmentIds(task.copy(attachments = listOf(mine))))
    }

    @Test
    fun `history says which was added and which was removed`() {
        fun entry(action: String, kind: String) = TaskHistoryEntry(
            id = "h1",
            userId = "u1",
            userName = "Riya",
            action = action,
            attachmentName = "recce.jpg",
            attachmentKind = AttachmentKind.entries.first { it.wire == kind },
        )

        val added = historyLines(entry("attachment_added", "image"), isSubtask = false).single()
        assertEquals(HistoryLine.AttachmentChanged("recce.jpg", isImage = true, added = true), added)

        val removed = historyLines(entry("attachment_removed", "file"), isSubtask = false).single()
        assertEquals(HistoryLine.AttachmentChanged("recce.jpg", isImage = false, added = false), removed)
    }
}
