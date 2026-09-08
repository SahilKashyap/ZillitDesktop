package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.calls.domain.CallSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Reactions and ephemeral chat, for as long as the call lasts.
 *
 * Its own object rather than more of [CallCoordinator] because it shares
 * nothing with the phase machine: no REST, no Firestore, no timers. It reads
 * the live session, addresses the CNC's relay, and forgets everything when the
 * call ends.
 *
 * Nothing here is written down anywhere. A line sent on a call exists in the
 * windows that were open to receive it and nowhere else — which is the bargain
 * every other Zillit client makes, and the reason the panel says so out loud.
 */
class InCallDataChannel(
    private val bus: SocketEventBus,
    private val scope: CoroutineScope,
    /** The call this belongs to, or null between calls. */
    private val session: () -> CallSession?,
    /** False until the call is actually up; nothing is sent before then. */
    private val connected: () -> Boolean,
    private val selfName: () -> String?,
    private val selfDeviceId: () -> String?,
    private val now: () -> Long,
    /**
     * A line the media engine can carry itself — Line 3's data channel.
     * True means it went that way and the socket relay is not used.
     */
    private val direct: (InCallData) -> Boolean = { false },
) {
    /**
     * What arrived, and what we sent — one stream, so the sender's view and
     * everyone else's are built from the same events.
     */
    private val _data = MutableSharedFlow<InCallData>(extraBufferCapacity = 32)
    val data: SharedFlow<InCallData> = _data.asSharedFlow()

    /**
     * Ids already delivered. Our own sends go in before they leave, so the
     * relay's echo is a no-op rather than a double.
     */
    private val seen = ArrayDeque<String>()
    private var lastReactionAtMillis = 0L
    private var sequence = 0L

    /** Collects forever; the caller owns the coroutine. */
    suspend fun listen() {
        bus.on(ZillitSocketEvents.Calls.InCallData).collect { message ->
            val payload = message.payload ?: return@collect
            val incoming = readInCallData(payload) ?: return@collect
            val current = session() ?: return@collect
            if (!belongsHere(incoming, current)) return@collect
            if (!remember(incoming.id)) return@collect
            _data.emit(incoming)
        }
    }

    /** Sends an emoji to everyone else on the call, and shows it here. */
    fun sendReaction(emoji: String) {
        if (emoji.isBlank()) return
        val nowMillis = now()
        // The phones throttle to one every 0.6s. Without it a held key turns a
        // call into a flood nobody can see past.
        if (nowMillis - lastReactionAtMillis < REACTION_MIN_GAP_MILLIS) return
        lastReactionAtMillis = nowMillis
        send(kind = IN_CALL_KIND_REACTION, emoji = emoji, text = "", nowMillis = nowMillis)
    }

    /** Sends one ephemeral line to everyone else on the call. */
    fun sendMessage(text: String) {
        val trimmed = text.trim().take(IN_CALL_TEXT_LIMIT)
        if (trimmed.isEmpty()) return
        send(kind = IN_CALL_KIND_MESSAGE, emoji = "", text = trimmed, nowMillis = now())
    }

    /** A line the engine received on its own channel, shown like a relayed one. */
    fun receive(incoming: InCallData) {
        val current = session() ?: return
        if (!remember(incoming.id)) return
        scope.launch { _data.emit(incoming.copy(roomId = incoming.roomId.ifBlank { current.callUuid })) }
    }

    /** Called when a call ends. Nothing said in one call reaches the next. */
    fun reset() {
        seen.clear()
        lastReactionAtMillis = 0L
    }

    private fun send(kind: String, emoji: String, text: String, nowMillis: Long) {
        val current = session() ?: return
        if (!connected()) return

        val outgoing = InCallData(
            roomId = current.roomId.ifBlank { current.callUuid },
            kind = kind,
            fromUserId = current.selfUserId,
            name = selfName().orEmpty(),
            emoji = emoji,
            text = text,
            id = newId(nowMillis),
            atMillis = nowMillis,
        )

        // Shown here first and unconditionally: the sender should see their own
        // line whether or not anyone is left to receive it.
        remember(outgoing.id)
        scope.launch { _data.emit(outgoing) }

        if (direct(outgoing)) return
        val recipients = recipientsOf(current)
        if (recipients.isEmpty()) return
        scope.launch {
            bus.emit(
                ZillitSocketEvents.Calls.Relay,
                inCallDataEnvelope(outgoing.roomId, outgoing, recipients),
                JsonObject.serializer(),
            )
        }
    }

    /**
     * The user rooms to address, one per person.
     *
     * The roster keys people by `userId:deviceId` so two devices of one person
     * are two tiles, but the CNC's rooms are per *user*. Sending the composite
     * id addresses a room nobody is in — which is how this kind of feature
     * works one-to-one and goes silent the moment a call has three people.
     */
    private fun recipientsOf(current: CallSession): List<String> =
        current.participants
            .asSequence()
            .map { it.userId.substringBefore(PEER_ID_SEPARATOR) }
            .filter { it.isNotBlank() && it != current.selfUserId }
            .distinct()
            .toList()

    /** Ours to show: this call, not from us, and not already seen. */
    private fun belongsHere(incoming: InCallData, current: CallSession): Boolean {
        val sameCall = incoming.roomId.isBlank() ||
            incoming.roomId == current.roomId ||
            incoming.roomId == current.callUuid
        val fromUs = incoming.fromUserId.isNotBlank() && incoming.fromUserId == current.selfUserId
        return sameCall && !fromUs
    }

    /** False when this id has been delivered already. Bounded, oldest evicted. */
    private fun remember(id: String): Boolean {
        if (id.isBlank()) return true
        if (id in seen) return false
        seen.addLast(id)
        if (seen.size > SEEN_IDS) seen.removeFirst()
        return true
    }

    /**
     * Unique across the fleet: device, clock, and a counter.
     *
     * The counter is what separates two sends inside the same millisecond —
     * possible for typed lines, where a collision would have the second one
     * silently swallowed by our own dedupe.
     */
    private fun newId(nowMillis: Long): String {
        sequence++
        return "${selfDeviceId().orEmpty()}-$nowMillis-$sequence"
    }

    companion object {
        /** The phones' outbound reaction throttle. */
        const val REACTION_MIN_GAP_MILLIS = 600L

        /** Their text cap too, so a desktop line cannot be truncated on arrival. */
        const val IN_CALL_TEXT_LIMIT = 1_000

        /** Enough to outlast any relay echo without growing for the call's life. */
        const val SEEN_IDS = 128

        /** Splits `userId:deviceId` peer keys back into a plain user id. */
        const val PEER_ID_SEPARATOR = ':'
    }
}
