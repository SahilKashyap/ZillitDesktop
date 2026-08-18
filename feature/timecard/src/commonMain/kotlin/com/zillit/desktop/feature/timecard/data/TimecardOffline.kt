package com.zillit.desktop.feature.timecard.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.sync.RetryPolicy
import com.zillit.desktop.core.sync.SyncContext
import com.zillit.desktop.core.sync.SyncHandler
import com.zillit.desktop.core.sync.SyncOperation
import com.zillit.desktop.core.sync.SyncOutcome
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.core.sync.toSyncOutcome
import com.zillit.desktop.feature.timecard.domain.LocalWeek
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import com.zillit.desktop.feature.timecard.domain.TimecardRepository
import com.zillit.desktop.feature.timecard.domain.TimecardStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The outbox kinds: a week saved offline, and its submission queued behind it. */
const val TIMECARD_SAVE_KIND = "timecard.save"
const val TIMECARD_SUBMIT_KIND = "timecard.submit"

/** Marks an id as belonging to a week that exists only on this computer. */
const val LOCAL_WEEK_PREFIX = "local:"

/** What the outbox carries for a saved week: the whole form. */
@Serializable
data class QueuedTimecardSave(
    val draft: TimecardDraft,
    val userId: String,
    val queuedAt: Long,
)

/**
 * A queued submit. [timecardId] is known when the week was already on the
 * server; otherwise the id arrives through the save it depends on.
 */
@Serializable
data class QueuedTimecardSubmit(
    val timecardId: String?,
    val weekStarting: Long?,
)

/**
 * Sends a week saved while offline.
 *
 * ## Idempotent by construction
 *
 * A person has one timecard per week, so `week_starting` is the natural key.
 * A draft with no id is not blindly POSTed: the handler reads the person's
 * weeks first and, if the week is already there — because a previous attempt
 * landed, or because they saved it from another device — PATCHes that one
 * instead. Then it reads again to adopt the server's id, which the queued
 * submit (and the local row) needs.
 */
class TimecardSaveHandler(
    private val repository: TimecardRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val policy: RetryPolicy = RetryPolicy(),
) : SyncHandler {

    override val kind: String = TIMECARD_SAVE_KIND

    override suspend fun execute(operation: SyncOperation, context: SyncContext): SyncOutcome {
        val queued = runCatching { json.decodeFromString(QueuedTimecardSave.serializer(), operation.payload) }
            .getOrElse { return SyncOutcome.Failed(ZillitError.Serialization(it.message)) }
        val draft = queued.draft

        val target = draft.timecardId ?: when (val mine = repository.myTimecards()) {
            is ZillitResult.Failure -> return policy.outcomeFor(mine.error)
            is ZillitResult.Success -> mine.data.firstOrNull { it.weekStarting == draft.weekStarting }?.id
        }

        return when (val saved = repository.save(draft.copy(timecardId = target))) {
            is ZillitResult.Failure -> policy.outcomeFor(saved.error)
            is ZillitResult.Success -> SyncOutcome.Done(
                // A PATCH already knows its id; a POST reads it back, best effort.
                result = target ?: repository.myTimecards().getOrNull()
                    ?.firstOrNull { it.weekStarting == draft.weekStarting }?.id,
            )
        }
    }
}

/**
 * Submits a week once it is on the server — its own id, the id its save
 * left behind, or a lookup by week as a last resort.
 */
class TimecardSubmitHandler(
    private val repository: TimecardRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val policy: RetryPolicy = RetryPolicy(),
) : SyncHandler {

    override val kind: String = TIMECARD_SUBMIT_KIND

    override suspend fun execute(operation: SyncOperation, context: SyncContext): SyncOutcome {
        val queued = runCatching { json.decodeFromString(QueuedTimecardSubmit.serializer(), operation.payload) }
            .getOrElse { return SyncOutcome.Failed(ZillitError.Serialization(it.message)) }

        val id = queued.timecardId
            ?: context.dependencyResult(operation)
            ?: when (val mine = repository.myTimecards()) {
                is ZillitResult.Failure -> return policy.outcomeFor(mine.error)
                is ZillitResult.Success -> mine.data.firstOrNull { it.weekStarting == queued.weekStarting }?.id
            }
            ?: return SyncOutcome.Failed(ZillitError.Validation(NOT_SAVED_MESSAGE))

        return repository.submit(id).toSyncOutcome(policy) { id }
    }

    companion object {
        const val NOT_SAVED_MESSAGE = "This week has not been saved on the server, so it cannot be submitted."
    }
}

/**
 * The queued week as a row for My Timecards, so what was saved offline is
 * visible where it will appear once sent. [submits] are the queued submits in
 * the same scope, so the row can say a submit is waiting behind it.
 */
fun SyncOperation.toLocalTimecard(
    submits: List<SyncOperation>,
    json: Json = Json { ignoreUnknownKeys = true },
): Timecard? {
    if (kind != TIMECARD_SAVE_KIND || state == SyncState.Done) return null
    val queued = runCatching { json.decodeFromString(QueuedTimecardSave.serializer(), payload) }.getOrNull()
        ?: return null
    val draft = queued.draft
    val submitQueued = submits.any { it.dependsOn == id && it.state != SyncState.Done }
    return Timecard(
        id = LOCAL_WEEK_PREFIX + id,
        userId = queued.userId,
        // Only ever the person's own week; the crew list is not to hand offline.
        crewName = "You",
        departmentId = null,
        designation = null,
        weekStarting = draft.weekStarting,
        weekNumber = null,
        status = TimecardStatus.Draft,
        currency = null,
        days = draft.days,
        totalDays = draft.days.count { it.dayType.isPaidWork }.toDouble(),
        notes = draft.notes.takeIf { it.isNotBlank() },
        queryNote = null,
        rejectionReason = null,
        lastApprovedBy = null,
        paidAt = null,
        updatedAt = updatedAt,
        local = LocalWeek(
            operationId = id,
            failed = state == SyncState.Failed,
            error = lastError,
            submitQueued = submitQueued,
        ),
    )
}
