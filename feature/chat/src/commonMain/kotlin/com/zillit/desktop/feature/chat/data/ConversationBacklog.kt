package com.zillit.desktop.feature.chat.data

/**
 * What the notification backlog says about each conversation: how many rows
 * are waiting, and when the newest one was written.
 *
 * Both keyed by conversation — the peer's user id for a DM, the room id for a
 * group — exactly as [conversationUnreadFrom] keys the counts.
 */
data class ConversationBacklog(
    /** Unread rows per conversation, absent when zero. */
    val unread: Map<String, Int> = emptyMap(),
    /**
     * The newest chat row's `created` per conversation, read or not.
     *
     * The server's word on when a thread last moved — the one signal this
     * desktop has for a message that arrived while it was closed. Android's
     * list is sorted by `sorting_activity`, which is the newest message's
     * `created` (`UserAndGroupListHandler.kt:161`); a notification row's
     * `created` (`NotificationDataModel.kt:34`) is that same instant as the
     * badge service recorded it.
     */
    val activity: Map<String, Long> = emptyMap(),
)
