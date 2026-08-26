package com.zillit.desktop.feature.saportal.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The artiste's own side of the supporting-artiste system.
 *
 * Every call resolves the artiste from the signed-in session; a user with no
 * artiste record on this production gets `artiste_not_found_for_user`, which
 * is a legitimate answer rather than a fault — most crew are not artistes.
 */
interface SaPortalRepository {

    suspend fun summary(): ZillitResult<SaSummary>

    suspend fun profile(): ZillitResult<SaProfile>

    /** [status] filters to one bucket; null asks for everything, newest first. */
    suspend fun vouchers(status: VoucherStatus? = null): ZillitResult<List<Voucher>>

    suspend fun voucher(id: String): ZillitResult<VoucherDetail>

    /**
     * Signs one's own voucher.
     *
     * Both consents are required together, and the day must be `submitted` —
     * see [Voucher.signable]. The server refuses anything else.
     */
    suspend fun sign(
        id: String,
        typedName: String,
        consentAccuracy: Boolean,
        consentESign: Boolean,
    ): ZillitResult<Unit>

    suspend fun pay(): ZillitResult<PayStatement>

    suspend fun queries(): ZillitResult<List<ArtisteQuery>>

    suspend fun query(id: String): ZillitResult<ArtisteQuery>

    /** A query always hangs off a day — the server requires the voucher id. */
    suspend fun raiseQuery(voucherId: String, text: String): ZillitResult<Unit>

    suspend fun replyToQuery(id: String, text: String): ZillitResult<Unit>

    suspend fun resolveQuery(id: String): ZillitResult<Unit>
}
