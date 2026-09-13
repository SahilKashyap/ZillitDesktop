package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.feature.boxschedule.data.matchesProject
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.MainCalendarLookup
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * A diary wire event re-runs the one big load exactly once — the web's
 * `BoxScheduleSocketRefresh` mounts (`boxScheduleV2/index.jsx:1538-1554`)
 * — and a frame naming another production is ignored, reading the diary's
 * camelCase `projectId` as well as the older `project_id`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoxScheduleSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an emitted event re-runs the load once, and a second start does not stack`() =
        runTest(dispatcher) {
            val events = MutableSharedFlow<Unit>()
            val repository = FakeDiaryRepository(refreshes = events)
            val model = BoxScheduleViewModel(
                repository = repository,
                calendar = MainCalendarLookup { _, _ -> emptyList() },
                resolveViewer = { BoxScheduleViewer(ready = true) },
                nowMillis = { 0L },
            )

            model.start()
            runCurrent()
            assertEquals(1, repository.blockLoads, "start loads once")

            events.emit(Unit)
            runCurrent()
            assertEquals(2, repository.blockLoads, "the event reloads")

            model.start() // the window reopening must not add a second collector
            runCurrent()
            events.emit(Unit)
            runCurrent()
            assertEquals(4, repository.blockLoads, "start reloads, the event reloads ONCE")
        }

    @Test
    fun `both projectId spellings are read, and only another production is dropped`() {
        assertTrue(Json.parseToJsonElement("""{"projectId":"p1"}""").matchesProject("p1"))
        assertTrue(Json.parseToJsonElement("""{"project_id":"p1"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"projectId":"p2"}""").matchesProject("p1"))
        assertTrue(null.matchesProject("p1"), "a payload-less frame must pass")
    }
}
