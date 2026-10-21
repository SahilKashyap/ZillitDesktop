package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.data.decodeDriveItem
import com.zillit.desktop.feature.drive.data.decodeFileText
import com.zillit.desktop.feature.drive.data.decodeTextSave
import com.zillit.desktop.feature.drive.domain.DriveFileText
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveTextSave
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.MarkdownBlock
import com.zillit.desktop.feature.drive.domain.isChangedSinceOpened
import com.zillit.desktop.feature.drive.domain.isMarkdownDocument
import com.zillit.desktop.feature.drive.domain.isPlainTextDocument
import com.zillit.desktop.feature.drive.domain.isTextDocument
import com.zillit.desktop.feature.drive.domain.parseInlineMarkdown
import com.zillit.desktop.feature.drive.domain.parseMarkdown
import com.zillit.desktop.feature.drive.domain.textByteLength
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drive's own text and Markdown viewer — the surface `.md` and `.txt` open in,
 * because the live editors refuse them (`file_type_not_editable`).
 *
 * The behaviour that matters is the save: it carries the stamp the file was
 * opened with, so a second person's save is refused rather than overwritten,
 * and the person is offered both ways out of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveTextDocTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private val notes = DriveItem(
        id = "f1",
        kind = DriveItemKind.File,
        name = "Day 3 notes.md",
        extension = "md",
        createdById = "u-me",
    )

    private class TextRepository : FakeDriveRepository() {
        var stored = "# Day 3\n\nCall at 06:00."
        var stamp = "100"
        var canEdit = true
        var refuseWithConflict = false
        var saves = mutableListOf<Pair<String, String?>>()
        var announced = mutableListOf<Pair<String, String>>()

        override suspend fun fileText(fileId: String, versionId: String?): ZillitResult<DriveFileText> =
            ZillitResult.Success(
                DriveFileText(
                    content = if (versionId == null) stored else "old text",
                    contentTime = stamp,
                    canEdit = canEdit,
                ),
            )

        override suspend fun saveFileText(
            fileId: String,
            content: String,
            contentTime: String?,
        ): ZillitResult<DriveTextSave> {
            saves += content to contentTime
            if (refuseWithConflict && contentTime != null) {
                return ZillitResult.Failure(
                    ZillitError.Http(status = CONFLICT, serverMessage = "file_changed_since_opened"),
                )
            }
            stored = content
            stamp = "200"
            return ZillitResult.Success(DriveTextSave(contentTime = "200", saved = true))
        }

        override suspend fun announceEdit(fileId: String, note: String): ZillitResult<String> {
            announced += fileId to note
            return ZillitResult.Success("notified")
        }
    }

    private fun viewModel(repository: TextRepository) = DriveViewModel(
        repository = repository,
        viewer = { DriveViewer(userId = "u-me", displayName = "Me", ready = true) },
    )

    // -- which files these are ---------------------------------------------

    @Test
    fun `md and txt open here, office formats do not`() {
        assertTrue(notes.isMarkdownDocument)
        assertTrue(notes.isTextDocument)
        assertFalse(notes.isPlainTextDocument)

        val plain = notes.copy(name = "call.txt", extension = "txt")
        assertTrue(plain.isPlainTextDocument)
        assertTrue(plain.isTextDocument)

        val sheet = notes.copy(name = "budget.xlsx", extension = "xlsx")
        assertFalse(sheet.isTextDocument)
    }

    @Test
    fun `the extension is taken from the name when the row carries none`() {
        assertTrue(notes.copy(extension = "").isMarkdownDocument)
    }

    // -- opening, editing, saving -------------------------------------------

    @Test
    fun `opening a markdown file reads its text and the server's own can_edit`() = runTest(dispatcher) {
        val repository = TextRepository().apply { canEdit = false }
        val vm = viewModel(repository)

        vm.onEvent(DriveEvent.OpenTextDoc(notes, edit = true))
        advanceUntilIdle()

        val doc = assertNotNull(vm.state.value.textDoc)
        assertEquals("# Day 3\n\nCall at 06:00.", doc.text)
        assertFalse(doc.canEdit)
        // Asked for the editor without the right to save: it opens read-only
        // rather than offering a Save that would be refused.
        assertFalse(doc.isEditing)
    }

    @Test
    fun `a save sends the stamp the file was opened with`() = runTest(dispatcher) {
        val repository = TextRepository()
        val vm = viewModel(repository)

        vm.onEvent(DriveEvent.OpenTextDoc(notes, edit = true))
        advanceUntilIdle()
        vm.onEvent(DriveEvent.TextDocDraft("# Day 3\n\nCall at 07:00."))
        vm.onEvent(DriveEvent.SaveTextDoc())
        advanceUntilIdle()

        val expected: List<Pair<String, String?>> = listOf("# Day 3\n\nCall at 07:00." to "100")
        assertEquals(expected, repository.saves.toList())
        val doc = assertNotNull(vm.state.value.textDoc)
        assertFalse(doc.dirty, "saved text is no longer dirty")
        assertEquals("200", doc.contentTime, "the new stamp is kept for the next save")
    }

    @Test
    fun `somebody else's save is refused, and overwriting drops the stamp`() = runTest(dispatcher) {
        val repository = TextRepository().apply { refuseWithConflict = true }
        val vm = viewModel(repository)

        vm.onEvent(DriveEvent.OpenTextDoc(notes, edit = true))
        advanceUntilIdle()
        vm.onEvent(DriveEvent.TextDocDraft("mine"))
        vm.onEvent(DriveEvent.SaveTextDoc())
        advanceUntilIdle()

        assertNotNull(vm.state.value.textDoc?.conflict, "the server's refusal is on screen")
        assertTrue(vm.state.value.textDoc?.dirty == true, "the person's text is still theirs")

        vm.onEvent(DriveEvent.SaveTextDoc(overwrite = true))
        advanceUntilIdle()

        val overwritten: Pair<String, String?> = "mine" to null
        assertEquals(overwritten, repository.saves.last(), "no stamp means take the conflict")
        assertNull(vm.state.value.textDoc?.conflict)
    }

    @Test
    fun `closing with unsaved changes asks once`() = runTest(dispatcher) {
        val vm = viewModel(TextRepository())
        vm.onEvent(DriveEvent.OpenTextDoc(notes, edit = true))
        advanceUntilIdle()
        vm.onEvent(DriveEvent.TextDocDraft("changed"))

        vm.onEvent(DriveEvent.CloseTextDoc)
        assertTrue(vm.state.value.textDoc?.confirmDiscard == true, "still open, asking")

        vm.onEvent(DriveEvent.CloseTextDoc)
        assertNull(vm.state.value.textDoc, "the second press discards")
        assertNull(vm.state.value.notifyEdit, "nothing was saved, so nobody is told")
    }

    @Test
    fun `a file this person saved asks whether to tell the others, and sends the note`() = runTest(dispatcher) {
        val repository = TextRepository()
        val vm = viewModel(repository)

        vm.onEvent(DriveEvent.OpenTextDoc(notes, edit = true))
        advanceUntilIdle()
        vm.onEvent(DriveEvent.TextDocDraft("# Day 3 — moved to 07:00"))
        vm.onEvent(DriveEvent.SaveTextDoc())
        advanceUntilIdle()
        vm.onEvent(DriveEvent.CloseTextDoc)

        val prompt = assertNotNull(vm.state.value.notifyEdit)
        assertEquals("f1", prompt.item.id)

        vm.onEvent(DriveEvent.NotifyEditNote(" call moved "))
        vm.onEvent(DriveEvent.SendNotifyEdit)
        advanceUntilIdle()

        assertEquals(listOf("f1" to "call moved"), repository.announced)
        assertNull(vm.state.value.notifyEdit, "it closes once the server has it")
    }

    @Test
    fun `a file opened and only read tells nobody`() = runTest(dispatcher) {
        val vm = viewModel(TextRepository())
        vm.onEvent(DriveEvent.OpenTextDoc(notes, edit = false))
        advanceUntilIdle()
        vm.onEvent(DriveEvent.CloseTextDoc)

        assertNull(vm.state.value.notifyEdit)
    }

    @Test
    fun `a text file previews in its own viewer, never in the preview dialog`() = runTest(dispatcher) {
        val vm = viewModel(TextRepository())
        vm.onEvent(DriveEvent.OpenItem(notes))
        advanceUntilIdle()

        assertNotNull(vm.state.value.textDoc)
        assertNull(vm.state.value.preview)
    }

    @Test
    fun `an over-long file is refused before the round trip`() = runTest(dispatcher) {
        val repository = TextRepository()
        val vm = viewModel(repository)
        vm.onEvent(DriveEvent.OpenTextDoc(notes, edit = true))
        advanceUntilIdle()

        vm.onEvent(DriveEvent.TextDocDraft("x".repeat(textByteLength("x") * OVER_LIMIT_CHARS)))
        vm.onEvent(DriveEvent.SaveTextDoc())
        advanceUntilIdle()

        assertTrue(repository.saves.isEmpty(), "nothing was sent")
    }

    // -- what the wire carries ---------------------------------------------

    @Test
    fun `the text route's body is read field by field`() {
        val text = decodeFileText("""{"content":"# Hi","content_time":"2026-10-19T08:00:00.000Z","can_edit":true}""")
        assertEquals("# Hi", text.content)
        assertEquals("2026-10-19T08:00:00.000Z", text.contentTime)
        assertTrue(text.canEdit)

        // The same field as a number on another deployment: handed back as it
        // came, never parsed.
        assertEquals("1754000000000", decodeFileText("""{"content":"x","content_time":1754000000000}""").contentTime)
    }

    @Test
    fun `a save answers the new stamp and whether a version was written`() {
        assertEquals(DriveTextSave("300", saved = true), decodeTextSave("""{"content_time":"300","saved":true}"""))
        assertFalse(decodeTextSave("""{"content_time":"300","saved":false}""").saved)
        // No `saved` field at all: the save happened, which is what a 200 on
        // this route has always meant.
        assertTrue(decodeTextSave("""{"content_time":"300"}""").saved)
    }

    @Test
    fun `the conflict is recognised by its key, wherever the layer put it`() {
        assertTrue(ZillitError.Http(CONFLICT, "file_changed_since_opened").isChangedSinceOpened())
        assertTrue(ZillitError.Forbidden(technical = "file_changed_since_opened").isChangedSinceOpened())
        assertFalse(ZillitError.Http(CONFLICT, "duplicate_file_name").isChangedSinceOpened())
    }

    @Test
    fun `a size sent as size or file_size is still a size`() {
        assertEquals(42L, decodeDriveItem("""{"_id":"f","name":"a.pdf","size":42}""")?.sizeBytes)
        assertEquals(42L, decodeDriveItem("""{"_id":"f","name":"a.pdf","file_size":42}""")?.sizeBytes)
        // A number sent as a string, which some routes do.
        assertEquals(42L, decodeDriveItem("""{"_id":"f","name":"a.pdf","file_size_bytes":"42"}""")?.sizeBytes)
        assertEquals(0L, decodeDriveItem("""{"_id":"f","name":"a.pdf"}""")?.sizeBytes)
    }

    /**
     * Seen live on dev, 2026-10-21: `/files` answers `file_size` as the text
     * a person reads — `"297.82 KB"` — beside the real byte count. Declared
     * as a number it threw `Failed to parse literal` and, because a listing
     * decodes as one object, **one such row emptied the whole Drive** with
     * "The server sent something unexpected".
     */
    @Test
    fun `a formatted size is ignored, and never takes the listing down with it`() {
        val row = decodeDriveItem(
            """{"_id":"f","name":"a.pdf","file_size_bytes":305000,"file_size":"297.82 KB"}""",
        )
        assertEquals(305_000L, row?.sizeBytes, "the byte count is still read")

        // And with nothing else to go on, the text is simply not a size.
        val textOnly = decodeDriveItem("""{"_id":"f","name":"a.pdf","file_size":"297.82 KB"}""")
        assertEquals(0L, textOnly?.sizeBytes)
        assertEquals("a.pdf", textOnly?.name, "the row itself still decodes")
    }

    // -- the Markdown it renders -------------------------------------------

    @Test
    fun `headings, lists, quotes, rules and code become blocks`() {
        val blocks = parseMarkdown(
            """
            # Day 3

            Call at 06:00, **sharp**.

            - Camera
            - Grip

            1. Load
            2. Travel

            > Weather hold until 07:00

            ---

            ```sh
            make dailies
            ```
            """.trimIndent(),
        )

        assertEquals(MarkdownBlock.Heading(1, parseInlineMarkdown("Day 3")), blocks.first())
        assertTrue(blocks.any { it is MarkdownBlock.ListItem && it.marker == "•" })
        assertTrue(blocks.any { it is MarkdownBlock.ListItem && it.marker == "1." })
        assertTrue(blocks.any { it is MarkdownBlock.Quote })
        assertTrue(blocks.any { it == MarkdownBlock.Rule })
        val code = assertNotNull(blocks.filterIsInstance<MarkdownBlock.Code>().firstOrNull())
        assertEquals("make dailies", code.text)
        assertEquals("sh", code.language)
    }

    @Test
    fun `a paragraph's lines join, and its emphasis, code and links are spans`() {
        val blocks = parseMarkdown("The call is **06:00**\nnot `07:00` — see [the sheet](https://z.test/cs).")
        val spans = assertNotNull(blocks.filterIsInstance<MarkdownBlock.Paragraph>().firstOrNull()).spans

        assertTrue(spans.any { it.text == "06:00" && it.bold })
        assertTrue(spans.any { it.text == "07:00" && it.code })
        assertTrue(spans.any { it.text == "the sheet" && it.href == "https://z.test/cs" })
        assertTrue(spans.none { "\n" in it.text }, "the two lines are one paragraph")
    }

    @Test
    fun `an unmatched marker stays the character it is`() {
        assertEquals("2 * 3 = 6", parseInlineMarkdown("2 \\* 3 = 6").joinToString("") { it.text })
        assertEquals("a `b", parseInlineMarkdown("a `b").joinToString("") { it.text })
    }

    @Test
    fun `code wins over emphasis inside it`() {
        val spans = parseInlineMarkdown("run `make **all**` now")
        assertTrue(spans.any { it.code && it.text == "make **all**" })
        assertTrue(spans.none { it.bold })
    }

    private companion object {
        const val CONFLICT = 409
        /** Enough characters to pass the 2 MB ceiling. */
        const val OVER_LIMIT_CHARS = 2 * 1024 * 1024 + 1
    }
}
