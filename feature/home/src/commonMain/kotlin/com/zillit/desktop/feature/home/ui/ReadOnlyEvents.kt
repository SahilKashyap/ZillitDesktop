package com.zillit.desktop.feature.home.ui

/**
 * The events a read-only board ([HomeFeedUiState.readOnly]) still honours:
 * loading and paging, finding, the Gallery, jumping to a post, and opening or
 * downloading a file. Everything else — posting, replying, editing, pinning,
 * deleting, forwarding, the call sheet's questions, read receipts — is a write
 * or a conversation, and an archive has neither.
 *
 * An allow-list rather than a deny-list: an event added to the board later is
 * refused here until someone decides it is safe to read with.
 */
internal fun HomeFeedEvent.readsOnly(): Boolean = when (this) {
    HomeFeedEvent.Load,
    HomeFeedEvent.ProjectChanged,
    HomeFeedEvent.Refresh,
    HomeFeedEvent.LoadOlder,
    is HomeFeedEvent.SelectUnit,
    HomeFeedEvent.ShowLibrary,
    HomeFeedEvent.DismissLibrary,
    HomeFeedEvent.DismissError,
    HomeFeedEvent.DismissInfo,
    HomeFeedEvent.OpenSearch,
    HomeFeedEvent.CloseSearch,
    is HomeFeedEvent.SearchChanged,
    is HomeFeedEvent.StepSearch,
    is HomeFeedEvent.OpenAttachment,
    HomeFeedEvent.OpenHandled,
    is HomeFeedEvent.JumpToPost,
    -> true

    else -> false
}
