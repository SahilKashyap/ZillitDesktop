package com.zillit.desktop.feature.invoices.domain

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The cost report's close boundary — the web's `useCrLock`.
 *
 * Everything dated on or before [lockedThrough] (`YYYY-MM-DD`, inclusive) is
 * read-only: the server refuses to create or change a source document there,
 * so the screens stop offering it. Blank means nothing is closed.
 */
data class PeriodLock(
    val lockedThrough: String = "",
    /** The production's zone, for turning a stored instant into its day; blank = London. */
    val timeZone: String = "",
) {
    val isSet: Boolean get() = lockedThrough.isNotBlank()

    /**
     * Whether a document dated [ms] sits in the closed period — `isDateLocked`.
     * An undated document is never locked.
     */
    fun isLocked(ms: Long?): Boolean {
        if (!isSet || ms == null || ms <= 0) return false
        val zone = runCatching { TimeZone.of(timeZone.ifBlank { DEFAULT_ZONE }) }.getOrDefault(TimeZone.UTC)
        val day = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date.toString()
        return day <= lockedThrough
    }

    /** A typed `YYYY-MM-DD` inside the closed period. */
    fun isLocked(ymd: String): Boolean = isSet && ymd.isNotBlank() && ymd.take(DATE_LENGTH) <= lockedThrough

    companion object {
        private const val DEFAULT_ZONE = "Europe/London"
        private const val DATE_LENGTH = 10

        /** The later of two readings of one boundary — the lock only moves forward. */
        fun later(first: PeriodLock?, second: PeriodLock?): PeriodLock? = when {
            first == null -> second
            second == null -> first
            else -> PeriodLock(
                lockedThrough = maxOf(first.lockedThrough, second.lockedThrough),
                timeZone = first.timeZone.ifBlank { second.timeZone },
            )
        }
    }
}

/** A legal entity from Production Setup — the ledger's Company select. */
data class Company(val id: String, val name: String, val country: String = "")

/**
 * The slice of Production Setup the invoice screens read: companies, tax
 * types, and the close boundary as the settings document stores it.
 */
data class InvoiceProjectSettings(
    val companies: List<Company> = emptyList(),
    val taxTypes: List<TaxType> = emptyList(),
    val lock: PeriodLock? = null,
    /** The account tags lines may carry — `asset_tags` (`useProjectAssetTags`). */
    val assetTags: List<String> = emptyList(),
)

/**
 * A query thread on one record — the Account Hub's `/queries`, shared by every
 * module; an invoice's is filed under entity type `invoice`.
 */
data class QueryThread(val id: String = "", val messages: List<QueryMessage> = emptyList())

data class QueryMessage(val text: String, val by: String, val atMs: Long? = null)
