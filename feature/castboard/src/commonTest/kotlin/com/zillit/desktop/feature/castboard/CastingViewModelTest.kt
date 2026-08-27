package com.zillit.desktop.feature.castboard

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingRepository
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.ui.CastingEvent
import com.zillit.desktop.feature.castboard.ui.CastingViewModel
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CastingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Both lists granted: the board opens on the main one, at Selected. */
    @Test
    fun `it opens on the first granted list`() = runTest(dispatcher) {
        val repository = FakeCastingRepository()
        val model = viewModel(repository)

        model.onEvent(CastingEvent.Load)
        advanceUntilIdle()

        assertEquals(BoardTool.Casting.units[0], model.state.value.unit?.kind)
        assertEquals(listOf("unit-main" to CastingStatus.Selected), repository.asked)
    }

    /**
     * The web gates the whole page on `casting_tool`, which this production
     * never issues — only the two unit tools. Gating on what is granted shows
     * a caster their lists instead of bouncing them out.
     */
    @Test
    fun `the background list alone is enough`() = runTest(dispatcher) {
        val repository = FakeCastingRepository()
        val model = viewModel(repository, permissions = permissions(main = false, background = true))

        model.onEvent(CastingEvent.Load)
        advanceUntilIdle()

        assertFalse(model.state.value.hasNoAccess)
        assertEquals(BoardTool.Casting.units[1], model.state.value.unit?.kind)
    }

    /** Neither list: one honest sentence, and nothing asked of the service. */
    @Test
    fun `no lists means no access`() = runTest(dispatcher) {
        val repository = FakeCastingRepository()
        val model = viewModel(repository, permissions = permissions(main = false, background = false))

        model.onEvent(CastingEvent.Load)
        advanceUntilIdle()

        assertTrue(model.state.value.hasNoAccess)
        assertTrue(repository.asked.isEmpty())
    }

    /** Rights still in flight are not a refusal. */
    @Test
    fun `unresolved rights are not a refusal`() = runTest(dispatcher) {
        val model = viewModel(FakeCastingRepository(), permissions = ProjectPermissions(emptyList()))

        model.onEvent(CastingEvent.Load)
        advanceUntilIdle()

        assertFalse(model.state.value.hasNoAccess)
    }

    /** Changing stage re-asks for that stage, not the old one. */
    @Test
    fun `a status change refetches`() = runTest(dispatcher) {
        val repository = FakeCastingRepository()
        val model = viewModel(repository)

        model.onEvent(CastingEvent.Load)
        advanceUntilIdle()
        model.onEvent(CastingEvent.StatusChanged(CastingStatus.Published))
        advanceUntilIdle()

        assertEquals("unit-main" to CastingStatus.Published, repository.asked.last())
    }

    /** Search reads both the character and everyone up for it. */
    @Test
    fun `search matches a character or a candidate`() = runTest(dispatcher) {
        val repository = FakeCastingRepository(
            entries = listOf(
                CastingEntry(id = "1", characterName = "Inspector Rao", talentNames = listOf("Ravi Menon")),
                CastingEntry(id = "2", characterName = "Nurse", talentNames = listOf("Sara Ali")),
            ),
        )
        val model = viewModel(repository)
        model.onEvent(CastingEvent.Load)
        advanceUntilIdle()

        model.onEvent(CastingEvent.QueryChanged("sara"))
        assertEquals(listOf("Nurse"), model.state.value.visible.map { it.characterName })

        model.onEvent(CastingEvent.QueryChanged("inspector"))
        assertEquals(listOf("Inspector Rao"), model.state.value.visible.map { it.characterName })
    }

    /**
     * A failure is shown, and shown readably: this server complains in keys
     * (`casting_access_denied`), which the app turns into words before anyone
     * reads them.
     */
    @Test
    fun `a refused list says why, in words`() = runTest(dispatcher) {
        val repository = FakeCastingRepository(
            failure = ZillitError.Http(status = 200, serverMessage = "casting_access_denied"),
        )
        val model = viewModel(repository)

        model.onEvent(CastingEvent.Load)
        advanceUntilIdle()

        assertEquals("Casting Access Denied", model.state.value.error)
    }

    private fun permissions(main: Boolean = true, background: Boolean = true) = ProjectPermissions(
        buildList {
            if (main) {
                add(
                    ToolAccess(
                        identifier = BoardTool.Casting.units[0].identifier,
                        unitId = "unit-main",
                        canView = true,
                        canPost = true,
                    ),
                )
            }
            if (background) {
                add(
                    ToolAccess(
                        identifier = BoardTool.Casting.units[1].identifier,
                        unitId = "unit-bg",
                        canView = true,
                    ),
                )
            }
            // A tool with no unit id cannot be addressed, so it is not a list.
            add(ToolAccess(identifier = "casting_tool", canView = true))
        },
    )

    private fun viewModel(
        repository: CastingRepository,
        permissions: ProjectPermissions = permissions(),
    ) = CastingViewModel(repository = repository, permissions = { permissions })
}

private class FakeCastingRepository(
    private val entries: List<CastingEntry> = emptyList(),
    private val failure: ZillitError? = null,
) : CastingRepository {

    val asked = mutableListOf<Pair<String, CastingStatus>>()
    var moved: Triple<String, String, CastingStatus>? = null

    override suspend fun moveTo(
        unitId: String,
        entryId: String,
        status: CastingStatus,
    ): ZillitResult<Unit> {
        moved = Triple(unitId, entryId, status)
        return ZillitResult.Success(Unit)
    }

    override suspend fun entries(
        unitId: String,
        status: CastingStatus,
    ): ZillitResult<List<CastingEntry>> {
        asked += unitId to status
        return failure?.let { ZillitResult.Failure(it) } ?: ZillitResult.Success(entries)
    }
}
