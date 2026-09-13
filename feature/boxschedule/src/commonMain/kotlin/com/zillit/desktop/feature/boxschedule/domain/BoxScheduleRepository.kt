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
 * The production diary service — `/api/v2/box-schedule/...` and
 * `/api/v2/user-preset` on the pre-and-production host.
 */
@Suppress("TooManyFunctions") // One suspend fun per server operation; the web's service has as many.
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

    /** Creates a type; the answer names the new type's id when the server sent one. */
    suspend fun createType(title: String, color: String): ZillitResult<String?>
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

    /** `PUT /days/:id` with the title alone — a one-day edit that kept its type. */
    suspend fun renameBlock(id: String, title: String): ZillitResult<Unit>

    /**
     * `PUT /days/:id/single-date` — moves one date of a block to another type.
     * The server splits the block, logs, bumps revisions and broadcasts, in
     * one atomic write.
     */
    suspend fun changeSingleDay(id: String, date: Long, typeId: String, action: ConflictAction): ZillitResult<Unit>

    /** Deletes a whole block; the answer is the server's own message, when it sent one. */
    suspend fun deleteBlock(id: String): ZillitResult<String?>

    /** Removes single dates from blocks; a block left empty is deleted server-side. */
    suspend fun removeDates(entries: Map<String, List<Long>>): ZillitResult<String?>

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
     * `startDateTime` — sending the start-of-day matches nothing. [RecurrenceScope.All]
     * sends no parameters at all.
     */
    suspend fun updateEvent(
        id: String,
        draft: DiaryDraft,
        scope: RecurrenceScope = RecurrenceScope.All,
        occurrenceDate: Long? = null,
    ): ZillitResult<Unit>

    /**
     * Deletes one event or note. Null [scope] is a plain whole-document delete
     * with no parameters; a recurring row sends `delete_type`, and
     * `occurrence_date` for every scope but [RecurrenceScope.All].
     */
    suspend fun deleteEvent(
        id: String,
        scope: RecurrenceScope? = null,
        occurrenceDate: Long? = null,
    ): ZillitResult<Unit>

    /** `GET /activity-log?limit=200&page=0` — the History drawer. */
    suspend fun history(): ZillitResult<List<HistoryEntry>>

    /** `GET /revisions` — the snapshots the history details pair with. */
    suspend fun revisions(): ZillitResult<List<DiaryRevision>>

    suspend fun presets(): ZillitResult<List<UserPreset>>

    /** `POST /user-preset`; a [presetId] makes it an update. */
    suspend fun savePreset(presetId: String?, name: String, userIds: List<String>): ZillitResult<Unit>

    suspend fun deletePreset(presetId: String): ZillitResult<Unit>

    /** `POST /share/generate-link` — the read-only link, made absolute against the web app. */
    suspend fun shareLink(): ZillitResult<String>
}

data class NoteType(
    val value: String,
    val label: String,
    val hideDistribution: Boolean,
) {
    companion object {
        /** What the form offers when the server's list has not answered — the web's seed. */
        val DEFAULTS = listOf(
            NoteType("general", "General", hideDistribution = false),
            NoteType(PERSONAL_NOTE_TYPE, PERSONAL_NOTE_LABEL, hideDistribution = true),
        )
    }
}

/**
 * The Main Calendar's events for the diary's merge — a different service, so
 * a seam the host wires. Failure yields an empty list; the web swallows it.
 */
fun interface MainCalendarLookup {
    suspend fun events(fromMs: Long, toMs: Long): List<DiaryEvent>
}
