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

    private class PublishRepo(refreshes: Flow<DocDistRefresh>) : FakeDocDistRepository(refreshes) {
        val published = mutableListOf<Pair<String, PublishDraft>>()
        var live = listOf(PublishedFile(chatId = "c1", name = "Call Sheet Day 11.pdf"))
        var publishedFileReads = 0
        var publishFails = false
        // The library holds three documents, so a ticked id is a real row.
        override suspend fun documents(query: LibraryQuery): ZillitResult<LibraryPage> = ZillitResult.Success(
            LibraryPage(
                listOf("d1", "d2", "d3").map { LibraryDocument(
                    id = it,
                    name = "$it.pdf",
                    contentType = "application/pdf",
                ) },
                total = 3,
            ),
        )
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

        model.onEvent(DocDistEvent.OpenPublishSelection)
        runCurrent()

        assertNull(model.state.value.publish, "nothing to publish, nothing to open")
    }

    @Test
    fun `the dialog opens on the chosen documents`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1", "d2")
        model.onEvent(DocDistEvent.OpenPublishSelection)

        assertEquals(listOf("d1", "d2"), model.state.value.publish?.draft?.documentIds)
        assertNull(model.state.value.publish?.target, "no destination is chosen for them")
    }

    @Test
    fun `a republishable destination loads what is already there`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublishSelection)
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
        model.onEvent(DocDistEvent.OpenPublishSelection)
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
        model.onEvent(DocDistEvent.OpenPublishSelection)
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
        model.onEvent(DocDistEvent.OpenPublishSelection)
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
        model.onEvent(DocDistEvent.OpenPublishSelection)
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
        model.onEvent(DocDistEvent.OpenPublishSelection)
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
        model.onEvent(DocDistEvent.OpenPublishSelection)
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
     * Picking a file to replace and then switching back to Addition must not
     * leave the target behind — the server acts on the id, not the word.
     */
    @Test
    fun `an add-mode publish carries no replace target`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublishSelection)
        model.onEvent(DocDistEvent.ChoosePublishTarget("call_sheet_unit"))
        runCurrent()
        model.onEvent(DocDistEvent.SetPublishMode(PublishMode.Replace))
        model.onEvent(DocDistEvent.ChooseReplaceTarget("c1"))
        model.onEvent(DocDistEvent.SetPublishMode(PublishMode.Add))
        model.onEvent(DocDistEvent.ConfirmPublish)
        runCurrent()

        assertNull(repo.published.single().second.replaceChatId)
    }

    /**
     * Ticking a second file under Replace is not an error to report at the
     * end: the publish falls back to Addition and says so, which is what the
     * web does rather than letting `publication_replace_single_only` answer.
     */
    @Test
    fun `a second ticked file drops a replace back to addition`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.pick("d2")
        model.onEvent(DocDistEvent.OpenPublishSelection)
        model.onEvent(DocDistEvent.ChoosePublishTarget("call_sheet_unit"))
        runCurrent()
        // Down to one, so Replace is takeable, then back up to two.
        model.onEvent(DocDistEvent.TogglePublishFile("d2"))
        model.onEvent(DocDistEvent.SetPublishMode(PublishMode.Replace))
        model.onEvent(DocDistEvent.ChooseReplaceTarget("c1"))
        model.onEvent(DocDistEvent.TogglePublishFile("d2"))

        val draft = model.state.value.publish!!.draft
        assertEquals(PublishMode.Add, draft.mode)
        assertNull(draft.replaceChatId)
    }

    /**
     * A destination with nothing live sends no `mode` at all; one with
     * something live starts on Addition, as the web's radio group does.
     */
    @Test
    fun `the mode is null until the destination has something live`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        repo.live = emptyList()
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublishSelection)
        model.onEvent(DocDistEvent.ChoosePublishTarget("call_sheet_unit"))
        runCurrent()
        assertNull(model.state.value.publish!!.draft.mode)

        repo.live = listOf(PublishedFile(chatId = "c1", name = "Call sheet"))
        model.onEvent(DocDistEvent.ChoosePublishTarget("production_report"))
        runCurrent()
        assertEquals(PublishMode.Add, model.state.value.publish!!.draft.mode)
    }

    /**
     * Switching to a PDF-only destination drops the ticks it cannot take,
     * rather than carrying a selection into a refusal.
     */
    @Test
    fun `a change of destination re-derives the ticks`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.pick("d2")
        model.onEvent(DocDistEvent.OpenPublishSelection)
        model.onEvent(DocDistEvent.ChoosePublishTarget("info"))
        runCurrent()
        assertEquals(2, model.state.value.publish!!.draft.documentIds.size)

        // Single-file, so only the first survives.
        model.onEvent(DocDistEvent.ChoosePublishTarget("script_distribution"))
        runCurrent()
        assertEquals(listOf("d1"), model.state.value.publish!!.draft.documentIds)
    }

    @Test
    fun `a failed publish leaves the dialog open and the files chosen`() = runTest(dispatcher) {
        val repo = PublishRepo(MutableSharedFlow())
        repo.publishFails = true
        val model = vm(repo)
        runCurrent()

        model.pick("d1")
        model.onEvent(DocDistEvent.OpenPublishSelection)
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
        model.onEvent(DocDistEvent.OpenPublishSelection)
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
