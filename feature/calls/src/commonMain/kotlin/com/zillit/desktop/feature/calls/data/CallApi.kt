package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallLogEntry
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
    suspend fun endCall(callUuid: String, deviceId: String, projectId: String?) =
        put("call/end-call", callRef(callUuid, deviceId), projectId)

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
    ) = put(
        "call/add-user",
        buildJsonObject {
            put("call_uuid", callUuid)
            put("receiver_device_id", receiverDeviceId)
            put("call_type", type.wire)
        },
        projectId,
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

    private fun callRef(callUuid: String, deviceId: String): JsonObject = buildJsonObject {
        put("call_uuid", callUuid)
        put("device_id", deviceId)
    }

    private suspend fun post(path: String, body: JsonObject, projectId: String?) =
        send(HttpVerb.Post, path, body, projectId)

    private suspend fun put(path: String, body: JsonObject, projectId: String?) =
        send(HttpVerb.Put, path, body, projectId)

    private suspend fun send(
        verb: HttpVerb,
        path: String,
        body: JsonObject,
        projectId: String?,
    ): ZillitResult<JsonElement?> =
        apiClient.envelope(
            verb = verb,
            url = "$base$path",
            module = RequestModule.ProjectUser,
            body = body,
            options = CallOptions(projectId = projectId),
        ).map { it.data }
}

private fun jsonArrayOf(values: List<String>): JsonArray =
    JsonArray(values.map(::JsonPrimitive))
