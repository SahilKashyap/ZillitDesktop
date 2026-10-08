package com.zillit.desktop.feature.documentdistribution.domain

import kotlin.random.Random

/**
 * Groups the requests of ONE upload action so the backend raises ONE
 * notification for it instead of one per folder and per file (the web's
 * `utils/uploadBatch.js`; a single folder upload once produced 400+).
 *
 * The agreed contract (backend mail, Oct 2026): every request of one user action
 * carries the same `upload_id`, plus `upload_total`, the number of requests the
 * action will send. The server announces the upload the moment `upload_total`
 * SUCCESSFUL requests have arrived under that id. Two rules follow:
 *
 *  - only successful requests count — a 4xx/5xx does not tick the server's
 *    counter, so a promised total that includes a failed item is never reached;
 *  - the latest `upload_total` wins — so when an item is dropped or lost, the
 *    requests that follow carry a smaller total and the notification still fires
 *    on the last one, not on the backend's 2-minute fallback.
 *
 * [drop] is therefore not bookkeeping; it is what keeps the notification prompt.
 * Requests without these fields keep the old time-based grouping, so sending
 * them is safe against a backend that has not shipped its half.
 */
class UploadBatch(total: Int, val id: String) {
    var total: Int = total.coerceAtLeast(0)
        private set

    /** [count] items will no longer be sent. Returns the new total. */
    fun drop(count: Int = 1): Int {
        total = (total - count).coerceAtLeast(0)
        return total
    }

    companion object {
        const val UPLOAD_ID_FIELD = "upload_id"
        const val UPLOAD_TOTAL_FIELD = "upload_total"

        /** One action, one batch. A later retry is a NEW action and needs a new one. */
        fun create(total: Int): UploadBatch = UploadBatch(total, randomId())

        private fun randomId(): String =
            List(ID_PARTS) { Random.nextLong().toULong().toString(HEX).padStart(ID_PART_WIDTH, '0') }.joinToString("-")

        private const val ID_PARTS = 2
        private const val ID_PART_WIDTH = 16
        private const val HEX = 16
    }
}
