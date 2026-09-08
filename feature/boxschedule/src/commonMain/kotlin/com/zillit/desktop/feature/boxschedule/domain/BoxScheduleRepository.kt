package com.zillit.desktop.feature.boxschedule.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The result of writing a block: it landed, or the server reported collisions. */
sealed interface BlockWrite {
    data object Saved : BlockWrite
    data class Conflicts(val conflicts: List<DateConflict>) : BlockWrite
}

/**
 * The production diary service — `/api/v2/box-schedule/...` on the
 * pre-and-production host.
 */
interface BoxScheduleRepository {

    /**
     * A pulse per diary wire event from another client — the web page's
     * `refreshAll` / `fetchTypes` / `loadStandaloneEvents` wiring
     * (`boxScheduleV2/index.jsx:1538-1554`). One flow serves all three:
     * this client's one refresh refetches types, blocks, events, and the
     * calendar merge together. Empty by default: tests, and hosts without
     * a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    suspend fun types(): ZillitResult<List<ScheduleType>>
    suspend fun createType(title: String, color: String): ZillitResult<Unit>
    suspend fun updateType(id: String, title: String?, color: String?): ZillitResult<Unit>
    suspend fun deleteType(id: String): ZillitResult<Unit>

    suspend fun blocks(): ZillitResult<List<ScheduleBlock>>

    /**
     * Creates a block. A colliding date comes back as [BlockWrite.Conflicts]
     * — HTTP 409 or a `status:0` envelope carrying `conflicts` — and the
     * caller retries with a [ConflictAction].
     */
    suspend fun createBlock(draft: BlockDraft, resolve: ConflictAction? = null): ZillitResult<BlockWrite>

    /** Full-array replace of the block's dates; same conflict routing. */
    suspend fun updateBlock(id: String, draft: BlockDraft, resolve: ConflictAction? = null): ZillitResult<BlockWrite>

    suspend fun deleteBlock(id: String): ZillitResult<Unit>

    /** Removes single dates from blocks; a block left empty is deleted server-side. */
    suspend fun removeDates(entries: Map<String, List<Long>>): ZillitResult<Unit>

    suspend fun duplicateBlock(sourceId: String, newStartDate: Long): ZillitResult<Unit>

    /**
     * Events and notes — recurring masters arrive already expanded, one row
     * per occurrence. Optionally scoped to one block.
     */
    suspend fun events(scheduleDayId: String? = null): ZillitResult<List<DiaryEvent>>

    /** The note kinds the server offers, with their labels. */
    suspend fun noteTypes(): ZillitResult<List<NoteType>>

    /**
     * `GET box-schedule/pdf` — the server renders the diary and stages the
     * file. Personal Notes are left out only when [options] say so; omitting
     * the parameter is the server's include-everything default.
     */
    suspend fun pdf(options: DiaryPdfOptions, action: DiaryPdfAction, watermark: String): ZillitResult<DiaryPdf>

    suspend fun createEvent(draft: DiaryDraft): ZillitResult<Unit>

    /**
     * Edits one event or note. For a recurring occurrence, [scope] decides how
     * far the change reaches and [occurrenceDate] MUST be the occurrence's
     * `startDateTime` — sending the start-of-day matches nothing.
     */
    suspend fun updateEvent(
        id: String,
        draft: DiaryDraft,
        scope: RecurrenceScope = RecurrenceScope.All,
        occurrenceDate: Long? = null,
    ): ZillitResult<Unit>

    suspend fun deleteEvent(
        id: String,
        scope: RecurrenceScope = RecurrenceScope.All,
        occurrenceDate: Long? = null,
    ): ZillitResult<Unit>
}

data class NoteType(
    val value: String,
    val label: String,
    val hideDistribution: Boolean,
)

/**
 * The Main Calendar's events for the diary's merge — a different service, so
 * a seam the host wires. Failure yields an empty list; the web swallows it.
 */
fun interface MainCalendarLookup {
    suspend fun events(fromMs: Long, toMs: Long): List<DiaryEvent>
}
