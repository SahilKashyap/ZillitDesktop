package com.zillit.desktop.feature.continuity

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityRepository
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityTransfer
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.continuity.ui.ContinuityEvent
import com.zillit.desktop.feature.continuity.ui.ContinuityViewModel
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

/** Folders, cards, the All-board department pick, uploads and validation on fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinuityViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo : ContinuityRepository {
        val cards = mutableListOf<ContinuityScene>()
        val created = mutableListOf<Pair<SceneDraft, ContinuityAttachment>>()
        val shared = mutableListOf<List<String>>()
        val archived = mutableListOf<List<String>>()
        var pages = 0
        override suspend fun folders(tab: ContinuityTab) = ZillitResult.Success(
            cards.filter { it.shownOn(tab) }.map { it.sceneNumber }.distinct(),
        )
        override suspend fun scenes(tab: ContinuityTab, sceneFolder: String, departmentId: String?, beforeMs: Long):
            ZillitResult<List<ContinuityScene>> {
            pages++
            return ZillitResult.Success(
                cards.filter { it.sceneNumber == sceneFolder && it.cursorMs < beforeMs && it.shownOn(tab) }
                    .filter { departmentId == null || it.departmentId == departmentId },
            )
        }
        override suspend fun departments(sceneFolder: String) = ZillitResult.Success(
            cards.filter { it.sceneNumber == sceneFolder }.map { ContinuityDepartment(it.departmentId, "") }.distinct(),
        )
        override suspend fun create(draft: SceneDraft, attachment: ContinuityAttachment, uniqueId: String):
            ZillitResult<Unit> {
            created += draft to attachment
            cards += scene("new${created.size}", draft.sceneNumber.trimStart('0'))
            return ZillitResult.Success(Unit)
        }
        override suspend fun update(id: String, draft: SceneDraft) = ZillitResult.Success(null)
        override suspend fun archive(sceneIds: List<String>): ZillitResult<Unit> {
            archived += sceneIds
            return ZillitResult.Success(Unit)
        }

        override suspend fun share(ids: List<String>, sceneFolder: String): ZillitResult<Unit> {
            shared += ids
            return ZillitResult.Success(Unit)
        }
        override suspend fun delete(tab: ContinuityTab, id: String): ZillitResult<Unit> {
            cards.removeAll { it.id == id }
            return ZillitResult.Success(Unit)
        }
    }

    private class FakeTransfer : ContinuityTransfer {
        var failUpload = false
        override suspend fun upload(file: PickedContinuityFile): ZillitResult<ContinuityAttachment> =
            if (failUpload) {
                ZillitResult.Failure(ZillitError.Unknown("s3 down"))
            } else {
                ZillitResult.Success(
                    ContinuityAttachment("k/${file.name}", "k/${file.name}", "image", "jpg", file.name, "b", "r"),
                )
            }
        override suspend fun fetch(attachment: ContinuityAttachment, preview: Boolean) =
            ZillitResult.Success(ByteArray(0))
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
    }

    private fun viewModel(repo: FakeRepo, transfer: FakeTransfer = FakeTransfer(), tv: Boolean = false) =
        ContinuityViewModel(
            repository = repo,
            transfer = transfer,
            resolveViewer = {
                ContinuityViewer(userId = "u1", departmentId = "d1", isTelevision = tv, canPost = true, ready = true)
            },
            departmentName = { null },
            newUniqueId = { "uid" },
            nowMillis = { 1_000L },
        )

    @Test
    fun `my department lists folders, opens cards and pages older ones`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            cards += scene("a", "12", created = 500)
            cards += scene("b", "12", created = 300)
            cards += scene("c", "7", dept = "d2")
        }
        val vm = viewModel(repo)
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("7", "12"), vm.state.value.shownFolders)

        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        val open = assertNotNull(vm.state.value.open)
        assertEquals(listOf("a", "b"), open.shown.map { it.id })

        vm.onEvent(ContinuityEvent.LoadMore)
        advanceUntilIdle()
        assertTrue(assertNotNull(vm.state.value.open).exhausted)
    }

    @Test
    fun `all departments opens a department pick before the cards`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            cards += scene("a", "12", dept = "d2", all = true)
            cards += scene("b", "12", dept = "d3", all = true)
        }
        val vm = viewModel(repo)
        vm.start()
        vm.onEvent(ContinuityEvent.SelectTab(ContinuityTab.AllDepartments))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        val pick = assertNotNull(vm.state.value.pick)
        assertEquals(listOf("d2", "d3"), pick.departments.map { it.id })

        vm.onEvent(ContinuityEvent.OpenDepartment(pick.departments.first()))
        advanceUntilIdle()
        assertNull(vm.state.value.pick)
        assertEquals(listOf("a"), assertNotNull(vm.state.value.open).shown.map { it.id })
    }

    @Test
    fun `upload validates the scene number then creates one record per file`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(
            ContinuityEvent.FilesPicked(
                listOf(
                    PickedContinuityFile("a.jpg", "image/jpeg", ByteArray(1)),
                    PickedContinuityFile("b.jpg", "image/jpeg", ByteArray(1)),
                ),
            ),
        )
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertEquals("A scene number is required", vm.state.value.error)

        vm.onEvent(ContinuityEvent.DraftChanged(SceneDraft(sceneNumber = "A1")))
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertEquals("The scene number must start with a digit", vm.state.value.error)

        vm.onEvent(ContinuityEvent.DraftChanged(SceneDraft(sceneNumber = "012", notes = "n")))
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertEquals(2, repo.created.size)
        assertNull(vm.state.value.editor)
        assertEquals(listOf("12"), vm.state.value.shownFolders)
    }

    @Test
    fun `a failed upload keeps the editor open with the error`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val transfer = FakeTransfer().apply { failUpload = true }
        val vm = viewModel(repo, transfer)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.FilesPicked(listOf(PickedContinuityFile("a.jpg", "image/jpeg", ByteArray(1)))))
        vm.onEvent(ContinuityEvent.DraftChanged(SceneDraft(sceneNumber = "3")))
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertNotNull(vm.state.value.editor)
        assertEquals(0, repo.created.size)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `television productions need an episode on new cards`() = runTest(dispatcher) {
        val vm = viewModel(FakeRepo(), tv = true)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.FilesPicked(listOf(PickedContinuityFile("a.jpg", "image/jpeg", ByteArray(1)))))
        vm.onEvent(ContinuityEvent.DraftChanged(SceneDraft(sceneNumber = "3")))
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertEquals("An episode number is required", vm.state.value.error)
    }

    @Test
    fun `forwarding shares the selection and leaves selection mode`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12"); cards += scene("b", "12") }
        val vm = viewModel(repo)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.ToggleSelecting)
        vm.onEvent(ContinuityEvent.ToggleSelect("a"))
        vm.onEvent(ContinuityEvent.ForwardSelected)
        assertTrue(vm.state.value.confirmForward)
        vm.onEvent(ContinuityEvent.ConfirmForward)
        advanceUntilIdle()
        assertEquals(listOf(listOf("a")), repo.shared)
        assertEquals(false, assertNotNull(vm.state.value.open).selecting)
    }

    /**
     * The file cabinet: off the board, not deleted.
     *
     * Unlike forwarding there is no confirmation — nothing is destroyed and
     * the scenes can be read back from the cabinet.
     */
    @Test
    fun `archiving files the selection away and leaves selection mode`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12"); cards += scene("b", "12") }
        val vm = viewModel(repo)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.ToggleSelecting)
        vm.onEvent(ContinuityEvent.ToggleSelect("a"))
        vm.onEvent(ContinuityEvent.ArchiveSelected)
        advanceUntilIdle()

        assertEquals(listOf(listOf("a")), repo.archived)
        assertEquals(false, assertNotNull(vm.state.value.open).selecting)
    }

    /** An empty selection asks the service for nothing. */
    @Test
    fun `archiving nothing calls nothing`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12") }
        val vm = viewModel(repo)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.ToggleSelecting)
        vm.onEvent(ContinuityEvent.ArchiveSelected)
        advanceUntilIdle()

        assertTrue(repo.archived.isEmpty())
    }
}

private fun scene(id: String, folder: String, dept: String = "d1", all: Boolean = false, created: Long = 100) =
    ContinuityScene(
        id = id, uniqueId = id, sceneNumber = folder, episode = "", notes = "n$id", actorName = "",
        talentInfo = emptyList(), attachment = null, departmentId = dept, uploadedBy = "u1",
        visibleIntra = true, visibleAll = all, deletedIntra = false, deletedAll = false,
        createdMs = created, updatedMs = 0,
    )
