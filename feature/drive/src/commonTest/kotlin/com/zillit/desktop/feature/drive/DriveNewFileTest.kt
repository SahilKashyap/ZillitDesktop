package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveFileText
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.EditorSession
import com.zillit.desktop.feature.drive.domain.NewFileType
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "New" in the Drive header — a blank Document, Spreadsheet, Presentation,
 * Markdown or Text file, made in the open folder and opened straight away
 * (web PR #6527 / #6530).
 *
 * The behaviour that matters: the name is asked for first and starts on the
 * type's own default, the call carries the open folder, and the file that
 * comes back opens in the right editor — the live one for the office types,
 * Drive's own for `.md` and `.txt`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveNewFileTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest fun after() = Dispatchers.resetMain()

    private class Repo : FakeDriveRepository() {
        var asked = mutableListOf<Triple<NewFileType, String, String?>>()
        var refuse = false

        override suspend fun createNewFile(
            type: NewFileType,
            name: String,
            folderId: String?,
        ): ZillitResult<DriveItem> {
            asked += Triple(type, name, folderId)
            if (refuse) return ZillitResult.Failure(ZillitError.Unknown("no"))
            val extension = type.extension
            return ZillitResult.Success(
                DriveItem(
                    id = "made-1",
                    kind = DriveItemKind.File,
                    name = "$name.$extension",
                    extension = extension,
                    createdById = "u-me",
                ),
            )
        }

        override suspend fun editorSession(fileId: String, viewOnly: Boolean): ZillitResult<EditorSession> =
            ZillitResult.Success(
                EditorSession(
                    baseUrl = "https://editors.zillit.com",
                    wopiSrc = "https://zillit.com/wopi/files/$fileId",
                    accessToken = "t",
                ),
            )

        override suspend fun fileText(fileId: String, versionId: String?): ZillitResult<DriveFileText> =
            ZillitResult.Success(DriveFileText(content = "", contentTime = "1", canEdit = true))
    }

    private fun viewModel(repository: Repo) = DriveViewModel(
        repository = repository,
        viewer = { DriveViewer(userId = "u-me", displayName = "Me", canPost = true, ready = true) },
    )

    @Test
    fun `the type's default name is already in the box`() = runTest(dispatcher) {
        val vm = viewModel(Repo())

        vm.onEvent(DriveEvent.OpenNewFile(NewFileType.Spreadsheet))
        advanceUntilIdle()

        val draft = assertNotNull(vm.state.value.newFile, "the name prompt should be up")
        assertEquals(NewFileType.Spreadsheet, draft.type)
        assertEquals("Untitled spreadsheet", draft.name, "typing replaces it; Enter is enough")
        assertEquals("xlsx", draft.type.extension)
    }

    @Test
    fun `the name typed and the open folder are what the server is asked for`() = runTest(dispatcher) {
        val repository = Repo()
        val vm = viewModel(repository)

        vm.onEvent(DriveEvent.OpenNewFile(NewFileType.Document))
        vm.onEvent(DriveEvent.NewFileName("  Day 3 call sheet  "))
        vm.onEvent(DriveEvent.SubmitNewFile)
        advanceUntilIdle()

        val (type, name, _) = repository.asked.single()
        assertEquals(NewFileType.Document, type)
        assertEquals("Day 3 call sheet", name, "trimmed, as typed")
        assertNull(vm.state.value.newFile, "the prompt closes once the file exists")
    }

    @Test
    fun `an emptied box falls back to the default rather than sending nothing`() = runTest(dispatcher) {
        val repository = Repo()
        val vm = viewModel(repository)

        vm.onEvent(DriveEvent.OpenNewFile(NewFileType.Presentation))
        vm.onEvent(DriveEvent.NewFileName("   "))
        vm.onEvent(DriveEvent.SubmitNewFile)
        advanceUntilIdle()

        assertEquals("Untitled presentation", repository.asked.single().second)
    }

    @Test
    fun `a Markdown file opens in Drive's own editor, not the live one`() = runTest(dispatcher) {
        val repository = Repo()
        val vm = viewModel(repository)

        vm.onEvent(DriveEvent.OpenNewFile(NewFileType.Markdown))
        vm.onEvent(DriveEvent.SubmitNewFile)
        advanceUntilIdle()

        val open = assertNotNull(vm.state.value.textDoc, "md opens here; the live editors refuse it")
        assertEquals("made-1", open.item.id)
        assertTrue(open.editing, "a file just made is open to be written in")
    }

    @Test
    fun `a refusal leaves the prompt up with its name, so it can be tried again`() = runTest(dispatcher) {
        val repository = Repo().apply { refuse = true }
        val vm = viewModel(repository)

        vm.onEvent(DriveEvent.OpenNewFile(NewFileType.Text))
        vm.onEvent(DriveEvent.NewFileName("notes"))
        vm.onEvent(DriveEvent.SubmitNewFile)
        advanceUntilIdle()

        val draft = assertNotNull(vm.state.value.newFile)
        assertEquals("notes", draft.name)
        assertEquals(false, draft.submitting, "and the Create button works again")
    }

    @Test
    fun `only the formats the editors keep are offered`() {
        // No .doc / .xls / .ppt: the editors save those as the new format, so
        // a file made in one would be renamed on its first save.
        assertEquals(
            listOf("docx", "xlsx", "pptx", "md", "txt"),
            NewFileType.entries.map { it.extension },
        )
        assertEquals(
            listOf(NewFileType.Markdown, NewFileType.Text),
            NewFileType.entries.filter { it.isText },
        )
    }
}
