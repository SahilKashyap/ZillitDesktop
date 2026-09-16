package com.zillit.desktop.core.badges

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A tabbed tool's unread per tab, and the read a tab makes when it is
 * shown — the seam a tool screen takes from the host that owns the ledger.
 *
 * Keys are whatever the tool's rows group by on the wire — a unit
 * (`my_vouchers_label`), a `level_1` (`receipt_inbox`) — and a tab may be
 * made of more than one (the card approval queue is `card_approval_queue`
 * plus `receipt_approval_queue`; the screen adds them up). The read is by
 * key too: the phones' `markReadCommon(isLCW = true)` scoped to the tab,
 * sent as `notification:level:read` and applied to the ledger at once.
 *
 * Coarser than the web's per-row reads inside these tools, which clear one
 * receipt or claim as its dialog opens; a tab read here clears the tab as
 * it is looked at, the way Bank Reconciliation and the boards already do.
 */
interface TabBadgeSource {
    val counts: Flow<Map<String, Int>> get() = emptyFlow()

    fun read(key: String) {}

    companion object {
        val None: TabBadgeSource = object : TabBadgeSource {}
    }
}
