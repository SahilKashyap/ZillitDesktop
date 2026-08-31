package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.documentdistribution.data.DOC_DIST_REFRESH_BY_EVENT
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRefresh
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.domain.NewDistribution
import com.zillit.desktop.feature.documentdistribution.domain.PublicationCategory
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/**
 * Moving items in the library.
 *
 * Folders go before documents on purpose: reparenting a folder rewrites the
 * tree the documents are being placed into, and the other order can land a
 * document in a folder that is about to move out from under it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveItemsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class MoveRepo(override val refreshes: Flow<DocDistRefresh>) : DocDistRepository {
        var libraryLoads = 0
        var historyLoads = 0
        val calls = mutableListOf<String>()
        var folderTree = listOf(
            LibraryFolder(id = "calls", name = "Call sheets"),
            LibraryFolder(id = "w1", name = "Week 1", parentId = "calls"),
            LibraryFolder(id = "scripts", name = "Scripts"),
        )
        var folderMoveFails = false
        override suspend fun folders(): ZillitResult<List<LibraryFolder>> {
            libraryLoads++
            return ZillitResult.Success(folderTree)
        }
        override suspend fun createFolder(name: String, parentId: String?) = ZillitResult.Success(Unit)
        override suspend fun renameFolder(folderId: String, name: String) = ZillitResult.Success(Unit)
        override suspend fun deleteFolder(folderId: String) = ZillitResult.Success(Unit)
        override suspend fun moveFolders(
            folderIds: List<String>,
            parentId: String?,
        ): ZillitResult<Unit> {
            if (folderMoveFails) return ZillitResult.Failure(ZillitError.Unknown("nope"))
            calls += "folders:${folderIds.joinToString(",")}->${parentId ?: "root"}"
            return ZillitResult.Success(Unit)
        }
        override suspend fun documents(query: LibraryQuery) =
            ZillitResult.Success(LibraryPage(emptyList(), total = 0))
        override suspend fun deleteDocument(documentId: String) = ZillitResult.Success(Unit)
        override suspend fun moveDocuments(
            documentIds: List<String>,
            folderId: String?,
        ): ZillitResult<Unit> {
            calls += "docs:${documentIds.joinToString(",")}->${folderId ?: "root"}"
            return ZillitResult.Success(Unit)
        }
        override suspend fun documentUrl(document: LibraryDocument) = ZillitResult.Success("url")
        override suspend fun lists() = ZillitResult.Success(emptyList<DistributionList>())
        override suspend fun createList(name: String, recipients: List<Recipient>) =
            ZillitResult.Success(Unit)
        override suspend fun updateList(listId: String, name: String, recipients: List<Recipient>) =
            ZillitResult.Success(Unit)
        override suspend fun deleteList(listId: String) = ZillitResult.Success(Unit)
        override suspend fun contacts() = ZillitResult.Success(emptyList<Contact>())
        override suspend fun saveContact(contact: Contact) = ZillitResult.Success(Unit)
        override suspend fun deleteContact(email: String) = ZillitResult.Success(Unit)
        override suspend fun templates() = ZillitResult.Success(emptyList<EmailTemplate>())
        override suspend fun saveTemplate(template: EmailTemplate) = ZillitResult.Success(Unit)
        override suspend fun deleteTemplate(templateId: String) = ZillitResult.Success(Unit)
        override suspend fun send(distribution: NewDistribution) = ZillitResult.Success(Unit)
        override suspend fun history(page: Int, search: String): ZillitResult<List<Distribution>> {
            historyLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun distribution(id: String): ZillitResult<Distribution> =
            ZillitResult.Failure(ZillitError.Unknown("unused"))
        override suspend fun openStatus(uniqueIds: List<String>) =
            ZillitResult.Success(emptyMap<String, DeliveryStatus>())
        override suspend fun publicationCategories() =
            ZillitResult.Success(emptyList<PublicationCategory>())
        override suspend fun publishedFiles(category: String) =
            ZillitResult.Success(emptyList<PublishedFile>())
        override suspend fun publish(category: String, draft: PublishDraft) =
            ZillitResult.Success(Unit)
    }


    private fun model(repo: MoveRepo) = DocDistViewModel(
        repository = repo,
        viewer = { DocDistViewer(userId = "u1", ready = true, canPost = true) },
        today = { LocalDate(2026, 8, 24) },
    )

    private fun started(repo: MoveRepo): DocDistViewModel =
        model(repo).also { it.start() }

    @Test
    fun `folders move before documents`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleFolder("calls"))
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.ChooseMoveDestination("scripts"))
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertEquals(listOf("folders:calls->scripts", "docs:d1->scripts"), repo.calls)
    }

    @Test
    fun `a folders-only move sends no document call`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleFolder("calls"))
        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.ChooseMoveDestination("scripts"))
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertEquals(listOf("folders:calls->scripts"), repo.calls)
    }

    @Test
    fun `the library root is a destination for folders`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleFolder("w1"))
        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.ChooseMoveDestination(null))
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertEquals(listOf("folders:w1->root"), repo.calls)
    }

    @Test
    fun `a successful move clears the selection and closes the dialog`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleFolder("calls"))
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.ChooseMoveDestination("scripts"))
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertTrue(vm.state.value.selectedFolderIds.isEmpty())
        assertTrue(vm.state.value.selectedDocumentIds.isEmpty())
        assertNull(vm.state.value.moveTarget)
        assertEquals("Moved 2 items", vm.state.value.notice)
    }

    @Test
    fun `one item is moved, not one items`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.OpenMove)
        // A folder, because a file may not be filed at the root.
        vm.onEvent(DocDistEvent.ChooseMoveDestination("scripts"))
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertEquals("Moved 1 item", vm.state.value.notice)
    }

    /**
     * A failed folder move stops before the documents: half a move is worse
     * than none, because the half that landed is invisible next to the half
     * that did not.
     */
    @Test
    fun `a refused folder move does not go on to the documents`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        repo.folderMoveFails = true
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleFolder("calls"))
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertTrue(repo.calls.isEmpty(), "the documents were left where they were")
        assertEquals(setOf("calls"), vm.state.value.selectedFolderIds, "the selection survives")
    }

    @Test
    fun `a failed move leaves the dialog open and usable`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        repo.folderMoveFails = true
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleFolder("calls"))
        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertEquals(false, vm.state.value.moveTarget?.saving, "the button is live again")
    }

    @Test
    fun `moving nothing sends nothing`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertTrue(repo.calls.isEmpty())
    }

    /** The dialog opens on the folder being browsed, so "here" means here. */
    @Test
    fun `the dialog opens on the folder in view`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.OpenFolder("calls"))
        runCurrent()
        vm.onEvent(DocDistEvent.OpenMove)

        assertEquals("calls", vm.state.value.moveTarget?.destinationId)
    }

    @Test
    fun `closing the dialog keeps the selection for another try`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleFolder("calls"))
        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.CloseMove)

        assertNull(vm.state.value.moveTarget)
        assertEquals(setOf("calls"), vm.state.value.selectedFolderIds)
    }

    @Test
    fun `ticking a folder twice unticks it`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleFolder("calls"))
        vm.onEvent(DocDistEvent.ToggleFolder("calls"))

        assertTrue(vm.state.value.selectedFolderIds.isEmpty())
    }
    /**
     * A file must live inside a folder.
     *
     * Both phones enforce it — web refuses with "Files must be moved into a
     * folder, not the root", Android dims the Root row (`rootForbidden`) and
     * explains why up front. The desktop offered Library root as a destination
     * *and preselected it*, so confirming a file move with nothing picked
     * filed the files at the root in two clicks.
     */
    @Test
    fun `files may not be moved to the library root`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.ChooseMoveDestination(null))
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertTrue(vm.state.value.rootForbidden, "a file selection bars the root")
        assertEquals(emptyList(), repo.calls, "the files were filed at the root anyway")
    }

    /** A mixed selection is barred too — the files in it still need a folder. */
    @Test
    fun `a folder and a file together may not go to the root`() = runTest(dispatcher) {
        val repo = MoveRepo(MutableSharedFlow())
        val vm = started(repo)
        runCurrent()

        vm.onEvent(DocDistEvent.ToggleFolder("calls"))
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.OpenMove)
        vm.onEvent(DocDistEvent.ChooseMoveDestination(null))
        vm.onEvent(DocDistEvent.ConfirmMove)
        runCurrent()

        assertEquals(emptyList(), repo.calls)
    }
}
