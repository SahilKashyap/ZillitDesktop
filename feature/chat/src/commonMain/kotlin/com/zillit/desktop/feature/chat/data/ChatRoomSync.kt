package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.ZillitSocketEvents

/**
 * The room listing's own live events.
 *
 * A group is made, renamed or left on somebody else's client; these three say
 * so. They were declared in `ZillitSocketEvents` and listened to by nobody
 * until now, so a room appeared or vanished here only when something else
 * happened to re-read the listing.
 *
 * They carry one room each, but the listing is ordered and badged as a whole,
 * so the handler re-reads it rather than patching a row — the same fetch a
 * reconnect already does.
 *
 * Both phones scope these by tool, so a budget conversation hears
 * `budget:chat-room:*` and C&C hears the bare spelling.
 */
val CHAT_ROOM_SYNC_EVENTS: List<SocketEventName> = listOf(
    ZillitSocketEvents.ChatRoom.Create,
    ZillitSocketEvents.ChatRoom.Remove,
    ZillitSocketEvents.ChatRoom.Updated,
)
