package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.boxschedule.data.matchesProject
import com.zillit.desktop.feature.boxschedule.domain.BlockDraft
import com.zillit.desktop.feature.boxschedule.domain.BlockWrite
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleRepository
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.MainCalendarLookup
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdf
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfOptions
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleViewModel
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

    private class FakeRepository(override val refreshes: Flow<Unit>) : BoxScheduleRepository {
        var blockLoads = 0

        override suspend fun types() = ZillitResult.Success(emptyList<ScheduleType>())
        override suspend fun createType(title: String, color: String) = ZillitResult.Success(Unit)
        override suspend fun updateType(id: String, title: String?, color: String?) =
            ZillitResult.Success(Unit)
        override suspend fun deleteType(id: String) = ZillitResult.Success(Unit)
        override suspend fun blocks(): ZillitResult<List<ScheduleBlock>> {
            blockLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun createBlock(draft: BlockDraft, resolve: ConflictAction?):
            ZillitResult<BlockWrite> = ZillitResult.Success(BlockWrite.Saved)
        override suspend fun updateBlock(id: String, draft: BlockDraft, resolve: ConflictAction?):
            ZillitResult<BlockWrite> = ZillitResult.Success(BlockWrite.Saved)
        override suspend fun deleteBlock(id: String) = ZillitResult.Success(Unit)
        override suspend fun removeDates(entries: Map<String, List<Long>>) = ZillitResult.Success(Unit)
        override suspend fun duplicateBlock(sourceId: String, newStartDate: Long) =
            ZillitResult.Success(Unit)
        override suspend fun events(scheduleDayId: String?) =
            ZillitResult.Success(emptyList<DiaryEvent>())
        override suspend fun noteTypes() = ZillitResult.Success(emptyList<NoteType>())
        override suspend fun pdf(options: DiaryPdfOptions, action: DiaryPdfAction, watermark: String) =
            ZillitResult.Success(DiaryPdf(media = "box/diary.pdf", name = "Box Schedule.pdf"))
        override suspend fun createEvent(draft: DiaryDraft) = ZillitResult.Success(Unit)
        override suspend fun updateEvent(
            id: String, draft: DiaryDraft, scope: RecurrenceScope, occurrenceDate: Long?,
        ) = ZillitResult.Success(Unit)
        override suspend fun deleteEvent(id: String, scope: RecurrenceScope, occurrenceDate: Long?) =
            ZillitResult.Success(Unit)
    }

    @Test
    fun `an emitted event re-runs the load once, and a second start does not stack`() =
        runTest(dispatcher) {
            val events = MutableSharedFlow<Unit>()
            val repository = FakeRepository(events)
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
