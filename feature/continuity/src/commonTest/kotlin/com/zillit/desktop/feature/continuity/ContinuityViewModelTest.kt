package com.zillit.desktop.feature.continuity

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityBadges
import com.zillit.desktop.feature.continuity.domain.ContinuityCrewMember
import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityForwarder
import com.zillit.desktop.feature.continuity.domain.ContinuityRepository
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityTransfer
import com.zillit.desktop.feature.continuity.domain.ContinuityUnread
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.continuity.domain.TalentInfo
import com.zillit.desktop.feature.continuity.ui.ContinuityEffect
import com.zillit.desktop.feature.continuity.ui.ContinuityEvent
import com.zillit.desktop.feature.continuity.ui.ContinuityViewModel
import com.zillit.desktop.feature.continuity.ui.ForwardSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

/** Folders, cards, the All-board department pick, uploads, validation, the two forwards and the reads — on fakes. */
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
        val updated = mutableListOf<Pair<String, SceneDraft>>()
        val shared = mutableListOf<List<String>>()
        val deleted = mutableListOf<Pair<ContinuityTab, String>>()
        var refuseDelete = false
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
        override suspend fun update(id: String, draft: SceneDraft): ZillitResult<ContinuityScene?> {
            updated += id to draft
            return ZillitResult.Success(null)
        }
        override suspend fun share(ids: List<String>, sceneFolder: String): ZillitResult<Unit> {
            shared += ids
            return ZillitResult.Success(Unit)
        }
        override suspend fun delete(tab: ContinuityTab, id: String): ZillitResult<Unit> {
            if (refuseDelete) {
                return ZillitResult.Failure(
                    ZillitError.Http(status = 200, serverMessage = "continuity_action_not_allowed"),
                )
            }
            deleted += tab to id
            cards.removeAll { it.id == id }
            return ZillitResult.Success(Unit)
        }
    }

    private class FakeTransfer : ContinuityTransfer {
        var failUpload = false
        val opened = mutableListOf<String>()
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
        override suspend fun open(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
            opened += fileName
            return ZillitResult.Success(Unit)
        }
    }

    private class FakeBadges : ContinuityBadges {
        val counts = MutableStateFlow(ContinuityUnread.Empty)
        val reads = mutableListOf<Triple<ContinuityTab, String, String?>>()
        override val unread: Flow<ContinuityUnread> get() = counts
        override fun markRead(tab: ContinuityTab, sceneFolder: String, departmentId: String?) {
            reads += Triple(tab, sceneFolder, departmentId)
        }
    }

    private class FakeForwarder : ContinuityForwarder {
        val sent = mutableListOf<Triple<String, String, String>>()
        var failFor: String? = null
        override suspend fun forward(scene: ContinuityScene, toUserId: String, caption: String): ZillitResult<Unit> {
            if (toUserId == failFor) return ZillitResult.Failure(ZillitError.Unknown("offline"))
            sent += Triple(scene.id, toUserId, caption)
            return ZillitResult.Success(Unit)
        }
    }

    private fun viewModel(
        repo: FakeRepo,
        transfer: FakeTransfer = FakeTransfer(),
        tv: Boolean = false,
        canPost: Boolean = true,
        badges: FakeBadges = FakeBadges(),
        forwarder: FakeForwarder = FakeForwarder(),
    ) = ContinuityViewModel(
        repository = repo,
        transfer = transfer,
        resolveViewer = {
            ContinuityViewer(userId = "u1", departmentId = "d1", isTelevision = tv, canPost = canPost, ready = true)
        },
        departmentName = { null },
        newUniqueId = { "uid" },
        nowMillis = { 1_000L },
        crew = {
            listOf(
                ContinuityCrewMember("u1", "Me"),
                ContinuityCrewMember("u2", "Aisha Khan", "Gaffer"),
                ContinuityCrewMember("u3", "Ben Ortiz", "Grip"),
            )
        },
        badges = badges,
        forwarder = forwarder,
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

    /** Opening a folder is a read — the web's `notification:read` with the scene as `level_1`. */
    @Test
    fun `opening a folder marks its badge read and the ledger's counts reach the state`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12") }
        val badges = FakeBadges()
        val vm = viewModel(repo, badges = badges)
        vm.start()
        badges.counts.value = ContinuityUnread(
            tabs = mapOf("continuity_intra_label" to 3),
            folders = mapOf("continuity_intra_label" to mapOf("12" to 3)),
        )
        advanceUntilIdle()
        assertEquals(3, vm.state.value.unread.tab(ContinuityTab.MyDepartment))
        assertEquals(3, vm.state.value.unread.folder(ContinuityTab.MyDepartment, "12"))

        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        assertEquals(listOf(read(ContinuityTab.MyDepartment, "12", null)), badges.reads)
    }

    @Test
    fun `all departments opens a department pick before the cards and reads the department`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            cards += scene("a", "12", dept = "d2", all = true)
            cards += scene("b", "12", dept = "d3", all = true)
        }
        val badges = FakeBadges()
        val vm = viewModel(repo, badges = badges)
        vm.start()
        vm.onEvent(ContinuityEvent.SelectTab(ContinuityTab.AllDepartments))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        val pick = assertNotNull(vm.state.value.pick)
        assertEquals(listOf("d2", "d3"), pick.departments.map { it.id })
        assertTrue(badges.reads.isEmpty(), "the pick itself is not a read")

        vm.onEvent(ContinuityEvent.OpenDepartment(pick.departments.first()))
        advanceUntilIdle()
        assertNull(vm.state.value.pick)
        assertEquals(listOf("a"), assertNotNull(vm.state.value.open).shown.map { it.id })
        assertEquals(listOf(read(ContinuityTab.AllDepartments, "12", "d2")), badges.reads)
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
        assertEquals("Fill the Scene Number", vm.state.value.editor?.error)
        assertNull(vm.state.value.error, "a refused form stays inside the dialog, not on the page")

        vm.onEvent(ContinuityEvent.DraftChanged(SceneDraft(sceneNumber = "A1")))
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertTrue(
            assertNotNull(vm.state.value.editor?.error).startsWith("Scene number cannot submit without a number"),
        )

        vm.onEvent(ContinuityEvent.DraftChanged(SceneDraft(sceneNumber = "12!")))
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertEquals("Special character not allow!", vm.state.value.editor?.error)

        vm.onEvent(ContinuityEvent.DraftChanged(SceneDraft(sceneNumber = "012", notes = "n")))
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertEquals(2, repo.created.size)
        assertNull(vm.state.value.editor)
        assertEquals(listOf("12"), vm.state.value.shownFolders)
    }

    /** The web's `handleUnitChatUploadFiles` lets pictures, videos and office documents through and nothing else. */
    @Test
    fun `a picked file the web would refuse is dropped with a warning`() = runTest(dispatcher) {
        val vm = viewModel(FakeRepo())
        val notices = mutableListOf<ContinuityEffect>()
        val listening = launch { vm.effects.collect { notices += it } }
        runCurrent()
        vm.start()
        advanceUntilIdle()
        vm.onEvent(
            ContinuityEvent.FilesPicked(
                listOf(
                    PickedContinuityFile("app.exe", "application/octet-stream", ByteArray(1)),
                    PickedContinuityFile("notes.pdf", "application/pdf", ByteArray(1)),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf("notes.pdf"), assertNotNull(vm.state.value.editor).files.map { it.name })
        assertTrue(notices.any { it is ContinuityEffect.Notice && !it.success })

        val binary = PickedContinuityFile("x.bin", "application/octet-stream", ByteArray(1))
        vm.onEvent(ContinuityEvent.FilesPicked(listOf(binary)))
        advanceUntilIdle()
        assertEquals(
            listOf("notes.pdf"),
            assertNotNull(vm.state.value.editor).files.map { it.name },
            "nothing accepted, editor untouched",
        )
        listening.cancel()
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
        assertEquals(0, repo.created.size)
        assertNotNull(assertNotNull(vm.state.value.editor).error)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `television productions need a numeric episode`() = runTest(dispatcher) {
        val vm = viewModel(FakeRepo(), tv = true)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.FilesPicked(listOf(PickedContinuityFile("a.jpg", "image/jpeg", ByteArray(1)))))
        vm.onEvent(ContinuityEvent.DraftChanged(SceneDraft(sceneNumber = "3")))
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertEquals("Episode Number is required", vm.state.value.editor?.error)

        vm.onEvent(ContinuityEvent.DraftChanged(SceneDraft(sceneNumber = "3", episode = "2a")))
        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertEquals("Episode Number should be a number", vm.state.value.editor?.error)
    }

    /** "Add more details": a Title and a Description both required; the pencil edits a row in place. */
    @Test
    fun `detail rows are added, edited and removed through the sub-dialog`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12") }
        val vm = viewModel(repo)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.Edit(repo.cards.first()))

        vm.onEvent(ContinuityEvent.OpenDetail())
        vm.onEvent(ContinuityEvent.DetailChanged("Costume", ""))
        vm.onEvent(ContinuityEvent.SaveDetail)
        assertNotNull(assertNotNull(vm.state.value.editor).detail, "a blank description does not save")

        vm.onEvent(ContinuityEvent.DetailChanged("Costume", "Blue jacket"))
        vm.onEvent(ContinuityEvent.SaveDetail)
        assertEquals(listOf(TalentInfo("Costume", "Blue jacket")), vm.state.value.editor?.draft?.talentInfo)

        vm.onEvent(ContinuityEvent.OpenDetail(0))
        assertEquals("Costume", vm.state.value.editor?.detail?.label)
        vm.onEvent(ContinuityEvent.DetailChanged("Costume", "Red jacket"))
        vm.onEvent(ContinuityEvent.SaveDetail)
        assertEquals(listOf(TalentInfo("Costume", "Red jacket")), vm.state.value.editor?.draft?.talentInfo)

        vm.onEvent(ContinuityEvent.Save)
        advanceUntilIdle()
        assertEquals(listOf("a"), repo.updated.map { it.first })
        assertEquals("Red jacket", repo.updated.single().second.talentInfo.single().value)
        assertNull(vm.state.value.editor)

        vm.onEvent(ContinuityEvent.Edit(repo.cards.first()))
        vm.onEvent(ContinuityEvent.RemoveDetail(0))
        assertEquals(emptyList(), vm.state.value.editor?.draft?.talentInfo)
    }

    /** The web asks before the ticks appear; "All Departments" then shares the ticked ids. */
    @Test
    fun `forwarding asks, ticks, then shares to all departments`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12"); cards += scene("b", "12") }
        val vm = viewModel(repo)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()

        vm.onEvent(ContinuityEvent.RequestForward)
        assertTrue(vm.state.value.forwardIntent)
        assertFalse(assertNotNull(vm.state.value.open).selecting)
        vm.onEvent(ContinuityEvent.ConfirmForwardIntent)
        assertTrue(assertNotNull(vm.state.value.open).selecting)

        vm.onEvent(ContinuityEvent.ForwardSelected)
        assertNull(vm.state.value.forward, "nothing ticked, no drawer")
        vm.onEvent(ContinuityEvent.ToggleSelect("a"))
        vm.onEvent(ContinuityEvent.ForwardSelected)
        assertEquals(ForwardSheet.Step.Options, vm.state.value.forward?.step)

        vm.onEvent(ContinuityEvent.ForwardToAllDepartments)
        advanceUntilIdle()
        assertEquals(listOf(listOf("a")), repo.shared)
        assertNull(vm.state.value.forward)
        assertFalse(assertNotNull(vm.state.value.open).selecting)
    }

    /** "Select Users": one chat message per card per person, the details as the body. */
    @Test
    fun `forwarding to users sends every card to every chosen person`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            cards += scene("a", "12", notes = "Blue jacket", details = listOf(TalentInfo("Prop", "Cup")))
            cards += scene("b", "12")
        }
        val forwarder = FakeForwarder()
        val vm = viewModel(repo, forwarder = forwarder)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.RequestForward)
        vm.onEvent(ContinuityEvent.ConfirmForwardIntent)
        vm.onEvent(ContinuityEvent.ToggleSelect("a"))
        vm.onEvent(ContinuityEvent.ToggleSelect("b"))
        vm.onEvent(ContinuityEvent.ForwardSelected)
        vm.onEvent(ContinuityEvent.ForwardChooseUsers)

        assertEquals(listOf("u2", "u3"), vm.state.value.shownCrew.map { it.userId }, "never oneself")
        vm.onEvent(ContinuityEvent.SearchCrew("gaff"))
        assertEquals(listOf("u2"), vm.state.value.shownCrew.map { it.userId })
        vm.onEvent(ContinuityEvent.SearchCrew(""))
        vm.onEvent(ContinuityEvent.ToggleAllCrew)
        assertEquals(setOf("u2", "u3"), vm.state.value.forward?.selectedUsers)

        vm.onEvent(ContinuityEvent.SendForward)
        advanceUntilIdle()
        assertEquals(4, forwarder.sent.size)
        val captionA = forwarder.sent.first { it.first == "a" }.third
        assertEquals("Scene Number: 12\nScene Description: Blue jacket\nProp: Cup", captionA)
        assertNull(vm.state.value.forward)
        assertFalse(assertNotNull(vm.state.value.open).selecting)
    }

    @Test
    fun `a refused send keeps the drawer open with the error`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12") }
        val forwarder = FakeForwarder().apply { failFor = "u3" }
        val vm = viewModel(repo, forwarder = forwarder)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.RequestForward)
        vm.onEvent(ContinuityEvent.ConfirmForwardIntent)
        vm.onEvent(ContinuityEvent.ToggleSelect("a"))
        vm.onEvent(ContinuityEvent.ForwardSelected)
        vm.onEvent(ContinuityEvent.ForwardChooseUsers)
        vm.onEvent(ContinuityEvent.ToggleCrew("u2"))
        vm.onEvent(ContinuityEvent.ToggleCrew("u3"))
        vm.onEvent(ContinuityEvent.SendForward)
        advanceUntilIdle()
        assertEquals(1, forwarder.sent.size, "the deliverable one still went")
        assertNotNull(assertNotNull(vm.state.value.forward).error, "the refusal is inside the sheet")
        assertNull(vm.state.value.error)
    }

    /** Without the posting right the press asks rather than acts — the web's `request_admin_for_posting_rights`. */
    @Test
    fun `posting acts are refused without the right`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12") }
        val vm = viewModel(repo, canPost = false)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.RequestForward)
        assertFalse(vm.state.value.forwardIntent)
        assertEquals("You do not have post rights on Continuity.", vm.state.value.error)
        vm.onEvent(ContinuityEvent.RequestDelete(repo.cards.first()))
        assertNull(vm.state.value.confirmDelete)
    }

    @Test
    fun `delete takes the card off this board and closes an emptied folder`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12") }
        val vm = viewModel(repo)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.RequestDelete(repo.cards.first()))
        vm.onEvent(ContinuityEvent.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(listOf(ContinuityTab.MyDepartment to "a"), repo.deleted)
        assertNull(vm.state.value.open)
        assertEquals(emptyList(), vm.state.value.shownFolders)
    }

    /** `continuity_action_not_allowed` is a warning toast, not an error banner, and the card stays. */
    @Test
    fun `a refused delete warns and keeps the card`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { cards += scene("a", "12"); refuseDelete = true }
        val vm = viewModel(repo)
        val notices = mutableListOf<ContinuityEffect>()
        val listening = launch { vm.effects.collect { notices += it } }
        runCurrent()
        vm.start()
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.OpenFolder("12"))
        advanceUntilIdle()
        vm.onEvent(ContinuityEvent.RequestDelete(repo.cards.first()))
        vm.onEvent(ContinuityEvent.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(listOf("a"), assertNotNull(vm.state.value.open).shown.map { it.id })
        assertNull(vm.state.value.error)
        assertTrue(notices.any { it is ContinuityEffect.Notice && !it.success })
        listening.cancel()
    }

    /** Playing a video is a view: it opens without the download right. */
    @Test
    fun `open hands the file to the system without asking for download rights`() = runTest(dispatcher) {
        val transfer = FakeTransfer()
        val vm = viewModel(FakeRepo(), transfer = transfer, canPost = false)
        vm.start()
        advanceUntilIdle()
        val video = scene("v", "12").copy(
            attachment = ContinuityAttachment("k/v.mp4", "k/t.jpg", "video", "mp4", "v.mp4", "b", "r"),
        )
        vm.onEvent(ContinuityEvent.Open(video))
        advanceUntilIdle()
        assertEquals(listOf("v.mp4"), transfer.opened)
        assertNull(vm.state.value.error)
    }
}

private fun read(tab: ContinuityTab, folder: String, department: String?): Triple<ContinuityTab, String, String?> =
    Triple(tab, folder, department)

private fun scene(
    id: String,
    folder: String,
    dept: String = "d1",
    all: Boolean = false,
    created: Long = 100,
    notes: String = "n$id",
    details: List<TalentInfo> = emptyList(),
) = ContinuityScene(
    id = id, uniqueId = id, sceneNumber = folder, episode = "", notes = notes, actorName = "",
    talentInfo = details,
    attachment = ContinuityAttachment("k/$id.jpg", "k/$id.jpg", "image", "jpg", "$id.jpg", "b", "r"),
    departmentId = dept, uploadedBy = "u1",
    visibleIntra = true, visibleAll = all, deletedIntra = false, deletedAll = false,
    createdMs = created, updatedMs = 0,
)
