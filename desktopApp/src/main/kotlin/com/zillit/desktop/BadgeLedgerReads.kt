package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.BadgeStore
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.badges.NotificationRecord
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.notifications.domain.NotificationsRepository
import com.zillit.desktop.feature.sos.domain.SosRepository

/**
 * Which ledger rows a segment read clears — Android's `segmentProvider`
 * (`CommonBadgesHandler.kt:9927-10006`) collapsed to the surfaces this
 * desktop reads through [emitSegmentRead]: one entity when a reference is
 * named (an email), a whole area for an area label, the missed-call tool
 * for the Calls tab, else the unit — a board, a distribution list, a
 * transport queue.
 */
internal fun ledgerReadFor(segment: String, referenceId: String?): LedgerRead = when {
    referenceId != null -> LedgerRead.Reference(referenceId)
    segment in BadgeSections.all -> LedgerRead.Section(segment)
    segment == NotificationRecord.CALL_TOOL -> LedgerRead.Tool(segment)
    else -> LedgerRead.Board(segment)
}

/**
 * The SOS feed's reads, applied to the ledger too.
 *
 * Android `SosBadge`: every `sos_label` row on a feed fetch or a clear-all;
 * one alert deleted is one row, named by its `_id`.
 */
internal fun SosRepository.readingLedger(store: BadgeStore): SosRepository = object : SosRepository by this {
    override suspend fun markRead(timestampMillis: Long): ZillitResult<Unit> =
        this@readingLedger.markRead(timestampMillis).also { store.markRead(LedgerRead.Section(BadgeSections.SOS)) }

    override suspend fun deleteAlert(alertId: String, timestampMillis: Long): ZillitResult<Unit> =
        this@readingLedger.deleteAlert(alertId, timestampMillis)
            .also { store.markRead(LedgerRead.Reference(alertId)) }

    override suspend fun deleteAllAlerts(timestampMillis: Long): ZillitResult<Unit> =
        this@readingLedger.deleteAllAlerts(timestampMillis)
            .also { store.markRead(LedgerRead.Section(BadgeSections.SOS)) }
}

/**
 * The bell page's reads, applied to the ledger too.
 *
 * Android `GlobalRead`: every row of the segment; one row deleted is one
 * row, named by its `_id`.
 */
internal fun NotificationsRepository.readingLedger(store: BadgeStore): NotificationsRepository =
    object : NotificationsRepository by this {
        override suspend fun markRead(segment: String, timestampMillis: Long): ZillitResult<Unit> =
            this@readingLedger.markRead(segment, timestampMillis).also { store.markRead(LedgerRead.Section(segment)) }

        override suspend fun delete(notificationId: String, timestampMillis: Long): ZillitResult<Unit> =
            this@readingLedger.delete(notificationId, timestampMillis)
                .also { store.markRead(LedgerRead.Reference(notificationId)) }

        override suspend fun deleteAll(): ZillitResult<Unit> =
            this@readingLedger.deleteAll().also { store.markRead(LedgerRead.Section(BadgeSections.GLOBAL)) }
    }
