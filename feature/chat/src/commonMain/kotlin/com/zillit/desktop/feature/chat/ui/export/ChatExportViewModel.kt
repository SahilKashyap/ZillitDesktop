package com.zillit.desktop.feature.chat.ui.export

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.data.ChatExportUpdate
import com.zillit.desktop.feature.chat.domain.ChatExport
import com.zillit.desktop.feature.chat.domain.ChatExportRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive

data class ChatExportUiState(
    /** Newest first, at most ten — the list route's own cap. */
    val exports: List<ChatExport> = emptyList(),
    /** What the next export will be asked for; the contract's own default is on. */
    val includeMedia: Boolean = true,
    val isLoading: Boolean = false,
    val isStarting: Boolean = false,
    /** The export whose Cancel or Remove is in flight. */
    val busyExportId: String = "",
    /** The export whose download link is being fetched. */
    val linkingExportId: String = "",
    /** A completed export the user has asked to remove, awaiting confirmation. */
    val pendingRemoval: ChatExport? = null,
    val error: String? = null,
    val info: String? = null,
) {
    /** The one the dialog leads with: the running export, else the newest. */
    val latest: ChatExport? get() = exports.firstOrNull { it.isInProgress } ?: exports.firstOrNull()

    /** The rest, shown as a short history under it. */
    val earlier: List<ChatExport> get() = exports.filter { it.exportId != latest?.exportId }

    /** One export per user per production — asking again while one runs does nothing new. */
    val canStart: Boolean get() = !isStarting && exports.none { it.isInProgress }
}

sealed interface ChatExportEvent {
    data object Load : ChatExportEvent

    /** The request dialog's checkbox. Ignored while an export is running. */
    data class IncludeMedia(val include: Boolean) : ChatExportEvent

    /** `POST /` — also the "Export again" of a failed, cancelled or expired one. */
    data object Start : ChatExportEvent

    /** `DELETE /:id` on a running export: it stops. No confirmation — nothing is lost. */
    data class Cancel(val exportId: String) : ChatExportEvent

    /** Asks first: removing a completed export deletes the ZIP for good. */
    data class AskRemove(val export: ChatExport) : ChatExportEvent
    data object ConfirmRemove : ChatExportEvent
    data object DismissRemove : ChatExportEvent

    /** `GET /:id/download`, then the browser. */
    data class Download(val exportId: String) : ChatExportEvent

    data object DismissMessage : ChatExportEvent
}

sealed interface ChatExportEffect {
    /**
     * Hand this link to the browser.
     *
     * A media export runs to hundreds of megabytes and S3 serves it as an
     * attachment under its own name, so it is never fetched into the app —
     * the same rule the Drive's download and the mailbox export follow.
     */
    data class OpenDownload(val url: String, val fileName: String) : ChatExportEffect
}

/**
 * Export chats — `/v2/chat-export`.
 *
 * The dialog is drawn from the list when it opens, kept current by the
 * socket, and polled only when the socket is down. Three rules out of the
 * contract that are easy to lose:
 *
 *  - **`queued` and `processing` are one state.** A temporary failure sends
 *    a running export back to `queued` and it retries within minutes; the
 *    counters can dip at that moment. None of that is an error and none of
 *    it is shown as one.
 *  - **The link is fetched on the click.** It lasts an hour. Nothing stores
 *    it and nothing puts it on screen on load, so a Download pressed a day
 *    later works exactly as the first one did.
 *  - **Removing a completed export cannot be undone**, only requested again,
 *    so it asks first. Cancelling a running one does not.
 */
class ChatExportViewModel(
    private val repository: ChatExportRepository,
    /**
     * `chat:export:updated` for the open production, already mapped and
     * already gated; null on a build with no socket, which leans on
     * [pollMillis] instead.
     */
    updates: Flow<ChatExportUpdate>? = null,
    /**
     * Whether the socket is up right now. The poll below is the fallback for
     * when it is not, so this is read at each tick rather than once.
     */
    private val isSocketConnected: () -> Boolean = { false },
    private val pollMillis: Long = POLL_MILLIS,
) : ZillitViewModel<ChatExportUiState, ChatExportEvent, ChatExportEffect>(ChatExportUiState()) {

    private var poll: Job? = null

    init {
        updates?.let { stream ->
            launch { stream.collect { update -> apply(update.export) } }
        }
    }

    /**
     * Stops the fallback poll.
     *
     * The loop below ends on its own as soon as the export settles, but
     * while one is being built it keeps a timer alive — so the host calls
     * this when the chat screen goes away, and a test calls it before it
     * finishes. (A `runTest` that leaves a self-rescheduling timer behind
     * never completes: draining the virtual clock keeps finding the next
     * tick.)
     */
    fun stopWatching() {
        poll?.cancel()
        poll = null
    }

    override fun onEvent(event: ChatExportEvent) {
        when (event) {
            ChatExportEvent.Load -> load()
            is ChatExportEvent.IncludeMedia -> setState { copy(includeMedia = event.include) }
            ChatExportEvent.Start -> start()
            is ChatExportEvent.Cancel -> stop(event.exportId)
            is ChatExportEvent.AskRemove -> setState { copy(pendingRemoval = event.export) }
            ChatExportEvent.ConfirmRemove ->
                currentState.pendingRemoval?.let { export ->
                    setState { copy(pendingRemoval = null) }
                    stop(export.exportId)
                }
            ChatExportEvent.DismissRemove -> setState { copy(pendingRemoval = null) }
            is ChatExportEvent.Download -> download(event.exportId)
            ChatExportEvent.DismissMessage -> setState { copy(error = null, info = null) }
        }
    }

    /**
     * The list, and the dialog's clean slate.
     *
     * Restores the screen mid-export: a running export is back with its
     * progress and a finished one with its Download, however long the
     * dialog was closed. The media checkbox is left alone — it is the
     * user's choice for the *next* export, not something the server owns.
     */
    private fun load() {
        stopWatching()
        setState {
            copy(
                isLoading = true,
                error = null,
                info = null,
                busyExportId = "",
                linkingExportId = "",
                pendingRemoval = null,
            )
        }
        launchResult(
            block = { repository.exports() },
            onSuccess = { rows ->
                setState { copy(isLoading = false, exports = rows) }
                watchProgress()
            },
            onError = { setState { copy(isLoading = false, error = it.localised()) } },
        )
    }

    private fun start() {
        if (!currentState.canStart) return
        val includeMedia = currentState.includeMedia
        setState { copy(isStarting = true, error = null, info = null) }
        launchResult(
            block = { repository.start(includeMedia) },
            onSuccess = { started ->
                setState {
                    copy(
                        isStarting = false,
                        // Already running is an answer, not a refusal: the
                        // running export is shown and the server's own words
                        // say why no second one started.
                        info = started.message.takeIf { it.isNotBlank() }?.localisedMessage(),
                    )
                }
                apply(started.export)
            },
            onError = { setState { copy(isStarting = false, error = it.localised()) } },
        )
    }

    /** Cancel a running export, or remove a finished one — the same route either way. */
    private fun stop(exportId: String) {
        if (currentState.busyExportId.isNotBlank()) return
        setState { copy(busyExportId = exportId, error = null, info = null) }
        launchResult(
            block = { repository.delete(exportId) },
            onSuccess = { export ->
                setState { copy(busyExportId = "") }
                apply(export)
            },
            onError = { setState { copy(busyExportId = "", error = it.localised()) } },
        )
    }

    /** Fetches a fresh link and hands it to the browser. */
    private fun download(exportId: String) {
        if (currentState.linkingExportId.isNotBlank()) return
        setState { copy(linkingExportId = exportId, error = null, info = null) }
        launchResult(
            block = { repository.downloadLink(exportId) },
            onSuccess = { link ->
                // Said here rather than left to the browser: the download
                // opens in a window this app does not own, and on a second
                // monitor nothing at all appears to happen.
                setState { copy(linkingExportId = "", info = str(S.desktop_email_export_download_started)) }
                sendEffect(ChatExportEffect.OpenDownload(link.url, link.fileName))
            },
            onError = { setState { copy(linkingExportId = "", error = it.localised()) } },
        )
    }

    /** Replaces the export with this id, or puts a new one at the front. */
    private fun apply(export: ChatExport) {
        setState {
            val known = exports.any { it.exportId == export.exportId }
            val next = if (known) {
                exports.map { if (it.exportId == export.exportId) export else it }
            } else {
                (listOf(export) + exports).take(ChatExportRepository.HISTORY_LIMIT)
            }
            copy(exports = next)
        }
        watchProgress()
    }

    /**
     * The fallback poll.
     *
     * Runs only while an export is unfinished, and asks nothing while the
     * socket is up — the pushes are better than a poll, and one already
     * arrives every 30 seconds. One loop at a time.
     */
    private fun watchProgress() {
        val running = currentState.exports.firstOrNull { it.isInProgress }
        if (running == null) {
            stopWatching()
            return
        }
        if (poll?.isActive == true) return
        poll = launch {
            while (isActive) {
                delay(pollMillis)
                val watched = currentState.exports.firstOrNull { it.isInProgress } ?: break
                if (!isSocketConnected()) {
                    val answer = repository.export(watched.exportId)
                    (answer as? ZillitResult.Success)?.data?.let { fresh ->
                        setState {
                            copy(exports = exports.map { if (it.exportId == fresh.exportId) fresh else it })
                        }
                    }
                }
                // A failed poll is not reported: the export is still being
                // built and one missed tick is not news.
            }
            poll = null
        }
    }

    private companion object {
        /** Contract §5: every 10–15 seconds while the socket is down. */
        const val POLL_MILLIS = 12_000L
    }
}
