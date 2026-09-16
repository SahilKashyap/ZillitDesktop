package com.zillit.desktop.feature.budget

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetActivityRow
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetDepartment
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMember
import com.zillit.desktop.feature.budget.domain.BudgetMembers
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetRepository
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetUpload
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.budget.ui.BudgetBadges
import com.zillit.desktop.feature.budget.ui.BudgetContext
import com.zillit.desktop.feature.budget.ui.BudgetEffect
import com.zillit.desktop.feature.budget.ui.BudgetEvent
import com.zillit.desktop.feature.budget.ui.BudgetHost
import com.zillit.desktop.feature.budget.ui.BudgetMembersDialog
import com.zillit.desktop.feature.budget.ui.BudgetPerson
import com.zillit.desktop.feature.budget.ui.BudgetStage
import com.zillit.desktop.feature.budget.ui.BudgetUnread
import com.zillit.desktop.feature.budget.ui.BudgetViewModel
import com.zillit.desktop.feature.budget.ui.PickedBudgetFile
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

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- the full budget --------------------------------------------------------

    /** `FullBudget.jsx`: the reader's department is named on the main path, and the newest version opens. */
    @Test
    fun `main mode loads its versions newest first and opens the latest`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            byDepartment = mapOf("dept-me" to listOf(version("v1", updated = 10), version("v2", updated = 30))),
            chats = listOf(BudgetChatEntry.Person("u2", "Ravi")),
        )
        val model = viewModel(BudgetMode.Main, repository)

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(BudgetType.Main to "dept-me", repository.lastAsked)
        assertEquals(listOf("v2", "v1"), state.documents.map { it.id })
        assertEquals("v2", state.selected?.id)
        assertTrue(state.canChat)
        assertEquals(listOf("u2"), state.chats.map { it.key })
        assertEquals(BudgetStage.Versions, state.stage)
    }

    /** Opening a version reads its row (`readMainBudgetBadges`) and, on the full budget, marks it visited. */
    @Test
    fun `selecting a version reads its badge and remembers the visit`() = runTest(dispatcher) {
        val badges = RecordingBadges()
        val repository = FakeBudgetRepository(
            byDepartment = mapOf("dept-me" to listOf(version("v1", updated = 10), version("v2", updated = 30))),
        )
        val model = viewModel(BudgetMode.Main, repository, badges = badges)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.SelectVersion("v1"))
        advanceUntilIdle()

        assertEquals(listOf("v2", "v1"), badges.readDocuments)
        assertEquals(listOf("v1"), repository.visited)
        // An older version can be read, not discussed (`CommonBudget.jsx:2135`).
        assertFalse(model.state.value.canChat)
        assertNull(model.state.value.selectedChat)
    }

    /** No view right on the tile: nothing is asked for, and the screen says so. */
    @Test
    fun `no view right asks the service for nothing`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository()
        val model = viewModel(BudgetMode.Main, repository, rights = rights(mainView = false))

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertNull(repository.lastAsked)
        assertFalse(model.state.value.viewer.canView(BudgetMode.Main))
    }

    /**
     * The tool is reopened from the grid with the same view model. The web
     * remounts and clears its current chat; found live: the last visit's
     * thread and department were still open under a fresh directory.
     */
    @Test
    fun `reopening the tool drops the last visit's department and thread`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            all = listOf(version("c1", type = BudgetType.Department, department = "cam", created = 1)),
            byDepartment = mapOf("cam" to listOf(version("c1", updated = 1))),
            chats = listOf(BudgetChatEntry.Group("r1", "Camera crew")),
        )
        val model = viewModel(BudgetMode.Department, repository)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()
        model.onEvent(BudgetEvent.OpenDepartment("cam"))
        advanceUntilIdle()
        model.onEvent(BudgetEvent.OpenChat(BudgetChatEntry.Group("r1", "Camera crew")))
        assertEquals("r1", model.state.value.selectedChat?.key)

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(BudgetStage.Directory, state.stage)
        assertNull(state.openDepartment)
        assertNull(state.selectedChat)
        assertTrue(state.documents.isEmpty())
    }

    // -- upload ------------------------------------------------------------------

    /** The dialog refuses a missing date, then a future one; the title is the web's. */
    @Test
    fun `upload needs a date no later than today and titles by it`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(byDepartment = mapOf("dept-me" to listOf(version("v1", updated = 10))))
        val host = FakeHost(picked = PickedBudgetFile("Budget.pdf", ByteArray(10)))
        val today = 1_788_609_600_000L // 2026-09-05T12:00Z
        val model = viewModel(BudgetMode.Main, repository, host = host, now = today)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.UploadRequested())
        advanceUntilIdle()
        assertNotNull(model.state.value.upload, "the picked file is staged for the dialog")

        model.onEvent(BudgetEvent.UploadConfirm)
        assertEquals("Date is required.", model.state.value.upload?.complaint)

        // A half-typed date is kept as text (the field echoes it back) with no date yet.
        model.onEvent(BudgetEvent.UploadDateChanged("2026-0", null))
        assertEquals("2026-0", model.state.value.upload?.dateText)
        assertNull(model.state.value.upload?.dateMillis)

        model.onEvent(BudgetEvent.UploadDateChanged("2026-09-07", today + 2 * 86_400_000L))
        model.onEvent(BudgetEvent.UploadConfirm)
        assertEquals("The date cannot be after today.", model.state.value.upload?.complaint)

        model.onEvent(BudgetEvent.UploadDateChanged("2026-09-05", today))
        model.onEvent(BudgetEvent.UploadConfirm)
        advanceUntilIdle()

        val posted = repository.posted.single()
        assertEquals(BudgetType.Main, posted.type)
        assertTrue(posted.title.startsWith("Budget (Full) -SEP 05, 2026"), posted.title)
        assertTrue(posted.file.thumbnail.endsWith(".png"), "the stock PDF thumbnail is pinned")
        assertNull(model.state.value.upload)
        assertEquals("Budget uploaded.", model.state.value.notice)
        // The new version is the open one after the list is re-read.
        assertEquals("posted", model.state.value.selected?.id)
    }

    /** Without posting rights the picker never opens; the refusal asks an admin. */
    @Test
    fun `upload without posting rights is refused before the picker`() = runTest(dispatcher) {
        val host = FakeHost(picked = PickedBudgetFile("Budget.pdf", ByteArray(1)))
        val model = viewModel(BudgetMode.Main, FakeBudgetRepository(), host = host, rights = rights(mainPost = false))
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.UploadRequested())
        advanceUntilIdle()

        assertEquals(0, host.picks)
        assertTrue(model.state.value.error!!.contains("posting rights"))
    }

    /** A television production needs the episode on every upload (`episode_number_required`). */
    @Test
    fun `television uploads need an episode`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(byDepartment = mapOf("dept-me" to emptyList()))
        val host = FakeHost(picked = PickedBudgetFile("Budget.pdf", ByteArray(1)), television = true)
        val today = 1_788_609_600_000L
        val model = viewModel(BudgetMode.Main, repository, host = host, now = today)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.UploadRequested())
        advanceUntilIdle()
        model.onEvent(BudgetEvent.UploadDateChanged("2026-09-05", today))
        model.onEvent(BudgetEvent.UploadConfirm)
        assertEquals("Episode number is required.", model.state.value.upload?.complaint)

        model.onEvent(BudgetEvent.UploadEpisodeChanged("4"))
        model.onEvent(BudgetEvent.UploadConfirm)
        advanceUntilIdle()

        assertEquals("4", repository.posted.single().episode)
    }

    // -- the department budget ---------------------------------------------------

    /** `DepartmentBudget.jsx`: the whole list, one row per department, then that department's versions. */
    @Test
    fun `department mode opens on the directory and drills into one department`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            all = listOf(
                version("c1", type = BudgetType.Department, department = "cam", created = 1),
                version("c2", type = BudgetType.Department, department = "cam", created = 5),
                version("a1", type = BudgetType.Department, department = "art", created = 3),
                version("m1", type = BudgetType.Main, created = 9),
            ),
            byDepartment = mapOf("cam" to listOf(version("c1", updated = 1), version("c2", updated = 5))),
        )
        val model = viewModel(BudgetMode.Department, repository)

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertEquals(BudgetStage.Directory, model.state.value.stage)
        assertEquals(listOf("Camera", "Art"), model.state.value.directoryVisible.map { it.department.name })

        model.onEvent(BudgetEvent.OpenDepartment("cam"))
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(BudgetStage.Versions, state.stage)
        assertEquals("Camera", state.openDepartment?.name)
        assertEquals("c2", state.selected?.id)
        assertEquals("cam", state.scopeDepartmentId)
        assertEquals(BudgetType.Department to "cam", repository.lastAsked)

        model.onEvent(BudgetEvent.BackToDirectory)
        assertEquals(BudgetStage.Directory, model.state.value.stage)
        assertTrue(model.state.value.documents.isEmpty())
    }

    /** The "+" drawer offers only the departments that have no budget yet. */
    @Test
    fun `the drawer lists the departments without a budget`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            all = listOf(version("c1", type = BudgetType.Department, department = "cam", created = 1)),
        )
        val model = viewModel(BudgetMode.Department, repository)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.OpenDrawer)

        assertEquals(listOf("Art"), model.state.value.drawer?.visible?.map { it.name })
    }

    /** A head of department without main-budget rights sees their own department alone. */
    @Test
    fun `a department-only reader sees only their department in the directory`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            all = listOf(version("a1", type = BudgetType.Department, department = "art", created = 1)),
        )
        val model = viewModel(
            BudgetMode.Department,
            repository,
            rights = rights(mainView = false, mainPost = false),
            host = FakeHost(departmentId = "cam"),
        )
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        // Their own department, added though it has no budget; Art hidden.
        assertEquals(listOf("Camera"), model.state.value.directoryVisible.map { it.department.name })
    }

    // -- conversations -----------------------------------------------------------

    /** "Add member": the pick opens that person's thread and adds their row. */
    @Test
    fun `adding a member opens their thread`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            byDepartment = mapOf("dept-me" to listOf(version("v1", updated = 10))),
            members = BudgetMembers(
                main = listOf(BudgetMember("me", "Me"), BudgetMember("u2", ""), BudgetMember("u3", "")),
            ),
            chats = listOf(BudgetChatEntry.Person("u3", "Already")),
        )
        val host = FakeHost(crew = listOf(person("u2", "Ravi Menon"), person("u3", "Anita Rao")))
        val model = viewModel(BudgetMode.Main, repository, host = host)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.ShowMembers(BudgetMembersDialog.Kind.Member))
        advanceUntilIdle()
        // Oneself and the already-listed person are not offered.
        assertEquals(listOf("u2"), model.state.value.members?.candidates?.map { it.userId })

        model.onEvent(BudgetEvent.TogglePick("u2"))

        val state = model.state.value
        assertNull(state.members)
        assertEquals("u2", state.selectedChat?.key)
        assertEquals(listOf("u3", "u2"), state.chats.map { it.key })
    }

    /** "Create group": validated as the web validates, then the room is opened. */
    @Test
    fun `creating a group validates then opens the room`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            byDepartment = mapOf("dept-me" to listOf(version("v1", updated = 10))),
            members = BudgetMembers(main = listOf(BudgetMember("u2", ""), BudgetMember("u3", ""))),
        )
        val host = FakeHost(crew = listOf(person("u2", "Ravi Menon"), person("u3", "Anita Rao")))
        val model = viewModel(BudgetMode.Main, repository, host = host)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.ShowMembers(BudgetMembersDialog.Kind.Group))
        advanceUntilIdle()
        model.onEvent(BudgetEvent.ConfirmMembers)
        assertEquals("Group name is mandatory.", model.state.value.members?.complaint)

        model.onEvent(BudgetEvent.GroupNameChanged("Camera crew"))
        model.onEvent(BudgetEvent.PickAll)
        model.onEvent(BudgetEvent.ConfirmMembers)
        advanceUntilIdle()

        val made = repository.rooms.single()
        assertEquals("Camera crew", made.name)
        assertEquals(setOf("u2", "u3"), made.members.toSet())
        assertEquals("v1", made.documentId)
        assertNull(model.state.value.members)
        assertEquals("room-1", model.state.value.selectedChat?.key)
    }

    /** No discussion may be opened on an older version — the dialog never appears. */
    @Test
    fun `members dialog is refused on an older version`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            byDepartment = mapOf("dept-me" to listOf(version("v1", updated = 10), version("v2", updated = 30))),
        )
        val model = viewModel(BudgetMode.Main, repository)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()
        model.onEvent(BudgetEvent.SelectVersion("v1"))
        advanceUntilIdle()

        model.onEvent(BudgetEvent.ShowMembers(BudgetMembersDialog.Kind.Group))

        assertNull(model.state.value.members)
    }

    // -- files --------------------------------------------------------------------

    /** View records the view and opens what the record answers; download needs the right. */
    @Test
    fun `view records and opens, download checks the right`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(byDepartment = mapOf("dept-me" to listOf(version("v1", updated = 10))))
        val model = viewModel(BudgetMode.Main, repository, rights = rights(download = false))
        val effects = mutableListOf<BudgetEffect>()
        val listening = launch { model.effects.collect { effects += it } }
        runCurrent()
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.ViewFile)
        advanceUntilIdle()
        assertEquals(listOf("v1" to BudgetActivity.View), repository.recorded)
        assertEquals(1, effects.size)
        assertFalse((effects.single() as BudgetEffect.Open).save)

        model.onEvent(BudgetEvent.DownloadFile)
        advanceUntilIdle()
        assertEquals(1, effects.size, "no download without the right")
        assertTrue(model.state.value.error!!.contains("download rights"))
        listening.cancel()
    }

    /** The admin's count sheet lists the rows the service answers. */
    @Test
    fun `activity sheet loads its rows`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            byDepartment = mapOf("dept-me" to listOf(version("v1", updated = 10))),
            activity = listOf(BudgetActivityRow("u2", viewCount = 2)),
        )
        val model = viewModel(BudgetMode.Main, repository)
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        model.onEvent(BudgetEvent.ShowActivity(BudgetActivity.View))
        advanceUntilIdle()

        assertEquals(listOf("u2"), model.state.value.activity?.rows?.map { it.userId })
        assertFalse(model.state.value.activity!!.loading)
    }

    // -- television -----------------------------------------------------------------

    /** Television: episodes first, then the episode's versions; the crumb goes back. */
    @Test
    fun `television groups the main budget by episode`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            byDepartment = mapOf(
                "dept-me" to listOf(
                    version("e1a", updated = 1, episode = "1"),
                    version("e1b", updated = 2, episode = "1"),
                    version("e2", updated = 3, episode = "2"),
                ),
            ),
        )
        val model = viewModel(BudgetMode.Main, repository, host = FakeHost(television = true))
        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertEquals(BudgetStage.Episodes, model.state.value.stage)
        assertEquals(listOf("1", "2"), model.state.value.episodesVisible)

        model.onEvent(BudgetEvent.OpenEpisode("1"))
        advanceUntilIdle()
        assertEquals(listOf("e1b", "e1a"), model.state.value.documents.map { it.id })
        assertEquals(BudgetStage.Versions, model.state.value.stage)

        model.onEvent(BudgetEvent.BackToEpisodes)
        assertEquals(BudgetStage.Episodes, model.state.value.stage)
    }

    // -- helpers --------------------------------------------------------------------

    private fun rights(
        mainView: Boolean = true,
        mainPost: Boolean = true,
        departmentView: Boolean = true,
        departmentPost: Boolean = true,
        download: Boolean = true,
    ) = BudgetViewer(
        canViewMain = mainView,
        canPostMain = mainPost,
        canViewDepartment = departmentView,
        canPostDepartment = departmentPost,
        canDownloadMain = download,
        canDownloadDepartment = download,
        resolved = true,
    )

    private fun viewModel(
        mode: BudgetMode,
        repository: FakeBudgetRepository,
        rights: BudgetViewer = rights(),
        host: FakeHost = FakeHost(),
        badges: BudgetBadges = RecordingBadges(),
        now: Long = 1_788_609_600_000L,
    ) = BudgetViewModel(
        mode = mode,
        repository = repository,
        viewer = { rights },
        host = host,
        badges = badges,
        nowMillis = { now },
    )

    private fun version(
        id: String,
        type: BudgetType = BudgetType.Main,
        department: String = "",
        created: Long = 0,
        updated: Long = created,
        episode: String = "",
    ) = BudgetDocument(
        id = id,
        type = type,
        departmentId = department,
        title = "Budget $id",
        episode = episode,
        file = BudgetFile(media = "k/$id.pdf", name = "$id.pdf"),
        uploadedById = "u1",
        createdMillis = created,
        updatedMillis = updated,
    )

    private fun person(id: String, name: String) = BudgetPerson(userId = id, fullName = name)
}

private class FakeHost(
    val picked: PickedBudgetFile? = null,
    val television: Boolean = false,
    val departmentId: String = "dept-me",
    val crew: List<BudgetPerson> = emptyList(),
) : BudgetHost {
    var picks = 0

    override suspend fun pickPdf(): PickedBudgetFile? {
        picks++
        return picked
    }

    override suspend fun store(name: String, bytes: ByteArray): ZillitResult<BudgetFile> =
        ZillitResult.Success(BudgetFile(media = "stored/$name", name = name, sizeBytes = bytes.size.toLong()))

    override fun context() = BudgetContext(
        userId = "me",
        departmentId = departmentId,
        departmentName = "Camera",
        isTelevision = television,
        departments = listOf(BudgetDepartment("cam", "Camera"), BudgetDepartment("art", "Art")),
    )

    override fun crew(): List<BudgetPerson> = crew
}

private class RecordingBadges : BudgetBadges {
    val readDocuments = mutableListOf<String>()
    override fun unread(mode: BudgetMode): Flow<BudgetUnread> = MutableStateFlow(BudgetUnread.None)
    override fun markDocumentRead(mode: BudgetMode, documentId: String, departmentId: String) {
        readDocuments += documentId
    }
}

private class FakeBudgetRepository(
    private val all: List<BudgetDocument> = emptyList(),
    private val byDepartment: Map<String, List<BudgetDocument>> = emptyMap(),
    private val members: BudgetMembers = BudgetMembers(),
    private val chats: List<BudgetChatEntry> = emptyList(),
    private val activity: List<BudgetActivityRow> = emptyList(),
) : BudgetRepository {
    var lastAsked: Pair<BudgetType, String>? = null
    val posted = mutableListOf<BudgetUpload>()
    val visited = mutableListOf<String>()
    val recorded = mutableListOf<Pair<String, BudgetActivity>>()
    val rooms = mutableListOf<MadeRoom>()

    class MadeRoom(val name: String, val members: List<String>, val documentId: String)

    private val extra = mutableMapOf<String, MutableList<BudgetDocument>>()

    override suspend fun documents() = ZillitResult.Success(all + extra.values.flatten())

    override suspend fun documentsOf(type: BudgetType, departmentId: String): ZillitResult<List<BudgetDocument>> {
        lastAsked = type to departmentId
        return ZillitResult.Success(byDepartment[departmentId].orEmpty() + extra[departmentId].orEmpty())
    }

    override suspend fun post(upload: BudgetUpload): ZillitResult<BudgetDocument> {
        posted += upload
        val saved = BudgetDocument(
            id = "posted",
            type = upload.type,
            departmentId = upload.departmentId,
            title = upload.title,
            episode = upload.episode,
            file = upload.file,
            updatedMillis = Long.MAX_VALUE,
        )
        extra.getOrPut(upload.departmentId.ifBlank { "dept-me" }) { mutableListOf() } += saved
        return ZillitResult.Success(saved)
    }

    override suspend fun delete(documentIds: List<String>) = ZillitResult.Success(Unit)

    override suspend fun members(departmentId: String) = ZillitResult.Success(members)

    override suspend fun markVisited(documentId: String): ZillitResult<Unit> {
        visited += documentId
        return ZillitResult.Success(Unit)
    }

    override suspend fun record(documentId: String, activity: BudgetActivity): ZillitResult<BudgetDocument?> {
        recorded += documentId to activity
        return ZillitResult.Success(null)
    }

    override suspend fun activity(documentId: String, activity: BudgetActivity) = ZillitResult.Success(this.activity)

    override suspend fun chats(mode: BudgetMode, departmentId: String, documentId: String) = ZillitResult.Success(chats)

    override suspend fun createRoom(
        mode: BudgetMode,
        departmentId: String,
        documentId: String,
        name: String,
        memberIds: List<String>,
    ): ZillitResult<BudgetChatEntry.Group> {
        rooms += MadeRoom(name, memberIds, documentId)
        return if (name == "refuse") {
            ZillitResult.Failure(ZillitError.Unknown("Cnc Atleast One Member"))
        } else {
            ZillitResult.Success(
                BudgetChatEntry.Group(roomId = "room-${rooms.size}", name = name, memberIds = memberIds),
            )
        }
    }
}
