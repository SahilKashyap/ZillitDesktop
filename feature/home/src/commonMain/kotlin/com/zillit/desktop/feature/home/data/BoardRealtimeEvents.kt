package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Live updates for the board tools that reuse Home's feed engine — Info,
 * Confidential Info, Camera & Sound Report, Script Notes, Catering and
 * Message Accounts. Keyed by the same `board` segment the repository is
 * built with (`HomeFeedRepositoryImpl.board`), so a provider names its
 * board once.
 *
 * Each tool has its own wire prefix but one message schema: the web funnels
 * all of them into a single shared handler (`hooks/useUnitChatEventListeners.js`),
 * which is the engine this module ports. **The names below are wire names**
 * from the web's socket layer (`socket/listenerSocket.js`) — the underscore
 * forms the pages listen on (`info_message_added` and friends) are aliases
 * the web re-emits internally, not wire names, and subscribing to those
 * would produce silence — the same trap `core:socket` documents on its
 * Email events.
 *
 * Declared here rather than in `core:socket`'s shared constants file
 * because only this module knows what a notice board is, and the shared
 * file is one reviewable place too many hands edit at once.
 */

/**
 * One board's wire names, grouped by how the desktop reacts to them.
 *
 * [reload] is deliberately coarse: multi-deletes carry ids rather than
 * posts, comment events carry one comment rather than the notice, and unit,
 * rights and archive events reshape the board wholesale — the web patches
 * some of these in place, but [HomeRealtimeEvent.UnitsChanged] makes the
 * feed refetch its tabs *and* the open board, which is always correct and
 * is exactly what Home already does for its own multi-delete.
 */
internal class BoardRealtimeEvents(
    val added: List<SocketEventName> = emptyList(),
    val edited: List<SocketEventName> = emptyList(),
    val deleted: List<SocketEventName> = emptyList(),
    val reload: List<SocketEventName> = emptyList(),
    /** `<prefix>:message:readby:update` — a receipt, not a change to the post. */
    val readBy: List<SocketEventName> = emptyList(),
) {
    val all: List<SocketEventName> get() = added + edited + deleted + reload + readBy
}

/**
 * The shared suffix scheme (`listenerSocket.js:203-331,341-706`): every
 * board chat emits `<prefix>:message:added|edited|deleted:multiple` and
 * `<prefix>:message:comment:added|edited|deleted`. [singleDelete] adds the
 * singular `<prefix>:message:deleted` some boards also emit. The
 * `:message:replied` events are not here: the web re-emits them as aliases
 * nothing listens to, on any board.
 */
private fun chatBoard(
    prefix: String,
    singleDelete: Boolean = false,
    reloadExtras: List<String> = emptyList(),
): BoardRealtimeEvents = BoardRealtimeEvents(
    added = listOf(SocketEventName("$prefix:message:added")),
    edited = listOf(SocketEventName("$prefix:message:edited")),
    deleted = if (singleDelete) listOf(SocketEventName("$prefix:message:deleted")) else emptyList(),
    reload = listOf(
        "$prefix:message:deleted:multiple",
        "$prefix:message:comment:added",
        "$prefix:message:comment:edited",
        "$prefix:message:comment:deleted",
    ).plus(reloadExtras).map(::SocketEventName),
    readBy = listOf(SocketEventName("$prefix:message:readby:update")),
)

/**
 * Wardrobe rides THREE message prefixes — the tool proper plus its Main and
 * Background unit chats (`listenerSocket.js:1525-1605`) — and its "units"
 * are wardrobe records: created, moved, updated, deleted (`:1512-1521`).
 * No desktop wardrobe board exists yet; the names wait here for its port.
 */
private fun wardrobeBoard(): BoardRealtimeEvents {
    val prefixes = listOf("wardrobe", "wardrobe-main-unit", "wardrobe-background-unit")
    val chats = prefixes.map { chatBoard(it) }
    return BoardRealtimeEvents(
        added = chats.flatMap { it.added },
        edited = chats.flatMap { it.edited },
        readBy = chats.flatMap { it.readBy },
        reload = chats.flatMap { it.reload } +
            listOf("wardrobe:created", "wardrobe:move", "wardrobe:updated", "wardrobe:deleted")
                .map(::SocketEventName),
    )
}

/**
 * Per-board wire names, keyed by the desktop's board segment.
 *
 * Evidence, all `socket/listenerSocket.js`: Info messages `:450-546` plus
 * `info:posting-rights:update` (`:90`) and `info:chat:archived` (`:1291`);
 * Confidential Info `:341-448` plus rights (`:96`) and archived (`:1295`);
 * Camera & Sound Report `:298-331`; Script Notes `:203-296`; Catering
 * `:549-626` plus units (`:794-802`) and archived (`:1287`); Accounts
 * `:629-706` plus units (`:785-793`), rights (`:82,:85`) and archived
 * (`:1283`).
 *
 * Read-by events (`:1625-1646`) ride each prefix as
 * `<prefix>:message:readby:update`. The web ignores them and so did this
 * module, on the web's authority — but the desktop has the read-by panel the
 * web does not, and both phones refresh exactly that panel from these names
 * (`BaseSocketListener.listenReadByObserver`, iOS `ProjectObserver.swift`).
 * The exclusion was right about the web and wrong here (found 2026-09-09).
 */
internal val BOARD_REALTIME_EVENTS: Map<String, BoardRealtimeEvents> = mapOf(
    "info" to chatBoard(
        prefix = "info",
        singleDelete = true,
        reloadExtras = listOf("info:posting-rights:update", "info:chat:archived"),
    ),
    "confidentialinfo" to chatBoard(
        prefix = "confidential_info",
        reloadExtras = listOf("confidential_info:posting-rights:update", "confidential_info:chat:archived"),
    ),
    "reports" to chatBoard(prefix = "reports"),
    // The production report tool's unit chat (`:2412-2489`, readby `:1653`): messages and
    // comments only — no singular delete, rights or archive events on this prefix.
    "production-report" to chatBoard(prefix = "production_report"),
    "script-notes" to chatBoard(
        prefix = "script_notes",
        reloadExtras = listOf("script_notes:posting-rights:update"),
    ),
    "catering" to chatBoard(
        prefix = "catering",
        singleDelete = true,
        reloadExtras = listOf(
            "catering:unit:created", "catering:unit:updated", "catering:unit:deleted",
            "catering:chat:archived",
        ),
    ),
    "account" to chatBoard(
        prefix = "account",
        singleDelete = true,
        reloadExtras = listOf(
            "account:unit:created", "account:unit:updated", "account:unit:deleted",
            "account:viewing-rights:update", "account:posting-rights:update", "account:chat:archived",
        ),
    ),
    "wardrobe" to wardrobeBoard(),
)

/**
 * One board's socket traffic as typed events — [HomeRealtimeSource]'s shape
 * for the reused boards, parameterised by segment.
 *
 * An unknown [board] yields an empty flow rather than throwing: the caller
 * wires unconditionally, and a segment without live events simply stays a
 * load-once board, which is what it was before this existed.
 *
 * No `myUserId` here, unlike Home's source: the per-user access-grid events
 * are Home's business — a board tool's rights ride its own
 * `posting-rights` events, which land in [BoardRealtimeEvents.reload].
 */
fun boardRealtime(
    events: SocketEventBus,
    board: String,
    decryptBody: (String) -> String,
): Flow<HomeRealtimeEvent> {
    val names = BOARD_REALTIME_EVENTS[board] ?: return emptyFlow()
    return events.onAny(names.all).mapNotNull { message -> names.toRealtimeEvent(message, decryptBody) }
}

/**
 * The mapping itself, separated so tests can feed messages without a bus.
 *
 * Same envelope handling as Home's ([unwrapData]): the payload for these
 * boards arrives flat (`useUnitChatEventListeners.js` reads `data.unit_id`
 * off the row), but being generous about wrappers costs nothing and the
 * server has form.
 */
internal fun BoardRealtimeEvents.toRealtimeEvent(
    message: SocketMessage,
    decryptBody: (String) -> String,
): HomeRealtimeEvent? {
    // Reload events first: their payloads carry no post, and asking
    // unwrapData for one would drop them.
    if (message.event in reload) return HomeRealtimeEvent.UnitsChanged
    if (message.event in readBy) {
        return message.payload?.readByMessageId()?.let(HomeRealtimeEvent::ReadByChanged)
    }

    val body = message.payload?.unwrapData() ?: return null
    val unitId = body.stringField("unit_id")

    return when (message.event) {
        in added -> readNotice(body, decryptBody)?.let { HomeRealtimeEvent.NoticeAdded(unitId, it) }
        in edited -> readNotice(body, decryptBody)?.let { HomeRealtimeEvent.NoticeEdited(unitId, it) }
        in deleted -> body.stringField("_id")?.let { HomeRealtimeEvent.NoticeDeleted(unitId, it) }
        else -> null
    }
}

/**
 * The message a read-by frame is about.
 *
 * `message_id` sits at the top of the row, beside `project_id` — not inside
 * `data`, which is why [unwrapData] is not used here: that helper insists on
 * an `_id` key and would drop these. Android reads the same field off the
 * first element of the frame array (`BaseSocketModelChecker.message_id`).
 */
internal fun JsonElement.readByMessageId(): String? {
    val row = when (this) {
        is JsonArray -> firstOrNull() as? JsonObject
        is JsonObject -> (this["detail"] as? JsonObject) ?: this
        else -> null
    } ?: return null
    return row.stringField("message_id")
}
