package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns Home's socket traffic into typed events.
 *
 * The socket layer knows nothing about notice boards — it hands over named
 * payloads. This is where `home:message:added` becomes something the board can
 * act on, and it is the only place in the app that knows that mapping.
 */
class HomeRealtimeSource(
    private val events: SocketEventBus,
    private val decryptBody: (String) -> String,
    /** Who is signed in — rights events for anyone else are not this board's. */
    private val myUserId: () -> String? = { null },
    /**
     * Which production is open — frames for any other one are not this
     * board's. Null means "do not filter", which is what every caller that
     * does not know a production gets.
     */
    private val openProjectId: () -> String? = { null },
) {

    val stream: Flow<HomeRealtimeEvent> =
        events.onAny(ZillitSocketEvents.Home.All + ZillitSocketEvents.AccessGrid.All)
            .filter(::isHere)
            .mapNotNull(::toEvent)

    /**
     * Whether a frame belongs to the production that is open.
     *
     * One socket serves every production this device is on — the rule chat
     * has gated on since it was ported (`ChatFrameScope`), and which Home
     * never did. So a notice posted on another production reached this
     * board's feed and raised a notification for it, with an author this
     * production's crew list has never heard of ("Someone") and, if that
     * production writes plain bodies, nothing readable either.
     *
     * A frame that names no production passes, as chat's does: the gate is
     * for frames that say they are somebody else's, not for every frame
     * whose shape this does not recognise.
     */
    private fun isHere(message: SocketMessage): Boolean {
        val open = openProjectId()?.takeIf { it.isNotBlank() } ?: return true
        val named = message.payload?.framedProjectId() ?: return true
        return named == open
    }

    /**
     * The production a Home frame names, wherever it put it.
     *
     * Board rows carry `project_id` on the row itself, which is under `data`
     * — so the row is unwrapped the same way everything else here reads it.
     * The two outer spellings are chat's (`ChatFrameScope`), checked as well
     * because the events in this family do not all share one envelope.
     */
    private fun JsonElement.framedProjectId(): String? {
        val outer = this as? JsonObject
        return unwrapData()?.stringField(PROJECT_ID)
            ?: (outer?.get("detail") as? JsonObject)?.stringField(PROJECT_ID)
            ?: outer?.stringField(PROJECT_ID)
    }

    private fun toEvent(message: SocketMessage): HomeRealtimeEvent? = when {
        message.event in ZillitSocketEvents.Home.Units -> HomeRealtimeEvent.UnitsChanged

        // A receipt, not a post: the board shows nothing for it, and only an
        // open read-by panel is listening.
        message.event == ZillitSocketEvents.Home.MessageReadBy ->
            message.payload?.readByMessageId()?.let(HomeRealtimeEvent::ReadByChanged)

        // A comment's payload is the comment, not the notice it belongs to,
        // so there is nothing to patch in place — the board is re-read, which
        // is what every other board does with the same events.
        //
        // The *board*, not the units: a comment leaves the tab strip exactly
        // as it was, and refetching it blanked the board behind a spinner
        // every time anybody commented on any unit. The unit id is read off
        // the payload when it names one, so a comment on a unit nobody is
        // looking at costs nothing at all.
        message.event in ZillitSocketEvents.Home.Comments ->
            HomeRealtimeEvent.BoardChanged(message.payload?.unwrapData()?.stringField("unit_id"))

        // An admin moved MY rights: the tab strip and the composer's gate are
        // both stale, so the unit list is read afresh — Android's own
        // fallback branch, minus its in-place patching (QA #18: rights taken
        // away kept working until the app restarted). Somebody else's rights
        // are their board's business.
        message.event in ZillitSocketEvents.AccessGrid.All ->
            HomeRealtimeEvent.UnitsChanged.takeIf {
                message.payload?.rightsTargetUserId() == myUserId()
            }

        else -> message.payload?.unwrapData()?.let { body -> boardEvent(message, body) }
    }

    /** The per-board events, once the payload has been unwrapped. */
    private fun boardEvent(message: SocketMessage, body: JsonObject): HomeRealtimeEvent? {
        // The wire nests the interesting object under `data`, sometimes as a
        // JSON **string** rather than an object — Android re-parses it for the
        // same reason (`baseSocketParser`).
        val unitId = body.stringField("unit_id")

        return when (message.event) {
            ZillitSocketEvents.Home.MessageAdded ->
                readNotice(body, decryptBody)?.let {
                    HomeRealtimeEvent.NoticeAdded(unitId, it, body.toString())
                }

            ZillitSocketEvents.Home.MessageEdited ->
                readNotice(body, decryptBody)?.let {
                    HomeRealtimeEvent.NoticeEdited(unitId, it, body.toString())
                }

            ZillitSocketEvents.Home.MessageDeleted ->
                body.stringField("_id")?.let { HomeRealtimeEvent.NoticeDeleted(unitId, it) }

            // The multi-delete carries ids rather than a post; re-reading the
            // board is simpler than removing n things by hand, and it is rare.
            // Any Home event not named above lands here for the same reason —
            // the board, though, not the tab strip, which none of these move.
            else -> HomeRealtimeEvent.BoardChanged(unitId)
        }
    }
}

/**
 * Digs the payload object out of the envelope.
 *
 * Accepts the object directly, `{"data": {...}}`, `{"data": "{...}"}` (a JSON
 * string, which the server does send) and `{"data": [{...}]}`. Being generous
 * here is cheaper than a class of silent no-ops where a board simply never
 * updates.
 */
internal fun JsonElement.unwrapData(): JsonObject? {
    val inner = (this as? JsonObject)?.get("data") ?: this

    return when (inner) {
        is JsonObject -> if (inner.containsKey("_id")) inner else inner["data"]?.unwrapData()
        is JsonArray -> inner.firstOrNull()?.unwrapData()
        is JsonPrimitive ->
            if (!inner.isString) null
            else runCatching { Json.parseToJsonElement(inner.content) }.getOrNull()?.unwrapData()
        else -> null
    }
}

/** Internal, not private: [BoardRealtimeEvents]' mapping reads the same keys. */
internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/**
 * Whose rights an access-grid event moves.
 *
 * The payload is an array whose first element names the person as `user_id`
 * or `_id` (Android's `baseSocketWithUserIDParser`). Not [unwrapData]: that
 * helper insists on an `_id` key inside `data` envelopes, and these rows
 * are bare.
 */
internal fun JsonElement.rightsTargetUserId(): String? {
    val row = when (this) {
        is JsonArray -> firstOrNull() as? JsonObject
        is JsonObject -> this
        else -> null
    } ?: return null
    return row.stringField("user_id") ?: row.stringField("_id")
}

/** The key every Home row names its production under. */
private const val PROJECT_ID = "project_id"
