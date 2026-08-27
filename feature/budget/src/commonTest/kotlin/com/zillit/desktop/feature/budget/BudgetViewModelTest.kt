package com.zillit.desktop.feature.budget

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMembers
import com.zillit.desktop.feature.budget.domain.BudgetRepository
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.budget.ui.BudgetEvent
import com.zillit.desktop.feature.budget.ui.BudgetTab
import com.zillit.desktop.feature.budget.ui.BudgetViewModel
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Both budgets land in their own halves of the screen. */
    @Test
    fun `loading splits the documents by type`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            documents = listOf(main("b1"), department("b2", "Camera"), department("b3", "Art")),
        )
        val model = viewModel(repository)

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertEquals("b1", model.state.value.mainBudget?.id)
        assertEquals(listOf("b2", "b3"), model.state.value.departmentBudgets.map { it.id })
    }

    /**
     * A viewer with no main budget opens on their department's, rather than
     * on an empty tab they cannot use (`BudgetPrimaryComponent.jsx:130-141`).
     */
    @Test
    fun `a department-only viewer opens on the department tab`() = runTest(dispatcher) {
        val model = viewModel(
            FakeBudgetRepository(documents = listOf(department("b2", "Camera"))),
            // No main budget at all — neither right, which is what the web
            // reads as department-only.
            rights = rights(mainView = false, mainPost = false, departmentView = true),
        )

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertEquals(BudgetTab.Department, model.state.value.tab)
        assertEquals(listOf(BudgetTab.Department), model.state.value.tabs)
    }

    /** No rights at all: the screen says so instead of showing an empty list. */
    @Test
    fun `no access asks the service for nothing`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository()
        val model = viewModel(repository, rights = rights(mainView = false, departmentView = false))

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertTrue(model.state.value.viewer.hasNoAccess)
        assertEquals(0, repository.listCalls, "a refused viewer must not reach the wire")
    }

    /** Upload puts the file in storage first, then tells the service about it. */
    @Test
    fun `uploading posts the stored file and reloads`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository()
        val model = viewModel(repository, file = BudgetFile(media = "k/new.pdf", name = "new.pdf"))

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()
        model.onEvent(BudgetEvent.Upload)
        advanceUntilIdle()

        assertEquals(BudgetType.Main to "k/new.pdf", repository.posted)
        assertEquals(2, repository.listCalls, "the list is re-read so the new file shows")
    }

    /** Without posting rights the upload never leaves the screen. */
    @Test
    fun `a viewer who cannot post cannot upload`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository()
        val model = viewModel(
            repository,
            rights = rights(mainView = true, mainPost = false),
            file = BudgetFile(media = "k/new.pdf"),
        )

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()
        model.onEvent(BudgetEvent.Upload)
        advanceUntilIdle()

        assertNull(repository.posted)
    }

    /** A failure is the server's sentence, not a silent nothing. */
    @Test
    fun `a refused list says what the server said`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            failure = ZillitError.Http(status = 200, serverMessage = "budget tool is off"),
        )
        val model = viewModel(repository)

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertEquals("budget tool is off", model.state.value.error)
    }

    /** Opening a budget is also a record — the service keeps the visit. */
    @Test
    fun `opening marks the document visited`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(documents = listOf(main("b1")))
        val model = viewModel(repository)

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()
        model.onEvent(BudgetEvent.OpenFile)
        advanceUntilIdle()

        assertEquals(listOf("b1"), repository.visited)
    }

    /** A budget with no file has nothing to open, and says so. */
    @Test
    fun `opening an empty budget explains itself`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(documents = listOf(main("b1", file = null)))
        val model = viewModel(repository)

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()
        model.onEvent(BudgetEvent.OpenFile)
        advanceUntilIdle()

        assertEquals("There is no file on this budget yet.", model.state.value.error)
        assertTrue(repository.visited.isEmpty())
    }

    /** Download rights are separate from view rights, and are enforced. */
    @Test
    fun `downloading without the right is refused`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(documents = listOf(main("b1")))
        val model = viewModel(repository, rights = rights(mainView = true, download = false))

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()
        model.onEvent(BudgetEvent.DownloadFile)
        advanceUntilIdle()

        assertEquals("You do not have download rights for this budget.", model.state.value.error)
    }

    /**
     * The live list came back with no `department_name` (2026-08-26), which
     * left the row calling itself whatever the screen's fallback said. The
     * production's own catalogue fills the gap.
     */
    @Test
    fun `a nameless department takes its name from the production`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(
            documents = listOf(
                BudgetDocument(id = "b9", type = BudgetType.Department, departmentId = "d-cam"),
            ),
        )
        val model = BudgetViewModel(
            repository = repository,
            viewer = { rights() },
            departmentName = { id -> "Camera".takeIf { id == "d-cam" } },
        )

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertEquals("Camera", model.state.value.departmentBudgets.single().departmentName)
    }

    /** A name the wire did send is never overwritten by the catalogue. */
    @Test
    fun `a named department keeps the wire's name`() = runTest(dispatcher) {
        val repository = FakeBudgetRepository(documents = listOf(department("b2", "Second Unit Camera")))
        val model = BudgetViewModel(
            repository = repository,
            viewer = { rights() },
            departmentName = { "Camera" },
        )

        model.onEvent(BudgetEvent.Load)
        advanceUntilIdle()

        assertEquals("Second Unit Camera", model.state.value.departmentBudgets.single().departmentName)
    }

    private fun main(id: String, file: BudgetFile? = BudgetFile("k/$id.pdf", "$id.pdf")) =
        BudgetDocument(id = id, type = BudgetType.Main, file = file)

    private fun department(id: String, name: String) = BudgetDocument(
        id = id,
        type = BudgetType.Department,
        departmentId = "d-$id",
        departmentName = name,
        file = BudgetFile("k/$id.xlsx", "$id.xlsx"),
    )

    private fun rights(
        mainView: Boolean = true,
        mainPost: Boolean = true,
        departmentView: Boolean = true,
        download: Boolean = true,
    ) = BudgetViewer.from(
        ProjectPermissions(
            listOf(
                ToolAccess(
                    identifier = BudgetViewer.MAIN_TOOL,
                    canView = mainView,
                    canPost = mainPost,
                    canDownload = download,
                ),
                ToolAccess(identifier = BudgetViewer.DEPARTMENT_TOOL, canView = departmentView, canPost = true),
            ),
        ),
    )

    private fun viewModel(
        repository: FakeBudgetRepository,
        rights: BudgetViewer = rights(),
        file: BudgetFile? = null,
    ) = BudgetViewModel(
        repository = repository,
        viewer = { rights },
        departmentId = { "d-mine" },
        pickFile = file?.let { picked -> { picked } },
    )
}

private class FakeBudgetRepository(
    private val documents: List<BudgetDocument> = emptyList(),
    private val failure: ZillitError? = null,
) : BudgetRepository {

    var listCalls = 0
    var posted: Pair<BudgetType, String>? = null
    val visited = mutableListOf<String>()

    override suspend fun documents(): ZillitResult<List<BudgetDocument>> {
        listCalls++
        return failure?.let { ZillitResult.Failure(it) } ?: ZillitResult.Success(documents)
    }

    override suspend fun documentsOf(type: BudgetType, departmentId: String) =
        ZillitResult.Success(documents.filter { it.type == type })

    override suspend fun post(
        type: BudgetType,
        departmentId: String,
        file: BudgetFile,
    ): ZillitResult<BudgetDocument> {
        posted = type to file.media
        return ZillitResult.Success(BudgetDocument(id = "new", type = type, file = file))
    }

    override suspend fun delete(documentIds: List<String>) = ZillitResult.Success(Unit)

    override suspend fun members(departmentId: String) = ZillitResult.Success(BudgetMembers())

    override suspend fun markVisited(documentId: String): ZillitResult<Unit> {
        visited += documentId
        return ZillitResult.Success(Unit)
    }

    override suspend fun activityCount(documentId: String, action: BudgetActivity) = ZillitResult.Success(0)
}
