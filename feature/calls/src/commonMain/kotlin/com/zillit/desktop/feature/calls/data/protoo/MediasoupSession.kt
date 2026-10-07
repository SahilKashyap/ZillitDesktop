package com.zillit.desktop.feature.calls.data.protoo

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.EngineConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** Whatever carries protoo frames. Implemented by the OkHttp socket. */
interface ProtooSignalling {
    /** [url] is asked for on every dial, so a redial can change the peer id. */
    fun dial(url: () -> String)
    fun send(frame: String): Boolean
    fun close(reason: String)
}

/** The page's media module, as this side needs it. */
interface MediasoupPage {
    suspend fun load(routerRtpCapabilities: JsonObject)
    suspend fun createTransports(send: JsonObject, recv: JsonObject, iceServers: String)
    suspend fun produceMic(deviceId: String)
    suspend fun produceCam(deviceId: String)
    suspend fun consume(params: JsonObject)
    suspend fun closeConsumer(consumerId: String)
    suspend fun settle(askId: Long, ok: Boolean, payload: JsonObject)
    suspend fun leave()
}

/**
 * One Line 1 call, from dial to teardown.
 *
 * Sits between the signalling socket (Kotlin, because protoo needs ping frames)
 * and the media page (Chromium, because mediasoup needs WebRTC), and exists so
 * the ORDER of a join is written down in one testable place. The order is not
 * incidental:
 *
 *  - TURN before transports, because a transport's ICE policy is fixed when it
 *    is built and late credentials help nobody.
 *  - Both transports before `join`, because the SFU starts offering consumers
 *    the moment `join` lands and an offer has nowhere to go without a receive
 *    transport.
 *  - The two `createWebRtcTransport` calls concurrently, because they are
 *    independent round-trips and doing them in sequence adds a whole RTT to
 *    the one exchange a user actually waits through.
 */
/*
 * The function count IS the protocol: one member per protoo exchange the SFU
 * speaks, plus the page seams. A split would put half the wire vocabulary in a
 * second object that must be kept in step with this one by hand.
 */
@Suppress("TooManyFunctions")
class MediasoupSession(
    private val scope: CoroutineScope,
    private val peer: ProtooPeer,
    private val signalling: ProtooSignalling,
    private val page: MediasoupPage,
    /** Fetched once per call, before any transport exists. */
    private val turn: suspend () -> TurnCredentials,
    private val emit: suspend (CallEngineEvent) -> Unit,
) {
    private var joined = false

    /** The id the NEXT dial will use. Freshened as each one is handed out. */
    private var peerId: String = ""

    /** The identity half of the FIRST peer id — rejoins only freshen the device half. */
    private var selfIdentity: String = ""

    /** The room this session dialled, for the Joined event and recording notices. */
    private var roomId: String = ""

    /** Set once the page reports its capabilities; the join waits on it. */
    private var deviceCaps: JsonObject? = null
    private var sctpCaps: JsonObject? = null

    /**
     * What each live consumer carries, so a `consumerClosed`/`consumerPaused`
     * — which names only the consumer — can be turned back into a fact about
     * a peer's tile.
     */
    private data class ConsumerInfo(val peerId: String, val kind: String, val share: Boolean)

    private val consumersById = mutableMapOf<String, ConsumerInfo>()

    /**
     * Consumers the SFU paused — or offered already paused — before the page
     * finished taking them on.
     *
     * `consumerPaused` names a consumer this side may not have registered yet:
     * consuming is a round trip through the page, and the SFU does not wait
     * for it. Dropped, as it was, the pause is simply lost and the tile shows
     * a camera that is off. Kept here, [onPageConsumer] applies it the moment
     * the consumer appears — the web's `_pendingPausedConsumers` (ZL-19613),
     * which this side needs more, not less: its consume round trip is longer
     * than the browser's by a whole bridge.
     */
    private val pausedBeforeRegistered = mutableSetOf<String>()

    /** Live peer ids behind each uid; a uid leaves only with its last peer. */
    private val peersByUid = mutableMapOf<Int, MutableSet<String>>()

    private fun rememberPeer(peerId: String) {
        if (mediasoupIdentityOf(peerId) == selfIdentity) return
        peersByUid.getOrPut(uidOf(peerId)) { mutableSetOf() }.add(peerId)
    }

    /**
     * Opens the socket. The join proper begins when it connects.
     *
     * The URL is recomputed per dial: the first uses the peer id the call was
     * invited under, and every redial a freshened one, because rejoining under
     * the old id makes the SFU evict the peer and tell the remote the call is
     * over.
     */
    fun dial(params: com.zillit.desktop.feature.calls.domain.CallJoin) {
        if (params.sfuHost.isBlank() || params.roomId.isBlank()) {
            scope.launch { emit(CallEngineEvent.Failed("no SFU host or room for this call")) }
            return
        }
        peerId = params.peerId
        selfIdentity = mediasoupIdentityOf(params.peerId)
        roomId = params.roomId
        signalling.dial {
            val id = peerId
            // Freshened for the next attempt, so this one is used exactly once.
            peerId = mediasoupRejoinPeerId(id)
            protooDialUrl(params.sfuHost, params.roomId, id, params.sfuToken)
        }
    }

    /**
     * Prepares for a rejoin after the socket came back.
     *
     * Everything media is torn down and rebuilt rather than resumed: a
     * mediasoup device can only be loaded once, so a second join needs a
     * second device, and the transports and producers belong to the session
     * that just died. Cheaper approaches exist — the phones probe with an ICE
     * restart first and keep their producers if it answers — but a full rejoin
     * always works, and correctness before cleverness on a path that cannot be
     * exercised without a live server.
     */
    suspend fun resetForRejoin() {
        // Retires whatever join is still in flight. See [joinGeneration].
        joinGeneration++
        joined = false
        deviceCaps = null
        sctpCaps = null
        consumersById.clear()
        pausedBeforeRegistered.clear()
        peersByUid.clear()
        page.leave()
    }

    /**
     * Which join attempt owns the call.
     *
     * Bumped by every [resetForRejoin], and captured by each [join] so a join
     * that a redial has already superseded can recognise itself and stay
     * quiet. Without it the loser of that race gets the last word.
     */
    private var joinGeneration = 0

    /**
     * The join sequence, once the socket is up.
     *
     * Every step can fail, and a failure here is a call that does not connect
     * — so each one says which step it was rather than surfacing a bare
     * protoo code that means nothing to anybody reading a log later.
     */
    suspend fun join(displayName: String, microphoneId: String, withVideo: Boolean) {
        if (joined) return
        joined = true
        val generation = joinGeneration
        try {
            val caps = step("router capabilities") {
                peer.request(MediasoupJoin.GET_ROUTER_CAPABILITIES)
            }
            page.load(caps)

            // The page answers with its own capabilities, which the join needs.
            val ready = awaitDeviceCaps()
                ?: throw ProtooError(CODE_PAGE, "the media page never reported its capabilities")

            // Before the transports, deliberately. See the class comment.
            val relays = runCatching { turn() }.getOrElse { thrown ->
                ZillitLog.w(TAG) { "no turn credentials: ${thrown.message}" }
                TurnCredentials(emptyList(), TurnCredentials.DEFAULT_TTL_SECONDS)
            }

            val sendParams = scope.async {
                peer.request(MediasoupJoin.CREATE_TRANSPORT, MediasoupJoin.createTransport(true, sctpCaps))
            }
            val recvParams = scope.async {
                peer.request(MediasoupJoin.CREATE_TRANSPORT, MediasoupJoin.createTransport(false, sctpCaps))
            }
            page.createTransports(sendParams.await(), recvParams.await(), relays.toJson().toString())

            val joinReply = step("join") {
                peer.request(MediasoupJoin.JOIN, MediasoupJoin.join(displayName, ready, sctpCaps))
            }
            announcePeersFromJoin(joinReply)

            page.produceMic(microphoneId)
            // Only when the call is a video one. Publishing a camera nobody
            // asked for lights the user's camera indicator on an audio call,
            // which reads as the app spying on them.
            if (withVideo) page.produceCam("")
            emit(CallEngineEvent.ConnectionChanged(EngineConnection.Connected))
            // The coordinator's post-join work — the roster fetch above all —
            // hangs off Joined, and `media.channel` staying blank reads as
            // "signalling ran but media never joined", which puts a permanent
            // "No audio on this call" banner over a call that has plenty.
            // uid 0 on purpose: Line 1 has no server-issued numeric uid, and
            // announcing a synthetic one would be mirrored into Firestore as
            // `agora_uid` where every other platform treats it as Agora's.
            emit(CallEngineEvent.Joined(channel = roomId, uid = 0))
        } catch (error: ProtooError) {
            // A join abandoned by a redial must not speak. The socket dropping
            // mid-join leaves this coroutine suspended on a request nobody will
            // ever answer; the redial resets and starts a join that can — and
            // succeed it often does, before this one's ten-second timer gives
            // up. Reporting then would end a call that is already back, which
            // is the worst possible answer: the user watches a working call
            // die for a reason that has expired.
            if (generation != joinGeneration) {
                ZillitLog.i(TAG) { "join $generation abandoned (${error.reason}); a later one owns the call" }
                return
            }
            ZillitLog.w(TAG) { "join failed: ${error.code} ${error.reason}" }
            emit(CallEngineEvent.Failed(error.reason.ifBlank { "could not join the call" }))
        }
    }

    /** The page reported what it can do. */
    fun onPageLoaded(rtpCapabilities: JsonObject, sctpCapabilities: JsonObject?) {
        deviceCaps = rtpCapabilities
        sctpCaps = sctpCapabilities
    }

    /**
     * The page needs the SFU consulted and cannot continue without an answer.
     *
     * Answered on this side's scope rather than inline: the page is blocked on
     * a promise, and holding the bridge thread through a network round-trip
     * would stall every other message behind it.
     */
    fun onPageAsk(askId: Long, method: String, data: JsonObject) {
        scope.launch {
            try {
                val reply = peer.request(method, data)
                page.settle(askId, ok = true, payload = reply)
            } catch (error: ProtooError) {
                ZillitLog.w(TAG) { "$method failed: ${error.code} ${error.reason}" }
                page.settle(
                    askId,
                    ok = false,
                    payload = buildJsonObject { put("reason", error.reason) },
                )
            }
        }
    }

    /**
     * A protoo REQUEST from the server. Only two exist, and both must be
     * answered — the SFU abandons a `newConsumer` nobody replies to, which is
     * a track that never arrives and no error anywhere.
     */
    suspend fun onServerRequest(request: ProtooMessage.Request) {
        when (request.method) {
            MediasoupNotification.NEW_CONSUMER -> {
                // Accepted FIRST, before any media work. The SFU's patience is
                // shorter than the browser's consume().
                peer.accept(request.id)
                val consumerId = request.data.text("id")
                // The offer's own snapshot of the producer: a peer whose camera
                // was already off when we started consuming them. Ignoring it
                // (as this did) draws them camera-ON over a track that carries
                // nothing — the web reads the same field, `data.producerPaused`.
                if (consumerId != null && request.data.flag("producerPaused")) {
                    pausedBeforeRegistered += consumerId
                }
                page.consume(request.data)
                resumeConsumer(consumerId)
            }

            MediasoupNotification.NEW_DATA_CONSUMER -> peer.accept(request.id)

            else -> peer.reject(
                request.id,
                ProtooPeer.CODE_UNKNOWN_METHOD,
                "unknown method: ${request.method}",
            )
        }
    }

    /**
     * Un-pauses a consumer the SFU created paused.
     *
     * Every consumer arrives paused and forwards nothing until this lands —
     * without it a call connects, shows everybody, and carries no audio at
     * all. Retried because losing this one request costs the whole track, and
     * fire-and-forget because there is nothing useful to do with a final
     * failure that the media watchdogs will not already notice.
     */
    private fun resumeConsumer(consumerId: String?) {
        if (consumerId.isNullOrBlank()) {
            ZillitLog.w(TAG) { "a consumer arrived with no id; it will stay paused" }
            return
        }
        scope.launch {
            repeat(RESUME_ATTEMPTS) { attempt ->
                val outcome = runCatching {
                    peer.request(MediasoupJoin.RESUME_CONSUMER, MediasoupJoin.consumerId(consumerId))
                }
                if (outcome.isSuccess) return@launch
                kotlinx.coroutines.delay((attempt + 1) * RESUME_BACKOFF_MILLIS)
            }
            ZillitLog.w(TAG) { "consumer $consumerId never resumed; that track will be silent" }
        }
    }

    /** What the SFU tells us, unasked. */
    @Suppress("CyclomaticComplexMethod")
    suspend fun onNotification(note: ProtooMessage.Notification) {
        when (note.method) {
            MediasoupNotification.NEW_PEER -> note.text("id", "peerId")?.let {
                // The peer id is `user:device`; its user half is what a roster
                // row must carry for the stream to land on that person's tile.
                ZillitLog.i(TAG) { "peer joined $it uid=${uidOf(it)}" }
                rememberPeer(it)
                val name = note.text("displayName").orEmpty()
                emitForPeer(it) { uid -> CallEngineEvent.PeerJoined(uid, it, displayName = name) }
            }

            MediasoupNotification.PEER_CLOSED -> note.peerId()?.let { closed ->
                ZillitLog.i(TAG) { "peer closed $closed uid=${uidOf(closed)}" }
                // Their consumers are gone with them. The SFU's `consumerClosed`
                // for each can arrive AFTER this, and finding the consumer still
                // registered it reported a camera-off for the uid — which
                // re-created the peer that had just left: a stuck face, and a
                // room that never read empty.
                consumersById.entries.filter { it.value.peerId == closed }.forEach { (id, _) ->
                    page.closeConsumer(id)
                    consumersById.remove(id)
                }
                // One person, two peers — a second device, or the old socket
                // of a reconnect closing after the new one is live. They are
                // still here until the last of them goes (the web's rule).
                val stillHere = peersByUid[uidOf(closed)]?.apply { remove(closed) }?.isNotEmpty() == true
                if (stillHere) return
                peersByUid.remove(uidOf(closed))
                emitForPeer(closed) { uid -> CallEngineEvent.PeerLeft(uid) }
            }

            MediasoupNotification.ACTIVE_SPEAKER -> note.peerId()?.let {
                emit(CallEngineEvent.ActiveSpeakers(listOf(uidOf(it))))
            }

            MediasoupNotification.CONSUMER_CLOSED -> note.consumerId()?.let { onConsumerClosed(it) }
            MediasoupNotification.CONSUMER_PAUSED -> note.consumerId()?.let { onConsumerPaused(it, true) }
            MediasoupNotification.CONSUMER_RESUMED -> note.consumerId()?.let { onConsumerPaused(it, false) }

            MediasoupNotification.PEER_RAISED_HAND -> note.peerId()?.let {
                emitForPeer(it) { _ -> CallEngineEvent.PeerHand(mediasoupIdentityOf(it), raised = true) }
            }

            MediasoupNotification.PEER_LOWERED_HAND -> note.peerId()?.let {
                emitForPeer(it) { _ -> CallEngineEvent.PeerHand(mediasoupIdentityOf(it), raised = false) }
            }

            MediasoupNotification.PEER_SCREEN_SHARE_STARTED -> note.peerId()?.let {
                emitForPeer(it) { uid -> CallEngineEvent.PeerScreenShare(uid, sharing = true) }
            }

            MediasoupNotification.PEER_SCREEN_SHARE_STOPPED -> note.peerId()?.let {
                emitForPeer(it) { uid -> CallEngineEvent.PeerScreenShare(uid, sharing = false) }
            }

            // Both spellings of "someone is recording": the dedicated pair,
            // and iOS's richer `recording` notification whose `peerId` is the
            // USER id on this line (its ZL-18123 fix documents why).
            MediasoupNotification.PEER_RECORDING_STARTED -> note.peerId()?.let {
                emitForPeer(it) { _ -> CallEngineEvent.PeerRecording(mediasoupIdentityOf(it), recording = true) }
            }

            MediasoupNotification.PEER_RECORDING_STOPPED -> note.peerId()?.let {
                emitForPeer(it) { _ -> CallEngineEvent.PeerRecording(mediasoupIdentityOf(it), recording = false) }
            }

            MediasoupNotification.RECORDING -> {
                val who = note.text("peerId", "userId") ?: return
                val recording = (note.data["recording"] as? JsonPrimitive)?.contentOrNull == "true"
                emitForPeer(who) { _ ->
                    CallEngineEvent.PeerRecording(mediasoupIdentityOf(who), recording)
                }
            }

            MediasoupNotification.PEER_IN_CALL_DATA ->
                (note.data["data"] as? JsonObject)?.let { emit(CallEngineEvent.RoomData(it.toString())) }

            MediasoupNotification.END_CALL ->
                emit(CallEngineEvent.ConnectionChanged(EngineConnection.Disconnected))

            else -> ZillitLog.d(TAG) { "unhandled notification ${note.method}" }
        }
    }

    /**
     * The page took on one consumer. Registered so the close/pause
     * notifications — which name only the consumer — can find the peer again,
     * and surfaced so the tiles learn about the track.
     */
    suspend fun onPageConsumer(consumerId: String, peerId: String, kind: String, share: Boolean) {
        if (consumerId.isBlank() || peerId.isBlank()) return
        // Peers already in the room when we joined never send `newPeer`; their
        // first consumer is the first this side hears of them.
        if (consumersById.values.none { it.peerId == peerId }) {
            ZillitLog.i(TAG) { "first $kind from $peerId uid=${uidOf(peerId)}" }
        }
        consumersById[consumerId] = ConsumerInfo(peerId, kind, share)
        rememberPeer(peerId)
        emitForPeer(peerId) { uid -> CallEngineEvent.PeerJoined(uid, peerId, withMedia = true) }
        // Everything the SFU said about this consumer before the page had it:
        // the offer's `producerPaused`, and any `consumerPaused` that beat the
        // round trip. Both mean the same thing to a tile — nothing is coming.
        val startedPaused = pausedBeforeRegistered.remove(consumerId)
        when {
            share -> emitForPeer(peerId) { uid -> CallEngineEvent.PeerScreenShare(uid, sharing = true) }
            kind == "video" -> {
                if (startedPaused) ZillitLog.i(TAG) { "$peerId's camera was already off when we consumed it" }
                emitForPeer(peerId) { uid -> CallEngineEvent.PeerVideoMuted(uid, muted = startedPaused) }
            }
        }
    }

    /**
     * A remote track started or stopped carrying frames.
     *
     * The page's own reading, and the only one that cannot be lost in transit
     * — so it OVERRIDES whatever the notifications left behind. A camera that
     * is sending must never sit behind a tile that says it is off, which is
     * the bug this answers: the state said off, the packets said otherwise,
     * and only the remote toggling their camera ever settled it.
     */
    suspend fun onPageTrackState(consumerId: String, peerId: String, kind: String, muted: Boolean) {
        val who = consumersById[consumerId]?.peerId ?: peerId
        if (who.isBlank() || kind != "video") return
        if (consumersById[consumerId]?.share == true) return
        ZillitLog.i(TAG) { "$who's video track ${if (muted) "stopped" else "is"} carrying frames" }
        emitForPeer(who) { uid -> CallEngineEvent.PeerVideoMuted(uid, muted) }
    }

    private suspend fun onConsumerClosed(consumerId: String) {
        page.closeConsumer(consumerId)
        pausedBeforeRegistered -= consumerId
        val info = consumersById.remove(consumerId) ?: return
        when {
            info.share ->
                emitForPeer(info.peerId) { uid -> CallEngineEvent.PeerScreenShare(uid, sharing = false) }
            // Only when nothing of theirs is left to watch. A camera-off
            // because ONE consumer closed is wrong whenever a peer has a
            // second: a producer replaced — which is how the web and the
            // phones turn a camera back on, close then produce — closes the
            // old consumer after the new one is already live, and that
            // out-of-order close used to leave their live camera hidden.
            info.kind == "video" && !hasVideoConsumer(info.peerId) ->
                emitForPeer(info.peerId) { uid -> CallEngineEvent.PeerVideoMuted(uid, muted = true) }
        }
    }

    /** Is any camera consumer of [peerId] still live? */
    private fun hasVideoConsumer(peerId: String): Boolean =
        consumersById.values.any { it.peerId == peerId && it.kind == "video" && !it.share }

    /**
     * The holder paused or resumed a track server-side — this is how a Line 1
     * mute and camera-off actually reach other peers (iOS's map: the
     * `muteAudio` notification carries an unmatchable id there; consumer
     * pause/resume is the signal that works).
     */
    private suspend fun onConsumerPaused(consumerId: String, paused: Boolean) {
        val info = consumersById[consumerId]
        if (info == null) {
            // It names a consumer the page has not finished taking on. Kept
            // rather than dropped, and applied at registration; a resume
            // cancels a pause still waiting there.
            if (paused) pausedBeforeRegistered += consumerId else pausedBeforeRegistered -= consumerId
            return
        }
        when (info.kind) {
            "audio" -> emitForPeer(info.peerId) { uid -> CallEngineEvent.PeerAudioMuted(uid, paused) }
            "video" -> if (!info.share) {
                emitForPeer(info.peerId) { uid -> CallEngineEvent.PeerVideoMuted(uid, paused) }
            }
        }
    }

    /** This user's hand, as the protoo request the phones send. Fire-and-forget. */
    fun sendHandRaise(raised: Boolean) {
        scope.launch {
            runCatching {
                peer.request(MediasoupJoin.TOGGLE_HAND_RAISE, MediasoupJoin.handRaise(raised))
            }.onFailure { ZillitLog.w(TAG) { "toggleHandRaise not delivered: ${it.message}" } }
        }
    }

    /**
     * Announces this machine's screen share to the room. Tagging the producer
     * is invisible to iOS and Android, which only learn of a share from the
     * SFU's `peerScreenShareStarted` — the web sends these for exactly that.
     * Fire and forget: an SFU without the handler must never break the share.
     */
    fun announceScreenShare(sharing: Boolean) {
        scope.launch {
            runCatching {
                peer.request(
                    if (sharing) MediasoupJoin.START_SCREEN_SHARE else MediasoupJoin.STOP_SCREEN_SHARE,
                    buildJsonObject { },
                )
            }.onFailure { ZillitLog.w(TAG) { "screen share announcement not delivered: ${it.message}" } }
        }
    }

    /** One in-call item to every other peer over the SFU; see [CallEngine.sendRoomData]. Fire and forget. */
    fun sendInCallData(payload: JsonObject) {
        scope.launch {
            runCatching {
                peer.request(MediasoupJoin.SEND_IN_CALL_DATA, buildJsonObject { put("data", payload) })
            }.onFailure { ZillitLog.w(TAG) { "sendInCallData not delivered: ${it.message}" } }
        }
    }

    /** This machine started or stopped recording — request AND notification, as iOS sends. */
    fun sendRecording(recording: Boolean) {
        val userId = selfIdentity
        // The device half, without any rejoin suffix a redial appended.
        val deviceId = mediasoupDeviceOf(peerId).substringBefore("-r-")
        scope.launch {
            peer.notify(
                MediasoupJoin.RECORDING_NOTIFY,
                MediasoupJoin.recordingNotification(userId, deviceId, roomId, recording),
            )
            runCatching {
                peer.request(
                    if (recording) MediasoupJoin.START_CLIENT_RECORDING else MediasoupJoin.STOP_CLIENT_RECORDING,
                    MediasoupJoin.clientRecording(userId, deviceId, recording),
                )
            }.onFailure { ZillitLog.w(TAG) { "clientRecording not delivered: ${it.message}" } }
        }
    }

    /**
     * The room as `join` answered it: who is already here, and which flags
     * they hold. A joiner missed every notification sent before it arrived,
     * and this snapshot is the only recovery — dropping it is how a hand
     * raised before we joined stays invisible until it moves again.
     */
    private suspend fun announcePeersFromJoin(reply: JsonObject) {
        val peers = reply["peers"] as? JsonArray ?: return
        peers.forEach { entry ->
            val row = entry as? JsonObject ?: return@forEach
            val id = row.text("id") ?: return@forEach
            if (mediasoupIdentityOf(id) == selfIdentity) return@forEach
            val uid = uidOf(id)
            rememberPeer(id)
            emit(CallEngineEvent.PeerJoined(uid, id, displayName = row.text("displayName").orEmpty()))
            // The phones write these flags under several spellings; read them all.
            if (row.flag("raisedHand", "raise_hand")) {
                emit(CallEngineEvent.PeerHand(mediasoupIdentityOf(id), raised = true))
            }
            if (row.flag("screenShare", "sharingScreen", "screen_share", "sharing_screen")) {
                emit(CallEngineEvent.PeerScreenShare(uid, sharing = true))
            }
        }
    }

    /** Emits one event about a peer, skipping our own echoes. */
    private suspend fun emitForPeer(peerId: String, build: (Int) -> CallEngineEvent) {
        if (mediasoupIdentityOf(peerId) == selfIdentity) return
        emit(build(uidOf(peerId)))
    }

    suspend fun leave(reason: String = "left") {
        joined = false
        consumersById.clear()
        pausedBeforeRegistered.clear()
        peersByUid.clear()
        page.leave()
        peer.close(reason)
        signalling.close(reason)
    }

    /**
     * Names the step a failure happened in.
     *
     * A bare protoo code tells whoever reads the log later nothing at all;
     * "join: room is full" tells them where the call died. The original is
     * kept as the cause rather than replaced.
     */
    private suspend fun <T> step(name: String, block: suspend () -> T): T =
        try {
            block()
        } catch (error: ProtooError) {
            throw ProtooError(error.code, "$name: ${error.reason}", cause = error)
        }

    /**
     * The page's capabilities, which arrive as an event rather than a return.
     *
     * Polled rather than awaited on a deferred because the page can report
     * them before this coroutine gets here, and a deferred created afterwards
     * would wait for an event that has already happened.
     */
    private suspend fun awaitDeviceCaps(): JsonObject? {
        repeat(CAPS_POLLS) {
            deviceCaps?.let { return it }
            kotlinx.coroutines.delay(CAPS_POLL_MILLIS)
        }
        return deviceCaps
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** True when any of [keys] holds a truthy flag, however the sender spelt it. */
    private fun JsonObject.flag(vararg keys: String): Boolean = keys.any { key ->
        when (val value = (this[key] as? JsonPrimitive)?.contentOrNull) {
            null -> false
            else -> value == "true" || value == "1"
        }
    }

    private fun ProtooMessage.Notification.peerId(): String? = text("peerId")

    private fun ProtooMessage.Notification.consumerId(): String? = text("consumerId")

    private fun ProtooMessage.Notification.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull {
            (data[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        }

    /** One shared derivation with the tile builder — see [mediasoupUidOf]. */
    private fun uidOf(peerId: String): Int = mediasoupUidOf(mediasoupIdentityOf(peerId))

    private companion object {
        const val CODE_PAGE = -3

        /** The phones' ladder: three tries, half a second apart and growing. */
        const val RESUME_ATTEMPTS = 3
        const val RESUME_BACKOFF_MILLIS = 500L
        const val CAPS_POLLS = 100
        const val CAPS_POLL_MILLIS = 50L
    }
}

private const val TAG = "MediasoupSession"
