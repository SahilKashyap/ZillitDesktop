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
 * Android's `BadgesHandler.getAllBadge`: a production whose ledger is empty
 * asks for the page before *now*; one that has rows asks for everything
 * updated since the newest row the API gave it (`AppBadgeDBManager.getTimeStamp`).
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
        // The picker's seed is user-wide, so its watermark is the ledger's
        // newest row of any production: keyed on one production it read 0
        // for a production with no rows and fetched the whole page again.
        val watermark = store.watermark(if (userId == null) projectId else "")
        val (cursor, direction) = if (watermark <= 0L) nowMillis() to PREVIOUS else watermark to NEXT
        return apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Notification)}project/all/notifications/$cursor/$direction",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            options = if (userId == null) CallOptions() else CallOptions(projectId = projectId, userId = userId),
        ).map { payload ->
            val records = payload.wireRecords()
                .mapNotNull { notificationRecordFrom(it, fromApi = true, fallbackProjectId = projectId) }
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
                    "newest=${records.maxOfOrNull { it.updated } ?: 0L} watermark=$watermark"
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
