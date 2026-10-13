package com.zillit.desktop.feature.castboard.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult

/** The casting service. */
interface CastingRepository {

    /** Everyone at [status] in one casting list. */
    suspend fun entries(unitId: String, status: CastingStatus): ZillitResult<List<CastingEntry>>

    /**
     * Moves one entry to another stage
     * (`PUT /v2/{segment}/{unitId}?castId=&status=`).
     *
     * The stage is the *destination*, and it rides the query string while the
     * body carries the row — a board where nothing can be moved is a board
     * nobody can cast from.
     */
    /**
     * An entry's discussion, newest first
     * (`GET /v2/{segment}/chat/{entryId}/{before}/previous`).
     *
     * The path parameter the web calls `unitId` is the *entry's* own id, not
     * a production unit's — the same misleading name the location thread
     * carries.
     */
    suspend fun messages(
        entryId: String,
        beforeMillis: Long,
        page: Int = 0,
    ): ZillitResult<List<BoardMessage>> = ZillitResult.Success(emptyList())

    /** Posts one line (`POST /v2/{segment}/chat`), the body encrypted. */
    suspend fun sendMessage(entryId: String, body: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("the board discussion is not wired"))

    suspend fun moveTo(
        unitId: String,
        entryId: String,
        status: CastingStatus,
    ): ZillitResult<Unit> = ZillitResult.Failure(ZillitError.Unknown("moving is not wired"))

    /**
     * Adds one record to a list (`POST /v2/{segment}/{unitId}`) — the web's
     * `createCasting` and `createWardrobe`, which are the same route under
     * each service's own noun.
     */
    suspend fun create(unitId: String, draft: BoardRecordDraft): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("creating is not wired"))

    /**
     * A wardrobe list's characters, for the one question a costume asks
     * (`GET /v2/wardrobe/get-casts/{unitId}`). Empty on casting, which has
     * no such list: there the character is typed.
     */
    suspend fun casts(unitId: String): ZillitResult<List<BoardCast>> = ZillitResult.Success(emptyList())
}
