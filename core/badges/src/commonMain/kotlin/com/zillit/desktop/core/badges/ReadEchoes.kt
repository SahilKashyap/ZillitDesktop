package com.zillit.desktop.core.badges

/**
 * Messages this person read on another device, as `project|reference id` →
 * when the mark lapses — iOS's remote chat read marks (ZL-21764,
 * `LocalNoticifationRealm.swift` `recordRemoteChatReads` / `isReadRemotely`).
 *
 * The read echo and the message's own `notification:save` travel by
 * independent server pipelines, and the echo can win: an open web chat reads
 * each message as it lands, and the `deleted_chat_ids` echo can reach this
 * socket before the row it reads does. The silence then finds nothing to
 * flip, the row arrives after it, and is filed unread for good — the "badge
 * stuck once read on the web" report. Remembered for [TTL_MILLIS], at most
 * [CAP] of them, in memory only: after a relaunch the rows come from the API
 * with the server's own `message_read`.
 *
 * Not thread-safe on its own; [BadgeStore] calls it under its lock.
 */
internal class ReadEchoes(private val nowMillis: () -> Long) {

    private val lapses = mutableMapOf<String, Long>()

    /** Remembers what one `notification:silent` frame says was read — [BadgeSilence.readEchoIds]. */
    fun remember(silence: BadgeSilence) {
        val project = silence.projectId?.takeIf { it.isNotBlank() } ?: return
        val ids = silence.readEchoIds
        if (ids.isEmpty()) return
        val now = nowMillis()
        ids.forEach { lapses[key(project, it)] = now + TTL_MILLIS }
        if (lapses.size <= CAP) return
        lapses.entries.removeAll { it.value <= now }
        val overflow = lapses.size - CAP
        if (overflow > 0) {
            lapses.entries.sortedBy { it.value }.take(overflow).map { it.key }.forEach(lapses::remove)
        }
    }

    /** The row, filed read when a live mark names it — the insert half of iOS's order-proofing. */
    fun applyTo(row: NotificationRecord): NotificationRecord {
        if (row.messageRead || row.referenceId.isBlank() || lapses.isEmpty()) return row
        val lapse = lapses[key(row.projectId, row.referenceId)] ?: return row
        return if (lapse > nowMillis()) row.copy(messageRead = true) else row
    }

    /** Sign-out — the marks are this person's. */
    fun clear() = lapses.clear()

    private fun key(projectId: String, referenceId: String) = "$projectId|$referenceId"

    private companion object {
        /** iOS `remoteChatReadTTL` — long enough for any save the echo outran. */
        const val TTL_MILLIS = 5 * 60 * 1000L

        /** iOS `remoteChatReadCap`. */
        const val CAP = 2048
    }
}
