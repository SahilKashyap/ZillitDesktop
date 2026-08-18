package com.zillit.desktop.feature.location.domain

import com.zillit.desktop.core.common.ZillitResult

/** The location service (`locationapi`, `/api/v2/location`). */
interface LocationRepository {

    /** The folder source for one shortlist — flat, grouped client-side. */
    suspend fun info(status: LocationStatus): ZillitResult<List<LocationInfo>>

    /**
     * One page of records under a folder. Blank filters are OMITTED (the
     * "Not Assigned" folder sends no filter for that dimension, as the web
     * does); [beforeMs] + `previous` first, then the last row's `created` +
     * `next`.
     */
    suspend fun media(
        status: LocationStatus,
        location: String?,
        sceneNumber: String?,
        episode: String?,
        beforeMs: Long,
        next: Boolean,
    ): ZillitResult<List<LocationMedia>>

    /** Creates one record; the stored file rides in `attachment`, a URL in `link`. */
    suspend fun create(draft: LocationDraft, attachment: MediaAttachment?,
        linkPreview: MediaAttachment?): ZillitResult<LocationMedia?>

    suspend fun update(id: String, draft: LocationDraft): ZillitResult<Unit>

    suspend fun delete(ids: List<String>, status: LocationStatus): ZillitResult<Unit>

    /** Moves records to another shortlist, keeping their folder. */
    suspend fun move(records: List<LocationMedia>, from: LocationStatus, to: LocationStatus): ZillitResult<Unit>

    /** Asks the server for a PDF of the records; answers a stored file. */
    suspend fun pdf(ids: List<String>, includeDetails: Boolean): ZillitResult<MediaAttachment>
}

/** Host seams: storage up and down, and the OS hand-off. */
interface LocationTransfer {
    suspend fun upload(file: PickedLocationFile): ZillitResult<MediaAttachment>
    suspend fun fetch(attachment: MediaAttachment): ZillitResult<ByteArray>
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}
