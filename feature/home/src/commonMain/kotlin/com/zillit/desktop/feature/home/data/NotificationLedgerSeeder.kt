package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.badges.BadgeStore
import com.zillit.desktop.core.badges.NotificationRecord
import com.zillit.desktop.core.badges.notificationRecordFrom
import com.zillit.desktop.core.badges.wireRecords
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * `GET notification/project/all/notifications/{cursor}/{previous|next}` —
 * the rows the ledger has not seen.
 *
 * iOS's `manageLocalNotification`: a ledger that is empty asks for the page
 * before *now*; one that has rows asks for everything updated since the
 * newest row the API ever gave it — one watermark for the device
 * (`NotificationRealmDateModel`, keyed by device), because the listing is the
 * person's, across every production.
 * The ledger is never re-read whole — the phones' rule, and the reason a row
 * read here stays read: the server keeps saying "unread" for rows every
 * phone dropped long ago.
 *
 * On the notification host, not the core one: badges are their own service.
 */
class NotificationLedgerSeeder(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val store: BadgeStore,
    private val myUserId: () -> String? = { null },
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /**
     * Whether the next seed must start over — the newest page, whatever the
     * watermark says — and [onResynced] once it has answered. The host's
     * answer is "the first seed of each launch" (`BadgeResync`): whatever
     * moved while the app was closed, or a new version reads differently, is
     * refreshed from the server in one page, while the ledger keeps every
     * read made here.
     */
    private val resyncDue: suspend () -> Boolean = { false },
    private val onResynced: suspend () -> Unit = {},
    /**
     * Whether this seed must also retire the unread rows its page proves the
     * server no longer serves ([BadgeStore.pruneUnserved]), and [onPruned]
     * once a page has been judged. The host's answer is "the first seed of a
     * newly installed version" (`BadgeResync.pruneDue`) — a resync alone
     * cannot clear a row the server has forgotten, because a seed only ever
     * adds.
     */
    private val pruneDue: suspend () -> Boolean = { false },
    private val onPruned: suspend () -> Unit = {},
) {

    /**
     * One seed at a time: a production open and a socket connect both ask
     * within the same second, and two asks in flight both read a zero
     * watermark and both fetch the whole page (seen live 2026-09-07). The
     * second now waits, sees the first's rows, and asks for `next`.
     */
    private val inFlight = Mutex()

    /**
     * @param userId this person's id *in* [projectId], for a seed made while no
     *   production is open — Android's foreground fetch on the picker, which
     *   uses the last production's headers. The listing is user-wide, so any
     *   production's headers fill every production's rows. Null uses the
     *   open production's headers.
     */
    suspend fun seed(projectId: String, userId: String? = null): ZillitResult<BadgeStore.SeedOutcome> =
        inFlight.withLock {
        // The listing is user-wide, so the watermark is the ledger's newest
        // row of any production. Keyed on the open production (Android's
        // rule) it read 0 for a production with no rows yet and fetched the
        // whole page again, and an old one re-fetched every other
        // production's rows since.
        val watermark = store.watermark("")
        val resync = resyncDue()
        // A prune wants the widest span it can get, so it asks from the top
        // too — its judgement only reaches as far back as the page it is given.
        val prune = pruneDue()
        val askedAt = nowMillis()
        val (cursor, direction) =
            if (resync || prune || watermark <= 0L) askedAt to PREVIOUS else watermark to NEXT

        val first = fetch(projectId, userId, cursor, direction, watermark, announceResync = resync)
        if (!prune) return first.map { it.outcome }
        val landed = (first as? ZillitResult.Success)?.data ?: return first.map { it.outcome }
        // A listing that answered nothing at all proves nothing. An endpoint
        // having a bad minute must not cost every badge on the machine, so the
        // version stays due and the next seed — a reconnect, the minute tick —
        // tries again.
        if (landed.records.isNotEmpty()) walkBackAndPrune(projectId, userId, askedAt, landed.records)
        return first.map { it.outcome }
    }

    /**
     * Retires the unread rows no page carried, having first walked the listing
     * back far enough to be entitled to say so.
     *
     * One page only proves what sits inside it, and a stuck row is usually
     * *older* than the newest page — the server dropped it long ago, so nothing
     * has moved it since. The walk therefore keeps asking `previous` until
     * either the span reaches the oldest unread row this ledger holds, or the
     * listing runs out: an empty page means there is nothing older to have,
     * which is the strongest answer available and the one that clears a years-old
     * orphan. [MAX_PRUNE_PAGES] bounds the cost; short of it, the prune simply
     * judges the span it did cover.
     *
     * Every page it walks is seeded like any other, so the walk also refreshes
     * what it passes.
     */
    private suspend fun walkBackAndPrune(
        projectId: String,
        userId: String?,
        askedAt: Long,
        firstPage: List<NotificationRecord>,
    ) {
        val oldestHeld = store.oldestUnreadUpdated
        if (oldestHeld <= 0L) {
            // Nothing unread to judge. The version is done rather than left to
            // ask again on every launch for a ledger with no badges in it.
            onPruned()
            return
        }
        val carried = firstPage.mapTo(mutableSetOf()) { it.id }
        var cursor = firstPage.minOf { it.updated }
        // Strictly inside the page: rows sharing the oldest row's stamp may sit
        // just past the page's edge, and those are not ours to judge.
        var coveredFrom = cursor + 1
        var exhausted = false
        var failed = false
        var walking = true
        var pages = 1
        while (walking && coveredFrom > oldestHeld && pages < MAX_PRUNE_PAGES) {
            val page = (fetch(projectId, userId, cursor, PREVIOUS, watermark = 0L) as? ZillitResult.Success)?.data
            pages++
            val records = page?.records.orEmpty()
            failed = page == null
            // An empty page is the listing's floor: there is nothing older to
            // have, so everything older than what we carried is unserved.
            exhausted = !failed && records.isEmpty()
            walking = !failed && !exhausted
            if (walking) {
                val fresh = records.count { carried.add(it.id) }
                val oldest = records.minOf { it.updated }
                coveredFrom = minOf(coveredFrom, oldest + 1)
                // Nothing new, or a cursor that will not move: the listing has
                // no more to say, and asking again would repeat this page.
                walking = fresh > 0 && oldest < cursor
                cursor = oldest
            }
        }
        val retired = store.pruneUnserved(
            coveredFrom = if (exhausted) 0L else coveredFrom,
            askedAt = askedAt,
            carried = carried,
        )
        ZillitLog.i(TAG) {
            "update prune: $pages page(s), " +
                (if (exhausted) "the whole listing" else "back to $coveredFrom") +
                ", oldest unread held $oldestHeld, retired $retired" +
                if (failed) " (a page failed: this version stays due)" else ""
        }
        // What it covered was judged honestly, but a walk cut short by a failed
        // page leaves the version due, so a later launch reaches further.
        if (!failed) onPruned()
    }

    /** One page of the listing, seeded as it lands. */
    private suspend fun fetch(
        projectId: String,
        userId: String?,
        cursor: Long,
        direction: String,
        watermark: Long,
        announceResync: Boolean = false,
    ): ZillitResult<Page> = apiClient.request(
        verb = HttpVerb.Get,
        url = "${config.apiV2(ZillitService.Notification)}project/all/notifications/$cursor/$direction",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        options = if (userId == null) CallOptions() else CallOptions(projectId = projectId, userId = userId),
    ).map { payload ->
        val records = payload.wireRecords()
            .mapNotNull { notificationRecordFrom(it, fromApi = true, fallbackProjectId = projectId) }
        // Before the rows land: their publish is what repaints the
        // listing's cards, and those must stop showing the server's totals
        // by then.
        if (announceResync) onResynced()
        val outcome = store.seed(records)
        val me = myUserId()
        // The one line that says where a badge came from — read it before
        // believing a count. `mine` is how many rows name this person as
        // receiver; the phones count every row regardless.
        ZillitLog.d(TAG) {
            "ledger seed $direction $cursor: rows=${records.size} " +
                "productions=${records.distinctBy { it.projectId }.size} " +
                "devices=${records.distinctBy { it.deviceId }.size} " +
                "perProduction=${records.filter { it.counts }.groupingBy { it.projectId }.eachCount()} " +
                "here=${records.count { it.projectId == projectId }} " +
                "mine=${records.count { it.receiver == me }} unread=${outcome.unread} " +
                "newest=${records.maxOfOrNull { it.updated } ?: 0L} watermark=$watermark" +
                if (announceResync) " (resync at launch)" else ""
        }
        Page(records, outcome)
    }

    private data class Page(val records: List<NotificationRecord>, val outcome: BadgeStore.SeedOutcome)

    private companion object {
        const val TAG = "Badges"
        const val PREVIOUS = "previous"
        const val NEXT = "next"

        /**
         * How far the prune will walk. Ten pages of the listing reach years
         * back on any real account; past that the span it covered is judged
         * and the rest is left alone.
         */
        const val MAX_PRUNE_PAGES = 10
    }
}
