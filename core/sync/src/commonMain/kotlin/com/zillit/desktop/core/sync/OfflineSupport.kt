package com.zillit.desktop.core.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * What a feature needs to work without the network, in one handle.
 *
 * Handed to a view model as a nullable: null means "as before" — every write
 * goes straight to the server and a failure is a toast — which is what tests
 * and any module not yet ported get. The pieces are separate on purpose (an
 * engine without a draft store is meaningful) but they arrive together
 * because they are wired together.
 */
class OfflineSupport(
    val engine: SyncEngine,
    val drafts: DraftStore,
    /** True while the API host answers; see [ConnectivityMonitor]. */
    val online: StateFlow<Boolean>,
    /** Whose work this is right now — null when no production is open. */
    val currentScope: () -> SyncScope?,
) {
    val cache: ScopedJsonCache = ScopedJsonCache(drafts)

    val isOffline: Boolean get() = !online.value
}
