package com.zillit.desktop.feature.notifications.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.notifications.data.NotificationsEndpoints
import com.zillit.desktop.feature.notifications.domain.NotificationsRepository
import com.zillit.desktop.feature.notifications.domain.ProjectNotification
import com.zillit.desktop.feature.notifications.domain.forDisplay

/**
 * The production's notification list.
 *
 * A port of `NotificationVM` (`bottomNav/common/viewmodel/NotificationVM.kt`):
 * pages older-ward from "now" by timestamp, marks the global segment read
 * after every page that answers, deletes one row or all of them behind a
 * confirmation. Nothing is optimistic except the local removal after a
 * confirmed delete — the same as Android's `adapter.removeItem`.
 */
class NotificationsViewModel(
    private val repository: NotificationsRepository,
    private val nowMillis: () -> Long,
    /**
     * Told after every page that answers, once the REST mark-read has gone
     * out. The host uses it for what this module cannot reach — the socket
     * `notification:read` emit the phones and the web send, and the badge
     * store's refresh — so the bell's count drops the moment the list is open.
     */
    private val onListRead: suspend () -> Unit = {},
    /** The page size the backend answers; a shorter page is the last one. */
    private val pageLimit: Int = PAGE_LIMIT,
    /**
     * A pulse per notification the socket delivers.
     *
     * This module deliberately owns no socket — the host reaches the wire, as
     * [onListRead] says. But the bell's count moved on `notification:save`
     * while the list under it did not, so an open bell page went stale the
     * moment it mattered most (found 2026-09-09). The host hands the same
     * badge stream it already collects.
     */
    private val arrivals: Flow<Unit> = emptyFlow(),
    /**
     * The production whose notifications these are, read at each [start].
     *
     * This model is built once for the application, and the rows and the
     * mark-read belong to one production. Without this the bell reopened on
     * another shoot showed the previous one's list and, worse, marked
     * nothing read — so that production's `global_label` badge could never
     * clear (found 2026-10-14 from a user report). Null — a host that cannot
     * say — behaves as before.
     */
    private val projectId: () -> String? = { null },
) : ZillitViewModel<NotificationsUiState, NotificationsEvent, NotificationsEffect>(NotificationsUiState()) {

    /** The production the rows on screen were loaded for. */
    private var loadedFor: String? = null

    init {
        // Only once the list has been looked at: refetching a page nobody has
        // opened spends a request to fill state the first `start()` replaces.
        launch {
            arrivals.collect { if (currentState.loaded) refresh() }
        }
    }

    /**
     * Every visit to the page. Loads when there is nothing to show for this
     * production — including after a failed attempt, which is why the error
     * path leaves [NotificationsUiState.loaded] alone: latching it would mean
     * one 401 during a production switch disabled the page, and with it the
     * only thing that marks the global segment read, for the rest of the run.
     */
    fun start() {
        val project = projectId()
        // The page's own voice. Three opens of this list left no trace in the
        // log at all — no page fetch, no read — while the window plainly
        // existed, and a page that never mounts looked exactly like one that
        // mounted and failed. Neither is distinguishable without this.
        ZillitLog.d(TAG) {
            "bell start: project=$project loadedFor=$loadedFor " +
                "loaded=${currentState.loaded} loading=${currentState.loading} rows=${currentState.rows.size}"
        }
        if (currentState.loaded && loadedFor != project) setState { NotificationsUiState() }
        loadedFor = project
        if (!currentState.loaded && !currentState.loading) refresh()
    }

    override fun onEvent(event: NotificationsEvent) {
        when (event) {
            NotificationsEvent.Refresh -> refresh()
            NotificationsEvent.LoadOlder -> loadOlder()
            is NotificationsEvent.AskDelete -> setState {
                copy(confirm = NotificationsConfirm.DeleteOne(event.notificationId))
            }
            NotificationsEvent.AskDeleteAll -> setState { copy(confirm = NotificationsConfirm.DeleteAll) }
            NotificationsEvent.ConfirmDelete -> confirmDelete()
            NotificationsEvent.CancelDelete -> setState { copy(confirm = null) }
            is NotificationsEvent.Open -> currentState.rows.firstOrNull { it.id == event.notificationId }?.let {
                sendEffect(NotificationsEffect.Open(it.target))
            }
            NotificationsEvent.DismissError -> setState { copy(error = null) }
        }
    }

    /** The newest page, replacing whatever is shown — Android's first `getNotifications()`. */
    private fun refresh() {
        if (currentState.loading) {
            ZillitLog.d(TAG) { "bell refresh skipped: a page is already in flight" }
            return
        }
        setState { copy(loading = true, error = null) }
        launchResult(
            block = { repository.page(nowMillis(), newest = true) },
            onSuccess = { page ->
                setState {
                    copy(loading = false, loaded = true, rows = page.forDisplay(), hasMore = page.size >= pageLimit)
                }
                markRead()
            },
            onError = { error ->
                ZillitLog.w(TAG) { "bell page failed: $error" }
                setState { copy(loading = false, error = error.localised()) }
            },
        )
    }

    /**
     * The next page down, from the oldest row shown.
     *
     * The cursor is the oldest `updated` (falling back to `created`): the
     * backend pages `/previous` on `updated`, so a `created` cursor skips any
     * older row that was touched since (web ZL-17771, `DrawerNotification.jsx:
     * 215-226`). Android's `NotificationVM` still cursors on `created`
     * (`NotificationVM.kt:46`); the two agree whenever nothing was re-touched.
     */
    private fun loadOlder() {
        val state = currentState
        if (state.loading || state.loadingMore || !state.hasMore) return
        val cursor = state.rows.minOfOrNull { it.cursorMillis } ?: nowMillis()
        setState { copy(loadingMore = true) }
        launchResult(
            block = { repository.page(cursor, newest = false) },
            onSuccess = { page ->
                setState {
                    copy(loadingMore = false, rows = (rows + page).forDisplay(), hasMore = page.size >= pageLimit)
                }
                markRead()
            },
            onError = { error -> setState { copy(loadingMore = false, error = error.localised()) } },
        )
    }

    /**
     * Every page that answers marks the segment read (`NotificationVM.kt:67-71`),
     * and only *after* the page: reading first would bump the rows' timestamps
     * past the cursor and hide them (the web says so at
     * `DrawerNotification.jsx:189`). The REST answer is not waited on for
     * anything — Android does not look at it either.
     */
    private fun markRead() {
        launch {
            repository.markRead(NotificationsEndpoints.GLOBAL_SEGMENT, nowMillis())
            onListRead()
        }
    }

    private fun confirmDelete() {
        val confirm = currentState.confirm ?: return
        setState { copy(confirm = null, busy = true) }
        launch {
            val result = when (confirm) {
                is NotificationsConfirm.DeleteOne -> repository.delete(confirm.notificationId, nowMillis())
                NotificationsConfirm.DeleteAll -> repository.deleteAll()
            }
            when (result) {
                is ZillitResult.Success -> {
                    setState {
                        when (confirm) {
                            is NotificationsConfirm.DeleteOne ->
                                copy(busy = false, rows = rows.filterNot { it.id == confirm.notificationId })
                            NotificationsConfirm.DeleteAll -> copy(busy = false, rows = emptyList(), hasMore = false)
                        }
                    }
                    val notice = if (confirm is NotificationsConfirm.DeleteAll) {
                        str(S.desktop_all_notifications_deleted)
                    } else {
                        str(S.desktop_notification_deleted)
                    }
                    sendEffect(NotificationsEffect.Notice(notice))
                }
                is ZillitResult.Failure -> setState { copy(busy = false, error = result.error.localised()) }
            }
        }
    }

    private val ProjectNotification.cursorMillis: Long
        get() = if (updatedMillis > 0) updatedMillis else createdMillis

    companion object {
        /** What the backend hands back per page at most (`DrawerNotification.jsx:42-44`). */
        const val PAGE_LIMIT = 50

        private const val TAG = "Notifications"
    }
}
