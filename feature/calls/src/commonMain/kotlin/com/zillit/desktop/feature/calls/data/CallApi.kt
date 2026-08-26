package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.calls.data.protoo.TURN_CREDENTIALS_PATH
import com.zillit.desktop.feature.calls.data.protoo.TurnCredentials
import com.zillit.desktop.feature.calls.data.protoo.readTurnCredentials
import com.zillit.desktop.core.common.getOrElse
import com.zillit.desktop.core.common.map
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The calling REST surface.
 *
 * Paths are Android's `ApiUrl`, verbatim — the `call/` family is Line 2
 * (Agora) and the `mediasoup-call/` family Line 1, and the split is historical
 * rather than meaningful: `call-response` lives under the Mediasoup prefix but
 * is the generic "here is my status" endpoint.
 *
 * Every call goes out under [RequestModule.ProjectUser]. A call can belong to
 * a production other than the open one — an invite from a set you are not
 * currently looking at — so the project the headers name is taken from the
 * session rather than from app state, via [CallOptions.projectId].
 */
class CallApi(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) {

    private val base get() = config.apiV2(ZillitService.Calling)

    /**
     * Places a call and returns the session the server minted for it.
     *
     * [receiverDeviceId] rings one device; [chatRoomId] rings a room. The
     * server wants whichever is relevant and ignores the other, but sending a
     * blank string for the unused one is not the same as omitting it — a blank
     * receiver has been seen to produce a call nobody is invited to.
     */
    suspend fun createCall(
        chatRoomId: String,
        receiverDeviceId: String,
        mode: CallMode,
        type: CallType,
        selfUserId: String,
        selfDeviceId: String,
        projectId: String?,
    ): ZillitResult<CallSession?> {
        val body = buildJsonObject {
            put("call_mode", mode.wire)
            put("call_type", type.wire)
            put("has_video", type == CallType.Video)
            if (receiverDeviceId.isNotBlank()) put("receiver_device_id", receiverDeviceId)
            if (chatRoomId.isNotBlank()) put("chat_room_id", chatRoomId)
        }
        return post("call/new-call", body, projectId).map { data ->
            data?.let {
                readCallSession(it, selfUserId, selfDeviceId, CallDirection.Outgoing)
                    ?.copy(mode = mode, type = type)
            }
        }
    }

    /** Hangs up for everyone. The server broadcasts `call:ended` in response. */
    suspend fun endCall(
        callUuid: String,
        deviceId: String,
        projectId: String?,
        provider: CallProvider = CallProvider.Agora,
    ) = put("call/end-call", callRef(callUuid, deviceId, provider), projectId)

    /** Records that this device never picked up. */
    suspend fun logMissedCall(callUuid: String, deviceId: String, projectId: String?) =
        put("call/log-miss-call", callRef(callUuid, deviceId), projectId)

    /** The group form: everyone who let it ring out. */
    suspend fun logMissedCalls(callUuid: String, deviceIds: List<String>, projectId: String?) =
        put(
            "call/log-miss-call-multiple",
            buildJsonObject {
                put("call_uuid", callUuid)
                put("device_id", jsonArrayOf(deviceIds))
            },
            projectId,
        )

    /** Pulls someone else into a call already under way. */
    suspend fun addUser(
        callUuid: String,
        receiverDeviceId: String,
        type: CallType,
        projectId: String?,
        /** Our id in the call's production. See [send]. */
        callerUserId: String? = null,
    ) = put(
        "call/add-user",
        buildJsonObject {
            put("call_uuid", callUuid)
            put("receiver_device_id", receiverDeviceId)
            put("call_type", type.wire)
            // Same reason as the teardown: this path lives under the Agora
            // prefix and is only line-correct because of the field.
            put("line", CallProvider.Agora.wire)
        },
        projectId,
        userId = callerUserId,
    )

    /**
     * Pulls someone into a Line 1 call.
     *
     * A different endpoint and a different shape: the room rather than the
     * call, and a person rather than one of their devices. Worth being exact
     * about, because this handler answers a wrong body with HTTP 200 and
     * `{"success": false, "message": "call_not_found"}` — so a mistake here is
     * not an error anybody sees. It is an invitee who never rings, under a
     * roster row that sits there saying "ringing" for the rest of the call.
     */
    suspend fun addMediasoupUser(
        roomId: String,
        receiverUserId: String,
        type: CallType,
        callerUserId: String,
        projectId: String?,
    ) = put(
        "mediasoup-call/add-user",
        buildJsonObject {
            put("roomId", roomId)
            put("receiverId", receiverUserId)
            put("callType", type.wire)
            put("callerId", callerUserId)
        },
        projectId,
        // The same id the body names, in the header too: this endpoint refuses
        // a caller it cannot place in the production.
        userId = callerUserId,
    )

    /**
     * Reports our own status into the call.
     *
     * Fire-and-forget in intent but not in delivery: a status that never lands
     * leaves the other end ringing a phone that has already hung up, so this
     * is one of the few calls whose failure is worth surfacing.
     */
    suspend fun sendCallResponse(
        roomId: String,
        status: CallStatus,
        fromUserId: String,
        projectId: String?,
    ): ZillitResult<Unit> {
        if (roomId.isBlank() || fromUserId.isBlank()) {
            return ZillitResult.Success(Unit)
        }
        return post(
            "mediasoup-call/call-response",
            callResponseEnvelope(roomId, status, fromUserId),
            projectId,
            // The responder's own id in the call's production — the same
            // pairing iOS sends here.
            userId = fromUserId,
        ).map { }
    }

    /**
     * The server's own view of who is in a room.
     *
     * Fetched right after joining, because a joiner missed every `call:update`
     * emitted before it subscribed and there is no way to recover those from
     * the socket. Read leniently — this endpoint has no other client pinning
     * its shape, and `call_users` sits at one of two depths depending on which
     * handler answered.
     */
    suspend fun callDump(roomId: String, projectId: String?): ZillitResult<List<CallParticipant>> =
        apiClient.envelope(
            verb = HttpVerb.Get,
            url = "${base}mediasoup-call/call-dump/$roomId",
            module = RequestModule.ProjectUser,
            options = CallOptions(projectId = projectId),
        ).map { envelope ->
            val data = envelope.data as? JsonObject
            val users = data?.get("call_users")
                ?: (data?.get("call") as? JsonObject)?.get("call_users")
            readParticipants(users)
        }

    /**
     * Places a call on Line 1, the mediasoup SFU.
     *
     * A different endpoint rather than a flag on the Agora one, which is how
     * the phones do it — the two lines are separate call plumbing on the
     * server, not two settings of the same thing.
     *
     * The receivers are USER ids here, not device ids. Line 1 rings a person
     * across their devices and the SFU has no notion of the device-scoped
     * addressing Line 2 uses; passing a device id produces a call nobody is
     * invited to, with no error.
     */
    suspend fun createMediasoupCall(
        chatRoomId: String,
        receiverUserIds: List<String>,
        mode: CallMode,
        type: CallType,
        selfUserId: String,
        selfDeviceId: String,
        projectId: String?,
    ): ZillitResult<CallSession?> {
        val body = buildJsonObject {
            put("callerId", selfUserId)
            put("receiverIds", jsonArrayOf(receiverUserIds))
            put("callType", type.wire)
            put("userID", selfUserId)
            if (chatRoomId.isNotBlank()) put("chat_room_id", chatRoomId)
            // `p2p` is omitted deliberately: absent means "plain SFU request",
            // which is what this client can actually service. Declaring a P2P
            // capability we do not implement would have the server elect a
            // direct connection and then nobody answers it.
        }
        return post("mediasoup-call/initiate-call", body, projectId).map { data ->
            val session = data?.let {
                readCallSession(it, selfUserId, selfDeviceId, CallDirection.Outgoing)
            }
            // Everything the request asserted and the response may not echo.
            // The mode especially: this endpoint omits `call_mode`, which
            // reads back as Private, and a group call believed to be Private
            // is torn down for the caller by the FIRST person to decline while
            // the rest are still ringing. `hasVideo` the same — a video call
            // that reads back false joins with the camera off.
            session?.copy(
                mode = mode,
                type = type,
                hasVideo = session.hasVideo || type == CallType.Video,
                provider = CallProvider.Mediasoup,
            )
        }
    }

    /**
     * The relays Line 1's transports may use to cross a hostile network.
     *
     * Fetched per call and BEFORE any transport is created: a transport's ICE
     * policy is fixed when it is built, so credentials that arrive afterwards
     * do nothing for the call that needed them. The backend proxies
     * Cloudflare's generator, so the upstream token never reaches this client.
     *
     * Degrades to an empty set rather than failing the call: most people on
     * most networks connect without a relay, and refusing to dial because the
     * relay list could not be fetched would be the worse of the two failures.
     */
    suspend fun turnCredentials(projectId: String?, userId: String? = null): TurnCredentials =
        apiClient.envelope(
            verb = HttpVerb.Get,
            url = "$base$TURN_CREDENTIALS_PATH",
            module = RequestModule.ProjectUser,
            options = CallOptions(projectId = projectId, userId = userId?.takeIf(String::isNotBlank)),
        ).map { envelope -> readTurnCredentials(envelope.data as? JsonObject) }
            .getOrElse(TurnCredentials(emptyList(), TurnCredentials.DEFAULT_TTL_SECONDS))

    /**
     * A page of call history, newest first.
     *
     * Android's shape verbatim: `call/{cursor}/{previous|next}`, where the
     * cursor is a millisecond timestamp and the direction word says which side
     * of it to read. `missed` asks the server for the missed-only view rather
     * than filtering here, because the two are paginated separately and mixing
     * them would leave a page half-full.
     */
    suspend fun callLogs(
        cursorMillis: Long,
        selfUserId: String,
        missedOnly: Boolean = false,
        older: Boolean = true,
        /**
         * True when [cursorMillis] is "now" — the first page. Its URL differs
         * on every load, so it is kept under one name for the read cache;
         * later pages read back from a row's own timestamp and key themselves.
         */
        newestPage: Boolean = false,
    ): ZillitResult<List<CallLogEntry>> {
        val direction = if (older) "previous" else "next"
        val query = if (missedOnly) "?missed=yes" else ""
        return apiClient.envelope(
            verb = HttpVerb.Get,
            url = "${base}call/$cursorMillis/$direction$query",
            module = RequestModule.ProjectUser,
            // No project override: history is the open production's, unlike a
            // live call which carries its own.
            options = CallOptions(
                projectId = null,
                cacheAs = if (newestPage) "${base}call/newest/$direction$query" else null,
            ),
        ).map { envelope -> readCallLogs(envelope.data ?: JsonObject(emptyMap()), selfUserId) }
    }

    /**
     * Wipes the recent-calls history for this user on the open production.
     *
     * Android's `RecentMissedVM.deleteCallLogs` (`:236-259`) — a bodiless
     * `DELETE ${DELETE_CALL_LOGS}/recent` under the project-user headers.
     * Its `DELETE_CALL_LOGS` is `${CALL_BASE_URL}/call` (`ApiUrl.kt:555`),
     * one slash more than `GET_CALL_LOGS` on the line above; the server
     * tolerates the double, but the single spelling is what the path is.
     */
    suspend fun deleteRecentCallLogs(): ZillitResult<Unit> = deleteLogs("recent")

    /**
     * Wipes the missed-calls history — the same call for the missed view.
     * Android's Recent tab sends both; its Missed tab only this one
     * (`RecentCallFragment.kt:203-209`).
     */
    suspend fun deleteMissedCallLogs(): ZillitResult<Unit> = deleteLogs("missed")

    private suspend fun deleteLogs(which: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = "${base}call/$which",
            module = RequestModule.ProjectUser,
            // History is the open production's — see callLogs.
            options = CallOptions(projectId = null),
        ).map { }

    /** Admits or refuses a guest waiting outside a room. */
    suspend fun respondToGuest(
        roomId: String,
        requestId: String,
        admit: Boolean,
        respondedBy: String,
        projectId: String?,
    ) = put(
        "mediasoup-call/guest-join-request/respond",
        buildJsonObject {
            put("room_id", roomId)
            put("request_id", requestId)
            put("response", if (admit) "accepted" else "rejected")
            put("responded_by", respondedBy)
        },
        projectId,
    )

    /**
     * The three things every call-scoped write names.
     *
     * `line` is not decoration: `call/end-call` and its siblings live under the
     * Agora prefix and are only line-correct BECAUSE of this field. Omitting it
     * has the backend resolve a mediasoup teardown on the Agora line, which
     * ends nothing — the SFU room survives the hang-up.
     */
    private fun callRef(
        callUuid: String,
        deviceId: String,
        provider: CallProvider = CallProvider.Agora,
    ): JsonObject = buildJsonObject {
        put("call_uuid", callUuid)
        put("device_id", deviceId)
        put("line", provider.wire)
    }

    private suspend fun post(
        path: String,
        body: JsonObject,
        projectId: String?,
        userId: String? = null,
    ) = send(HttpVerb.Post, path, body, projectId, userId)

    private suspend fun put(
        path: String,
        body: JsonObject,
        projectId: String?,
        userId: String? = null,
    ) = send(HttpVerb.Put, path, body, projectId, userId)

    /**
     * [userId] is the caller's id ON [projectId], for a call about a
     * production that is not the open one.
     *
     * User ids are project-scoped, and the header's project and user are set
     * together from the ACTIVE production — so overriding only the project
     * ships a valid project paired with a user id that does not exist in it,
     * and the server refuses the write. Blank means "use the ambient pair",
     * which is right for a call in the open production and is what every one
     * of these did before; an empty string is never sent.
     */
    private suspend fun send(
        verb: HttpVerb,
        path: String,
        body: JsonObject,
        projectId: String?,
        userId: String? = null,
    ): ZillitResult<JsonElement?> =
        apiClient.envelope(
            verb = verb,
            url = "$base$path",
            module = RequestModule.ProjectUser,
            body = body,
            options = CallOptions(projectId = projectId, userId = userId?.takeIf(String::isNotBlank)),
        ).map { it.data }
}

private fun jsonArrayOf(values: List<String>): JsonArray =
    JsonArray(values.map(::JsonPrimitive))

/**
 * Places a call on whichever line the caller chose.
 *
 * Two endpoints, not one with a flag, because that is what the server offers:
 * the lines are separate call plumbing, and they do not even address the callee
 * the same way — Line 1 rings a person by user id, Line 2 rings one device. An
 * extension rather than a coordinator method so the choice sits beside the two
 * calls it is choosing between.
 */
internal suspend fun CallApi.createOnLine(request: NewCall): ZillitResult<CallSession?> =
    if (request.provider == CallProvider.Mediasoup) {
        createMediasoupCall(
            chatRoomId = request.chatRoomId,
            receiverUserIds = listOfNotNull(request.receiverUserId.takeIf(String::isNotBlank)),
            mode = request.mode,
            type = request.type,
            selfUserId = request.selfUserId,
            selfDeviceId = request.selfDeviceId,
            projectId = null,
        )
    } else {
        createCall(
            chatRoomId = request.chatRoomId,
            receiverDeviceId = request.receiverDeviceId,
            mode = request.mode,
            type = request.type,
            selfUserId = request.selfUserId,
            selfDeviceId = request.selfDeviceId,
            projectId = null,
        )
    }

/** Everything the two create endpoints need between them. */
internal data class NewCall(
    val provider: CallProvider,
    val chatRoomId: String,
    /** Line 2 addressing: one device. */
    val receiverDeviceId: String,
    /** Line 1 addressing: a person, across their devices. */
    val receiverUserId: String,
    val mode: CallMode,
    val type: CallType,
    val selfUserId: String,
    val selfDeviceId: String,
)

/**
 * Files the misses, on the one line that wants them told.
 *
 * Agora only: the mediasoup backend derives missed calls from the roster
 * itself when a call ends, so posting them here files a second, phantom row
 * against the same call — and these two paths live under the Agora prefix
 * regardless. iOS gates the same block on `isAgora` for the same reason.
 */
internal suspend fun CallApi.logMissed(
    session: CallSession,
    ids: List<String>,
    projectId: String?,
) {
    if (session.provider != CallProvider.Agora || ids.isEmpty()) return
    if (ids.size == 1) {
        logMissedCall(session.callUuid, ids.first(), projectId)
    } else {
        logMissedCalls(session.callUuid, ids, projectId)
    }
}

/**
 * Invites one more person, on whichever line the call is running.
 *
 * The two endpoints do not agree on anything: Line 2 names the call and one
 * device, Line 1 names the room and a person. Getting it wrong on Line 1 is
 * silent — that handler answers a bad body with HTTP 200 and `success: false`
 * — so the branch is here rather than at the call site, beside the two
 * requests it is choosing between.
 */
internal suspend fun CallApi.invite(
    session: CallSession,
    userId: String,
    deviceId: String,
): ZillitResult<JsonElement?> {
    val projectId = session.projectId.takeIf(String::isNotBlank)
    return if (session.provider == CallProvider.Mediasoup) {
        addMediasoupUser(
            // The REST election, not the dial's — see CallSession.restRoomId.
            roomId = session.restRoomId,
            receiverUserId = userId,
            type = session.type,
            callerUserId = session.selfUserId,
            projectId = projectId,
        )
    } else {
        addUser(
            callUuid = session.callUuid,
            receiverDeviceId = deviceId,
            type = session.type,
            projectId = projectId,
            // Pinned from the ring's `receiver_user_id`, which is our id in
            // the call's production rather than in whichever one is open.
            callerUserId = session.selfUserId,
        )
    }
}
