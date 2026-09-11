package com.zillit.desktop.feature.castboard.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.castboard.domain.BoardTool

/**
 * Live updates for the casting and wardrobe boards.
 *
 * The entity events only — a record added, changed or removed. All three
 * clients carry them: Android's `_castingObserver` / `_wardrobeObserver`,
 * iOS's `.updateCastingDataNotify` / `.updateWardrobeDataNotify`, the web's
 * `casting_created` / `wardrobe_created` aliases. The desktop listened for
 * none (audited 2026-09-07), so a record added by the costume department
 * stayed invisible until the board was reopened.
 *
 * Built from [BoardTool.segment], which is already the wire's own prefix, so
 * the two boards cannot drift apart.
 *
 * Two things are deliberately absent. The **discussion** family
 * (`casting:message:*` and its two unit chats) rides the Home feed engine,
 * whose per-board names live in `BoardRealtimeEvents`; adding them here would
 * give one board two reload paths racing each other. And wardrobe's move is
 * spelled `wardrobe:move`, not `:moved` — the web and Android agree on that,
 * and casting has no move at all.
 */
fun castingSyncEvents(board: BoardTool): List<SocketEventName> = buildList {
    add(SocketEventName("${board.segment}:created"))
    add(SocketEventName("${board.segment}:updated"))
    add(SocketEventName("${board.segment}:deleted"))
    if (board.segment == WARDROBE_SEGMENT) add(SocketEventName("${board.segment}:move"))
}

/** Only wardrobe records move between units. */
private const val WARDROBE_SEGMENT = "wardrobe"

/**
 * The board discussion's wire names — the three chats one board carries.
 *
 * A board's thread exists three times over: the tool's own
 * (`casting:message:*`) and one per unit (`casting-main-unit:`,
 * `casting-background-unit:`). All three clients carry the full family, and
 * the desktop carried none, so a note left on an entry by the costume
 * supervisor appeared only if the thread was closed and reopened.
 *
 * `:message:deleted:multiple` rather than `:deleted`: on these boards the wire
 * only ever sends the plural, which is why the singular is absent here and
 * present on Home. `:readby:update` rides along on the tool's own prefix —
 * iOS treats the unit variants' read-by as a no-op, and so does this.
 */
fun castingDiscussionEvents(board: BoardTool): List<SocketEventName> = buildList {
    val prefixes = listOf(
        board.segment,
        "${board.segment}-main-unit",
        "${board.segment}-background-unit",
    )
    prefixes.forEach { prefix ->
        listOf(
            "message:added",
            "message:edited",
            "message:deleted:multiple",
            "message:comment:added",
            "message:comment:edited",
            "message:comment:deleted",
        ).forEach { add(SocketEventName("$prefix:$it")) }
    }
    add(SocketEventName("${board.segment}:message:readby:update"))
}
