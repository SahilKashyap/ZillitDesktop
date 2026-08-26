package com.zillit.desktop.feature.crewlist

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.crewlist.data.matchesProject
import com.zillit.desktop.feature.crewlist.domain.CrewListPdf
import com.zillit.desktop.feature.crewlist.domain.CrewListRepository
import com.zillit.desktop.feature.crewlist.domain.CrewListTransfer
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.ui.CrewListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `department:reordered` frame reloads the roster exactly once — the web
 * handler refetches the users (`NewCrewList.jsx:244`) — and a frame naming
 * another production is ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrewListSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(override val refreshes: Flow<Unit>) : CrewListRepository {
        var rosterCalls = 0

        override suspend fun roster(): ZillitResult<List<CrewUnit>> {
            rosterCalls++
            return ZillitResult.Success(emptyList())
        }

        override suspend fun generate(hideExternalLabel: Boolean): ZillitResult<CrewListPdf> =
            ZillitResult.Success(CrewListPdf(media = "m", name = "n", bucket = "b", region = "r"))
    }

    @Test
    fun `an emitted event reloads the roster once, and a second start does not stack`() =
        runTest(dispatcher) {
            val events = MutableSharedFlow<Unit>()
            val repository = FakeRepository(events)
            val model = CrewListViewModel(
                repository = repository,
                transfer = CrewListTransfer { ZillitResult.Success(Unit) },
                resolveViewer = { CrewListViewer() },
            )

            model.start()
            runCurrent()
            assertEquals(1, repository.rosterCalls, "start loads once")

            events.emit(Unit)
            runCurrent()
            assertEquals(2, repository.rosterCalls, "the event reloads")

            model.start() // the window reopening must not add a second collector
            runCurrent()
            events.emit(Unit)
            runCurrent()
            assertEquals(4, repository.rosterCalls, "start reloads, the event reloads ONCE")
        }

    @Test
    fun `only a frame naming another production is dropped`() {
        assertTrue(Json.parseToJsonElement("""{"project_id":"p1"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"project_id":"p2"}""").matchesProject("p1"))
        assertTrue(null.matchesProject("p1"), "a payload-less frame must pass")
    }
}
