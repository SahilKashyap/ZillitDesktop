package com.zillit.desktop.feature.notifications.ui

import com.zillit.desktop.feature.notifications.domain.NotificationTarget
import com.zillit.desktop.feature.notifications.domain.ProjectNotification

/** A destructive act waiting on the reader's "Yes". */
sealed interface NotificationsConfirm {
    data class DeleteOne(val notificationId: String) : NotificationsConfirm
    data object DeleteAll : NotificationsConfirm
}

data class NotificationsUiState(
    /** The first page, or a refresh, in flight. */
    val loading: Boolean = false,
    /** An older page in flight — the list stays up. */
    val loadingMore: Boolean = false,
    /** A delete in flight. */
    val busy: Boolean = false,
    val rows: List<ProjectNotification> = emptyList(),
    /**
     * Whether "Show older" is worth offering. The backend answers at most fifty
     * rows a page (web `NOTIFICATIONS_PAGE_LIMIT`, `DrawerNotification.jsx:44`),
     * so a shorter page is the last one.
     */
    val hasMore: Boolean = false,
    /** True once a first page has answered, so an empty list reads as empty rather than pending. */
    val loaded: Boolean = false,
    val error: String? = null,
    val confirm: NotificationsConfirm? = null,
)

sealed interface NotificationsEvent {
    data object Refresh : NotificationsEvent
    data object LoadOlder : NotificationsEvent
    data class AskDelete(val notificationId: String) : NotificationsEvent
    data object AskDeleteAll : NotificationsEvent
    data object ConfirmDelete : NotificationsEvent
    data object CancelDelete : NotificationsEvent
    data class Open(val notificationId: String) : NotificationsEvent
    data object DismissError : NotificationsEvent
}

sealed interface NotificationsEffect {
    data class Notice(val message: String) : NotificationsEffect

    /** A row was clicked. Android does nothing with this; a host may. */
    data class Open(val target: NotificationTarget) : NotificationsEffect
}
