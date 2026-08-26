package com.zillit.desktop.core.socket

/**
 * Wire event names, transcribed from the Android client.
 *
 * Grouped by domain purely for readability — **the socket layer does not act on
 * these**. They are constants a feature can reference, nothing more. Declaring
 * them centrally means the exact wire strings live in one reviewable place
 * rather than scattered across handlers, which is how the Android side ended up
 * with `private const val` names inside a 2,000-line object.
 *
 * Names must match the server exactly; a typo produces silence, not an error.
 * Source: `ChatSocketHelper.kt` and `BaseSocketListener.kt`.
 */
object ZillitSocketEvents {

    /** Lifecycle events the transport itself emits. */
    object Lifecycle {
        val Connect = SocketEventName("connect")
        val Disconnect = SocketEventName("disconnect")
        val ConnectError = SocketEventName("connect_error")
    }

    /**
     * Unread counts changed somewhere.
     *
     * Treated as a signal, not a payload: the desktop refetches rather than
     * applying the records the messages carry. See `BadgeCounts`.
     *
     * Names verified against Android's registrations (`BaseSocketListener`
     * `notificationAddedListener` + `CommonBadgesHandler`
     * `listenForNotificationSyncChanges`, 2026-08-11). An earlier draft
     * watched `badge_data`/`tab_badge`/…, which exist on no wire — the
     * subscriptions were silent and sync never fired.
     */
    object Badges {
        /** New notification records — a badge somewhere went up. */
        val Save = SocketEventName("notification:save")

        /** Records read or deleted on another device — badges went down. */
        val Silent = SocketEventName("notification:silent")

        /** All of one project's (or this device's) records were cleared. */
        val ClearedUser = SocketEventName("badges:cleared:user")
        val ClearedDevice = SocketEventName("badges:cleared:device")

        /** Cross-device pings that a read/delete happened; no records. */
        val ReadSync = SocketEventName("notification:read:sync")
        val DeleteSync = SocketEventName("notification:delete:sync")
        val DeleteGlobalSync = SocketEventName("notification:delete:global:sync")

        /**
         * Outbound: "this user has seen this segment" — clears matching
         * records server-side. Payload is [NotificationReadDto]
         * (Android `ReadOrDeleteNotificationRequestModel`, `notification:read`).
         */
        val NotificationRead = SocketEventName("notification:read")

        /** Outbound: the level-scoped variant tools use for their tabs. */
        val NotificationLevelRead = SocketEventName("notification:level:read")

        /** Every inbound event that means "counts moved". */
        val All = listOf(
            Save, Silent, ClearedUser, ClearedDevice,
            ReadSync, DeleteSync, DeleteGlobalSync,
        )
    }

    /**
     * The Home notice boards.
     *
     * Colon-delimited, unlike the badge events — the wire is not consistent and
     * these must match it exactly (`BaseSocketListener:2688`).
     */
    object Home {
        val MessageAdded = SocketEventName("home:message:added")
        val MessageEdited = SocketEventName("home:message:edited")
        val MessageDeleted = SocketEventName("home:message:deleted")
        val MessagesDeleted = SocketEventName("home:message:deleted:multiple")

        /** Unit creation, rights changes and deletion all invalidate the tab strip. */
        val UnitCreated = SocketEventName("home:unit:create")
        val UnitUpdated = SocketEventName("home:unit:update")
        val UnitDeleted = SocketEventName("home:unit:delete")

        /**
         * A comment on a notice.
         *
         * The payload carries the comment, not the notice it hangs off, so
         * these reload the board rather than patching a post in place — the
         * same coarse handling every other board applies to them
         * (`BoardRealtimeEvents.chatBoard`). Home was the one board without
         * them, so a comment appeared live on an Info notice and silently on
         * a Home one.
         */
        val CommentAdded = SocketEventName("home:message:comment:added")
        val CommentEdited = SocketEventName("home:message:comment:edited")
        val CommentDeleted = SocketEventName("home:message:comment:deleted")

        val Messages = listOf(MessageAdded, MessageEdited, MessageDeleted, MessagesDeleted)
        val Comments = listOf(CommentAdded, CommentEdited, CommentDeleted)
        val Units = listOf(UnitCreated, UnitUpdated, UnitDeleted)
        val All = Messages + Comments + Units
    }

    object Session {
        val JoinUser = SocketEventName("user:join")
        val UserList = SocketEventName("user:list")
        val MarkOnline = SocketEventName("mark_online")
        val MarkOffline = SocketEventName("mark_offline")
        val UnlinkedDevice = SocketEventName("device:unlinked")
    }

    object PrivateChat {
        val Message = SocketEventName("private_chat")
        val Delete = SocketEventName("private-chat:delete-messages")
        val ReadUntil = SocketEventName("private_chat_message_read_untill")
        val Edit = SocketEventName("private-chat:edit")
        val Typing = SocketEventName("private-chat:typing")
        val Expired = SocketEventName("private-chat:message-expired")
    }

    object GroupChat {
        val Message = SocketEventName("group_chat")
        val Delete = SocketEventName("group-chat:delete-messages")
        val ReadUntil = SocketEventName("group-chat:read-untill")
        val Edit = SocketEventName("group-chat:edit")
        val Typing = SocketEventName("group-chat:typing")
        val Expired = SocketEventName("group-chat:message-expired")
    }

    object ChatRoom {
        val Create = SocketEventName("chat-room:create")
        val Remove = SocketEventName("chat-room:remove")
        val Updated = SocketEventName("chat-room:updated")
        val Blocked = SocketEventName("chat-communication:blocked")
        val UpdateReaction = SocketEventName("update_reaction")
        val PendingMessages = SocketEventName("pending-messages:get")
    }

    /**
     * Calling.
     *
     * The ring arrives on [Incoming] as a flat call object. On Android the
     * same invite races in over Firebase as well and the two are deduplicated
     * by `invite_code`; the desktop has no push channel, so the socket is the
     * only way a call reaches it and the dedupe has nothing to do.
     */
    object Calls {
        /** A call is ringing this device. Payload is the call object. */
        val Incoming = SocketEventName("cnc:incoming-call")

        /** A participant answered, declined or hung up. */
        val Update = SocketEventName("call:update")

        /** Someone's response to an invite (`ringing`, `incall`, `declined`). */
        val Response = SocketEventName("call:response")

        /**
         * The call is over — broadcast to every member of the chat room, not
         * just the participants, so open chat screens can drop their "Join
         * call" affordance without refetching.
         */
        val Ended = SocketEventName("call:ended")
        val GroupCallEnded = SocketEventName("call:group-call-ended")

        /** Rang out unanswered. */
        val Timeout = SocketEventName("call:timeout")

        /**
         * A missed call landed in this user's ledger. Payload `{project_id}`.
         *
         * The server does not emit `notification:save` for it — iOS bumps its
         * CnC count directly off this event (`ProjectObserver`, its one
         * surviving direct increment) — so the desktop's badge refresh must
         * listen here too or the missed call badges nothing until an unrelated
         * notification arrives.
         */
        val MissedCall = SocketEventName("call:missed-call")

        /**
         * In-call reactions and ephemeral chat. Never persisted.
         *
         * Inbound only. The server relays the inner event verbatim to the
         * addressed user rooms, so this is what ARRIVES; sending one goes out
         * on [Relay] wrapped in an envelope naming this event.
         */
        val InCallData = SocketEventName("call:incall-data")

        /**
         * The CNC's generic relay — `{event, rooms, eventData}` in, the inner
         * `event` out to each addressed user room. In-call reactions and chat
         * are sent this way rather than on a call event of their own, and the
         * phones do the same.
         */
        val Relay = SocketEventName("custom:events")

        /** Another of this user's devices is taking the call over. */
        val Handoff = SocketEventName("call:handoff")
        val HandoffDone = SocketEventName("call:handoff-done")
        val HandoffEvict = SocketEventName("call:handoff-evict")

        /** A 1:1 call must move onto the SFU. */
        val Migrate = SocketEventName("call:migrate")

        /** An external guest is asking to be let in. */
        val GuestJoinRequest = SocketEventName("call:guest:join:request")
        val GuestJoinResponded = SocketEventName("call:guest:join:responded")

        val ActiveGroupCalls = SocketEventName("call:get-active-group-calls")
        val DeleteLog = SocketEventName("call:delete:log")

        /** Everything that changes who is in a call, or whether it exists. */
        val Lifecycle = listOf(Incoming, Update, Response, Ended, GroupCallEnded, Timeout)
    }

    object Budget {
        val RecentList = SocketEventName("budget:recent:list")
    }

    /** The Distribution List tool — the per-user × per-unit opt-in matrix. */
    object Distribution {
        /** A switch flipped on another device — refetch the matrix. */
        val AccessUpdate = SocketEventName("distribution:access:update")
    }

    /**
     * The production's tool set or its sections changed — a tool switched on
     * or off, a group made, renamed or removed, or this user's own section
     * order saved on another device (Android's `PROJECT_TOOLS_UPDATE` and
     * `TOOL_GROUP_*` keys). The grid refetches; the payloads carry less than
     * the list itself does.
     */
    object ToolsGrid {
        val ProjectToolsUpdate = SocketEventName("project:tools:update")
        val GroupCreate = SocketEventName("tool:group:create")
        val GroupUpdate = SocketEventName("tool:group:update")
        val GroupDelete = SocketEventName("tool:group:delete")
        val GroupOrderUpdate = SocketEventName("project:tool:group:order:update")

        val All = listOf(ProjectToolsUpdate, GroupCreate, GroupUpdate, GroupDelete, GroupOrderUpdate)
    }

    /**
     * An admin moved someone's rights on the access grid — view, post or
     * download, per unit or per tool (Android's `ACCESS_*_SOCKET_KEY` trio).
     * The payload is an array whose first element carries `user_id`/`_id`;
     * only the person whose rights moved should react.
     */
    object AccessGrid {
        val ViewingRights = SocketEventName("access-grid:viewing-rights:update")
        val PostingRights = SocketEventName("access-grid:posting-rights:update")
        val DownloadRights = SocketEventName("access-grid:download-rights:update")

        val All = listOf(ViewingRights, PostingRights, DownloadRights)
    }

    /**
     * The mailbox.
     *
     * Transcribed from `BaseSocketListener.emailObservers()`. Note these are
     * colon-delimited: the web app's `inbound_email_received` and friends are
     * aliases it re-emits internally, **not** wire names, and subscribing to
     * those would produce silence.
     */
    object Email {
        val InboundReceived = SocketEventName("inbound:email:received")
        val OutboundSent = SocketEventName("outbound:email:sent")
        val Read = SocketEventName("email:read")

        val InboundDeleted = SocketEventName("inbound:email:delete")
        val OutboundDeleted = SocketEventName("outbound:email:delete")
        val TrailDeleted = SocketEventName("inbound:email:delete:trail")
        val Deleted = SocketEventName("email:deleted")
        val TrashEmptied = SocketEventName("email:trash:empty")

        val Moved = SocketEventName("email:moved")
        val MovedPlural = SocketEventName("emails:moved")
        val Move = SocketEventName("email:move")

        val DraftSaved = SocketEventName("email:draft:saved")
        val DraftUpdated = SocketEventName("email:draft:updated")
        val DraftDeleted = SocketEventName("email:draft:deleted")

        val FolderSaved = SocketEventName("email:folder:saved")
        val FolderUpdated = SocketEventName("email:folder:updated")
        val FolderDeleted = SocketEventName("email:folder:deleted")

        /** Mail appeared or disappeared — the open folder needs re-syncing. */
        val Mail = listOf(
            InboundReceived, OutboundSent, InboundDeleted, OutboundDeleted,
            TrailDeleted, Deleted, TrashEmptied, Moved, MovedPlural, Move,
        )

        /** The folder list itself changed. */
        val Folders = listOf(FolderSaved, FolderUpdated, FolderDeleted)

        /**
         * Drafts changed — on another device, or in another window here.
         *
         * Separate from [Mail] because drafts are not IMAP and do not sync by
         * uid; the Drafts folder is reloaded wholesale instead.
         */
        val Drafts = listOf(DraftSaved, DraftUpdated, DraftDeleted)

        val All = Mail + Folders + Drafts + listOf(Read)
    }

    object DocumentDistribution {
        val EmailOpened = SocketEventName("outbound:email:opened")
    }

    /** Every name above, for the collision test. */
    val all: List<SocketEventName> = listOf(
        Lifecycle.Connect, Lifecycle.Disconnect, Lifecycle.ConnectError,
        Badges.Save, Badges.Silent, Badges.ClearedUser, Badges.ClearedDevice,
        Badges.ReadSync, Badges.DeleteSync, Badges.DeleteGlobalSync,
        Badges.NotificationRead, Badges.NotificationLevelRead,
        Home.MessageAdded, Home.MessageEdited, Home.MessageDeleted, Home.MessagesDeleted,
        Home.UnitCreated, Home.UnitUpdated, Home.UnitDeleted,
        Session.JoinUser, Session.UserList, Session.MarkOnline, Session.MarkOffline, Session.UnlinkedDevice,
        PrivateChat.Message, PrivateChat.Delete, PrivateChat.ReadUntil,
        PrivateChat.Edit, PrivateChat.Typing, PrivateChat.Expired,
        GroupChat.Message, GroupChat.Delete, GroupChat.ReadUntil,
        GroupChat.Edit, GroupChat.Typing, GroupChat.Expired,
        ChatRoom.Create, ChatRoom.Remove, ChatRoom.Updated, ChatRoom.Blocked,
        ChatRoom.UpdateReaction, ChatRoom.PendingMessages,
        Calls.Incoming, Calls.Update, Calls.Response, Calls.Ended, Calls.GroupCallEnded,
        Calls.Timeout, Calls.MissedCall, Calls.InCallData, Calls.Relay,
        Calls.Handoff, Calls.HandoffDone, Calls.HandoffEvict,
        Calls.Migrate, Calls.GuestJoinRequest, Calls.GuestJoinResponded,
        Calls.ActiveGroupCalls, Calls.DeleteLog,
        Budget.RecentList,
        Distribution.AccessUpdate,
        DocumentDistribution.EmailOpened,
        Email.InboundReceived, Email.OutboundSent, Email.Read,
        Email.InboundDeleted, Email.OutboundDeleted, Email.TrailDeleted,
        Email.Deleted, Email.TrashEmptied,
        Email.Moved, Email.MovedPlural, Email.Move,
        Email.FolderSaved, Email.FolderUpdated, Email.FolderDeleted,
        Email.DraftSaved, Email.DraftUpdated, Email.DraftDeleted,
    )
}
