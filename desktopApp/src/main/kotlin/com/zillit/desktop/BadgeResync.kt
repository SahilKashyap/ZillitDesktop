package com.zillit.desktop

import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import java.util.UUID

/**
 * Every launch of the app — an update's restart included — the badges start
 * over from the server, once.
 *
 * **The ledger is fetched again from the top.** The first seed of the run
 * asks for the newest page instead of only what changed since the watermark
 * (`NotificationLedgerSeeder.resyncDue`), so whatever a new version counts or
 * reads differently, and whatever moved while the app was closed, is
 * refreshed in one pass. Reads made on this computer survive it — the ledger
 * keeps a row read here read whatever the page says.
 *
 * The production listing's cards are the ledger's own counts throughout, and
 * nothing else — bare until that first page has landed, as on the phones.
 * They used to wear the server's `GET device/unread` totals meanwhile, and
 * again whenever the ledger held no page at all; that endpoint keeps rows
 * every client has long read or the server itself no longer lists, so a card
 * wore a number the listing could not back (four on one production whose
 * resync page came back empty, 2026-10-17), lost it the moment the page
 * landed, and got it back on every return to the listing. No client the
 * phones share asks that endpoint, and now neither does this one.
 *
 * "Not yet resynced" is the stored token ([ZillitPreferences.BadgeResyncedLaunch])
 * differing from this run's [launch]: every start of the app is due once, and
 * so is a fresh sign-in (the key is user-scoped and cleared at sign-out). A
 * resync that fails stays due, and the next seed — a reconnect, a window
 * coming to the front, the minute tick — tries again.
 *
 * ## The prune, once per installed version
 *
 * A resync refreshes what the server still has; it cannot remove what the
 * server has forgotten, because a seed is additive and a row's absence from
 * one page proves nothing. So a row the server dropped without ever telling
 * this device — a `badges:cleared:*` frame emitted while the app was closed,
 * a room deleted, a read nobody relayed — badges a screen for ever.
 *
 * The first seed of a newly installed version therefore also prunes: inside
 * the span its page covers, the page is the server's whole answer, and our
 * unread rows that it did not carry are retired
 * ([BadgeStore.pruneUnserved]). Gated on the installed version
 * ([ZillitPreferences.BadgeResyncedVersion]) rather than on the run, so an
 * ordinary restart does not spend the extra pass — and, like the resync, a
 * fresh sign-in is due one because the key is user-scoped.
 */
class BadgeResync(
    private val preferences: PreferenceStore,
    /**
     * The installed build's version — `installedAppVersion()`. Blank disables
     * the prune, which is what a build that cannot name itself should do
     * rather than prune on every launch.
     */
    private val installedVersion: String = "",
    /** This run of the app; a new one each start. */
    private val launch: String = UUID.randomUUID().toString(),
) {
    /** Whether the next seed should start over. */
    suspend fun due(): Boolean =
        runCatching { preferences.get(ZillitPreferences.BadgeResyncedLaunch) != launch }.getOrDefault(false)

    /**
     * Whether this installed version has yet to prune. Blank [installedVersion]
     * is never due: a build with no version would prune on every launch.
     */
    suspend fun pruneDue(): Boolean = installedVersion.isNotBlank() &&
        runCatching {
            preferences.get(ZillitPreferences.BadgeResyncedVersion) != installedVersion
        }.getOrDefault(false)

    /**
     * The prune ran against a page: this version is done.
     *
     * Recorded only once a page has actually been judged — a seed that failed,
     * or answered nothing, leaves the version due so the next seed tries again.
     */
    suspend fun pruned() {
        runCatching { preferences.set(ZillitPreferences.BadgeResyncedVersion, installedVersion) }
    }

    /** The resync answered: this run is remembered as resynced. */
    suspend fun completed() {
        runCatching { preferences.set(ZillitPreferences.BadgeResyncedLaunch, launch) }
    }
}
