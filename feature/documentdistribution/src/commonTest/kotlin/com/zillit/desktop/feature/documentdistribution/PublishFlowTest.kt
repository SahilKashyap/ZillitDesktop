package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.documentdistribution.data.DOC_DIST_REFRESH_BY_EVENT
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
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
import com.zillit.desktop.feature.documentdistribution.domain.PublishMode
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
 * The publish flow: choosing a destination, filling what it asks for, sending.
 *
 * The field-clearing on a destination change is the part worth holding: a
 * scene number typed for Pages is not a D.O.D name, and carrying one across
 * sends a stale value to an endpoint reading a different key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublishFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class PublishRepo(override val refreshes: Flow<DocDistRefresh>) : DocDistRepository {
        var libraryLoads = 0
        var historyLoads = 0
        val published = mutableListOf<Pair<String, PublishDraft>>()
        var live = listOf(PublishedFile(chatId = "c1", name = "Call Sheet Day 11.pdf"))
        var publishedFileReads = 0
        var publishFails = false
        override suspend fun folders(): ZillitResult<List<LibraryFolder>> {
            libraryLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun createFolder(name: String, parentId: String?) = ZillitResult.Success(Unit)
        override suspend fun renameFolder(folderId: String, name: String) = ZillitResult.Success(Unit)
        override suspend fun deleteFolder(folderId: String) = ZillitResult.Success(Unit)
        override suspend fun moveFolders(folderIds: List<String>, parentId: String?) =
            ZillitResult.Success(Unit)
        override suspend fun documents(query: LibraryQuery) =
            ZillitResult.Success(LibraryPage(emptyList(), total = 0))
        override suspend fun deleteDocument(documentId: String) = ZillitResult.Success(Unit)
        override suspend fun moveDocuments(documentIds: List<String>, folderId: String?) =
            ZillitResult.Success(Unit)
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
        override suspend fun senders(): ZillitResult<List<DistributionSender>> =
            ZillitResult.Success(emptyList())

        override suspend fun history(
            page: Int,
            search: String,
            senderIds: Set<String>,
        ): ZillitResult<List<Distribution>> {
            historyLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun distribution(id: String): ZillitResult<Distribution> =
            ZillitResult.Failure(ZillitError.Unknown("unused"))
        override suspend fun openStatus(uniqueIds: List<String>) =
            ZillitResult.Success(emptyMap<String, DeliveryStatus>())
        override suspend fun publicationCategories() =
            ZillitResult.Success(emptyList<PublicationCategory>())
        override suspend fun publishedFiles(category: String): ZillitResult<List<PublishedFile>> {
            publishedFileReads++
            return ZillitResult.Success(live)
        }
        override suspend fun publish(category: String, draft: PublishDraft): ZillitResult<Unit> {
            if (publishFails) return ZillitResult.Failure(ZillitError.Unknown("nope"))
            published += category to draft
            return ZillitResult.Success(Unit)
        }
    }


    private fun granted(identifier: String) = ToolAccess(
        identifier = identifier,
        enabled = true,
        canView = true,
        canPost = true,
        canDownload = true,
    )

    private fun vm(repo: PublishRepo, isTelevision: Boolean = false): DocDistViewModel {
        val permissions = ProjectPermissions(
            tools = listOf(
                granted(DocDistViewer.TOOL_IDENTIFIER),
                granted("schedule_distribution_tool"),
                granted("dod_tool"),
                granted("info_tool"),
            ),
        )
        return DocDistViewModel(
            repository = repo,
            viewer = {
                DocDistViewer.from(permissions, "u1", "u@x", isTelevision = isTelevision)
            },
            today = { LocalDate(2026, 8, 24) },
        ).also { it.start() }
    }

    private fun DocDistViewModel.pick(vararg ids: String) {
        ids.forEach { onEvent(DocDistEvent.ToggleDocument(it)) }
    }

    @Test
    fun `publishing needs a document chosen first`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.onEvent(DocDistEvent.OpenPublish)
        runCurrent()

        assertNull(model.state.value.publish, "nothing to publish, nothing to open")
    }

    @Test
    fun `the dialog opens on the chosen documents`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1", "d2")
        model.onEvent(DocDistEvent.OpenPublish)

        assertEquals(listOf("d1", "d2"), model.state.value.publish?.draft?.documentIds)
        assertNull(model.state.value.publish?.target, "no destination is chosen for them")
    }

    @Test
    fun `a republishable destination loads what is already there`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ChoosePublishTarget("call_sheet_unit"))
        runCurrent()

        assertEquals(1, repo.publishedFileReads)
        assertEquals(listOf("c1"), model.state.value.publish?.alreadyPublished?.map { it.chatId })
        assertTrue(model.state.value.publish?.offersMode == true)
    }

    @Test
    fun `a plain destination asks the server nothing`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ChoosePublishTarget("info"))
        runCurrent()

        assertEquals(0, repo.publishedFileReads, "nothing there to replace, so nothing to ask")
        assertFalse(model.state.value.publish?.offersMode == true)
    }

    /** A destination that republishes but has nothing live is still a first publish. */
    @Test
    fun `an empty destination offers no add or replace`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        repo.live = emptyList()
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ChoosePublishTarget("call_sheet_unit"))
        runCurrent()

        assertFalse(model.state.value.publish?.offersMode == true)
    }

    @Test
    fun `changing destination clears the fields the last one asked for`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ChoosePublishTarget("schedule_page"))
        runCurrent()
        val withScene = model.state.value.publish!!.draft.copy(sceneNumber = "12A")
        model.onEvent(DocDistEvent.EditPublishDraft(withScene))
        model.onEvent(DocDistEvent.ChoosePublishTarget("schedule_dod"))
        runCurrent()

        assertEquals("", model.state.value.publish?.draft?.sceneNumber)
        assertEquals(listOf("d1"), model.state.value.publish?.draft?.documentIds, "the files stay")
    }

    @Test
    fun `a filled publish sends its draft and clears the selection`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ChoosePublishTarget("schedule_dod"))
        runCurrent()
        val named = model.state.value.publish!!.draft.copy(name = "DOD v2")
        model.onEvent(DocDistEvent.EditPublishDraft(named))
        model.onEvent(DocDistEvent.ConfirmPublish)
        runCurrent()

        assertEquals(1, repo.published.size)
        assertEquals("schedule_dod", repo.published.single().first)
        assertEquals("DOD v2", repo.published.single().second.name)
        assertNull(model.state.value.publish)
        assertTrue(model.state.value.selectedDocumentIds.isEmpty())
        assertEquals("Published 1 file to Day Out of Days", model.state.value.notice)
    }

    @Test
    fun `a publish missing a required field is not sent`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ChoosePublishTarget("schedule_dod"))
        runCurrent()
        model.onEvent(DocDistEvent.ConfirmPublish)
        runCurrent()

        assertTrue(repo.published.isEmpty(), "the name was never given")
        assertNotNull(model.state.value.publish, "the dialog stays open to be fixed")
    }

    @Test
    fun `on television the episode is demanded before sending`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo, isTelevision = true)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ChoosePublishTarget("schedule_dod"))
        runCurrent()
        model.onEvent(
            DocDistEvent.EditPublishDraft(model.state.value.publish!!.draft.copy(name = "DOD")),
        )
        model.onEvent(DocDistEvent.ConfirmPublish)
        runCurrent()

        assertTrue(repo.published.isEmpty())
    }

    /**
     * Ticking a file to replace and then switching back to "add" must not
     * leave the targets behind — the server acts on the list, not the word.
     */
    @Test
    fun `an add-mode publish carries no replace targets`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ChoosePublishTarget("call_sheet_unit"))
        runCurrent()
        model.onEvent(DocDistEvent.ToggleReplaceTarget("c1"))
        model.onEvent(
            DocDistEvent.EditPublishDraft(
                model.state.value.publish!!.draft.copy(
                    mode = PublishMode.Add,
                    replaceChatIds = emptyList(),
                ),
            ),
        )
        model.onEvent(DocDistEvent.ConfirmPublish)
        runCurrent()

        assertTrue(repo.published.single().second.replaceChatIds.isEmpty())
    }

    @Test
    fun `a failed publish leaves the dialog open and the files chosen`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        repo.publishFails = true
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ChoosePublishTarget("info"))
        runCurrent()
        model.onEvent(DocDistEvent.ConfirmPublish)
        runCurrent()

        assertNotNull(model.state.value.publish)
        assertEquals(false, model.state.value.publish?.saving, "the button is live again")
        assertEquals(setOf("d1"), model.state.value.selectedDocumentIds)
    }

    @Test
    fun `closing keeps the documents chosen for another go`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublish)
        model.onEvent(DocDistEvent.ClosePublish)

        assertNull(model.state.value.publish)
        assertEquals(setOf("d1"), model.state.value.selectedDocumentIds)
    }

    @Test
    fun `a destination this viewer cannot post to is not offered`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        val offered = model.state.value.viewer.targets().map { it.category }

        assertTrue("info" in offered)
        assertTrue("schedule_full" in offered)
        assertFalse("production_report" in offered, "no posting right on that tool")
        assertFalse("confidential_info" in offered)
    }
    /**
     * The production opens before its tool grid arrives, so the viewer built
     * at that moment answers "rights not yet known" and offers no publish
     * destination. Nothing used to replace it — seen live 2026-08-27, a
     * permanently empty Publish dialog on a production with 42 tools on.
     */
    @Test
    fun `rights arriving after the production opens reach the publish dialog`() = runTest {
        var rights = ProjectPermissions.Empty
        val vm = DocDistViewModel(
            repository = PublishRepo(MutableSharedFlow()),
            viewer = { DocDistViewer.from(rights, "u1", "u@x") },
            today = { LocalDate(2026, 8, 24) },
        ).also { it.start() }
        runCurrent()

        assertTrue(vm.state.value.viewer.targets().isEmpty(), "resolved too early to know")

        // The tools call answers.
        rights = ProjectPermissions(tools = listOf(granted(DocDistViewer.TOOL_IDENTIFIER)))
        vm.onRightsChanged()
        runCurrent()

        assertTrue(
            vm.state.value.viewer.targets().isNotEmpty(),
            "publish dialog still offers nothing after rights arrived",
        )
    }

}
