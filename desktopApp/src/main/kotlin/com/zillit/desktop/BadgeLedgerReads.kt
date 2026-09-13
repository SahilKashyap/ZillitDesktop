package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.BadgeStore
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.badges.MailFolderState
import com.zillit.desktop.core.badges.NotificationRecord
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.domain.MailboxCredentials
import com.zillit.desktop.feature.email.ui.MailFolderSync
import com.zillit.desktop.feature.email.ui.MailRead
import com.zillit.desktop.feature.notifications.domain.NotificationsRepository
import com.zillit.desktop.feature.sos.domain.SosRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/**
 * The mailbox's reads, applied to the ledger.
 *
 * An email row is keyed by folder and IMAP uid, tagged with the mailbox
 * address in `level_1` — see `LedgerRead.Mail`. Two hooks: a message opened
 * clears its row; a folder synced retires the rows that no longer describe
 * unread mail (read on another device, moved by a rule, deleted), which is
 * what keeps the Email badge equal to the unread mail on screen.
 */
internal class MailLedger(
    private val store: BadgeStore,
    /** This person's own mailbox address, as the rows tag it; null while unknown (the read then fails open). */
    private val mailboxAddress: suspend () -> String?,
) {
    private var lastReport = ""

    suspend fun read(read: MailRead): Int =
        store.markRead(LedgerRead.Mail(read.folderName, read.uid, read.messageId, mailboxAddress()))

    suspend fun synced(sync: MailFolderSync): Int {
        val retired = store.markRead(LedgerRead.MailFolder(sync.toLedgerState(mailboxAddress())))
        if (retired > 0) ZillitLog.d(TAG) { "email rows retired by ${sync.folderName} sync: $retired" }
        report()
        return retired
    }

    private fun MailFolderSync.toLedgerState(mailbox: String?) = MailFolderState(
        folder = folderName,
        uids = serverUids,
        readUids = messages.filter { it.isRead }.map { it.uid }.toSet(),
        readMessageIds = messages.filter { it.isRead }.map { it.id }.toSet(),
        messageIds = messages.map { it.id }.toSet(),
        complete = complete,
        listedAt = listedAt,
        mailbox = mailbox,
    )

    /**
     * Names the email rows a sync left counting — folder, key, mailbox tag,
     * written when — once per distinct set. A badge that outlives its mail is
     * diagnosed from this line, not from the count.
     */
    private fun report() {
        val rows = store.unreadRows(BadgeSections.EMAIL)
        val line = rows.joinToString { "${it.unit}/${it.referenceId}/${it.level1.maskedAddress()}/${it.created}" }
        if (line == lastReport) return
        lastReport = line
        ZillitLog.d(TAG) { "email rows still unread: ${rows.size} [$line]" }
    }

    private fun String.maskedAddress(): String = when {
        isBlank() -> "-"
        '@' in this -> take(1) + "…" + substring(indexOf('@'))
        else -> take(1) + "…"
    }

    private companion object {
        const val TAG = "Badges"
    }
}

/**
 * This person's mailbox address for the open production, asked of the profile
 * once and kept — a read must not cost a request. Re-asked after a failure,
 * and for another production.
 */
internal class MailboxAddress(
    private val projectId: () -> String?,
    private val fetch: suspend () -> ZillitResult<MailboxCredentials?>,
) {
    private val lock = Mutex()
    private var forProject: String? = null
    private var resolved = false
    private var address: String? = null

    suspend operator fun invoke(): String? = lock.withLock {
        val project = projectId()
        if (project != forProject) {
            forProject = project
            resolved = false
            address = null
        }
        if (!resolved) {
            when (val got = fetch()) {
                is ZillitResult.Success -> {
                    address = got.data?.emailAddress?.takeIf { it.isNotBlank() }
                    resolved = true
                }
                is ZillitResult.Failure -> ZillitLog.w(TAG) { "mailbox address unknown: ${got.error.technical}" }
            }
        }
        address
    }

    private companion object {
        const val TAG = "Badges"
    }
}
