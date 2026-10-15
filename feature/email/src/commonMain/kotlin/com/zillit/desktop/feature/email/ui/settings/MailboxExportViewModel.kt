package com.zillit.desktop.feature.email.ui.settings

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.data.MailboxExportUpdate
import com.zillit.desktop.feature.email.domain.MailboxExport
import com.zillit.desktop.feature.email.domain.MailboxExportPart
import com.zillit.desktop.feature.email.domain.MailboxExportRepository
import com.zillit.desktop.feature.email.domain.MailboxScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive

data class MailboxExportUiState(
    /** Newest first, at most ten — the list route's own cap. */
    val exports: List<MailboxExport> = emptyList(),
    val isLoading: Boolean = false,
    val isStarting: Boolean = false,
    /** The export whose Cancel or Remove is in flight. */
    val busyExportId: String = "",
    /** The export whose download links are being fetched. */
    val linkingExportId: String = "",
    /** A completed export the user has asked to remove, awaiting confirmation. */
    val pendingRemoval: MailboxExport? = null,
    val error: String? = null,
    val info: String? = null,
) {
    /** The one the page leads with: the running export, else the newest. */
    val latest: MailboxExport? get() = exports.firstOrNull { it.isInProgress } ?: exports.firstOrNull()

    /** The rest, shown as a short history under it. */
    val earlier: List<MailboxExport> get() = exports.filter { it.exportId != latest?.exportId }

    /** One export per mailbox at a time — asking again while one runs does nothing new. */
    val canStart: Boolean get() = !isStarting && exports.none { it.isInProgress }
}

sealed interface MailboxExportEvent {
    data object Load : MailboxExportEvent

    /** `POST /` — also the "Try again" of a failed export. */
    data object Start : MailboxExportEvent

    /** `DELETE /:id` on a running export: it stops. No confirmation — nothing is lost. */
    data class Cancel(val exportId: String) : MailboxExportEvent

    /** Asks first: removing a completed export deletes the files for good. */
    data class AskRemove(val export: MailboxExport) : MailboxExportEvent
    data object ConfirmRemove : MailboxExportEvent
    data object DismissRemove : MailboxExportEvent

    /**
     * `GET /:id/download`, then the browser.
     *
     * [partNo] is [ALL_PARTS] for every ZIP at once. A negative sentinel
     * rather than `0`, because a part the server numbered `0` would
     * otherwise read as "all of them" and one row's Download would hand the
     * browser the whole export.
     */
    data class Download(val exportId: String, val partNo: Int = ALL_PARTS) : MailboxExportEvent

    companion object {
        /** Every part of the export — the user needs all of them to open it. */
        const val ALL_PARTS = -1
    }

    data object DismissMessage : MailboxExportEvent
}

sealed interface MailboxExportEffect {
    /**
     * Hand this link to the browser.
     *
     * The file is served as an attachment under its own name and a part can
     * be about a gigabyte, so it is never fetched into the app — the same
     * rule the Drive's download follows.
     */
    data class OpenDownload(val url: String, val fileName: String) : MailboxExportEffect
}

/**
 * Export mailbox — `/v2/email-export` (contract §7).
 *
 * The page is drawn from the list on open, kept current by the socket, and
 * polled only when the socket is down. Three rules out of the contract that
 * are easy to lose:
 *
 *  - **`queued` and `processing` are one state.** A dropped connection on the
 *    server puts a running export back to `queued` and resumes minutes later
 *    from where it stopped; `exported_messages` can dip at that moment. None
 *    of that is an error and none of it is shown as one.
 *  - **Links are fetched on the click.** They last an hour. Nothing stores
 *    them and nothing puts them on screen on load.
 *  - **Removing a completed export cannot be undone**, only requested again,
 *    so it asks first. Cancelling a running one does not.
 */
class MailboxExportViewModel(
    private val repository: MailboxExportRepository,
    /**
     * `email:export:updated`, already mapped; null on a build with no socket,
     * which leans on [pollMillis] instead.
     */
    updates: Flow<MailboxExportUpdate>? = null,
    /**
     * Which mailbox this page is showing. An update for the other one is
     * dropped: the event names the mailbox, and one window can be on either.
     */
    private val scope: MailboxScope = MailboxScope.Personal,
    /**
     * Whether the socket is up right now. The poll below is the fallback for
     * when it is not, so this is read at each tick rather than once.
     */
    private val isSocketConnected: () -> Boolean = { false },
    private val pollMillis: Long = POLL_MILLIS,
) : ZillitViewModel<MailboxExportUiState, MailboxExportEvent, MailboxExportEffect>(MailboxExportUiState()) {

    private var poll: Job? = null

    init {
        updates?.let { stream ->
            launch {
                stream.collect { update ->
                    // The event is only sent to whoever asked for the export,
                    // and only for one of the two mailboxes.
                    if (update.isAccountsMailbox == scope.isAccountsActive()) apply(update.export)
                }
            }
        }
    }

    /**
     * Stops the fallback poll.
     *
     * The loop below ends on its own as soon as the export settles, but while
     * one is being built it keeps a 45-second timer alive — so the host calls
     * this when the page goes away, and a test calls it before it finishes.
     * (A `runTest` that leaves a self-rescheduling timer behind never
     * completes: draining the virtual clock keeps finding the next tick.)
     */
    fun stopWatching() {
        poll?.cancel()
        poll = null
    }

    override fun onEvent(event: MailboxExportEvent) {
        when (event) {
            MailboxExportEvent.Load -> load()
            MailboxExportEvent.Start -> start()
            is MailboxExportEvent.Cancel -> stop(event.exportId)
            is MailboxExportEvent.AskRemove -> setState { copy(pendingRemoval = event.export) }
            MailboxExportEvent.ConfirmRemove ->
                currentState.pendingRemoval?.let { export ->
                    setState { copy(pendingRemoval = null) }
                    stop(export.exportId)
                }
            MailboxExportEvent.DismissRemove -> setState { copy(pendingRemoval = null) }
            is MailboxExportEvent.Download -> download(event.exportId, event.partNo)
            MailboxExportEvent.DismissMessage -> setState { copy(error = null, info = null) }
        }
    }

    /**
     * The list, and the page's clean slate.
     *
     * Also the reload after the mailbox switcher flips — the host re-sends
     * [MailboxExportEvent.Load] then — so everything that belonged to the
     * previous mailbox goes: its rows, its in-flight ids, its pending
     * question and the poll watching its export.
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
        setState { copy(isStarting = true, error = null, info = null) }
        launchResult(
            block = { repository.start() },
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

    /**
     * Fetches the links and hands them to the browser — every part, or the
     * one whose button was pressed.
     */
    private fun download(exportId: String, partNo: Int) {
        if (currentState.linkingExportId.isNotBlank()) return
        setState { copy(linkingExportId = exportId, error = null, info = null) }
        launchResult(
            block = { repository.downloadLinks(exportId) },
            onSuccess = { links ->
                val wanted = links.parts.filter { partNo == MailboxExportEvent.ALL_PARTS || it.partNo == partNo }
                setState {
                    copy(
                        linkingExportId = "",
                        // The server answered, but with no link for what was
                        // asked for. Saying nothing would leave a button that
                        // flashes and does nothing at all.
                        error = if (wanted.isEmpty()) str(S.desktop_email_export_no_link) else null,
                    )
                }
                wanted.forEach { part -> sendEffect(part.asDownload()) }
            },
            onError = { setState { copy(linkingExportId = "", error = it.localised()) } },
        )
    }

    private fun MailboxExportPart.asDownload() = MailboxExportEffect.OpenDownload(url, fileName)

    /** Replaces the export with this id, or puts a new one at the front. */
    private fun apply(export: MailboxExport) {
        setState {
            val known = exports.any { it.exportId == export.exportId }
            val next = if (known) {
                exports.map { if (it.exportId == export.exportId) export else it }
            } else {
                (listOf(export) + exports).take(MailboxExportRepository.HISTORY_LIMIT)
            }
            copy(exports = next)
        }
        watchProgress()
    }

    /**
     * The fallback poll.
     *
     * Runs only while an export is unfinished, and asks nothing while the
     * socket is up — the pushes are better than a poll, and a 30-second
     * progress event already arrives on its own. One loop at a time.
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
                // Nothing is asked while the socket is up: its pushes are
                // better than a poll, and one already arrives every 30s.
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
        /** Contract §6: every 30–60 seconds while the socket is down. */
        const val POLL_MILLIS = 45_000L
    }
}
