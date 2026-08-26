package com.zillit.desktop.feature.addashboard.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The AD department's side of supporting artistes.
 *
 * Scoped to the open production by the usual project/user module data; the
 * shoot day is addressed by date rather than id on the `today` routes,
 * because the AD works in days, not records.
 */
interface AdRepository {

    // -- the register ---------------------------------------------------------

    suspend fun artistes(): ZillitResult<List<Artiste>>

    suspend fun verify(artisteId: String): ZillitResult<Unit>

    suspend fun block(artisteId: String, reason: String?): ZillitResult<Unit>

    suspend fun unblock(artisteId: String): ZillitResult<Unit>

    /** Refused by the server for a verified artiste; see [Artiste.deletable]. */
    suspend fun deleteArtiste(artisteId: String): ZillitResult<Unit>

    // -- the day --------------------------------------------------------------

    /** The shoot day for [shootDate] (UTC midnight), created by the server if absent. */
    suspend fun today(shootDate: Long): ZillitResult<AdShootDay>

    suspend fun shootDays(): ZillitResult<List<AdShootDay>>

    /** Everyone on that day's list. */
    suspend fun dayList(shootDate: Long): ZillitResult<List<SupportingArtistDay>>

    /** Adds artistes to a day in one call — the web's `bulk`. */
    suspend fun addToDay(
        artisteIds: List<String>,
        shootDate: Long,
        callTime: String?,
    ): ZillitResult<Unit>

    suspend fun updateDayEntry(
        id: String,
        callTime: String? = null,
        wrapTime: String? = null,
        attendance: AttendanceStatus? = null,
    ): ZillitResult<Unit>

    suspend fun removeFromDay(id: String): ZillitResult<Unit>

    /** Sends the day to the production — after which it is locked. */
    suspend fun submitDay(shootDate: Long): ZillitResult<Unit>
}

/**
 * The dates this client sends.
 *
 * Every date on this service is **UTC midnight** and its timezone stamp is
 * `UTC` — the module works in UTC wall-clock throughout, deliberately, so a
 * unit shooting across midnight in another zone still files one day. Sending
 * a local midnight puts an artiste on the wrong day.
 */
object AdDates {
    const val MILLIS_PER_DAY = 86_400_000L

    /** [millis] flattened to the UTC midnight that contains it. */
    fun utcMidnight(millis: Long): Long = millis - Math.floorMod(millis, MILLIS_PER_DAY)
}
