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
 */
class BadgeResync(
    private val preferences: PreferenceStore,
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
