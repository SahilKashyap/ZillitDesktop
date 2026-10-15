package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * `/api/v2/email-export` — asking for a copy of a mailbox, watching it being
 * built, and fetching the links once it is.
 *
 * Five routes, none of which takes a request body: whose mailbox is being
 * exported is said with a query parameter
 * (`use_project_account_mailbox=true`), which is why the shared-mailbox flag
 * cannot ride a body here the way the rest of the mail service accepts it.
 */
interface MailboxExportRepository {

    /**
     * `POST /` — start one.
     *
     * Only one export per mailbox runs at a time. Asking while one is running
     * is **not** a failure: the answer is still 200, carrying the running
     * export and `email_export_already_in_progress`
     * ([StartedMailboxExport.alreadyRunning]).
     */
    suspend fun start(): ZillitResult<StartedMailboxExport>

    /** `GET /` — this mailbox's exports, newest first, at most ten. */
    suspend fun exports(): ZillitResult<List<MailboxExport>>

    /** `GET /:exportId` — one export, for the fallback poll when the socket is down. */
    suspend fun export(exportId: String): ZillitResult<MailboxExport>

    /**
     * `GET /:exportId/download` — the signed links.
     *
     * Asked for on the click, never on load: the links live about an hour and
     * must not be kept. Refuses with `email_export_not_ready` while the
     * export is unfinished and `email_export_expired` once it is gone.
     */
    suspend fun downloadLinks(exportId: String): ZillitResult<MailboxExportDownload>

    /**
     * `DELETE /:exportId` — cancel a running export, or remove a finished one.
     *
     * Running becomes `cancelled`; completed becomes `expired` and its files
     * are deleted for good. An already-settled export is unchanged and still
     * answers 200.
     */
    suspend fun delete(exportId: String): ZillitResult<MailboxExport>

    companion object {
        /** The list route's own cap — the server returns no more than this. */
        const val HISTORY_LIMIT = 10
    }
}
