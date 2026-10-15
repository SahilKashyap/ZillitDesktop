package com.zillit.desktop

import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import java.util.UUID

/**
 * Every launch of the app — an update's restart included — the badges start
 * over from the server, once.
 *
 * Two halves, asked for together:
 *
 * - **The ledger is fetched again from the top.** The first seed of the run
 *   asks for the newest page instead of only what changed since the watermark
 *   (`NotificationLedgerSeeder.resyncDue`), so whatever a new version counts or
 *   reads differently, and whatever moved while the app was closed, is
 *   refreshed in one pass. Reads made on this computer survive it — the ledger
 *   keeps a row read here read whatever the page says.
 * - **The production listing shows the server's totals meanwhile.**
 *   `GET device/unread` answers in one call, before the page has landed; the
 *   cards wear [serverCards] until the resync completes, then the ledger's own
 *   counts again. Those totals run high — the server keeps rows every phone has
 *   long read or pruned — so a card may read higher for a moment at launch.
 *
 * "Not yet resynced" is the stored token ([ZillitPreferences.BadgeResyncedLaunch])
 * differing from this run's [launch]: every start of the app is due once, and
 * so is a fresh sign-in (the key is user-scoped and cleared at sign-out) —
 * though a second sign-in within one run resyncs without the server's totals
 * on its cards, since those are kept from coming back once a resync has
 * landed. A resync that fails stays due, and the next seed — a reconnect, a
 * window coming to the front, the minute tick — tries again.
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
    private val lock = Any()

    /**
     * Set once a resync has landed in this run: a [showServerCards] racing it
     * must not put the server's totals back on the cards for good.
     */
    private var done = false

    /** The server's per-production totals, shown on the listing's cards until the resync lands. */
    @Volatile
    var serverCards: Map<String, Int>? = null
        private set

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

    /** Puts the server's totals on the cards — unless the resync has already landed. */
    fun showServerCards(cards: Map<String, Int>) = synchronized(lock) {
        if (!done) serverCards = cards.takeIf { it.isNotEmpty() }
    }

    /** The resync answered: the cards go back to the ledger, and this run is remembered as resynced. */
    suspend fun completed() {
        synchronized(lock) {
            done = true
            serverCards = null
        }
        runCatching { preferences.set(ZillitPreferences.BadgeResyncedLaunch, launch) }
    }
}
