package com.zillit.desktop.feature.recce.domain

import com.zillit.desktop.core.common.ZillitResult

/** The recce service (`recceapi`), routes under `/api/v2/recce`. */
interface RecceRepository {

    /** Every recce of the production; the web filters and pages client-side. */
    suspend fun recces(): ZillitResult<List<Recce>>

    suspend fun recce(id: String): ZillitResult<Recce>

    /** The crew offered by the personnel picker. */
    suspend fun crew(): ZillitResult<List<RecceCrewMember>>

    /** Creates; the returned id is the new record's. */
    suspend fun create(draft: RecceDraft): ZillitResult<String>

    /** Updates in place; the id rides in the body, not the path. */
    suspend fun update(id: String, draft: RecceDraft): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>

    /** Asks the server to render the report; answers a stored file. */
    suspend fun report(id: String): ZillitResult<RecceReport>
}

/**
 * Everything the client sends. Distinct from [Recce] because the server owns
 * the id and the version, and because a draft may be blank in ways a saved
 * record cannot — the web saves drafts with no validation at all.
 */
data class RecceDraft(
    val uniqueId: String,
    /** IANA zone, so the server's PDF prints the wall-clock the user typed. */
    val timezone: String,
    val title: String,
    val unit: String,
    val dateMs: Long,
    val station: String,
    val weather: String,
    val crewNote: String,
    val rdv: RecceStop,
    val itinerary: List<RecceStop>,
    val personnel: List<ReccePerson>,
    val status: RecceStatus,
)

/** Host seams the module cannot own: opening a rendered report, the clock. */
interface RecceTransfer {
    /** Fetches the stored report and hands it to the OS (Downloads + open). */
    suspend fun openReport(report: RecceReport): ZillitResult<Unit>
}
