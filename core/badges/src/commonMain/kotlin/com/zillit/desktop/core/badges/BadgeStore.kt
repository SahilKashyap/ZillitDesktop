package com.zillit.desktop.core.badges

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray

/**
 * The live badge ledger, for whoever draws a badge.
 *
 * The open production's rows, held in memory and mirrored to [storage];
 * every count is recomputed from them ([tallyBadges]). This is the model
 * all three phones share: the server is asked for rows — a backlog page on
 * open, then only what changed since — never for counts, because its own
 * ledger keeps rows the phones have long read or pruned locally and would
 * hand them straight back. A read applied here is applied for good.
 *
 * A single store rather than per-consumer state: the rail, the window tabs
 * and the dock badge all show the same numbers, and three copies would
 * disagree the moment one missed an update.
 */
class BadgeStore(private val storage: NotificationLedgerStore = InMemoryNotificationLedgerStore()) {

    private val lock = Mutex()
    private val rows = mutableMapOf<String, NotificationRecord>()
    private var projectId: String? = null

    /**
     * Per production, the mailboxes this desktop can open there, as the
     * email rows tag them — see [showMailboxes]. A production not named yet
     * counts every row.
     */
    private val mailboxes = mutableMapOf<String, Set<String>>()

    /**
     * Per production, the group rooms its chat can still open — see
     * [showChatRooms]. A production whose room list has not answered here
     * counts every row.
     */
    private val chatRooms = mutableMapOf<String, Set<String>>()

    private val state = MutableStateFlow(BadgeCounts.Empty)
    val counts: StateFlow<BadgeCounts> = state.asStateFlow()

    private val changed =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Fires after the open production's rows moved — for a screen that folds
     * the rows itself rather than reading [counts] (the chat listing's
     * per-conversation split). Conflated: a burst is one signal.
     */
    val changes: Flow<Unit> = changed.asSharedFlow()

    /** The open production; null between productions. */
    val openProjectId: String? get() = projectId

    /** Loads a production's rows; the seed that follows adds what the server has since. */
    suspend fun open(projectId: String) = lock.withLock {
        this.projectId = projectId
        rows.clear()
        storage.rows(projectId).forEach { rows[it.id] = it }
        publish()
    }

    /**
     * Sign-out and production switch — the counts belong to a production. The
     * rows stay on disk, and so do the mailboxes learnt: the picker between
     * productions counts by them.
     */
    fun clear() {
        projectId = null
        rows.clear()
        state.value = BadgeCounts.Empty
    }

    /**
     * Sign-out — the next person's mailboxes and rooms are their own to name.
     *
     * Not a production switch: the picker's cards are counted under the
     * scopes learnt for each production, so they outlive the production that
     * taught them.
     */
    fun forgetMailboxes() {
        mailboxes.clear()
        chatRooms.clear()
    }

    /**
     * Which mailboxes the badges may count mail for on one production.
     *
     * An email row is tagged with its mailbox (`level_1`, ZL-21025): the
     * person's own, or the production's shared Accounts mailbox. The web
     * counts both because it can open both; a mailbox this desktop cannot
     * open is mail it has no view for and a badge nothing here could clear —
     * the two unread rows of an Accounts inbox sat on the rail for days
     * before the desktop had an Accounts mailbox. Rows for a mailbox not
     * named here stay in the ledger, uncounted.
     *
     * Per production, because mailboxes are provisioned per production. An
     * empty set is "unknown", not "none": an ask that failed keeps the last
     * answer, and only [forgetMailboxes] — a sign-out — forgets it.
     */
    suspend fun showMailboxes(projectId: String, addresses: Set<String>) = lock.withLock {
        val known = addresses.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (known.isEmpty() || mailboxes.put(projectId, known) == known) return@withLock
        if (projectId == this.projectId) publish()
    }

    /** The mailboxes learnt so far, production by production — for a host that remembers them. */
    val mailboxesShown: Map<String, Set<String>> get() = mailboxes.toMap()

    /**
     * The group rooms whose chat rows may count on one production — every
     * room `chat-room` listed, as the chat listing last loaded it. See
     * [isOfChatRooms] for why the ledger needs telling.
     *
     * Published by whoever holds the room list, on a load that succeeded: an
     * empty set is "this person is in no rooms", which is a real answer, so
     * the fail-open case is a production never published at all.
     */
    suspend fun showChatRooms(projectId: String, rooms: Set<String>) = lock.withLock {
        if (chatRooms.put(projectId, rooms) == rooms) return@withLock
        if (projectId == this.projectId) publish()
    }

    /**
     * The production picker's numbers — one per production, composed exactly
     * as the rail inside that production composes its own
     * ([tallyByProject]), under the same mailbox and room scopes.
     *
     * This was a raw count of unread rows, and that is what made a card wear
     * a number nothing inside could show or clear. Android groups its own
     * device's rows the same way (`calculateAllProjectsBadges`): rows for
     * this device when the ledger has any, else every row — a listing that
     * never names devices must not read as empty.
     */
    fun projectCounts(deviceId: String? = null): Map<String, Int> {
        val own = deviceId?.takeIf { it.isNotBlank() }?.let { storage.unreadAcrossProductions(it) }.orEmpty()
        val rows = own.ifEmpty { storage.unreadAcrossProductions("") }
        return tallyByProject(rows.filter { it.isShown(mailboxes, chatRooms) })
            .mapValues { (_, counts) -> counts.total }
            .filterValues { it > 0 }
    }

    /** The seed watermark for [projectId] — see [NotificationLedgerStore.watermark]. */
    fun watermark(projectId: String): Long = storage.watermark(projectId)

    /**
     * Rows the API handed over (a `previous` page, or `next` since the
     * watermark). Android's `insertOrUpdateFromApi`: a row read here stays
     * read even when the server still says unread; everything else is taken
     * as sent, read rows included — that is how a read on the phone lands
     * here without a socket frame.
     */
    suspend fun seed(records: Collection<NotificationRecord>): SeedOutcome = lock.withLock {
        val merged = records.map { incoming ->
            val known = rows[incoming.id] ?: storage.row(incoming.id)
            val readHere = known != null && known.messageRead && !incoming.messageRead
            if (readHere) incoming.copy(messageRead = true) else incoming
        }
        storage.upsert(merged)
        merged.filter { it.projectId == projectId }.forEach { rows[it.id] = it }
        publish()
        SeedOutcome(rows = merged.size, unread = merged.count { it.counts })
    }

    /**
     * Rows a `notification:save` frame carried. Kept for every production
     * (the socket is per device), counted for the open one. A Document
     * Distribution row supersedes an earlier unread row for the same entity
     * rather than stacking on it (Android `insertOrUpdate` 118-139).
     */
    suspend fun arrived(records: Collection<NotificationRecord>) = lock.withLock {
        if (records.isEmpty()) return@withLock
        val superseded = records
            .filter { it.tool == DOCUMENT_DISTRIBUTION && it.referenceId.isNotBlank() }
            .flatMap { fresh ->
                rows.values.filter {
                    it.id != fresh.id && it.tool == fresh.tool && it.referenceId == fresh.referenceId &&
                        it.unit == fresh.unit && !it.messageRead
                }
            }
            .map { it.id }
        flip(superseded)
        storage.upsert(records)
        records.filter { it.projectId == projectId }.forEach { rows[it.id] = it }
        publish()
    }

    /** Applies a `notification:silent` instruction, as the phones apply it to their ledgers. */
    suspend fun silence(silence: BadgeSilence, ownDeviceId: String? = null) = lock.withLock {
        // Android drops a frame that names this device as its origin (8625-8637).
        if (silence.selfDeviceId != null && silence.selfDeviceId == ownDeviceId) return@withLock
        flip(rows.values.filter { !it.messageRead && silence.matches(it) }.map { it.id })
        publish()
    }

    /**
     * A read made here, or relayed from another of this person's devices.
     * Answers how many rows it turned — the line that says a badge moved.
     *
     * A read that turned nothing announces nothing. The chat listing replays
     * its read marks after every refresh ([LedgerRead.ConversationsRead]),
     * and [changes] is what makes it refresh: publishing a read that moved no
     * row would answer its own signal for ever.
     */
    suspend fun markRead(read: LedgerRead): Int = lock.withLock {
        val ids = rows.values.filter { !it.messageRead && read.matches(it) }.map { it.id }
        if (ids.isEmpty()) return@withLock 0
        flip(ids)
        publish()
        ids.size
    }

    /** `badges:cleared:user` — every row of one production is gone. */
    suspend fun clearProject(projectId: String) = lock.withLock {
        storage.deleteProject(projectId)
        if (projectId == this.projectId) {
            rows.clear()
            publish()
        }
    }

    /** `badges:cleared:device` — every row of every production. */
    suspend fun clearEverything() = lock.withLock {
        storage.deleteAll()
        rows.clear()
        publish()
    }

    /** One screen's split of the open production's rows. */
    fun split(query: BadgeDrilldownQuery): Map<String, Int> = splitBadges(shown(), query)

    /**
     * The open production's rows of one section that still count — what a
     * badge is made of. [everyMailbox] adds the mail rows held back for a
     * mailbox this desktop cannot show, for the line that explains a count.
     */
    fun unreadRows(section: String, everyMailbox: Boolean = false): List<NotificationRecord> =
        (if (everyMailbox) rows.values.toList() else shown()).filter { it.section == section && it.counts }

    /**
     * The open production's rows of one section, as wire rows — what the
     * chat listing folds into per-conversation counts, the way it folds the
     * server's own backlog page.
     */
    fun wireRows(section: String): JsonArray =
        JsonArray(shown().filter { it.section == section }.mapNotNull { it.asWireRow() })

    /** The open production's rows this desktop may count — see [isShown]. */
    private fun shown(): List<NotificationRecord> = rows.values.filter { it.isShown(mailboxes, chatRooms) }

    private fun flip(ids: List<String>) {
        if (ids.isEmpty()) return
        storage.markRead(ids)
        ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(messageRead = true) } }
    }

    private fun publish() {
        val counts = tallyBadges(shown())
        if (counts != state.value) {
            ZillitLog.d(TAG) { "ledger ${rows.size} rows → sections=${counts.sectionMap()} tools=${counts.toolMap()}" }
        }
        state.value = counts
        changed.tryEmit(Unit)
    }

    /** What one seed did — for the log line that says where a badge came from. */
    data class SeedOutcome(val rows: Int, val unread: Int)

    private companion object {
        const val TAG = "Badges"
        const val DOCUMENT_DISTRIBUTION = "document_distribution_label"
    }
}

/**
 * Whether this desktop may count one row at all: the mail it can open
 * ([BadgeStore.showMailboxes]) and the rooms it can still enter
 * ([BadgeStore.showChatRooms]).
 *
 * The one scope, run by the open production's counts and by the picker's
 * cards alike — two filters over the same rows is how the card and the rail
 * came to disagree.
 */
private fun NotificationRecord.isShown(
    mailboxes: Map<String, Set<String>>,
    chatRooms: Map<String, Set<String>>,
): Boolean = isOfMailboxes(mailboxes[projectId]) && isOfChatRooms(chatRooms[projectId])
