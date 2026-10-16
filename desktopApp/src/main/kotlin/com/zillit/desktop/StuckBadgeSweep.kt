package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.datastore.ZillitPreferences

/**
 * **Temporary — one build only.** Clears unread rows nothing on this machine
 * can reach.
 *
 * ## What it is for
 *
 * A row the server still serves as unread, whose screen is gone — a chat room
 * deleted, a tool a licence no longer grants, a unit retired — badges the rail
 * for ever. Nothing the person can do clears it: a restart re-seeds it, an
 * update re-seeds it, and a sign-out wipes the ledger only for the next seed
 * to fetch it straight back ([LocalCacheWiper] empties the table; the server's
 * own ledger is untouched by it).
 *
 * The standing repair for this is [BadgeStore.pruneUnserved], run once per
 * installed version: inside the span a resync page covers, the page is the
 * server's whole answer, so an unread row of ours it did not carry has been
 * dropped. That cannot reach a row the server *does* still serve, which is
 * what is left here.
 *
 * ## What it does
 *
 * After the first seed of the run — so the stuck rows are in the ledger to be
 * found — every unread row of every production is flipped read
 * ([BadgeStore.markEveryRowRead]). Read, not deleted: a read made locally is
 * the one thing a seed will not overturn, so the badge stays down across every
 * later page and relaunch. Nothing is announced — no `notification:read` goes
 * out and no `badges:cleared:device` is faked — so this device's badges are
 * repaired and the phones' are left alone.
 *
 * ## What it costs
 *
 * Badges that arrived while the app was closed are cleared along with the
 * stuck ones, once, on the first launch of this build. That is why it is gated
 * on the installed version ([ZillitPreferences.BadgeSweptVersion]) and not on
 * the run: a sweep per launch would eat every badge earned overnight.
 *
 * Rows that reach the ledger later in that same run are ordinary new badges
 * and are left alone — a stuck row that only turns up on a later page will
 * therefore survive, and `-Dzillit.badges.sweep=1` forces the sweep on the
 * next launch for that case.
 *
 * ## Removing it
 *
 * Delete this file, its call in `main.kt`, [BadgeStore.markEveryRowRead], its
 * test, and [ZillitPreferences.BadgeSweptVersion]. The stored key is harmless
 * if left behind. The real fix belongs server-side — the notification service
 * dropping rows whose room, tool or unit is gone, as every client's silent
 * handler already does locally.
 */
internal suspend fun sweepStuckBadges(ready: AppGraph.Ready) {
    val version = installedAppVersion()
    val forced = System.getProperty(SWEEP_PROPERTY).equals("1", ignoreCase = true)
    // A build that cannot name itself would sweep on every launch; only the
    // explicit property may do that.
    if (!forced && version.isBlank()) return
    val swept = runCatching { ready.preferences.get(ZillitPreferences.BadgeSweptVersion) }.getOrNull()
    if (!forced && swept == version) return

    val turned = ready.badgeStore.markEveryRowRead()
    // An empty ledger has not been repaired, it has not been seen: the seed
    // may have failed, or no production has been opened yet. Leaving the
    // version unrecorded lets the next launch try again, which is the whole
    // value of the pass.
    if (turned == 0) {
        ZillitLog.i(TAG) { "stuck-badge sweep found no unread rows; staying armed for $version" }
        return
    }
    ZillitLog.i(TAG) { "stuck-badge sweep: $turned unread rows flipped read locally (build $version)" }
    runCatching { ready.preferences.set(ZillitPreferences.BadgeSweptVersion, version) }
        .onFailure { ZillitLog.w(TAG) { "sweep ran but was not recorded: ${it::class.simpleName}" } }
}

/** Forces the sweep on the next launch whatever the stored version says. */
private const val SWEEP_PROPERTY = "zillit.badges.sweep"

private const val TAG = "Badges"
