package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * `/api/v2/chat-export` — asking for a copy of this production's chats,
 * watching it being built, and fetching the link once it is.
 *
 * Whose chats these are is said by the `moduledata` header, as on every
 * other C&C call: the production, the user and the device. Nothing here
 * takes a project or a user id.
 */
interface ChatExportRepository {

    /**
     * `POST /` — start one.
     *
     * Only one export per user per production runs at a time. Asking while
     * one is running is **not** a failure: the answer is still 200, carrying
     * the running export and `cnc_chat_export_already_in_progress`
     * ([StartedChatExport.alreadyRunning]).
     *
     * [includeMedia] false leaves the photos, clips and files out, for a
     * much smaller download.
     */
    suspend fun start(includeMedia: Boolean = true): ZillitResult<StartedChatExport>

    /** `GET /` — this user's exports, newest first, at most ten. */
    suspend fun exports(): ZillitResult<List<ChatExport>>

    /** `GET /:exportId` — one export, for the fallback poll when the socket is down. */
    suspend fun export(exportId: String): ZillitResult<ChatExport>

    /**
     * `GET /:exportId/download` — the signed link.
     *
     * Asked for on the click, never on load: it lives an hour and must not
     * be kept. Refuses with `cnc_chat_export_not_ready` while the export is
     * unfinished and `cnc_chat_export_expired` once it is gone.
     */
    suspend fun downloadLink(exportId: String): ZillitResult<ChatExportDownload>

    /**
     * `DELETE /:exportId` — cancel a running export, or remove a finished one.
     *
     * Running becomes `cancelled`; completed becomes `expired` and the ZIP is
     * deleted for good. An already-settled export is unchanged and still
     * answers 200.
     */
    suspend fun delete(exportId: String): ZillitResult<ChatExport>

    companion object {
        /** The list route's own cap — the server returns no more than this. */
        const val HISTORY_LIMIT = 10
    }
}
