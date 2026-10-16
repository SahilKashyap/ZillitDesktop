package com.zillit.desktop.core.badges

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
@Suppress("TooManyFunctions") // One operation per thing the ledger does; they share one mutex and cannot be split.
class BadgeStore(
    private val storage: NotificationLedgerStore = InMemoryNotificationLedgerStore(),
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** Where the whole-ledger read in [projectCounts] runs; tests pass their own. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

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

    /** Messages this person read elsewhere, for rows that arrive after the read — see [ReadEchoes]. */
    private val readEchoes = ReadEchoes(nowMillis)

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
        readEchoes.clear()
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
     *
     * Suspending, and on [dispatcher]: this is the one read here that is not
     * scoped to a production. It selects every unread row the ledger holds, of
     * every production, and folds them — so it costs more the more productions
     * the device has been in. It used to run wherever it was called, which
     * included the production listing's badge collector on the UI thread, on
     * every ledger publish; a switch publishes several (the ledger swap, the
     * seed, each socket frame), so choosing a production ran that whole scan
     * two or three times over on the thread that had to draw the next frame.
     */
    suspend fun projectCounts(deviceId: String? = null): Map<String, Int> = withContext(dispatcher) {
        val own = deviceId?.takeIf { it.isNotBlank() }?.let { storage.unreadAcrossProductions(it) }.orEmpty()
        val rows = own.ifEmpty { storage.unreadAcrossProductions("") }
        tallyByProject(rows.filter { it.isShown(mailboxes, chatRooms) })
            .mapValues { (_, counts) -> counts.total }
            .filterValues { it > 0 }
    }

    /**
     * Whether the ledger has ever been given a page — the watermark standing
     * above zero.
     *
     * The question a fallback to the server's own totals has to ask. "No cards
     * have a number on them" and "no rows have arrived yet" are the same shape
     * coming out of [projectCounts], and they mean opposite things: the first
     * is this ledger's answer and the second is its silence. Only the silence
     * is the server's to fill.
     */
    fun hasSeededRows(): Boolean = storage.watermark("") > 0L

    /** The seed watermark for [projectId] — see [NotificationLedgerStore.watermark]. */
    fun watermark(projectId: String): Long = storage.watermark(projectId)

    /**
     * The oldest `updated` among the unread rows of every production — how far
     * back a prune has to reach to have judged all of them. 0 when nothing is
     * unread, which is a prune with nothing to do.
     */
    val oldestUnreadUpdated: Long
        get() = storage.unreadAcrossProductions().minOfOrNull { it.updated } ?: 0L

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
            if (readHere) incoming.copy(messageRead = true) else readEchoes.applyTo(incoming)
        }
        storage.upsert(merged)
        merged.filter { it.projectId == projectId }.forEach { rows[it.id] = it }
        publish()
        SeedOutcome(rows = merged.size, unread = merged.count { it.counts })
    }

    /**
     * Retires the unread rows a resync page proves the server no longer
     * serves — the first seed after an update ([NotificationLedgerSeeder],
     * gated by `BadgeResync.pruneDue`).
     *
     * **Why a page's silence can be trusted here and nowhere else.** A seed is
     * otherwise additive, and rightly: a row missing from one page means
     * nothing, because the page is a window. But the listing is contiguous by
     * `updated`, so inside the span the page covers — from its oldest row's
     * `updated` up to the moment it was asked for — the page IS the server's
     * whole answer. An unread row of ours sitting in that span that the page
     * did not carry is one the server has dropped or read elsewhere: nothing
     * will ever flip it, and it badges a screen that can never clear it.
     * Outside the span nothing is concluded, which is why this takes a window
     * rather than a cutoff someone has to guess.
     *
     * Reads are untouched: only unread rows are considered, so every local
     * read survives, and with them the watermark and the "local read wins"
     * rule the whole ledger rests on.
     *
     * @param coveredFrom the oldest `updated` the page carried.
     * @param askedAt when the page was requested — rows updated after it are
     *   out of its reach (a `notification:save` that landed mid-request),
     *   so they are never judged by it.
     * @param carried the ids the page carried.
     * @return how many rows were retired.
     */
    suspend fun pruneUnserved(coveredFrom: Long, askedAt: Long, carried: Set<String>): Int = lock.withLock {
        val stale = storage.unreadAcrossProductions()
            .filter { it.updated in coveredFrom..askedAt && it.id !in carried }
        if (stale.isEmpty()) return@withLock 0
        val ids = stale.map { it.id }
        storage.delete(ids)
        ids.forEach(rows::remove)
        ZillitLog.i(TAG) {
            "update prune: retired ${ids.size} unread rows the listing no longer serves " +
                "(span $coveredFrom..$askedAt) " +
                stale.take(PRUNE_LOG_ROWS).joinToString(prefix = "[", postfix = "]") {
                    "${it.section}/${it.tool}/${it.unit}@${it.updated}"
                }
        }
        publish()
        ids.size
    }

    /**
     * Rows a `notification:save` frame carried. Kept for every production
     * (the socket is per device), counted for the open one. A Document
     * Distribution row supersedes an earlier unread row for the same entity
     * rather than stacking on it (Android `insertOrUpdate` 118-139).
     */
    suspend fun arrived(incoming: Collection<NotificationRecord>) = lock.withLock {
        if (incoming.isEmpty()) return@withLock
        val records = incoming.map(readEchoes::applyTo)
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

    /**
     * Applies a `notification:silent` instruction, as the phones apply it to
     * their ledgers — across the WHOLE ledger, not only the open production.
     *
     * Android prunes Realm with no project filter at all
     * (`AppBadgeDBManager` 1772-1967), and here it matters twice over: the
     * production picker counts every production's rows, and between
     * productions there is no open one, so a prune that reached only
     * `rows` reached nothing. Measured on the listing before this: an arrival
     * moved a card in ~60 ms while a read made on a phone took 20-30 s,
     * because only the next seeded page brought it down.
     *
     * A row deleted or read elsewhere is not this device's badge whichever
     * production it belongs to, so the wider scope is also the more correct
     * one. Ids and room keys are server-unique; a screen read ([markRead])
     * stays with the open production, because its tool and unit labels are
     * not.
     */
    suspend fun silence(silence: BadgeSilence, ownDeviceId: String? = null) = lock.withLock {
        // Android drops a frame that names this device as its origin (8625-8637).
        if (silence.selfDeviceId != null && silence.selfDeviceId == ownDeviceId) return@withLock
        // Before the flip: the rows this frame reads may not have arrived yet.
        readEchoes.remember(silence)
        val ids = storage.unreadAcrossProductions().filter { silence.matches(it) }.map { it.id }
        if (ids.isEmpty()) return@withLock
        flip(ids)
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

    /**
     * `badges:cleared:user` — every unread row of one production written up to
     * now is read (iOS `clearOutPendingNotificationsByProjectID`).
     *
     * Read, not deleted: a deleted row is a row this ledger no longer knows it
     * read, so the next page that carries it unread would badge it again —
     * and with the newest rows gone the seed's watermark falls back and asks
     * for exactly those pages.
     */
    suspend fun clearProject(projectId: String) = lock.withLock {
        val cutoff = nowMillis()
        val ids = storage.rows(projectId).filter { !it.messageRead && it.created <= cutoff }.map { it.id }
        if (ids.isEmpty()) return@withLock
        flip(ids)
        if (projectId == this.projectId) publish()
    }

    /**
     * **Temporary (one build only — remove with `sweepStuckBadges`).** Every
     * unread row the ledger holds, of every production, flipped read.
     *
     * Read, not deleted, and that is the whole point: a delete would be
     * undone by the next page that carries the row unread, while a read made
     * here is what the seed keeps ("local read wins",
     * `insertOrUpdateFromApi`). The watermark is left standing for the same
     * reason — dropping the newest rows would make the next seed ask for
     * exactly the pages that stuck.
     *
     * Not `clearEverything`: that answers `badges:cleared:device`, deletes,
     * and belongs to the server's frame. This is a local sweep for rows no
     * screen can reach — a dead room, a retired tool — and announces nothing
     * to anybody.
     *
     * @return how many rows it turned.
     */
    suspend fun markEveryRowRead(): Int = lock.withLock {
        val ids = storage.unreadAcrossProductions().map { it.id }
        if (ids.isEmpty()) return@withLock 0
        flip(ids)
        publish()
        ids.size
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

        /** How many retired rows the prune line names before it gets unreadable. */
        const val PRUNE_LOG_ROWS = 10
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
