package com.zillit.desktop.feature.location.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The location service (`locationapi`, `/api/v2/location`). */
interface LocationRepository {

    /**
     * A pulse per socket frame saying another client created, edited, or
     * deleted a record — the web's `location_created/updated/deleted`
     * handlers (`LocationPage.jsx:1044,1108,1126`). The ViewModel answers
     * by re-running its load. Empty by default: tests, and hosts without
     * a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    /**
     * A line added to the open record's thread by somebody else.
     *
     * Separate from [refreshes] because it reloads a different thing: the
     * shortlist does not move when a note is left on one record.
     */
    val discussionRefreshes: Flow<Unit> get() = emptyFlow()

    /**
     * The folder source for one shortlist — flat, grouped client-side.
     *
     * [fileCabinet]: the File Cabinet's viewer. The web sends nothing extra
     * on this call there; it only stops dropping the rows flagged `delete`
     * (`LocationPage.jsx:394`), which is what a cabinet is made of.
     */
    suspend fun info(status: LocationStatus, fileCabinet: Boolean = false): ZillitResult<List<LocationInfo>>

    /**
     * One page of records under a folder. Blank filters are OMITTED (the
     * "Not Assigned" folder sends no filter for that dimension, as the web
     * does); [beforeMs] + `previous` first, then the last row's `created` +
     * `next`.
     *
     * [fileCabinet] adds `isFileCabinet=true`, which makes the server answer
     * the records moved to the cabinet. The web then keeps the first page
     * whole and drops `deleted` rows only from later pages
     * (`LocationPage.jsx:1535`), and so does this.
     */
    suspend fun media(
        status: LocationStatus,
        location: String?,
        sceneNumber: String?,
        episode: String?,
        beforeMs: Long,
        next: Boolean,
        fileCabinet: Boolean = false,
    ): ZillitResult<List<LocationMedia>>

    /**
     * Creates one record; the stored file rides in `attachment`, a URL in
     * `link`, and the link's preview picture — or the map around a picked
     * place — in `link_attachment`. A record may now carry a file *and* a
     * link; see [LocationMedia.isLink].
     */
    suspend fun create(draft: LocationDraft, attachment: MediaAttachment?,
        linkPreview: MediaAttachment?): ZillitResult<LocationMedia?>

    /** [linkPreview] is sent only when the pin moved; otherwise the stored picture stands. */
    suspend fun update(id: String, draft: LocationDraft,
        linkPreview: MediaAttachment? = null): ZillitResult<Unit>

    suspend fun delete(ids: List<String>, status: LocationStatus): ZillitResult<Unit>

    /**
     * Moves records to another shortlist, keeping their folder.
     *
     * [place] is a place picked in the Move form: a move otherwise drops the
     * link and the pin, and the web's Move to Shortlist / Final asks where
     * the location is so the Map tool can draw it (PR #6521).
     */
    suspend fun move(records: List<LocationMedia>, from: LocationStatus, to: LocationStatus,
        place: MovePlace? = null): ZillitResult<Unit>

    /**
     * Shortlisted and Final locations that carry a pin, one per place.
     *
     * [status] narrows it to one shortlist — the Location tool's own map view
     * of a tab. Without it the Map tool's read: both stages, Final winning a
     * place that is in both.
     */
    suspend fun mapPins(status: LocationStatus? = null): ZillitResult<List<LocationPin>> =
        ZillitResult.Success(emptyList())

    /** Asks the server for a PDF of the records; answers a stored file. */
    suspend fun pdf(ids: List<String>, includeDetails: Boolean): ZillitResult<MediaAttachment>

    /**
     * A record's discussion, newest first
     * (`GET /v2/location/chat/{recordId}/{before}/previous`).
     *
     * The path parameter the web calls `unitId` is the *record's* own id, not
     * a production unit's (`CastingChat.jsx:122-127`) — a name that has cost
     * more than one reader an afternoon.
     */
    suspend fun messages(
        recordId: String,
        beforeMillis: Long,
        page: Int = 0,
    ): ZillitResult<List<LocationMessage>> = ZillitResult.Success(emptyList())

    /** Posts one line to a record's discussion (`POST /v2/location/chat`). */
    suspend fun sendMessage(recordId: String, body: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("the location discussion is not wired"))
}

/** Host seams: storage up and down, and the OS hand-off. */
interface LocationTransfer {
    suspend fun upload(file: PickedLocationFile): ZillitResult<MediaAttachment>
    suspend fun fetch(attachment: MediaAttachment): ZillitResult<ByteArray>
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}
