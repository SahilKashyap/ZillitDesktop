package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.badges.BadgeStore
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
     * answer is "the first launch after an update" (`BadgeResync`): rows a new
     * version reads differently are refreshed from the server in one page,
     * while the ledger keeps every read made here.
     */
    private val resyncDue: suspend () -> Boolean = { false },
    private val onResynced: suspend () -> Unit = {},
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
        val (cursor, direction) = if (resync || watermark <= 0L) nowMillis() to PREVIOUS else watermark to NEXT
        return apiClient.request(
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
            if (resync) onResynced()
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
                    if (resync) " (resync after an update)" else ""
            }
            outcome
        }
    }

    private companion object {
        const val TAG = "Badges"
        const val PREVIOUS = "previous"
        const val NEXT = "next"
    }
}
