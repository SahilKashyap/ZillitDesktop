package com.zillit.desktop.feature.notifications.ui

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
) : ZillitViewModel<NotificationsUiState, NotificationsEvent, NotificationsEffect>(NotificationsUiState()) {

    fun start() {
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
        if (currentState.loading) return
        setState { copy(loading = true, error = null) }
        launchResult(
            block = { repository.page(nowMillis(), newest = true) },
            onSuccess = { page ->
                setState {
                    copy(loading = false, loaded = true, rows = page.forDisplay(), hasMore = page.size >= pageLimit)
                }
                markRead()
            },
            onError = { error -> setState { copy(loading = false, loaded = true, error = error.localised()) } },
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
                        "All notifications deleted"
                    } else {
                        "Notification deleted"
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
    }
}
