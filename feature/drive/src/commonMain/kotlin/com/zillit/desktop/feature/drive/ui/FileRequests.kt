package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.feature.drive.domain.DriveFileRequest
import com.zillit.desktop.feature.drive.domain.DriveFileRequestDraft
import com.zillit.desktop.feature.drive.domain.DriveItem

/**
 * How the "Request files" panel moves.
 *
 * Kept out of the view model as plain transforms: each step is one obvious
 * function that can be read and tested without a coroutine, and the view
 * model stays a list of handlers rather than a second copy of these rules.
 */
internal object FileRequests {

    /** Opening on a folder: what is already there has not loaded yet. */
    fun opening(folder: DriveItem): FileRequestState = FileRequestState(
        folderId = folder.id,
        folderName = folder.name,
        loading = true,
    )

    fun loaded(state: FileRequestState, rows: List<DriveFileRequest>): FileRequestState =
        state.copy(loading = false, requests = rows)

    /** What the form is asking the service for. */
    fun draft(state: FileRequestState): DriveFileRequestDraft? {
        val folderId = state.folderId ?: return null
        return DriveFileRequestDraft(
            destinationFolderId = folderId,
            title = state.title.trim(),
            description = state.description.trim(),
            expiresInMillis = state.expiryDays.toLong() * MILLIS_PER_DAY,
            requireUploaderName = state.requireName,
            requireUploaderEmail = state.requireEmail,
        )
    }

    /**
     * After a link is made: the form empties, and the new request goes to the
     * top of the list where the reader is already looking.
     */
    fun created(state: FileRequestState, made: DriveFileRequest): FileRequestState = state.copy(
        submitting = false,
        created = made,
        title = "",
        description = "",
        requests = listOf(made) + state.requests,
    )

    /**
     * A revoked request keeps its row, marked, and loses its address: it is
     * what explains where a link someone still holds has gone.
     */
    fun revoked(state: FileRequestState, requestId: String): FileRequestState = state.copy(
        requests = state.requests.map {
            if (it.id == requestId) it.copy(revoked = true, link = "") else it
        },
    )

    /** Expiry is asked for in days and sent in milliseconds. */
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}
